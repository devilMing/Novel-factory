package cn.novel.yonren.domain.novel.service.job;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmInteractionTrackerTest {

    @Test
    void snapshot_keepsNewestEventsInChronologicalOrder() {
        LlmInteractionTracker tracker = new LlmInteractionTracker(3, 24);

        for (int i = 1; i <= 5; i++) {
            tracker.record("job-1", new LlmInteractionEvent(
                    "call-" + i, "REQUESTED", "CHAPTER_PLAN", "gpt-6.1-sol",
                    1, 4, 1000L + i, null, null,
                    "message-" + i, "prompt-" + i, null));
        }

        List<LlmInteractionEvent> events = tracker.snapshot("job-1");

        assertEquals(List.of("call-3", "call-4", "call-5"),
                events.stream().map(LlmInteractionEvent::callId).toList());
    }

    @Test
    void record_capsPreviewLengthAndDoesNotMutateOtherJobs() {
        LlmInteractionTracker tracker = new LlmInteractionTracker(8, 12);
        tracker.record("job-1", new LlmInteractionEvent(
                "call-1", "SUCCEEDED", "SUMMARY", "gpt-6.1-sol",
                1, 4, 1000L, null, 250L,
                "ok", "123456789012345", "abcdefghijklmno"));

        LlmInteractionEvent event = tracker.snapshot("job-1").get(0);

        assertEquals(12, event.promptPreview().length());
        assertEquals(12, event.responsePreview().length());
        assertTrue(event.promptPreview().endsWith("…"));
        assertFalse(tracker.snapshot("job-2").iterator().hasNext());
    }
}
