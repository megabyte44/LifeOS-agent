# 02 — Architecture

## ASCII System Diagram

```
Browser / Mobile (PWA)
        │
        │  HTTPS  (Bearer Firebase ID Token)
        ▼
┌──────────────────────────────────────────────┐
│         Next.js 16 Frontend (port 9002)       │
│                                              │
│  App Router pages:                           │
│   /dashboard  /notes  /habits  /planner      │
│   /todos  /ai-chat  /admin/**                │
│                                              │
│  api-client.ts → injects Firebase token      │
│  TanStack React Query → server-state cache   │
│  Service Worker (sw.js) → PWA offline        │
└──────────────┬───────────────────────────────┘
               │  REST + SSE
               │  POST /api/ai/chat/stream
               ▼
┌──────────────────────────────────────────────┐
│       Spring Boot 4 Backend (port 8000)       │
│                                              │
│  FirebaseAuthenticationFilter                │
│    → verifies Bearer token with Firebase SDK │
│    → ensureUserExists() → sets SecurityContext│
│                                              │
│  Controllers (12 total)                      │
│   AiChatController → AiChatService           │
│   NoteController   → NoteService             │
│   HabitController  → HabitService            │
│   AdminController  → AdminService            │
│   ... (Planner, Todo, Push, UserProfile…)    │
│                                              │
│  PromptAssemblyService                       │
│   Layer 1: MemoryRetrievalStrategyService    │
│             (BM25 + pgvector hybrid)         │
│   Layer 2: StructuredContextService          │
│             (intent-gated SQL snapshots)     │
│   Layer 3: MemoryGraphService                │
│             (static/dynamic profile from     │
│              ConversationMemory graph)       │
│                                              │
│  async: MemoryExtractionService              │
│           @Async → extractAndStore()         │
│           LLM → graph-aware memory nodes     │
│                                              │
│  async: IngestionPipeline                    │
│           embedChunkedDocument()             │
│           → OpenAiEmbeddingClient            │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│       PostgreSQL + pgvector                   │
│                                              │
│  Tables (managed by Flyway migrations)       │
│   notes, habits, todo_items, planner_items   │
│   embeddings (float[] via hibernate-vector)  │
│   conversation_memories (versioned graph)    │
│   memory_relationships (edge table)          │
│   knowledge_edges (KG-RAG graph edges)       │
│   ai_chat_history, ai_conversations          │
│   push_subscriptions                        │
│   users, user_profiles                      │
└──────────────────────────────────────────────┘
               │
               ▼
  Firebase Auth (token verification only — no data storage)
  OpenAI / OpenRouter / Gemini APIs (chat + embeddings)
```

## Architectural Patterns

| Pattern | Where |
|---|---|
| Layered MVC (Controller → Service → Repository) | All domain features |
| Filter-based stateless auth | `FirebaseAuthenticationFilter extends OncePerRequestFilter` |
| Event-driven async embedding | `ApplicationEventPublisher` → `EmbeddingEventListener` |
| Strategy pattern | `MemoryRetrievalStrategyService` switches hybrid vs. legacy path via `enableHybridReads` flag |
| Facade | `IngestionPipeline` wraps `embedChunkedDocument()` + event publishing in one call |
| Feature flags | `AiFoundationProperties.Rag` — `enableHybridReads`, `enableDynamicTopK`, `enableGraphBoostedScoring` etc. |
| Optimistic UI | AI chat adds user message + AI placeholder before the SSE stream returns |
| PWA offline-first | Service worker with Workbox via `@ducanh2912/next-pwa` |

## Folder Breakdown

### Backend (`backend/src/main/java/com/lifos/backend/`)

```
config/
  AiFoundationProperties.java  — @ConfigurationProperties(prefix="ai"), all RAG flags
  SecurityConfig.java          — Spring Security filter chain, stateless sessions
  FirebaseConfig.java          — FirebaseApp initialisation from service account
  AppConfig.java               — RestTemplate, WebClient, async executor beans

controller/       — thin HTTP layer, delegates all logic to services
  AiChatController.java        — POST /ai/chat, POST /ai/chat/stream (SSE)
  NoteController.java          — CRUD /notes
  HabitController.java         — CRUD /habits
  AdminController.java         — /admin/dashboard, /admin/users, /admin/ai-config
  MemoryGraphController.java   — /ai/memories (read memory graph)
  ...

service/          — all business logic
  AiChatService.java               — chat(), streamChat(), buildSystemPrompt()
  PromptAssemblyService.java        — three-layer context assembly
  MemoryRetrievalStrategyService.java — BM25 + vector hybrid scoring
  MemoryExtractionService.java      — @Async graph-aware memory extraction
  MemoryGraphService.java           — generateProfile() (static + dynamic facts)
  IngestionPipeline.java            — ingest(), ingestAsync(), delete()
  EmbeddingService.java             — embedAndStore(), embedChunkedDocument()
  QueryIntentClassifier.java        — keyword-based intent → HABITS/TODOS/SCHEDULE etc.
  AiConfigurationResolver.java      — resolves provider/model/apiKey at runtime
  RagEvaluationService.java         — evaluateAsync() after each chat turn

entity/           — JPA entities
repository/       — Spring Data interfaces (all extend JpaRepository)
security/         — FirebaseAuthenticationFilter, SecurityUtils.getCurrentUserUid()
event/            — EmbeddingTriggerEvent, EmbeddingEventListener, KnowledgeGraphEventListener
dto/              — ~30 request/response DTOs
exception/        — GlobalExceptionHandler, ResourceNotFoundException
```

### Frontend (`frontend/src/`)

```
app/              — Next.js App Router (file-based routing)
  (ai-chat)/      — streaming chat UI, conversation sidebar
  admin/          — protected admin pages (users, ai-config, analytics, rag-eval)
  notes/          — note list + editor
  habits/         — streak tracker
  planner/        — day planner
  dashboard/      — summary view
  api/            — Next.js route handlers (push subscription, health check)

components/
  layout/AppLayout.tsx     — sidebar + mobile nav shell
  chat/                    — ChatSidebar, ChatInputBar, ChatMessageBubble, WelcomeScreen
  ui/                      — shadcn/ui components (sidebar.tsx, command-palette.tsx, etc.)

hooks/
  use-auth.tsx             — AuthProvider + useAuth() — wraps Firebase onAuthStateChanged
  use-admin-check.ts       — checks /admin/users for ROLE_ADMIN
  api/                     — per-feature React Query hooks

lib/
  api-client.ts            — typed fetch wrapper, auto-injects Firebase ID token, handles 401
  firebase.ts              — single Firebase app init
  notifications.ts         — Web Push subscription helpers

services/                  — per-domain API call functions
  ai-chat.service.ts       — listConversations(), sendMessage(), streamChat()
  note.service.ts, habit.service.ts, todo.service.ts, planner.service.ts…
```

## Key Data Models

### `Embedding` (pgvector)
- `sourceType` (note / conversation_memory / habit / …)
- `sourceId` UUID — FK to source entity
- `embedding` float[] — 1536-d stored via `hibernate-vector`
- `chunkIndex`, `totalChunks`, `parentSourceId` — chunking support
- `contentHash` SHA-256 — dedup on re-embed
- `recencyWeight`, `importanceSignal`, `embeddingQualityScore` — scoring signals

### `ConversationMemory` (graph nodes)
- `memoryText` — the extracted fact
- `memoryType` ("static" | "dynamic")
- `isLatest` boolean — current head in version chain
- `parentMemoryId` — prev version UUID
- `nextVersionId` — forward pointer
- `expiresAt` — temporal facts expire automatically
- `active`, `forgotten` — soft lifecycle

### `MemoryRelationship` (graph edges)
- `fromMemory`, `toMemory` — ConversationMemory FKs
- `relationshipType` ("updates" | "extends" | "derives")
- `confidence` float

### `AiConfiguration` (JSONB)
- `modelConfig` — `{provider, model, temperature, maxTokens, topP}`
- `apiKeys` — `{openai, openrouter, gemini}`
- `systemInstructions` — `{professionalAssistant, casualBuddy}`
- Resolved at runtime by `AiConfigurationResolver.resolve()`

## Auth Flow

```
1. User signs in with Google via signInWithPopup(auth, googleProvider)  [firebase.ts]
2. Firebase returns an ID token
3. useAuth() (onAuthStateChanged) stores FirebaseUser in React context
4. api-client.ts calls user.getIdToken() and sets Authorization: Bearer <token> on every request
5. Backend: FirebaseAuthenticationFilter.doFilterInternal()
   → FirebaseAuth.getInstance().verifyIdToken(idToken)
   → decodedToken.getUid(), .getEmail(), .getName()
   → userService.ensureUserExists(uid, email, displayName, photoUrl)  [auto-creates DB record]
   → Maps DB role "admin"/"user" to ROLE_ADMIN/ROLE_USER Spring authority
   → SecurityContextHolder.getContext().setAuthentication(...)
6. Controllers use SecurityUtils.getCurrentUserUid() to scope all queries
7. On 401 response: api-client.ts fires signOut(auth) → React Query cache cleared → redirect /login
```

## CI/CD

- Docker: `backend/Dockerfile` (multi-stage Maven build → JRE image)
- `backend/docker-compose.yml` — PostgreSQL + pgvector (`pgvector/pgvector:pg16` image)
- Frontend: `next build --webpack` (standard Next.js production build)
- Local dev: `run-backend.ps1` starts Spring Boot; `npm run dev` on port 9002
- [PLACEHOLDER — add if deployed: e.g., Railway / Render / Vercel]
