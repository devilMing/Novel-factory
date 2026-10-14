package cn.novel.yonren.api.dto;

import lombok.Data;

/** Sanitized model-call lifecycle event shown in the generation console. */
@Data
public class LlmInteractionEventDTO {
    private String callId;
    private String type;
    private String scene;
    private String model;
    private Integer attempt;
    private Integer maxAttempts;
    private Long timestampMs;
    private Long retryAtMs;
    private Long durationMs;
    private String message;
    private String promptPreview;
    private String responsePreview;
}
