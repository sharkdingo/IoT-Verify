// src/main/java/cn/edu/nju/Iot_Verify/dto/spec/SpecConditionDto.java
package cn.edu.nju.Iot_Verify.dto.spec;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto.FrozenModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import lombok.Data;

/**
 * 规格条件 DTO
 *
 * targetType 支持:
 * - state: 检查设备状态，如 "state=cool"
 * - mode: 检查设备模式值，如 "HvacMode=cool"
 * - variable: 检查变量值，如 "temperature>30"
 * - api: 检查 API 信号，如 "fanAuto_a=FALSE"
 * - trust: 检查当前模式状态或变量的来源标签
 * - privacy: 检查当前模式状态或变量的敏感性标签
 */
@Data
public class SpecConditionDto {
    @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Condition ID must be at most 200 characters", groups = {Default.class, FrozenModel.class})
    private String id;
    @Pattern(regexp = "^(a|if|then)$", message = "Side must be one of: a, if, then", groups = {Default.class, FrozenModel.class})
    private String side;        // 'a' | 'if' | 'then'
    /**
     * Device reference. Board storage uses the raw DeviceNode.id; verification/simulation
     * requests use the normalized NuSMV varName. deviceLabel is display-only.
     */
    @NotBlank(message = "Device ID is required for spec condition", groups = {Default.class, FrozenModel.class})
    @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Device ID must be at most 200 characters", groups = {Default.class, FrozenModel.class})
    private String deviceId;
    @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Device label must be at most 200 characters")
    private String deviceLabel;
    /**
     * 目标类型: state | mode | variable | api | trust | privacy
     */
    @NotBlank(message = "Target type is required for spec condition", groups = {Default.class, FrozenModel.class})
    @Pattern(regexp = "(?i:state|mode|variable|api|trust|privacy)",
             message = "targetType must be one of: state, mode, variable, api, trust, privacy", groups = {Default.class, FrozenModel.class})
    private String targetType;
    @NotBlank(message = "Key is required for spec condition", groups = {Default.class, FrozenModel.class})
    @Size(max = RequestLimits.MAX_IDENTIFIER_LENGTH, message = "Condition key must be at most 200 characters", groups = {Default.class, FrozenModel.class})
    private String key;
    /**
     * Required only for trust/privacy. "state" means the label of the currently active
     * state in the mode named by key; "variable" means the variable named by key.
     * This keeps generated NuSMV names such as Mode_on out of the authored contract.
     */
    @Pattern(regexp = "(?i:state|variable)",
            message = "propertyScope must be one of: state, variable", groups = {Default.class, FrozenModel.class})
    private String propertyScope;
    /**
     * Which of two different questions a shared-variable condition asks. Required only for
     * {@code targetType=variable}, and rejected on every other target type.
     *
     * <p>{@code environment} compiles to the pool value {@code a_<key>} — "did this actually happen in the
     * home". {@code reported} compiles to {@code <device>.<key>} — "is this what the device said". Outside
     * attack modelling the two are provably equal; a compromised device may report a value the environment
     * never held, and then they diverge. Measured before this field existed: a specification reading
     * "temperature never exceeds 30" was reported SATISFIED while a falsified reading reached 40 and the
     * rule fired, because the builder always chose the pool value and silently dropped the device the
     * author had picked.
     *
     * <p>Deliberately has **no default**. A null value means the author never chose, which is a real and
     * distinct state for every specification written before this field: presenting either value as their
     * intent is the defect being fixed. Generation refuses such a condition and reports it as a skipped
     * specification rather than guessing, and the client renders it as unresolved rather than as a verdict.
     */
    // Whitespace-tolerant like `relation` below, and for the same reason: every service-layer normalizer
    // trims before comparing, so a padded value is one the semantic gates accept and canonicalize. Anchoring
    // without `\s*` made bean validation reject `" environment "` with this generic message, hiding the
    // actionable one that explains why there is no default.
    @Pattern(regexp = "^\\s*(?i:environment|reported)\\s*$",
            message = "variableSource must be one of: environment, reported", groups = {Default.class, FrozenModel.class})
    private String variableSource;
    @NotBlank(message = "Relation is required for spec condition", groups = {Default.class, FrozenModel.class})
    @Pattern(
            regexp = "^\\s*(=|==|!=|>|>=|<|<=|(?i:eq|neq|gt|gte|lt|lte|in|not_in|not in))\\s*$",
            message = "Relation must be one of =, !=, >, >=, <, <=, in, not_in, not in, or aliases EQ, NEQ, GT, GTE, LT, LTE",
            groups = {Default.class, FrozenModel.class}
    )
    private String relation;
    @NotBlank(message = "Value is required for spec condition", groups = {Default.class, FrozenModel.class})
    @Size(max = RequestLimits.MAX_VALUE_LENGTH, message = "Condition value must be at most 1000 characters", groups = {Default.class, FrozenModel.class})
    private String value;

}
