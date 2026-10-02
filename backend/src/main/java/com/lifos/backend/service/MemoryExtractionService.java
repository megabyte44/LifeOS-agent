package com.lifos.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.lifos.backend.dto.UpdateUserProfileRequest;
import com.lifos.backend.config.AiFoundationProperties;
import com.lifos.backend.entity.ConversationMemory;
import com.lifos.backend.entity.MemoryRelationship;
import com.lifos.backend.entity.User;
import com.lifos.backend.repository.ConversationMemoryRepository;
import com.lifos.backend.repository.MemoryRelationshipRepository;
import com.lifos.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts persistent "life facts" from Chat Buddy conversations and stores
 * them in conversation_memories as versioned graph nodes.
 *
 * <p>Phase 2 upgrade: the extraction LLM call now also detects graph relationships
 * (updates / extends / derives) and static vs dynamic memory type in a single shot,
 * following the Supermemory Trick #12 pattern.
 *
 * <p>Runs fully async after a response is sent — chat latency is never affected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "personal", "goals", "health", "work", "relationships", "preferences", "context"
    );
    private static final Set<String> VALID_RELATION_TYPES = Set.of("updates", "extends", "derives");
    private static final int CONTEXT_MEMORY_LIMIT = 20;

    private static final Pattern AGE_PATTERN = Pattern.compile(
            "\\b(?:I(?:'m| am)|age[: ]*)(\\d{1,2})\\b|\\b(\\d{1,2})\\s*(?:years? old|yo)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ConversationMemoryRepository memoryRepository;
    private final MemoryRelationshipRepository memoryRelationshipRepository;
    private final UserRepository userRepository;
    private final UserProfileService userProfileService;
    private final EmbeddingService embeddingService;
    private final AiFoundationProperties aiFoundationProperties;
    private final AiConfigurationResolver aiConfigurationResolver;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /**
     * Extracts and stores graph-aware memories from a completed Chat Buddy exchange.
     * Fires async — never blocks the HTTP response.
     *
     * @param userUid     the user whose memory we're updating
     * @param userMessage the user's last message in the exchange
     * @param aiResponse  the AI's complete response
     * @param sessionId   the AiConversation UUID for provenance tracking (nullable)
     */
    @Async
    @Transactional
    public void extractAndStore(String userUid, String userMessage, String aiResponse,
                                UUID sessionId) {
        try {
            String apiKey = resolveExtractionApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.debug("No API key available for memory extraction, skipping.");
                return;
            }

            // Load the N most recent live head memories for graph context
            List<ConversationMemory> existingMemories =
                    memoryRepository.findLatestActiveByUserUid(
                            userUid, PageRequest.of(0, CONTEXT_MEMORY_LIMIT));

            String extractionPrompt = buildExtractionPrompt(userMessage, aiResponse, existingMemories);
            List<MemoryChunk> extracted = callExtractionApi(apiKey, extractionPrompt);

            if (extracted.isEmpty()) {
                log.debug("No new memories extracted for user [{}]", userUid);
                return;
            }

            User user = userRepository.findById(userUid).orElse(null);
            if (user == null) {
                log.warn("User [{}] not found during memory extraction, skipping.", userUid);
                return;
            }

            // Build lookup map: "mem_0" → ConversationMemory for relation resolution
            Map<String, ConversationMemory> existingById = new LinkedHashMap<>();
            for (int i = 0; i < existingMemories.size(); i++) {
                existingById.put("mem_" + i, existingMemories.get(i));
            }

            Map<String, List<MemoryChunk>> byCategory = extracted.stream()
                    .collect(Collectors.groupingBy(c -> c.category));

            int storedCount = 0;
            int dedupedCount = 0;

            for (MemoryChunk chunk : extracted) {
                String normalized = normalizeMemoryText(chunk.memory);
                if (normalized.isBlank()) continue;

                String hash = computeHash(normalized);
                if (memoryRepository.findByUserUidAndMemoryHashAndActiveTrue(userUid, hash).isPresent()) {
                    dedupedCount++;
                    continue;
                }

                String domain = normalizeDomain(chunk.category);
                String memoryType = "static".equals(chunk.memoryType) ? "static" : "dynamic";

                ConversationMemory memory = ConversationMemory.builder()
                        .user(user)
                        .memoryText(chunk.memory)
                        .category(chunk.category)
                        .domain(domain)
                        .confidence(chunk.confidence)
                        .factualityScore(clamp(chunk.confidence))
                        .relevanceScore(0.7f)
                        .timelinessScore(0.7f)
                        .overallConfidence(weightedOverall(chunk.confidence))
                        .memoryHash(hash)
                        .verificationStatus("unverified")
                        .extractionModel(resolveExtractionModelName())
                        .extractionConfidence(clamp(chunk.confidence))
                        .sourceConversationDate(Instant.now())
                        .memoryType(memoryType)
                        .isLatest(true)
                        .forgotten(false)
                        .sessionId(sessionId)
                        .expiresAt(chunk.expiresAt)
                        .active(true)
                        .updatedAt(Instant.now())
                        .build();

                ConversationMemory saved = memoryRepository.save(memory);
                storedCount++;

                // ── Graph relationship handling ──────────────────────────────
                if (chunk.relationTargetId != null && VALID_RELATION_TYPES.contains(chunk.relationType)) {
                    ConversationMemory target = existingById.get(chunk.relationTargetId);
                    if (target != null) {
                        handleRelation(user, saved, target, chunk.relationType, chunk.confidence);
                    }
                } else {
                    // Legacy fallback: token-overlap supersession check
                    ConversationMemory legacySuperseded =
                            findLikelySuperseded(existingMemories, domain, normalized);
                    if (legacySuperseded != null) {
                        saved.setParentMemoryId(legacySuperseded.getId());
                        memoryRepository.save(saved);
                        handleRelation(user, saved, legacySuperseded, "updates", chunk.confidence);
                    }
                }

                existingMemories.add(saved);

                if (aiFoundationProperties.getRag().isEnableConversationMemoryEmbeddings()) {
                    embeddingService.embedAndStore(
                            userUid, "conversation_memory", saved.getId(), saved.getMemoryText(),
                            domain, "conversation_memory",
                            clamp(saved.getOverallConfidence()), 0.8f, 0.7f);
                }
            }

            log.info("Memory extraction for user [{}]: stored={}, deduped={}", userUid, storedCount, dedupedCount);
            enrichProfileFromChunks(userUid, byCategory);

        } catch (Exception ex) {
            log.warn("Memory extraction failed for user [{}]: {}", userUid, ex.getMessage());
        }
    }

    /**
     * Backward-compatible overload for call sites that don't have a session ID.
     */
    @Async
    @Transactional
    public void extractAndStore(String userUid, String userMessage, String aiResponse) {
        extractAndStore(userUid, userMessage, aiResponse, null);
    }

    // ── Graph relationship application ────────────────────────────────────────

    /**
     * Applies an {@code updates}, {@code extends}, or {@code derives} edge between
     * two memory nodes, updating versioning pointers as needed.
     *
     * <ul>
     *   <li>{@code updates}: old head is marked {@code isLatest=false}; forward pointer set.</li>
     *   <li>{@code extends} / {@code derives}: both heads remain {@code isLatest=true}.</li>
     * </ul>
     */
    private void handleRelation(User user, ConversationMemory newMem,
                                ConversationMemory target, String relationType,
                                float confidence) {
        if ("updates".equals(relationType)) {
            target.setIsLatest(false);
            target.setNextVersionId(newMem.getId());
            target.setActive(false);
            target.setSupersededBy(newMem.getId());
            target.setArchivedAt(Instant.now());
            target.setUpdatedAt(Instant.now());
            memoryRepository.save(target);

            newMem.setParentMemoryId(target.getId());
            memoryRepository.save(newMem);
        }
        // extends / derives: both nodes stay as live heads — no pointer changes needed.

        memoryRelationshipRepository.save(MemoryRelationship.builder()
                .user(user)
                .fromMemory(target)
                .toMemory(newMem)
                .relationshipType(relationType)
                .confidence(Math.max(0.65f, confidence))
                .reason("Graph-aware extraction: " + relationType)
                .build());
    }

    // ── Profile enrichment ────────────────────────────────────────────────────

    private void enrichProfileFromChunks(String userUid, Map<String, List<MemoryChunk>> byCategory) {
        try {
            UpdateUserProfileRequest update = new UpdateUserProfileRequest();
            boolean hasUpdate = false;

            List<MemoryChunk> personalChunks = byCategory.getOrDefault("personal", List.of());
            for (MemoryChunk chunk : personalChunks) {
                Matcher m = AGE_PATTERN.matcher(chunk.memory());
                if (m.find()) {
                    String ageStr = m.group(1) != null ? m.group(1) : m.group(2);
                    try {
                        int age = Integer.parseInt(ageStr);
                        if (age >= 10 && age <= 120) {
                            update.setAge(age);
                            hasUpdate = true;
                        }
                    } catch (NumberFormatException ignored) {}
                    break;
                }
            }

            List<MemoryChunk> workChunks = byCategory.getOrDefault("work", List.of());
            if (!workChunks.isEmpty()) {
                String occupation = workChunks.get(0).memory();
                if (occupation.length() <= 120) {
                    update.setOccupation(occupation);
                    hasUpdate = true;
                }
            }

            if (hasUpdate) {
                userProfileService.updateProfile(userUid, update);
                log.debug("Auto-enriched profile for user [{}] from memories", userUid);
            }
        } catch (Exception ex) {
            log.warn("Profile enrichment from memories failed for user [{}]: {}", userUid, ex.getMessage());
        }
    }

    // ── Graph-aware extraction prompt ─────────────────────────────────────────

    private String buildExtractionPrompt(String userMessage, String aiResponse,
                                         List<ConversationMemory> existing) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are a memory extraction engine for a personal life-management assistant.
                Extract factual statements the USER made about themselves.

                RULES:
                - Extract facts from what the USER said, NOT from the AI response.
                - Only extract long-term useful facts (skip greetings, "ok", "sure", "thanks").
                - Each memory must be a single, clear, atomic fact.
                - If nothing noteworthy, return an empty array [].
                - Categories: personal | goals | health | work | relationships | preferences | context
                - memory_type: "static" for long-term stable facts (name, job, preferences);
                               "dynamic" for evolving context (current project, recent activity).
                - For temporal facts ("meeting at 3pm today", "exam next week"), set expires_at
                  to the ISO-8601 datetime when this fact becomes irrelevant. Otherwise null.
                - For each extracted fact, check against EXISTING MEMORIES and detect if it:
                    - "updates": directly contradicts / replaces an existing memory
                    - "extends": adds detail to an existing memory
                    - "derives": a conclusion inferred from two or more existing memories
                  Use the mem_N ID from the existing memories list as target_id. If no relation, set relation to null.

                OUTPUT FORMAT (strict JSON array, no markdown, no explanation):
                [
                  {
                    "memory": "...",
                    "category": "...",
                    "confidence": 0.9,
                    "memory_type": "static",
                    "expires_at": null,
                    "relation": null
                  },
                  {
                    "memory": "...",
                    "category": "...",
                    "confidence": 0.85,
                    "memory_type": "dynamic",
                    "expires_at": "2026-03-16T23:59:59Z",
                    "relation": {"type": "updates", "target_id": "mem_2"}
                  }
                ]

                """);

        if (!existing.isEmpty()) {
            sb.append("EXISTING MEMORIES (check if new facts update or extend any of these):\n");
            for (int i = 0; i < existing.size(); i++) {
                ConversationMemory m = existing.get(i);
                String type = m.getMemoryType() != null ? m.getMemoryType() : "dynamic";
                sb.append("[mem_").append(i).append("] (").append(type).append(") ")
                  .append(m.getMemoryText()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("CONVERSATION EXCHANGE:\n");
        sb.append("User: ").append(userMessage).append("\n");
        sb.append("Assistant: ").append(aiResponse).append("\n");

        return sb.toString();
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private List<MemoryChunk> callExtractionApi(String apiKey, String prompt) {
        String provider = resolveExtractionProvider();
        String model = resolveExtractionModelName();
        
        if (provider.equals("gemini")) {
            return callGeminiExtractionApi(apiKey, model, prompt);
        }

        String url = switch (provider) {
            case "openai" -> "https://api.openai.com/v1/chat/completions";
            case "huggingface" -> {
                String custom = aiFoundationProperties.getHfInferenceUrl();
                yield (custom != null && !custom.isBlank()) ? custom : "https://api-inference.huggingface.co/v1/chat/completions";
            }
            default -> "https://openrouter.ai/api/v1/chat/completions";
        };

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0.2);
            body.put("max_tokens", 768);
            body.putArray("messages")
                    .addObject().put("role", "user").put("content", prompt);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            if (provider.equals("openrouter") || provider.equals("huggingface")) {
                headers.set("HTTP-Referer", "https://lifeos.app");
            }

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class);

            if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                JsonNode root = objectMapper.readTree(resp.getBody());
                String content = root.path("choices").get(0)
                        .path("message").path("content").asText("");
                return parseMemoryChunks(content);
            }
        } catch (Exception e) {
            log.warn("Extraction API call failed: {}", e.getMessage());
        }
        return List.of();
    }

    private List<MemoryChunk> callGeminiExtractionApi(String apiKey, String model, String prompt) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;
        try {
            ObjectNode body = objectMapper.createObjectNode();
            
            ArrayNode contents = body.putArray("contents");
            ObjectNode msgNode = contents.addObject();
            msgNode.put("role", "user");
            msgNode.putArray("parts").addObject().put("text", prompt);

            ObjectNode genConfig = body.putObject("generationConfig");
            genConfig.put("temperature", 0.2);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class);

            if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                JsonNode root = objectMapper.readTree(resp.getBody());
                String content = root.path("candidates").get(0)
                        .path("content").path("parts").get(0)
                        .path("text").asText("");
                return parseMemoryChunks(content);
            }
        } catch (Exception e) {
            log.warn("Gemini Extraction API call failed: {}", e.getMessage());
        }
        return List.of();
    }

    private List<MemoryChunk> parseMemoryChunks(String raw) {
        String cleaned = raw.strip();
        
        int startIndex = cleaned.indexOf('[');
        int endIndex = cleaned.lastIndexOf(']');
        if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
            cleaned = cleaned.substring(startIndex, endIndex + 1);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("```[a-z]*\\s*", "").replaceAll("```$", "").strip();
        }

        List<MemoryChunk> result = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(cleaned);
            if (!arr.isArray()) return result;

            for (JsonNode node : arr) {
                String memory   = node.path("memory").asText("").strip();
                String category = node.path("category").asText("context").strip().toLowerCase();
                float  conf     = (float) node.path("confidence").asDouble(0.7);
                String memType  = node.path("memory_type").asText("dynamic").strip().toLowerCase();
                if (!"static".equals(memType)) memType = "dynamic";

                if (memory.isBlank()) continue;
                if (!VALID_CATEGORIES.contains(category)) category = "context";

                Instant expiresAt = null;
                String expiresStr = node.path("expires_at").asText(null);
                if (expiresStr != null && !expiresStr.isBlank() && !"null".equals(expiresStr)) {
                    try { expiresAt = Instant.parse(expiresStr); } catch (DateTimeParseException ignored) {}
                }

                String relationType = null;
                String relationTargetId = null;
                JsonNode relationNode = node.path("relation");
                if (!relationNode.isNull() && !relationNode.isMissingNode()) {
                    String rt = relationNode.path("type").asText(null);
                    String tid = relationNode.path("target_id").asText(null);
                    if (VALID_RELATION_TYPES.contains(rt)) {
                        relationType = rt;
                        relationTargetId = tid;
                    }
                }

                result.add(new MemoryChunk(memory, category, conf, memType,
                        expiresAt, relationType, relationTargetId));
            }
        } catch (Exception e) {
            log.warn("Failed to parse memory extraction JSON: {} | raw='{}'", e.getMessage(), raw);
        }
        return result;
    }

    // ── Provider resolution ───────────────────────────────────────────────────

    private String resolveExtractionProvider() {
        var config = aiConfigurationResolver.resolve();
        return (String) config.getModelConfig().getOrDefault("provider", "openrouter");
    }

    private String resolveExtractionModelName() {
        var config = aiConfigurationResolver.resolve();
        String provider = resolveExtractionProvider();
        return aiConfigurationResolver.resolveChatModel(provider, config.getModelConfig(), null);
    }

    private String resolveExtractionApiKey() {
        var config = aiConfigurationResolver.resolve();
        String provider = resolveExtractionProvider();
        return aiConfigurationResolver.resolveProviderApiKey(provider, config.getApiKeys());
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String normalizeMemoryText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDomain(String category) {
        if (category == null || category.isBlank()) return "context";
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        return VALID_CATEGORIES.contains(normalized) ? normalized : "context";
    }

    private String computeHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private ConversationMemory findLikelySuperseded(List<ConversationMemory> existing,
                                                    String domain, String newText) {
        for (ConversationMemory candidate : existing) {
            if (!Boolean.TRUE.equals(candidate.getActive())) continue;
            String candidateDomain = normalizeDomain(candidate.getDomain() != null
                    ? candidate.getDomain() : candidate.getCategory());
            if (!candidateDomain.equals(domain)) continue;
            String oldText = normalizeMemoryText(candidate.getMemoryText());
            if (oldText.isBlank()) continue;
            if (newText.contains(oldText) || oldText.contains(newText)
                    || tokenOverlap(newText, oldText) >= 0.8f) {
                return candidate;
            }
        }
        return null;
    }

    private float tokenOverlap(String a, String b) {
        Set<String> left = Arrays.stream(a.split(" "))
                .filter(s -> s.length() > 2).collect(Collectors.toSet());
        Set<String> right = Arrays.stream(b.split(" "))
                .filter(s -> s.length() > 2).collect(Collectors.toSet());
        if (left.isEmpty() || right.isEmpty()) return 0f;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        int minSize = Math.min(left.size(), right.size());
        return minSize == 0 ? 0f : (float) intersection.size() / minSize;
    }

    private float weightedOverall(float extractionConfidence) {
        float c = clamp(extractionConfidence);
        return clamp((0.45f * c) + (0.30f * 0.7f) + (0.25f * 0.7f));
    }

    private float clamp(float score) {
        return Math.max(0f, Math.min(1f, score));
    }

    // ── Internal DTO ─────────────────────────────────────────────────────────

    private record MemoryChunk(
            String memory,
            String category,
            float confidence,
            String memoryType,
            Instant expiresAt,
            String relationType,
            String relationTargetId
    ) {}
}
