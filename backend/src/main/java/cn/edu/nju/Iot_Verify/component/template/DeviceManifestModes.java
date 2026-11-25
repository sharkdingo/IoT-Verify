package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Reads the mode / working-state structure out of a device manifest.
 *
 * <p>A manifest declares modes plus working states whose names encode one state per mode,
 * separated by {@code ;} (a single-mode template omits the separator). Both template validation
 * and board persistence need that decoding, so it lives here rather than being duplicated or
 * reached for across a service boundary.</p>
 */
public final class DeviceManifestModes {

    private DeviceManifestModes() {
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Declared mode names, trimmed, with blanks dropped. */
    public static List<String> modeNames(DeviceManifest manifest) {
        if (manifest == null || manifest.getModes() == null) {
            return List.of();
        }
        return manifest.getModes().stream()
                .filter(DeviceManifestModes::hasText)
                .map(String::trim)
                .toList();
    }

    /** Mode counts keyed by the authored Board node id, from the same Board snapshot as its rules and specs. */
    public static Map<String, Integer> boardModeCounts(List<DeviceNodeDto> nodes, List<DeviceTemplateDto> templates) {
        Map<String, Integer> countByTemplate = new HashMap<>();
        for (DeviceTemplateDto template : templates) {
            if (template != null && hasText(template.getName()) && template.getManifest() != null) {
                countByTemplate.put(template.getName().trim().toLowerCase(Locale.ROOT),
                        modeNames(template.getManifest()).size());
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        for (DeviceNodeDto node : nodes) {
            if (node != null && hasText(node.getId()) && hasText(node.getTemplateName())) {
                Integer count = countByTemplate.get(node.getTemplateName().trim().toLowerCase(Locale.ROOT));
                if (count != null) counts.put(node.getId().trim(), count);
            }
        }
        return counts;
    }

    /** Mode name to the distinct states that mode can take, in declaration order. */
    public static Map<String, List<String>> modeStates(DeviceManifest manifest) {
        return modeStateValues(manifest, DeviceSmvDataFactory::cleanStateName);
    }

    public static Map<String, List<String>> authoredModeStates(DeviceManifest manifest) {
        return modeStateValues(manifest, String::trim);
    }

    /** For each authored mode, maps the checked NuSMV atom back to its unique declared state name. */
    public static Map<String, Map<String, String>> authoredStatesByMode(
            DeviceManifest manifest, Function<String, ? extends RuntimeException> invalid) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        authoredModeStates(manifest).forEach((mode, states) -> {
            Map<String, String> authoredNames = new LinkedHashMap<>();
            for (String state : states) {
                String token = DeviceSmvDataFactory.cleanStateName(state);
                String previous = authoredNames.putIfAbsent(token, state);
                if (previous != null && !previous.equals(state)) {
                    throw invalid.apply("Working states '" + previous + "' and '" + state
                            + "' of mode '" + mode + "' collide at NuSMV state token '" + token + "'.");
                }
            }
            result.put(mode, Map.copyOf(authoredNames));
        });
        return Map.copyOf(result);
    }

    private static Map<String, List<String>> modeStateValues(
            DeviceManifest manifest, UnaryOperator<String> stateName) {
        List<String> modes = modeNames(manifest);
        if (modes.isEmpty() || manifest == null || manifest.getWorkingStates() == null) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String mode : modes) {
            result.put(mode, new ArrayList<>());
        }

        boolean singleMode = modes.size() == 1;
        for (DeviceManifest.WorkingState state : manifest.getWorkingStates()) {
            if (state == null || !hasText(state.getName())) {
                continue;
            }
            if (singleMode) {
                addUniqueState(result.get(modes.get(0)), stateName.apply(state.getName()));
                continue;
            }
            String[] parts = state.getName().split(";");
            for (int i = 0; i < parts.length && i < modes.size(); i++) {
                addUniqueState(result.get(modes.get(i)), stateName.apply(parts[i]));
            }
        }
        return result;
    }

    private static void addUniqueState(List<String> states, String state) {
        if (states != null && hasText(state) && !states.contains(state)) {
            states.add(state);
        }
    }

    /**
     * The value a device-local enum variable holds in {@code state}, if that state declares one.
     *
     * <p>A WorkingState's {@code Dynamics} entry is not an action but a standing constraint: the generator
     * emits it as a branch of {@code next(<device>.<var>)} guarded by being in that state. So the state a
     * device starts in already determines such a variable's initial value, and defaulting it independently
     * to {@code Values[0]} produced a step-0 model that contradicts itself — {@code init(CarLocation) := away}
     * beside {@code init(location) := garage}, on six bundled templates. This is the one place that
     * derivation lives; every defaulter calls it rather than re-deriving.</p>
     *
     * <p>Enum only, deliberately. A numeric target declares {@code ChangeRate}, a rate that says nothing
     * about the current value (schema {@code oneOf} makes the two exclusive), so there is nothing to derive
     * and a numeric local keeps its lower-bound default. Returns {@code null} when the state declares no
     * {@code Value} for the variable, which means "no opinion", not "empty".</p>
     */
    public static String stateDeclaredValue(DeviceManifest manifest, String stateName, String variableName) {
        if (manifest == null || !hasText(stateName) || !hasText(variableName)
                || manifest.getWorkingStates() == null) {
            return null;
        }
        // A Transition assignment on the same variable means the state does not determine it: the generator
        // emits transition branches ahead of the state branches in one `case`, and first match wins, so a
        // firing transition overrides the state's Dynamics. Treating such a variable as state-determined
        // would default it wrongly and make the writer gates refuse a pair the transition legitimately
        // produces. No bundled template does this — Clock's only assignment targets a shared variable — so
        // this guards custom templates.
        if (isDrivenByTransition(manifest, variableName)) {
            return null;
        }
        List<String> wanted = authoredTuple(stateName);
        for (DeviceManifest.WorkingState state : manifest.getWorkingStates()) {
            if (state == null || state.getName() == null || state.getDynamics() == null) {
                continue;
            }
            // Match the complete tuple: a multi-mode InitState like `heating;ready` names one WorkingState,
            // and matching a single segment would pick an arbitrary sibling.
            if (!wanted.equals(authoredTuple(state.getName()))) {
                continue;
            }
            for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                if (dynamic == null || !variableName.equals(dynamic.getVariableName())) {
                    continue;
                }
                return hasText(dynamic.getValue()) ? dynamic.getValue().trim() : null;
            }
        }
        return null;
    }

    private static List<String> authoredTuple(String state) {
        return Arrays.stream(state.split(";", -1)).map(String::trim).toList();
    }

    /** Validate authored endpoint operands before either engine maps them to solver atoms. */
    public static String endpointIssue(String state, List<String> modes,
                                       Map<String, List<String>> authoredStates, boolean startState) {
        if (!hasText(state) || (startState && !modes.isEmpty() && "_".equals(state.trim()))) {
            return null;
        }
        List<String> parts = authoredTuple(state);
        if (parts.size() != modes.size()) {
            return parts.size() + " segments, expected " + modes.size() + " for modes " + modes;
        }
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            if (part.isEmpty() || (startState && "_".equals(part))) continue;
            String mode = modes.get(i);
            if (!authoredStates.getOrDefault(mode, List.of()).contains(part)) {
                return "state '" + part + "' is not declared for mode '" + mode + "'";
            }
        }
        return null;
    }

    /** Whether a Transition assignment targets this variable, making it more than the state's consequence. */
    private static boolean isDrivenByTransition(DeviceManifest manifest, String variableName) {
        if (manifest.getTransitions() == null) {
            return false;
        }
        for (DeviceManifest.Transition transition : manifest.getTransitions()) {
            if (transition == null || transition.getAssignments() == null) {
                continue;
            }
            for (DeviceManifest.Assignment assignment : transition.getAssignments()) {
                if (assignment != null && variableName.equals(assignment.getAttribute())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String localInitialValue(DeviceManifest manifest,
                                          DeviceManifest.InternalVariable variable,
                                          String stateName) {
        return localInitialValue(manifest, variable, stateName, null);
    }

    /** The starting state owns a derived value; otherwise an independent instance value can override the default. */
    public static String localInitialValue(DeviceManifest manifest,
                                          DeviceManifest.InternalVariable variable,
                                          String stateName,
                                          String instanceValue) {
        if (variable == null || variable.getName() == null) {
            return null;
        }
        String initialState = hasText(stateName) || manifest == null ? stateName : manifest.getInitState();
        String declared = stateDeclaredValue(manifest, initialState, variable.getName());
        if (declared != null) {
            return declared;
        }
        if (instanceValue != null) {
            return instanceValue;
        }
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            return variable.getValues().get(0);
        }
        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            return String.valueOf(variable.getLowerBound());
        }
        return null;
    }
}
