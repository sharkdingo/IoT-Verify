package cn.edu.nju.Iot_Verify.dto.chat;

import cn.edu.nju.Iot_Verify.component.ai.model.ChatExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.Map;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamResponseDto {
    @ToString.Exclude
    private String content;
    @ToString.Exclude
    private String error;
    private CommandDto command;
    private ProgressDto progress;
    private TerminalDto terminal;

    public StreamResponseDto(String content) {
        this.content = content;
    }

    public static StreamResponseDto error(String message) {
        StreamResponseDto response = new StreamResponseDto();
        response.setError(message);
        return response;
    }

    public static StreamResponseDto progress(String stage, String toolName, Integer round) {
        return progress(stage, toolName, round, null, null, null, null, null);
    }

    public static StreamResponseDto progress(String stage,
                                             String toolName,
                                             Integer round,
                                             String outcome,
                                             Integer successfulSteps,
                                             Integer failedSteps,
                                             Integer unconfirmedSteps) {
        return progress(stage, toolName, round, outcome, successfulSteps, failedSteps, unconfirmedSteps, null);
    }

    public static StreamResponseDto progress(String stage,
                                             String toolName,
                                             Integer round,
                                             String outcome,
                                             Integer successfulSteps,
                                             Integer failedSteps,
                                             Integer unconfirmedSteps,
                                             String detail) {
        StreamResponseDto response = new StreamResponseDto();
        response.setProgress(new ProgressDto(
                stage,
                toolName,
                round,
                outcome,
                successfulSteps,
                failedSteps,
                unconfirmedSteps,
                detail
        ));
        return response;
    }

    public static StreamResponseDto terminal(String turnId, ChatExecutionStatus executionStatus) {
        StreamResponseDto response = new StreamResponseDto();
        response.setTerminal(new TerminalDto(turnId, executionStatus));
        return response;
    }

    public static StreamResponseDto command(CommandDto command) {
        StreamResponseDto response = new StreamResponseDto();
        response.setCommand(command);
        return response;
    }

    @Data
    @NoArgsConstructor
    public static class ProgressDto {
        private String stage;
        private String toolName;
        private Integer round;
        private String outcome;
        private Integer successfulSteps;
        private Integer failedSteps;
        private Integer unconfirmedSteps;
        @ToString.Exclude
        private String detail;
        @ToString.Exclude
        private ClaimReviewDto claimReview;

        public ProgressDto(String stage,
                           String toolName,
                           Integer round,
                           String outcome,
                           Integer successfulSteps,
                           Integer failedSteps,
                           Integer unconfirmedSteps,
                           String detail) {
            this.stage = stage;
            this.toolName = toolName;
            this.round = round;
            this.outcome = outcome;
            this.successfulSteps = successfulSteps;
            this.failedSteps = failedSteps;
            this.unconfirmedSteps = unconfirmedSteps;
            this.detail = detail;
        }
    }

    public enum ClaimReviewReason {
        MISSING_TOOL_EVIDENCE, MISSING_BOARD_SNAPSHOT, MISSING_OPERATION_RECEIPT
    }

    @Data
    @NoArgsConstructor
    public static class ClaimReviewDto {
        private ClaimReviewReason reasonCode;
        @ToString.Exclude
        private String originalText;
        @ToString.Exclude
        private String matchedText;

        public ClaimReviewDto(ClaimReviewReason reasonCode, String originalText, String matchedText) {
            this.reasonCode = reasonCode;
            this.originalText = originalText;
            this.matchedText = matchedText;
        }
    }

    @Data
    @NoArgsConstructor
    public static class CommandDto {
        private String type;
        @ToString.Exclude
        private Map<String, Object> payload;

        public CommandDto(String type, Map<String, Object> payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    @Data
    @NoArgsConstructor
    public static class TerminalDto {
        private String turnId;
        private ChatExecutionStatus executionStatus;

        public TerminalDto(String turnId, ChatExecutionStatus executionStatus) {
            this.turnId = turnId;
            this.executionStatus = executionStatus;
        }
    }
}
