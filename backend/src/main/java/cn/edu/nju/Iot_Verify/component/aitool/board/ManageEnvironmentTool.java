package cn.edu.nju.Iot_Verify.component.aitool.board;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableUpdateRequestDto;
import cn.edu.nju.Iot_Verify.exception.EnvironmentVariableConflictException;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Targeted access to the board-level environment values used as model initial inputs. */
@Slf4j
@Component
public class ManageEnvironmentTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;

    public ManageEnvironmentTool(BoardStorageService boardStorageService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
    }

    @Override
    public String getName() {
        return "manage_environment";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of(
                "type", "string",
                "enum", List.of("list", "set", "reset"),
                "description", "list reads the current shared environment pool; set changes only explicitly "
                        + "supplied fields and preserves the rest; reset restores one variable's template "
                        + "defaults. set is compare-and-set against the pool as this call reads it: if the "
                        + "variable changed meanwhile it is rejected with ENVIRONMENT_VARIABLE_STALE and "
                        + "nothing is written, so re-read with list before setting again."));
        properties.put("name", Map.of(
                "type", "string",
                "description", "Environment variable name. Required for set/reset; use list first. Only variables required by devices on the current board are editable."));
        properties.put("value", Map.of(
                "type", "string",
                "description", "Initial model value for set. Individually optional, but set requires at least one of value, trust or privacy - supplying none is rejected. It must belong to the template-declared domain."));
        properties.put("trust", Map.of(
                "type", "string",
                "enum", List.of("trusted", "untrusted"),
                "description", "MEDIC control-source label; individually optional, see value for the joint requirement. When several values trigger one rule, one trusted source retains trusted control and only an all-untrusted set makes the target untrusted. This is not authentication or generic data integrity."));
        properties.put("privacy", Map.of(
                "type", "string",
                "enum", List.of("public", "private"),
                "description", "Sensitivity label for MEDIC privacy propagation; individually optional, see value for the joint requirement. This does not enforce access control or encryption."));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", properties, List.of("action"));
        return LlmToolSpec.of(getName(),
                "List or update shared board environment inputs. These are formal-model initial values and MEDIC labels, not live sensor readings or runtime security controls.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of(
                    "action", "name", "value", "trust", "privacy"));
            String action = requiredText(args, "action").toLowerCase(Locale.ROOT);
            return switch (action) {
                case "list" -> executeList(userId, args);
                case "set" -> executeSet(userId, args);
                case "reset" -> executeReset(userId, args);
                default -> errorJson("Unknown action: " + action + ". Use list, set, or reset.",
                        "VALIDATION_ERROR", 400);
            };
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (IllegalArgumentException e) {
            log.warn("manage_environment validation failed: {}", e.getMessage());
            return errorJson(e.getMessage(), "VALIDATION_ERROR", 400);
        } catch (ServiceUnavailableException e) {
            log.warn("manage_environment busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (EnvironmentVariableConflictException e) {
            /*
             * A stale-baseline rejection, ahead of the generic `BaseException` branch below.
             *
             * That branch would already return the right 409 — this exception extends `ConflictException`
             * extends `BaseException` — but under the flat `BUSINESS_ERROR` code and with only the message.
             * A conflict is the one failure here that the model can act on by itself, and to do that it
             * needs three things the generic branch drops: a reason code it can branch on, the variable's
             * *current* row so it can decide whether its intent still applies, and an instruction not to
             * simply retry the same write. Nothing was written, so this is safe to retry after re-reading.
             */
            log.warn("manage_environment stale baseline for '{}'", e.getVariableName());
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("variableName", e.getVariableName());
            conflict.put("currentVariable", e.getCurrentVariable());
            conflict.put("guidance", "The variable changed after this call read it, so nothing was written. "
                    + "Re-read with action=list, confirm the change is still wanted against the current "
                    + "value, then set again. Do not repeat the same set blindly.");
            return errorJson(e.getMessage(), EnvironmentVariableConflictException.REASON_CODE, 409, conflict);
        } catch (BaseException e) {
            log.warn("manage_environment business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("manage_environment failed", e);
            return errorJson("Environment operation failed. Refresh the board and retry.",
                    "INTERNAL_ERROR", 500);
        }
    }

    private String executeList(Long userId, JsonNode args) throws ArgValidationException {
        requireOnlyFields(args, "arguments", Set.of("action"));
        List<BoardEnvironmentVariableDto> current = safeList(
                boardStorageService.getEnvironmentVariables(userId));
        Map<String, Object> response = baseResponse(current);
        response.put("message", current.isEmpty()
                ? "The current board has no shared environment inputs."
                : "Current shared environment inputs returned.");
        response.put("operation", "listed");
        response.put("changesApplied", false);
        return readOnlySuccessJson(response);
    }

    private String executeSet(Long userId, JsonNode args) throws ArgValidationException {
        requireOnlyFields(args, "arguments", Set.of("action", "name", "value", "trust", "privacy"));
        String name = requiredText(args, "name");
        boolean hasValue = hasNonNullField(args, "value");
        boolean hasTrust = hasNonNullField(args, "trust");
        boolean hasPrivacy = hasNonNullField(args, "privacy");
        rejectExplicitNull(args, "value");
        rejectExplicitNull(args, "trust");
        rejectExplicitNull(args, "privacy");
        if (!hasValue && !hasTrust && !hasPrivacy) {
            throw validation("set requires at least one of value, trust, or privacy. Use reset to restore template defaults.");
        }

        String value = hasValue ? requiredText(args, "value") : null;
        String trust = hasTrust ? requiredEnum(args, "trust", List.of("trusted", "untrusted")) : null;
        String privacy = hasPrivacy ? requiredEnum(args, "privacy", List.of("public", "private")) : null;
        List<String> suppliedFields = new ArrayList<>();
        if (hasValue) suppliedFields.add("value");
        if (hasTrust) suppliedFields.add("trust");
        if (hasPrivacy) suppliedFields.add("privacy");

        /*
         * Compare-and-set against the baseline this call actually read, through the same
         * `saveEnvironmentVariables` path the REST/UI surface uses.
         *
         * The previous implementation passed a mutator to `updateEnvironmentVariables`, which reads the
         * pool and applies the change inside one locked transaction - atomic, but with no *precondition*.
         * That made the assistant last-writer-wins on a surface where the UI answers 409: the user edits
         * a variable's value in the Environment Pool, the assistant (holding a snapshot from an earlier
         * turn) writes `trust`, and the user's value is silently reverted, because the fields this call
         * does not set are carried over from what the assistant remembered. Two authorities over one row
         * with no conflict detection.
         *
         * `expected` is the COMPLETE row as read here - value, trust and privacy - so a concurrent change
         * to a field this call is not setting still rejects the write. Narrowing the baseline to the
         * fields being written would let exactly the silent-revert case through.
         */
        List<BoardEnvironmentVariableDto> baseline = boardStorageService.getEnvironmentVariables(userId);
        BoardEnvironmentVariableDto previous = find(baseline, name);
        if (previous == null) {
            throw unknownVariable(name, baseline);
        }

        EnvironmentVariableUpdateRequestDto request = new EnvironmentVariableUpdateRequestDto();
        request.setName(previous.getName());
        request.setExpected(new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                previous.getValue(), previous.getTrust(), previous.getPrivacy()));
        // Only the supplied fields go into `desired`; the service preserves the rest from `expected`,
        // which is what keeps "set changes only explicitly supplied fields" true.
        request.setDesired(new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                hasValue ? value : null,
                hasTrust ? trust : null,
                hasPrivacy ? privacy : null));

        List<BoardEnvironmentVariableDto> current = boardStorageService
                .saveEnvironmentVariables(userId, List.of(request))
                .getEnvironmentVariables();
        BoardEnvironmentVariableDto updated = find(current, name);
        boolean changed = !Objects.equals(previous, updated);

        Map<String, Object> response = mutationResponse(current, previous, updated);
        response.put("message", changed
                ? "Environment input updated. The new value and labels will be used by later model generation."
                : "No environment input changed because the supplied fields already had those values.");
        response.put("operation", changed ? "updated" : "unchanged");
        response.put("changesApplied", changed);
        response.put("suppliedFields", suppliedFields);
        response.put("unspecifiedFieldsPreserved", List.of("value", "trust", "privacy").stream()
                .filter(field -> !suppliedFields.contains(field)).toList());
        return successJson(response);
    }

    /**
     * Restore one variable to its template defaults.
     *
     * <p>Deliberately NOT compare-and-set, unlike {@code set}. This writes {@code (name, null, null, null)}
     * so the defaults are re-projected, carrying nothing over from any snapshot — so it cannot silently
     * revert a concurrent edit the way a stale-baseline partial update can. "Restore the defaults" is also
     * a complete instruction on its own: its outcome does not depend on what the value happened to be, so
     * a baseline precondition would reject the write without changing what the user gets.
     *
     * <p>The mutator still runs inside one locked transaction, so it is atomic; what it lacks is a
     * precondition, and here that is correct rather than missing.
     */
    private String executeReset(Long userId, JsonNode args) throws ArgValidationException {
        requireOnlyFields(args, "arguments", Set.of("action", "name"));
        String name = requiredText(args, "name");
        AtomicReference<BoardEnvironmentVariableDto> previousRef = new AtomicReference<>();
        List<BoardEnvironmentVariableDto> current = boardStorageService.updateEnvironmentVariables(userId, values -> {
            int index = indexOf(values, name);
            if (index < 0) {
                throw unknownVariable(name, values);
            }
            previousRef.set(copy(values.get(index)));
            List<BoardEnvironmentVariableDto> next = copyAll(values);
            next.set(index, new BoardEnvironmentVariableDto(name, null, null, null));
            return next;
        });
        BoardEnvironmentVariableDto previous = previousRef.get();
        BoardEnvironmentVariableDto reset = find(current, name);
        boolean changed = !Objects.equals(previous, reset);

        Map<String, Object> response = mutationResponse(current, previous, reset);
        response.put("message", changed
                ? "Template defaults restored for this environment input."
                : "This environment input already matched its template defaults; no value changed.");
        response.put("operation", changed ? "defaults_restored" : "unchanged");
        response.put("changesApplied", changed);
        response.put("defaultsRestored", true);
        return successJson(response);
    }

    private Map<String, Object> mutationResponse(List<BoardEnvironmentVariableDto> current,
                                                 BoardEnvironmentVariableDto previous,
                                                 BoardEnvironmentVariableDto updated) {
        Map<String, Object> response = baseResponse(current);
        response.put("previousVariable", previous);
        response.put("currentVariable", updated);
        return response;
    }

    private Map<String, Object> baseResponse(List<BoardEnvironmentVariableDto> current) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("environmentVariables", current);
        response.put("currentCount", current.size());
        response.put("modelMeaning", Map.of(
                "value", "Initial value used by the generated formal model; it is not a live sensor reading.",
                "trust", "Source label propagated by the MEDIC model; it is not authentication or a correctness guarantee.",
                "privacy", "Sensitivity label propagated by the MEDIC model; it does not enforce access control or encryption."));
        return response;
    }

    private String requiredText(JsonNode args, String field) throws ArgValidationException {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw validation("'" + field + "' is required and must be a non-empty string.");
        }
        String text = trimToNull(value.textValue());
        if (text == null) {
            throw validation("'" + field + "' is required and must be a non-empty string.");
        }
        return text;
    }

    private String requiredEnum(JsonNode args, String field, List<String> allowed)
            throws ArgValidationException {
        String value = requiredText(args, field).toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw validation(field + " must be one of: " + String.join(", ", allowed) + ".");
        }
        return value;
    }

    private void rejectExplicitNull(JsonNode args, String field) throws ArgValidationException {
        if (args != null && args.has(field) && args.get(field).isNull()) {
            throw validation(field + " cannot be null for set. Omit it to preserve the current field, or use reset for template defaults.");
        }
    }

    private boolean hasNonNullField(JsonNode args, String field) {
        return args != null && args.has(field) && !args.get(field).isNull();
    }

    private int indexOf(List<BoardEnvironmentVariableDto> values, String name) {
        for (int i = 0; i < values.size(); i++) {
            BoardEnvironmentVariableDto value = values.get(i);
            if (value != null && name.equals(value.getName())) return i;
        }
        return -1;
    }

    private BoardEnvironmentVariableDto find(List<BoardEnvironmentVariableDto> values, String name) {
        return safeList(values).stream()
                .filter(value -> value != null && name.equals(value.getName()))
                .findFirst()
                .map(this::copy)
                .orElse(null);
    }

    private IllegalArgumentException unknownVariable(String name, List<BoardEnvironmentVariableDto> values) {
        List<String> available = safeList(values).stream()
                .filter(Objects::nonNull)
                .map(BoardEnvironmentVariableDto::getName)
                .filter(Objects::nonNull)
                .toList();
        String suffix = available.isEmpty()
                ? " The current board has no shared environment inputs."
                : " Available variables: " + String.join(", ", available) + ".";
        return new IllegalArgumentException(
                "Environment variable '" + name + "' is not required by the current board." + suffix);
    }

    private List<BoardEnvironmentVariableDto> copyAll(List<BoardEnvironmentVariableDto> values) {
        return new ArrayList<>(safeList(values).stream().map(this::copy).toList());
    }

    private BoardEnvironmentVariableDto copy(BoardEnvironmentVariableDto value) {
        if (value == null) return null;
        return new BoardEnvironmentVariableDto(
                value.getName(), value.getValue(), value.getTrust(), value.getPrivacy());
    }

    private ArgValidationException validation(String message) {
        return new ArgValidationException(errorJson(message, "VALIDATION_ERROR", 400));
    }
}
