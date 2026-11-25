// src/main/java/cn/edu/nju/Iot_Verify/dto/board/BoardBatchDto.java
package cn.edu.nju.Iot_Verify.dto.board;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;

/**
 * Batch save of the interdependent board semantic collections in one transaction.
 *
 * <p>Used only for explicitly confirmed scene import/clear, where nodes, environment values,
 * rules, and specs must change together. Persisting them via separate requests risks a partial
 * scene (for example, imported nodes/specs beside the previous environment pool).</p>
 *
 * <p>This is an explicit full-scene replacement contract. Nodes, environment variables, rules,
 * specifications, and template snapshots are all required complete collections. It never means
 * "patch the supplied collections and preserve the omitted ones".</p>
 *
 * <p>The opaque {@code impactToken} binds the destructive command to the exact current board
 * preview that the user confirmed. Each referenced template must have exactly one snapshot.
 * Existing templates must have the same manifest and missing templates are created in the same
 * transaction. Each required environment variable must have a value, trust, and privacy.
 * The response reports templates created in {@code createdTemplates}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BoardBatchDto {

    public BoardBatchDto(
            List<@Valid @NotNull(message = "Node item cannot be null") DeviceNodeDto> nodes,
            List<@Valid @NotNull(message = "Environment variable item cannot be null") BoardEnvironmentVariableDto> environmentVariables,
            List<@Valid @NotNull(message = "Rule item cannot be null") RuleDto> rules,
            List<@Valid @NotNull(message = "Specification item cannot be null") SpecificationDto> specs) {
        this.nodes = nodes;
        this.environmentVariables = environmentVariables;
        this.rules = rules;
        this.specs = specs;
    }

    @Valid
    @NotNull(message = "Complete scene nodes collection is required")
    @Size(max = RequestLimits.MAX_DEVICES, message = "At most 100 nodes are allowed in a scene")
    private List<@Valid @NotNull(message = "Node item cannot be null") DeviceNodeDto> nodes;

    @Valid
    @NotNull(message = "Complete scene environmentVariables collection is required")
    @Size(max = RequestLimits.MAX_ENVIRONMENT_VARIABLES, message = "At most 200 environment variables are allowed in a scene")
    private List<@Valid @NotNull(message = "Environment variable item cannot be null") BoardEnvironmentVariableDto> environmentVariables;

    @Valid
    @NotNull(message = "Complete scene rules collection is required")
    @Size(max = RequestLimits.MAX_RULES, message = "At most 100 rules are allowed in a scene")
    private List<@Valid @NotNull(message = "Rule item cannot be null") RuleDto> rules;

    @Valid
    @NotNull(message = "Complete scene specs collection is required")
    @Size(max = RequestLimits.MAX_SPECS, message = "At most 100 specifications are allowed in a scene")
    private List<@Valid @NotNull(message = "Specification item cannot be null") SpecificationDto> specs;

    /** Request-only exact scene template dependency set; empty only for a scene with no devices. */
    @Valid
    @NotNull(message = "Complete scene templateSnapshots collection is required")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Size(max = RequestLimits.MAX_TEMPLATES, message = "At most 100 template snapshots are allowed in a scene")
    private List<@Valid @NotNull(message = "Template snapshot item cannot be null") DeviceTemplateDto> templateSnapshots;

    /** Request-only opaque token from the latest server replacement preview. */
    @NotBlank(message = "Scene replacement impactToken is required")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String impactToken;

    /** Response-only templates created while resolving scene-import snapshots. */
    private List<DeviceTemplateDto> createdTemplates;
}
