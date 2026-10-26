package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** User-semantic projections for chat tools; persistence/model ids stay out of ordinary output. */
public final class ModelTraceToolPresenter {

    public static final int DEFAULT_STATE_LIMIT = 10;
    public static final int MAX_STATE_LIMIT = 10;
    public static final int MAX_STATE_OFFSET = Integer.MAX_VALUE;

    private ModelTraceToolPresenter() {
    }

    public static Map<String, Object> violatedSpecification(TraceDto trace) {
        SpecificationDto specification = trace != null ? trace.getViolatedSpec() : null;
        Map<String, Object> result = violatedSpecification(specification);
        String checkedExpression = trace != null ? trace.getCheckedExpression() : null;
        putText(result, "formulaKind", formulaKind(checkedExpression,
                specification != null ? specification.getTemplateId() : null));
        putText(result, "checkedExpression", checkedExpression);
        return result;
    }

    public static Map<String, Object> violatedSpecification(SpecificationDto specification) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (specification != null) {
            // `get_trace` tells the model that a cycle is the violation itself "for a liveness specification
            // (templateId 2, 5, 6)", and the frontend gates the same decision on the same field
            // (`Board.vue`'s LIVENESS_TEMPLATES). Without emitting it the instruction named something the
            // response did not contain, leaving the label as the only clue — and "Eventually" / "Eventual
            // response" / "Persistence" are display strings that change with wording, not a contract.
            putText(result, "templateId", specification.getTemplateId());
            putText(result, "specificationLabel", specification.getName());
            putText(result, "formulaPreview", specification.getFormula());
            putConditions(result, "aConditions", specification.getAConditions());
            putConditions(result, "ifConditions", specification.getIfConditions());
            putConditions(result, "thenConditions", specification.getThenConditions());
            putText(result, "formulaKind", formulaKind(null, specification.getTemplateId()));
        }
        return result;
    }

    public static List<Map<String, Object>> states(List<TraceStateDto> states) {
        return states(states, null, true);
    }

    private static List<Map<String, Object>> states(List<TraceStateDto> states, TraceStateDto previous,
                                                     boolean includePropertyLabels) {
        if (states == null || states.isEmpty()) return List.of();
        List<Map<String, Object>> presented = new ArrayList<>(states.size());
        TraceStateDto previousLabelState = null;
        for (TraceStateDto state : states) {
            if (state == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stateIndex", state.getStateIndex());
            item.put("valueSnapshot", valueSnapshot(state));
            if (previous != null && previous.getStateIndex() != null && state.getStateIndex() != null
                    && (long) previous.getStateIndex() + 1 == state.getStateIndex()) {
                item.put("changesFromPreviousState", stateChanges(previous, state));
            }
            // A liveness counterexample's fault is its cycle, not any single state, and NuSMV closes the path by
            // re-printing the loop entry. Without these flags an assistant reads such a trace wrong in one of two
            // ways, depending on the cycle length (both measured on NuSMV 2.7.1): a one-state cycle prints no
            // variable lines, so the closing state merges identical to its predecessor and looks like a stalled or
            // truncated run, while a longer cycle prints the deltas back to the entry and looks like the path
            // simply continuing. That is the same misreading the frontend needed a dedicated field to avoid, and
            // it is worse here: state windows are paginated, so one response need not contain both ends of the
            // cycle — which is also why the flags cannot be recovered by comparing values.
            //
            // Emitted only when true, matching the other optional fields here: simulation and fuzz traces are
            // finite paths that never carry them (`SmvTraceParser` is the only writer), so their tool output is
            // unchanged rather than gaining two always-false keys.
            if (Boolean.TRUE.equals(state.getLoopStart())) item.put("loopStart", true);
            if (Boolean.TRUE.equals(state.getLoopBack())) item.put("loopBack", true);
            item.put("devices", devices(state.getDevices(), includePropertyLabels, previousLabelState));
            item.put("triggeredRules", rules(state.getTriggeredRules()));
            item.put("compromisedAutomationLinks", rules(state.getCompromisedAutomationLinks()));
            item.put("trustPrivacies", safeList(state.getTrustPrivacies()));
            item.put("environmentVariables", safeList(state.getEnvVariables()));
            item.put("globalVariables", safeList(state.getGlobalVariables()));
            presented.add(item);
            previous = state;
            previousLabelState = state;
        }
        return List.copyOf(presented);
    }

    public static StateWindow putStateWindow(Map<String, Object> target,
                                             List<TraceStateDto> states,
                                             int requestedOffset,
                                             int limit) {
        return putStateWindow(target, states, requestedOffset, limit, true);
    }

    public static StateWindow putStateWindow(Map<String, Object> target,
                                             List<TraceStateDto> states,
                                             int requestedOffset,
                                             int limit,
                                             boolean includePropertyLabels) {
        List<TraceStateDto> source = states == null ? List.of() : states;
        int windowStart = Math.min(requestedOffset, source.size());
        int windowEnd = (int) Math.min(source.size(), (long) windowStart + limit);
        StateWindow window = new StateWindow(windowStart, windowEnd);
        target.put("stateCount", source.size());
        target.put("stateOffset", requestedOffset);
        target.put("stateLimit", limit);
        target.put("returnedStateCount", windowEnd - windowStart);
        target.put("hasMoreStates", windowEnd < source.size());
        if (windowEnd < source.size()) {
            target.put("nextStateOffset", windowEnd);
        }
        target.put("states", states(source.subList(windowStart, windowEnd),
                windowStart > 0 ? source.get(windowStart - 1) : null, includePropertyLabels));
        return window;
    }

    public record StateWindow(int startInclusive, int endExclusive) {
    }

    private static Map<String, Object> valueSnapshot(TraceStateDto state) {
        List<Map<String, Object>> deviceStates = new ArrayList<>();
        for (TraceDeviceDto device : safeList(state.getDevices())) {
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "deviceLabel", device.getDeviceLabel());
            putText(value, "mode", device.getMode());
            putText(value, "state", device.getState());
            deviceStates.add(value);
        }
        List<Map<String, Object>> environmentValues = new ArrayList<>();
        for (TraceVariableDto variable : safeList(state.getEnvVariables())) {
            Map<String, Object> value = new LinkedHashMap<>();
            putText(value, "name", variable.getName());
            value.put("observed", variable.isObserved());
            if (variable.isObserved() && variable.getValue() != null) {
                value.put("value", variable.getValue());
            }
            environmentValues.add(value);
        }
        // Keep the small chronological view beside its index, before labels and rule details.
        // It belongs to this whole state, so byte-budget paging cannot detach it from that state.
        return Map.of("deviceStates", deviceStates, "environmentValues", environmentValues);
    }

    private static Map<String, Object> stateChanges(TraceStateDto previous, TraceStateDto current) {
        List<Map<String, Object>> deviceChanges = new ArrayList<>();
        for (TraceDeviceDto device : safeList(current.getDevices())) {
            if (device.getDeviceId() == null || device.getState() == null) continue;
            TraceDeviceDto before = previousDevice(previous, device);
            if (before == null || before.getState() == null
                    || device.getState().equals(before.getState())) continue;
            Map<String, Object> change = new LinkedHashMap<>();
            putText(change, "deviceLabel", device.getDeviceLabel());
            putText(change, "mode", device.getMode());
            change.put("previousState", before.getState());
            change.put("currentState", device.getState());
            deviceChanges.add(change);
        }
        List<Map<String, Object>> environmentChanges = new ArrayList<>();
        for (TraceVariableDto variable : safeList(current.getEnvVariables())) {
            if (!variable.isObserved() || variable.getName() == null || variable.getValue() == null) continue;
            TraceVariableDto before = safeList(previous.getEnvVariables()).stream()
                    .filter(candidate -> variable.getName().equals(candidate.getName()))
                    .findFirst().orElse(null);
            if (before == null || !before.isObserved() || before.getValue() == null
                    || variable.getValue().equals(before.getValue())) continue;
            environmentChanges.add(Map.of("name", variable.getName(),
                    "previousValue", before.getValue(), "currentValue", variable.getValue()));
        }
        // These are observed adjacent snapshots, not proof of when a command began or how long it lasts.
        return Map.of("previousStateIndex", previous.getStateIndex(),
                "deviceStates", deviceChanges, "environmentValues", environmentChanges);
    }

    private static TraceDeviceDto previousDevice(TraceStateDto previous, TraceDeviceDto device) {
        if (previous == null || device.getDeviceId() == null) return null;
        return safeList(previous.getDevices()).stream()
                .filter(candidate -> device.getDeviceId().equals(candidate.getDeviceId())
                        && Objects.equals(device.getMode(), candidate.getMode()))
                .findFirst().orElse(null);
    }

    private static List<Map<String, Object>> devices(List<TraceDeviceDto> devices,
                                                    boolean includePropertyLabels,
                                                    TraceStateDto previousLabelState) {
        if (devices == null || devices.isEmpty()) return List.of();
        List<Map<String, Object>> presented = new ArrayList<>(devices.size());
        for (TraceDeviceDto device : devices) {
            if (device == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            putText(item, "deviceLabel", device.getDeviceLabel());
            putText(item, "deviceType", device.getTemplateName());
            putText(item, "state", device.getState());
            putText(item, "mode", device.getMode());
            if (device.getCompromised() != null) item.put("compromised", device.getCompromised());
            item.put("variables", safeList(device.getVariables()));
            if (includePropertyLabels) {
                TraceDeviceDto before = previousDevice(previousLabelState, device);
                if (before != null && previousLabelState.getStateIndex() != null
                        && Objects.equals(device.getTrustPrivacy(), before.getTrustPrivacy())
                        && Objects.equals(device.getPrivacies(), before.getPrivacies())) {
                    // The baseline stays inside this page when the byte budget removes trailing states.
                    item.put("propertyLabelsFromStateIndex", previousLabelState.getStateIndex());
                } else {
                    item.put("trustPrivacyLabels", safeList(device.getTrustPrivacy()));
                    item.put("privacyLabels", safeList(device.getPrivacies()));
                }
            }
            presented.add(item);
        }
        return List.copyOf(presented);
    }

    private static List<Map<String, Object>> rules(List<TraceTriggeredRuleDto> rules) {
        if (rules == null || rules.isEmpty()) return List.of();
        List<Map<String, Object>> presented = new ArrayList<>(rules.size());
        for (TraceTriggeredRuleDto rule : rules) {
            if (rule == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            putText(item, "ruleLabel", rule.getRuleLabel());
            if (!item.isEmpty()) presented.add(item);
        }
        return List.copyOf(presented);
    }

    private static void putConditions(Map<String, Object> target,
                                      String field,
                                      List<SpecConditionDto> conditions) {
        if (conditions == null || conditions.isEmpty()) return;
        List<Map<String, Object>> presented = new ArrayList<>(conditions.size());
        for (SpecConditionDto condition : conditions) {
            if (condition == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            putText(item, "deviceLabel", condition.getDeviceLabel());
            putText(item, "targetType", condition.getTargetType());
            putText(item, "propertyScope", condition.getPropertyScope());
            // Without this the assistant explaining a counterexample cannot tell whether the violated
            // condition was about the home or about one device's reading — the distinction the trace is
            // most often used to explain, since the two only diverge when a device lies.
            putText(item, "variableSource", condition.getVariableSource());
            putText(item, "key", condition.getKey());
            putText(item, "relation", condition.getRelation());
            putText(item, "value", condition.getValue());
            if (!item.isEmpty()) presented.add(item);
        }
        if (!presented.isEmpty()) target.put(field, List.copyOf(presented));
    }

    private static String formulaKind(String checkedExpression, String templateId) {
        String normalized = checkedExpression == null
                ? "" : checkedExpression.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("LTLSPEC") || normalized.startsWith("LTL SPEC")) return "LTL";
        if (normalized.startsWith("CTLSPEC") || normalized.startsWith("CTL SPEC")
                || normalized.startsWith("SPEC")) return "CTL";
        if (templateId == null || templateId.isBlank()) return null;
        return "6".equals(templateId) ? "LTL" : "CTL";
    }

    private static void putText(Map<String, Object> target, String field, String value) {
        if (value != null && !value.isBlank()) target.put(field, value);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
