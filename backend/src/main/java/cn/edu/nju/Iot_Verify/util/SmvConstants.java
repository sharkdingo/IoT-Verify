package cn.edu.nju.Iot_Verify.util;

import java.util.List;

/**
 * NuSMV 相关的共享常量。
 */
public final class SmvConstants {

    /** 原定义于 VerificationServiceImpl 和 TraceMapper 中 */
    public static final String UNKNOWN_VIOLATED_SPEC_ID = "__UNKNOWN_SPEC__";

    /** Internal NuSMV counter; translated before it reaches API clients. */
    public static final String NUSMV_COMPROMISED_POINT_COUNT = "iot_verify_compromised_point_count";

    /** Internal deterministic trace probes; translated to user-facing rule snapshots. */
    public static final String RULE_EXECUTION_PROBE_PREFIX = "iot_verify_rule_fired_";

    /** Rule-guard components condition adjustment reads from a forward-verification counterexample. */
    public static final String GUARD_PROBE_PREFIX = "iot_verify_guard_probe_";

    /**
     * Prefixes the automatic-fix strategies mint main-module names under.
     *
     * <p>Unlike the {@code iot_verify_} names in this class, the frozen-variable prefixes are *not* namespaced
     * away from user input — and {@code param_} cannot be: it is part of the wire contract for
     * {@code PreferredRangeSelection.targetId}, validated by a {@code @Pattern} here and by
     * {@code ^param_[A-Za-z0-9_-]{24}$} in the frontend's {@code fixResponse.ts}. So a device may legitimately
     * be named {@code condition_value_r0_c1} — verified against the running API, which accepts it — and the
     * collision only surfaces inside {@code SmvMainModuleBuilder} when a fix is requested, long after the board
     * was saved and verified.
     *
     * <p>{@link #GUARD_PROBE_PREFIX} is namespaced but belongs here for the same reason: how many probes a
     * forward verification defines is only known once condition adjustment runs, so no concrete name exists
     * for board admission or request validation to check.
     *
     * <p>Listing them here lets board admission and the request-time validator reject the device name up
     * front, where every other generated identifier is already checked, instead of failing during fix
     * generation.
     */
    public static final List<String> FIX_GENERATED_NAME_PREFIXES =
            List.of("param_", "lambda_", "condition_value_", GUARD_PROBE_PREFIX);

    /** Internal fixed attack choices for user-visible automation delivery links. */
    public static final String AUTOMATION_LINK_ATTACK_PREFIX = "iot_verify_automation_link_compromised_";

    /** User-facing trace name for compromised device-instance plus automation-link points. */
    public static final String TRACE_COMPROMISED_POINT_COUNT = "compromisedPointCount";

    private SmvConstants() {
    }
}
