package cn.edu.nju.Iot_Verify.exception;

import lombok.Getter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Authored board-content conflicts need a stable category rather than message parsing. */
@Getter
public class BoardContentConflictException extends ConflictException {
    private final String reasonCode;
    private final Map<String, String> errors;
    private final String templateName;

    public BoardContentConflictException(String reasonCode, String message, Map<String, String> errors) {
        this(reasonCode, message, errors, null);
    }

    public BoardContentConflictException(String reasonCode, String message,
                                         Map<String, String> errors, String templateName) {
        super(message);
        this.reasonCode = reasonCode;
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
        this.templateName = templateName;
    }
}
