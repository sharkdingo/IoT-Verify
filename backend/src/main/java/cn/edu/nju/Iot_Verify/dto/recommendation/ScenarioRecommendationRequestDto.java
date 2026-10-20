package cn.edu.nju.Iot_Verify.dto.recommendation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.List;

@Data
public class ScenarioRecommendationRequestDto {
    @NotNull(message = "minDevices must not be null")
    @Min(1)
    @Max(10)
    private Integer minDevices;
    @NotNull(message = "minRules must not be null")
    @Min(1)
    @Max(10)
    private Integer minRules;
    @NotNull(message = "minSpecs must not be null")
    @Min(1)
    @Max(10)
    private Integer minSpecs;
    @NotNull(message = "maxDevices must not be null")
    @Min(1)
    @Max(10)
    private Integer maxDevices;
    @NotNull(message = "maxRules must not be null")
    @Min(1)
    @Max(10)
    private Integer maxRules;
    @NotNull(message = "maxSpecs must not be null")
    @Min(1)
    @Max(10)
    private Integer maxSpecs;
    @Size(max = 20, message = "Language must be at most 20 characters")
    private String language;
    @Size(max = RecommendationLimits.MAX_USER_REQUIREMENT_LENGTH,
            message = "User requirement must be at most 2000 characters")
    private String userRequirement;
    @Size(min = 1, max = 100)
    private List<@NotBlank @Size(max = 100) String> templateNames;

    @JsonIgnore
    @AssertTrue(message = "Each minimum scenario count must be less than or equal to its maximum")
    public boolean isObjectiveRangeOrdered() {
        return ordered(minDevices, maxDevices)
                && ordered(minRules, maxRules)
                && ordered(minSpecs, maxSpecs);
    }

    private boolean ordered(Integer minimum, Integer maximum) {
        return minimum == null || maximum == null || minimum <= maximum;
    }
}
