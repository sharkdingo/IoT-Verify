package cn.edu.nju.Iot_Verify.dto.chat;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Explicit UI command for a server-side pending protected action. */
@Data
public class ChatConfirmationCommandDto {

    @NotNull(message = "Confirmation action is required")
    private Action action;

    @NotNull(message = "Confirmation kind is required")
    private Kind kind;

    @NotBlank(message = "Confirmation identity is required")
    private String confirmationId;

    public enum Action {
        CONFIRM,
        CANCEL
    }

    public enum Kind {
        DESTRUCTIVE,
        DEFAULT_TEMPLATE_RESET,
        SCENE_REPLACEMENT
    }
}
