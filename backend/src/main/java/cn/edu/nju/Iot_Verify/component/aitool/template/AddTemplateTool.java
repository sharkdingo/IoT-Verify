package cn.edu.nju.Iot_Verify.component.aitool.template;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class AddTemplateTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;
    private final DeviceTemplateSchemaValidator deviceTemplateSchemaValidator;
    private ObjectMapper tolerantMapper;

    public AddTemplateTool(BoardStorageService boardStorageService,
                           ObjectMapper objectMapper,
                           DeviceTemplateSchemaValidator deviceTemplateSchemaValidator) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
        this.deviceTemplateSchemaValidator = deviceTemplateSchemaValidator;
    }

    @PostConstruct
    void initTolerantMapper() {
        tolerantMapper = objectMapper.copy();
        tolerantMapper.setConfig(
                tolerantMapper.getDeserializationConfig()
                        .with(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        );
    }

    @Override
    public String getName() {
        return "add_template";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("name", Map.of(
                "type", "string",
                "description", "Template name (e.g. 'Smart Lock', 'Temperature Sensor')"
        ));

        props.put("manifest", Map.of(
                "type", "object",
                "description", "Full device manifest JSON defining the device behavior. " +
                        "For stateful devices, must include: Name, Description, Modes (array of mode dimension names), InitState (one concrete WorkingStates.Name, semicolon-separated for multi-mode; no wildcard/partial/case alias), " +
                        "WorkingStates (array of {Name, Description, Trust, Privacy, Dynamics[]}), " +
                        "Every multi-mode WorkingState.Name is a complete semicolon-separated tuple with one concrete value per mode. If a mode-state component is reused in several tuples, its Trust and Privacy labels must be identical because the formal model stores one label for that component. " +
                        "Transitions (array of {Name, StartState (optional), EndState (optional for variable transitions), Trigger{Attribute,Relation,Value}, Assignments[{Attribute,Value}]}); every Transition requires a Trigger and has exactly one state or variable effect. Transition event signals are not supported. " +
                        "APIs (array of {Name, Description, StartState, EndState, Signal, optional AcceptsContent, Trigger:null}); StartState is required: use an explicit empty string or '_' pattern for any state, otherwise provide the state precondition. Signal is required and must explicitly be true (observable one-step automation/specification trigger) or false (command only). AcceptsContent defaults to false; set it true only when a rule invoking this action may carry one explicitly selected Content sensitivity label. It does not model payload bytes or access control. APIs require a stateful template and cannot assign variables. " +
                        "Use a triggered Transition for internal or shared-variable assignments; every assignment target and value must match a declared variable domain. " +
                        "For stateless sensors (no modes), Modes/InitState/WorkingStates can all be empty. " +
                        "Optional: Icon (UI-only self-contained data:image), InternalVariables, ImpactedVariables, Contents. " +
                        "Every InternalVariables item must explicitly set IsInside, a domain, and FalsifiableWhenCompromised. IsInside=true means a device-local instance value; IsInside=false means one scene-wide shared environment value. Never omit it or infer ownership from the device type. Define the domain with Values (case-sensitive enum tokens, for example [on, off] for two-valued data) or LowerBound+UpperBound; an omitted domain is invalid. TRUE and FALSE are reserved literals used for API signal conditions. Set FalsifiableWhenCompromised true only when compromise may replace that reported sensor/received-data value with any value in its declared domain; use false for actuator state, progress, and setpoints. " +
                        "This flag applies to both shared environment readings (IsInside=false) and device-local readings (IsInside=true), so API presence does not classify a device as purely sensor or actuator. " +
                        "Every ImpactedVariables name must have its domain in this same manifest as an IsInside=false InternalVariable. Every IsInside=false declaration must state Reads explicitly (it is rejected if omitted, and rejected if present on an IsInside=true declaration): Reads=false when the device only affects the value without observing it, so its rules and specifications cannot use it as a condition source, and Reads=true when the device also reads it. " +
                        // `EnvironmentDomains` used to be named here as a required section. The schema
                        // *removed* that array (see its own `$comment`) and runs with
                        // `additionalProperties: false`, so emitting it is a guaranteed 400 — measured:
                        // "property EnvironmentDomains not defined in the schema and the schema does not
                        // allow additional properties". The capability it used to encode is now the
                        // `Reads` field, described in the sentence above this one.
                        "Every WorkingState and InternalVariable must explicitly define Trust and Privacy; every Content must explicitly define Privacy. Trust is MEDIC control-source trust: one trusted trigger source retains trusted control and only an all-untrusted trigger set makes the target untrusted; it is not authentication or generic data integrity. Never treat a missing security label as trusted or public. " +
                        // NaturalChangeRate was described nowhere, while the schema requires it for exactly
                        // the commonest case: a shared numeric reading. Measured with an isolated A/B pair
                        // that differed in this field alone — without it, 400 "required property
                        // NaturalChangeRate not found"; with it, 200. So every temperature, humidity or
                        // illuminance variable authored from this description failed.
                        "An IsInside=false InternalVariable with integer LowerBound/UpperBound must also state NaturalChangeRate: the per-step change bound, either a single integer (\"1\" means every step moves within 0..1) or an interval (\"[-1, 1]\", the exact MEDIC disturbance rule). \"0\" disables independent drift; any other interval is a deliberate project extension, not a default. Omitting it is rejected. " +
                        "Icon is display-only and must not be used to express device behavior. " +
                        "The manifest must match backend/device-template-schema.json exactly."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, List.of("name", "manifest")
        );

        return LlmToolSpec.of(getName(), "Add a custom device template. Templates define device behavior including states, transitions, and APIs. " +
                "Use list_templates to see existing templates for reference.", schema);
    }

    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("name", "manifest"));
            String name = requiredTextField(args, "name", "arguments");

            JsonNode manifestNode = args.path("manifest");
            if (manifestNode.isMissingNode() || !manifestNode.isObject()) {
                return errorJson("Manifest object is required.", "VALIDATION_ERROR", 400);
            }
            deviceTemplateSchemaValidator.validateRawManifest(name, manifestNode);

            DeviceTemplateDto.DeviceManifest manifest = tolerantMapper.treeToValue(
                    manifestNode, DeviceTemplateDto.DeviceManifest.class);
            if (manifest == null) {
                return errorJson("Manifest could not be parsed.", "VALIDATION_ERROR", 400);
            }
            // Align with backend: no-mode sensors have all three empty; otherwise all must be present
            boolean hasModes = manifest.getModes() != null && !manifest.getModes().isEmpty();
            boolean hasInitState = manifest.getInitState() != null && !manifest.getInitState().isBlank();
            boolean hasWorkingStates = manifest.getWorkingStates() != null && !manifest.getWorkingStates().isEmpty();
            if (hasModes || hasInitState || hasWorkingStates) {
                if (!hasModes) {
                    return errorJson("Manifest must contain non-empty Modes when InitState or WorkingStates are defined.", "VALIDATION_ERROR", 400);
                }
                if (!hasInitState) {
                    return errorJson("Manifest must contain InitState when Modes are defined.", "VALIDATION_ERROR", 400);
                }
                if (!hasWorkingStates) {
                    return errorJson("Manifest must contain non-empty WorkingStates when Modes are defined.", "VALIDATION_ERROR", 400);
                }
            }

            DeviceTemplateDto dto = new DeviceTemplateDto();
            dto.setName(name);
            dto.setManifest(manifest);

            DeviceTemplateDto saved = boardStorageService.addDeviceTemplate(userId, dto);
            return successJson(Map.of(
                    "message", "Template added successfully.",
                    "operation", "created",
                    "template", saved
            ), "Template added successfully.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("add_template busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("add_template business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("add_template failed", e);
            return errorJson("Failed to add template. Please check manifest format and retry.",
                    "INTERNAL_ERROR", 500);
        }
    }
}
