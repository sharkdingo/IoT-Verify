package cn.edu.nju.Iot_Verify.exception;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

@Getter
public class ValidationException extends BaseException {

    private final Map<String, String> errors;

    public ValidationException(String message) {
        super(422, message);
        this.errors = new HashMap<>();
    }

    public ValidationException(String field, String message) {
        super(422, field + ": " + message);
        this.errors = new HashMap<>();
        this.errors.put(field, message);
    }

    public ValidationException(Map<String, String> errors) {
        super(422, buildMessage(errors));
        this.errors = errors;
    }

    private static String buildMessage(Map<String, String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Validation failed";
        }
        return errors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .findFirst()
                .orElse("Validation failed");
    }
}
