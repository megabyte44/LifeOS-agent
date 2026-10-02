package com.lifos.backend.service;

import com.lifos.backend.config.AiFoundationProperties;
import com.lifos.backend.dto.AiChatRequest;
import com.lifos.backend.dto.AiChatResponse;
import com.lifos.backend.entity.AiConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import java.time.Duration;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final AiFoundationProperties aiFoundationProperties;
    private final AiConfigurationResolver aiConfigurationResolver;
    private final UserProfileService userProfileService;
    private final PromptAssemblyService promptAssemblyService;
    private final MemoryExtractionService memoryExtractionService;
    private final AiChatHistoryService aiChatHistoryService;
    private final AiConversationService aiConversationService;
    private final RagEvaluationService ragEvaluationService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final WebClient webClient;

    public AiChatResponse chat(AiChatRequest req, String userUid) {
        AiConfiguration config = aiConfigurationResolver.resolve();

        Map<String, Object> modelCfg = config.getModelConfig();
        Map<String, Object> apiKeysCfg = config.getApiKeys();

        String provider = (String) modelCfg.getOrDefault("provider", "openrouter");
        String model = aiConfigurationResolver.resolveChatModel(provider, modelCfg, req.getModel());
        double temperature = toDouble(modelCfg.getOrDefault("temperature", 0.7));
        int maxTokens = toInt(modelCfg.getOrDefault("maxTokens", 4096));
        double topP = toDouble(modelCfg.getOrDefault("topP", 1.0));

        String mode = req.getMode() != null ? req.getMode() : "normal";
        log.info("AI chat request — provider={} model={} mode={}", provider, model, mode);

        // Resolve or create conversation synchronously before the AI call
        String lastUserMsg = extractLatestUserMessage(req);
        final boolean isTemporary = Boolean.TRUE.equals(req.getTemporary());
        UUID resolvedConversationId = isTemporary ? null :
                aiConversationService.ensureConversation(
                        req.getConversationId(), userUid, req.getPersonality(), mode, lastUserMsg);

        // Build system prompt based on mode
        String sysPrompt = buildSystemPrompt(req, config, userUid, mode);

        AiChatResponse response = switch (provider) {
            case "gemini" -> callGemini(req.getMessages(), sysPrompt, model,
                    aiConfigurationResolver.resolveProviderApiKey("gemini", apiKeysCfg),
                    temperature, maxTokens, topP);
            default -> callOpenAiCompatible(req.getMessages(), sysPrompt, model,
                resolveApiKey(provider, apiKeysCfg),
                    temperature, maxTokens, topP, provider);
        };

        if (response != null) {
            if (!isTemporary) {
                response.setConversationId(resolvedConversationId.toString());
                aiChatHistoryService.saveExchange(
                        userUid,
                        req,
                        provider,
                        response.getModel(),
                        lastUserMsg,
                        response.getResult(),
                        resolvedConversationId);
            }
        }

        // Async memory extraction — only in chat_buddy mode, never blocks the response
        if ("chat_buddy".equals(mode) && response != null && !lastUserMsg.isBlank()) {
            memoryExtractionService.extractAndStore(userUid, lastUserMsg, response.getResult(),
                    resolvedConversationId);
        }

        return response;
    }

    // ── System Prompt Builder ──────────────────────────────────────────────────

    private String buildSystemPrompt(AiChatRequest req, AiConfiguration config,
                                     String userUid, String mode) {
        String personality = req.getPersonality() != null ? req.getPersonality() : config.getDefaultPersonality();
        Map<String, Object> sysInstructions = config.getSystemInstructions();
        String basePrompt = personality.equals("professional")
                ? (String) sysInstructions.getOrDefault("professionalAssistant", "")
                : (String) sysInstructions.getOrDefault("casualBuddy", "");

        String base;
        if ("chat_buddy".equals(mode)) {
            base = buildChatBuddyPrompt(userUid);
        } else {
            base = basePrompt;
        }

        String userQuery = req.getMessages() == null ? "" : req.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(AiChatRequest.AiMessage::getContent)
                .orElse("");

        return promptAssemblyService.buildFullSystemPrompt(base, userUid, mode, userQuery);
    }

    private String buildChatBuddyPrompt(String userUid) {
        List<String> pending = userProfileService.generatePendingQuestions(userUid);
        String profileCtx = userProfileService.buildProfileContext(userUid);

        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are the user's personal Chat Buddy in LifeOS — a warm, curious friend \
                whose goal is to get to know them deeply. Your job is to have natural, \
                engaging conversations that help build a rich understanding of who they are.

                RULES:
                - Ask ONE question at a time. Don't overwhelm with multiple questions.
                - Be conversational, not interrogative. React to their answers naturally.
                - Share brief observations or connections ("That's cool, so you're into...").
                - If they seem done with a topic, move on gracefully.
                - Extract facts naturally — don't say "I'm updating your profile".
                - Mix getting-to-know-you with life check-ins ("How's your week going?").
                - Keep it casual and warm. You're a friend, not a survey bot.
                """);

        if (!profileCtx.isBlank()) {
            prompt.append("\n=== WHAT YOU ALREADY KNOW ===\n").append(profileCtx);
            prompt.append("\nDon't re-ask things you already know. Build on them.\n");
        }

        if (!pending.isEmpty()) {
            prompt.append("\n=== TOPICS TO EXPLORE (pick ONE naturally) ===\n");
            for (String q : pending) {
                prompt.append("- ").append(q).append("\n");
            }
        }

        return prompt.toString();
    }

    // ── OpenAI / OpenRouter ────────────────────────────────────────────────────

    private AiChatResponse callOpenAiCompatible(
            List<AiChatRequest.AiMessage> messages, String sysPrompt,
            String model, String apiKey, double temperature, int maxTokens, double topP,
            String provider) {

        String url = switch (provider) {
            case "openai" -> "https://api.openai.com/v1/chat/completions";
            case "huggingface" -> {
                String custom = aiFoundationProperties.getHfInferenceUrl();
                yield (custom != null && !custom.isBlank()) ? custom : "https://api-inference.huggingface.co/v1/chat/completions";
            }
            default -> "https://openrouter.ai/api/v1/chat/completions";
        };

        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "API key for " + provider + " is not configured.");
        }

        String resolvedModel = (model == null || model.isBlank())
                ? (provider.equals("openrouter") ? "openai/gpt-4o-mini" : "gpt-4o-mini")
                : model;

        log.info("AI calling {} with model={}", provider, resolvedModel);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", resolvedModel);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("top_p", topP);

            ArrayNode msgs = body.putArray("messages");
            if (!sysPrompt.isBlank()) {
                msgs.addObject().put("role", "system").put("content", sysPrompt);
            }
            for (AiChatRequest.AiMessage m : messages) {
                msgs.addObject().put("role", m.getRole()).put("content", m.getContent());
            }

            String jsonBody = objectMapper.writeValueAsString(body);
            log.info("AI request body: {}", jsonBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            if (provider.equals("openrouter") || provider.equals("huggingface")) {
                headers.set("HTTP-Referer", "https://lifeos.app");
            }

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            String content = root.path("choices").get(0)
                    .path("message").path("content").asText();

            String usedModel = root.path("model").asText(model);

            AiChatResponse r = new AiChatResponse();
            r.setResult(content);
            r.setModel(usedModel);
            return r;

        } catch (Exception e) {
            log.error("AI chat error ({}): {}", provider, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider call failed: " + e.getMessage());
        }
    }

    // ── Gemini ─────────────────────────────────────────────────────────────────

    private AiChatResponse callGemini(
            List<AiChatRequest.AiMessage> messages, String sysPrompt,
            String model, String apiKey, double temperature, int maxTokens, double topP) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gemini API key is not configured.");
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=" + apiKey;

        try {
            ObjectNode body = objectMapper.createObjectNode();

            // Use Gemini's systemInstruction field — NOT a user turn in contents
            if (!sysPrompt.isBlank()) {
                ObjectNode sysInstr = body.putObject("systemInstruction");
                sysInstr.putArray("parts").addObject().put("text", sysPrompt);
            }

            ArrayNode contents = body.putArray("contents");
            for (AiChatRequest.AiMessage m : messages) {
                ObjectNode msgNode = contents.addObject();
                String geminiRole = m.getRole().equals("assistant") ? "model" : "user";
                msgNode.put("role", geminiRole);
                msgNode.putArray("parts").addObject().put("text", m.getContent());
            }

            ObjectNode genConfig = body.putObject("generationConfig");
            genConfig.put("temperature", temperature);
            genConfig.put("maxOutputTokens", maxTokens);
            genConfig.put("topP", topP);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);

            JsonNode root = objectMapper.readTree(resp.getBody());
            String text = root.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            AiChatResponse r = new AiChatResponse();
            r.setResult(text);
            r.setModel(model);
            return r;

        } catch (Exception e) {
            log.error("Gemini chat error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini call failed: " + e.getMessage());
        }
    }

    // ── Streaming ──────────────────────────────────────────────────────────────

    public void streamChat(AiChatRequest req, String userUid, SseEmitter emitter) {
        AiConfiguration config = aiConfigurationResolver.resolve();
        Map<String, Object> modelCfg = config.getModelConfig();
        Map<String, Object> apiKeysCfg = config.getApiKeys();

        String provider = (String) modelCfg.getOrDefault("provider", "openrouter");
        String model = aiConfigurationResolver.resolveChatModel(provider, modelCfg, req.getModel());
        double temperature = toDouble(modelCfg.getOrDefault("temperature", 0.7));
        int maxTokens = toInt(modelCfg.getOrDefault("maxTokens", 4096));
        double topP = toDouble(modelCfg.getOrDefault("topP", 1.0));

        String mode = req.getMode() != null ? req.getMode() : "normal";
        final String personality = req.getPersonality() != null ? req.getPersonality() : config.getDefaultPersonality();
        log.info("AI stream request — provider={} model={} mode={}", provider, model, mode);

        // Resolve or create conversation synchronously before streaming starts
        final String lastUserMsg = extractLatestUserMessage(req);
        final boolean isTemporary = Boolean.TRUE.equals(req.getTemporary());
        final UUID resolvedConversationId;

        if (isTemporary) {
            resolvedConversationId = null;
        } else {
            UUID tmpId;
            try {
                tmpId = aiConversationService.ensureConversation(
                        req.getConversationId(), userUid, req.getPersonality(), mode, lastUserMsg);
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
                return;
            }
            // Send conversation_id as first SSE event so the frontend can wire it up immediately
            try {
                emitter.send(SseEmitter.event().name("conversation_id").data(tmpId.toString()));
            } catch (IOException e) {
                emitter.completeWithError(e);
                return;
            }
            resolvedConversationId = tmpId;
        }

        String sysPrompt = buildSystemPrompt(req, config, userUid, mode);

        final boolean extractMemory = "chat_buddy".equals(mode);
        // Always accumulate the full response for history saving
        final StringBuilder fullResponse = new StringBuilder();
        final long streamStartTime = System.currentTimeMillis();

        Flux<String> tokenFlux = switch (provider) {
            case "gemini" -> streamGemini(req.getMessages(), sysPrompt, model,
                    aiConfigurationResolver.resolveProviderApiKey("gemini", apiKeysCfg),
                    temperature, maxTokens, topP);
            default -> streamOpenAiCompatible(req.getMessages(), sysPrompt, model,
                    resolveApiKey(provider, apiKeysCfg),
                    temperature, maxTokens, topP, provider);
        };

        tokenFlux.subscribe(
            token -> {
                try {
                    fullResponse.append(token);
                    // Encode newlines so SSE framing doesn't eat them
                    emitter.send(SseEmitter.event().data(token.replace("\n", "\\n")));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            },
            error -> {
                log.error("AI stream error: {}", error.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(error);
            },
            () -> {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
                String full = fullResponse.toString();
                if (!isTemporary) {
                    aiChatHistoryService.saveExchange(
                            userUid, req, provider, model, lastUserMsg, full, resolvedConversationId);
                }
                if (extractMemory && !lastUserMsg.isBlank()) {
                    memoryExtractionService.extractAndStore(userUid, lastUserMsg, full,
                            resolvedConversationId);
                }
                if (!isTemporary && !lastUserMsg.isBlank()) {
                    ragEvaluationService.evaluateAsync(userUid, lastUserMsg, full,
                            System.currentTimeMillis() - streamStartTime, model, personality);
                }
            }
        );
    }

    private Flux<String> streamOpenAiCompatible(
            List<AiChatRequest.AiMessage> messages, String sysPrompt,
            String model, String apiKey, double temperature, int maxTokens, double topP,
            String provider) {

        String url = switch (provider) {
            case "openai" -> "https://api.openai.com/v1/chat/completions";
            case "huggingface" -> {
                String custom = aiFoundationProperties.getHfInferenceUrl();
                yield (custom != null && !custom.isBlank()) ? custom : "https://api-inference.huggingface.co/v1/chat/completions";
            }
            default -> "https://openrouter.ai/api/v1/chat/completions";
        };

        String resolvedModel = (model == null || model.isBlank())
                ? (provider.equals("openrouter") ? "openai/gpt-4o-mini" : "gpt-4o-mini")
                : model;

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", resolvedModel);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);
            body.put("top_p", topP);
            body.put("stream", true);

            ArrayNode msgs = body.putArray("messages");
            if (!sysPrompt.isBlank()) {
                msgs.addObject().put("role", "system").put("content", sysPrompt);
            }
            for (AiChatRequest.AiMessage m : messages) {
                msgs.addObject().put("role", m.getRole()).put("content", m.getContent());
            }
            String jsonBody = objectMapper.writeValueAsString(body);

            return webClient.post()
                    .uri(url)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.ACCEPT, "text/event-stream")
                    .header("HTTP-Referer", "https://lifeos.app")
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .filter(event -> {
                        String data = event.data();
                        return data != null && !data.isBlank() && !"[DONE]".equals(data);
                    })
                    .flatMap(event -> {
                        try {
                            JsonNode node = objectMapper.readTree(event.data());
                            String content = node.path("choices").get(0)
                                    .path("delta").path("content").asText("");
                            return content.isEmpty() ? Flux.empty() : Flux.just(content);
                        } catch (Exception e) {
                            return Flux.empty();
                        }
                    })
                    .onErrorMap(e -> {
                        log.warn("Upstream AI provider error: {}", e.getMessage());
                        return new RuntimeException("The AI provider unexpectedly dropped the connection. Please try again later.");
                    })
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(2)));
        } catch (Exception e) {
            return Flux.error(new RuntimeException("The AI provider connection failed completely."));
        }
    }

    private Flux<String> streamGemini(
            List<AiChatRequest.AiMessage> messages, String sysPrompt,
            String model, String apiKey, double temperature, int maxTokens, double topP) {

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":streamGenerateContent?alt=sse&key=" + apiKey;

        try {
            ObjectNode body = objectMapper.createObjectNode();

            // Use Gemini's systemInstruction field — NOT a user turn in contents
            if (!sysPrompt.isBlank()) {
                ObjectNode sysInstr = body.putObject("systemInstruction");
                sysInstr.putArray("parts").addObject().put("text", sysPrompt);
            }

            ArrayNode contents = body.putArray("contents");
            for (AiChatRequest.AiMessage m : messages) {
                ObjectNode msgNode = contents.addObject();
                String geminiRole = m.getRole().equals("assistant") ? "model" : "user";
                msgNode.put("role", geminiRole);
                msgNode.putArray("parts").addObject().put("text", m.getContent());
            }
            ObjectNode genConfig = body.putObject("generationConfig");
            genConfig.put("temperature", temperature);
            genConfig.put("maxOutputTokens", maxTokens);
            genConfig.put("topP", topP);

            String jsonBody = objectMapper.writeValueAsString(body);

            return webClient.post()
                    .uri(url)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header(HttpHeaders.ACCEPT, "text/event-stream")
                    .bodyValue(jsonBody)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .filter(event -> event.data() != null && !event.data().isBlank())
                    .flatMap(event -> {
                        try {
                            JsonNode node = objectMapper.readTree(event.data());
                            JsonNode parts = node.path("candidates").get(0)
                                    .path("content").path("parts");
                            if (parts.isArray() && parts.size() > 0) {
                                String text = parts.get(0).path("text").asText("");
                                return text.isEmpty() ? Flux.empty() : Flux.just(text);
                            }
                            return Flux.empty();
                        } catch (Exception e) {
                            return Flux.empty();
                        }
                    })
                    .onErrorMap(e -> {
                        log.warn("Upstream Gemini provider error: {}", e.getMessage());
                        return new RuntimeException("The Gemini provider unexpectedly dropped the connection. Please try again later.");
                    })
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(500)).maxBackoff(Duration.ofSeconds(2)));
        } catch (Exception e) {
            return Flux.error(new RuntimeException("The Gemini provider connection failed completely."));
        }
    }

    // ── Utils ──────────────────────────────────────────────────────────────────

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0.7; }
    }

    private String extractLatestUserMessage(AiChatRequest req) {
        if (req == null || req.getMessages() == null) {
            return "";
        }
        return req.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((a, b) -> b)
                .map(AiChatRequest.AiMessage::getContent)
                .orElse("");
    }

    private int toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 4096; }
    }

    private String resolveApiKey(String provider, Map<String, Object> apiKeysCfg) {
        return aiConfigurationResolver.resolveProviderApiKey(provider, apiKeysCfg);
    }
}
