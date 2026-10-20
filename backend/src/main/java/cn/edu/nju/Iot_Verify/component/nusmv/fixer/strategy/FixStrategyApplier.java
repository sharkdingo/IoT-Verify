package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.fix.ConditionAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.util.RulePreviewText;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * Applies a verified {@link FixSuggestionDto} onto a set of rules, producing the modified rule list.
 *
 * <p>This mirrors the mutation semantics each fix strategy uses internally when it forward-verifies a
 * candidate, but operates on the caller's rule list (the persisted board rules) so the change can be
 * saved. It is intentionally strategy-agnostic and pure (no NuSMV, no persistence).</p>
 *
 * <p>Lives in the strategy package to reuse the package-private helpers in {@link FixStrategyUtils}
 * (device varName resolution and condition fingerprinting).</p>
 */
@Slf4j
public final class FixStrategyApplier {

    private FixStrategyApplier() {}

    /**
     * Apply the suggestion to a deep copy of {@code rules} and return the modified list.
     * The input list is not mutated.
     */
    public static List<RuleDto> apply(String strategy, FixSuggestionDto suggestion,
                                      List<RuleDto> rules, Map<String, DeviceSmvData> deviceSmvMap) {
        return apply(strategy, suggestion, rules, deviceSmvMap, Map.of(), Map.of());
    }

    /**
     * Apply the suggestion, translating newly-added condition device references through
     * {@code persistenceDeviceRefs} when provided. Verified suggestions are computed against SMV
     * varNames, while persisted rules should keep the board's raw {@code DeviceNode.id} references so
     * the UI and storage layer keep using the same stable identity.
     */
    public static List<RuleDto> apply(String strategy, FixSuggestionDto suggestion,
                                      List<RuleDto> rules, Map<String, DeviceSmvData> deviceSmvMap,
                                      Map<String, String> persistenceDeviceRefs) {
        return apply(strategy, suggestion, rules, deviceSmvMap, persistenceDeviceRefs, Map.of());
    }

    /**
     * {@code displayDeviceNames} maps persisted and normalized device references to current user-facing
     * labels. It affects only regenerated rule text; typed rule references remain unchanged.
     */
    public static List<RuleDto> apply(String strategy, FixSuggestionDto suggestion,
                                      List<RuleDto> rules, Map<String, DeviceSmvData> deviceSmvMap,
                                      Map<String, String> persistenceDeviceRefs,
                                      Map<String, String> displayDeviceNames) {
        List<RuleDto> working = FixStrategyUtils.deepCopyRules(rules);
        switch (strategy) {
            case "parameter":
                Set<Integer> parameterRuleIndices = applyParameter(
                        suggestion.getParameterAdjustments(), working);
                refreshRuleStrings(working, parameterRuleIndices, displayDeviceNames);
                return working;
            case "condition":
                Set<Integer> conditionRuleIndices = applyCondition(
                        suggestion.getConditionAdjustments(), working, persistenceDeviceRefs);
                refreshRuleStrings(working, conditionRuleIndices, displayDeviceNames);
                return working;
            case "remove":
                return applyRemove(suggestion.getRemovedRuleIndices(), working);
            default:
                throw new BadRequestException("Unsupported fix strategy: " + strategy);
        }
    }

    // ---- parameter: overwrite the threshold value of an existing condition ----

    private static Set<Integer> applyParameter(
            List<ParameterAdjustment> adjustments, List<RuleDto> rules) {
        if (adjustments == null || adjustments.isEmpty()) {
            throw new BadRequestException("Parameter fix has no adjustments to apply.");
        }
        Set<Integer> adjustedRuleIndices = new LinkedHashSet<>();
        for (ParameterAdjustment adj : adjustments) {
            RuleDto.Condition cond = conditionAt(rules, adj.getRuleIndex(), adj.getConditionIndex());
            // Sanity check: the condition we are about to edit should be the one the fix targeted.
            if (adj.getAttribute() != null && cond.getAttribute() != null
                    && !adj.getAttribute().equals(cond.getAttribute())) {
                throw new BadRequestException("Parameter fix target mismatch at "
                        + describeConditionPosition(adj.getRuleIndex(), adj.getConditionIndex())
                        + ": expected attribute '" + adj.getAttribute()
                        + "' but found '" + cond.getAttribute() + "'.");
            }
            cond.setValue(adj.getNewValue());
            if (adj.getRelation() != null && !adj.getRelation().isBlank()) {
                cond.setRelation(adj.getRelation());
            }
            adjustedRuleIndices.add(adj.getRuleIndex());
        }
        return adjustedRuleIndices;
    }

    // ---- condition: add candidate conditions and remove disabled ones ----

    private static Set<Integer> applyCondition(
            List<ConditionAdjustment> adjustments, List<RuleDto> rules,
            Map<String, String> persistenceDeviceRefs) {
        if (adjustments == null || adjustments.isEmpty()) {
            throw new BadRequestException("Condition fix has no adjustments to apply.");
        }
        // Group by rule so we can remove by descending index safely and add afterwards.
        Map<Integer, List<Integer>> toRemove = new LinkedHashMap<>();
        Map<Integer, List<RuleDto.Condition>> toAdd = new LinkedHashMap<>();
        Set<Integer> adjustedRuleIndices = new LinkedHashSet<>();

        for (ConditionAdjustment adj : adjustments) {
            String action = adj.getAction();
            if (action == null || "keep".equals(action)) continue;
            int ruleIdx = adj.getRuleIndex();
            requireRuleIndex(rules, ruleIdx);
            if ("remove".equals(action)) {
                toRemove.computeIfAbsent(ruleIdx, k -> new ArrayList<>()).add(adj.getConditionIndex());
            } else if ("add".equals(action)) {
                RuleDto.Condition cond = RuleDto.Condition.builder()
                        .deviceName(persistenceDeviceRef(adj.getDeviceName(), persistenceDeviceRefs))
                        .attribute(adj.getAttribute())
                        .targetType(adj.getTargetType())
                        .relation(adj.getRelation())
                        .value(adj.getValue())
                        .build();
                toAdd.computeIfAbsent(ruleIdx, k -> new ArrayList<>()).add(cond);
            } else {
                throw new BadRequestException("Unknown condition action: " + action);
            }
        }

        // 1) remove existing conditions in descending index order to keep indices valid
        for (Map.Entry<Integer, List<Integer>> e : toRemove.entrySet()) {
            List<Integer> indices = new ArrayList<>(e.getValue());
            indices.sort(Collections.reverseOrder());
            List<RuleDto.Condition> conds = rules.get(e.getKey()).getConditions();
            if (conds == null) continue;
            for (int idx : indices) {
                if (idx >= 0 && idx < conds.size()) {
                    conds.remove(idx);
                    adjustedRuleIndices.add(e.getKey());
                }
            }
        }
        // 2) add candidate conditions
        for (Map.Entry<Integer, List<RuleDto.Condition>> e : toAdd.entrySet()) {
            RuleDto rule = rules.get(e.getKey());
            if (rule.getConditions() == null) {
                rule.setConditions(new ArrayList<>());
            }
            rule.getConditions().addAll(e.getValue());
            adjustedRuleIndices.add(e.getKey());
        }

        // A rule with no conditions is fail-closed during SMV generation and is invalid in the
        // persisted DTO; reject rather than use an empty condition list to imitate rule removal.
        for (RuleDto rule : rules) {
            if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
                throw new BadRequestException("Applying this condition fix would leave a rule with no "
                        + "trigger conditions. Please review the suggestion manually.");
            }
        }
        return adjustedRuleIndices;
    }

    private static void refreshRuleStrings(
            List<RuleDto> rules, Set<Integer> adjustedRuleIndices,
            Map<String, String> displayDeviceNames) {
        if (rules == null || adjustedRuleIndices == null) {
            return;
        }
        for (int ruleIndex : adjustedRuleIndices) {
            RuleDto rule = rules.get(ruleIndex);
            if (rule != null) {
                rule.setRuleString(RulePreviewText.bounded(buildRuleString(rule, displayDeviceNames)));
            }
        }
    }

    private static String buildRuleString(RuleDto rule, Map<String, String> displayDeviceNames) {
        List<String> conditionTexts = new ArrayList<>();
        if (rule.getConditions() != null) {
            for (RuleDto.Condition condition : rule.getConditions()) {
                if (condition != null) {
                    conditionTexts.add(describeCondition(condition, displayDeviceNames));
                }
            }
        }
        String ifPart = conditionTexts.isEmpty() ? "FALSE" : String.join(" AND ", conditionTexts);
        return "IF " + ifPart + " THEN " + describeCommand(rule.getCommand(), displayDeviceNames);
    }

    private static String describeCondition(RuleDto.Condition condition, Map<String, String> displayDeviceNames) {
        String device = displayDeviceName(condition.getDeviceName(), displayDeviceNames);
        String attribute = text(condition.getAttribute(), "unknown-attribute");
        String targetType = condition.getTargetType() == null ? "" : condition.getTargetType().trim().toLowerCase(Locale.ROOT);
        if ("api".equals(targetType)) {
            return device + " emits " + attribute;
        }
        return device + "." + attribute + " "
                + text(condition.getRelation(), "?") + " "
                + text(condition.getValue(), "?");
    }

    private static String describeCommand(RuleDto.Command command, Map<String, String> displayDeviceNames) {
        if (command == null) {
            return "missing-command";
        }
        String result = displayDeviceName(command.getDeviceName(), displayDeviceNames)
                + "." + text(command.getAction(), "unknown-action");
        String content = textOrNull(command.getContent());
        String contentDevice = textOrNull(command.getContentDevice());
        if (content != null || contentDevice != null) {
            result += " with";
            if (content != null) {
                result += " content " + content;
            }
            if (contentDevice != null) {
                result += " from " + displayDeviceName(contentDevice, displayDeviceNames);
            }
        }
        return result;
    }

    private static String displayDeviceName(String deviceRef, Map<String, String> displayDeviceNames) {
        String ref = textOrNull(deviceRef);
        if (ref == null) {
            return "unknown device";
        }
        String label = displayDeviceNames == null ? null : textOrNull(displayDeviceNames.get(ref));
        return label != null ? label : ref;
    }

    private static String text(String value, String fallback) {
        String trimmed = textOrNull(value);
        return trimmed != null ? trimmed : fallback;
    }

    private static String textOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String persistenceDeviceRef(String deviceRef, Map<String, String> persistenceDeviceRefs) {
        String ref = textOrNull(deviceRef);
        if (ref == null || persistenceDeviceRefs == null || persistenceDeviceRefs.isEmpty()) {
            throw new BadRequestException("The automatic fix could not map a device reference to the current board. "
                    + "No rule was changed. Please re-run verification and try again.");
        }
        String mapped = persistenceDeviceRefs.get(ref);
        if (mapped == null || mapped.isBlank()) {
            throw new BadRequestException("The automatic fix could not map a device reference to the current board. "
                    + "No rule was changed. Please re-run verification and try again.");
        }
        return mapped;
    }

    // ---- remove: drop the flagged rules ----

    private static List<RuleDto> applyRemove(List<Integer> removedIndices, List<RuleDto> rules) {
        if (removedIndices == null || removedIndices.isEmpty()) {
            throw new BadRequestException("Remove-rules fix has no rules to remove.");
        }
        for (int idx : removedIndices) {
            requireRuleIndex(rules, idx);
        }
        List<RuleDto> remaining = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            if (!removedIndices.contains(i)) {
                remaining.add(rules.get(i));
            }
        }
        return remaining;
    }

    // ---- shared helpers ----

    private static RuleDto.Condition conditionAt(List<RuleDto> rules, int ruleIdx, int condIdx) {
        requireRuleIndex(rules, ruleIdx);
        List<RuleDto.Condition> conds = rules.get(ruleIdx).getConditions();
        if (conds == null || condIdx < 0 || condIdx >= conds.size()) {
            throw new BadRequestException("Fix references " + describeConditionPosition(ruleIdx, condIdx)
                    + " which does not exist.");
        }
        return conds.get(condIdx);
    }

    private static void requireRuleIndex(List<RuleDto> rules, int ruleIdx) {
        if (ruleIdx < 0 || ruleIdx >= rules.size()) {
            throw new BadRequestException("Fix references " + describeRulePosition(ruleIdx)
                    + " which is out of range for the current rule list.");
        }
    }

    private static String describeConditionPosition(int ruleIdx, int condIdx) {
        return describeRulePosition(ruleIdx) + " condition #" + (condIdx + 1);
    }

    private static String describeRulePosition(int ruleIdx) {
        return "Rule #" + (ruleIdx + 1);
    }

    /** Expose {@link FixStrategyUtils#resolveVarNameSafe} for the service's drift check. */
    public static String resolveVarName(String deviceName, Map<String, DeviceSmvData> deviceSmvMap) {
        String v = FixStrategyUtils.resolveVarNameSafe(deviceName, deviceSmvMap);
        return v != null ? v : (deviceName == null ? "" : deviceName);
    }

    /** Expose {@link FixStrategyUtils#conditionFingerprint} for the service's drift check. */
    public static String conditionFingerprint(RuleDto.Condition c, Map<String, DeviceSmvData> deviceSmvMap) {
        String fp = FixStrategyUtils.conditionFingerprint(c, deviceSmvMap);
        return fp != null ? fp : "?";
    }
}
