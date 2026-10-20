package cn.edu.nju.Iot_Verify.exception;

import lombok.Getter;

@Getter
public class ConflictException extends BaseException {
    private final String reasonCode;
    private final String field;

    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    private ConflictException(String message, Throwable cause, String reasonCode, String field) {
        super(409, message, cause);
        this.reasonCode = reasonCode;
        this.field = field;
    }

    public static ConflictException duplicatePhone(String phone) {
        return new ConflictException("Phone number is already registered.", null, "PHONE_ALREADY_EXISTS", "phone");
    }

    public static ConflictException duplicateUsername(String username) {
        return new ConflictException("Username already exists.", null, "USERNAME_ALREADY_EXISTS", "username");
    }

    public static ConflictException duplicateTemplate(String name) {
        return new ConflictException("Device template already exists: " + name);
    }
}
