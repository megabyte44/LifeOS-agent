# 03 — Backend Deep Dive

## Framework & Stack

- **Spring Boot 4.x** (latest generation) with `spring-boot-starter-webmvc` for REST and `spring-boot-starter-webflux` for reactive streaming
- **Java 17** — uses records (`RetrievalPlan`, `ContextProfile`, `EmbeddingCandidate`, `MemoryChunk`), switch expressions, text blocks
- **Lombok** — `@Slf4j`, `@RequiredArgsConstructor`, `@Builder` throughout
- **Flyway** for schema migrations (`flyway-database-postgresql`)
- **hibernate-vector** for pgvector `float[]` column support

Both MVC and WebFlux coexist: controllers are standard `@RestController` (servlet stack), but `AiChatService.streamChat()` uses `WebClient` to call LLM streaming APIs and bridges to `SseEmitter`.

---

## Auth Implementation

**Class:** `FirebaseAuthenticationFilter` (`security/` package)  
**Pattern:** `OncePerRequestFilter` — guaranteed one execution per HTTP request

Flow:
1. Extract `Authorization: Bearer <token>` header
2. Call `FirebaseAuth.getInstance().verifyIdToken(idToken)` — throws `FirebaseAuthException` if expired/revoked
3. Pull `uid`, `email`, `displayName`, `photoUrl` from `FirebaseToken`
4. Call `userService.ensureUserExists(uid, email, displayName, photoUrl)` — creates a `User` row on first login
5. Map DB role to Spring authority: `"admin"` → `ROLE_ADMIN`, else `ROLE_USER`
6. Set `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`

If Firebase is not initialised (missing service account), the filter logs a warning once (via `AtomicBoolean firebaseMissingWarnLogged`) and passes through — so the app still starts in dev without credentials.

**`SecurityConfig`** (stateless, no CSRF):
```java
.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
.addFilterBefore(firebaseAuthFilter, UsernamePasswordAuthenticationFilter.class)
.authorizeHttpRequests(auth -> auth
    .dispatcherTypeMatchers(ASYNC, ERROR).permitAll()
    .requestMatchers(OPTIONS, "/**").permitAll()
    .requestMatchers("/health").permitAll()
    .anyRequest().authenticated()
)
```

---

## RBAC

Two roles: `ROLE_USER` and `ROLE_ADMIN`.

Admin-only endpoints are guarded with `@PreAuthorize("hasRole('ADMIN')")` (enabled by `@EnableMethodSecurity`) on `AdminController` methods.

`SecurityUtils.getCurrentUserUid()` is the most-called helper in the codebase (fan-in: 50) — extracts the Firebase UID from the Spring Security context principal and passes it down to every service method so queries are always user-scoped.

---

## API Routes

| Method | Path | Controller | Service |
|---|---|---|---|
| POST | `/ai/chat` | `AiChatController` | `AiChatService.chat()` |
| POST | `/ai/chat/stream` | `AiChatController` | `AiChatService.streamChat()` (SSE) |
| GET | `/ai/conversations` | `AiConversationController` | `AiConversationService` |
| DELETE | `/ai/conversations/{id}` | `AiConversationController` | |
| GET | `/ai/memories` | `MemoryGraphController` | `MemoryGraphService` |
| GET/POST/PUT/DELETE | `/notes` | `NoteController` | `NoteService` |
| GET/POST/PUT/DELETE | `/habits` | `HabitController` | `HabitService` |
| GET/POST/PUT/DELETE | `/todos` | `TodoController` | `TodoService` |
| GET/PUT | `/planner` | `PlannerController` | `PlannerService` |
| GET/PUT | `/preferences` | `PreferenceController` | `PreferenceService` |
| GET/PUT | `/profile` | `UserProfileController` | `UserProfileService` |
| POST | `/push/subscribe` | `PushController` | `WebPushService` |
| GET | `/admin/dashboard` | `AdminController` | `AdminService` |
| GET/PUT | `/admin/ai-config` | `AdminController` | `AiConfigurationResolver` |
| GET | `/admin/analytics` | `AdminController` | `AdminService` |
| GET | `/admin/users` | `AdminController` | `UserService` |
| PUT | `/admin/users/{uid}/role` | `AdminController` | `UserService` |
| GET | `/health` | `HealthController` | — (public) |

---

## DB Models (Key Entities)

**`User`** — `uid` (PK, Firebase UID), `email`, `displayName`, `photoUrl`, `role`  
**`Note`** — `id` UUID, `userUid`, `title`, `content`, `tags`, `deleted` (soft delete)  
**`Habit`** — `id`, `userUid`, `name`, `description`, `frequency`, `streakCount`, `completedDates`  
**`TodoItem`** — `id`, `userUid`, `title`, `completed`, `deleted` — `findByIdAndDeletedFalse()` used throughout  
**`PlannerItem`** — `id`, `userUid`, `date`, `title`, `startTime`, `endTime`, `sortOrder`  
**`Embedding`** — `id`, `user`, `sourceType`, `sourceId`, `embedding float[]`, `contentHash`, `chunkIndex`, `parentSourceId`  
**`ConversationMemory`** — `id`, `userUid`, `memoryText`, `memoryType`, `isLatest`, `parentMemoryId`, `nextVersionId`, `expiresAt`, `active`, `forgotten`  
**`AiConfiguration`** — singleton per system (JSONB `modelConfig`, `apiKeys`, `systemInstructions`)  
**`PushSubscription`** — `endpoint`, `p256dhKey`, `authKey`, `userUid`

---

## Service / Controller Pattern

Controllers are deliberately thin: they validate the request, call `SecurityUtils.getCurrentUserUid()`, and delegate everything to a `@Service`.

Example — note save triggers async embedding:

```java
// NoteController
@PostMapping
public NoteResponse createNote(@Valid @RequestBody CreateNoteRequest req) {
    String uid = SecurityUtils.getCurrentUserUid();
    return noteService.createNote(uid, req);
}

// NoteService (simplified)
public NoteResponse createNote(String uid, CreateNoteRequest req) {
    Note note = ... // build entity
    Note saved = noteRepository.save(note);
    ingestionPipeline.publishEvent(uid, "note", saved.getId(), saved.getContent());
    return toDto(saved);
}

// EmbeddingEventListener (async)
@Async
@EventListener
public void onEmbedding(EmbeddingTriggerEvent event) {
    embeddingService.embedChunkedDocument(event.getUserUid(), event.getSourceType(), ...);
}
```

Key services and their responsibilities:

- `AiConfigurationResolver` — resolves the live `AiConfiguration` from DB; falls back to env vars from `AiFoundationProperties`. Exposes `resolveChatModel()`, `resolveProviderApiKey()`.
- `IngestionPipeline` — unified facade: `ingest()` (sync), `ingestAsync()` (`@Async`), `publishEvent()` (via Spring events), `delete()`
- `DocumentChunker` — splits text into overlapping chunks (`chunkMaxChars=3200`, `chunkOverlapChars=400`)
- `StructuredContextService` — injects SQL snapshots (habits, todos, schedule) gated on `QueryIntentClassifier.Intent`
- `RagEvaluationService` — `evaluateAsync()` scores each chat response for RAG quality, stored in `RagEvaluation`

---

## Testing Approach

- `spring-boot-starter-test` is the test dependency in `pom.xml`
- [PLACEHOLDER — fill in: unit tests for X service / integration tests for Y / test coverage %]

Call out in interviews:
- The `@Async` annotation on `MemoryExtractionService.extractAndStore()` means memory extraction never blocks the HTTP streaming response — testable by checking the method executes on a different thread (thread name prefix `"ai-"` from `AiFoundationProperties.Async`)
- `AiConfigurationResolver.isBlank()` (fan-in: 42) is a prime candidate for unit tests since it gates provider selection

---

## Things to Call Out in Interviews

1. **Both MVC and WebFlux in the same app** — `RestTemplate` for synchronous LLM calls, `WebClient` for streaming. They coexist because `webflux` is only used as an HTTP client (not a full reactive server).

2. **`OncePerRequestFilter` guarantee** — important for forwarded/async dispatches; Firebase filter runs once even when Spring re-dispatches the request for `@Async` error handling.

3. **Soft deletes** — `deleted` flag on `Note`, `TodoItem` etc.; queries use `findByIdAndDeletedFalse()` to exclude tombstoned records.

4. **`AiFoundationProperties` as a runtime feature flag system** — toggling `enableHybridReads`, `enableDynamicTopK`, `enableGraphBoostedScoring` changes retrieval behaviour without a deploy.

5. **SHA-256 content hashing for dedup** — `EmbeddingService.embedAndStore()` computes `computeHash(text)` and skips the OpenAI API call if the hash hasn't changed.

6. **The async pool** — `corePoolSize=4`, `maxPoolSize=8`, `queueCapacity=100`, thread name prefix `"ai-"` — custom executor ensures AI tasks don't starve the web thread pool.
