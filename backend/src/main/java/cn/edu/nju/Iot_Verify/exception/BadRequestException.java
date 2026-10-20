package cn.edu.nju.Iot_Verify.exception;

import lombok.Getter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class BadRequestException extends BaseException {

    private final Map<String, String> errors;

    public BadRequestException(String message) {
        super(400, message);
        this.errors = Map.of();
    }

    public BadRequestException(String message, Throwable cause) {
        super(400, message, cause);
        this.errors = Map.of();
    }

    public BadRequestException(String message, Map<String, String> errors) {
        super(400, message);
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }
}
