package com.lifos.backend.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Firebase Configuration — initializes the Firebase Admin SDK on startup.
 *
 * KEY CONCEPTS:
 * - @PostConstruct → the init() method runs ONCE immediately after Spring
 *   creates this bean. It's the right place to run one-time setup.
 * - GoogleCredentials.fromStream() → reads the service account JSON and
 *   creates credentials that let us call Firebase APIs from the backend.
 * - FirebaseApp.getApps().isEmpty() → guard against re-initialization on
 *   hot reload (Spring DevTools restarts the context).
 *
 * HOW TO GET YOUR SERVICE ACCOUNT:
 * 1. Go to https://console.firebase.google.com
 * 2. Project Settings → Service Accounts
 * 3. Click "Generate new private key"
 * 4. Save the JSON file as: src/main/resources/firebase-service-account.json
 */
@Configuration
@Slf4j
public class FirebaseConfig {

    /**
     * Reads path from application.yaml:
     *   firebase.service-account-path: classpath:firebase-service-account.json
     *
     * Spring's Resource abstraction handles classpath: / file: prefixes.
     */
    @Value("${firebase.service-account-path:classpath:firebase-service-account.json}")
    private Resource serviceAccountResource;

    @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}")
    private String firebaseServiceAccountJson;

    @Value("${FIREBASE_SERVICE_ACCOUNT_BASE64:}")
    private String firebaseServiceAccountBase64;

    @Value("${FIREBASE_ADMIN_PROJECT_ID:}")
    private String firebaseAdminProjectId;

    @Value("${FIREBASE_ADMIN_CLIENT_EMAIL:}")
    private String firebaseAdminClientEmail;

    @Value("${FIREBASE_ADMIN_PRIVATE_KEY:}")
    private String firebaseAdminPrivateKey;

    @PostConstruct
    public void init() {
        if (FirebaseApp.getApps().isEmpty()) {
            try {
                GoogleCredentials credentials = resolveCredentials();
                if (credentials == null) {
                    log.warn("Firebase Admin credentials not found. Set FIREBASE_SERVICE_ACCOUNT_JSON or FIREBASE_ADMIN_* env vars (or provide firebase-service-account.json).");
                    return;
                }

                FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder()
                        .setCredentials(credentials);

                if (!firebaseAdminProjectId.isBlank()) {
                    optionsBuilder.setProjectId(firebaseAdminProjectId);
                }

                FirebaseApp.initializeApp(optionsBuilder.build());
                log.info("Firebase Admin initialized successfully");

            } catch (Exception e) {
                log.error("Failed to initialize Firebase Admin", e);
            }
        }
    }

    private GoogleCredentials resolveCredentials() throws IOException {
        String normalizedJsonEnv = normalizeSecret(firebaseServiceAccountJson);
        if (!normalizedJsonEnv.isBlank()) {
            log.info("Initializing Firebase Admin from FIREBASE_SERVICE_ACCOUNT_JSON");
            return GoogleCredentials.fromStream(
                    new ByteArrayInputStream(normalizedJsonEnv.getBytes(StandardCharsets.UTF_8))
            );
        }

        String normalizedBase64Env = normalizeSecret(firebaseServiceAccountBase64);
        if (!normalizedBase64Env.isBlank()) {
            log.info("Initializing Firebase Admin from FIREBASE_SERVICE_ACCOUNT_BASE64");
            String decoded = new String(Base64.getDecoder().decode(normalizedBase64Env), StandardCharsets.UTF_8);
            return GoogleCredentials.fromStream(
                    new ByteArrayInputStream(decoded.getBytes(StandardCharsets.UTF_8))
            );
        }

        if (!firebaseAdminProjectId.isBlank() && !firebaseAdminClientEmail.isBlank() && !firebaseAdminPrivateKey.isBlank()) {
            log.info("Initializing Firebase Admin from FIREBASE_ADMIN_* fields");
            String normalizedPrivateKey = firebaseAdminPrivateKey.replace("\\n", "\n");
            String json = String.format(
                    "{\"type\":\"service_account\",\"project_id\":\"%s\",\"private_key\":\"%s\",\"client_email\":\"%s\",\"client_id\":\"0\",\"private_key_id\":\"auto\"}",
                    escapeJson(firebaseAdminProjectId),
                    escapeJson(normalizedPrivateKey),
                    escapeJson(firebaseAdminClientEmail)
            );
            return GoogleCredentials.fromStream(
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
            );
        }

        if (serviceAccountResource != null && serviceAccountResource.exists()) {
            log.info("Initializing Firebase Admin from firebase.service-account-path resource");
            return GoogleCredentials.fromStream(serviceAccountResource.getInputStream());
        }

        try {
            log.info("Attempting Firebase Admin initialization via Application Default Credentials");
            return GoogleCredentials.getApplicationDefault();
        } catch (Exception ignored) {
            // No ADC available in this runtime; return null so caller logs guidance.
        }

        return null;
    }

    private String normalizeSecret(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
