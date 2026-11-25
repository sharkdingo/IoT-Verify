package cn.edu.nju.Iot_Verify.exception;

/** Historical verification evidence cannot authorize a repair under different model semantics. */
public final class ModelSemanticsChangedException extends ConflictException {
    public static final String REASON_CODE = "MODEL_SEMANTICS_CHANGED";

    public ModelSemanticsChangedException() {
        super("This counterexample was recorded under different model semantics. "
                + "Run verification again before using automatic repair.", null, REASON_CODE, "traceId");
    }
}
