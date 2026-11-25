package cn.edu.nju.Iot_Verify.dto.device;

import com.fasterxml.jackson.annotation.JsonInclude;

public record DefaultTemplateRuntimeChangeDto(
        Field field,
        @JsonInclude(JsonInclude.Include.NON_NULL) String itemName,
        @JsonInclude(JsonInclude.Include.ALWAYS) String previousValue,
        @JsonInclude(JsonInclude.Include.ALWAYS) String currentValue) {

    public enum Field {
        STATE, STATE_TRUST, STATE_PRIVACY,
        VARIABLE_NAME, VARIABLE_VALUE, VARIABLE_TRUST,
        PRIVACY_NAME, PRIVACY_LABEL
    }
}
