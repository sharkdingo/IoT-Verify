package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.cleanEnumLiteral;
import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.findApi;
import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.findConditionVariable;
import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.findDeclaredVariable;
import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.hasSignalApi;

/**
 * Template-aware validation shared by AI mutation tools.
 *
 * <p>The UI, AI tools, and NuSMV generator must agree that device nodes are
 * instances and templates define the legal states, modes, variables, APIs, and
 * content keys. These checks stop AI-created board data from being accepted only
 * to fail later during simulation or verification.</p>
 */
public final class BoardSemanticValidator {


    private BoardSemanticValidator() {
    }

    public static BoardContext context(List<DeviceNodeDto> nodes, List<DeviceTemplateDto> templates) {
        return context(nodes, templates, List.of());
    }

    public static BoardContext context(List<DeviceNodeDto> nodes,
                                       List<DeviceTemplateDto> templates,
                                       List<BoardEnvironmentVariableDto> environmentVariables) {
        return buildContext(nodes, templates, environmentVariables, false);
    }

    public static BoardContext recommendationContext(
            List<DeviceNodeDto> nodes,
            List<DeviceTemplateDto> templates,
            List<BoardEnvironmentVariableDto> environmentVariables) {
        return buildContext(nodes, templates, environmentVariables, true);
    }

    private static BoardContext buildContext(
            List<DeviceNodeDto> nodes,
            List<DeviceTemplateDto> templates,
            List<BoardEnvironmentVariableDto> environmentVariables,
            boolean enforceReachability) {
        Map<String, DeviceNodeDto> nodesById = new LinkedHashMap<>();
        if (nodes != null) {
            for (DeviceNodeDto node : nodes) {
                if (node != null && hasText(node.getId())) {
                    nodesById.put(node.getId().trim(), node);
                }
            }
        }

        Map<String, DeviceTemplateDto> templatesByName = new HashMap<>();
        if (templates != null) {
            for (DeviceTemplateDto template : templates) {
                if (template == null) {
                    continue;
                }
                putTemplateAlias(templatesByName, template.getName(), template);
                if (template.getManifest() != null) {
                    putTemplateAlias(templatesByName, template.getManifest().getName(), template);
                }
            }
        }
        Map<String, BoardEnvironmentVariableDto> environmentByName = new LinkedHashMap<>();
        if (environmentVariables != null) {
            for (BoardEnvironmentVariableDto variable : environmentVariables) {
                if (variable != null && hasText(variable.getName())) {
                    environmentByName.put(normalizeKey(variable.getName()), variable);
                }
            }
        }
        return new BoardContext(
                nodesById, templatesByName, environmentByName,
                !templatesByName.isEmpty(), enforceReachability);
    }

    public static String validateRuleCondition(BoardContext context, RuleDto.Condition condition, int index) {
        DeviceTemplateDto template = templateForDevice(context, condition.getDeviceName());
        if (template == null || template.getManifest() == null) {
            return null;
        }

        String targetType = normalize(condition.getTargetType());
        String attribute = trimToNull(condition.getAttribute());
        String relation = SmvRelationUtils.normalizeRelation(condition.getRelation());
        String value = trimToNull(condition.getValue());
        String prefix = "Condition " + (index + 1);

        if ("api".equals(targetType)) {
            return hasSignalApi(template.getManifest(), attribute)
                    ? null
                    : prefix + " references unknown or non-signal API '" + attribute + "'.";
        }
        if ("variable".equals(targetType)) {
            DeviceTemplateDto.DeviceManifest.InternalVariable variable = findConditionVariable(template.getManifest(), attribute);
            if (variable == null) {
                return prefix + " references unknown template variable '" + attribute + "'.";
            }
            return validateVariableValue(prefix, variable, relation, value);
        }
        if ("mode".equals(targetType)) {
            String mode = resolveModeName(template.getManifest(), attribute);
            if (mode == null) {
                return prefix + " references unknown mode '" + attribute + "'.";
            }
            return validateModeValue(prefix, template.getManifest(), mode, relation, value);
        }
        if ("state".equals(targetType)) {
            if (!"state".equalsIgnoreCase(attribute)) {
                return prefix + " state conditions must use attribute 'state'.";
            }
            return validateStateValue(prefix, template.getManifest(), relation, value);
        }
        return null;
    }

    public static String validateRuleCommand(BoardContext context, RuleDto.Command command) {
        DeviceTemplateDto targetTemplate = templateForDevice(context, command.getDeviceName());
        DeviceTemplateDto.DeviceManifest.API actionApi = targetTemplate != null
                ? findApi(targetTemplate.getManifest(), command.getAction()) : null;
        if (targetTemplate != null && targetTemplate.getManifest() != null && actionApi == null) {
            return "Command references unknown API '" + command.getAction() + "'.";
        }

        if ((hasText(command.getContentDevice()) || hasText(command.getContent()))
                && actionApi != null && !Boolean.TRUE.equals(actionApi.getAcceptsContent())) {
            return "Action '" + actionApi.getName()
                    + "' does not accept a content-sensitivity input. Omit command.contentDevice and command.content.";
        }
        if (!command.isContentPayloadShapeValid()) {
            return "contentDevice and content must be provided together for a content-accepting action.";
        }
        if (hasText(command.getContentDevice()) && hasText(command.getContent())) {
            DeviceTemplateDto contentTemplate = templateForDevice(context, command.getContentDevice());
            if (contentTemplate != null && contentTemplate.getManifest() != null
                    && !hasContent(contentTemplate.getManifest(), command.getContent())) {
                return "Command references unknown content '" + command.getContent() + "'.";
            }
        }
        return null;
    }

    /**
     * Rejects only contradictions that are provable from the declared template domains.
     * Ordinary board APIs intentionally keep accepting structurally valid user-authored
     * condition sets; this stricter check is for AI-authored mutations.
     */
    public static GroupValidationIssue validateRuleConditionGroup(BoardContext context,
                                                                    List<RuleDto.Condition> conditions,
                                                                    RuleDto.Command command) {
        List<RuleDto.Condition> safeConditions = conditions == null ? List.of() : conditions;
        GroupValidationIssue stateIssue = validateStateAndModeGroups(
                context,
                safeConditions,
                RuleDto.Condition::getDeviceName,
                RuleDto.Condition::getTargetType,
                RuleDto.Condition::getAttribute,
                RuleDto.Condition::getRelation,
                RuleDto.Condition::getValue
        );
        if (stateIssue != null) {
            return stateIssue;
        }

        GroupValidationIssue variableIssue = validateVariableGroups(
                context,
                safeConditions,
                RuleDto.Condition::getDeviceName,
                RuleDto.Condition::getTargetType,
                RuleDto.Condition::getAttribute,
                RuleDto.Condition::getRelation,
                RuleDto.Condition::getValue,
                // A rule guard has no such choice: it always reads the device's own value, so all
                // rule conditions on one key belong to a single jointly-constrained group.
                ignored -> null
        );
        if (variableIssue != null) {
            return variableIssue;
        }

        if (command == null || !hasText(command.getDeviceName()) || !hasText(command.getAction())) {
            return null;
        }
        DeviceTemplateDto template = templateForDevice(context, command.getDeviceName());
        DeviceTemplateDto.DeviceManifest.API api = template == null
                ? null : findApi(template.getManifest(), command.getAction());
        if (api == null || !hasText(api.getStartState()) || "_".equals(api.getStartState().trim())) {
            return null;
        }

        List<RuleDto.Condition> targetStateConditions = safeConditions.stream()
                .filter(condition -> condition != null
                        && command.getDeviceName().trim().equals(trimToNull(condition.getDeviceName()))
                        && isStateOrMode(condition.getTargetType()))
                .toList();
        List<StateTuple> legalStates = legalStateTuples(template.getManifest());
        boolean hasCompatibleState = legalStates.stream()
                .filter(tuple -> stateCandidateMatches(template.getManifest(), tuple, api.getStartState()))
                .anyMatch(tuple -> targetStateConditions.stream().allMatch(condition ->
                        stateOrModeConditionMatches(
                                template.getManifest(),
                                tuple,
                                condition.getTargetType(),
                                condition.getAttribute(),
                                condition.getRelation(),
                                condition.getValue())));
        if (!hasCompatibleState) {
            return new GroupValidationIssue(
                    "COMMAND_PRESTATE_INCOMPATIBLE",
                    "The rule conditions on device '" + command.getDeviceName()
                            + "' cannot hold in the start state required by action '"
                            + command.getAction() + "' (" + api.getStartState() + ")."
            );
        }
        if (context.enforceReachability()) {
            List<StateTuple> reachableStates = reachableStateTuples(
                    context, command.getDeviceName(), template.getManifest(), legalStates);
            boolean hasReachableCompatibleState = reachableStates.stream()
                    .filter(tuple -> stateCandidateMatches(template.getManifest(), tuple, api.getStartState()))
                    .anyMatch(tuple -> targetStateConditions.stream().allMatch(condition ->
                            stateOrModeConditionMatches(
                                    template.getManifest(),
                                    tuple,
                                    condition.getTargetType(),
                                    condition.getAttribute(),
                                    condition.getRelation(),
                                    condition.getValue())));
            if (!hasReachableCompatibleState) {
                return new GroupValidationIssue(
                        "COMMAND_PRESTATE_UNREACHABLE",
                        "Action '" + command.getAction() + "' on device '" + command.getDeviceName()
                                + "' requires start state '" + api.getStartState()
                                + "', which is unreachable from the current device state."
                );
            }
        }
        return null;
    }

    public static String validateSpecCondition(BoardContext context, SpecConditionDto condition, String side, int index) {
        DeviceTemplateDto template = templateForDevice(context, condition.getDeviceId());
        if (template == null || template.getManifest() == null) {
            return null;
        }

        String targetType = normalize(condition.getTargetType());
        String key = trimToNull(condition.getKey());
        String relation = SmvRelationUtils.normalizeRelation(condition.getRelation());
        String value = trimToNull(condition.getValue());
        String displaySide = side == null ? "UNKNOWN" : side.trim().toUpperCase(Locale.ROOT);
        String prefix = "Condition " + (index + 1) + " on '" + displaySide + "'";

        if ("api".equals(targetType)) {
            if (!hasSignalApi(template.getManifest(), key)) {
                return prefix + " references unknown or non-signal API '" + key + "'.";
            }
            return validateLiteralValue(prefix, relation, value, Set.of("TRUE", "FALSE"), "TRUE or FALSE");
        }
        if ("state".equals(targetType)) {
            if (!"state".equalsIgnoreCase(key)) {
                return prefix + " state conditions must use key 'state'.";
            }
            return validateStateValue(prefix, template.getManifest(), relation, value);
        }
        if ("mode".equals(targetType)) {
            String mode = resolveModeName(template.getManifest(), key);
            if (mode == null) {
                return prefix + " references unknown mode '" + key + "'.";
            }
            return validateModeValue(prefix, template.getManifest(), mode, relation, value);
        }
        if ("variable".equals(targetType)) {
            DeviceTemplateDto.DeviceManifest.InternalVariable variable = findConditionVariable(template.getManifest(), key);
            if (variable == null) {
                return prefix + " references unknown template variable '" + key + "'.";
            }
            if ("environment".equals(normalize(condition.getVariableSource()))
                    && Boolean.TRUE.equals(variable.getIsInside())) {
                return prefix + " cannot read device-local variable '" + key + "' from the environment.";
            }
            return validateVariableValue(prefix, variable, relation, value);
        }
        if ("trust".equals(targetType)) {
            String propertyError = validatePropertyReference(prefix, condition, template.getManifest());
            if (propertyError != null) return propertyError;
            return validateLiteralValue(prefix, relation, value, Set.of("trusted", "untrusted"), "trusted or untrusted");
        }
        if ("privacy".equals(targetType)) {
            String propertyError = validatePropertyReference(prefix, condition, template.getManifest());
            if (propertyError != null) return propertyError;
            return validateLiteralValue(prefix, relation, value, Set.of("public", "private"), "public or private");
        }
        return null;
    }

    public static GroupValidationIssue validateSpecConditionGroup(BoardContext context,
                                                                   List<SpecConditionDto> conditions,
                                                                   String side) {
        List<SpecConditionDto> safeConditions = conditions == null ? List.of() : conditions;
        GroupValidationIssue stateIssue = validateStateAndModeGroups(
                context,
                safeConditions,
                SpecConditionDto::getDeviceId,
                SpecConditionDto::getTargetType,
                SpecConditionDto::getKey,
                SpecConditionDto::getRelation,
                SpecConditionDto::getValue
        );
        if (stateIssue != null) {
            return withSpecSide(stateIssue, side);
        }

        GroupValidationIssue variableIssue = validateVariableGroups(
                context,
                safeConditions,
                SpecConditionDto::getDeviceId,
                SpecConditionDto::getTargetType,
                SpecConditionDto::getKey,
                SpecConditionDto::getRelation,
                SpecConditionDto::getValue,
                SpecConditionDto::getVariableSource
        );
        if (variableIssue != null) {
            return withSpecSide(variableIssue, side);
        }

        Map<String, List<SpecConditionDto>> literalGroups = new LinkedHashMap<>();
        for (SpecConditionDto condition : safeConditions) {
            if (condition == null || !hasText(condition.getDeviceId())) {
                continue;
            }
            String targetType = normalize(condition.getTargetType());
            if (!Set.of("api", "trust", "privacy").contains(targetType)) {
                continue;
            }
            String groupKey = condition.getDeviceId().trim() + "\u0000" + targetType + "\u0000"
                    + normalize(condition.getPropertyScope()) + "\u0000" + normalize(condition.getKey());
            literalGroups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(condition);
        }
        for (List<SpecConditionDto> group : literalGroups.values()) {
            SpecConditionDto first = group.get(0);
            String targetType = normalize(first.getTargetType());
            Set<String> domain = switch (targetType) {
                case "api" -> Set.of("true", "false");
                case "trust" -> Set.of("trusted", "untrusted");
                case "privacy" -> Set.of("public", "private");
                default -> Set.of();
            };
            if (!hasSatisfyingLiteral(domain, group, SpecConditionDto::getRelation, SpecConditionDto::getValue)) {
                return withSpecSide(new GroupValidationIssue(
                        "CONTRADICTORY_CONDITION_GROUP",
                        "Conditions for " + targetType + " '" + first.getKey() + "' on device '"
                                + first.getDeviceId() + "' have no common legal value."
                ), side);
            }
        }
        return null;
    }

    public record BoardContext(Map<String, DeviceNodeDto> nodesById,
                               Map<String, DeviceTemplateDto> templatesByName,
                               Map<String, BoardEnvironmentVariableDto> environmentByName,
                               boolean hasTemplateData,
                               boolean enforceReachability) {
    }

    public record GroupValidationIssue(String reasonCode, String message) {
    }

    private static GroupValidationIssue withSpecSide(GroupValidationIssue issue, String side) {
        if (issue == null || !hasText(side)) {
            return issue;
        }
        return new GroupValidationIssue(issue.reasonCode(),
                "The '" + side.trim().toUpperCase(Locale.ROOT) + "' condition group is unsatisfiable. "
                        + issue.message());
    }

    private static <T> GroupValidationIssue validateStateAndModeGroups(
            BoardContext context,
            Collection<T> conditions,
            Function<T, String> deviceGetter,
            Function<T, String> targetTypeGetter,
            Function<T, String> keyGetter,
            Function<T, String> relationGetter,
            Function<T, String> valueGetter) {
        Map<String, List<T>> byDevice = new LinkedHashMap<>();
        for (T condition : conditions) {
            if (condition == null || !hasText(deviceGetter.apply(condition))
                    || !isStateOrMode(targetTypeGetter.apply(condition))) {
                continue;
            }
            byDevice.computeIfAbsent(deviceGetter.apply(condition).trim(), ignored -> new ArrayList<>())
                    .add(condition);
        }
        for (Map.Entry<String, List<T>> entry : byDevice.entrySet()) {
            DeviceTemplateDto template = templateForDevice(context, entry.getKey());
            if (template == null || template.getManifest() == null) {
                continue;
            }
            List<StateTuple> legalStates = legalStateTuples(template.getManifest());
            boolean satisfiable = legalStates.stream()
                    .anyMatch(tuple -> entry.getValue().stream().allMatch(condition ->
                            stateOrModeConditionMatches(
                                    template.getManifest(), tuple,
                                    targetTypeGetter.apply(condition), keyGetter.apply(condition),
                                    relationGetter.apply(condition), valueGetter.apply(condition))));
            if (!satisfiable) {
                return new GroupValidationIssue(
                        "CONTRADICTORY_CONDITION_GROUP",
                        "State and mode conditions on device '" + entry.getKey()
                                + "' have no common legal working state."
                );
            }
            if (context != null && context.enforceReachability()) {
                List<StateTuple> reachableStates = reachableStateTuples(
                        context, entry.getKey(), template.getManifest(), legalStates);
                if (reachableStates.size() < legalStates.size()
                        && reachableStates.stream().noneMatch(tuple -> entry.getValue().stream().allMatch(condition ->
                        stateOrModeConditionMatches(
                                template.getManifest(), tuple,
                                targetTypeGetter.apply(condition), keyGetter.apply(condition),
                                relationGetter.apply(condition), valueGetter.apply(condition))))) {
                    return new GroupValidationIssue(
                            "UNREACHABLE_CONDITION_GROUP",
                            "State and mode conditions on device '" + entry.getKey()
                                    + "' are legal but unreachable from its current state under the declared APIs and transitions."
                    );
                }
            }
        }
        return null;
    }

    private static <T> GroupValidationIssue validateVariableGroups(
            BoardContext context,
            Collection<T> conditions,
            Function<T, String> deviceGetter,
            Function<T, String> targetTypeGetter,
            Function<T, String> keyGetter,
            Function<T, String> relationGetter,
            Function<T, String> valueGetter,
            Function<T, String> variableSourceGetter) {
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T condition : conditions) {
            if (condition == null || !"variable".equals(normalize(targetTypeGetter.apply(condition)))
                    || !hasText(deviceGetter.apply(condition)) || !hasText(keyGetter.apply(condition))) {
                continue;
            }
            // The reading belongs in the group key: two conditions on one key asking *different* questions
            // are not jointly constrained, because under compromise the pool value and the device's report
            // may legitimately disagree. Grouped together, "the home is below 30 AND this sensor reports
            // above 40" — the falsified-reading specification this field exists to express — collapsed into
            // one contradictory group and was refused as unsatisfiable.
            String groupKey = deviceGetter.apply(condition).trim()
                    + "\u0000" + normalize(variableSourceGetter.apply(condition))
                    + "\u0000" + keyGetter.apply(condition).trim();
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(condition);
        }
        for (List<T> group : groups.values()) {
            T first = group.get(0);
            String deviceId = deviceGetter.apply(first).trim();
            String variableName = keyGetter.apply(first).trim();
            DeviceTemplateDto template = templateForDevice(context, deviceId);
            DeviceTemplateDto.DeviceManifest.InternalVariable variable = template == null
                    ? null : findConditionVariable(template.getManifest(), variableName);
            if (variable == null) {
                continue;
            }

            boolean satisfiable;
            if (variable.getValues() != null && !variable.getValues().isEmpty()) {
                Set<String> domain = new LinkedHashSet<>();
                for (String value : variable.getValues()) {
                    if (hasText(value)) {
                        domain.add(cleanEnumLiteral(value).toLowerCase(Locale.ROOT));
                    }
                }
                satisfiable = hasSatisfyingLiteral(domain, group, relationGetter, valueGetter);
            } else if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
                satisfiable = hasSatisfyingInteger(
                        variable.getLowerBound(), variable.getUpperBound(), group, relationGetter, valueGetter);
            } else {
                continue;
            }
            if (!satisfiable) {
                return new GroupValidationIssue(
                        "CONTRADICTORY_CONDITION_GROUP",
                        "Conditions for variable '" + variableName + "' on device '" + deviceId
                                + "' have no common legal value."
                );
            }

            Set<String> reachableValues = context != null && context.enforceReachability()
                    ? provablyReachableVariableValues(context, deviceId, template, variable)
                    : null;
            if (reachableValues != null && !reachableValues.isEmpty()) {
                boolean reachable = reachableValues.stream().anyMatch(candidate ->
                        group.stream().allMatch(condition -> variableConditionMatches(
                                candidate, variable, relationGetter.apply(condition), valueGetter.apply(condition))));
                if (!reachable) {
                    return new GroupValidationIssue(
                            "UNREACHABLE_CONDITION_GROUP",
                            "Conditions for variable '" + variableName + "' on device '" + deviceId
                                    + "' are legal but unreachable from its current value under the declared dynamics and assignments."
                    );
                }
            }
        }
        return null;
    }

    private static boolean variableConditionMatches(String candidate,
                                                    DeviceTemplateDto.DeviceManifest.InternalVariable variable,
                                                    String relation,
                                                    String value) {
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            return literalConditionMatches(candidate, relation, value);
        }
        try {
            int numeric = Integer.parseInt(candidate.trim());
            return numericConditionMatches(numeric, relation, value);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean numericConditionMatches(int candidate, String rawRelation, String rawValue) {
        String relation = SmvRelationUtils.normalizeRelation(rawRelation);
        List<Integer> values = new ArrayList<>();
        for (String raw : splitValues(rawValue, relation)) {
            try {
                values.add(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (values.isEmpty()) return false;
        return switch (relation == null ? "" : relation) {
            case "=" -> candidate == values.get(0);
            case "!=" -> candidate != values.get(0);
            case "in" -> values.contains(candidate);
            case "not in" -> !values.contains(candidate);
            case ">" -> candidate > values.get(0);
            case ">=" -> candidate >= values.get(0);
            case "<" -> candidate < values.get(0);
            case "<=" -> candidate <= values.get(0);
            default -> false;
        };
    }

    private static <T> boolean hasSatisfyingLiteral(Set<String> domain,
                                                    Collection<T> conditions,
                                                    Function<T, String> relationGetter,
                                                    Function<T, String> valueGetter) {
        return domain.stream().anyMatch(candidate -> conditions.stream().allMatch(condition ->
                literalConditionMatches(candidate, relationGetter.apply(condition), valueGetter.apply(condition))));
    }

    private static boolean literalConditionMatches(String candidate, String rawRelation, String rawValue) {
        String relation = SmvRelationUtils.normalizeRelation(rawRelation);
        Set<String> values = new HashSet<>();
        for (String value : splitValues(rawValue, relation)) {
            values.add(cleanEnumLiteral(value).toLowerCase(Locale.ROOT));
        }
        String normalizedCandidate = cleanEnumLiteral(candidate).toLowerCase(Locale.ROOT);
        return switch (relation == null ? "" : relation) {
            case "=", "in" -> values.contains(normalizedCandidate);
            case "!=", "not in" -> !values.contains(normalizedCandidate);
            default -> false;
        };
    }

    private static <T> boolean hasSatisfyingInteger(int declaredLower,
                                                    int declaredUpper,
                                                    Collection<T> conditions,
                                                    Function<T, String> relationGetter,
                                                    Function<T, String> valueGetter) {
        long lower = declaredLower;
        long upper = declaredUpper;
        Set<Integer> excluded = new HashSet<>();
        Set<Integer> included = null;
        for (T condition : conditions) {
            String relation = SmvRelationUtils.normalizeRelation(relationGetter.apply(condition));
            List<Integer> values = new ArrayList<>();
            for (String rawValue : splitValues(valueGetter.apply(condition), relation)) {
                try {
                    values.add(Integer.parseInt(rawValue.trim()));
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
            if (values.isEmpty()) {
                return false;
            }
            switch (relation == null ? "" : relation) {
                case "=" -> included = intersect(included, Set.of(values.get(0)));
                case "!=" -> excluded.add(values.get(0));
                case "in" -> included = intersect(included, new LinkedHashSet<>(values));
                case "not in" -> excluded.addAll(values);
                case ">" -> lower = Math.max(lower, (long) values.get(0) + 1L);
                case ">=" -> lower = Math.max(lower, values.get(0));
                case "<" -> upper = Math.min(upper, (long) values.get(0) - 1L);
                case "<=" -> upper = Math.min(upper, values.get(0));
                default -> {
                    return false;
                }
            }
        }
        if (lower > upper) {
            return false;
        }
        if (included != null) {
            for (Integer value : included) {
                if (value >= lower && value <= upper && !excluded.contains(value)) {
                    return true;
                }
            }
            return false;
        }
        long domainSize = upper - lower + 1L;
        long excludedInRange = 0L;
        for (Integer value : excluded) {
            if (value >= lower && value <= upper) {
                excludedInRange++;
            }
        }
        return excludedInRange < domainSize;
    }

    private static Set<Integer> intersect(Set<Integer> current, Set<Integer> next) {
        if (current == null) {
            return new LinkedHashSet<>(next);
        }
        Set<Integer> result = new LinkedHashSet<>(current);
        result.retainAll(next);
        return result;
    }

    private static boolean isStateOrMode(String targetType) {
        String normalized = normalize(targetType);
        return "state".equals(normalized) || "mode".equals(normalized);
    }

    private static boolean stateOrModeConditionMatches(DeviceTemplateDto.DeviceManifest manifest,
                                                       StateTuple tuple,
                                                       String rawTargetType,
                                                       String rawKey,
                                                       String rawRelation,
                                                       String rawValue) {
        String targetType = normalize(rawTargetType);
        String relation = SmvRelationUtils.normalizeRelation(rawRelation);
        if ("mode".equals(targetType)) {
            String mode = resolveModeName(manifest, rawKey);
            String currentValue = mode == null ? null : tuple.valuesByMode().get(mode);
            if (currentValue == null) {
                return false;
            }
            Set<String> values = new HashSet<>();
            for (String value : splitValues(rawValue, relation)) {
                values.add(DeviceSmvDataFactory.cleanStateName(value));
            }
            boolean contains = values.contains(currentValue);
            return switch (relation == null ? "" : relation) {
                case "=", "in" -> contains;
                case "!=", "not in" -> !contains;
                default -> false;
            };
        }
        if (!"state".equals(targetType)) {
            return true;
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitStateRuleValues(rawValue, modeNames(manifest).size())
                : List.of(rawValue == null ? "" : rawValue.trim());
        boolean anyMatch = candidates.stream().anyMatch(candidate -> stateCandidateMatches(manifest, tuple, candidate));
        return switch (relation == null ? "" : relation) {
            case "=", "in" -> anyMatch;
            case "!=", "not in" -> !anyMatch;
            default -> false;
        };
    }

    private static boolean stateCandidateMatches(DeviceTemplateDto.DeviceManifest manifest,
                                                 StateTuple tuple,
                                                 String rawCandidate) {
        if (!hasText(rawCandidate)) {
            return false;
        }
        List<String> modes = modeNames(manifest);
        String candidate = rawCandidate.trim();
        if (candidate.contains(";")) {
            String[] parts = candidate.split(";", -1);
            if (parts.length != modes.size()) {
                return false;
            }
            boolean hasConcreteValue = false;
            for (int i = 0; i < modes.size(); i++) {
                String cleaned = DeviceSmvDataFactory.cleanStateName(parts[i]);
                if (!hasText(cleaned) || "_".equals(cleaned)) {
                    continue;
                }
                hasConcreteValue = true;
                if (!cleaned.equals(tuple.valuesByMode().get(modes.get(i)))) {
                    return false;
                }
            }
            return hasConcreteValue;
        }

        String cleaned = DeviceSmvDataFactory.cleanStateName(candidate);
        String matchedMode = null;
        Map<String, List<String>> valuesByMode = modeValues(manifest);
        for (String mode : modes) {
            List<String> values = valuesByMode.get(mode);
            if (values != null && values.contains(cleaned)) {
                if (matchedMode != null) {
                    return false;
                }
                matchedMode = mode;
            }
        }
        return matchedMode != null && cleaned.equals(tuple.valuesByMode().get(matchedMode));
    }

    private static List<StateTuple> legalStateTuples(DeviceTemplateDto.DeviceManifest manifest) {
        List<String> modes = modeNames(manifest);
        List<StateTuple> result = new ArrayList<>();
        if (manifest == null || manifest.getWorkingStates() == null || modes.isEmpty()) {
            return result;
        }
        for (DeviceTemplateDto.DeviceManifest.WorkingState state : manifest.getWorkingStates()) {
            if (state == null || !hasText(state.getName())) {
                continue;
            }
            String[] parts = modes.size() == 1
                    ? new String[]{state.getName()}
                    : state.getName().split(";", -1);
            if (parts.length != modes.size()) {
                continue;
            }
            Map<String, String> valuesByMode = new LinkedHashMap<>();
            boolean valid = true;
            for (int i = 0; i < modes.size(); i++) {
                String value = DeviceSmvDataFactory.cleanStateName(parts[i]);
                if (!hasText(value) || "_".equals(value)) {
                    valid = false;
                    break;
                }
                valuesByMode.put(modes.get(i), value);
            }
            if (valid) {
                result.add(new StateTuple(valuesByMode));
            }
        }
        return result;
    }

    private static List<StateTuple> reachableStateTuples(BoardContext context,
                                                         String deviceId,
                                                         DeviceTemplateDto.DeviceManifest manifest,
                                                         List<StateTuple> legalStates) {
        if (legalStates == null || legalStates.isEmpty()) {
            return List.of();
        }
        DeviceNodeDto node = context == null ? null : context.nodesById().get(trimToNull(deviceId));
        List<StateTuple> initial = matchingStateTuples(
                manifest,
                legalStates,
                node != null && hasText(node.getState()) ? node.getState() : manifest.getInitState());
        if (initial.isEmpty() && node != null && hasText(node.getState()) && hasText(manifest.getInitState())) {
            initial = matchingStateTuples(manifest, legalStates, manifest.getInitState());
        }
        if (initial.isEmpty()) {
            return legalStates;
        }

        Set<StateTuple> reachable = new LinkedHashSet<>(initial);
        boolean changed;
        do {
            changed = false;
            List<StateTuple> snapshot = List.copyOf(reachable);
            for (StateTuple source : snapshot) {
                for (DeviceTemplateDto.DeviceManifest.API api : safeList(manifest.getApis())) {
                    if (api == null || !hasText(api.getEndState())
                            || !matchesStatePattern(manifest, source, api.getStartState())) {
                        continue;
                    }
                    changed |= reachable.addAll(applyStateTarget(
                            manifest, legalStates, source, api.getEndState()));
                }
                for (DeviceTemplateDto.DeviceManifest.Transition transition : safeList(manifest.getTransitions())) {
                    if (transition == null || !hasText(transition.getEndState())
                            || !matchesStatePattern(manifest, source, transition.getStartState())) {
                        continue;
                    }
                    // Trigger feasibility is deliberately over-approximated: reject only states
                    // that remain unreachable even if every declared transition may fire.
                    changed |= reachable.addAll(applyStateTarget(
                            manifest, legalStates, source, transition.getEndState()));
                }
            }
        } while (changed && reachable.size() < legalStates.size());
        return List.copyOf(reachable);
    }

    private static List<StateTuple> matchingStateTuples(DeviceTemplateDto.DeviceManifest manifest,
                                                        List<StateTuple> legalStates,
                                                        String statePattern) {
        if (!hasText(statePattern) || "_".equals(statePattern.trim())) {
            return List.of();
        }
        return legalStates.stream()
                .filter(tuple -> stateCandidateMatches(manifest, tuple, statePattern))
                .toList();
    }

    private static boolean matchesStatePattern(DeviceTemplateDto.DeviceManifest manifest,
                                               StateTuple source,
                                               String statePattern) {
        return !hasText(statePattern) || "_".equals(statePattern.trim())
                || stateCandidateMatches(manifest, source, statePattern);
    }

    private static List<StateTuple> applyStateTarget(DeviceTemplateDto.DeviceManifest manifest,
                                                     List<StateTuple> legalStates,
                                                     StateTuple source,
                                                     String endState) {
        List<String> modes = modeNames(manifest);
        if (modes.isEmpty() || !hasText(endState)) return List.of();
        String[] parts = endState.split(";", -1);
        if (parts.length > modes.size()) return List.of();
        Map<String, String> target = new LinkedHashMap<>(source.valuesByMode());
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            if (DeviceSmvDataFactory.isWildcardStateSegment(parts[i])) continue;
            String value = DeviceSmvDataFactory.cleanStateName(parts[i]);
            if (!hasText(value)) return List.of();
            target.put(modes.get(i), value);
            changed = true;
        }
        if (!changed) return List.of();
        return legalStates.stream()
                .filter(tuple -> tuple.valuesByMode().equals(target))
                .toList();
    }

    /**
     * Returns a finite over-approximation only when the variable is provably closed.
     * A null result means that natural change or missing runtime data keeps the full
     * declared domain reachable for validation purposes.
     */
    private static Set<String> provablyReachableVariableValues(
            BoardContext context,
            String deviceId,
            DeviceTemplateDto owningTemplate,
            DeviceTemplateDto.DeviceManifest.InternalVariable variable) {
        // A shared environment variable is never frozen at its pool value: the generated model gives
        // every enum environment variable a final `TRUE: {<all declared values>}` branch, so the
        // pool value is only `init` and any declared value is reachable on the first step. Narrowing
        // these the way local variables are narrowed rejected the platform's core requests — "alarm
        // when smoke is detected" while the pool reads `clear` — as provably dead.
        //
        // The narrowing stays for IsInside=true locals, which the model really does hold constant
        // (`TRUE: <device>.<var>`) when nothing writes them.
        if (!Boolean.TRUE.equals(variable.getIsInside())) {
            return null;
        }
        String currentValue = currentVariableValue(context, deviceId, variable);
        if (!hasText(currentValue) || hasNonZeroRate(variable.getNaturalChangeRate())) {
            return null;
        }

        Set<String> reachable = new LinkedHashSet<>();
        if (!addReachableVariableValue(reachable, variable, currentValue)) {
            return null;
        }
        Collection<DeviceTemplateDto> relevantTemplates = Boolean.TRUE.equals(variable.getIsInside())
                ? List.of(owningTemplate)
                : activeTemplates(context);
        for (DeviceTemplateDto template : relevantTemplates) {
            if (template == null || template.getManifest() == null) continue;
            DeviceTemplateDto.DeviceManifest manifest = template.getManifest();
            for (DeviceTemplateDto.DeviceManifest.WorkingState state : safeList(manifest.getWorkingStates())) {
                if (state == null) continue;
                for (DeviceTemplateDto.DeviceManifest.Dynamic dynamic : safeList(state.getDynamics())) {
                    if (dynamic == null || !sameKey(variable.getName(), dynamic.getVariableName())) continue;
                    if (hasNonZeroRate(dynamic.getChangeRate())) return null;
                    if (hasText(dynamic.getValue())) {
                        addReachableVariableValue(reachable, variable, dynamic.getValue());
                    }
                }
            }
            for (DeviceTemplateDto.DeviceManifest.Transition transition : safeList(manifest.getTransitions())) {
                if (transition == null) continue;
                for (DeviceTemplateDto.DeviceManifest.Assignment assignment : safeList(transition.getAssignments())) {
                    if (assignment != null && sameKey(variable.getName(), assignment.getAttribute())
                            && hasText(assignment.getValue())) {
                        addReachableVariableValue(reachable, variable, assignment.getValue());
                    }
                }
            }
        }
        return reachable;
    }

    private static String currentVariableValue(BoardContext context,
                                               String deviceId,
                                               DeviceTemplateDto.DeviceManifest.InternalVariable variable) {
        if (context == null || variable == null || !hasText(variable.getName())) return null;
        if (!Boolean.TRUE.equals(variable.getIsInside())) {
            BoardEnvironmentVariableDto environment = context.environmentByName().get(normalizeKey(variable.getName()));
            return environment == null ? null : environment.getValue();
        }
        DeviceNodeDto node = context.nodesById().get(trimToNull(deviceId));
        if (node == null) return null;
        for (VariableStateDto value : safeList(node.getVariables())) {
            if (value != null && sameKey(variable.getName(), value.getName())) {
                return value.getValue();
            }
        }
        return null;
    }

    private static Collection<DeviceTemplateDto> activeTemplates(BoardContext context) {
        if (context == null) return List.of();
        Map<String, DeviceTemplateDto> active = new LinkedHashMap<>();
        for (DeviceNodeDto node : context.nodesById().values()) {
            if (node == null || !hasText(node.getTemplateName())) continue;
            DeviceTemplateDto template = context.templatesByName().get(normalizeKey(node.getTemplateName()));
            if (template != null) {
                active.putIfAbsent(normalizeKey(node.getTemplateName()), template);
            }
        }
        return active.values();
    }

    private static boolean addReachableVariableValue(
            Set<String> target,
            DeviceTemplateDto.DeviceManifest.InternalVariable variable,
            String rawValue) {
        if (!hasText(rawValue)) return false;
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            String normalized = cleanEnumLiteral(rawValue).toLowerCase(Locale.ROOT);
            boolean legal = variable.getValues().stream()
                    .filter(BoardSemanticValidator::hasText)
                    .map(ManifestConditionSemantics::cleanEnumLiteral)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(normalized::equals);
            if (!legal) return false;
            target.add(normalized);
            return true;
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            if (variable.getLowerBound() == null || variable.getUpperBound() == null
                    || value < variable.getLowerBound() || value > variable.getUpperBound()) {
                return false;
            }
            target.add(Integer.toString(value));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean hasNonZeroRate(String rawRate) {
        if (!hasText(rawRate)) return false;
        String value = rawRate.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        String[] parts = value.split(",", -1);
        if (parts.length == 0) return true;
        for (String part : parts) {
            try {
                if (Integer.parseInt(part.trim()) != 0) return true;
            } catch (NumberFormatException ignored) {
                // Unknown rate syntax must keep reachability open rather than reject a valid recommendation.
                return true;
            }
        }
        return false;
    }

    private static boolean sameKey(String left, String right) {
        return hasText(left) && hasText(right) && left.trim().equals(right.trim());
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record StateTuple(Map<String, String> valuesByMode) {
    }

    private static DeviceTemplateDto templateForDevice(BoardContext context, String deviceId) {
        if (context == null || !context.hasTemplateData() || !hasText(deviceId)) {
            return null;
        }
        DeviceNodeDto node = context.nodesById().get(deviceId.trim());
        if (node == null || !hasText(node.getTemplateName())) {
            return null;
        }
        return context.templatesByName().get(normalizeKey(node.getTemplateName()));
    }

    private static void putTemplateAlias(Map<String, DeviceTemplateDto> templatesByName,
                                         String rawName,
                                         DeviceTemplateDto template) {
        String key = normalizeKey(rawName);
        if (key != null) {
            templatesByName.putIfAbsent(key, template);
        }
    }

    private static String validateVariableValue(String prefix,
                                                DeviceTemplateDto.DeviceManifest.InternalVariable variable,
                                                String relation,
                                                String value) {
        if (relation == null || value == null) {
            return null;
        }
        List<String> candidates = splitValues(value, relation);
        if (candidates.isEmpty()) {
            return prefix + " has an empty value list for variable '" + variable.getName() + "'.";
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            return prefix + " relation '" + relation + "' requires exactly one value.";
        }

        if (variable.getValues() != null && !variable.getValues().isEmpty()
                && !SmvRelationUtils.isEnumRelation(relation)) {
            return prefix + " enum variable '" + variable.getName() + "' supports only =, !=, in, not in.";
        }
        ManifestConditionSemantics.ValueIssue issue =
                ManifestConditionSemantics.firstInvalidVariableValue(variable, candidates);
        if (issue == null) return null;
        return switch (issue.error()) {
            case UNKNOWN_ENUM -> prefix + " illegal enum value '" + issue.candidate()
                    + "' for variable '" + variable.getName() + "'.";
            case OUT_OF_RANGE -> prefix + " variable '" + variable.getName() + "' value '" + issue.candidate()
                    + "' is outside " + variable.getLowerBound() + ".." + variable.getUpperBound() + ".";
            case NOT_INTEGER -> prefix + " variable '" + variable.getName() + "' requires an integer value.";
        };
    }

    private static String validateModeValue(String prefix,
                                            DeviceTemplateDto.DeviceManifest manifest,
                                            String mode,
                                            String relation,
                                            String value) {
        if (!SmvRelationUtils.isEnumRelation(relation)) {
            return prefix + " mode conditions support only =, !=, in, not in.";
        }
        List<String> legalValues = modeValues(manifest).get(mode);
        if (legalValues == null || legalValues.isEmpty()) {
            return prefix + " mode '" + mode + "' has no legal values.";
        }
        List<String> candidates = splitValues(value, relation);
        for (String candidate : candidates) {
            String cleaned = DeviceSmvDataFactory.cleanStateName(candidate);
            if (!hasText(cleaned) || !legalValues.contains(cleaned)) {
                return prefix + " illegal value '" + candidate + "' for mode '" + mode + "'.";
            }
        }
        return null;
    }

    private static String validateStateValue(String prefix,
                                             DeviceTemplateDto.DeviceManifest manifest,
                                             String relation,
                                             String value) {
        if (!SmvRelationUtils.isEnumRelation(relation)) {
            return prefix + " state conditions support only =, !=, in, not in.";
        }
        List<String> modes = modeNames(manifest);
        if (modes.isEmpty()) {
            return prefix + " references state on a template with no modes.";
        }
        List<String> candidates = ("in".equals(relation) || "not in".equals(relation))
                ? SmvRelationUtils.splitStateRuleValues(value, modes.size())
                : List.of(value == null ? "" : value.trim());
        if (candidates.isEmpty()) {
            return prefix + " has an empty state value.";
        }
        if (("=".equals(relation) || "!=".equals(relation)) && candidates.size() != 1) {
            return prefix + " relation '" + relation + "' requires exactly one state value.";
        }
        for (String candidate : candidates) {
            if (!ManifestConditionSemantics.isLegalState(candidate, modeNames(manifest), modeValues(manifest))) {
                return prefix + " illegal state value '" + candidate + "'.";
            }
        }
        return null;
    }

    private static String validateLiteralValue(String prefix,
                                               String relation,
                                               String value,
                                               Set<String> allowed,
                                               String label) {
        if (!SmvRelationUtils.isEnumRelation(relation)) {
            return prefix + " supports only =, !=, in, not in.";
        }
        List<String> candidates = splitValues(value, relation);
        if (candidates.isEmpty()) {
            return prefix + " value must be " + label + ".";
        }
        for (String candidate : candidates) {
            boolean found = ManifestConditionSemantics.matchesLiteral(candidate, allowed);
            if (!found) {
                return prefix + " value must be " + label + ": " + candidate + ".";
            }
        }
        return null;
    }

    private static boolean hasApi(DeviceTemplateDto.DeviceManifest manifest, String apiName) {
        return findApi(manifest, apiName) != null;
    }

    private static boolean hasContent(DeviceTemplateDto.DeviceManifest manifest, String contentName) {
        if (manifest == null || manifest.getContents() == null || !hasText(contentName)) {
            return false;
        }
        String target = contentName.trim();
        for (DeviceTemplateDto.DeviceManifest.Content content : manifest.getContents()) {
            if (content != null && target.equals(content.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String validatePropertyReference(String prefix,
                                                    SpecConditionDto condition,
                                                    DeviceTemplateDto.DeviceManifest manifest) {
        String scope = normalize(condition.getPropertyScope());
        String key = trimToNull(condition.getKey());
        if (!"state".equals(scope) && !"variable".equals(scope)) {
            return prefix + " requires propertyScope='state' or 'variable' for trust/privacy.";
        }
        if ("variable".equals(scope)) {
            // Capability-blind, unlike the condition-source lookup: the device module declares
            // trust_<name>/privacy_<name> for every declared variable, so a property reference to an
            // affect-only value is something the generated model really permits. Asking about a label is
            // not reading the value, and narrowing here would refuse the assistant a specification the
            // UI offers and NuSMV would decide.
            return findDeclaredVariable(manifest, key) == null
                    ? prefix + " references unknown property variable '" + key + "'."
                    : null;
        }
        return resolveModeName(manifest, key) == null
                ? prefix + " references unknown state-label mode '" + key + "'."
                : null;
    }

    private static String resolveModeName(DeviceTemplateDto.DeviceManifest manifest, String rawMode) {
        if (!hasText(rawMode)) {
            return null;
        }
        String trimmed = rawMode.trim();
        String cleaned = DeviceSmvDataFactory.cleanStateName(trimmed);
        for (String mode : modeNames(manifest)) {
            if (mode.equals(trimmed) || mode.equalsIgnoreCase(trimmed) || mode.equals(cleaned)) {
                return mode;
            }
        }
        return null;
    }

    private static List<String> modeNames(DeviceTemplateDto.DeviceManifest manifest) {
        List<String> modes = new ArrayList<>();
        if (manifest == null || manifest.getModes() == null) {
            return modes;
        }
        for (String mode : manifest.getModes()) {
            if (hasText(mode)) {
                modes.add(mode.trim());
            }
        }
        return modes;
    }

    private static Map<String, List<String>> modeValues(DeviceTemplateDto.DeviceManifest manifest) {
        List<String> modes = modeNames(manifest);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String mode : modes) {
            result.put(mode, new ArrayList<>());
        }
        if (manifest == null || manifest.getWorkingStates() == null || modes.isEmpty()) {
            return result;
        }

        for (DeviceTemplateDto.DeviceManifest.WorkingState state : manifest.getWorkingStates()) {
            if (state == null || !hasText(state.getName())) {
                continue;
            }
            if (modes.size() == 1) {
                addModeValue(result, modes.get(0), state.getName());
                continue;
            }
            String[] parts = state.getName().split(";", -1);
            if (parts.length == modes.size()) {
                for (int i = 0; i < modes.size(); i++) {
                    addModeValue(result, modes.get(i), parts[i]);
                }
            }
        }
        return result;
    }

    private static void addModeValue(Map<String, List<String>> valuesByMode,
                                     String mode,
                                     String rawValue) {
        if (!hasText(rawValue)) {
            return;
        }
        String clean = DeviceSmvDataFactory.cleanStateName(rawValue);
        if (!hasText(clean) || "_".equals(clean)) {
            return;
        }
        List<String> values = valuesByMode.get(mode);
        if (values != null && !values.contains(clean)) {
            values.add(clean);
        }
    }

    private static List<String> splitValues(String value, String relation) {
        if ("in".equals(relation) || "not in".equals(relation)) {
            return SmvRelationUtils.splitRuleValues(value);
        }
        return value == null ? List.of() : List.of(value.trim());
    }

    private static String normalizeKey(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
