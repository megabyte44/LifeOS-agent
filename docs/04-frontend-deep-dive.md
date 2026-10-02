# 04 — Frontend Deep Dive

## Framework

**Next.js 16 + React 19 + TypeScript** using the **App Router** (file-system routing in `frontend/src/app/`).

Every page directory has its own `page.tsx`, `loading.tsx`, and `error.tsx` — Next.js renders loading and error boundaries automatically. The root `layout.tsx` wraps the whole app in `QueryProvider` (TanStack React Query) and `AuthProvider` (Firebase auth context).

---

## Routing (App Router)

```
app/
  page.tsx           — root redirect → /dashboard
  layout.tsx         — root layout: QueryProvider + AuthProvider
  login/page.tsx     — Google sign-in
  dashboard/page.tsx
  notes/page.tsx
  habits/page.tsx
  planner/page.tsx
  ai-chat/page.tsx   — streaming chat UI (the most complex page)
  admin/             — nested admin layout with auth guard
    page.tsx         — admin dashboard
    users/page.tsx
    ai-config/page.tsx
    analytics/page.tsx
    announcements/page.tsx
    rag-evaluation/page.tsx
  api/               — Next.js route handlers (server-side)
    ai/chat/stream/route.ts   — SSE proxy to Spring Boot
    push/subscribe/route.ts   — Web Push subscription
    push/unsubscribe/route.ts
    push/send-test/route.ts
    health/route.ts
```

The `admin/` subtree uses `useAdminCheck` hook — it checks the `GET /admin/users` endpoint and redirects non-admins to `/dashboard`.

---

## Data Fetching

All server-state is managed by **TanStack React Query v5** via `QueryProvider` in `frontend/src/providers/query-provider.tsx`.

API calls go through `apiClient` in `frontend/src/lib/api-client.ts`:
- Auto-injects Firebase ID token: `await user.getIdToken()` → `Authorization: Bearer <token>`
- On 401: lazy-imports Firebase and calls `signOut(auth)` — React Query cache clears on sign-out, AppLayout redirects to `/login`
- `AbortSignal.timeout(30_000)` on every request
- Typed generic: `apiClient.get<T>()`, `apiClient.post<T>()`, `apiClient.put<T>()`, `apiClient.delete<T>()`
- `NEXT_PUBLIC_USE_MOCK_API=true` toggles a mock layer for offline development

Domain-specific service files in `frontend/src/services/`:

| File | Responsibilities |
|---|---|
| `ai-chat.service.ts` | `listConversations()`, `getConversationMessages()`, `deleteConversation()`, `renameConversation()` |
| `note.service.ts` | CRUD notes |
| `habit.service.ts` | CRUD habits, toggle completion |
| `todo.service.ts` | CRUD todos |
| `planner.service.ts` | day fetch, create, update day schedule |
| `push.service.ts` | subscribe, unsubscribe, test notification |
| `admin.service.ts` | `getUsers()`, `updateUserRole()`, `getDashboardStats()`, `getAnalytics()` |

---

## State Management

No global client-state library (no Redux/Zustand). State is split:

- **Server state** → TanStack React Query (cache, refetch, loading/error states)
- **Auth state** → React Context in `useAuth()` hook: `user`, `loading`, `isSigningIn`, `signInWithGoogle()`, `signOut()`
- **UI / ephemeral state** → local `useState` in each page component
- **Session persistence** → `sessionStorage.setItem('lastConversationId', id)` in the chat page to restore the last open conversation on reload

The `useAuth` context listens via `onAuthStateChanged(auth, callback)` with an 8-second failsafe timeout if Firebase never responds.

---

## Key Components

### `AppLayout` (`components/layout/AppLayout.tsx`)
Wraps every page. Renders the sidebar navigation and the mobile bottom nav. Guards the whole app — if `loading=false && user=null && !isSigningIn`, redirects to `/login`.

### `AiChatContent` (`app/ai-chat/page.tsx`)
The most complex component in the frontend:
- `ChatSidebar` — lightweight conversation list (metadata only, lazy-loads messages on select)
- `ChatInputBar` — message textarea, mode toggle (normal / chat_buddy), personality selector
- `ChatMessageBubble` — renders markdown via `react-markdown` + `remark-gfm`
- `WelcomeScreen` — suggested prompts, personality picker, mode selector

SSE streaming implementation:
1. `fetch('/api/ai/chat/stream', { method: 'POST', ... })` (Next.js route handler proxies to Spring)
2. `response.body.getReader()` — manually reads SSE frames
3. Named SSE events: `conversation_id` (first event, wires up the sidebar), `done` (stream complete), unnamed data events (token chunks)
4. Optimistic UI: user message + empty AI placeholder added to state before the stream starts; AI placeholder content fills in token-by-token via `setMessages(prev => prev.map(m => m.id === aiMsgId ? {...m, content: fullContent} : m))`

### `quick-capture.tsx`
Global keyboard shortcut (Cmd/Ctrl + K equivalent) for fast note capture from anywhere in the app.

### `command-palette.tsx`
App-wide command palette (similar to Cmd+P in VS Code) — search across pages and actions.

### `OnboardingTour` (`components/onboarding/OnboardingTour.tsx`)
First-run guided tour, shown once to new users.

---

## Performance Decisions

- **React Query cache** prevents redundant fetches across navigations — notes list doesn't re-fetch when you navigate away and back
- **Lazy conversation loading** in chat sidebar: the sidebar only loads conversation metadata (`listConversations()`); messages are fetched only when a conversation is selected (`getConversationMessages(id)`)
- **Service worker** (`public/sw.js` via `@ducanh2912/next-pwa`) caches static assets and handles offline — `app/offline/page.tsx` shown when network is unavailable
- **Streaming over polling** — chat uses SSE streaming so the first token appears instantly instead of waiting for the full response
- `AbortSignal.timeout(30_000)` prevents requests from hanging indefinitely

---

## Quality Gates

- **TypeScript** with `tsconfig.json` (strict mode implied by `tsc --noEmit` in `package.json` scripts)
- **ESLint** via `eslint-config-next` (`.eslintrc.json`)
- **`next build --webpack`** catches type errors and module resolution issues at build time
- Zod for form validation via `react-hook-form` + `@hookform/resolvers`

---

## PWA Details

- `frontend/public/manifest.json` — app name, icons, `display: "standalone"`
- `frontend/public/sw.js` — generated by `next-pwa`, Workbox-based
- `frontend/public/fallback-ce627215c0e4a9af.js` — offline fallback chunk
- Web Push: `notifications.ts` handles `Notification.requestPermission()` and `PushManager.subscribe()`, sends subscription to `POST /push/subscribe` (Spring Boot), which stores it in `PushSubscription` table and dispatches via `WebPushService` (VAPID)
