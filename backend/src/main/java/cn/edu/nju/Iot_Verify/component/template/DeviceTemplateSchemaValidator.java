package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.util.NaturalChangeRateParser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DeviceTemplateSchemaValidator {

    public static final String CANONICAL_SCHEMA_PATH = "backend/device-template-schema.json";

    private final ObjectMapper objectMapper;

    @Value("${device-template.schema-path:device-template-schema.json}")
    private String schemaPath = "device-template-schema.json";

    private volatile JsonSchema schema;
    private volatile ObjectMapper nonNullObjectMapper;

    public void validateRawManifest(String templateName, JsonNode manifestNode) {
        if (manifestNode == null || !manifestNode.isObject()) {
            throw new BadRequestException("Template manifest is required and must be a JSON object.",
                    Map.of("manifest", "Template manifest is required and must be a JSON object"));
        }
        validateNode(templateName, manifestNode);
    }

    public void validateManifest(String templateName, DeviceManifest manifest) {
        if (manifest == null) {
            throw new BadRequestException("Template manifest is required.",
                    Map.of("manifest", "Template manifest is required"));
        }
        validateNode(templateName, toSchemaNode(manifest));
    }

    /**
     * Serialize the typed manifest in the same null-free shape validated by the canonical schema.
     * Persisting a different shape can make a previously accepted template fail at model-build time.
     */
    public String toCanonicalJson(DeviceManifest manifest) {
        if (manifest == null) {
            throw new BadRequestException("Template manifest is required.");
        }
        try {
            return getNonNullObjectMapper().writeValueAsString(manifest);
        } catch (JsonProcessingException exception) {
            throw InternalServerException.jsonSerializationFailed(exception);
        }
    }

    public JsonNode getSchemaNode() {
        return readSchemaNode().deepCopy();
    }

    private void validateNode(String templateName, JsonNode manifestNode) {
        try {
            validateNodeContents(templateName, manifestNode);
        } catch (BadRequestException error) {
            if (!error.getErrors().isEmpty()) throw error;
            throw new BadRequestException(error.getMessage(), Map.of("manifest", error.getMessage()));
        }
    }

    private void validateNodeContents(String templateName, JsonNode manifestNode) {
        String name = templateName == null || templateName.isBlank() ? "<unnamed>" : templateName;
        validateUnsupportedApiBehavior(name, manifestNode);
        Set<ValidationMessage> messages = getSchema().validate(manifestNode);
        if (!messages.isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            List<ValidationMessage> reported = messages.stream()
                    .sorted(Comparator.comparing((ValidationMessage message) -> message.getInstanceLocation().toString())
                            .thenComparing(ValidationMessage::getMessage))
                    .limit(5)
                    .toList();
            for (ValidationMessage message : reported) {
                String path = "manifest" + message.getInstanceLocation().toString().replaceFirst("^\\$", "");
                if (("required".equals(message.getType()) || "additionalProperties".equals(message.getType()))
                        && message.getProperty() != null) {
                    path += "." + message.getProperty();
                }
                errors.merge(path, message.getMessage(), (first, next) -> first + "; " + next);
            }
            String details = reported.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
            if (messages.size() > 5) {
                details += "; ... " + (messages.size() - 5) + " more";
            }
            throw new BadRequestException("Template '" + name + "' does not match "
                    + CANONICAL_SCHEMA_PATH + ": " + details, errors);
        }
        validateMultiModeStateLabels(name, manifestNode);
        validateConcreteInitialState(name, manifestNode);
        validateVariableDomains(name, manifestNode);
        validateRepresentableDynamics(name, manifestNode);
        validateRepresentableTransitions(name, manifestNode);
    }

    private void validateUnsupportedApiBehavior(String templateName, JsonNode manifestNode) {
        JsonNode apis = manifestNode.path("APIs");
        if (!apis.isArray() || apis.isEmpty()) {
            return;
        }
        JsonNode modes = manifestNode.path("Modes");
        if (!modes.isArray() || modes.isEmpty()) {
            throw new BadRequestException("Template '" + templateName
                    + "': APIs require at least one Mode because an API command is modeled as a state change. "
                    + "Use a triggered Transition for autonomous variable behavior on a stateless device.");
        }
        int modeCount = modes.size();
        for (JsonNode api : apis) {
            String apiName = api.path("Name").asText("<unnamed>");
            JsonNode assignments = api.path("Assignments");
            if (!assignments.isMissingNode()) {
                throw new BadRequestException("Template '" + templateName + "': API '" + apiName
                        + "' contains the unsupported Assignments field. Express the API effect through "
                        + "EndState, or use a triggered Transition for variable assignments.");
            }
            String endState = api.path("EndState").asText("");
            if (!endState.isEmpty() && endState.replace(";", "").isBlank()) {
                throw new BadRequestException("Template '" + templateName + "': API '" + apiName
                        + "' EndState changes no mode. Every API must have at least one concrete state effect.");
            }
            if (hasNoPossibleApiStateChange(api.path("StartState").asText(""), endState)) {
                throw new BadRequestException("Template '" + templateName + "': API '" + apiName
                        + "' has identical concrete StartState and EndState effects, so calling it cannot change "
                        + "the formal model.");
            }
        }
        validateSignalRouteIsolation(templateName, manifestNode, modeCount);
    }

    private void validateSignalRouteIsolation(String templateName, JsonNode manifestNode, int modeCount) {
        JsonNode apis = manifestNode.path("APIs");
        JsonNode transitions = manifestNode.path("Transitions");
        for (int signalIndex = 0; signalIndex < apis.size(); signalIndex++) {
            JsonNode signalApi = apis.get(signalIndex);
            if (!signalApi.path("Signal").asBoolean(false)) {
                continue;
            }
            String signalName = signalApi.path("Name").asText("<unnamed>");
            for (int otherIndex = 0; otherIndex < apis.size(); otherIndex++) {
                if (signalIndex == otherIndex) continue;
                JsonNode otherApi = apis.get(otherIndex);
                if (signalMayObserveChange(signalApi, otherApi, modeCount)) {
                    throw new BadRequestException("Template '" + templateName + "': observable API '"
                            + signalName + "' overlaps API '" + otherApi.path("Name").asText("<unnamed>")
                            + "'. Their modeled state changes cannot be distinguished as automation events; "
                            + "give the observable API a unique route or set Signal=false.");
                }
            }
            if (!transitions.isArray()) continue;
            for (JsonNode transition : transitions) {
                if (signalMayObserveChange(signalApi, transition, modeCount)) {
                    throw new BadRequestException("Template '" + templateName + "': observable API '"
                            + signalName + "' overlaps autonomous Transition '"
                            + transition.path("Name").asText("<unnamed>")
                            + "'. The model would expose that transition as the API event; use a unique "
                            + "state-change route or set Signal=false.");
                }
            }
        }
    }

    /** Whether the second state change can satisfy the first API's one-step event predicate. */
    private boolean signalMayObserveChange(JsonNode signalApi, JsonNode stateChange, int modeCount) {
        List<String> signalStarts = stateParts(signalApi.path("StartState").asText(""), modeCount);
        List<String> signalEnds = stateParts(signalApi.path("EndState").asText(""), modeCount);
        List<String> changeStarts = stateParts(stateChange.path("StartState").asText(""), modeCount);
        List<String> changeEnds = stateParts(stateChange.path("EndState").asText(""), modeCount);
        boolean observedChangePossible = false;

        for (int i = 0; i < modeCount; i++) {
            String signalStart = signalStarts.get(i);
            String changeStart = changeStarts.get(i);
            if (!signalStart.isEmpty() && !changeStart.isEmpty() && !signalStart.equals(changeStart)) {
                return false;
            }

            String signalEnd = signalEnds.get(i);
            if (signalEnd.isEmpty()) continue;
            String changeEnd = changeEnds.get(i);
            if (!changeEnd.isEmpty()) {
                if (!signalEnd.equals(changeEnd)) return false;
                String fixedStart = !signalStart.isEmpty() ? signalStart : changeStart;
                if (fixedStart.isEmpty() || !fixedStart.equals(signalEnd)) {
                    observedChangePossible = true;
                }
            } else {
                String fixedStart = !signalStart.isEmpty() ? signalStart : changeStart;
                if (!fixedStart.isEmpty() && !fixedStart.equals(signalEnd)) {
                    return false;
                }
            }
        }
        return observedChangePossible;
    }

    private List<String> stateParts(String state, int modeCount) {
        String[] parts = state.split(";", -1);
        List<String> normalized = new ArrayList<>(modeCount);
        for (int i = 0; i < modeCount; i++) {
            String part = i < parts.length ? normalizeStateComponent(parts[i]) : "";
            normalized.add("_".equals(part) ? "" : part);
        }
        return normalized;
    }

    private void validateVariableDomains(String templateName, JsonNode manifestNode) {
        validateDomainDeclarations(templateName, "InternalVariable", manifestNode.path("InternalVariables"));
    }

    private void validateDomainDeclarations(String templateName, String kind, JsonNode declarations) {
        if (!declarations.isArray()) {
            return;
        }
        for (JsonNode declaration : declarations) {
            String name = declaration.path("Name").asText("<unnamed>");
            JsonNode lower = declaration.get("LowerBound");
            JsonNode upper = declaration.get("UpperBound");
            if (lower != null && upper != null && lower.isIntegralNumber() && upper.isIntegralNumber()
                    && lower.asInt() > upper.asInt()) {
                throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                        + name + "' has LowerBound " + lower.asInt() + " greater than UpperBound "
                        + upper.asInt() + ".");
            }
            JsonNode values = declaration.path("Values");
            if (values.isArray()) {
                Set<String> normalized = new java.util.LinkedHashSet<>();
                for (JsonNode value : values) {
                    String raw = value.asText();
                    String modelValue = normalizeModelLiteral(raw);
                    if (modelValue.isEmpty()) {
                        throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                                + name + "' contains an empty enum value after model normalization.");
                    }
                    if (!normalized.add(modelValue)) {
                        throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                                + name + "' contains duplicate enum value '" + raw
                                + "' after spaces are removed for the formal model.");
                    }
                }
            }
            String naturalRate = declaration.path("NaturalChangeRate").asText(null);
            boolean numeric = lower != null && upper != null
                    && lower.isIntegralNumber() && upper.isIntegralNumber();
            boolean sharedEnvironment = "EnvironmentDomain".equals(kind)
                    || ("InternalVariable".equals(kind)
                    && declaration.has("IsInside")
                    && !declaration.path("IsInside").asBoolean());
            if (numeric && sharedEnvironment && naturalRate == null) {
                throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                        + name + "' is a shared numeric environment variable and must explicitly define "
                        + "NaturalChangeRate ('[-1, 1]' for the MEDIC baseline disturbance, or '0' "
                        + "for no natural change).");
            }
            if (naturalRate != null && !naturalRate.isBlank() && !numeric) {
                throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                        + name + "' declares NaturalChangeRate, but only numeric ranges can change by a rate.");
            }
            validateNaturalChangeRate(templateName, kind, name, naturalRate);
        }
    }

    private void validateNaturalChangeRate(String templateName,
                                           String kind,
                                           String name,
                                           String rawRate) {
        if (rawRate == null) {
            return;
        }
        try {
            NaturalChangeRateParser.RateRange range = NaturalChangeRateParser.parse(rawRate);
            // The interval is modeled exhaustively, so reject an unmodelably wide span at authoring
            // time rather than letting generation silently narrow it.
            if (range.span() > RequestLimits.MAX_NATURAL_CHANGE_RATE_SPAN) {
                throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                        + name + "' declares NaturalChangeRate '" + rawRate + "', whose span exceeds"
                        + " the modelable maximum of "
                        + RequestLimits.MAX_NATURAL_CHANGE_RATE_SPAN
                        + "; every value in the interval is modeled as reachable in one step.");
            }
        } catch (NaturalChangeRateParser.ParseException exception) {
            if (exception.isDescending()) {
                throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                        + name + "' has descending NaturalChangeRate '" + rawRate
                        + "'; the lower rate must come first.");
            }
            throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                    + name + "' has NaturalChangeRate outside the supported integer format: '"
                    + rawRate + "'.");
        }
    }

    private void validateRepresentableDynamics(String templateName, JsonNode manifestNode) {
        JsonNode workingStates = manifestNode.path("WorkingStates");
        if (!workingStates.isArray()) {
            return;
        }
        Map<String, JsonNode> localDomains = new LinkedHashMap<>();
        JsonNode internalVariables = manifestNode.path("InternalVariables");
        if (internalVariables.isArray()) {
            for (JsonNode variable : internalVariables) {
                if (variable.path("IsInside").asBoolean(false)) {
                    localDomains.putIfAbsent(variable.path("Name").asText(), variable);
                }
            }
        }
        Map<String, JsonNode> impactedDomains = new LinkedHashMap<>();
        Set<String> impactedNames = new java.util.LinkedHashSet<>();
        JsonNode impactedVariables = manifestNode.path("ImpactedVariables");
        if (impactedVariables.isArray()) {
            impactedVariables.forEach(value -> impactedNames.add(value.asText()));
        }
        collectNamedDomains(internalVariables, impactedDomains);

        for (JsonNode state : workingStates) {
            String stateName = state.path("Name").asText("<unnamed>");
            JsonNode dynamics = state.path("Dynamics");
            if (!dynamics.isArray()) {
                continue;
            }
            Set<String> seenTargets = new java.util.LinkedHashSet<>();
            for (JsonNode dynamic : dynamics) {
                String variableName = dynamic.path("VariableName").asText();
                if (!seenTargets.add(variableName)) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + stateName + "' defines Dynamics for '" + variableName
                            + "' more than once; one model state needs one unambiguous effect per variable.");
                }
                JsonNode domain = localDomains.get(variableName);
                boolean impacted = impactedNames.contains(variableName);
                if (domain == null && impacted) {
                    domain = impactedDomains.get(variableName);
                }
                if (domain == null) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + stateName + "' has Dynamics for unknown or non-writable variable '"
                            + variableName + "'. Use a local InternalVariable or a name in ImpactedVariables.");
                }
                boolean numeric = domain.path("LowerBound").isIntegralNumber()
                        && domain.path("UpperBound").isIntegralNumber();
                boolean hasChangeRate = dynamic.hasNonNull("ChangeRate");
                boolean hasValue = dynamic.hasNonNull("Value");
                if (numeric && !hasChangeRate) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + stateName + "' must use ChangeRate for numeric Dynamics target '"
                            + variableName + "'; direct Value is for enum/boolean targets.");
                }
                if (!numeric && !hasValue) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + stateName + "' must use Value for enum/boolean Dynamics target '"
                            + variableName + "'; ChangeRate has no discrete-domain meaning.");
                }
                if (hasValue) {
                    validateDynamicValue(templateName, stateName, variableName,
                            dynamic.path("Value").asText(), domain);
                }
            }
        }
    }

    private void validateDynamicValue(String templateName,
                                      String stateName,
                                      String variableName,
                                      String rawValue,
                                      JsonNode domain) {
        String value = normalizeModelLiteral(rawValue);
        JsonNode values = domain.path("Values");
        if (values.isArray() && !values.isEmpty()) {
            for (JsonNode allowed : values) {
                if (value.equals(normalizeModelLiteral(allowed.asText()))) {
                    return;
                }
            }
            throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                    + stateName + "' sets Dynamics target '" + variableName + "' to '" + rawValue
                    + "', outside its enum domain " + values + ".");
        }
        if (!"TRUE".equalsIgnoreCase(value) && !"FALSE".equalsIgnoreCase(value)) {
            throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                    + stateName + "' sets boolean Dynamics target '" + variableName + "' to '"
                    + rawValue + "'; use TRUE or FALSE.");
        }
    }

    private boolean hasNoPossibleApiStateChange(String startState, String endState) {
        String[] starts = startState.split(";", -1);
        String[] ends = endState.split(";", -1);
        boolean hasEffect = false;
        for (int i = 0; i < ends.length; i++) {
            String end = normalizeStateComponent(ends[i]);
            if (end.isEmpty()) {
                continue;
            }
            hasEffect = true;
            String start = i < starts.length ? normalizeStateComponent(starts[i]) : "";
            if (start.isEmpty() || "_".equals(start) || !start.equals(end)) {
                return false;
            }
        }
        return hasEffect;
    }

    /** Reject accepted-looking transition data that the generator cannot execute. */
    private void validateRepresentableTransitions(String templateName, JsonNode manifestNode) {
        JsonNode transitions = manifestNode.path("Transitions");
        if (!transitions.isArray()) {
            return;
        }
        JsonNode modes = manifestNode.path("Modes");
        boolean hasStateMachine = modes.isArray() && !modes.isEmpty();
        Map<String, JsonNode> assignmentDomains = collectAssignmentDomains(manifestNode);
        Map<String, TriggerDomain> triggerDomains = collectTriggerDomains(manifestNode);
        for (JsonNode transition : transitions) {
            String transitionName = transition.path("Name").asText("<unnamed>");
            JsonNode trigger = transition.get("Trigger");
            boolean hasTrigger = trigger != null && trigger.isObject();
            JsonNode assignments = transition.path("Assignments");
            boolean hasAssignments = assignments.isArray() && !assignments.isEmpty();
            int assignmentCount = hasAssignments ? assignments.size() : 0;
            JsonNode startState = transition.get("StartState");
            boolean hasStartState = startState != null && startState.isTextual()
                    && !startState.asText().isBlank();
            JsonNode endState = transition.get("EndState");
            boolean hasEndState = endState != null && endState.isTextual()
                    && !endState.asText().isBlank();
            int stateEffectCount = hasEndState ? countConcreteStateEffects(endState.asText()) : 0;
            if (!hasStateMachine && (hasStartState || hasEndState)) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' declares a state endpoint, but this template has no "
                        + "Modes. Stateless transitions may only use a Trigger and one variable Assignment.");
            }
            if (hasAssignments && !hasTrigger) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' assigns variables but has no Trigger. The formal model would "
                        + "never execute those assignments; add a Trigger or remove the assignments.");
            }
            if (!hasTrigger) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' has no Trigger, so its autonomous effect has no modeled cause.");
            }
            if (hasTrigger && !hasEndState && !hasAssignments) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' has a Trigger but changes neither state nor a variable.");
            }
            if (hasEndState && stateEffectCount == 0) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' EndState changes no mode.");
            }
            if (hasEndState && hasNoPossibleApiStateChange(
                    transition.path("StartState").asText(""), endState.asText(""))) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' has identical concrete StartState and EndState effects, so it "
                        + "cannot change the formal model.");
            }
            if (stateEffectCount + assignmentCount > 1) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' declares " + (stateEffectCount + assignmentCount)
                        + " state/variable effects. The current formal model cannot preserve those effects as "
                        + "one atomic action; use separate single-effect transitions with explicit triggers.");
            }
            validateTransitionTrigger(templateName, transitionName, trigger, triggerDomains);
            if (hasAssignments) {
                for (JsonNode assignment : assignments) {
                    validateAssignment(templateName, transitionName, assignment, assignmentDomains);
                }
            }
        }
    }

    private int countConcreteStateEffects(String endState) {
        int count = 0;
        for (String component : endState.split(";", -1)) {
            String normalized = normalizeStateComponent(component);
            if (!normalized.isEmpty() && !"_".equals(normalized)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, TriggerDomain> collectTriggerDomains(JsonNode manifestNode) {
        Map<String, TriggerDomain> domains = new LinkedHashMap<>();
        JsonNode modes = manifestNode.path("Modes");
        JsonNode workingStates = manifestNode.path("WorkingStates");
        if (modes.isArray() && !modes.isEmpty() && workingStates.isArray()) {
            for (int modeIndex = 0; modeIndex < modes.size(); modeIndex++) {
                Set<String> values = new java.util.LinkedHashSet<>();
                for (JsonNode state : workingStates) {
                    String[] components = state.path("Name").asText("").split(";", -1);
                    if (modeIndex < components.length) {
                        String value = normalizeModelLiteral(components[modeIndex]);
                        if (!value.isEmpty()) {
                            values.add(value);
                        }
                    }
                }
                domains.putIfAbsent(modes.get(modeIndex).asText(), TriggerDomain.enumerated("mode", values));
            }
        }
        JsonNode variables = manifestNode.path("InternalVariables");
        if (variables.isArray()) {
            for (JsonNode variable : variables) {
                String name = variable.path("Name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                JsonNode values = variable.path("Values");
                if (values.isArray() && !values.isEmpty()) {
                    Set<String> allowed = new java.util.LinkedHashSet<>();
                    values.forEach(value -> allowed.add(normalizeModelLiteral(value.asText())));
                    domains.putIfAbsent(name, TriggerDomain.enumerated("enum variable", allowed));
                } else if (variable.path("LowerBound").isIntegralNumber()
                        && variable.path("UpperBound").isIntegralNumber()) {
                    domains.putIfAbsent(name, TriggerDomain.numeric(
                            variable.path("LowerBound").asInt(), variable.path("UpperBound").asInt()));
                } else {
                    domains.putIfAbsent(name, TriggerDomain.enumerated(
                            "boolean variable", Set.of("TRUE", "FALSE")));
                }
            }
        }
        return domains;
    }

    private void validateTransitionTrigger(String templateName,
                                           String transitionName,
                                           JsonNode trigger,
                                           Map<String, TriggerDomain> domains) {
        String attribute = trigger.path("Attribute").asText();
        String relation = normalizeTriggerRelation(trigger.path("Relation").asText());
        String rawValue = trigger.path("Value").asText();
        TriggerDomain domain = domains.get(attribute);
        if (domain == null) {
            throw new BadRequestException("Template '" + templateName + "': Transition '"
                    + transitionName + "' reads unknown trigger attribute '" + attribute
                    + "'. Triggers may read a Mode or any declared InternalVariable.");
        }
        if (domain.numeric()) {
            final int value;
            try {
                value = Integer.parseInt(rawValue.trim());
            } catch (NumberFormatException exception) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' compares numeric trigger '" + attribute
                        + "' with non-integer value '" + rawValue + "'.");
            }
            if (value < domain.lowerBound() || value > domain.upperBound()) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' compares '" + attribute + "' with " + value
                        + ", outside its range " + domain.lowerBound() + ".." + domain.upperBound() + ".");
            }
            return;
        }
        if (!"=".equals(relation) && !"!=".equals(relation)) {
            throw new BadRequestException("Template '" + templateName + "': Transition '"
                    + transitionName + "' uses ordering relation '" + trigger.path("Relation").asText()
                    + "' on " + domain.kind() + " '" + attribute + "'. Use = or !=.");
        }
        String value = normalizeModelLiteral(rawValue);
        if ("boolean variable".equals(domain.kind())) {
            value = value.toUpperCase(Locale.ROOT);
        }
        if (!domain.values().contains(value)) {
            throw new BadRequestException("Template '" + templateName + "': Transition '"
                    + transitionName + "' compares '" + attribute + "' with unknown value '"
                    + rawValue + "'. Allowed values: " + domain.values() + ".");
        }
    }

    private String normalizeTriggerRelation(String relation) {
        return switch (relation == null ? "" : relation.trim().toUpperCase(Locale.ROOT)) {
            case "=", "==", "EQ" -> "=";
            case "!=", "NEQ" -> "!=";
            case ">", "GT" -> ">";
            case ">=", "GTE" -> ">=";
            case "<", "LT" -> "<";
            case "<=", "LTE" -> "<=";
            default -> relation;
        };
    }

    private String normalizeModelLiteral(String value) {
        return value == null ? "" : value.trim().replace(" ", "");
    }

    private record TriggerDomain(String kind,
                                 Set<String> values,
                                 Integer lowerBound,
                                 Integer upperBound) {
        private static TriggerDomain enumerated(String kind, Set<String> values) {
            return new TriggerDomain(kind, values, null, null);
        }

        private static TriggerDomain numeric(int lowerBound, int upperBound) {
            return new TriggerDomain("numeric variable", Set.of(), lowerBound, upperBound);
        }

        private boolean numeric() {
            return lowerBound != null && upperBound != null;
        }
    }

    private Map<String, JsonNode> collectAssignmentDomains(JsonNode manifestNode) {
        Map<String, JsonNode> domains = new LinkedHashMap<>();
        collectNamedDomains(manifestNode.path("InternalVariables"), domains);
        return domains;
    }

    private void collectNamedDomains(JsonNode declarations, Map<String, JsonNode> domains) {
        if (!declarations.isArray()) {
            return;
        }
        for (JsonNode declaration : declarations) {
            String name = declaration.path("Name").asText(null);
            if (name != null && !name.isBlank()) {
                domains.putIfAbsent(name, declaration);
            }
        }
    }

    private void validateAssignment(String templateName,
                                    String transitionName,
                                    JsonNode assignment,
                                    Map<String, JsonNode> domains) {
        String attribute = assignment.path("Attribute").asText();
        String value = assignment.path("Value").asText();
        JsonNode domain = domains.get(attribute);
        if (domain == null) {
            throw new BadRequestException("Template '" + templateName + "': Transition '"
                    + transitionName + "' assigns unknown variable '" + attribute
                    + "'. Every assignment target must be declared in InternalVariables.");
        }

        JsonNode values = domain.path("Values");
        if (values.isArray() && !values.isEmpty()) {
            String normalized = normalizeAssignmentLiteral(value);
            for (JsonNode allowed : values) {
                if (normalized.equals(normalizeAssignmentLiteral(allowed.asText()))) {
                    return;
                }
            }
            throw new BadRequestException("Template '" + templateName + "': Transition '"
                    + transitionName + "' assigns '" + value + "' to '" + attribute
                    + "', outside its enum domain " + values + ".");
        }

        JsonNode lower = domain.get("LowerBound");
        JsonNode upper = domain.get("UpperBound");
        if (lower != null && upper != null && lower.isIntegralNumber() && upper.isIntegralNumber()) {
            final int numericValue;
            try {
                numericValue = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' assigns non-integer value '" + value
                        + "' to numeric variable '" + attribute + "'.");
            }
            if (numericValue < lower.asInt() || numericValue > upper.asInt()) {
                throw new BadRequestException("Template '" + templateName + "': Transition '"
                        + transitionName + "' assigns " + numericValue + " to '" + attribute
                        + "', outside its range " + lower.asInt() + ".." + upper.asInt() + ".");
            }
            return;
        }

        if (!"TRUE".equalsIgnoreCase(value.trim()) && !"FALSE".equalsIgnoreCase(value.trim())) {
            throw new BadRequestException("Template '" + templateName + "': Transition '"
                    + transitionName + "' assigns '" + value + "' to boolean variable '" + attribute
                    + "'. Use TRUE or FALSE.");
        }
    }

    private String normalizeAssignmentLiteral(String value) {
        return value == null ? "" : value.replace(" ", "");
    }

    /**
     * The generated model stores trust/privacy per mode-state component. Reject a
     * compound-state manifest that would require one component to carry two labels,
     * because accepting it would silently make JSON order change model semantics.
     */
    private void validateMultiModeStateLabels(String templateName, JsonNode manifestNode) {
        JsonNode modes = manifestNode.path("Modes");
        JsonNode workingStates = manifestNode.path("WorkingStates");
        if (!modes.isArray() || modes.isEmpty() || !workingStates.isArray()) {
            return;
        }

        Map<String, String> fullStates = new LinkedHashMap<>();
        Map<String, StateLabelDeclaration> components = new LinkedHashMap<>();
        int modeCount = modes.size();
        for (JsonNode workingState : workingStates) {
            String rawState = workingState.path("Name").asText("").trim();
            String[] segments = rawState.split(";", -1);
            if (segments.length != modeCount) {
                throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                        + rawState + "' must contain exactly one semicolon-separated value for each mode ("
                        + modeCount + " values required). WorkingStates are complete configurations; only API or "
                        + "transition endpoints may use partial state tuples.");
            }

            String[] normalizedSegments = new String[modeCount];
            for (int i = 0; i < modeCount; i++) {
                normalizedSegments[i] = normalizeStateComponent(segments[i]);
                if (normalizedSegments[i].isEmpty() || "_".equals(normalizedSegments[i])) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + rawState + "' must define a concrete value for mode '"
                            + modes.get(i).asText() + "'.");
                }
            }

            String fullStateKey = String.join(";", normalizedSegments);
            String previousFullState = fullStates.putIfAbsent(fullStateKey, rawState);
            if (previousFullState != null) {
                throw new BadRequestException("Template '" + templateName
                        + "': duplicate WorkingState after model normalization: '" + previousFullState
                        + "' and '" + rawState + "'.");
            }

            String trust = workingState.path("Trust").asText("trusted").trim().toLowerCase(Locale.ROOT);
            String privacy = workingState.path("Privacy").asText("public").trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < modeCount; i++) {
                String mode = modes.get(i).asText().trim();
                String componentKey = normalizeStateComponent(mode) + "\u0000" + normalizedSegments[i];
                StateLabelDeclaration current = new StateLabelDeclaration(rawState, mode, segments[i].trim(), trust, privacy);
                StateLabelDeclaration previous = components.putIfAbsent(componentKey, current);
                if (previous != null && (!previous.trust().equals(trust) || !previous.privacy().equals(privacy))) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingStates '"
                            + previous.fullState() + "' and '" + rawState + "' assign conflicting Trust/Privacy "
                            + "labels to " + mode + "='" + segments[i].trim() + "'. The model stores one label "
                            + "per mode-state component, so these declarations cannot be represented losslessly.");
                }
            }
        }
    }

    private void validateConcreteInitialState(String templateName, JsonNode manifestNode) {
        JsonNode modes = manifestNode.path("Modes");
        JsonNode workingStates = manifestNode.path("WorkingStates");
        String initialState = manifestNode.path("InitState").asText("").trim();
        boolean hasModes = modes.isArray() && !modes.isEmpty();
        boolean hasWorkingStates = workingStates.isArray() && !workingStates.isEmpty();

        if (!hasModes && initialState.isEmpty() && !hasWorkingStates) {
            return;
        }
        if (!hasModes || initialState.isEmpty() || !hasWorkingStates) {
            throw new BadRequestException("Template '" + templateName
                    + "': a stateful template must define non-empty Modes, a concrete InitState, "
                    + "and non-empty WorkingStates together.");
        }

        String[] initialSegments = initialState.split(";", -1);
        if (initialSegments.length != modes.size()) {
            throw new BadRequestException("Template '" + templateName + "': InitState '"
                    + initialState + "' must contain exactly one value for each mode ("
                    + modes.size() + " values required).");
        }
        for (int i = 0; i < initialSegments.length; i++) {
            String segment = normalizeStateComponent(initialSegments[i]);
            if (segment.isEmpty() || "_".equals(segment)) {
                throw new BadRequestException("Template '" + templateName + "': InitState '"
                        + initialState + "' must define a concrete value for mode '"
                        + modes.get(i).asText() + "'.");
            }
        }
        for (JsonNode workingState : workingStates) {
            if (initialState.equals(workingState.path("Name").asText("").trim())) {
                return;
            }
        }
        throw new BadRequestException("Template '" + templateName + "': InitState '"
                + initialState + "' is not defined in WorkingStates.");
    }

    private String normalizeStateComponent(String value) {
        return value == null ? "" : value.trim().replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private record StateLabelDeclaration(String fullState,
                                         String mode,
                                         String state,
                                         String trust,
                                         String privacy) {
    }

    private JsonSchema getSchema() {
        JsonSchema current = schema;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (schema == null) {
                schema = loadSchema();
            }
            return schema;
        }
    }

    private JsonSchema loadSchema() {
        JsonNode schemaNode = readSchemaNode();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(schemaNode);
    }

    private JsonNode toSchemaNode(DeviceManifest manifest) {
        if (manifest == null) {
            throw new BadRequestException("Template manifest is required.");
        }
        return getNonNullObjectMapper().valueToTree(manifest);
    }

    private ObjectMapper getNonNullObjectMapper() {
        ObjectMapper current = nonNullObjectMapper;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (nonNullObjectMapper == null) {
                nonNullObjectMapper = objectMapper.copy()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
            }
            return nonNullObjectMapper;
        }
    }

    private JsonNode readSchemaNode() {
        List<Resource> candidates = List.of(
                new FileSystemResource(schemaPath),
                new FileSystemResource(CANONICAL_SCHEMA_PATH),
                new ClassPathResource("device-template-schema.json")
        );
        for (Resource resource : candidates) {
            try {
                if (resource.exists() && resource.isReadable()) {
                    try (var input = resource.getInputStream()) {
                        return objectMapper.readTree(input);
                    }
                }
            } catch (IOException e) {
                throw new InternalServerException("Failed to read device template schema from "
                        + resource.getDescription(), e);
            }
        }
        throw new InternalServerException("Device template schema not found. Expected "
                + CANONICAL_SCHEMA_PATH + " or classpath:device-template-schema.json.");
    }
}
