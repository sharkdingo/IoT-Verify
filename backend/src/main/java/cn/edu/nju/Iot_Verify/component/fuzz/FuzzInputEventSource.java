package cn.edu.nju.Iot_Verify.component.fuzz;

/** Stable provenance for one persisted fuzz-input event. */
public enum FuzzInputEventSource {
    MODEL_CHOICE,
    RANDOM_INITIAL_STATE,
    SEED_EVENT;

    public int causalOrder() {
        return switch (this) {
            case RANDOM_INITIAL_STATE -> 0;
            case SEED_EVENT -> 1;
            case MODEL_CHOICE -> 2;
        };
    }
}
