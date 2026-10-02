# LifeOS AI Architecture

This document breaks down the end-to-end architecture of the LifeOS AI system into four distinct components. Each flow prioritizes conceptual responsibilities over the specific technology stack to provide clear, interview-ready diagrams.

---

## 1️⃣ Chat Request Flow

**Purpose:** Securely handle incoming user messages, validate session integrity, and safely persist the initial request before passing it to the heavy-lifting RAG pipeline.

```text
                                    User Submits Chat
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │     API / Web Layer         │
                             │                             │
                             │ • Token Validation (JWT)    │
                             │ • Rate Limiting & Quotas    │
                             │ • Input Sanitization        │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │    Session Management       │
                             │                             │
                             │ • Load Chat Session         │
                             │ • Verify Ownership          │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │    Message Persistence      │
                             │                             │
                             │ • Save Request to DB        │
                             │   (Status: 'PENDING')       │
                             │ • Update Session Timestamp  │
                             └─────────────────────────────┘
                                            │
                                            ▼
                              Proceed to Context Retrieval
                                      (Module 2)
```

**Tech Used**
---------
**Backend**      : Spring Boot (REST Controllers, Filters)
**Security**     : JWT Validation, Rate Limiter
**Database**     : PostgreSQL (Relational tables for Users & Sessions)

---

## 2️⃣ Context Retrieval Pipeline (RAG)

**Purpose:** Intelligently understand the user's intent, pull relevant data from all interconnected modules (multi-source retrieval), and rank them to provide the LLM with the most meaningful context.

```text
                             Proceeds from Chat Request
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │  Query Understanding Phase  │
                             │                             │
                             │ • Intent Classification     │
                             │   (General? Search? Task?)  │
                             │ • Query Expansion           │
                             │   (Synonyms, Timeframes)    │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │   Multi-Source Retrieval    │
                             │                             │
                             │ ┌───────┐ ┌───────┐ ┌─────┐ │
                             │ │ Notes │ │ Todos │ │Habit│ │
                             │ └───────┘ └───────┘ └─────┘ │
                             │ ┌───────┐ ┌───────┐         │
                             │ │ Plans │ │Memory │         │
                             │ └───────┘ └───────┘         │
                             └──────────────┬──────────────┘
                                            │
                     ┌──────────────────────┴──────────────────────┐
                     │                                             │
                     ▼                                             ▼
          Semantic Vector Search                        Lexical Keyword Search
       (Cosine Distance on vectors)                   (Exact Match, Frequency)
                     │                                             │
                     └──────────────────────┬──────────────────────┘
                                            ▼
                             ┌─────────────────────────────┐
                             │    Merge & Rank (RRF)       │
                             │                             │
                             │ • Reciprocal Rank Fusion    │
                             │ • Boost by Recency          │
                             │ • Boost by Intent Match     │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │      Context Optimizer      │
                             │                             │
                             │ • Deduplicate Results       │
                             │ • Enforce Token Limits      │
                             │ • Format to Structured Text │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             Proceed to Prompt Construction
                                      (Module 3)
```

**Tech Used**
---------
**Backend**           : Spring Boot
**Vector Store**      : pgvector (PostgreSQL extension)
**Lexical Search**    : Hibernate Search / BM25
**Ranking Algorithm** : Custom RRF implementation

---

## 3️⃣ Prompt Construction → LLM → Streaming

**Purpose:** Marry the system instructions with the retrieved context and user query. Connect out to the LLM Gateway and process the response in real-time (streaming) so the user experiences zero lag.

```text
                             Proceeds from Context Retrieval
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │    Prompt Builder Phase     │
                             │                             │
                             │ • Load Configured Persona   │
                             │ • Inject Processed Context  │
                             │ • Inject Chat History       │
                             │ • Append User Query         │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │      LLM Gateway Layer      │
                             │                             │
                             │ • Load API Keys             │
                             │ • Map Provider Overrides    │
                             │ • Construct JSON Payload    │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │    External LLM Service     │
                             │                             │
                             │ • Process Generation        │
                             │ • Yield Token Chunks        │
                             └──────────────┬──────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │     Response Streaming      │
                             │                             │
                             │ • Parse Server-Sent Events  │
                             │ • Accumulate Full Buffer    │
                             └──────────────┬──────────────┘
                                            │
                        ┌───────────────────┴───────────────────┐
                        │                                       │
                        ▼                                       ▼
             Stream Chunk to Client                 When Stream Finishes
                        │                                       │
                        ▼                                       ▼
               Frontend UI Receives              ┌─────────────────────────────┐
              Real-time Text updates             │    Message Finalization     │
                                                 │                             │
                                                 │ • Save full Assistant reply │
                                                 │ • Update metrics/tokens     │
                                                 └──────────────┬──────────────┘
                                                                │
                                                                ▼
                                                      Proceed to Async Memory
                                                            (Module 4)
```

**Tech Used**
---------
**LLM Gateway**   : OpenRouter
**Provider Models**: Gemini / Claude / GPT
**Streaming**     : Server-Sent Events (SSE) via WebFlux / Reactor
**Backend**       : Spring Boot

---

## 4️⃣ Async Memory & Embedding Pipeline

**Purpose:** To extract long-term memory, summarize facts, and create vectors off the critical path. This ensures the request/response cycle stays lightning fast while database persistence happens reliably in the background without data loss.

```text
                                Proceed from Message Finalization
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │       Event Publisher       │
                             │                             │
                             │ • Publish Async Payload     │
                             │   (ChatCompletedEvent)      │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │ Transactional Event Listener│
                             │                             │
                             │ • Await AFTER_COMMIT hook   │
                             │ • Ensure data consistency   │
                             └─────────────────────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │    Background Task Pool     │
                             │                             │
                             │ • Isolate from Request path │
                             │ • Thread Pool Executor      │
                             └──────────────┬──────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │      Memory Extraction      │
                             │                             │
                             │ • Evaluate Chat Content     │
                             │ • Extract Important Facts   │
                             │ • Chunk Long Form Text      │
                             └──────────────┬──────────────┘
                                            │
                                            ▼
                             ┌─────────────────────────────┐
                             │     Embedding Generation    │
                             │                             │
                             │ • Call Embedding Service    │
                             │ • Map Text to 1536-d Vector │
                             └──────────────┬──────────────┘
                                            │
                                            ▼
                                     ┌─────────────┐
                                     │   API Call  │
                                     │  Succeeded? │
                                     └──────┬──────┘
                                            │
                     ┌──────────────────────┴─────────────────────┐
                     │ (Yes)                                (No)  │
                     ▼                                            ▼
      ┌─────────────────────────────┐               ┌─────────────────────────────┐
      │     Memory Persistence      │               │       Retry Mechanism       │
      │                             │               │                             │
      │ • Insert Vector into DB     │               │ • Exponential Backoff Delay │
      │ • Update Vector Index       │               │ • Check Max Retry limit     │
      └──────────────┬──────────────┘               └──────────────┬──────────────┘
                     │                                             │
                     ▼                                             ▼
            System Available                       ┌───────────────┴───────────────┐
            For Future Queries                     │                               │
                                                   ▼                               ▼
                                            Retry Allowed                Max Retries Exceeded
                                                   │                               │
                                                   ▼                               ▼
                                             Re-queue Task           ┌─────────────────────────────┐
                                                                     │      Dead Letter Queue      │
                                                                     │                             │
                                                                     │ • Log permanent failure     │
                                                                     │ • Queue for manual review   │
                                                                     └─────────────────────────────┘
```

**Tech Used**
---------
**Concurrency**   : Spring `@Async`, CoreThreadPool
**Event System**  : Spring ApplicationEvents (`@TransactionalEventListener`)
**Embeddings**    : OpenAI / Gemini Embedding API
**Vector DB**     : pgvector
**Resilience**    : Spring Retry (Exponential Backoff)
