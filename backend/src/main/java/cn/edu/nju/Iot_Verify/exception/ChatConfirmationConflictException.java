package cn.edu.nju.Iot_Verify.exception;

/** A replaced, cancelled, or expired protected preview cannot authorize the current action. */
public class ChatConfirmationConflictException extends ConflictException {

    public ChatConfirmationConflictException() {
        super("The selected protected action is no longer pending. Refresh the conversation before confirming.");
    }
}
