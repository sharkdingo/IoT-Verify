package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BoardContentConflictException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reports rejected scene semantics in the file's coordinates at both import entry points. */
public final class PortableSceneDiagnostics {
    private PortableSceneDiagnostics() {}

    public static ValidationException portable(ValidationException error) {
        return error.getErrors().isEmpty() ? error : new ValidationException(portableErrors(error.getErrors()));
    }

    public static BadRequestException portable(BadRequestException error) {
        if (error.getErrors().isEmpty()) return error;
        Map<String, String> errors = portableErrors(error.getErrors());
        return new BadRequestException(firstMessage(errors), errors);
    }

    public static BoardContentConflictException portable(BoardContentConflictException error) {
        return new BoardContentConflictException(error.getReasonCode(), error.getMessage(),
                portableErrors(error.getErrors()), error.getTemplateName());
    }

    private static Map<String, String> portableErrors(Map<String, String> errors) {
        Map<String, String> result = new LinkedHashMap<>();
        errors.forEach((path, reason) -> result.merge(portablePath(path), reason,
                (first, next) -> first.equals(next) ? first : first + "; " + next));
        return result;
    }

    private static String firstMessage(Map<String, String> errors) {
        var first = errors.entrySet().iterator().next();
        return first.getKey() + ": " + first.getValue();
    }

    private static String portablePath(String path) {
        String portable = path.replaceFirst("^nodes(?=\\[|$)", "devices")
                .replaceFirst("^templateSnapshots(?=\\[|$)", "templates");
        if (!portable.startsWith("rules[")) return portable;
        portable = portable.replaceFirst("\\.conditions(?=\\[|$)", ".sources")
                .replaceFirst("\\.command\\.deviceName$", ".toId")
                .replaceFirst("\\.command\\.action$", ".toApi")
                .replaceFirst("\\.command\\.contentDevice$", ".contentDevice")
                .replaceFirst("\\.command\\.content$", ".content")
                .replaceFirst("\\.command\\.contentPayloadShapeValid$", "")
                .replaceFirst("\\.ruleString$", ".name");
        if (portable.contains(".sources[")) {
            portable = portable.replaceFirst("\\.deviceName$", ".fromId")
                    .replaceFirst("\\.attribute$", ".fromApi")
                    .replaceFirst("\\.targetType$", ".itemType")
                    .replaceFirst("\\.(apiSignalShapeValid|valueConditionShapeValid)$", "");
        }
        return portable;
    }
}
