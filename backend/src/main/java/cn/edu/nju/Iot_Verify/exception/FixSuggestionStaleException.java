package cn.edu.nju.Iot_Verify.exception;

/** The submitted signed proposal cannot be used to apply a repair. */
public final class FixSuggestionStaleException extends BadRequestException {

    public static final String REASON_CODE = "FIX_SUGGESTION_STALE";

    public FixSuggestionStaleException() {
        super("This fix suggestion is stale or no longer matches the displayed proposal. Run the fix again before applying it.");
    }
}
