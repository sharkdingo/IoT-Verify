package cn.edu.nju.Iot_Verify.dto.rule;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 规则 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleDto {

    private Long id;
    @JsonIgnore
    private Long userId;

    @Valid
    @NotNull(message = "Conditions cannot be null")
    @NotEmpty(message = "Conditions cannot be empty")
    @Size(max = RequestLimits.MAX_RULE_CONDITIONS, message = "At most 50 conditions are allowed per rule")
    private List<@Valid @NotNull(message = "Condition item cannot be null") Condition> conditions;

    @Valid
    @NotNull(message = "Command cannot be null")
    private Command command;

    @Size(max = RequestLimits.MAX_DESCRIPTION_LENGTH, message = "Rule preview must be at most 4000 characters")
    private String ruleString;

    private LocalDateTime createdAt;

    /**
     * 条件
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Condition {
        /**
         * Source device reference.
         *
         * <p>Boundary-specific value:
         * board storage uses the raw DeviceNode.id; verification/simulation use the
         * normalized model varName captured by the server's BoardDataConverter.</p>
         */
        @NotBlank(message = "Condition device name is required")
        @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Condition device name must be at most 200 characters")
        private String deviceName;

        /**
         * 属性（如 state、temperature）
         */
        @NotBlank(message = "Condition attribute is required")
        @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Condition attribute must be at most 200 characters")
        private String attribute;

        /**
         * Authoritative condition kind: api | variable | mode | state.
         */
        @NotBlank(message = "Condition targetType is required")
        @Pattern(
                regexp = "(?i:api|variable|mode|state)",
                message = "Condition targetType must be one of api, variable, mode, state"
        )
        private String targetType;

        /**
         * 关系（=、>、<）。API 信号条件必须为 null；值型条件必须提供。
         */
        @Pattern(
                regexp = "^\\s*(=|==|!=|>|>=|<|<=|(?i:eq|neq|gt|gte|lt|lte|in|not_in|not in))\\s*$",
                message = "Condition relation must be one of =, !=, >, >=, <, <=, in, not_in, not in, or aliases EQ, NEQ, GT, GTE, LT, LTE"
        )
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String relation;

        /**
         * 值。API 信号条件必须为 null；值型条件必须提供。
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Size(max = RequestLimits.MAX_VALUE_LENGTH, message = "Condition value must be at most 1000 characters")
        private String value;

        @JsonIgnore
        @AssertTrue(message = "API signal conditions must not include relation or value")
        public boolean isApiSignalShapeValid() {
            if (!isTargetType("api")) {
                return true;
            }
            return !hasText(relation) && !hasText(value);
        }

        @JsonIgnore
        @AssertTrue(message = "Value-based conditions require relation and value")
        public boolean isValueConditionShapeValid() {
            if (isTargetType("api")) {
                return true;
            }
            return hasText(relation) && hasText(value);
        }

        private boolean isTargetType(String expected) {
            return targetType != null && expected.equalsIgnoreCase(targetType.trim());
        }

        private boolean hasText(String input) {
            return input != null && !input.isBlank();
        }
    }

    /**
     * 命令
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Command {
        /**
         * Target device reference.
         *
         * <p>Boundary-specific value:
         * board storage uses the raw DeviceNode.id; verification/simulation use the
         * normalized model varName captured by the server's BoardDataConverter.</p>
         */
        @NotBlank(message = "Command device name is required")
        @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Command device name must be at most 200 characters")
        private String deviceName;

        /**
         * 动作/API
         */
        @NotBlank(message = "Command action is required")
        @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Command action must be at most 200 characters")
        private String action;

        /**
         * 隐私设备
         */
        @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Content device must be at most 200 characters")
        private String contentDevice;

        /**
         * 隐私内容
         */
        @Size(max = RequestLimits.MAX_VALUE_LENGTH, message = "Content must be at most 1000 characters")
        private String content;

        @JsonIgnore
        @AssertTrue(message = "contentDevice and content must be provided together")
        public boolean isContentPayloadShapeValid() {
            return hasText(contentDevice) == hasText(content);
        }

        private boolean hasText(String input) {
            return input != null && !input.isBlank();
        }
    }
}
