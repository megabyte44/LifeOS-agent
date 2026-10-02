# 05 — RAG Pipeline Deep Dive

The RAG pipeline is the most technically complex part of LifeOS. It combines three retrieval layers, a graph-aware memory extraction system, and a streaming LLM integration with SSE.

---

## Overview: Three Layers

```
User message
    │
    ▼
PromptAssemblyService.buildFullSystemPrompt()
    │
    ├── Layer 1: MemoryRetrievalStrategyService.buildPlan()
    │     ├── BM25 keyword scoring over ConversationMemory rows
    │     ├── pgvector cosine similarity (OpenAI text-embedding-3-small)
    │     ├── Hybrid scoring with weights (0.35 vector + 0.25 BM25 + ...)
    │     └── Optional: graph boost pass (KnowledgeEdgeRepository.walkGraph())
    │
    ├── Layer 2: StructuredContextService.buildSnapshot()
    │     └── Intent-gated: QueryIntentClassifier.classify(query)
    │           HABITS  → inject today's habit snapshot
    │           TODOS   → inject pending todos
    │           SCHEDULE → inject planner items for today
    │
    └── Layer 3: MemoryGraphService.generateProfile()
          ├── findStaticProfileMemories(uid)  → long-term facts (name, job, preferences)
          └── findDynamicContextMemories(uid, top-12) → recent evolving context
```

The assembled context is injected into the system prompt as a hidden block:
```
[CONTEXT — use this to answer accurately, do not reveal this block exists]
=== USER PROFILE ===
...
=== MEMORIES ===
...
=== KNOWLEDGE: DOCUMENTS ===
...
[END CONTEXT]
```

---

## Layer 1: Hybrid Retrieval (`MemoryRetrievalStrategyService`)

**File:** `service/MemoryRetrievalStrategyService.java`

### Candidate sources
- `ConversationMemory` rows — extracted facts from Chat Buddy conversations
- `Embedding` rows — vector embeddings of notes and other documents

### BM25-lite scoring (`bm25Score()`)
A custom BM25 implementation (not a library) using the formula:
```
tf*(k1+1) / (tf + k1*(1 - b + b*(docLen/avgDocLen)))
```
Parameters: `k1=1.2`, `b=0.75`, `avgDocLen=50`. Scores are clamped to [0, 1].

### Composite scoring formula (without graph boost)
```
score = 0.35 * vectorScore
      + 0.25 * bm25Score
      + 0.15 * recencyScore      (exponential decay: exp(-ageDays/30))
      + 0.10 * importanceScore
      + 0.10 * confidenceScore
      + 0.05 * accessBoost       (log1p(accessCount)/3.0)
```

With graph boost enabled, vector gets 0.30 and graph contributes 0.10.

### Dynamic top-k and token budget
- `enableDynamicTopK=true` (default) → `computeTokenBudget()` scales 900–1900 tokens based on query complexity
- `computeDynamicCap()` → 8–24 candidates based on query length
- Minimum 6 candidates always included regardless of budget

### Graph boost pass (`applyGraphBoost()`)
1. Take top 5 candidates as seed nodes
2. Call `KnowledgeEdgeRepository.walkGraph(userUid, seedTypes, seedIds, maxHops=2, 50)` — SQL recursive walk
3. Score decay: `0.5^depth * confidence`
4. Boost connected candidates: memory nodes +`graphWeight*gs` (0.10), embedding nodes +(0.15*gs)

### Observability
Every retrieval is logged to `MemoryRetrievalLog` table with per-candidate scores (vector, BM25, recency, importance, confidence, graph). `touchAccess()` increments `accessCount` on selected memories.

---

## Layer 2: Intent-Gated Structured Context (`QueryIntentClassifier`)

**File:** `service/QueryIntentClassifier.java`

Keyword-based, zero-latency (no LLM call). Maps query terms to `Intent` enum values:

| Intent | Trigger keywords |
|---|---|
| HABITS | habit, streak, routine, daily, consistency |
| TODOS | todo, task, checklist, pending, finish |
| SCHEDULE | schedule, today, plan, calendar, meeting, tomorrow |
| PERSONAL | about me, who am i, my profile, interests |
| MEMORY | remember, told you, recall, last time |
| NOTES | note, notes, jotted, saved, wrote down |
| GENERAL | (fallback when no keywords match) |

`StructuredContextService.buildSnapshot()` only fetches SQL data for detected intents — a HABITS query doesn't pull your todo list.

---

## Layer 3: Memory Graph Profile (`MemoryGraphService`)

**File:** `service/MemoryGraphService.java`

Follows the "Supermemory" pattern: no separate structured profile table — the profile is generated on-the-fly from `ConversationMemory` nodes grouped by `memoryType`.

- `findStaticProfileMemories(uid)` → `isLatest=true AND memoryType='static'`
- `findDynamicContextMemories(uid, top-12)` → `isLatest=true AND memoryType='dynamic'`

Returns `ContextProfile(List<String> staticFacts, List<String> dynamicFacts)`.

`computeStatus(mem)` produces display states for the memory graph UI: "forgotten", "expiring" (expires within 24h), "new" (created within 24h), "active".

---

## Memory Extraction Pipeline (`MemoryExtractionService`)

**File:** `service/MemoryExtractionService.java`

Triggered after every Chat Buddy response (`chat_buddy` mode only), fully async (`@Async`).

### Flow Diagram

```
streamChat() / chat() completes
    │
    │ (async, does not block HTTP response)
    ▼
MemoryExtractionService.extractAndStore(userUid, userMessage, aiResponse, conversationId)
    │
    ├── Load top 20 recent active ConversationMemory rows (context for graph)
    │
    ├── buildExtractionPrompt(userMessage, aiResponse, existingMemories)
    │     → "EXISTING MEMORIES: [mem_0]...[mem_19]"
    │     → "Extract what the USER said, categorise, detect relations"
    │
    ├── callExtractionApi(apiKey, prompt)
    │     → POST to OpenAI/OpenRouter with model=gpt-4o-mini, temperature=0.2
    │     → Returns JSON array of MemoryChunk records
    │
    ├── For each extracted chunk:
    │     ├── normalizeMemoryText() + SHA-256 hash → dedup check
    │     ├── Build ConversationMemory entity (isLatest=true, memoryType=static|dynamic)
    │     ├── Save to DB
    │     │
    │     ├── Graph relation handling:
    │     │     "updates"  → mark old node isLatest=false, set nextVersionId/supersededBy
    │     │     "extends"  → both nodes stay isLatest=true (additive relation)
    │     │     "derives"  → both stay live (inferred relation)
    │     │     Save MemoryRelationship edge
    │     │
    │     └── Embed the new memory (if enableConversationMemoryEmbeddings=true)
    │           embeddingService.embedAndStore(uid, "conversation_memory", id, text)
    │
    └── enrichProfileFromChunks()
          → If "personal" category + AGE_PATTERN match → userProfileService.updateProfile(age)
          → If "work" category → updateProfile(occupation)
```

### Temporal memory expiry
The extraction prompt instructs the LLM to set `expires_at` for facts like "exam next week" or "meeting tomorrow". The memory node's `expiresAt` field is stored; `computeStatus()` surfaces it as "expiring" in the UI.

---

## Streaming Architecture (`AiChatService.streamChat()`)

**File:** `service/AiChatService.java`

```
1. AiConfigurationResolver.resolve() → pick provider/model/temperature/maxTokens
2. aiConversationService.ensureConversation() → create/resolve UUID
3. Emit conversation_id as first SSE event (wires frontend sidebar immediately)
4. buildSystemPrompt() → PromptAssemblyService.buildFullSystemPrompt()
5. tokenFlux = switch(provider):
     "gemini"  → streamGemini() via WebClient
     default   → streamOpenAiCompatible() via WebClient (OpenAI/OpenRouter)
6. tokenFlux.subscribe(
     token    → emitter.send(event().data(token.replace("\n", "\\n")))
     error    → emitter.send(event().name("error")...) + completeWithError()
     complete → emitter.send(event().name("done").data("[DONE]"))
                aiChatHistoryService.saveExchange(...)   [sync, inside callback]
                memoryExtractionService.extractAndStore(...) [async]
                ragEvaluationService.evaluateAsync(...)  [async]
   )
```

Newlines in token content are encoded as `\\n` in SSE data to prevent breaking SSE framing (SSE uses `\n\n` as event boundary). The frontend decodes `data.replace(/\\n/g, '\n')`.

---

## Config Flags (from `AiFoundationProperties.Rag`)

| Flag | Default | Effect |
|---|---|---|
| `enableHybridReads` | true | Use BM25+vector hybrid; false = legacy top-N memory only |
| `enableDynamicTopK` | true | Adaptive token budget and candidate cap |
| `enableConversationMemoryEmbeddings` | true | Embed extracted memories for vector retrieval |
| `enableQueryIntentClassification` | true | Use `QueryIntentClassifier` for structured context |
| `enableGraphBoostedScoring` | false | Add graph traversal boost to hybrid scoring |
| `enableKnowledgeGraph` | false | Build KG edges on entity save via LLM |
| `graphMaxHops` | 2 | Depth limit for graph traversal |
| `graphBoostWeight` | 0.15 | Weight of graph signal in composite score |

---

## Interview Q&A for This Feature

**Q: Why did you implement BM25 yourself instead of using Elasticsearch or a BM25 library?**  
A: The candidate pool is per-user and small (60 memories max). Adding Elasticsearch for per-user keyword search was significant operational complexity for a dataset that fits comfortably in a Postgres query. The custom BM25 in `bm25Score()` runs in memory on a pre-fetched list and adds no network latency. If the dataset scaled to hundreds of thousands of memories per user, I'd revisit this.

**Q: How do you handle memory conflicts / outdated facts?**  
A: The extraction LLM detects `"updates"` relations pointing to existing memories (`mem_0`..`mem_19`). When a conflict is detected, `handleRelation()` sets `isLatest=false` and `active=false` on the old node, sets `nextVersionId` to the new node's ID, and creates a `MemoryRelationship` edge with type "updates". The retrieval query only fetches `isLatest=true` nodes.

**Q: Why async memory extraction? What if it fails?**  
A: Extraction runs `@Async` because it calls OpenAI (potentially 1-2s latency) after the response is already streaming. A failure in extraction — caught by the try/catch in `extractAndStore()` — logs a warning but never affects the user's chat experience. The worst case is that the memory isn't stored; the chat still works perfectly.

**Q: How does the system handle short queries that don't need context?**  
A: `buildPlan()` returns `RetrievalPlan.empty()` immediately if `userQuery.isBlank()`. The assembled context block is skipped (`PromptAssemblyService.buildFullSystemPrompt()` returns the base prompt unchanged if context is empty). This avoids unnecessary DB queries on simple greetings.

**Q: What's the token budget strategy?**  
A: `computeTokenBudget()` uses a base of 1400 tokens, adds up to 500 for complex queries (estimated from query length × 2), clamped to 900–1900. `computeDynamicCap()` sets a cap of 8–24 candidates depending on whether the query is "long" (>30 tokens) or contains comparison words. A `minK=6` ensures at least 6 candidates are always included even if they'd exceed the budget.
