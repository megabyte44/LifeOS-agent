package com.lifos.backend.service;

import com.lifos.backend.config.AiFoundationProperties;
import com.lifos.backend.entity.AiConfiguration;
import com.lifos.backend.repository.AiConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigurationResolver {

    private final AiConfigurationRepository aiConfigurationRepository;
    private final AiFoundationProperties aiFoundationProperties;

    public AiConfiguration resolve() {
        AiConfiguration stored = aiConfigurationRepository.findAll().stream().findFirst().orElse(null);
        AiConfiguration defaults = defaultConfiguration();
        if (stored == null) {
            return defaults;
        }

        Map<String, Object> mergedModelConfig = mergeMaps(defaults.getModelConfig(), stored.getModelConfig());
        Map<String, Object> mergedApiKeys = mergeApiKeys(defaults.getApiKeys(), stored.getApiKeys());

        // AI_PROVIDER env var is a hard operator override — it always wins over the DB stored value.
        String envProviderOverride = aiFoundationProperties.getProvider();
        if (!isBlank(envProviderOverride)) {
            mergedModelConfig = new LinkedHashMap<>(mergedModelConfig);
            mergedModelConfig.put("provider", envProviderOverride.trim().toLowerCase());
            mergedModelConfig.remove("model"); // reset model so defaultModelFor() picks the right one
            log.info("AI_PROVIDER override active — forcing provider={}", envProviderOverride);
        }

        // If the stored provider has no API key, fall back to the best available provider.
        String provider = readString(mergedModelConfig, "provider");
        String keyForProvider = readString(mergedApiKeys, provider);
        if (isBlank(keyForProvider)) {
            String fallbackProvider = resolveBestAvailableProvider(mergedApiKeys);
            if (fallbackProvider != null && !fallbackProvider.equals(provider)) {
                log.warn("No API key for provider '{}', falling back to '{}'", provider, fallbackProvider);
                mergedModelConfig = new LinkedHashMap<>(mergedModelConfig);
                mergedModelConfig.put("provider", fallbackProvider);
                // Clear the model so it gets set to the default for the new provider.
                mergedModelConfig.remove("model");
            }
        }
        log.debug("Resolved AI configuration — provider={}", readString(mergedModelConfig, "provider"));

        return AiConfiguration.builder()
                .id(stored.getId())
                .systemInstructions(mergeMaps(defaults.getSystemInstructions(), stored.getSystemInstructions()))
                .defaultPersonality(isBlank(stored.getDefaultPersonality())
                        ? defaults.getDefaultPersonality()
                        : stored.getDefaultPersonality())
                .modelConfig(mergedModelConfig)
                .apiKeys(mergedApiKeys)
                .ragEnabled(stored.getRagEnabled() != null ? stored.getRagEnabled() : defaults.getRagEnabled())
                .insightsEnabled(stored.getInsightsEnabled() != null ? stored.getInsightsEnabled() : false)
                .insightsCron(stored.getInsightsCron() != null ? stored.getInsightsCron() : "0 0 20 * * *")
                .evaluationEnabled(stored.getEvaluationEnabled() != null ? stored.getEvaluationEnabled() : false)
                .evaluationSampleRate(stored.getEvaluationSampleRate() != null ? stored.getEvaluationSampleRate() : 1)
                .updatedAt(stored.getUpdatedAt())
                .updatedBy(stored.getUpdatedBy())
                .build();
    }

    private String resolveBestAvailableProvider(Map<String, Object> apiKeys) {
        if (!isBlank(readString(apiKeys, "openai")) || !isBlank(aiFoundationProperties.getOpenaiApiKey())) {
            return "openai";
        }
        if (!isBlank(readString(apiKeys, "openrouter")) || !isBlank(aiFoundationProperties.getOpenrouterApiKey())) {
            return "openrouter";
        }
        if (!isBlank(readString(apiKeys, "gemini")) || !isBlank(aiFoundationProperties.getGeminiApiKey())) {
            return "gemini";
        }
        if (!isBlank(readString(apiKeys, "huggingface")) || !isBlank(aiFoundationProperties.getHfApiKey())) {
            return "huggingface";
        }
        return null;
    }

    public String resolveProviderApiKey(String provider, Map<String, Object> configuredApiKeys) {
        String configuredKey = readString(configuredApiKeys, provider);
        if (!isBlank(configuredKey)) {
            return configuredKey;
        }

        return switch (provider) {
            case "gemini" -> aiFoundationProperties.getGeminiApiKey();
            case "openrouter" -> aiFoundationProperties.getOpenrouterApiKey();
            case "huggingface" -> aiFoundationProperties.getHfApiKey();
            case "openai" -> firstNonBlank(
                    readString(resolve().getApiKeys(), "openai"),
                    aiFoundationProperties.getOpenaiApiKey()
            );
            default -> null;
        };
    }

    public String resolveChatModel(String provider, Map<String, Object> modelConfig, String requestedModel) {
        if (isUsableModelOverride(requestedModel)) {
            return requestedModel.trim();
        }

        String configuredModel = readString(modelConfig, "model");
        if (isUsableModelOverride(configuredModel)) {
            return configuredModel.trim();
        }

        return defaultModelFor(provider);
    }

    private AiConfiguration defaultConfiguration() {
        String provider = resolveDefaultProvider();
        Map<String, Object> modelConfig = new LinkedHashMap<>();
        modelConfig.put("provider", provider);
        modelConfig.put("model", firstNonBlank(aiFoundationProperties.getModel(), defaultModelFor(provider)));
        modelConfig.put("temperature", 0.7);
        modelConfig.put("maxTokens", 4096);
        modelConfig.put("topP", 1.0);

        Map<String, Object> apiKeys = new LinkedHashMap<>();
        putIfNotBlank(apiKeys, "openai", aiFoundationProperties.getOpenaiApiKey());
        putIfNotBlank(apiKeys, "openrouter", aiFoundationProperties.getOpenrouterApiKey());
        putIfNotBlank(apiKeys, "gemini", aiFoundationProperties.getGeminiApiKey());
        putIfNotBlank(apiKeys, "huggingface", aiFoundationProperties.getHfApiKey());

        Map<String, Object> systemInstructions = new LinkedHashMap<>();
        systemInstructions.put("casualBuddy", "You are LifeOS, a concise and practical assistant. Give direct, useful answers and stay grounded in the user's request.");
        systemInstructions.put("professionalAssistant", "You are LifeOS, a concise professional assistant. Provide structured, accurate, and actionable responses.");

        return AiConfiguration.builder()
                .systemInstructions(systemInstructions)
                .defaultPersonality("casual")
                .modelConfig(modelConfig)
                .apiKeys(apiKeys)
                .ragEnabled(false)
                .updatedBy("system-default")
                .build();
    }

    private String resolveDefaultProvider() {
        String explicitProvider = aiFoundationProperties.getProvider();
        if (!isBlank(explicitProvider)) {
            return explicitProvider.trim().toLowerCase();
        }
        if (!isBlank(aiFoundationProperties.getOpenrouterApiKey())) {
            return "openrouter";
        }
        if (!isBlank(aiFoundationProperties.getGeminiApiKey())) {
            return "gemini";
        }
        return "openai";
    }

    private String defaultModelFor(String provider) {
        return switch (provider) {
            case "gemini" -> "gemini-2.0-flash";
            case "openrouter" -> "openai/gpt-4o-mini";
            case "huggingface" -> "meta-llama/Llama-3.2-3B-Instruct";
            default -> "gpt-4o-mini";
        };
    }

    private Map<String, Object> mergeMaps(Map<String, Object> defaults, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (defaults != null) {
            merged.putAll(defaults);
        }
        if (overrides != null) {
            overrides.forEach((key, value) -> {
                if (value instanceof String stringValue) {
                    if (!isBlank(stringValue)) {
                        merged.put(key, stringValue);
                    }
                } else if (value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    private Map<String, Object> mergeApiKeys(Map<String, Object> defaults, Map<String, Object> overrides) {
        return mergeMaps(defaults, overrides);
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value);
        }
    }

    private String readString(Map<String, Object> values, String key) {
        if (values == null) {
            return null;
        }
        Object value = values.get(key);
        return value instanceof String stringValue ? stringValue : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isUsableModelOverride(String value) {
        if (isBlank(value)) {
            return false;
        }

        String normalized = value.trim().toLowerCase();
        return !normalized.equals("openai")
                && !normalized.equals("openrouter")
                && !normalized.equals("gemini");
    }
}