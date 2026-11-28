package cn.edu.nju.Iot_Verify.dto.rule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RuleSimilarityBatchRequestDto(
        @NotNull(message = "Rules are required")
        @Size(min = 1, max = 10, message = "Provide between 1 and 10 rules")
        List<@NotNull(message = "Rule item cannot be null") @Valid RuleDto> rules) {
}
