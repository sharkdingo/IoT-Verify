package cn.edu.nju.Iot_Verify.dto.fix;

import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A numeric rule condition that can be targeted by a preferred fix range. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParameterTarget {
    private String targetId;
    /** One-based rule order in the frozen trace snapshot, for display only. */
    private int rulePosition;
    private String deviceLabel;
    /** Internal trace-snapshot locator used only to attach frozen template provenance. */
    @JsonIgnore
    private int ruleIndex;
    /** Internal trace-snapshot locator used only to attach frozen template provenance. */
    @JsonIgnore
    private int conditionIndex;
    private String attribute;
    private String relation;
    private String originalValue;
    private int lowerBound;
    private int upperBound;
    private String description;
    @Builder.Default
    private ModelTokenSource modelTokenSource = ModelTokenSource.UNKNOWN;
}
