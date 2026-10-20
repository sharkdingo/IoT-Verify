package cn.edu.nju.Iot_Verify.dto.fix;

import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * §5.1 Rule Parameter Adjustment result: a numeric threshold change.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterAdjustment {
    /**
     * Opaque API-facing id for selecting this adjustment as a preferred-range target.
     * REST/AI clients should submit this id instead of ruleIndex/conditionIndex.
     */
    private String targetId;
    /** One-based rule order in the frozen trace snapshot, for display only. */
    private int rulePosition;
    private String deviceLabel;
    /** Internal trace-snapshot locator used only while applying the server-recomputed fix. */
    @JsonIgnore
    private int ruleIndex;
    /** Internal trace-snapshot locator used only while applying the server-recomputed fix. */
    @JsonIgnore
    private int conditionIndex;
    /** Attribute name, e.g. "temperature" */
    private String attribute;
    /** Relation operator, e.g. ">" */
    private String relation;
    /** Original threshold value, e.g. "30" */
    private String originalValue;
    /** New threshold value found by NuSMV, e.g. "25" */
    private String newValue;
    private int lowerBound;
    private int upperBound;
    /** Human-readable explanation of the rule condition this adjustment changes. */
    private String description;
    @Builder.Default
    private ModelTokenSource modelTokenSource = ModelTokenSource.UNKNOWN;
}
