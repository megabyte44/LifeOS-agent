package com.lifos.backend.event;

import java.util.UUID;

/**
 * Specialised embedding trigger for documents that should be split into
 * overlapping chunks before embedding (notes, journals, uploaded documents).
 *
 * <p>The {@link EmbeddingEventListener} detects this subtype and routes the
 * text through {@link com.lifos.backend.service.EmbeddingService#embedChunkedDocument}
 * instead of the single-vector {@code embedAndStore} path.
 *
 * <p>Inherits all fields from {@link EmbeddingTriggerEvent} so the same
 * listener infrastructure handles both event types without duplication.
 */
public record ChunkedEmbeddingTriggerEvent(
        String userUid,
        String sourceType,
        UUID   sourceId,
        String text,
        String domain,
        String domainTag,
        float  qualityScore,
        float  recencyWeight,
        float  importanceSignal
) {

    /** Factory: builds a chunked event with metadata resolved from {@link com.lifos.backend.service.EmbeddingTextBuilder}. */
    public static ChunkedEmbeddingTriggerEvent of(
            String userUid, String sourceType, UUID sourceId, String text) {
        var meta = com.lifos.backend.service.EmbeddingTextBuilder.metaFor(sourceType);
        return new ChunkedEmbeddingTriggerEvent(
                userUid, sourceType, sourceId, text,
                meta.domain(), meta.domainTag(),
                meta.quality(), meta.recency(), meta.importance()
        );
    }
}
