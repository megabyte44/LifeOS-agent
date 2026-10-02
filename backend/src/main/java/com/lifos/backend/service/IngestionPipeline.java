package com.lifos.backend.service;

import com.lifos.backend.event.ChunkedEmbeddingTriggerEvent;
import com.lifos.backend.event.EmbeddingTriggerEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Unified ingestion façade: resolves chunking strategy, publishes an
 * {@link EmbeddingTriggerEvent}, and returns immediately.
 *
 * <h3>Design contract</h3>
 * Every public method in this class is non-blocking.  The calling thread
 * (an HTTP request thread or scheduler) returns as soon as the event is
 * published.  Actual embedding work runs on the {@code aiTaskExecutor}
 * thread pool via {@link com.lifos.backend.event.EmbeddingEventListener}.
 *
 * <h3>Why event-driven instead of direct @Async?</h3>
 * Spring's {@code @TransactionalEventListener(AFTER_COMMIT)} guarantees the
 * source row is committed before the worker reads it back.  A bare
 * {@code @Async} call that starts immediately can race with the outer
 * transaction and read stale (or missing) data.
 *
 * <h3>Chunking</h3>
 * For longer documents (notes, journals) callers should use
 * {@link #ingestChunked} — the {@link com.lifos.backend.event.EmbeddingEventListener}
 * delegates to {@link EmbeddingService#embedChunkedDocument} for those types.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionPipeline {

    private final EmbeddingService embeddingService;
    private final ApplicationEventPublisher eventPublisher;

    // ── Write path ────────────────────────────────────────────────────────────

    /**
     * Publishes an embedding event for a short-text entity (todo, habit, planner
     * item, user-profile, etc.).  Returns immediately — embedding happens
     * asynchronously after the outer transaction commits.
     *
     * @param userUid    target user
     * @param sourceType entity type label (e.g. "note", "todo", "habit")
     * @param sourceId   entity primary key
     * @param text       text to embed (null delegates to a delete event)
     */
    public void ingest(String userUid, String sourceType, UUID sourceId, String text) {
        if (text == null || text.isBlank()) {
            delete(userUid, sourceType, sourceId);
            return;
        }
        eventPublisher.publishEvent(EmbeddingTextBuilder.buildEvent(userUid, sourceType, sourceId, text));
        log.debug("[ingestion] Queued embed for {} {} (user={})", sourceType, sourceId, userUid);
    }

    /**
     * Same as {@link #ingest} but signals that the document should be chunked
     * if it exceeds the configured {@code ai.chunking.chunk-max-chars} threshold.
     * The {@link com.lifos.backend.event.EmbeddingEventListener} routes chunked
     * types through {@link EmbeddingService#embedChunkedDocument}.
     *
     * <p>Currently notes and documents use this path.
     */
    public void ingestChunked(String userUid, String sourceType, UUID sourceId, String text) {
        if (text == null || text.isBlank()) {
            delete(userUid, sourceType, sourceId);
            return;
        }
        eventPublisher.publishEvent(
                ChunkedEmbeddingTriggerEvent.of(userUid, sourceType, sourceId, text));
        log.debug("[ingestion] Queued chunked-embed for {} {} (user={})", sourceType, sourceId, userUid);
    }

    // ── Delete path ───────────────────────────────────────────────────────────

    /**
     * Publishes a delete-embedding event.  The physical row removal is executed
     * asynchronously on the {@code aiTaskExecutor} after the outer transaction
     * commits, so it never blocks the calling thread.
     */
    public void delete(String userUid, String sourceType, UUID sourceId) {
        eventPublisher.publishEvent(EmbeddingTextBuilder.deleteEvent(userUid, sourceType, sourceId));
        log.debug("[ingestion] Queued delete for {} {} (user={})", sourceType, sourceId, userUid);
    }

    // ── Legacy compatibility ───────────────────────────────────────────────────

    /**
     * @deprecated Use {@link #ingest} directly — it is already non-blocking.
     *             Kept for backward compatibility; will be removed in future.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    @Async("aiTaskExecutor")
    public void ingestAsync(String userUid, String sourceType, UUID sourceId, String text) {
        ingest(userUid, sourceType, sourceId, text);
    }

    /**
     * @deprecated Use {@link #delete} directly.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public void publishDeleteEvent(String userUid, String sourceType, UUID sourceId) {
        delete(userUid, sourceType, sourceId);
    }

    /**
     * @deprecated Use {@link #ingest} directly.
     */
    @Deprecated(since = "2.0", forRemoval = true)
    public void publishEvent(String userUid, String sourceType, UUID sourceId, String text) {
        ingest(userUid, sourceType, sourceId, text);
    }
}
