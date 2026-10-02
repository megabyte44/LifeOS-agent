package com.lifos.backend.config;

import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai")
public class AiFoundationProperties {

    // ── Provider selection ──────────────────────────────────────────────────
    /** Set via AI_PROVIDER in .env — forces a specific provider (openai|openrouter|gemini) */
    private String provider = "";
    /** Set via AI_MODEL in .env — overrides the default model for the chosen provider */
    private String model = "";

    // ── API keys (sourced from .env / environment) ────────────────────────────
    /** Set via OPENAI_API_KEY */
    private String openaiApiKey = "";
    /** Set via OPENROUTER_API_KEY */
    private String openrouterApiKey = "";
    /** Optional dedicated embedding key via OPENROUTER_EMBEDDING_API_KEY */
    private String openrouterEmbeddingApiKey = "";
    /** Set via GEMINI_API_KEY or GOOGLE_API_KEY */
    private String geminiApiKey = "";
    /** Set via HF_API_KEY — used for HuggingFace Inference API or custom HF Space */
    private String hfApiKey = "";
    /** Set via HF_INFERENCE_URL — overrides default HF endpoint (e.g. your ZeroGPU Space URL) */
    private String hfInferenceUrl = "";

    private final Embedding embedding = new Embedding();
    private final Streaming streaming = new Streaming();
    private final Rag rag = new Rag();
    private final Async async = new Async();
    private final Chunking chunking = new Chunking();

    /** Embedding config — apiKey is read from the top-level openaiApiKey field */
    @Getter
    @Setter
    public static class Embedding {
        /** Embedding model name (defaults to OpenAI small embedding). */
        private String model = "text-embedding-3-small";
        private int dimensions = 1536;
        private int maxInputChars = 24000;
    }

    @Getter
    @Setter
    public static class Streaming {
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration responseTimeout = Duration.ofSeconds(120);
    }

    @Getter
    @Setter
    public static class Rag {
        private int defaultVectorLimit = 5;
        private int maxContextChunks = 5;
        /** Runtime flag: use hybrid memory retrieval strategy. */
        private boolean enableHybridReads = true;
        /** Runtime flag: enable adaptive top-k and token budget selection. */
        private boolean enableDynamicTopK = true;
        /** Runtime flag: embed extracted conversation memories. */
        private boolean enableConversationMemoryEmbeddings = true;

        // ── KG-RAG feature flags ────────────────────────────────────────
        /** Embed all entity types (todo, habit, transaction, etc.), not just note/goal/memory. */
        private boolean enableUniversalEmbedding = false;
        /** Use knowledge graph edges during retrieval scoring. */
        private boolean enableKnowledgeGraph = false;
        /** Extract knowledge graph edges on entity save via LLM. */
        private boolean enableEdgeExtraction = false;
        /** Classify query intent to selectively inject structured context. */
        private boolean enableQueryIntentClassification = true;
        /** Use graph-boosted scoring in hybrid retrieval. */
        private boolean enableGraphBoostedScoring = false;
        /** Source types to include in vector search (empty = search all). */
        private List<String> enabledSourceTypes = List.of();
        /** Max graph hops during retrieval traversal. */
        private int graphMaxHops = 2;
        /** Weight of graph boost signal in hybrid scoring. */
        private float graphBoostWeight = 0.15f;
    }

    /** Document chunking config — controls how long documents are split before embedding. */
    @Getter
    @Setter
    public static class Chunking {
        /** When true, notes/docs longer than chunkMaxChars are split into overlapping chunks. */
        private boolean enableDocumentChunking = true;
        /** Maximum characters per chunk (~800 tokens for text-embedding-3-small). */
        private int chunkMaxChars = 3200;
        /** Overlap characters between consecutive chunks (~200 token equivalent). */
        private int chunkOverlapChars = 400;
    }

    @Getter
    @Setter
    public static class Async {
        /** Threads always alive for embedding/AI background work. */
        private int corePoolSize = 4;
        /** Max threads; scales under burst load. */
        private int maxPoolSize = 8;
        /** Pending jobs before CallerRunsPolicy back-pressure kicks in. */
        private int queueCapacity = 200;
        /** Prefix visible in thread dumps and APM tools. */
        private String threadNamePrefix = "ai-";
    }
}