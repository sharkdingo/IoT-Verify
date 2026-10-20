package cn.edu.nju.Iot_Verify.exception;

import cn.edu.nju.Iot_Verify.dto.auth.AccountProfileDto;
import lombok.Getter;

@Getter
public class AccountChangeException extends BaseException {
    private final String reasonCode;
    private final String field;
    private final AccountProfileDto currentProfile;

    private AccountChangeException(int code, String reasonCode, String field,
                                   String message, AccountProfileDto currentProfile) {
        super(code, message);
        this.reasonCode = reasonCode;
        this.field = field;
        this.currentProfile = currentProfile;
    }

    public static AccountChangeException incorrectPassword() {
        return new AccountChangeException(400, "CURRENT_PASSWORD_INCORRECT", "currentPassword",
                "Current password is incorrect.", null);
    }

    public static AccountChangeException unchangedPassword() {
        return new AccountChangeException(400, "NEW_PASSWORD_UNCHANGED", "newPassword",
                "New password must differ from the current password.", null);
    }

    public static AccountChangeException duplicateUsername() {
        return new AccountChangeException(409, "USERNAME_ALREADY_EXISTS", "username",
                "Username already exists.", null);
    }

    public static AccountChangeException staleProfile(AccountProfileDto currentProfile) {
        return new AccountChangeException(409, "ACCOUNT_PROFILE_CHANGED", "expectedUsername",
                "Account details changed; reload the current username before saving.", currentProfile);
    }
}
