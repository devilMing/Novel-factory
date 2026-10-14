package cn.novel.yonren.domain.novel.service.job;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps a small in-memory timeline per generation job. The tracker only accepts
 * already-sanitized text and applies a second preview length guard before data
 * is exposed by the job status API.
 */
@Service
public class LlmInteractionTracker {

    private static final int DEFAULT_CAPACITY = 80;
    private static final int DEFAULT_PREVIEW_LIMIT = 600;

    private final int capacity;
    private final int previewLimit;
    private final ConcurrentMap<String, ArrayDeque<LlmInteractionEvent>> eventsByJob = new ConcurrentHashMap<>();

    public LlmInteractionTracker() {
        this(DEFAULT_CAPACITY, DEFAULT_PREVIEW_LIMIT);
    }

    LlmInteractionTracker(int capacity, int previewLimit) {
        if (capacity < 1 || previewLimit < 2) {
            throw new IllegalArgumentException("capacity must be positive and previewLimit must be at least 2");
        }
        this.capacity = capacity;
        this.previewLimit = previewLimit;
    }

    public void record(String jobId, LlmInteractionEvent event) {
        if (!StringUtils.hasText(jobId) || event == null) {
            return;
        }
        LlmInteractionEvent sanitized = new LlmInteractionEvent(
                event.callId(), event.type(), event.scene(), event.model(),
                event.attempt(), event.maxAttempts(), event.timestampMs(),
                event.retryAtMs(), event.durationMs(), event.message(),
                cap(event.promptPreview()), cap(event.responsePreview()));
        ArrayDeque<LlmInteractionEvent> events = eventsByJob.computeIfAbsent(jobId, ignored -> new ArrayDeque<>());
        synchronized (events) {
            events.addLast(sanitized);
            while (events.size() > capacity) {
                events.removeFirst();
            }
        }
    }

    public List<LlmInteractionEvent> snapshot(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return List.of();
        }
        ArrayDeque<LlmInteractionEvent> events = eventsByJob.get(jobId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private String cap(String value) {
        if (value == null || value.length() <= previewLimit) {
            return value;
        }
        return value.substring(0, previewLimit - 1) + "…";
    }
}
