package com.lifos.backend.event;

import com.lifos.backend.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Asynchronously handles embedding creation/deletion after a transaction commits.
 *
 * <h3>Why AFTER_COMMIT?</h3>
 * The source document must already be persisted in Postgres before we attempt to
 * read it back during embedding. AFTER_COMMIT guarantees the row is visible to
 * any subsequent read (including the embedding worker's own DB session).
 *
 * <h3>Why @Async("aiTaskExecutor")?</h3>
 * Routing to the named executor ensures embedding work never monopolises the
 * HTTP thread pool. The executor is configured with CallerRunsPolicy so no job
 * is silently dropped if the queue fills up — tasks spill back to the event
 * dispatcher as back-pressure instead.
 *
 * <h3>Retry strategy</h3>
 * Embedding API calls are network-bound and can fail transiently (rate limits,
 * timeouts). We retry up to {@code MAX_RETRIES} times with exponential back-off
 * before giving up. Failures are non-critical — the record is already saved;
 * it will simply be absent from semantic search until the next write triggers
 * another embedding job (or an admin runs the backfill).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingEventListener {

    private static final int  MAX_RETRIES    = 3;
    private static final long BASE_DELAY_MS  = 500L;   // 500 ms → 1 s → 2 s

    private final EmbeddingService embeddingService;

    // ── Standard (single-vector) embedding ────────────────────────────────────

    /**
     * Handles create/update and delete events for short-text entity types
     * (todos, habits, planner items, user-profile, etc.).
     */
    @Async("aiTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmbeddingTrigger(EmbeddingTriggerEvent event) {
        if (event.text() == null) {
            deleteWithRetry(event.sourceType(), event.sourceId());
        } else {
            embedWithRetry(
                    event.userUid(), event.sourceType(), event.sourceId(), event.text(),
                    event.domain(), event.domainTag(),
                    event.qualityScore(), event.recencyWeight(), event.importanceSignal()
            );
        }
    }

    // ── Chunked-document embedding ────────────────────────────────────────────

    /**
     * Handles notes, journals and uploaded documents that may exceed the
     * single-chunk limit. Delegates to the chunking path which splits the text
     * into overlapping windows and stores each window as a separate embedding row.
     */
    @Async("aiTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChunkedEmbeddingTrigger(ChunkedEmbeddingTriggerEvent event) {
        embedChunkedWithRetry(event.userUid(), event.sourceType(), event.sourceId(), event.text());
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void embedWithRetry(String userUid, String sourceType, java.util.UUID sourceId,
                                String text, String domain, String domainTag,
                                float quality, float recency, float importance) {
        int attempt = 0;
        while (true) {
            try {
                embeddingService.embedAndStore(
                        userUid, sourceType, sourceId, text,
                        domain, domainTag, quality, recency, importance);
                log.debug("[embedding] Stored embedding for {} {} (attempt {})",
                        sourceType, sourceId, attempt + 1);
                return;
            } catch (Exception ex) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.warn("[embedding] Gave up embedding {} {} after {} attempts: {}",
                            sourceType, sourceId, MAX_RETRIES, ex.getMessage());
                    return;
                }
                long delay = BASE_DELAY_MS * (1L << (attempt - 1));
                log.warn("[embedding] Attempt {}/{} failed for {} {}, retrying in {} ms: {}",
                        attempt, MAX_RETRIES, sourceType, sourceId, delay, ex.getMessage());
                sleepQuietly(delay);
            }
        }
    }

    private void embedChunkedWithRetry(String userUid, String sourceType,
                                       java.util.UUID sourceId, String text) {
        int attempt = 0;
        while (true) {
            try {
                embeddingService.embedChunkedDocument(userUid, sourceType, sourceId, text);
                log.debug("[embedding] Stored chunked embedding for {} {} (attempt {})",
                        sourceType, sourceId, attempt + 1);
                return;
            } catch (Exception ex) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.warn("[embedding] Gave up chunked-embed {} {} after {} attempts: {}",
                            sourceType, sourceId, MAX_RETRIES, ex.getMessage());
                    return;
                }
                long delay = BASE_DELAY_MS * (1L << (attempt - 1));
                log.warn("[embedding] Chunked attempt {}/{} failed for {} {}, retrying in {} ms: {}",
                        attempt, MAX_RETRIES, sourceType, sourceId, delay, ex.getMessage());
                sleepQuietly(delay);
            }
        }
    }

    private void deleteWithRetry(String sourceType, java.util.UUID sourceId) {
        int attempt = 0;
        while (true) {
            try {
                embeddingService.deleteBySource(sourceType, sourceId);
                log.debug("[embedding] Deleted embedding for {} {} (attempt {})",
                        sourceType, sourceId, attempt + 1);
                return;
            } catch (Exception ex) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.warn("[embedding] Gave up deleting {} {} after {} attempts: {}",
                            sourceType, sourceId, MAX_RETRIES, ex.getMessage());
                    return;
                }
                long delay = BASE_DELAY_MS * (1L << (attempt - 1));
                log.warn("[embedding] Delete attempt {}/{} failed for {} {}, retrying in {} ms: {}",
                        attempt, MAX_RETRIES, sourceType, sourceId, delay, ex.getMessage());
                sleepQuietly(delay);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
