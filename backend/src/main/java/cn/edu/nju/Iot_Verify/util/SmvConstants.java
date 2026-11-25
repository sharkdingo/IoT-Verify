package cn.edu.nju.Iot_Verify.util;

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

    /** Internal fixed attack choices for user-visible automation delivery links. */
    public static final String AUTOMATION_LINK_ATTACK_PREFIX = "iot_verify_automation_link_compromised_";

    /** Main-module variable prefix for a shared environment value. */
    public static final String ENVIRONMENT_POOL_PREFIX = "a_";

    /** Optional attack flag in an attackable device module. */
    public static final String DEVICE_ATTACK_FLAG = "is_attack";

    /** User-facing trace name for compromised device-instance plus automation-link points. */
    public static final String TRACE_COMPROMISED_POINT_COUNT = "compromisedPointCount";

    private SmvConstants() {
    }
}
