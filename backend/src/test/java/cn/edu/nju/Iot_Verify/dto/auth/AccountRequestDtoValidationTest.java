package cn.edu.nju.Iot_Verify.dto.auth;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountRequestDtoValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private final Validator validator = FACTORY.getValidator();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @Test
    void usernameChangeRequiresThePasswordAndOriginalUsername() {
        var request = new UpdateUsernameRequestDto();
        request.setUsername("Alice");

        var fields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString()).toList();

        assertTrue(fields.contains("currentPassword"));
        assertTrue(fields.contains("expectedUsername"));
        request.setCurrentPassword("CurrentPassword2026");
        request.setExpectedUsername("Previous");
        assertTrue(validator.validate(request).isEmpty());
        assertFalse(request.toString().contains("CurrentPassword2026"));
    }

    @Test
    void passwordChangeUsesRegistrationBoundsAndRejectsBcryptTailTruncation() {
        assertFalse(validator.validate(password("a".repeat(9))).isEmpty());
        assertTrue(validator.validate(password("a".repeat(10))).isEmpty());
        assertTrue(validator.validate(password("a".repeat(64))).isEmpty());
        assertFalse(validator.validate(password("a".repeat(65))).isEmpty());
        assertTrue(validator.validate(password("\u4e2d".repeat(24))).isEmpty());
        assertFalse(validator.validate(password("\u4e2d".repeat(25))).isEmpty());
    }

    @Test
    void passwordDtoStringDoesNotContainEitherSecret() {
        var request = password("DifferentPassword2026");
        assertFalse(request.toString().contains("CurrentPassword2026"));
        assertFalse(request.toString().contains("DifferentPassword2026"));
    }

    private ChangePasswordRequestDto password(String newPassword) {
        var request = new ChangePasswordRequestDto();
        request.setCurrentPassword("CurrentPassword2026");
        request.setNewPassword(newPassword);
        return request;
    }
}
