package com.lifos.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public health endpoint — no authentication required.
 *
 * GET /health
 *   → 200 { status: "UP",   db: "UP",   dbType: "PostgreSQL", dbProvider: "NEON", timestamp: "..." }
 *   → 200 { status: "DEGRADED", db: "DOWN", dbType: "UNKNOWN", dbProvider: "UNKNOWN", timestamp: "..." }
 *
 * Lives outside /api/** so it is permitted without a Firebase token.
 * Used by the frontend connection logger to track backend + DB reachability.
 */
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final com.lifos.backend.service.AiConfigurationResolver aiConfigurationResolver;

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> dbHealth = checkDatabase();
        String dbStatus = dbHealth.get("db");
        String dbType = dbHealth.get("dbType");
        String dbProvider = dbHealth.get("dbProvider");

        Map<String, String> body = new LinkedHashMap<>();
        body.put("status",    dbStatus.equals("UP") ? "UP" : "DEGRADED");
        body.put("db",        dbStatus);
        body.put("dbType",    dbType);
        body.put("dbProvider", dbProvider);
        body.put("timestamp", Instant.now().toString());

        try {
            com.lifos.backend.entity.AiConfiguration config = aiConfigurationResolver.resolve();
            Map<String, Object> modelCfg = config.getModelConfig();
            String provider = (String) modelCfg.getOrDefault("provider", "openrouter");
            String model = aiConfigurationResolver.resolveChatModel(provider, modelCfg, null);
            String apiKey = aiConfigurationResolver.resolveProviderApiKey(provider, config.getApiKeys());
            boolean hasKey = apiKey != null && !apiKey.trim().isEmpty();

            body.put("resolvedAiProvider", provider);
            body.put("resolvedAiModel", model);
            body.put("hasResolvedAiApiKey", String.valueOf(hasKey));
        } catch (Exception e) {
            body.put("resolvedAiError", e.getMessage());
        }

        return ResponseEntity.ok(body);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, String> checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(2); // 2-second timeout
            String dbUrl = conn.getMetaData().getURL();
            Map<String, String> db = new LinkedHashMap<>();
            db.put("db", valid ? "UP" : "DOWN");
            db.put("dbType", valid ? conn.getMetaData().getDatabaseProductName() : "UNKNOWN");
            db.put("dbProvider", valid ? detectProvider(dbUrl) : "UNKNOWN");
            return db;
        } catch (Exception e) {
            Map<String, String> db = new LinkedHashMap<>();
            db.put("db", "DOWN");
            db.put("dbType", "UNKNOWN");
            db.put("dbProvider", "UNKNOWN");
            return db;
        }
    }

    private String detectProvider(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "UNKNOWN";
        }

        String url = jdbcUrl.toLowerCase();
        if (url.contains("neon.tech") || url.contains("neon")) {
            return "NEON";
        }
        if (url.contains("rds.amazonaws.com") || url.contains("amazonaws.com")) {
            return "AWS_RDS";
        }
        if (url.contains("localhost") || url.contains("127.0.0.1") || url.contains("0.0.0.0")) {
            return "LOCAL";
        }
        return "CUSTOM";
    }
}
