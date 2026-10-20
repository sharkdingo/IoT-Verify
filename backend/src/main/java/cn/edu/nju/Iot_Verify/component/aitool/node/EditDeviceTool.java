package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeConfigDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceUpdateResultDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.DeviceLabelConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceLayoutConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceRuntimeConflictException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
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

/**
 * Edits an existing device instance in place: display name, runtime initial state, or canvas layout.
 * Every edit is a reversible, targeted mutation. Label and runtime reuse their persisted baselines;
 * layout adds the same compare-and-set protection around the canvas-only update. Structural changes
 * (add/delete) use their own tools and destructive confirmation rules.
 */
@Slf4j
@Component
public class EditDeviceTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;

    public EditDeviceTool(BoardStorageService boardStorageService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
    }

    @Override
    public String getName() {
        return "edit_device";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", Map.of(
                "type", "string",
                "description", "Canonical board node id of the device to edit (from board_overview or search_devices)."));
        props.put("field", Map.of(
                "type", "string",
                "enum", List.of("label", "runtime", "layout"),
                "description", "Which aspect to edit: 'label' renames the display name; 'runtime' changes the initial model state/trust/privacy/variables; 'layout' moves or resizes the canvas card. Edit one aspect per call."));
        props.put("label", Map.of(
                "type", "string",
                "description", "Required for field=label. The new display name. It is exact: if another device already uses it, the edit is rejected."));
        props.put("state", Map.of(
                "type", "string",
                "description", "For field=runtime. New initial state; must belong to the template state domain."));
        props.put("currentStateTrust", Map.of(
                "type", "string", "enum", List.of("trusted", "untrusted"),
                "description", "For field=runtime. MEDIC control-source label for the initial state. Not authentication."));
        props.put("currentStatePrivacy", Map.of(
                "type", "string", "enum", List.of("public", "private"),
                "description", "For field=runtime. Initial-state sensitivity label. Does not enforce access control."));
        props.put("variables", Map.of(
                "type", "array",
                "description", "For field=runtime. The complete desired device-local variable values; names must come from the template. Omit to leave variables unchanged.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "value", Map.of("type", "string"),
                                "trust", Map.of("type", "string", "enum", List.of("trusted", "untrusted"))),
                        "required", List.of("name", "value"),
                        "additionalProperties", false)));
        props.put("privacies", Map.of(
                "type", "array",
                "description", "For field=runtime. The complete desired device-local sensitivity overrides. Omit to leave unchanged.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "privacy", Map.of("type", "string", "enum", List.of("public", "private"))),
                        "required", List.of("name", "privacy"),
                        "additionalProperties", false)));
        props.put("x", Map.of("type", "number", "description", "For field=layout. New X coordinate."));
        props.put("y", Map.of("type", "number", "description", "For field=layout. New Y coordinate."));
        props.put("w", Map.of("type", "integer", "description", "For field=layout. New width (80-2000)."));
        props.put("h", Map.of("type", "integer", "description", "For field=layout. New height (60-2000)."));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, List.of("id", "field"));
        return LlmToolSpec.of(getName(),
                "Edit one existing device in place: rename its label, change its runtime initial state, or compare-and-set its canvas layout. Reversible, targeted edit; other board items are preserved. Use search_devices or board_overview to get the device id first.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of(
                    "id", "field", "label", "state", "currentStateTrust", "currentStatePrivacy",
                    "variables", "privacies", "x", "y", "w", "h"));
            String id = requiredDeviceIdField(args, "id", "arguments");
            String field = requiredTextField(args, "field", "arguments").toLowerCase(Locale.ROOT);

            return switch (field) {
                case "label" -> editLabel(userId, id, args);
                case "runtime" -> editRuntime(userId, id, args);
                case "layout" -> editLayout(userId, id, args);
                default -> errorJson("Unknown field: " + field + ". Use 'label', 'runtime', or 'layout'.",
                        "VALIDATION_ERROR", 400);
            };
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (DeviceLabelConflictException e) {
            log.info("edit_device label conflict: requested={}, suggested={}",
                    e.getRequestedLabel(), e.getSuggestedLabel());
            return errorJson(e.getMessage(), "DEVICE_LABEL_CONFLICT", 409, Map.of(
                    "operation", "notUpdated",
                    "requestedLabel", e.getRequestedLabel(),
                    "suggestedLabel", e.getSuggestedLabel(),
                    "requiresUserConfirmation", true,
                    "userActionRequired", true));
        } catch (DeviceRuntimeConflictException e) {
            log.info("edit_device runtime conflict for device");
            return errorJson(
                    "The device runtime changed on the board before this edit. Refresh the device and reapply your change.",
                    "DEVICE_RUNTIME_CONFLICT", 409, Map.of(
                            "operation", "notUpdated",
                            "requiresUserConfirmation", true,
                            "currentDevice", e.getCurrentDevice()));
        } catch (DeviceLayoutConflictException e) {
            log.info("edit_device layout conflict for device");
            return errorJson(
                    "The device layout changed on the board before this edit. Refresh the device and reapply your change.",
                    "DEVICE_LAYOUT_CONFLICT", 409, Map.of(
                            "operation", "notUpdated",
                            "requiresUserConfirmation", true,
                            "currentDevice", e.getCurrentDevice()));
        } catch (ResourceNotFoundException e) {
            return errorJson(e.getMessage(), "NOT_FOUND", 404);
        } catch (ServiceUnavailableException e) {
            log.warn("edit_device busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("edit_device business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("edit_device failed", e);
            return errorJson("Edit device failed. Please retry.", "INTERNAL_ERROR", 500);
        }
    }

    private String editLabel(Long userId, String id, JsonNode args) throws ArgValidationException {
        rejectUnexpected(args, Set.of("id", "field", "label"), "label");
        String label = requiredTextField(args, "label", "arguments");
        DeviceNodeDto current = requireDevice(userId, id);
        DeviceMutationResultDto result = boardStorageService.renameNode(
                userId, id, label, current.getLabel());

        DeviceNodeDto renamed = result.getAffectedDevices().isEmpty()
                ? null : result.getAffectedDevices().get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Device renamed.");
        body.put("operation", result.getOperation());
        body.put("field", "label");
        body.put("previousLabel", result.getPreviousLabel());
        body.put("device", renamed);
        body.put("updatedSpecificationCount", result.getUpdatedSpecificationCount());
        body.put("environmentChanges", result.getEnvironmentChanges());
        return successJson(body, "Device renamed.");
    }

    private String editRuntime(Long userId, String id, JsonNode args) throws ArgValidationException {
        rejectUnexpected(args, Set.of(
                "id", "field", "state", "currentStateTrust", "currentStatePrivacy",
                "variables", "privacies"), "runtime");
        requireAtLeastOneField(args, Set.of(
                "state", "currentStateTrust", "currentStatePrivacy", "variables", "privacies"),
                "field=runtime requires at least one runtime value to update.");
        rejectExplicitNulls(args, Set.of(
                "state", "currentStateTrust", "currentStatePrivacy", "variables", "privacies"));
        validateRuntimePatchShape(args);
        DeviceNodeDto current = requireDevice(userId, id);

        DeviceRuntimeConfigDto expected = runtimeFromDevice(current);
        DeviceRuntimeConfigDto desired = desiredRuntime(current, args);
        DeviceRuntimeUpdateDto update = new DeviceRuntimeUpdateDto(expected, desired);

        DeviceUpdateResultDto result = boardStorageService.updateNodeRuntime(userId, id, update);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", result.getChangedFields().isEmpty()
                ? "No runtime change was needed; the device already had those values."
                : "Device runtime updated. The board has not been re-verified.");
        body.put("operation", result.getOperation());
        body.put("field", "runtime");
        body.put("changedFields", result.getChangedFields());
        body.put("device", result.getCurrentDevice());
        return successJson(body, "Device runtime updated.");
    }

    private String editLayout(Long userId, String id, JsonNode args) throws ArgValidationException {
        rejectUnexpected(args, Set.of("id", "field", "x", "y", "w", "h"), "layout");
        requireAtLeastOneField(args, Set.of("x", "y", "w", "h"),
                "field=layout requires at least one coordinate or dimension to update.");
        rejectExplicitNulls(args, Set.of("x", "y", "w", "h"));
        validateLayoutPatchShape(args);
        DeviceNodeDto current = requireDevice(userId, id);

        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(coordinate(args, "x", current.getPosition() != null ? current.getPosition().getX() : null));
        position.setY(coordinate(args, "y", current.getPosition() != null ? current.getPosition().getY() : null));
        Integer width = dimension(args, "w", current.getWidth());
        Integer height = dimension(args, "h", current.getHeight());
        DeviceLayoutDto expected = layoutFromDevice(current);
        DeviceLayoutDto desired = new DeviceLayoutDto(position, width, height);

        DeviceUpdateResultDto result = boardStorageService.updateNodeLayoutIfUnchanged(
                userId, id, expected, desired);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", result.getChangedFields().isEmpty()
                ? "No layout change was needed; the device already had that position and size."
                : "Device layout updated.");
        body.put("operation", result.getOperation());
        body.put("field", "layout");
        body.put("changedFields", result.getChangedFields());
        body.put("device", result.getCurrentDevice());
        return successJson(body, "Device layout updated.");
    }

    private DeviceNodeDto requireDevice(Long userId, String id) {
        return safeList(boardStorageService.getNodes(userId)).stream()
                .filter(node -> node != null && Objects.equals(node.getId(), id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Device", id));
    }

    private DeviceLayoutDto layoutFromDevice(DeviceNodeDto device) {
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(device.getPosition() != null ? device.getPosition().getX() : null);
        position.setY(device.getPosition() != null ? device.getPosition().getY() : null);
        return new DeviceLayoutDto(position, device.getWidth(), device.getHeight());
    }

    private void rejectExplicitNulls(JsonNode args, Set<String> fields) throws ArgValidationException {
        for (String field : fields) {
            if (args.has(field) && args.get(field).isNull()) {
                throw validation(field + " must not be null when provided.");
            }
        }
    }

    private void validateRuntimePatchShape(JsonNode args) throws ArgValidationException {
        if (has(args, "state")) {
            requiredTextField(args, "state", "arguments");
        }
        if (has(args, "currentStateTrust")) {
            requiredEnum(args.path("currentStateTrust"), "currentStateTrust",
                    List.of("trusted", "untrusted"));
        }
        if (has(args, "currentStatePrivacy")) {
            requiredEnum(args.path("currentStatePrivacy"), "currentStatePrivacy",
                    List.of("public", "private"));
        }
        if (has(args, "variables")) {
            parseVariables(args.path("variables"), List.of());
        }
        if (has(args, "privacies")) {
            parsePrivacies(args.path("privacies"));
        }
    }

    private void validateLayoutPatchShape(JsonNode args) throws ArgValidationException {
        if (has(args, "x")) coordinate(args, "x", null);
        if (has(args, "y")) coordinate(args, "y", null);
        if (has(args, "w")) dimension(args, "w", null);
        if (has(args, "h")) dimension(args, "h", null);
    }

    private DeviceRuntimeConfigDto runtimeFromDevice(DeviceNodeDto device) {
        DeviceRuntimeConfigDto runtime = new DeviceRuntimeConfigDto();
        runtime.setState(device.getState());
        runtime.setCurrentStateTrust(device.getCurrentStateTrust());
        runtime.setCurrentStatePrivacy(device.getCurrentStatePrivacy());
        runtime.setVariables(copyVariables(device.getVariables()));
        runtime.setPrivacies(copyPrivacies(device.getPrivacies()));
        return runtime;
    }

    private DeviceRuntimeConfigDto desiredRuntime(DeviceNodeDto current, JsonNode args)
            throws ArgValidationException {
        DeviceRuntimeConfigDto desired = runtimeFromDevice(current);
        if (args.has("state")) {
            desired.setState(requiredTextField(args, "state", "arguments"));
        }
        if (has(args, "currentStateTrust")) {
            desired.setCurrentStateTrust(requiredEnum(args.path("currentStateTrust"),
                    "currentStateTrust", List.of("trusted", "untrusted")));
        }
        if (has(args, "currentStatePrivacy")) {
            desired.setCurrentStatePrivacy(requiredEnum(args.path("currentStatePrivacy"),
                    "currentStatePrivacy", List.of("public", "private")));
        }
        if (has(args, "variables")) {
            desired.setVariables(parseVariables(args.path("variables"), current.getVariables()));
        }
        if (has(args, "privacies")) {
            desired.setPrivacies(parsePrivacies(args.path("privacies")));
        }
        return desired;
    }

    private List<VariableStateDto> parseVariables(JsonNode node, List<VariableStateDto> current)
            throws ArgValidationException {
        if (!node.isArray()) throw validation("variables must be a JSON array.");
        List<VariableStateDto> variables = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            String itemPath = "arguments.variables[" + index + "]";
            requireOnlyFields(item, itemPath, Set.of("name", "value", "trust"));
            String name = requiredTextField(item, "name", itemPath);
            String value = requiredTextField(item, "value", itemPath);
            String trust = has(item, "trust")
                    ? requiredEnum(item.path("trust"), "variables[" + index + "].trust",
                        List.of("trusted", "untrusted"))
                    : existingVariableTrust(current, name);
            variables.add(new VariableStateDto(name, value, trust));
            index++;
        }
        return variables;
    }

    private List<PrivacyStateDto> parsePrivacies(JsonNode node) throws ArgValidationException {
        if (!node.isArray()) throw validation("privacies must be a JSON array.");
        List<PrivacyStateDto> privacies = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            String itemPath = "arguments.privacies[" + index + "]";
            requireOnlyFields(item, itemPath, Set.of("name", "privacy"));
            String name = requiredTextField(item, "name", itemPath);
            String privacy = requiredEnum(item.path("privacy"), "privacies[" + index + "].privacy",
                    List.of("public", "private"));
            privacies.add(new PrivacyStateDto(name, privacy));
            index++;
        }
        return privacies;
    }

    private List<VariableStateDto> copyVariables(List<VariableStateDto> values) {
        if (values == null) return null;
        List<VariableStateDto> copy = new ArrayList<>();
        for (VariableStateDto value : values) {
            copy.add(value == null ? null
                    : new VariableStateDto(value.getName(), value.getValue(), value.getTrust()));
        }
        return copy;
    }

    private List<PrivacyStateDto> copyPrivacies(List<PrivacyStateDto> values) {
        if (values == null) return null;
        List<PrivacyStateDto> copy = new ArrayList<>();
        for (PrivacyStateDto value : values) {
            copy.add(value == null ? null : new PrivacyStateDto(value.getName(), value.getPrivacy()));
        }
        return copy;
    }

    private Double coordinate(JsonNode args, String field, Double fallback) throws ArgValidationException {
        if (!has(args, field)) {
            return fallback;
        }
        JsonNode node = args.path(field);
        if (!node.isNumber()) {
            throw validation(field + " must be a number.");
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || Math.abs(value) > DeviceLayoutDto.MAX_ABS_POSITION) {
            throw validation(field + " must be a finite number between -"
                    + DeviceLayoutDto.MAX_ABS_POSITION + " and " + DeviceLayoutDto.MAX_ABS_POSITION + ".");
        }
        return value;
    }

    private Integer dimension(JsonNode args, String field, Integer fallback) throws ArgValidationException {
        if (!has(args, field)) {
            return fallback;
        }
        JsonNode node = args.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw validation(field + " must be an integer.");
        }
        int value = node.intValue();
        int min = "w".equals(field) ? DeviceLayoutDto.MIN_WIDTH : DeviceLayoutDto.MIN_HEIGHT;
        int max = "w".equals(field) ? DeviceLayoutDto.MAX_WIDTH : DeviceLayoutDto.MAX_HEIGHT;
        if (value < min || value > max) {
            throw validation(field + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private boolean has(JsonNode node, String field) {
        return node != null && node.has(field);
    }

    private void requireAtLeastOneField(JsonNode args, Set<String> fields, String message)
            throws ArgValidationException {
        if (fields.stream().noneMatch(args::has)) {
            throw validation(message);
        }
    }

    private String existingVariableTrust(List<VariableStateDto> current, String name) {
        if (current == null) return null;
        return current.stream()
                .filter(Objects::nonNull)
                .filter(variable -> Objects.equals(variable.getName(), name))
                .map(VariableStateDto::getTrust)
                .findFirst()
                .orElse(null);
    }

    private void rejectUnexpected(JsonNode args, Set<String> allowed, String field)
            throws ArgValidationException {
        List<String> unexpected = new ArrayList<>();
        args.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unexpected.add(name);
            }
        });
        if (!unexpected.isEmpty()) {
            throw validation("field=" + field + " does not accept: " + String.join(", ", unexpected) + ".");
        }
    }

    private String requiredEnum(JsonNode node, String field, List<String> allowed) throws ArgValidationException {
        if (node == null || node.isNull() || !node.isTextual()) {
            throw validation(field + " must be one of: " + String.join(", ", allowed) + ".");
        }
        String value = trimToNull(node.textValue());
        String normalized = value == null ? null : value.toLowerCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw validation(field + " must be one of: " + String.join(", ", allowed) + ".");
        }
        return normalized;
    }

    private ArgValidationException validation(String message) {
        return new ArgValidationException(errorJson(message, "VALIDATION_ERROR", 400));
    }
}
