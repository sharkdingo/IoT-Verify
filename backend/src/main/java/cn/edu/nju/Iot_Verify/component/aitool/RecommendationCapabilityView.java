package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Builds the complete behavior-relevant template view supplied to recommendation models. */
public final class RecommendationCapabilityView {

    private RecommendationCapabilityView() {
    }

    public static Map<String, Object> fromManifest(DeviceTemplateDto.DeviceManifest manifest) {
        if (manifest == null) return Collections.emptyMap();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manifestName", manifest.getName());
        putDescription(result, manifest.getDescription(), manifestDescription(manifest));
        result.put("modes", safeList(manifest.getModes()));
        result.put("modeValues", modeValues(manifest));
        result.put("initState", manifest.getInitState());
        result.put("workingStates", safeList(manifest.getWorkingStates()).stream()
                .filter(Objects::nonNull)
                .map(RecommendationCapabilityView::workingStateView)
                .toList());
        result.put("variables", safeList(manifest.getInternalVariables()).stream()
                .filter(Objects::nonNull)
                .map(RecommendationCapabilityView::variableView)
                .toList());
        result.put("impactedVariables", safeList(manifest.getImpactedVariables()));
        result.put("transitions", safeList(manifest.getTransitions()).stream()
                .filter(Objects::nonNull)
                .map(RecommendationCapabilityView::transitionView)
                .toList());
        result.put("apis", safeList(manifest.getApis()).stream()
                .filter(Objects::nonNull)
                .map(RecommendationCapabilityView::apiView)
                .toList());
        result.put("contents", safeList(manifest.getContents()).stream()
                .filter(Objects::nonNull)
                .map(RecommendationCapabilityView::contentView)
                .toList());
        return result;
    }

    public static Map<String, DeviceTemplateDto> indexTemplates(List<DeviceTemplateDto> templates) {
        Map<String, DeviceTemplateDto> result = new LinkedHashMap<>();
        for (DeviceTemplateDto template : safeList(templates)) {
            if (template == null) continue;
            putAlias(result, template.getName(), template);
            if (template.getManifest() != null) {
                putAlias(result, template.getManifest().getName(), template);
            }
        }
        return result;
    }

    public static DeviceTemplateDto resolveTemplate(Map<String, DeviceTemplateDto> templatesByName,
                                                    String templateName) {
        if (templatesByName == null || templateName == null || templateName.isBlank()) return null;
        return templatesByName.get(templateName.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> workingStateView(DeviceTemplateDto.DeviceManifest.WorkingState state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", state.getName());
        putDescription(result, state.getDescription(), workingStateDescription(state));
        result.put("trust", state.getTrust());
        result.put("privacy", state.getPrivacy());
        result.put("dynamics", safeList(state.getDynamics()).stream()
                .filter(Objects::nonNull)
                .map(dynamic -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("variableName", dynamic.getVariableName());
                    item.put("value", dynamic.getValue());
                    item.put("changeRate", dynamic.getChangeRate());
                    return item;
                })
                .toList());
        return result;
    }

    private static Map<String, Object> variableView(DeviceTemplateDto.DeviceManifest.InternalVariable variable) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", variable.getName());
        putDescription(result, variable.getDescription(), variableDescription(variable));
        // Normalized, not passed through raw: `IsInside` is a tri-state Boolean and an omitted declaration
        // means *shared* everywhere in the product (every gate uses `!Boolean.TRUE.equals(getIsInside())`).
        // Emitting null here left a shared variable matching neither branch of the rule the prompt states
        // about this field, with no instruction covering it.
        result.put("deviceLocal", Boolean.TRUE.equals(variable.getIsInside()));
        // Whether this device observes the shared value: the generator emits no `device.name := a_name`
        // mirror for an affect-only declaration, so neither reported nor environment value conditions
        // can reference it; its declared trust/privacy labels remain available. Declared only for a
        // shared variable (`isValidReadCapability`), so a device-local one reports null and the
        // deviceLocal flag above already answers the question.
        result.put("reads", variable.getReads());
        result.put("falsifiableWhenCompromised", variable.getFalsifiableWhenCompromised());
        result.put("trust", variable.getTrust());
        result.put("privacy", variable.getPrivacy());
        result.put("values", variable.getValues());
        result.put("lowerBound", variable.getLowerBound());
        result.put("upperBound", variable.getUpperBound());
        result.put("naturalChangeRate", variable.getNaturalChangeRate());
        return result;
    }

    private static Map<String, Object> transitionView(DeviceTemplateDto.DeviceManifest.Transition transition) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", transition.getName());
        putDescription(result, transition.getDescription(), transitionDescription(transition));
        result.put("startState", transition.getStartState());
        result.put("endState", transition.getEndState());
        result.put("trigger", triggerView(transition.getTrigger()));
        result.put("assignments", safeList(transition.getAssignments()).stream()
                .filter(Objects::nonNull)
                .map(assignment -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("attribute", assignment.getAttribute());
                    item.put("value", assignment.getValue());
                    return item;
                })
                .toList());
        return result;
    }

    private static Map<String, Object> triggerView(DeviceTemplateDto.DeviceManifest.Trigger trigger) {
        if (trigger == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attribute", trigger.getAttribute());
        result.put("relation", trigger.getRelation());
        result.put("value", trigger.getValue());
        return result;
    }

    private static Map<String, Object> apiView(DeviceTemplateDto.DeviceManifest.API api) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", api.getName());
        putDescription(result, api.getDescription(), apiDescription(api));
        result.put("signal", api.getSignal());
        result.put("acceptsContent", api.getAcceptsContent());
        result.put("startState", api.getStartState());
        result.put("endState", api.getEndState());
        result.put("trigger", triggerView(api.getTrigger()));
        return result;
    }

    private static Map<String, Object> contentView(DeviceTemplateDto.DeviceManifest.Content content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", content.getName());
        putDescription(result, content.getDescription(), contentDescription(content));
        result.put("privacy", content.getPrivacy());
        return result;
    }

    private static Map<String, List<String>> modeValues(DeviceTemplateDto.DeviceManifest manifest) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<String> modes = safeList(manifest.getModes()).stream()
                .filter(RecommendationCapabilityView::hasText)
                .map(String::trim)
                .toList();
        for (String mode : modes) {
            result.put(mode, new ArrayList<>());
        }
        for (DeviceTemplateDto.DeviceManifest.WorkingState state : safeList(manifest.getWorkingStates())) {
            if (state == null || !hasText(state.getName()) || modes.isEmpty()) continue;
            String[] values = modes.size() == 1
                    ? new String[]{state.getName().trim()}
                    : state.getName().split(";", -1);
            if (values.length != modes.size()) continue;
            for (int i = 0; i < modes.size(); i++) {
                String value = values[i].trim();
                if (!value.isEmpty() && !"_".equals(value) && !result.get(modes.get(i)).contains(value)) {
                    result.get(modes.get(i)).add(value);
                }
            }
        }
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        result.forEach((mode, values) -> immutable.put(mode, List.copyOf(values)));
        return immutable;
    }

    private static void putDescription(Map<String, Object> target,
                                       String declared,
                                       String derived) {
        boolean hasDeclaredDescription = hasText(declared);
        target.put("description", hasDeclaredDescription ? declared.trim() : derived);
        target.put("descriptionSource", hasDeclaredDescription ? "declared" : "derived");
    }

    private static String manifestDescription(DeviceTemplateDto.DeviceManifest manifest) {
        List<String> facts = new ArrayList<>();
        List<String> modes = safeList(manifest.getModes());
        facts.add(modes.isEmpty()
                ? "Stateless device template"
                : "Stateful device template with modes " + String.join(", ", modes));
        facts.add(safeList(manifest.getInternalVariables()).size() + " declared variables");
        facts.add(safeList(manifest.getApis()).size() + " APIs");
        if (!safeList(manifest.getImpactedVariables()).isEmpty()) {
            facts.add("impacts " + String.join(", ", manifest.getImpactedVariables()));
        }
        return String.join("; ", facts) + ".";
    }

    private static String workingStateDescription(DeviceTemplateDto.DeviceManifest.WorkingState state) {
        List<String> effects = new ArrayList<>();
        for (DeviceTemplateDto.DeviceManifest.Dynamic dynamic : safeList(state.getDynamics())) {
            if (dynamic == null || !hasText(dynamic.getVariableName())) continue;
            if (hasText(dynamic.getChangeRate())) {
                effects.add(dynamic.getVariableName() + " changes at rate " + dynamic.getChangeRate());
            } else if (hasText(dynamic.getValue())) {
                effects.add(dynamic.getVariableName() + " is set to " + dynamic.getValue());
            }
        }
        return effects.isEmpty()
                ? "Working state " + state.getName() + " with no declared variable dynamics."
                : "Working state " + state.getName() + ": " + String.join("; ", effects) + ".";
    }

    private static String variableDescription(DeviceTemplateDto.DeviceManifest.InternalVariable variable) {
        String scope = Boolean.TRUE.equals(variable.getIsInside()) ? "Device-local" : "Shared environment";
        String domain = domainDescription(variable.getValues(), variable.getLowerBound(), variable.getUpperBound());
        String compromise = Boolean.TRUE.equals(variable.getFalsifiableWhenCompromised())
                ? "compromised reports may be replaced within the domain"
                : "compromise does not falsify this reported value";
        String naturalChange = hasText(variable.getNaturalChangeRate())
                ? "; natural change rate " + variable.getNaturalChangeRate() : "";
        return scope + " variable " + variable.getName() + "; " + domain + "; " + compromise
                + naturalChange + ".";
    }

    private static String transitionDescription(DeviceTemplateDto.DeviceManifest.Transition transition) {
        List<String> facts = new ArrayList<>();
        if (hasText(transition.getStartState()) || hasText(transition.getEndState())) {
            facts.add(valueOrAny(transition.getStartState()) + " -> " + valueOrAny(transition.getEndState()));
        }
        if (transition.getTrigger() != null && hasText(transition.getTrigger().getAttribute())) {
            facts.add("triggered by " + transition.getTrigger().getAttribute() + " "
                    + valueOrEmpty(transition.getTrigger().getRelation()) + " "
                    + valueOrEmpty(transition.getTrigger().getValue()));
        }
        if (!safeList(transition.getAssignments()).isEmpty()) {
            facts.add(safeList(transition.getAssignments()).size() + " variable assignments");
        }
        return "Transition " + valueOrAny(transition.getName())
                + (facts.isEmpty() ? "." : ": " + String.join("; ", facts) + ".");
    }

    private static String apiDescription(DeviceTemplateDto.DeviceManifest.API api) {
        List<String> facts = new ArrayList<>();
        facts.add(Boolean.TRUE.equals(api.getSignal()) ? "observable signal API" : "command API");
        facts.add("start state " + valueOrAny(api.getStartState()));
        if (hasText(api.getEndState())) facts.add("end state " + api.getEndState());
        if (Boolean.TRUE.equals(api.getAcceptsContent())) facts.add("accepts sensitive-content input");
        return "API " + api.getName() + ": " + String.join("; ", facts) + ".";
    }

    private static String contentDescription(DeviceTemplateDto.DeviceManifest.Content content) {
        return "Declared content item " + content.getName() + " with "
                + valueOrAny(content.getPrivacy()) + " sensitivity.";
    }

    private static String domainDescription(List<String> values, Integer lower, Integer upper) {
        if (values != null && !values.isEmpty()) {
            return "enum domain [" + String.join(", ", values) + "]";
        }
        if (lower != null && upper != null) {
            return "integer range " + lower + ".." + upper;
        }
        return "domain not declared";
    }

    private static String valueOrAny(String value) {
        return hasText(value) ? value.trim() : "any";
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void putAlias(Map<String, DeviceTemplateDto> target,
                                 String rawName,
                                 DeviceTemplateDto template) {
        if (rawName == null || rawName.isBlank()) return;
        target.putIfAbsent(rawName.trim().toLowerCase(Locale.ROOT), template);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
