package cn.edu.nju.Iot_Verify.component.ai.model;

import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;

/** A transient provider stream failure; no unfinished tool calls may be returned for execution. */
public final class LlmStreamInterruptedException extends ServiceUnavailableException {
    public LlmStreamInterruptedException(Throwable cause) {
        super("AI response stream was interrupted", cause);
    }
}
