package cn.edu.nju.Iot_Verify.component.fuzz;

import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;

import java.util.ArrayList;
import java.util.List;

/** Required semantic-disclosure codes persisted with every completed fuzz run. */
public final class FuzzLimitationContract {

    private static final List<String> BASE_CODES = List.of(
            "FINITE_TRACE_TEMPLATES_1_3_4_ONLY",
            "ATTACK_TRUST_PRIVACY_CONTENT_UNSUPPORTED",
            "FINAL_ANTECEDENT_WITHOUT_SUCCESSOR_INCONCLUSIVE");

    private FuzzLimitationContract() {
    }

    public static List<String> requiredCodes(FuzzExplorationMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Exploration mode is required");
        }
        if (mode == FuzzExplorationMode.BOARD_SNAPSHOT) {
            return BASE_CODES;
        }
        List<String> required = new ArrayList<>(BASE_CODES);
        required.addAll(FuzzPaperSemantics.limitationCodes());
        return List.copyOf(required);
    }
}
