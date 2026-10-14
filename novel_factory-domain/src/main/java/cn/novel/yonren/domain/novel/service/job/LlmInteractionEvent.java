package cn.novel.yonren.domain.novel.service.job;

/**
 * A sanitized, user-visible snapshot of one model call lifecycle event.
 */
public record LlmInteractionEvent(
        String callId,
        String type,
        String scene,
        String model,
        int attempt,
        int maxAttempts,
        long timestampMs,
        Long retryAtMs,
        Long durationMs,
        String message,
        String promptPreview,
        String responsePreview) {
}
