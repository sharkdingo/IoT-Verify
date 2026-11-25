package cn.edu.nju.Iot_Verify.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Per-value semantic provenance for one environment variable in a frozen verification/simulation run.
 *
 * <p>Describes ordinary evolution when no autonomous assignment applies. It is frozen model
 * evidence rather than a record of the cause of an individual trace step.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentValueProvenanceDto {

    public enum ValueType {
        NUMERIC,
        DISCRETE_ENUM,
        DISCRETE_BOOLEAN
    }

    /**
     * Authorship category describes who contributes to ordinary evolution.
     *
     * <ul>
     * <li>EXOGENOUS: no device declares ImpactedVariables. Discrete values may take any declared
     *     value each step. Numeric values follow the declared natural rate.</li>
     * <li>DEVICE_CONTROLLED: one device declares ImpactedVariables for this value.</li>
     * <li>COMPOSED: several devices declare ImpactedVariables. Numeric effects sum, which is
     *     MEDIC's additive {@code env.D.v}. Discrete writers must agree: a scene whose declared
     *     effects assign different values to one discrete value is rejected at board assembly,
     *     because resolving it by iteration order made the same scene produce opposite verdicts.
     *     So a COMPOSED discrete value here is one whose writers agree (exact semantics).</li>
     * </ul>
     */
    public enum AuthorshipCategory {
        EXOGENOUS,
        DEVICE_CONTROLLED,
        COMPOSED
    }

    /**
     * Whether the evolution rule is exact (means what the user declared) or a conservative
     * over-approximation (disclosed abstraction).
     */
    public enum SemanticsTag {
        /** Means what the user declared, or what the device template explicitly states. */
        EXACT,
        /** Deliberate conservative over-approximation disclosed in modelSemantics. */
        ABSTRACTION
    }

    /**
     * One device that declares ImpactedVariables for this environment value.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceWriter {
        /** NuSMV variable name for this device instance. */
        private String deviceVarName;

        /** Template display name. */
        private String templateName;

        /** Whether the template is bundled, custom, or unknown. */
        private ModelTokenSource templateSource;
    }

    /**
     * One device that declares Reads=true for this environment value.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceReader {
        /** NuSMV variable name for this device instance. */
        private String deviceVarName;
    }

    /** User-facing environment variable name. */
    private String name;

    private ValueType type;

    /** For numeric: lower bound of the declared domain. */
    private Integer lowerBound;

    /** For numeric: upper bound of the declared domain. */
    private Integer upperBound;

    /** For numeric: declared natural change rate, for example {@code 0} or {@code [-1, 1]}. */
    private String naturalChangeRate;

    /** For discrete: the declared values (enum or boolean). */
    private List<String> values;

    private AuthorshipCategory authorship;

    /** Devices that declare ImpactedVariables for this value. Empty for purely exogenous. */
    private List<DeviceWriter> writers;

    /** Devices that declare Reads=true for this value. */
    private List<DeviceReader> readers;

    /** EXACT when user-declared; ABSTRACTION for purely exogenous discrete. */
    private SemanticsTag semantics;

    /**
     * Human-readable summary of the evolution rule for user-facing disclosure.
     * The summary covers ordinary evolution in steps without an active autonomous assignment.
     */
    private String evolutionSummary;
}
