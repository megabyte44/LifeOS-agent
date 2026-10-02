# LifeOS v3

A focused life management application built with Spring Boot and Next.js. Combines task and habit tracking, daily planning, and note-taking with a context-aware AI assistant that understands your schedule, habits, and notes in real time.

## 🚀 Features

- **Dashboard** - Unified view of your day — habits, todos, planner, and water intake at a glance.
- **Task Management** - Full todo list with priorities and a daily planner
- **Habit Tracking** - Build streaks, visualise last-7-day completion grids, get milestone insights
- **Note Taking** - Create and organise notes with markdown support; notes are fully searchable by the AI
- **AI Chat** - Context-aware assistant powered by a 3-layer RAG system (vector search + live SQL snapshots + long-term memory extraction)
- **Notifications** - Web push notifications with insight-driven alerts (streak breaks, milestones, overdue todos)
- **Offline Support** - Progressive Web App with offline capabilities

## 🏗️ Tech Stack

### Backend
- **Framework**: Spring Boot 4.0.3
- **Language**: Java 17
- **Database**: PostgreSQL 16 with `pgvector`
- **Migration**: Flyway (32 versioned migrations)
- **Authentication**: Firebase Admin SDK
- **Security**: AES-256-GCM encryption for credential vault
- **Push Notifications**: Web Push (VAPID)
- **Containerization**: Docker

### Frontend
- **Framework**: Next.js 16 (App Router, React 19)
- **Language**: TypeScript
- **UI Library**: shadcn/ui + Radix UI primitives
- **Styling**: Tailwind CSS
- **State Management**: TanStack React Query v5
- **PWA**: `@ducanh2912/next-pwa` (offline caching + service worker)
- **AI Streaming**: Server-Sent Events (SSE) bridge
- **Drag & Drop**: `@dnd-kit`

## 📋 Prerequisites

- **Java 17** or higher
- **Node.js 20** or higher
- **PostgreSQL 16** with `pgvector` (or use Docker)
- **Maven** (bundled with project wrapper)
- **Firebase Project** (for authentication)

## 🛠️ Setup Instructions

### 1. Clone the Repository

```bash
git clone https://github.com/megabyte44/LifeOS-agent.git
cd LifeOS-v3
```

### 2. Backend Setup

1. Copy the environment template:
   ```powershell
   # Windows PowerShell
   Copy-Item backend\.env.example backend\.env
   ```
   ```bash
   # Linux / macOS
   cp backend/.env.example backend/.env
   ```

2. Edit `backend/.env` with your credentials:
   - Database credentials
   - Firebase service account (or `FIREBASE_SERVICE_ACCOUNT_BASE64`)
   - AES encryption key (`openssl rand -base64 32`)
   - AI provider API keys (Gemini, OpenRouter, or OpenAI)

3. Start PostgreSQL + pgvector in Docker:
   ```bash
   docker compose up db -d
   ```

4. Run the Spring Boot backend:
   ```powershell
   # Windows PowerShell
   cd backend
   .\mvnw.cmd spring-boot:run
   ```
   ```bash
   # macOS / Linux
   cd backend
   ./mvnw spring-boot:run
   ```
   The backend will be available at `http://localhost:8080`.

### 3. Frontend Setup

1. Navigate to frontend directory:
   ```bash
   cd frontend
   npm install
   ```

2. Configure `frontend/.env` (or `.env.local`):
   ```env
   NEXT_PUBLIC_API_BASE_URL=http://localhost:8000
   NEXT_PUBLIC_FIREBASE_API_KEY=your_api_key
   NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN=your_project.firebaseapp.com
   NEXT_PUBLIC_FIREBASE_PROJECT_ID=your_project_id
   # ... see frontend/.env.example for full details
   ```

3. Run development server:
   ```bash
   npm run dev
   ```
   The frontend will be available at `http://localhost:9002`.

---

## 🚢 Production Deployment

For full cloud production deployment (Vercel + Railway/Render + Neon PostgreSQL) or self-hosted VPS, refer to the complete deployment guide:

📖 **[Production Deployment Guide](./docs/DEPLOYMENT.md)**
- Cloud Architecture & Recommended Hosting
- Required Keys & Step-by-Step Generation (Firebase, VAPID, AES-256)
- Complete Environment Variables Matrix for Frontend & Backend
- Step-by-Step Vercel & Railway Deployment
- CORS Configuration & Common Troubleshooting

---

## 🐳 Docker Deployment (Full Stack)

Build and run the entire stack (PostgreSQL + Spring Boot + Next.js) with Docker Compose:

```bash
docker compose up --build
```

Services:
- **Frontend UI**: `http://localhost:9002`
- **Backend API**: `http://localhost:8000`
- **PostgreSQL + pgvector**: `localhost:5432`

## 🤖 AI Architecture

The AI chat assistant uses a three-layer context assembly pipeline built on top of PostgreSQL + pgvector:

- **Layer 1 — RAG / Vector Search**: every note, todo, habit, planner item, and past conversation is embedded on save and stored in pgvector. On each message the user's query is vectorised and the top semantically similar chunks are retrieved via cosine similarity, merged with BM25-scored conversation memories, and ranked by a composite score (similarity × quality × recency × access-boost).
- **Layer 2 — Structured SQL Snapshots**: a fast keyword-based intent classifier (HABITS / TODOS / SCHEDULE / NOTES / GENERAL) gates targeted SQL queries so the AI always sees live, exact data — current streak counts, today's schedule, pending todos.
- **Layer 3 — Memory Graph Profile**: facts learned from past conversations (name, preferences, life events) are extracted asynchronously after each chat turn and injected as a persistent user profile on every future request.

After each response the system asynchronously extracts new user facts, embeds the conversation turn, and evaluates RAG quality (faithfulness, answer relevancy, context precision).

## 📚 API Documentation

The backend exposes RESTful APIs for:
- User management and admin
- Tasks and todos
- Habits
- Daily planner
- Notes
- Notifications and web push
- AI chat (streaming SSE + conversation history)
- User preferences and profile

See [API_CONTRACT.md](frontend/src/services/API_CONTRACT.md) for detailed API documentation.

## 🔒 Security

- Firebase Authentication for user management (JWT validated on every request)
- CORS configured for frontend origin
- All data scoped strictly to the authenticated user's UID
- Environment-based configuration — no secrets in source control

## 🧪 Testing

### Backend
```bash
cd backend
./mvnw test
```

### Frontend
```bash
cd frontend
npm run test
```

## 📱 Progressive Web App

The application can be installed as a PWA on supported devices:
- Offline functionality
- Push notifications
- Native app experience

## 🤝 Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request


## 🆘 Troubleshooting

### Database Connection Issues
- Ensure PostgreSQL is running
- Check database credentials in `.env` or `application.yaml`
- Verify connection string format

### Firebase Authentication Errors
- Verify `firebase-service-account.json` is in the correct location
- Check Firebase project configuration
- Ensure Firebase Auth is enabled in your project

### Frontend Build Errors
- Clear Next.js cache: `rm -rf .next`
- Clear node_modules: `rm -rf node_modules && npm install`
- Check Node.js version compatibility

### Maven Wrapper Error on Windows
- Symptom: `'powershell' is not recognized` followed by `Cannot start maven from wrapper`
- Cause: Maven wrapper launcher cannot find a PowerShell executable from `PATH`
- Fix in this repository: `backend/mvnw.cmd` now checks `powershell`, `pwsh`, and `%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`
- Run commands from `backend`:
   - `./mvnw.cmd -v`
   - `./mvnw.cmd clean install -DskipTests`
   - `./mvnw.cmd spring-boot:run`

## 📞 Support

For issues and questions, please open an issue in the repository.

---

**Built with ❤️ for better life management**
