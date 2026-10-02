# 01 — Project Overview

## What Is LifeOS?

LifeOS is a personal life-management platform with an embedded AI second brain. It centralises a user's notes, habits, daily planner, todos, and push notifications under a single authenticated interface, with a context-aware AI chat that uses a three-layer Retrieval-Augmented Generation (RAG) pipeline to answer questions about the user's own life.

## Problem It Solves

People spread their personal data across apps: notes in Notion, habits in Streaks, calendar in Google Calendar, todos in Todoist. None of these apps talk to each other, and none of them can answer questions like "What did I commit to last week?" or "How are my habits trending this month?". LifeOS consolidates everything and makes it queryable through natural language.

## Core Features

- **Notes** — rich-text capture with automatic embedding on save (via `EmbeddingEventListener`)
- **Habits** — daily streak tracking with per-user cycle configuration (`CycleConfig`, `Habit` entities)
- **Planner** — day-view schedule with drag-and-drop reordering (`PlannerItem`, `updateDay`)
- **Todos** — lightweight checklist with soft-delete (`TodoItem`, `findByIdAndDeletedFalse`)
- **AI Chat** — streaming chat with two modes: `normal` (structured RAG context) and `chat_buddy` (extracts and stores memories from conversation)
- **Memory Graph** — `ConversationMemory` nodes versioned with `isLatest`, `updates`/`extends`/`derives` edges stored in `MemoryRelationship`
- **Push Notifications** — VAPID Web Push subscriptions stored in `PushSubscription`, dispatched by `WebPushService`
- **Admin Panel** — user management, AI config override, RAG evaluation dashboard, system settings, announcements
- **PWA** — service worker, offline page, installable on mobile

## Tech Stack

| Layer | Technology | Key Classes / Files |
|---|---|---|
| Backend framework | Spring Boot 4.x (Java 17) | `LifeOsBackendApplication.java` |
| Auth | Firebase Admin SDK | `FirebaseAuthenticationFilter`, `SecurityConfig` |
| Database | PostgreSQL + pgvector | `Embedding.java` stores `float[]` vectors |
| Migrations | Flyway | `backend/src/main/resources/db/migration/` |
| ORM | Spring Data JPA + Hibernate | All `*Repository.java` interfaces |
| AI chat | OpenAI / OpenRouter / Gemini | `AiChatService`, `AiConfigurationResolver` |
| Embeddings | text-embedding-3-small (1536-d) | `OpenAiEmbeddingClient`, `EmbeddingService` |
| Streaming | Spring WebFlux `WebClient` + `SseEmitter` | `AiChatService.streamChat()` |
| Frontend | Next.js 16 + React 19 + TypeScript | `frontend/src/` |
| UI | shadcn/ui + Radix UI + Tailwind | `frontend/src/components/ui/` |
| Data fetching | TanStack React Query v5 | `frontend/src/providers/query-provider.tsx` |
| HTTP client | Custom `apiClient` (wraps fetch) | `frontend/src/lib/api-client.ts` |
| PWA | `@ducanh2912/next-pwa` | `frontend/public/sw.js`, `manifest.json` |
| Push | Web Push (VAPID) | `WebPushService`, `PushSubscription` entity |

## Repo Structure

```
LifeOS-v3/
├── backend/
│   ├── src/main/java/com/lifos/backend/
│   │   ├── config/          # AppConfig, SecurityConfig, FirebaseConfig, AiFoundationProperties
│   │   ├── controller/      # 12 REST controllers
│   │   ├── dto/             # ~30 request/response DTOs
│   │   ├── entity/          # JPA entities (Note, Habit, TodoItem, Embedding, ConversationMemory…)
│   │   ├── event/           # Spring event listeners for async embedding
│   │   ├── repository/      # Spring Data JPA repositories
│   │   ├── security/        # FirebaseAuthenticationFilter, SecurityUtils
│   │   └── service/         # All business logic (20+ services)
│   ├── pom.xml              # Spring Boot 4, Firebase Admin, pgvector, Web Push
│   ├── Dockerfile
│   └── docker-compose.yml   # PostgreSQL + pgvector service
├── frontend/
│   ├── src/
│   │   ├── app/             # Next.js App Router pages
│   │   ├── components/      # layout/, chat/, ui/, onboarding/
│   │   ├── hooks/           # useAuth, useAdminCheck, api/ hooks
│   │   ├── lib/             # api-client.ts, firebase.ts, notifications.ts
│   │   ├── services/        # Per-domain service files (note, habit, todo…)
│   │   └── types/           # Shared TypeScript types
│   └── public/              # sw.js, manifest.json (PWA assets)
└── docs/                    # Architecture notes, RAG design docs
```

## Scale / Impact

- [PLACEHOLDER — fill in: e.g., personal project / X active users / used daily by N people]

## My Role

- [PLACEHOLDER — fill in: e.g., sole developer / built backend from scratch / designed RAG pipeline]

## One-Liner Resume Bullet

> Built a full-stack personal AI second brain (Spring Boot 4 + Next.js 16) with a three-layer RAG pipeline (BM25 + pgvector + knowledge graph) and streaming SSE chat that auto-extracts versioned memory facts from conversation.
