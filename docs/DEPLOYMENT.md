# LifeOS Production Deployment Guide

A comprehensive, step-by-step guide for deploying LifeOS to production.

---

## 🏛️ 1. Architecture Overview

For optimal performance, resilience, and free/low-cost tiers, the recommended production setup separates concerns:

```
┌────────────────────────────────────────────────────────┐
│                   Users / Browsers                     │
└──────────────┬──────────────────────────┬──────────────┘
               │                          │
   Static Assets & UI                     │ API Calls & SSE Stream
               ▼                          ▼
┌─────────────────────────────┐  ┌────────────────────────────────┐
│      Frontend (Vercel)      │  │    Backend (Railway/Render)    │
│  Next.js 16 + React 19 PWA  │  │ Spring Boot 4 + Java 17 Docker │
└──────────────┬──────────────┘  └────────┬──────────────┬────────┘
               │                          │              │
        Firebase Auth                     │              │ Embeddings / Chat
               ▼                          ▼              ▼
┌─────────────────────────────┐  ┌────────────────┐ ┌────────────────────┐
│      Firebase Project       │  │ Database (Neon)│ │   AI LLM Gateway   │
│ Identity & Token Generation │  │  PostgreSQL 16 │ │ Gemini/OpenRouter/ │
└─────────────────────────────┘  │   + pgvector   │ │ OpenAI/HuggingFace │
                                 └────────────────┘ └────────────────────┘
```

| Component | Recommended Platform | Alternative Platforms |
| :--- | :--- | :--- |
| **Frontend** | [Vercel](https://vercel.com) | Netlify, Cloudflare Pages, AWS Amplify |
| **Backend** | [Railway](https://railway.app) | Render, Fly.io, Google Cloud Run, Hugging Face Spaces |
| **Database** | [Neon](https://neon.tech) | Supabase, AWS RDS Aurora Postgres, Railway Postgres |
| **Auth** | [Firebase Auth](https://firebase.google.com) | — |

---

## 📋 2. Prerequisites & Credentials Checklist

Before deploying, collect the following credentials:

### 1. Database (PostgreSQL 16 + `pgvector`)
- Create a project on [Neon.tech](https://neon.tech) (or [Supabase](https://supabase.com)).
- Enable the vector extension in the SQL editor:
  ```sql
  CREATE EXTENSION IF NOT EXISTS vector;
  ```
- Copy your pooled JDBC connection string:
  ```text
  jdbc:postgresql://<user>:<password>@<neon-host>/<dbname>?sslmode=require
  ```

### 2. Firebase Credentials
- **Client Configuration** (from Firebase Console → Project Settings → General → Web App):
  - `API_KEY`, `AUTH_DOMAIN`, `PROJECT_ID`, `STORAGE_BUCKET`, `MESSAGING_SENDER_ID`, `APP_ID`
- **Admin Service Account** (from Project Settings → Service Accounts → Generate new private key):
  - Download the JSON key file.
  - To safely provide this to cloud providers without committing files, encode it to Base64:
    ```powershell
    # Windows PowerShell:
    [Convert]::ToBase64String([System.IO.File]::ReadAllBytes("path\to\service-account.json"))
    ```
    ```bash
    # Linux / macOS:
    base64 -w 0 path/to/service-account.json
    ```

### 3. Encryption Secret Key (AES-256-GCM)
Used to encrypt sensitive user vault data in the database:
```bash
openssl rand -base64 32
```

### 4. Web Push Notification Keys (VAPID)
Generate a VAPID keypair for web notifications:
```bash
npx web-push generate-vapid-keys
```

### 5. AI Provider API Keys
Obtain an API key for your chosen provider:
- **Google Gemini** (Free tier available): [Google AI Studio](https://aistudio.google.com/app/apikey)
- **OpenRouter** (Unified API for OpenAI, Claude, DeepSeek, and open-source models): [OpenRouter](https://openrouter.ai/keys)
- **OpenAI** (Optional direct access): [OpenAI Platform](https://platform.openai.com)

---

## ⚙️ 3. Environment Variables Reference

### Backend (Server-Side Secrets)
Configure these in your backend hosting dashboard (Railway / Render / Container):

| Variable | Description | Example / Format | Required? |
| :--- | :--- | :--- | :---: |
| `SERVER_PORT` | Port Spring Boot listens on | `8080` (or host `$PORT`) | Yes |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC connection URL | `jdbc:postgresql://ep-...aws.neon.tech/lifeos?sslmode=require` | Yes |
| `SPRING_DATASOURCE_USERNAME` | Database username | `neondb_owner` | Yes |
| `SPRING_DATASOURCE_PASSWORD` | Database password | `your-db-password` | Yes |
| `ENCRYPTION_SECRET_KEY` | 32-byte Base64 AES key | Output of `openssl rand -base64 32` | Yes |
| `FIREBASE_SERVICE_ACCOUNT_BASE64` | Base64-encoded service account JSON | `ewogICJ0eXBlIj...` | Yes |
| `AI_PROVIDER` | Active AI provider (`gemini`, `openrouter`, `openai`) | `gemini` | Yes |
| `AI_MODEL` | Default model override | `gemini-2.0-flash` | No |
| `GEMINI_API_KEY` | Google Gemini API key | `AIzaSy...` | If using Gemini |
| `OPENROUTER_API_KEY` | OpenRouter API key | `sk-or-v1-...` | If using OpenRouter |
| `OPENAI_API_KEY` | OpenAI API key | `sk-proj-...` | If using OpenAI |
| `OPENAI_EMBEDDING_MODEL` | Embedding model slug | `text-embedding-3-small` | No (default set) |
| `VAPID_PUBLIC_KEY` | Public VAPID key | `BG9lS2...` | Optional (Push) |
| `VAPID_PRIVATE_KEY` | Private VAPID key | `DkYPYT...` | Optional (Push) |
| `VAPID_SUBJECT` | Contact URI for push notifications | `mailto:admin@yourdomain.com` | Optional (Push) |

> [!TIP]
> On platforms with reserved environment variables (like Hugging Face Spaces), you can prefix any AI variable with `LIFOS_` (e.g. `LIFOS_AI_PROVIDER`, `LIFOS_OPENROUTER_API_KEY`). The backend automatically checks `LIFOS_*` first before falling back.

---

### Frontend (Client & Edge Environment Variables)
Configure these in your Vercel project settings:

| Variable | Description | Exposed to Browser? | Required? |
| :--- | :--- | :---: | :---: |
| `NEXT_PUBLIC_API_BASE_URL` | Public HTTPS URL of the deployed backend | Yes | Yes |
| `NEXT_PUBLIC_FIREBASE_API_KEY` | Firebase Web API Key | Yes | Yes |
| `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` | Firebase Auth Domain | Yes | Yes |
| `NEXT_PUBLIC_FIREBASE_PROJECT_ID` | Firebase Project ID | Yes | Yes |
| `NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET` | Firebase Storage Bucket | Yes | Yes |
| `NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID` | Firebase Messaging Sender ID | Yes | Yes |
| `NEXT_PUBLIC_FIREBASE_APP_ID` | Firebase Web App ID | Yes | Yes |
| `NEXT_PUBLIC_VAPID_PUBLIC_KEY` | Public VAPID Key | Yes | Optional |
| `VAPID_PRIVATE_KEY` | Private VAPID Key (for serverless push actions) | No | Optional |
| `VAPID_SUBJECT` | Contact mailto | No | Optional |
| `CRON_SECRET` | Secret protecting `/api/cron/*` endpoints | No | Optional |

---

## 🚀 4. Step-by-Step Deployment Walkthrough

### Step 1: Deploy Database (Neon.tech)
1. Log in to [Neon.tech](https://neon.tech) and create a database named `lifeos`.
2. Open the **SQL Editor** in Neon and execute:
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ```
3. Copy the pooled connection string (with `?sslmode=require`).

---

### Step 2: Deploy Backend (Railway.app)
1. Log in to [Railway](https://railway.app) and create a **New Project** → **Deploy from GitHub repo**.
2. Select your `LifeOS-agent` (or `LifeOS-v3`) repository.
3. Open the service **Settings**:
   - **Root Directory**: Set to `/backend`
   - **Builder**: Select `Dockerfile`
   - **Healthcheck Path**: Set to `/health`
   - **Healthcheck Timeout**: `120` seconds
4. Open the service **Variables** tab and add all the **Backend Environment Variables** listed above.
5. In **Networking**, click **Generate Domain** (e.g. `https://lifeos-backend-production.up.railway.app`).
6. Deploy the service. Flyway will automatically execute migrations `V1` through `V32` upon startup.
7. Test the health endpoint in your browser:
   ```text
   https://your-backend.up.railway.app/health
   ```
   You should receive a `200 OK` response:
   ```json
   {
     "status": "UP",
     "db": "UP",
     "dbType": "PostgreSQL",
     "resolvedAiProvider": "gemini",
     "resolvedAiModel": "gemini-2.0-flash",
     "hasResolvedAiApiKey": "true"
   }
   ```

---

### Step 3: Deploy Frontend (Vercel)
1. Log in to [Vercel](https://vercel.com) and click **Add New Project**.
2. Import the `LifeOS-agent` repository.
3. In **Project Settings**:
   - **Framework Preset**: Next.js
   - **Root Directory**: `frontend`
4. Expand **Environment Variables** and enter all the **Frontend Environment Variables**:
   - Set `NEXT_PUBLIC_API_BASE_URL` to your backend URL from Step 2 (e.g. `https://lifeos-backend-production.up.railway.app`).
5. Click **Deploy**.

---

### Step 4: Configure CORS & Domains
The backend CORS policy is defined in [`WebConfig.java`](file:///d:/MyProjects/LifeOS-v3/backend/src/main/java/com/lifos/backend/config/WebConfig.java):
```java
registry.addMapping("/**")
        .allowedOriginPatterns(
            "http://localhost:9002",
            "http://localhost:3000",
            "https://*.vercel.app",       // Automatically matches all Vercel previews & production
            "https://www.lifeos.page"
        )
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true);
```
If you add a custom domain (e.g. `https://app.yourdomain.com`), append it to `allowedOriginPatterns` in [`WebConfig.java`](file:///d:/MyProjects/LifeOS-v3/backend/src/main/java/com/lifos/backend/config/WebConfig.java).

---

## 🐳 Alternative: Self-Hosted All-in-One (Docker VPS)

If you prefer deploying the entire stack onto a single Linux VPS (Ubuntu/Debian on DigitalOcean, Hetzner, AWS EC2):

1. Clone the repository on the server:
   ```bash
   git clone https://github.com/megabyte44/LifeOS-agent.git
   cd LifeOS-agent
   ```
2. Populate `backend/.env` and `frontend/.env` with your production secrets.
3. Build and launch with Docker Compose:
   ```bash
   docker compose up -d --build
   ```
4. Put Nginx with Certbot SSL in front of port `9002` (Frontend) and `8000` (Backend API).

---

## 🔍 5. Troubleshooting & Health Verification

| Symptom | Probable Cause | Resolution |
| :--- | :--- | :--- |
| **"Failed to load. Check your connection." on Dashboard** | Port mismatch or Firebase Admin failed to initialize. | Verify backend `/health` endpoint is responding. Check backend logs for `Firebase Admin initialized successfully`. Ensure `FIREBASE_SERVICE_ACCOUNT_BASE64` is valid. |
| **CORS errors in browser console** | Frontend domain not allowed in backend CORS configuration. | Verify frontend URL matches `https://*.vercel.app` or add your custom domain to `WebConfig.java`. |
| **Flyway Migration Failure (`V17`)** | `pgvector` extension not enabled on database. | Run `CREATE EXTENSION IF NOT EXISTS vector;` in your PostgreSQL database before starting Flyway. |
| **Streaming AI chat drops prematurely** | Reverse proxy or cloud provider SSE buffering/timeout. | The backend has built-in auto-retry with exponential backoff (`Retry.backoff(3, 500ms)`). In Nginx reverse proxies, ensure `proxy_buffering off;` and `proxy_read_timeout 300s;` are set. |
