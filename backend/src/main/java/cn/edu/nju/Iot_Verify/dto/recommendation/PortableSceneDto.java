package cn.edu.nju.Iot_Verify.dto.recommendation;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortableSceneDto {
    private String schema;
    private Integer version;
    private List<PortableTemplateDto> templates;
    private List<PortableDeviceDto> devices;
    private List<BoardEnvironmentVariableDto> environmentVariables;
    private List<PortableRuleDto> rules;
    private List<PortableSpecificationDto> specs;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortableTemplateDto {
        private String name;
        private DeviceTemplateDto.DeviceManifest manifest;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortableDeviceDto {
        private String id;
        private String templateName;
        private String label;
        private PortablePositionDto position;
        private String state;
        private Integer width;
        private Integer height;
        private String currentStateTrust;
        private String currentStatePrivacy;
        private List<VariableStateDto> variables;
        private List<PrivacyStateDto> privacies;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortablePositionDto {
        private Double x;
        private Double y;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortableRuleDto {
        private String name;
        private List<PortableRuleSourceDto> sources;
        private String toId;
        private String toApi;
        private String contentDevice;
        private String content;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortableRuleSourceDto {
        private String fromId;
        private String fromApi;
        private String itemType;
        private String relation;
        private String value;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = JsonAutoDetect.Visibility.NONE,
            fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static class PortableSpecificationDto {
        private String name;
        private String templateId;
        @JsonProperty("aConditions")
        private List<PortableSpecConditionDto> aConditions;
        private List<PortableSpecConditionDto> ifConditions;
        private List<PortableSpecConditionDto> thenConditions;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PortableSpecConditionDto {
        private String deviceId;
        private String targetType;
        private String key;
        private String propertyScope;
        /**
         * Which question a variable condition asks — see {@code SpecConditionDto.variableSource}.
         *
         * <p>Declared here because the injected mapper sets {@code FAIL_ON_UNKNOWN_PROPERTIES}: the
         * scenario tool emits this on every variable condition, so omitting the field did not drop it
         * quietly — {@code convertValue} threw, and the controller turned that into a 502 for the whole
         * recommendation response. Not a partial loss: nothing came back at all.
         */
        private String variableSource;
        private String relation;
        private String value;
    }
}
