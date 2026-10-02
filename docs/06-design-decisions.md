# 06 — Design Decisions

Eight "why did you choose X" decisions, each with the problem, alternatives considered, what was chosen, and trade-offs accepted.

---

## 1. Firebase Auth instead of custom auth (JWT + Spring Security UserDetails)

**Problem:** Auth is table stakes but implementing a secure custom auth system (password hashing, refresh tokens, CSRF, account recovery) takes weeks and is easy to get wrong.

**Alternatives considered:**
- Custom JWT with Spring Security `UserDetailsService`
- Spring Authorization Server (OAuth2)
- Auth0 / Cognito

**What was chosen:** Firebase Auth + `FirebaseAuthenticationFilter`.

**Why:** Firebase handles the full identity lifecycle (password reset, Google OAuth, token refresh, revocation). The backend only verifies stateless ID tokens — one line (`FirebaseAuth.getInstance().verifyIdToken(idToken)`). Google OAuth support comes for free. Token verification is local (Firebase Admin SDK caches the public keys), so auth adds minimal latency.

**Trade-offs accepted:** Firebase lock-in. Migrating away means re-implementing token verification and changing the frontend sign-in flow. Acceptable for a personal project; would revisit for a product that needed vendor neutrality.

---

## 2. pgvector over a dedicated vector DB (Pinecone, Weaviate, Qdrant)

**Problem:** AI features require vector similarity search. Dedicated vector databases offer ANN indexing and high throughput, but add a separate service to run and pay for.

**Alternatives considered:** Pinecone (managed), Weaviate (self-hosted), Qdrant (self-hosted), ChromaDB.

**What was chosen:** pgvector extension on the existing PostgreSQL instance.

**Why:** The `embeddings` table already lives in Postgres alongside the source entities (`Note`, `Habit`, etc.). Keeping vectors in Postgres means a JOIN to get the source record is a local operation — no cross-service network call. Transactional consistency is automatic: if a note is deleted, its embedding row is deleted in the same transaction. pgvector's `<->` operator (cosine distance) is fast enough for per-user collections of thousands of vectors. `hibernate-vector` integrates the `float[]` column type with zero additional code.

**Trade-offs accepted:** pgvector doesn't support approximate nearest neighbor (ANN) indexing as robustly as Pinecone/Qdrant at millions-of-vectors scale. At LifeOS's current scale (hundreds to low thousands of embeddings per user), exact cosine search is fast enough. Would add an IVFFlat or HNSW index if query latency became a concern.

---

## 3. BM25-hybrid retrieval over pure vector search

**Problem:** Pure vector search (cosine similarity) is good at semantic matching but can miss exact keyword matches. A user asking "what are my todos?" needs keyword signal, not just semantic similarity.

**Alternatives considered:**
- Pure vector search (simpler, one DB query)
- Full-text search only (PostgreSQL `tsvector`)
- Elasticsearch BM25 (external service)

**What was chosen:** Custom BM25 implementation in `MemoryRetrievalStrategyService.bm25Score()` combined with pgvector cosine similarity.

**Why:** The hybrid approach captures both semantic and keyword relevance. The custom BM25 implementation is ~40 lines, avoids an external service, and runs on a pre-fetched list (no extra query). Feature flags (`enableHybridReads`) let the system fall back to legacy vector-only mode without code changes.

**Trade-offs accepted:** The BM25 implementation is a "lite" version — it doesn't use IDF (inverse document frequency) because the document corpus is per-user and small. A real BM25 implementation over millions of documents would need IDF precomputed across the corpus. Acceptable at current scale.

---

## 4. @Async memory extraction over a job queue (Celery, Spring Batch, SQS)

**Problem:** Memory extraction after each chat message calls OpenAI and takes 1-3s. If it blocked the HTTP response, streaming latency would double.

**Alternatives considered:**
- Spring Batch scheduled job
- SQS + Lambda consumer
- Redis + Sidekiq-style worker
- Dedicated microservice

**What was chosen:** `@Async` annotation with a custom thread pool (`AiFoundationProperties.Async`: corePool=4, maxPool=8, queue=100).

**Why:** `@Async` is zero-infrastructure for a Spring Boot monolith. The thread pool configuration in `AppConfig` gives enough concurrency for typical usage (multiple simultaneous users chatting) without queue overhead. The method is wrapped in try/catch — failures are logged but never surface to the user. If `extractAndStore()` fails, the only consequence is that one conversation exchange isn't remembered; the chat still works.

**Trade-offs accepted:** If the server restarts mid-extraction, the in-progress task is lost. No retry logic. For a production system at scale, a durable queue (SQS + DLQ) would be the right call. For a personal project where the extraction is non-critical, this is the right trade-off.

---

## 5. Provider-agnostic AI routing (`AiConfigurationResolver`) over hardcoding OpenAI

**Problem:** LLM providers change pricing and model availability constantly. Hardcoding a provider means a code change to switch.

**Alternatives considered:** Hardcode OpenAI; use LangChain4j (abstracts providers).

**What was chosen:** `AiConfigurationResolver.resolve()` reads an `AiConfiguration` entity from DB (with `modelConfig: {provider, model, temperature}` as JSONB). `AiChatService.chat()` switches on `provider` (gemini / openai / openrouter).

**Why:** Admin can change the provider, model, temperature, and API keys from the UI (`/admin/ai-config`) without a redeploy. OpenRouter is the default because it gives access to dozens of models under one API key. Gemini and OpenAI have their own code paths (`callGemini()`, `callOpenAiCompatible()`) because their request/response schemas differ.

**Trade-offs accepted:** Two slightly different code paths (`callGemini()` vs `callOpenAiCompatible()`) instead of one unified adapter. LangChain4j would clean this up but adds dependency weight and abstracts away the raw request control (streaming, custom headers like `HTTP-Referer`).

---

## 6. Versioned graph nodes for memory instead of "replace in place"

**Problem:** User facts change ("I'm 20" → "I'm 21"). Overwriting the memory loses history and makes it impossible to understand how a fact evolved.

**Alternatives considered:**
- Replace existing memory text in place (simple, but lossy)
- Append-only with no versioning (keeps history but retrieval gets noisy)
- Event sourcing pattern

**What was chosen:** Versioned graph: each memory is a node. When an "updates" relation is detected, the old node gets `isLatest=false`, `active=false`, `nextVersionId=<new id>`. The new node gets `parentMemoryId=<old id>`. A `MemoryRelationship` edge records the graph edge with type/confidence.

**Why:** Retrieval always queries `isLatest=true` nodes — stale facts are automatically excluded. The graph structure is preserved for visualization (`/ai/memories`). The extraction LLM is shown the existing memories as `[mem_0]...[mem_N]` and outputs a `relation.target_id` field to name the node it's updating, so the system doesn't need a separate similarity pass to find the right node to supersede.

**Trade-offs accepted:** Schema complexity (`nextVersionId`, `parentMemoryId`, `supersededBy`, `archivedAt` fields on `ConversationMemory`). A "replace in place" model would be simpler. Accepted because the history is genuinely useful for debugging and for building the memory visualization UI.

---

## 7. App Router + TanStack React Query over Pages Router + SWR

**Problem:** Choosing the right data-fetching and routing approach for a complex multi-page app with real-time features (streaming chat).

**Alternatives considered:**
- Next.js Pages Router + SWR
- Next.js Pages Router + React Query
- Vite SPA + React Query

**What was chosen:** Next.js App Router + TanStack React Query v5.

**Why:** App Router gives per-route `loading.tsx` and `error.tsx` boundaries as first-class primitives — no manual wrapping in Suspense/ErrorBoundary in every page. React Query v5 has better TypeScript support, deduplication, and stale-while-revalidate semantics than SWR. The App Router coexists cleanly with Next.js route handlers (`/api/ai/chat/stream`) for the SSE proxy and push subscription endpoints.

**Trade-offs accepted:** App Router has more boilerplate per route (page.tsx + loading.tsx + error.tsx). The Next.js App Router + React Query combination required carefully ensuring all data-fetching hooks are in `'use client'` components. React Query's client-side nature means no server components for data-heavy pages.

---

## 8. Document chunking in the embedding pipeline

**Problem:** `text-embedding-3-small` has an 8191 token limit. Long notes (research notes, journals) exceed this. Even within the limit, embedding a 10,000-character note as one vector loses precision — retrieving it brings in the whole document when only a section is relevant.

**Alternatives considered:**
- Truncate to 8000 chars (simple, but loses the tail)
- Summarise before embedding (accurate but slow, costs tokens)
- Chunk without overlap (fast but misses cross-boundary context)

**What was chosen:** Overlapping chunking in `DocumentChunker.chunk()`: `chunkMaxChars=3200` (~800 tokens), `chunkOverlapChars=400` (~100 token overlap). Each chunk gets its own `Embedding` row with `chunkIndex`, `totalChunks`, `parentSourceId` for provenance.

**Why:** Overlapping chunks preserve semantic context at boundaries. Each chunk is small enough to embed precisely and be retrieved specifically. `parentSourceId` lets the system reconstruct which note a chunk came from. SHA-256 hashing on the full document text in `embedAndStore()` prevents re-embedding when the note hasn't changed.

**Trade-offs accepted:** A long note generates N embedding rows instead of 1 — more DB storage. Re-embedding (on note update) first deletes all chunks (`embeddingRepository.deleteBySourceTypeAndSourceId(sourceType, sourceId)`) then re-chunks, which means a brief window where the note's embeddings are missing. Chunking is gated on `enableDocumentChunking=true` so it can be disabled via admin config.
