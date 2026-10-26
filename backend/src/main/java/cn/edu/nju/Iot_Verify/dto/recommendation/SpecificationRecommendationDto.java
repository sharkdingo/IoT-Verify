package cn.edu.nju.Iot_Verify.dto.recommendation;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class SpecificationRecommendationDto {
    private String name;
    /** Advisory explanation only; not part of the persisted requirement. */
    private String rationale;
    private String templateId;
    @JsonProperty("aConditions")
    private List<ConditionDto> aConditions;
    private List<ConditionDto> ifConditions;
    private List<ConditionDto> thenConditions;

    /** Exact authored condition shape; persistence-only id/side fields do not belong in a recommendation. */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConditionDto {
        private String deviceId;
        private String deviceLabel;
        private String targetType;
        private String key;
        private String propertyScope;
        /**
         * Which question a variable condition asks — see {@code SpecConditionDto.variableSource}.
         *
         * <p>Required here for the same reason as in the portable scene DTO: the mapper is strict about
         * unknown properties, so one recommended variable condition carrying this field would fail
         * {@code convertValue}, which the controller reports as a 502 for the whole response — the user
         * gets no recommendations rather than the rest of them.
         */
        private String variableSource;
        private String relation;
        private String value;
    }
}
