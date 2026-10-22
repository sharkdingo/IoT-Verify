package cn.edu.nju.Iot_Verify.dto.verification;

/** Reachability of a response trigger in the checked model; separate from the property verdict. */
public enum AntecedentReachability {
    NOT_APPLICABLE, REACHABLE, UNREACHABLE, INCONCLUSIVE;

    public static boolean appliesTo(String templateId) {
        return "4".equals(templateId) || "5".equals(templateId) || "6".equals(templateId);
    }
}
