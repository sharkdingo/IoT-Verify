package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResponseHelper;
import cn.edu.nju.Iot_Verify.dto.device.DeviceCreationResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeConfigDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.DeviceLabelConflictException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.NodeService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class AddNodeTool extends DeviceRuntimeAiTool {

    private final NodeService nodeService;

    public AddNodeTool(NodeService nodeService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.nodeService = nodeService;
    }

    @Override
    public String getName() {
        return "add_device";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        String templateDesc = "Exact device template name returned by list_templates. " +
                "Map user aliases to a returned template name, preserving spaces and punctuation exactly; " +
                "for example, do not change Air Purifier to Air_Purifier. " +
                "If no listed template matches the requested device, do not invent a template name.";
        props.put("templateName", Map.of("type", "string", "description", templateDesc));

        String labelDesc = "User-facing device display name, independent of the system-generated stable device id. " +
                "Only fill if user explicitly specifies a name. " +
                "If user just says 'create an AC' without naming it, pass null. Do not invent names. " +
                "An explicit name is exact: if it is already used, creation is rejected and the tool returns an available suggestion.";
        props.put("label", Map.of("type", "string", "description", labelDesc));

        props.put("x", Map.of("type", "number", "description", "X coordinate on the canvas (default 250; must be finite and within -1000000..1000000)."));
        props.put("y", Map.of("type", "number", "description", "Y coordinate on the canvas (default 250; must be finite and within -1000000..1000000)."));
        props.put("w", Map.of("type", "integer", "description", "Width in canvas units (default 176; 80-2000)."));
        props.put("h", Map.of("type", "integer", "description", "Height in canvas units (default 128; 60-2000)."));
        props.put("state", Map.of("type", "string", "description", "Initial state; leave null to use the template default. A template with no Modes has no states: for one of those, omit this or send the UI placeholder \"Working\" — any other value is rejected."));
        props.put("currentStateTrust", Map.of(
                "type", "string", "enum", List.of("trusted", "untrusted"),
                "description", "MEDIC control-source label for the initial state. Optional for a template with Modes, and REJECTED for one without — a mode-less device has no initial state to label. It describes trusted user control when the state triggers automation, not authentication, generic data integrity, or attack probability."));
        props.put("currentStatePrivacy", Map.of(
                "type", "string", "enum", List.of("public", "private"),
                "description", "Initial-state sensitivity label. Optional for a template with Modes, and REJECTED for one without, like currentStateTrust. This does not enforce access control."));
        props.put("variables", Map.of(
                "type", "array",
                "description", "Optional device-local initial variable values. Names and values must come from the selected template; shared environment variables are not accepted here.",
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
                "description", "Optional sensitivity overrides for device-local variables. This labels model data; it does not enforce access control.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string"),
                                "privacy", Map.of("type", "string", "enum", List.of("public", "private"))),
                        "required", List.of("name", "privacy"),
                        "additionalProperties", false)));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("templateName")
        );

        return LlmToolSpec.of(getName(),
                "Add one device instance from an exact template name returned by list_templates. " +
                        "This targeted mutation preserves existing board items.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of(
                    "templateName", "label", "x", "y", "w", "h", "state",
                    "currentStateTrust", "currentStatePrivacy", "variables", "privacies"));
            String templateName = requiredTextField(args, "templateName", "arguments");
            String label = nullableTextField(args, "label", "arguments");
            Double x = parseDoubleOrNull(args, "x");
            Double y = parseDoubleOrNull(args, "y");
            Integer w = parseIntOrNull(args, "w");
            Integer h = parseIntOrNull(args, "h");
            DeviceRuntimeConfigDto runtime = parseRuntime(args);

            log.info("Executing add_device: {}", label);

            DeviceCreationResultDto result = nodeService.addNode(userId, templateName, label, x, y, runtime, w, h);
            if (result == null || result.getDevice() == null) {
                return errorJson("Device creation completed without a persisted device result.",
                        "INTERNAL_ERROR", 500);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "Device created successfully.");
            body.put("operation", "created");
            body.put("device", result.getDevice());
            body.put("initialStateSource", result.getInitialStateSource());
            body.put("defaultsApplied", result.getDefaultsApplied());
            body.put("warnings", result.getWarnings());
            body.put("environmentVariables", result.getEnvironmentVariables());
            body.put("environmentChanges", result.getEnvironmentChanges());
            return AiToolResponseHelper.success(objectMapper, body);

        } catch (ServiceUnavailableException e) {
            log.warn("add_device busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (DeviceLabelConflictException e) {
            log.info("add_device name conflict: requested={}, suggested={}",
                    e.getRequestedLabel(), e.getSuggestedLabel());
            return errorJson(e.getMessage(), "DEVICE_LABEL_CONFLICT", 409, Map.of(
                    "operation", "notCreated",
                    "requestedLabel", e.getRequestedLabel(),
                    "suggestedLabel", e.getSuggestedLabel(),
                    "requiresUserConfirmation", true,
                    "userActionRequired", true));
        } catch (BaseException e) {
            log.warn("add_device business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (Exception e) {
            log.error("add_device failed", e);
            return errorJson("Add device failed. Please retry.", "INTERNAL_ERROR", 500);
        }
    }

    private Double parseDoubleOrNull(JsonNode args, String field) throws ArgValidationException {
        if (!args.has(field)) return null;
        JsonNode node = args.path(field);
        if (node.isNull()) return null;
        if (!node.isNumber()) {
            throw new ArgValidationException(errorJson(
                    field + " must be a JSON number.",
                    "VALIDATION_ERROR",
                    400));
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value)) {
            throw new ArgValidationException(errorJson(
                    field + " must be a finite JSON number.",
                    "VALIDATION_ERROR",
                    400));
        }
        return value;
    }

    private Integer parseIntOrNull(JsonNode args, String field) throws ArgValidationException {
        if (!args.has(field)) return null;
        JsonNode node = args.path(field);
        if (node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new ArgValidationException(errorJson(
                    field + " must be a 32-bit JSON integer.",
                    "VALIDATION_ERROR",
                    400));
        }
        return node.intValue();
    }

    private DeviceRuntimeConfigDto parseRuntime(JsonNode args) throws ArgValidationException {
        DeviceRuntimeConfigDto runtime = new DeviceRuntimeConfigDto();
        boolean provided = false;
        if (args.has("state") && !args.path("state").isNull()) {
            runtime.setState(nullableTextField(args, "state", "arguments"));
            provided = runtime.getState() != null;
        }
        if (args.has("currentStateTrust") && !args.path("currentStateTrust").isNull()) {
            String value = requiredEnum(args.path("currentStateTrust"), "currentStateTrust",
                    List.of("trusted", "untrusted"));
            runtime.setCurrentStateTrust(value);
            provided = true;
        }
        if (args.has("currentStatePrivacy") && !args.path("currentStatePrivacy").isNull()) {
            String value = requiredEnum(args.path("currentStatePrivacy"), "currentStatePrivacy",
                    List.of("public", "private"));
            runtime.setCurrentStatePrivacy(value);
            provided = true;
        }
        if (args.has("variables") && !args.path("variables").isNull()) {
            runtime.setVariables(initialVariables(args.path("variables")));
            provided = true;
        }
        if (args.has("privacies") && !args.path("privacies").isNull()) {
            runtime.setPrivacies(parsePrivacies(args.path("privacies")));
            provided = true;
        }
        return provided ? runtime : null;
    }
}
