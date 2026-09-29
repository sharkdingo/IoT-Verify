package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.util.NaturalChangeRateParser;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueReasonCode;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackSurface;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.GuardProbe;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.PropertyDimension;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SmvMainModuleBuilder {

    /** Thread-safe context carrier for parameterized builds (§5.1/§5.2). Null for normal builds. */
    record ParamCtx(ParameterizationConfig config, Map<RuleDto, Integer> ruleIndexMap) {}

    /** One target-mode branch after applying the board's first-match rule execution order. */
    private record SelectedRuleBranch(
            RuleDto rule,
            String rawCondition,
            String selectedCondition,
            String endState) {}

    private record EarlierRuleAction(Set<Integer> affectedModes, String rawCondition) {}

    /**
     * Build a parameterized SMV main module (for ActFeedback §5 fix strategies).
     */
    public String buildParameterized(Long userId,
                                     List<DeviceVerificationDto> devices,
                                     List<RuleDto> rules,
                                     Map<String, DeviceSmvData> deviceSmvMap,
                                     AttackScenarioDto attackScenario,
                                     boolean enablePrivacy,
                                     ParameterizationConfig config) {
        return buildParameterized(userId, devices, null, rules, deviceSmvMap,
                attackScenario, enablePrivacy, config, SmvGenerationContext.noop());
    }

    public String buildParameterized(Long userId,
                                     List<DeviceVerificationDto> devices,
                                     List<BoardEnvironmentVariableDto> environmentVariables,
                                     List<RuleDto> rules,
                                     Map<String, DeviceSmvData> deviceSmvMap,
                                     AttackScenarioDto attackScenario,
                                     boolean enablePrivacy,
                                     ParameterizationConfig config,
                                     SmvGenerationContext context) {
        Map<RuleDto, Integer> ruleIndexMap = new IdentityHashMap<>();
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                ruleIndexMap.put(rules.get(i), i);
            }
        }
        ParamCtx ctx = new ParamCtx(config, ruleIndexMap);
        return buildInternal(userId, devices, environmentVariables, rules, deviceSmvMap,
                attackScenario, enablePrivacy, ctx, context, List.of());
    }

    private static boolean hasParameterizationFrozenVars(ParamCtx ctx) {
        if (ctx == null || ctx.config() == null) return false;
        ParameterizationConfig c = ctx.config();
        return (c.getParameterizedThresholds() != null && !c.getParameterizedThresholds().isEmpty())
                || (c.getConditionLambdas() != null && !c.getConditionLambdas().isEmpty())
                || (c.getCandidateConditionValues() != null && !c.getCandidateConditionValues().isEmpty());
    }

    private static void appendParameterizationFrozenVars(StringBuilder content, ParamCtx ctx) {
        if (ctx == null || ctx.config() == null) return;
        ParameterizationConfig c = ctx.config();
        if (c.getParameterizedThresholds() != null) {
            for (ParameterizationConfig.ParamInfo param : c.getParameterizedThresholds().values()) {
                content.append("\n\t").append(param.getFrozenVarName())
                        .append(": ").append(param.getLowerBound())
                        .append("..").append(param.getUpperBound()).append(";");
            }
        }
        if (c.getConditionLambdas() != null) {
            for (String lambdaName : c.getConditionLambdas().values()) {
                content.append("\n\t").append(lambdaName).append(": boolean;");
            }
        }
        if (c.getCandidateConditionValues() != null) {
            for (ParameterizationConfig.ConditionValueInfo valueInfo
                    : c.getCandidateConditionValues().values()) {
                content.append("\n\t").append(valueInfo.getFrozenVarName()).append(": ");
                List<String> values = valueInfo.getValues();
                if (values != null && !values.isEmpty()) {
                    Set<String> normalizedValues = values.stream()
                            .filter(Objects::nonNull)
                            .map(value -> value.replace(" ", ""))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (normalizedValues.size() == 2
                            && normalizedValues.stream().anyMatch("TRUE"::equalsIgnoreCase)
                            && normalizedValues.stream().anyMatch("FALSE"::equalsIgnoreCase)) {
                        content.append("boolean;");
                    } else {
                        content.append("{").append(String.join(", ", normalizedValues)).append("};");
                    }
                } else if (valueInfo.getLowerBound() != null && valueInfo.getUpperBound() != null) {
                    content.append(valueInfo.getLowerBound()).append("..")
                            .append(valueInfo.getUpperBound()).append(";");
                } else {
                    throw SmvGenerationException.invalidBuilderInput(
                            "SmvMainModuleBuilder",
                            "candidate condition value domain",
                            "must declare enum values or numeric bounds");
                }
            }
        }
    }

    private static void appendParameterizationInvars(StringBuilder content, ParamCtx ctx) {
        if (ctx == null || ctx.config() == null || ctx.config().getExclusionInvars() == null) return;
        for (String invar : ctx.config().getExclusionInvars()) {
            content.append("\nINVAR ").append(invar).append(";");
        }
    }

    private static void appendParameterizationInitialState(StringBuilder content, ParamCtx ctx) {
        if (ctx == null || ctx.config() == null
                || ctx.config().getInitialStateConstraints() == null) {
            return;
        }
        for (String constraint : ctx.config().getInitialStateConstraints()) {
            if (constraint != null && !constraint.isBlank()) {
                content.append("\nINIT ").append(constraint).append(";");
            }
        }
    }

    private static String automationLinkAttackInitializer(AttackScenarioDto attackScenario, RuleDto rule) {
        if (attackScenario.getMode() == AttackScenarioDto.Mode.ANY_UP_TO_BUDGET) {
            return "{TRUE, FALSE}";
        }
        Long ruleId = rule != null ? rule.getId() : null;
        return ruleId != null && attackScenario.selectedAutomationLinkRuleIds().contains(ruleId)
                ? "TRUE" : "FALSE";
    }

    public String build(Long userId,
                       List<DeviceVerificationDto> devices,
                       List<RuleDto> rules,
                       Map<String, DeviceSmvData> deviceSmvMap,
                       AttackScenarioDto attackScenario,
                       boolean enablePrivacy) {
        return build(userId, devices, null, rules, deviceSmvMap,
                attackScenario, enablePrivacy, SmvGenerationContext.noop());
    }

    public String build(Long userId,
                        List<DeviceVerificationDto> devices,
                        List<RuleDto> rules,
                        Map<String, DeviceSmvData> deviceSmvMap,
                        AttackScenarioDto attackScenario,
                        boolean enablePrivacy,
                        SmvGenerationContext context) {
        return build(userId, devices, null, rules, deviceSmvMap,
                attackScenario, enablePrivacy, context);
    }

    public String build(Long userId,
                       List<DeviceVerificationDto> devices,
                       List<BoardEnvironmentVariableDto> environmentVariables,
                       List<RuleDto> rules,
                       Map<String, DeviceSmvData> deviceSmvMap,
                       AttackScenarioDto attackScenario,
                       boolean enablePrivacy,
                       SmvGenerationContext context) {
        return build(userId, devices, environmentVariables, rules, deviceSmvMap,
                attackScenario, enablePrivacy, context, List.of());
    }

    /** As {@link #build}, and appends {@code guardProbes} as main-module {@code DEFINE}s. */
    public String build(Long userId,
                        List<DeviceVerificationDto> devices,
                        List<BoardEnvironmentVariableDto> environmentVariables,
                        List<RuleDto> rules,
                        Map<String, DeviceSmvData> deviceSmvMap,
                        AttackScenarioDto attackScenario,
                        boolean enablePrivacy,
                        SmvGenerationContext context,
                        List<GuardProbe> guardProbes) {
        return buildInternal(userId, devices, environmentVariables, rules, deviceSmvMap,
                attackScenario, enablePrivacy, null, context, guardProbes);
    }

    private String buildInternal(Long userId,
                                  List<DeviceVerificationDto> devices,
                                  List<BoardEnvironmentVariableDto> environmentVariables,
                                  List<RuleDto> rules,
                                  Map<String, DeviceSmvData> deviceSmvMap,
                                  AttackScenarioDto attackScenario,
                                  boolean enablePrivacy,
                                  ParamCtx paramCtx,
                                  SmvGenerationContext context,
                                  List<GuardProbe> guardProbes) {
        List<GuardProbe> safeGuardProbes = guardProbes != null ? guardProbes : List.of();

        if (attackScenario == null) {
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvMainModuleBuilder", "attackScenario", "must not be null");
        }
        AttackScenarioDto safeAttackScenario = attackScenario;
        boolean isAttack = safeAttackScenario.isEnabled();
        int attackBudget = safeAttackScenario.effectiveBudget();

        // Parameter validation
        if (devices == null) {
            log.error("SmvMainModuleBuilder.build: devices must not be null");
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvMainModuleBuilder",
                    "devices",
                    "must not be null");
        }
        if (deviceSmvMap == null) {
            log.error("SmvMainModuleBuilder.build: deviceSmvMap must not be null");
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvMainModuleBuilder",
                    "deviceSmvMap",
                    "must not be null");
        }

        paramCtx = ensureRuleIndexMap(paramCtx, rules);
        Map<String, DeviceManifest.InternalVariable> environmentDomains =
                collectEnvironmentDomains(devices, deviceSmvMap);
        AttackSurface attackSurface = AttackSurface.analyze(rules, deviceSmvMap);
        validateMainNamespace(devices, rules, deviceSmvMap, environmentDomains, isAttack, paramCtx,
                safeGuardProbes);

        StringBuilder content = new StringBuilder();

        content.append("\nMODULE main");

        // The request supplies a maximum compromised-point budget. The generated
        // counter records the actual number selected in a model branch.
        boolean hasFrozenVar = isAttack || hasParameterizationFrozenVars(paramCtx);
        if (hasFrozenVar) {
            content.append("\nFROZENVAR");
            if (isAttack) {
                int attackPointCount = attackSurface.totalCount();
                content.append("\n\t").append(SmvConstants.NUSMV_COMPROMISED_POINT_COUNT)
                        .append(": 0..").append(attackPointCount).append(";");
                if (rules != null) {
                    for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                        content.append("\n\t").append(SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX)
                                .append(ruleIndex).append(": boolean;");
                    }
                }
            }
            appendParameterizationFrozenVars(content, paramCtx);
        }
        if (safeAttackScenario.getMode() == AttackScenarioDto.Mode.ANY_UP_TO_BUDGET) {
            content.append("\nINVAR ").append(SmvConstants.NUSMV_COMPROMISED_POINT_COUNT)
                    .append(" <= ").append(attackBudget).append(";");
        }
        appendParameterizationInvars(content, paramCtx);

        content.append("\nVAR");

        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null) continue;

            String moduleName = smv.getModuleName();
            String varName = smv.getVarName();
            content.append("\n\t").append(varName).append(": ").append(moduleName).append(";");
        }

        Set<String> declaredEnvVars = new HashSet<>();
        for (Map.Entry<String, DeviceManifest.InternalVariable> env : environmentDomains.entrySet()) {
            String varName = env.getKey();
            DeviceManifest.InternalVariable var = env.getValue();
            if (var == null || declaredEnvVars.contains(varName)) {
                continue;
            }
            declaredEnvVars.add(varName);
            content.append("\n\ta_").append(varName).append(": ");
            if (var.getValues() != null && !var.getValues().isEmpty()) {
                List<String> cleanValues = new ArrayList<>();
                for (String v : var.getValues()) {
                    cleanValues.add(v.replace(" ", ""));
                }
                content.append("{").append(String.join(", ", cleanValues)).append("};");
            } else if (var.getLowerBound() != null && var.getUpperBound() != null) {
                int lower = var.getLowerBound();
                int upper = var.getUpperBound();
                content.append(lower).append("..").append(upper).append(";");
            } else {
                throw SmvGenerationException.templateInvalid("environment",
                        "Environment variable '" + varName + "' has no declared value domain");
            }
        }

        if (rules != null) {
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                content.append("\n\t").append(SmvConstants.RULE_EXECUTION_PROBE_PREFIX)
                        .append(ruleIndex).append(": boolean;");
            }
        }

        appendParameterizationInitialState(content, paramCtx);

        Map<String, String> envVarInitValues = resolveEnvironmentPoolInitValues(
                environmentVariables, environmentDomains);

        content.append("\nASSIGN");

        // Env variable init assignments
        for (Map.Entry<String, String> entry : envVarInitValues.entrySet()) {
            content.append("\n\tinit(a_").append(entry.getKey()).append(") := ")
                   .append(entry.getValue()).append(";");
        }

        if (isAttack) {
            if (rules != null) {
                for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                    content.append("\n\tinit(").append(SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX)
                            .append(ruleIndex).append(") := ")
                            .append(automationLinkAttackInitializer(safeAttackScenario, rules.get(ruleIndex)))
                            .append(";");
                }
            }
            content.append("\n\tinit(").append(SmvConstants.NUSMV_COMPROMISED_POINT_COUNT)
                    .append(") := 0");
            for (DeviceVerificationDto device : devices) {
                DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
                if (smv != null && attackSurface.includesDevice(smv.getVarName())) {
                    String varName = smv.getVarName();
                    content.append(" + toint(").append(varName).append(".is_attack)");
                }
            }
            if (rules != null) {
                for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                    content.append(" + toint(").append(SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX)
                            .append(ruleIndex).append(")");
                }
            }
            content.append(";");
        }

        appendStateTransitions(content, devices, rules, deviceSmvMap, isAttack, paramCtx, context);
        appendEnvTransitions(content, devices, deviceSmvMap, environmentDomains);
        appendApiSignalTransitions(content, devices, deviceSmvMap);
        appendPropertyTransitions(content, devices, rules, deviceSmvMap, isAttack, PropertyDimension.TRUST, paramCtx, context);
        if (enablePrivacy) {
            appendPropertyTransitions(content, devices, rules, deviceSmvMap, isAttack, PropertyDimension.PRIVACY, paramCtx, context);
        }
        appendVariablePropertyTransitions(content, devices, deviceSmvMap, PropertyDimension.TRUST, isAttack);
        if (enablePrivacy) {
            appendVariablePropertyTransitions(content, devices, deviceSmvMap, PropertyDimension.PRIVACY, isAttack);
        }
        // Impact rates are DEFINEs inside each device module, so main emits no rate transition.
        appendExternalVariableAssignments(content, devices, deviceSmvMap, isAttack);
        appendInternalVariableTransitions(content, devices, deviceSmvMap, isAttack);
        appendRuleExecutionProbeAssignments(content, rules, deviceSmvMap, isAttack, paramCtx, context);
        appendGuardProbes(content, safeGuardProbes, deviceSmvMap, isAttack, paramCtx);

        return content.toString();
    }

    private void validateMainNamespace(List<DeviceVerificationDto> devices,
                                       List<RuleDto> rules,
                                       Map<String, DeviceSmvData> deviceSmvMap,
                                       Map<String, DeviceManifest.InternalVariable> environmentDomains,
                                       boolean isAttack,
                                       ParamCtx paramCtx,
                                       List<GuardProbe> guardProbes) {
        Map<String, String> identifiers = new LinkedHashMap<>();
        for (GuardProbe probe : guardProbes) {
            registerMainIdentifier(identifiers, probe.name(), "generated guard probe");
        }
        if (isAttack) {
            registerMainIdentifier(identifiers, SmvConstants.NUSMV_COMPROMISED_POINT_COUNT,
                    "generated compromised-point counter");
        }
        if (paramCtx != null && paramCtx.config() != null) {
            ParameterizationConfig config = paramCtx.config();
            if (config.getParameterizedThresholds() != null) {
                for (ParameterizationConfig.ParamInfo param : config.getParameterizedThresholds().values()) {
                    if (param != null) {
                        registerMainIdentifier(identifiers, param.getFrozenVarName(),
                                "generated fix parameter");
                    }
                }
            }
            if (config.getConditionLambdas() != null) {
                for (String lambdaName : config.getConditionLambdas().values()) {
                    registerMainIdentifier(identifiers, lambdaName, "generated fix condition lambda");
                }
            }
            if (config.getCandidateConditionValues() != null) {
                for (ParameterizationConfig.ConditionValueInfo valueInfo
                        : config.getCandidateConditionValues().values()) {
                    if (valueInfo != null) {
                        registerMainIdentifier(identifiers, valueInfo.getFrozenVarName(),
                                "generated fix condition value");
                    }
                }
            }
        }

        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null) {
                continue;
            }
            registerMainIdentifier(identifiers, smv.getVarName(), "device instance '" + smv.getVarName() + "'");
        }

        for (String envName : environmentDomains.keySet()) {
            registerMainIdentifier(identifiers, "a_" + envName,
                    "generated environment variable for '" + envName + "'");
        }
        if (rules != null) {
            for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
                registerMainIdentifier(identifiers, SmvConstants.RULE_EXECUTION_PROBE_PREFIX + ruleIndex,
                        "generated rule execution probe");
                if (isAttack) {
                    registerMainIdentifier(identifiers, SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX + ruleIndex,
                            "generated automation-link attack choice");
                }
            }
        }
    }

    private ParamCtx ensureRuleIndexMap(ParamCtx current, List<RuleDto> rules) {
        if (current != null && current.ruleIndexMap() != null) {
            return current;
        }
        Map<RuleDto, Integer> indexes = new IdentityHashMap<>();
        if (rules != null) {
            for (int i = 0; i < rules.size(); i++) {
                indexes.put(rules.get(i), i);
            }
        }
        return new ParamCtx(current != null ? current.config() : null, indexes);
    }

    /*
     * The generated-name half of the main namespace — a backstop, not the first line of defence.
     *
     * `NusmvRequestValidator.validateMainNamespace` runs on every verify and simulate request and now also
     * reserves the fix-generated prefixes (`SmvConstants.FIX_GENERATED_NAME_PREFIXES`) against device names, so a
     * user learns about the clash at their first verification rather than when they later ask for a fix. Before
     * that reservation existed, a device named `condition_value_r0_c1` saved and verified fine — confirmed
     * against the running API — and only this throw stopped it, from a surface the user was not editing.
     *
     * This check stays because the exact names still are not known until a strategy has chosen them: only here
     * can `param_<hash>` be compared against everything else in the module. It fails closed, naming the
     * identifier and both colliding sources, rather than emitting a model where one name means two things.
     */
    private void registerMainIdentifier(Map<String, String> identifiers, String identifier, String source) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        String previous = identifiers.putIfAbsent(normalized, source);
        if (previous != null) {
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvMainModuleBuilder",
                    "main namespace",
                    "identifier '" + identifier + "' from " + source + " collides with " + previous);
        }
    }

    /**
     * Assign external (IsInside=false) variables using simple assignment in main module.
     * e.g. Thermostat.temperature := a_temperature; The device module must not use init() for these.
     */
    private void appendExternalVariableAssignments(StringBuilder content,
                                                   List<DeviceVerificationDto> devices,
                                                   Map<String, DeviceSmvData> deviceSmvMap,
                                                   boolean isAttack) {
        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null || smv.getManifest() == null) continue;

            String varName = smv.getVarName();

            if (smv.getManifest().getInternalVariables() != null) {
                for (DeviceManifest.InternalVariable var : smv.getManifest().getInternalVariables()) {
                    // A read mirror exists only for a value the device actually reads. An affect-only
                    // declaration (Reads=false) contributes a domain and an effect, not a reading, so
                    // mirroring it would hand the device a value it never observes.
                    if ((var.getIsInside() == null || !var.getIsInside())
                            && !Boolean.FALSE.equals(var.getReads())) {
                        content.append("\n\t").append(varName).append(".").append(var.getName()).append(" := ");
                        if (isAttack && Boolean.TRUE.equals(var.getFalsifiableWhenCompromised())) {
                            content.append("\n\tcase\n")
                                    .append("\t\t").append(varName).append(".is_attack=TRUE: ")
                                    .append(variableDomainExpression(var)).append(";\n")
                                    .append("\t\tTRUE: a_").append(var.getName()).append(";\n")
                                    .append("\tesac;");
                        } else {
                            content.append("a_").append(var.getName()).append(";");
                        }
                    }
                }
            }
        }
    }

    private Map<String, List<RuleDto>> groupRulesByResolvedTarget(List<RuleDto> rules,
                                                                   Map<String, DeviceSmvData> deviceSmvMap) {
        Map<String, List<RuleDto>> rulesByTarget = new LinkedHashMap<>();
        if (rules == null) {
            return rulesByTarget;
        }
        for (RuleDto rule : rules) {
            if (rule == null || rule.getCommand() == null) {
                continue;
            }
            String requestedTarget = rule.getCommand().getDeviceName();
            if (requestedTarget == null || requestedTarget.isBlank()) {
                continue;
            }
            DeviceSmvData targetSmv = DeviceSmvDataFactory.findDeviceSmvDataStrict(requestedTarget, deviceSmvMap);
            if (targetSmv == null) {
                throw SmvGenerationException.deviceNotFound(requestedTarget);
            }
            rulesByTarget.computeIfAbsent(targetSmv.getVarName(), k -> new ArrayList<>()).add(rule);
        }
        return rulesByTarget;
    }

    private List<SelectedRuleBranch> buildSelectedRuleBranches(
            DeviceSmvData targetSmv,
            String targetVarName,
            int modeIndex,
            List<RuleDto> deviceRules,
            Map<String, DeviceSmvData> deviceSmvMap,
            boolean isAttack,
            ParamCtx paramCtx,
            SmvGenerationContext context) {
        if (deviceRules == null || deviceRules.isEmpty()) {
            return List.of();
        }

        List<SelectedRuleBranch> branches = new ArrayList<>();
        List<EarlierRuleAction> earlierActions = new ArrayList<>();
        for (RuleDto rule : deviceRules) {
            if (rule == null || rule.getCommand() == null) {
                continue;
            }
            // This method is the only source of rule branches for state, property, and probe
            // assignments, so a rule that drops out here is absent from the whole model. Skipping it
            // silently returned SATISFIED with modelComplete=true for a scene whose automation was
            // never checked, so an unresolvable action is recorded as a disabled rule instead.
            DeviceManifest.API api = rule.getCommand().getAction() == null ? null
                    : DeviceSmvDataFactory.findApi(
                            targetSmv.getManifest(), rule.getCommand().getAction());
            if (api == null) {
                String reason = "Rule command action could not be resolved to a template API"
                        + " and the rule was disabled during SMV generation";
                log.warn(reason);
                recordDisabledRule(context, rule,
                        ModelGenerationIssueReasonCode.RULE_UNRESOLVABLE_COMMAND_ACTION, reason);
                continue;
            }
            Set<Integer> affectedModes = affectedModeIndexes(targetSmv, api);
            String rawCondition = buildRuleTransitionCondition(rule, targetSmv, deviceSmvMap,
                    targetVarName, api, isAttack, paramCtx, context);
            if (affectedModes.contains(modeIndex)) {
                String endState = getStateForMode(api.getEndState(), modeIndex);
                String selectedCondition = "(" + rawCondition + ")";
                List<String> conflictingEarlierConditions = earlierActions.stream()
                        .filter(earlier -> !Collections.disjoint(earlier.affectedModes(), affectedModes))
                        .map(earlier -> "(" + earlier.rawCondition() + ")")
                        .toList();
                if (!conflictingEarlierConditions.isEmpty()) {
                    selectedCondition += " & !(" + String.join(" | ", conflictingEarlierConditions) + ")";
                }
                branches.add(new SelectedRuleBranch(rule, rawCondition, selectedCondition, endState));
            }
            earlierActions.add(new EarlierRuleAction(affectedModes, rawCondition));
        }
        return branches;
    }

    private Set<Integer> affectedModeIndexes(DeviceSmvData targetSmv, DeviceManifest.API api) {
        Set<Integer> result = new LinkedHashSet<>();
        if (targetSmv.getModes() == null) {
            return result;
        }
        for (int modeIndex = 0; modeIndex < targetSmv.getModes().size(); modeIndex++) {
            String endState = getStateForMode(api.getEndState(), modeIndex);
            if (endState != null && !endState.isEmpty()) {
                result.add(modeIndex);
            }
        }
        return result;
    }

    private void appendStateTransitions(StringBuilder content,
                                       List<DeviceVerificationDto> devices,
                                       List<RuleDto> rules,
                                       Map<String, DeviceSmvData> deviceSmvMap,
                                       boolean isAttack,
                                       ParamCtx paramCtx,
                                       SmvGenerationContext context) {
        Map<String, List<RuleDto>> rulesByTarget = groupRulesByResolvedTarget(rules, deviceSmvMap);

        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null || smv.getModes() == null) continue;

            String varName = smv.getVarName();
            List<RuleDto> deviceRules = rulesByTarget.get(varName);

            if (!smv.getModes().isEmpty()) {
                for (int modeIdx = 0; modeIdx < smv.getModes().size(); modeIdx++) {
                    String mode = smv.getModes().get(modeIdx);
                    List<String> modeStates = smv.getModeStates().get(mode);
                    if (modeStates == null || modeStates.isEmpty()) continue;

                    content.append("\n\tnext(").append(varName).append(".").append(mode).append(") :=\n");
                    content.append("\tcase\n");

                    for (SelectedRuleBranch branch : buildSelectedRuleBranches(
                            smv, varName, modeIdx, deviceRules, deviceSmvMap,
                            isAttack, paramCtx, context)) {
                        String stateCondition = branch.selectedCondition().equals("(" + branch.rawCondition() + ")")
                                ? branch.rawCondition()
                                : branch.selectedCondition();
                        content.append("\t\t").append(stateCondition)
                                .append(": ").append(branch.endState()).append(";\n");
                    }

                    if (smv.getManifest() != null && smv.getManifest().getTransitions() != null) {
                        for (DeviceManifest.Transition trans : smv.getManifest().getTransitions()) {
                            if (trans.getEndState() == null) continue;
                            String endState = getStateForMode(trans.getEndState(), modeIdx);
                            if (endState == null || endState.isEmpty()) continue;

                            DeviceManifest.Trigger trigger = trans.getTrigger();
                            if (trigger != null) {
                                content.append("\t\t")
                                        .append(buildTransitionTriggerCondition(smv, varName, trans))
                                        .append(": ").append(endState).append(";\n");
                            }
                        }
                    }

                    content.append("\t\tTRUE: ").append(varName).append(".").append(mode).append(";\n");
                    content.append("\tesac;");
                }
            }
        }
    }

    private void appendRuleConditions(StringBuilder content, RuleDto rule, Map<String, DeviceSmvData> deviceSmvMap,
                                      boolean useNext, String transitionTargetVarName, ParamCtx paramCtx,
                                      SmvGenerationContext context) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            String reason = "Rule has no trigger conditions and was disabled during SMV generation";
            log.warn(reason);
            recordDisabledRule(context, rule,
                    ModelGenerationIssueReasonCode.RULE_NO_TRIGGER_CONDITIONS, reason);
            content.append("FALSE");
            return;
        }

        Integer ruleIdx = (paramCtx != null && paramCtx.ruleIndexMap() != null)
                ? paramCtx.ruleIndexMap().get(rule) : null;
        ParameterizationConfig cfg = (paramCtx != null) ? paramCtx.config() : null;

        List<String> parts = new ArrayList<>();
        for (int condIdx = 0; condIdx < rule.getConditions().size(); condIdx++) {
            RuleDto.Condition condition = rule.getConditions().get(condIdx);
            if (condition == null) {
                // fail-closed: malformed condition entry disables this rule
                String reason = "Rule contains a null condition entry and was disabled";
                log.warn("{} - rule will never fire", reason);
                recordDisabledRule(context, rule,
                        ModelGenerationIssueReasonCode.RULE_NULL_TRIGGER_CONDITION, reason);
                content.append("FALSE");
                return;
            }

            String part = buildSingleCondition(condition, deviceSmvMap, useNext, transitionTargetVarName,
                    ruleIdx, condIdx, cfg);
            if (part != null && !part.isEmpty()) {
                // §5.2: wrap with lambda guard if configured
                String lambdaKey = (ruleIdx != null) ? "r" + ruleIdx + "_c" + condIdx : null;
                if (lambdaKey != null && cfg != null
                        && cfg.getConditionLambdas() != null
                        && cfg.getConditionLambdas().containsKey(lambdaKey)) {
                    String lambdaName = cfg.getConditionLambdas().get(lambdaKey);
                    part = "(!" + lambdaName + " | (" + part + "))";
                }
                parts.add(part);
            } else {
                // fail-closed: unresolved condition disables this rule
                String technicalReason = "Rule condition on device '" + condition.getDeviceName()
                        + "' attribute '" + condition.getAttribute() + "' could not be resolved";
                log.warn("{} - rule will never fire", technicalReason);
                recordDisabledRule(context, rule,
                        ModelGenerationIssueReasonCode.RULE_UNRESOLVABLE_TRIGGER_CONDITION,
                        "A trigger condition on '" + condition.getAttribute()
                                + "' could not be represented in the generated model.");
                content.append("FALSE");
                return;
            }
        }

        if (parts.isEmpty()) {
            // fail-closed: non-empty input but no resolvable condition
            String reason = "Rule has non-empty condition list but no resolvable condition";
            log.warn("{} - rule will never fire", reason);
            recordDisabledRule(context, rule,
                    ModelGenerationIssueReasonCode.RULE_NO_RESOLVABLE_TRIGGER_CONDITIONS, reason);
            content.append("FALSE");
        } else {
            content.append(String.join(" & ", parts));
        }
    }

    private String variableDomainExpression(DeviceManifest.InternalVariable variable) {
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            List<String> values = variable.getValues().stream()
                    .map(value -> value.replace(" ", ""))
                    .toList();
            return "{" + String.join(", ", values) + "}";
        }
        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            return variable.getLowerBound() + ".." + variable.getUpperBound();
        }
        return "{TRUE, FALSE}";
    }

    private String buildRuleTransitionCondition(RuleDto rule,
                                                DeviceSmvData targetSmv,
                                                Map<String, DeviceSmvData> deviceSmvMap,
                                                String targetVarName,
                                                DeviceManifest.API api,
                                                boolean isAttack,
                                                ParamCtx paramCtx,
                                                SmvGenerationContext context) {
        StringBuilder condition = new StringBuilder();
        // User-authored IF conditions describe the source state. The command produces the next state.
        appendRuleConditions(condition, rule, deviceSmvMap, false, targetVarName, paramCtx, context);
        appendRuleBaseTerms(condition, rule, targetSmv, targetVarName, api, isAttack, paramCtx);
        return condition.toString();
    }

    /** The guard terms besides the trigger conditions, each prefixed with {@code " & "}. */
    private void appendRuleBaseTerms(StringBuilder condition,
                                     RuleDto rule,
                                     DeviceSmvData targetSmv,
                                     String targetVarName,
                                     DeviceManifest.API api,
                                     boolean isAttack,
                                     ParamCtx paramCtx) {
        if (targetSmv.getModes() != null) {
            for (int modeIndex = 0; modeIndex < targetSmv.getModes().size(); modeIndex++) {
                String startState = getStateForMode(api.getStartState(), modeIndex, true);
                if (startState != null && !startState.isEmpty()) {
                    condition.append(" & ").append(targetVarName).append(".")
                            .append(targetSmv.getModes().get(modeIndex)).append("=").append(startState);
                }
            }
        }
        if (isAttack) {
            appendCommandDeliveryGuard(condition, targetVarName, rule, paramCtx);
        }
    }

    private void appendCommandDeliveryGuard(StringBuilder content,
                                            String targetVarName,
                                            RuleDto rule,
                                            ParamCtx paramCtx) {
        content.append(" & ").append(targetVarName).append(".is_attack=FALSE");
        Integer ruleIndex = paramCtx != null && paramCtx.ruleIndexMap() != null
                ? paramCtx.ruleIndexMap().get(rule)
                : null;
        if (ruleIndex != null) {
            content.append(" & ").append(SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX)
                    .append(ruleIndex).append("=FALSE");
        }
    }

    /**
     * Records which ordered rule branch produced the transition into the next trace state.
     * The probes are deterministic functions of existing model state and therefore do not add behavior.
     */
    private void appendRuleExecutionProbeAssignments(StringBuilder content,
                                                     List<RuleDto> rules,
                                                     Map<String, DeviceSmvData> deviceSmvMap,
                                                     boolean isAttack,
                                                     ParamCtx paramCtx,
                                                     SmvGenerationContext context) {
        if (rules == null || rules.isEmpty()) {
            return;
        }

        Map<RuleDto, Integer> ruleIndexes = new IdentityHashMap<>();
        Map<Integer, Set<String>> selectedBranchesByRule = new LinkedHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            ruleIndexes.put(rules.get(i), i);
            selectedBranchesByRule.put(i, new LinkedHashSet<>());
        }

        Map<String, List<RuleDto>> rulesByTarget = groupRulesByResolvedTarget(rules, deviceSmvMap);
        for (Map.Entry<String, List<RuleDto>> entry : rulesByTarget.entrySet()) {
            String targetVarName = entry.getKey();
            DeviceSmvData targetSmv = deviceSmvMap.get(targetVarName);
            if (targetSmv == null || targetSmv.getModes() == null) {
                continue;
            }

            for (int modeIndex = 0; modeIndex < targetSmv.getModes().size(); modeIndex++) {
                for (SelectedRuleBranch branch : buildSelectedRuleBranches(
                        targetSmv, targetVarName, modeIndex, entry.getValue(), deviceSmvMap,
                        isAttack, paramCtx, context)) {
                    Integer ruleIndex = ruleIndexes.get(branch.rule());
                    if (ruleIndex != null) {
                        selectedBranchesByRule.get(ruleIndex).add(branch.selectedCondition());
                    }
                }
            }
        }

        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            String probeName = SmvConstants.RULE_EXECUTION_PROBE_PREFIX + ruleIndex;
            Set<String> selectedBranches = selectedBranchesByRule.get(ruleIndex);
            String expression = selectedBranches == null || selectedBranches.isEmpty()
                    ? "FALSE"
                    : (selectedBranches.size() == 1
                    ? selectedBranches.iterator().next()
                    : "(" + String.join(" | ", selectedBranches) + ")");
            content.append("\n\tinit(").append(probeName).append(") := FALSE;");
            content.append("\n\tnext(").append(probeName).append(") := ")
                    .append(expression).append(";");
        }
    }

    /**
     * Emits each probe as a {@code DEFINE}, rendered by the code that renders the guard component it
     * reports. A probe that cannot be rendered is omitted rather than defined as a constant: its reader
     * treats a missing value as unknown, whereas a stand-in would read as a fact about the model.
     */
    private void appendGuardProbes(StringBuilder content,
                                   List<GuardProbe> guardProbes,
                                   Map<String, DeviceSmvData> deviceSmvMap,
                                   boolean isAttack,
                                   ParamCtx paramCtx) {
        List<String> definitions = new ArrayList<>();
        for (GuardProbe probe : guardProbes) {
            String expression = guardProbeExpression(probe, deviceSmvMap, isAttack, paramCtx);
            if (expression == null || expression.isBlank()) {
                log.debug("Guard probe {} ({}) could not be rendered and is omitted", probe.name(), probe.kind());
                continue;
            }
            definitions.add("\n\t" + probe.name() + " := " + expression + ";");
        }
        if (!definitions.isEmpty()) {
            content.append("\nDEFINE");
            definitions.forEach(content::append);
        }
    }

    private String guardProbeExpression(GuardProbe probe,
                                        Map<String, DeviceSmvData> deviceSmvMap,
                                        boolean isAttack,
                                        ParamCtx paramCtx) {
        // A probe reports a guard of this model, so it may only name one of this model's rules; the
        // delivery guard's link term is keyed by the rule's position among them.
        if (probe.rule() != null && !paramCtx.ruleIndexMap().containsKey(probe.rule())) {
            return null;
        }
        // Rendering never records into the generation context: the guard itself already reported
        // whatever made it unresolvable, and a probe must not count that rule as disabled twice.
        return switch (probe.kind()) {
            case RULE_BASE -> ruleBaseProbeExpression(probe.rule(), deviceSmvMap, isAttack, paramCtx);
            case RULE_CONDITIONS -> {
                StringBuilder conditions = new StringBuilder();
                appendRuleConditions(conditions, probe.rule(), deviceSmvMap, false, null, paramCtx,
                        SmvGenerationContext.noop());
                yield conditions.toString();
            }
            case CONDITION -> buildSingleCondition(probe.condition(), deviceSmvMap, false, null,
                    null, 0, null);
            case OPERAND -> conditionOperand(probe.condition(), deviceSmvMap);
        };
    }

    private String ruleBaseProbeExpression(RuleDto rule,
                                           Map<String, DeviceSmvData> deviceSmvMap,
                                           boolean isAttack,
                                           ParamCtx paramCtx) {
        if (rule.getCommand() == null || rule.getCommand().getDeviceName() == null
                || rule.getCommand().getAction() == null) {
            return null;
        }
        DeviceSmvData targetSmv = DeviceSmvDataFactory.findDeviceSmvDataStrict(
                rule.getCommand().getDeviceName(), deviceSmvMap);
        if (targetSmv == null) {
            return null;
        }
        DeviceManifest.API api = DeviceSmvDataFactory.findApi(targetSmv.getManifest(), rule.getCommand().getAction());
        if (api == null) {
            return null;
        }
        StringBuilder terms = new StringBuilder();
        appendRuleBaseTerms(terms, rule, targetSmv, targetSmv.getVarName(), api, isAttack, paramCtx);
        return terms.isEmpty() ? "TRUE" : terms.substring(" & ".length());
    }

    /**
     * The left-hand side a mode or variable condition compares: the expression
     * {@link #buildSingleCondition} relates to the condition's value.
     */
    private static String conditionOperand(RuleDto.Condition condition, Map<String, DeviceSmvData> deviceSmvMap) {
        if (condition.getAttribute() == null || condition.getTargetType() == null) {
            return null;
        }
        DeviceSmvData smv = DeviceSmvDataFactory.findDeviceSmvDataStrict(condition.getDeviceName(), deviceSmvMap);
        if (smv == null) {
            return null;
        }
        String attr = condition.getAttribute().trim();
        String targetType = condition.getTargetType().trim().toLowerCase(Locale.ROOT);
        boolean resolves = switch (targetType) {
            case "mode" -> smv.getModes() != null && smv.getModes().contains(attr);
            case "variable" -> findInternalVariableByName(smv, attr) != null;
            default -> false;
        };
        return resolves ? smv.getVarName() + "." + attr : null;
    }

    private void recordDisabledRule(SmvGenerationContext context,
                                    RuleDto rule,
                                    ModelGenerationIssueReasonCode reasonCode,
                                    String reason) {
        if (context != null) {
            context.disabledRule(rule, reasonCode, reason);
        }
    }

    private String buildSingleCondition(RuleDto.Condition condition, Map<String, DeviceSmvData> deviceSmvMap,
                                        boolean useNext, String transitionTargetVarName,
                                        Integer ruleIdx, int condIdx, ParameterizationConfig cfg) {
        String deviceId = condition.getDeviceName();
        DeviceSmvData condSmv = DeviceSmvDataFactory.findDeviceSmvDataStrict(deviceId, deviceSmvMap);

        if (condSmv == null) {
            log.warn("Rule condition references unknown device '{}' and cannot be resolved", deviceId);
            return null;
        }

        String varName = condSmv.getVarName();
        boolean effectiveUseNext = useNext;
        if (useNext && transitionTargetVarName != null && transitionTargetVarName.equals(varName)) {
            // Avoid recursively defined next(target.*) when target rules read target state/vars.
            effectiveUseNext = false;
        }
        if (condition.getAttribute() == null || condition.getAttribute().isBlank()) {
            log.warn("Rule condition has null/blank attribute for device '{}' and cannot be resolved", deviceId);
            return null;
        }
        // Trimmed once here because every downstream comparison against a manifest variable, mode,
        // or signal-API name uses equals(), and admission resolves the attribute trimmed. Leaving
        // the two disagreeing rejected a rule the validator had already accepted. Manifest names may
        // contain interior spaces, so only surrounding whitespace is dropped.
        String attr = condition.getAttribute().trim();
        String targetType = condition.getTargetType() != null
                ? condition.getTargetType().trim().toLowerCase(Locale.ROOT)
                : null;
        if (targetType == null || !List.of("api", "variable", "mode", "state").contains(targetType)) {
            log.warn("Rule condition on device '{}' attribute '{}' has missing/unsupported targetType '{}'",
                    deviceId, attr, condition.getTargetType());
            return null;
        }

        if (condition.getRelation() != null) {
            if (condition.getValue() == null || condition.getValue().isBlank()) {
                log.warn("Rule condition has relation '{}' but null/blank value for device '{}' and cannot be resolved",
                        condition.getRelation(), deviceId);
                return null;
            }
            String normalizedRel = normalizeRuleRelation(condition.getRelation());
            if (!isSupportedRuleRelation(normalizedRel)) {
                log.warn("Rule condition has unsupported relation '{}' (normalized '{}') for device '{}'",
                        condition.getRelation(), normalizedRel, deviceId);
                return null;
            }
            if (("in".equals(normalizedRel) || "not in".equals(normalizedRel))
                    && splitRuleValues(condition.getValue()).isEmpty()) {
                log.warn("Rule condition has empty value list for '{}' relation on device '{}'",
                        normalizedRel, deviceId);
                return null;
            }
            if ("state".equals(targetType)) {
                if (!"state".equals(attr)) {
                    log.warn("Rule state condition on device '{}' must use attribute 'state', got '{}'", deviceId, attr);
                    return null;
                }
                return buildRuleStateCondition(condition, condSmv, normalizedRel, effectiveUseNext);
            }

            boolean isModeAttr = "mode".equals(targetType)
                    && condSmv.getModes() != null && condSmv.getModes().contains(attr);
            DeviceManifest.InternalVariable internalVar = "variable".equals(targetType)
                    ? findInternalVariableByName(condSmv, attr)
                    : null;
            DeviceManifest.API signalApi = "api".equals(targetType)
                    ? findSignalApiByName(condSmv, attr)
                    : null;
            if ("mode".equals(targetType) && !isModeAttr) {
                log.warn("Rule mode condition attribute '{}' on device '{}' is not a known mode", attr, deviceId);
                return null;
            }
            if ("variable".equals(targetType) && internalVar == null) {
                log.warn("Rule variable condition attribute '{}' on device '{}' is not an internal variable", attr, deviceId);
                return null;
            }
            if ("api".equals(targetType) && signalApi == null) {
                log.warn("Rule API condition attribute '{}' on device '{}' is not a known signal API", attr, deviceId);
                return null;
            }

            String lhsAttr = attr;
            if (signalApi != null) {
                if (!isSupportedApiSignalRuleRelation(normalizedRel)) {
                    log.warn("Rule API signal condition on device '{}' attribute '{}' does not support relation '{}'",
                            deviceId, attr, normalizedRel);
                    return null;
                }
                lhsAttr = DeviceSmvDataFactory.formatApiSignalName(signalApi.getName());
                if (lhsAttr == null || lhsAttr.isBlank()) {
                    log.warn("Rule API signal condition on device '{}' attribute '{}' cannot resolve signal variable name",
                            deviceId, attr);
                    return null;
                }
            }

            String rhsValue = condition.getValue();
            if (rhsValue != null && isModeAttr) {
                rhsValue = cleanRuleValueByRelation(normalizedRel, rhsValue);
            }
            if (rhsValue != null && internalVar != null
                    && internalVar.getValues() != null && !internalVar.getValues().isEmpty()) {
                rhsValue = rhsValue.replace(" ", "");
            }
            if (rhsValue != null && signalApi != null) {
                rhsValue = normalizeApiSignalRuleValue(normalizedRel, rhsValue);
                if (rhsValue == null) {
                    log.warn("Rule API signal condition has non-boolean value '{}' for device '{}' attribute '{}'",
                            condition.getValue(), deviceId, attr);
                    return null;
                }
            }
            if (rhsValue == null || rhsValue.isBlank()) {
                log.warn("Rule condition value became null/blank after normalization for device '{}' attribute '{}'",
                        deviceId, attr);
                return null;
            }
            String lhsExpr = varName + "." + lhsAttr;
            if (effectiveUseNext) {
                lhsExpr = "next(" + lhsExpr + ")";
            }
            // §5.1: Replace threshold value with FROZENVAR name if parameterized
            if (ruleIdx != null && cfg != null && cfg.getParameterizedThresholds() != null) {
                String paramKey = "r" + ruleIdx + "_c" + condIdx;
                ParameterizationConfig.ParamInfo paramInfo = cfg.getParameterizedThresholds().get(paramKey);
                if (paramInfo != null) {
                    rhsValue = paramInfo.getFrozenVarName();
                }
            }
            // §5.2: Candidate clauses keep their relation while NuSMV chooses the free value Y.
            if (ruleIdx != null && cfg != null && cfg.getCandidateConditionValues() != null) {
                String paramKey = "r" + ruleIdx + "_c" + condIdx;
                ParameterizationConfig.ConditionValueInfo valueInfo =
                        cfg.getCandidateConditionValues().get(paramKey);
                if (valueInfo != null) {
                    rhsValue = valueInfo.getFrozenVarName();
                }
            }
            String expr = SmvRelationUtils.ruleRelationExpression(lhsExpr, normalizedRel, rhsValue);
            if (expr == null || expr.isBlank()) {
                log.warn("Rule condition failed to build relation expression for device '{}' attribute '{}'", deviceId, attr);
                return null;
            }
            return expr;
        }

        if (!"api".equals(targetType)) {
            log.warn("Rule {} condition on device '{}' requires a relation/value", targetType, deviceId);
            return null;
        }

        DeviceManifest manifest = condSmv.getManifest();
        if (manifest == null || manifest.getApis() == null) return null;

        for (DeviceManifest.API api : manifest.getApis()) {
            if (!Boolean.TRUE.equals(api.getSignal()) || !api.getName().equals(attr)) {
                continue;
            }

            String apiSignal = DeviceSmvDataFactory.formatApiSignalName(api.getName());
            String apiSignalExpr = null;
            if (apiSignal != null) {
                String apiSignalLhs = varName + "." + apiSignal;
                if (effectiveUseNext) {
                    apiSignalLhs = "next(" + apiSignalLhs + ")";
                }
                apiSignalExpr = apiSignalLhs + "=TRUE";
            }

            if (apiSignalExpr != null) {
                return apiSignalExpr;
            }
            break;
        }
        log.warn("Rule condition: attribute '{}' on device '{}' did not match any mode, variable, or API signal",
                condition.getAttribute(), deviceId);
        return null;
    }
private String buildRuleStateCondition(RuleDto.Condition condition, DeviceSmvData condSmv,
                                           String normalizedRel, boolean useNext) {
        String deviceId = condition.getDeviceName();
        if (condSmv.getModes() == null || condSmv.getModes().isEmpty()) {
            log.warn("Rule state condition references no-mode device '{}' and cannot be resolved", deviceId);
            return null;
        }
        if (!"=".equals(normalizedRel)
                && !"!=".equals(normalizedRel)
                && !"in".equals(normalizedRel)
                && !"not in".equals(normalizedRel)) {
            log.warn("Rule state condition relation '{}' is not supported for device '{}'", normalizedRel, deviceId);
            return null;
        }

        List<String> rawCandidates = splitStateRuleCandidates(condition.getValue(), normalizedRel, condSmv);
        if (rawCandidates.isEmpty()) {
            log.warn("Rule state condition has empty candidate set on device '{}'", deviceId);
            return null;
        }

        List<String> tupleExprs = new ArrayList<>();
        for (String rawCandidate : rawCandidates) {
            Map<String, String> modeStateMap = resolveStateTupleCandidate(condSmv, rawCandidate);
            if (modeStateMap == null || modeStateMap.isEmpty()) {
                log.warn("Rule state condition value '{}' on device '{}' cannot be resolved to a legal mode tuple",
                        rawCandidate, deviceId);
                return null;
            }
            String tupleExpr = buildStateTupleExpr(condSmv.getVarName(), condSmv, modeStateMap, useNext);
            if (tupleExpr == null || tupleExpr.isBlank()) {
                log.warn("Rule state condition tuple '{}' on device '{}' produced no expression", rawCandidate, deviceId);
                return null;
            }
            tupleExprs.add(tupleExpr);
        }

        if ("=".equals(normalizedRel)) {
            if (tupleExprs.size() != 1) {
                log.warn("Rule state '=' condition on device '{}' resolved to multiple candidates: {}", deviceId, rawCandidates);
                return null;
            }
            return tupleExprs.get(0);
        }
        if ("!=".equals(normalizedRel)) {
            if (tupleExprs.size() != 1) {
                log.warn("Rule state '!=' condition on device '{}' resolved to multiple candidates: {}", deviceId, rawCandidates);
                return null;
            }
            return "!(" + tupleExprs.get(0) + ")";
        }
        if ("in".equals(normalizedRel)) {
            return tupleExprs.size() == 1 ? tupleExprs.get(0) : "(" + String.join(" | ", tupleExprs) + ")";
        }

        List<String> negated = new ArrayList<>();
        for (String tupleExpr : tupleExprs) {
            negated.add("!(" + tupleExpr + ")");
        }
        return negated.size() == 1 ? negated.get(0) : "(" + String.join(" & ", negated) + ")";
    }

    private List<String> splitStateRuleCandidates(String value, String normalizedRel, DeviceSmvData condSmv) {
        if (value == null) {
            return List.of();
        }
        if ("in".equals(normalizedRel) || "not in".equals(normalizedRel)) {
            String splitRegex = (condSmv.getModes() != null && condSmv.getModes().size() > 1) ? "[,|]" : "[,;|]";
            String[] parts = value.split(splitRegex);
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed);
    }

    private Map<String, String> resolveStateTupleCandidate(DeviceSmvData condSmv, String rawCandidate) {
        if (condSmv == null || condSmv.getModes() == null || condSmv.getModes().isEmpty()
                || rawCandidate == null || rawCandidate.isBlank()) {
            return null;
        }

        List<String> modes = condSmv.getModes();
        String candidate = rawCandidate.trim();

        if (candidate.contains(";")) {
            String[] segments = candidate.split(";", -1);
            if (segments.length != modes.size()) {
                return null;
            }
            Map<String, String> tuple = new LinkedHashMap<>();
            for (int i = 0; i < modes.size(); i++) {
                if (DeviceSmvDataFactory.isWildcardStateSegment(segments[i])) {
                    continue;
                }
                String cleanSeg = DeviceSmvDataFactory.cleanStateName(segments[i]);
                if (cleanSeg == null || cleanSeg.isBlank()) {
                    continue;
                }
                String mode = modes.get(i);
                List<String> legalStates = condSmv.getModeStates().get(mode);
                if (legalStates == null || !legalStates.contains(cleanSeg)) {
                    return null;
                }
                tuple.put(mode, cleanSeg);
            }
            return tuple.isEmpty() ? null : tuple;
        }

        String cleanState = DeviceSmvDataFactory.cleanStateName(candidate);
        if (cleanState == null || cleanState.isBlank()) {
            return null;
        }

        if (modes.size() == 1) {
            String mode = modes.get(0);
            List<String> legalStates = condSmv.getModeStates().get(mode);
            if (legalStates == null || !legalStates.contains(cleanState)) {
                return null;
            }
            Map<String, String> tuple = new LinkedHashMap<>();
            tuple.put(mode, cleanState);
            return tuple;
        }

        List<String> matchedModes = new ArrayList<>();
        for (String mode : modes) {
            List<String> legalStates = condSmv.getModeStates().get(mode);
            if (legalStates != null && legalStates.contains(cleanState)) {
                matchedModes.add(mode);
            }
        }
        if (matchedModes.size() != 1) {
            return null;
        }
        Map<String, String> tuple = new LinkedHashMap<>();
        tuple.put(matchedModes.get(0), cleanState);
        return tuple;
    }

    private String buildStateTupleExpr(String varName, DeviceSmvData condSmv,
                                       Map<String, String> modeStateMap, boolean useNext) {
        List<String> parts = new ArrayList<>();
        for (String mode : condSmv.getModes()) {
            String state = modeStateMap.get(mode);
            if (state == null || state.isBlank()) {
                continue;
            }
            String lhs = varName + "." + mode;
            if (useNext) {
                lhs = "next(" + lhs + ")";
            }
            parts.add(lhs + "=" + state);
        }
        if (parts.isEmpty()) {
            return null;
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" & ", parts) + ")";
    }

    private Map<String, DeviceManifest.InternalVariable> collectEnvironmentDomains(
            List<DeviceVerificationDto> devices,
            Map<String, DeviceSmvData> deviceSmvMap) {
        Map<String, DeviceManifest.InternalVariable> result = new LinkedHashMap<>();
        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null) continue;
            if (smv.getEnvVariables() != null) {
                for (Map.Entry<String, DeviceManifest.InternalVariable> env : smv.getEnvVariables().entrySet()) {
                    if (env.getKey() != null && env.getValue() != null) {
                        result.putIfAbsent(env.getKey(), env.getValue());
                    }
                }
            }
            if (smv.getImpactedEnvironmentVariables() != null) {
                for (Map.Entry<String, DeviceManifest.InternalVariable> env : smv.getImpactedEnvironmentVariables().entrySet()) {
                    if (env.getKey() != null && env.getValue() != null) {
                        result.putIfAbsent(env.getKey(), env.getValue());
                    }
                }
            }
        }
        return result;
    }

    private void appendEnvTransitions(StringBuilder content,
                                     List<DeviceVerificationDto> devices,
                                     Map<String, DeviceSmvData> deviceSmvMap,
                                     Map<String, DeviceManifest.InternalVariable> environmentDomains) {

        Set<String> processedVars = new HashSet<>();

        for (Map.Entry<String, DeviceManifest.InternalVariable> env : environmentDomains.entrySet()) {
                String varName = env.getKey();
                if (processedVars.contains(varName)) continue;
                processedVars.add(varName);

                DeviceManifest.InternalVariable var = env.getValue();
                String smvVarName = "a_" + varName;

                content.append("\n\tnext(").append(smvVarName).append(") :=\n");
                content.append("\tcase\n");

                for (DeviceVerificationDto dev : devices) {
                    DeviceSmvData transSmv = deviceSmvMap.get(dev.getVarName());
                    if (transSmv == null || transSmv.getManifest() == null ||
                        transSmv.getManifest().getTransitions() == null) continue;

                    for (DeviceManifest.Transition trans : transSmv.getManifest().getTransitions()) {
                        if (trans.getAssignments() == null) continue;

                        for (DeviceManifest.Assignment assignment : trans.getAssignments()) {
                            if (assignment == null || assignment.getAttribute() == null) {
                                throw SmvGenerationException.incompleteTrigger(
                                        transSmv.getVarName(), "Transition '" + trans.getName() + "'",
                                        "assignment or assignment.attribute is null");
                            }
                            if (varName.equals(assignment.getAttribute())) {
                                DeviceManifest.Trigger trigger = trans.getTrigger();
                                if (trigger != null) {
                                    if (trigger.getAttribute() == null || trigger.getRelation() == null
                                            || trigger.getValue() == null || assignment.getValue() == null) {
                                        throw SmvGenerationException.incompleteTrigger(
                                                transSmv.getVarName(), "Transition '" + trans.getName() + "'",
                                                "attribute=" + trigger.getAttribute() + ", relation=" + trigger.getRelation()
                                                        + ", value=" + trigger.getValue() + ", assignValue=" + assignment.getValue());
                                    }
                                    content.append("\t\t")
                                           .append(buildTransitionTriggerCondition(
                                                   transSmv, transSmv.getVarName(), trans))
                                           .append(": ")
                                           .append(normalizeAssignmentLiteral(assignment.getValue())).append(";\n");
                                }
                            }
                        }
                    }
                }

                if (var.getValues() != null && !var.getValues().isEmpty()) {
                    appendDiscreteImpactBranches(content, varName, devices, deviceSmvMap);
                    List<String> cleanValues = new ArrayList<>();
                    for (String v : var.getValues()) {
                        cleanValues.add(v.replace(" ", ""));
                    }
                    content.append("\t\tTRUE: ")
                           .append(discreteEnvironmentFallback(varName, devices, deviceSmvMap,
                                   smvVarName, "{" + String.join(", ", cleanValues) + "}"))
                           .append(";\n");
                } else if (var.getLowerBound() != null && var.getUpperBound() != null) {
                    // Numeric environment variable transition with natural change and impacted rates.
                    appendNumericEnvTransition(content, smvVarName, var, varName, devices, deviceSmvMap);
                } else {
                    appendDiscreteImpactBranches(content, varName, devices, deviceSmvMap);
                    content.append("\t\tTRUE: ")
                           .append(discreteEnvironmentFallback(varName, devices, deviceSmvMap,
                                   smvVarName, "{TRUE, FALSE}"))
                           .append(";\n");
                }

                content.append("\tesac;");
        }
    }

    /**
     * What a shared enum/boolean value does in a step no declared effect covers.
     *
     * <p>Free choice inside the declared domain is the right model for a genuinely exogenous input —
     * weather, a clock, an occupant — which no device in the scene controls. It is the wrong model for
     * a value a device declares it <em>writes</em>: a template that declares "while running, set
     * airQuality := good" says nothing about the value changing on its own, so letting it flip freely
     * in every other state invents a cause the scene does not contain. NuSMV showed the cost with the
     * humidifier off in both the current and the next step: the free-choice model still let
     * {@code airQuality} become {@code good}, refuting a property the user could not act on, while
     * holding the value makes the same property true.
     *
     * <p>So the split is on authorship, using the same {@code ImpactedVariables} declaration that
     * already means "this device may change it", and the same stutter-when-nothing-applies policy the
     * product already uses for device-local variables. This narrows the model, so it can turn a
     * spurious counterexample into a pass — but only by removing behaviour no declaration supported.
     */
    private String discreteEnvironmentFallback(String varName,
                                               List<DeviceVerificationDto> devices,
                                               Map<String, DeviceSmvData> deviceSmvMap,
                                               String smvVarName,
                                               String declaredDomain) {
        return hasDeclaredWriter(varName, devices, deviceSmvMap) ? smvVarName : declaredDomain;
    }

    /** True when some submitted device declares this shared value in its {@code ImpactedVariables}. */
    private boolean hasDeclaredWriter(String varName,
                                      List<DeviceVerificationDto> devices,
                                      Map<String, DeviceSmvData> deviceSmvMap) {
        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv != null && smv.getImpactedVariables() != null
                    && smv.getImpactedVariables().contains(varName)) {
                return true;
            }
        }
        return false;
    }

    private void appendDiscreteImpactBranches(StringBuilder content,
                                              String varName,
                                              List<DeviceVerificationDto> devices,
                                              Map<String, DeviceSmvData> deviceSmvMap) {
        for (DeviceVerificationDto dev : devices) {
            DeviceSmvData smv = deviceSmvMap.get(dev.getVarName());
            if (smv == null || smv.getImpactedVariables() == null
                    || !smv.getImpactedVariables().contains(varName)
                    || smv.getManifest() == null
                    || smv.getManifest().getWorkingStates() == null) {
                continue;
            }
            for (DeviceManifest.WorkingState state : smv.getManifest().getWorkingStates()) {
                if (state.getDynamics() == null) continue;
                for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                    if (!varName.equals(dynamic.getVariableName()) || dynamic.getValue() == null) {
                        continue;
                    }
                    String condition = buildStateCondition(smv.getVarName(), smv, state.getName());
                    if (condition == null) {
                        continue;
                    }
                    content.append("\t\t").append(condition).append(": ")
                           .append(normalizeAssignmentLiteral(dynamic.getValue())).append(";\n");
                }
            }
        }
    }

    /**
     * Generate numeric env variable next() transition following sample.smv pattern.
     * Incorporates device _rate (e.g. airconditioner.temperature_rate) and NaturalChangeRate.
     */
    private void appendNumericEnvTransition(StringBuilder content, String smvVarName,
                                            DeviceManifest.InternalVariable var, String varName,
                                            List<DeviceVerificationDto> devices,
                                            Map<String, DeviceSmvData> deviceSmvMap) {
        int lower = var.getLowerBound();
        int upper = var.getUpperBound();

        NaturalChangeRateParser.RateRange rate =
                parseNaturalChangeRate(var.getNaturalChangeRate(), "env:" + varName);

        // Check if any device provides an impacted _rate expression for this variable
        String rateExpr = findImpactRateExpression(varName, devices, deviceSmvMap);

        // MEDIC §3.1, Fig. 2b constrains v' - v to the declared interval combined with the active
        // device effect, so every integer in that interval is an admissible next value. Clamping to
        // the declared domain already pins both boundaries, which is why no separate at-boundary
        // branch is needed: at v = upper the ascending deltas simply saturate there.
        List<String> rateCandidates = new ArrayList<>();
        for (int delta : rate.admissibleDeltas()) {
            String shifted = formatArithmeticExpr(smvVarName, delta);
            rateCandidates.add(clampExpr(
                    rateExpr == null ? shifted : shifted + " + " + rateExpr, lower, upper));
        }
        content.append("\t\tTRUE: {").append(String.join(", ", rateCandidates)).append("};\n");
    }

    /**
     * Find all device rate expressions that impact the target variable.
     * Example: "air_conditioner.temperature_rate"
     */
    private String findImpactRateExpression(String varName, List<DeviceVerificationDto> devices,
                                            Map<String, DeviceSmvData> deviceSmvMap) {
        List<String> rateExprs = new ArrayList<>();
        for (DeviceVerificationDto dev : devices) {
            DeviceSmvData devSmv = deviceSmvMap.get(dev.getVarName());
            if (devSmv == null || devSmv.getImpactedVariables() == null) continue;
            if (devSmv.getImpactedVariables().contains(varName)) {
                rateExprs.add(devSmv.getVarName() + "." + varName + "_rate");
            }
        }
        if (rateExprs.isEmpty()) return null;
        // Multiple devices affecting the same variable are summed.
        return String.join("+", rateExprs);
    }

    private void appendApiSignalTransitions(StringBuilder content,
                                           List<DeviceVerificationDto> devices,
                                           Map<String, DeviceSmvData> deviceSmvMap) {
        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null || smv.getManifest() == null || smv.getManifest().getApis() == null) continue;

            String varName = smv.getVarName();

            for (DeviceManifest.API api : smv.getManifest().getApis()) {
                if (api.getSignal() == null || !api.getSignal()) continue;

                String signalName = DeviceSmvDataFactory.formatApiSignalName(api.getName());
                if (signalName == null) {
                    throw SmvGenerationException.templateInvalid(varName,
                            "Signal API name is required");
                }
                
                content.append("\n\tnext(").append(varName).append(".").append(signalName).append(") :=\n");
                content.append("\tcase\n");

                String eventCondition = buildStateEventCondition(
                        smv, varName, api.getStartState(), api.getEndState());
                if (eventCondition != null) {
                    content.append("\t\t").append(eventCondition).append(": TRUE;\n");
                } else {
                    throw SmvGenerationException.templateInvalid(varName,
                            "Signal API '" + api.getName()
                                    + "' has no representable state change");
                }

                content.append("\t\tTRUE: FALSE;\n");
                content.append("\tesac;");
            }
        }
    }

    private void appendPropertyTransitions(StringBuilder content,
                                           List<DeviceVerificationDto> devices,
                                           List<RuleDto> rules,
                                           Map<String, DeviceSmvData> deviceSmvMap,
                                           boolean isAttack,
                                           PropertyDimension dim,
                                           ParamCtx paramCtx,
                                           SmvGenerationContext context) {

        Map<String, List<RuleDto>> rulesByTarget = groupRulesByResolvedTarget(rules, deviceSmvMap);

        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null || smv.isSensor()) continue;

            String varName = smv.getVarName();
            List<RuleDto> deviceRules = rulesByTarget.get(varName);

            if (smv.getModes() == null || smv.getModes().isEmpty()) continue;

            for (int i = 0; i < smv.getModes().size(); i++) {
                String mode = smv.getModes().get(i);
                List<String> modeStates = smv.getModeStates().get(mode);
                if (modeStates == null) continue;
                List<SelectedRuleBranch> selectedBranches = buildSelectedRuleBranches(
                        smv, varName, i, deviceRules, deviceSmvMap,
                        isAttack, paramCtx, context);

                for (String state : modeStates) {
                    String cleanState = state.replace(" ", "");
                    String propVar = varName + "." + dim.prefix + mode + "_" + cleanState;

                    content.append("\n\tnext(").append(propVar).append(") :=\n");
                    content.append("\tcase\n");

                    for (SelectedRuleBranch branch : selectedBranches) {
                        if (branch.endState().replace(" ", "").equals(cleanState)) {
                                content.append("\t\t").append(branch.selectedCondition()).append(" & (");
                                appendRulePropertyConditions(content, branch.rule(), deviceSmvMap, dim, context);
                                // Content privacy condition: check if rule references contentDevice.content for content privacy
                                if (dim == PropertyDimension.PRIVACY) {
                                    String contentCond = buildContentPrivacyCondition(branch.rule(), deviceSmvMap);
                                    if (contentCond != null) {
                                        content.append(" | ").append(contentCond);
                                    }
                                }
                                content.append("): ").append(dim.activeValue).append(";\n");

                                content.append("\t\t").append(branch.selectedCondition())
                                        .append(": ").append(dim.inactiveValue).append(";\n");
                        }
                    }

                    content.append("\t\tTRUE: ").append(propVar).append(";\n");
                    content.append("\tesac;");
                }
            }
        }
    }

    /**
     * Generate property transitions for API-bearing devices. A compromised device marks only
     * template-declared falsifiable readings untrusted; other values and privacy classifications stutter.
     */
    private void appendVariablePropertyTransitions(StringBuilder content,
                                                    List<DeviceVerificationDto> devices,
                                                    Map<String, DeviceSmvData> deviceSmvMap,
                                                    PropertyDimension dim,
                                                    boolean isAttack) {
        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null || smv.isSensor()) continue;

            String varName = smv.getVarName();
            for (DeviceManifest.InternalVariable var : smv.getVariables()) {
                String propVar = varName + "." + dim.prefix + var.getName();
                if (dim == PropertyDimension.TRUST
                        && isAttack
                        && Boolean.TRUE.equals(var.getFalsifiableWhenCompromised())) {
                    content.append("\n\tnext(").append(propVar).append(") :=\n")
                            .append("\tcase\n")
                            .append("\t\t").append(varName).append(".is_attack=TRUE: untrusted;\n")
                            .append("\t\tTRUE: ").append(propVar).append(";\n")
                            .append("\tesac;");
                } else {
                    content.append("\n\tnext(").append(propVar).append(") := ").append(propVar).append(";");
                }
            }
        }
    }

    private void appendRulePropertyConditions(StringBuilder content, RuleDto rule,
                                              Map<String, DeviceSmvData> deviceSmvMap,
                                              PropertyDimension dim,
                                              SmvGenerationContext context) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            String reason = "Rule has no trigger conditions and cannot produce " + dim.name() + " property conditions";
            log.warn(reason);
            recordDisabledRule(context, rule,
                    ModelGenerationIssueReasonCode.RULE_NO_TRIGGER_CONDITIONS, reason);
            content.append("FALSE");
            return;
        }

        int attemptedConditions = 0;
        Set<String> parts = new LinkedHashSet<>();
        for (RuleDto.Condition condition : rule.getConditions()) {
            if (condition == null || condition.getDeviceName() == null) continue;
            DeviceSmvData condSmv = DeviceSmvDataFactory.findDeviceSmvDataStrict(condition.getDeviceName(), deviceSmvMap);
            if (condSmv == null || condSmv.getManifest() == null) continue;
            attemptedConditions++;

            String part = buildPropertyConditionPart(condition, condSmv, condSmv.getVarName(), condSmv.getManifest(), dim);
            if (part != null && !part.isEmpty()) parts.add(part);
        }

        // MEDIC Definition 3.3 treats trust as retained user control: the
        // target is trusted when at least one contributing trigger source is
        // trusted, and becomes untrusted only when all are untrusted. Privacy
        // remains taint-like: any private source makes the target private.
        // Fail-closed: if conditions existed but none produced a property part,
        // output FALSE rather than TRUE to avoid silently relaxing the property constraint.
        if (parts.isEmpty()) {
            if (attemptedConditions > 0) {
                String reason = "Rule has " + attemptedConditions + " condition(s) but none produced a "
                        + dim.name() + " property part";
                log.warn("{}; using fail-closed FALSE", reason);
                recordDisabledRule(context, rule,
                        ModelGenerationIssueReasonCode.RULE_PROPERTY_PROPAGATION_UNAVAILABLE, reason);
                content.append("FALSE");
            } else {
                content.append("TRUE");
            }
        } else {
            content.append(String.join(" | ", parts));
        }
    }

    private String buildPropertyConditionPart(RuleDto.Condition condition, DeviceSmvData condSmv,
                                              String condVarName, DeviceManifest deviceManifest,
                                              PropertyDimension dim) {
        String targetType = condition.getTargetType() != null
                ? condition.getTargetType().trim().toLowerCase(Locale.ROOT)
                : null;
        if (targetType == null || !List.of("api", "variable", "mode", "state").contains(targetType)) {
            return null;
        }
        // Trimmed to match the trigger resolver and admission; see buildRuleCondition. Returning null
        // here silently drops the propagation constraint for a condition the guard itself accepted.
        String attr = condition.getAttribute() == null ? null : condition.getAttribute().trim();
        if (condition.getRelation() == null) {
            if (!"api".equals(targetType)) {
                return null;
            }
            if (deviceManifest.getApis() != null) {
                for (DeviceManifest.API api : deviceManifest.getApis()) {
                    if (api.getName().equals(attr) && Boolean.TRUE.equals(api.getSignal())) {
                        return buildApiPropertyPredicate(condSmv, condVarName, dim, api.getEndState());
                    }
                }
            }
        } else if ("variable".equals(targetType) && deviceManifest.getInternalVariables() != null) {
            for (DeviceManifest.InternalVariable var : deviceManifest.getInternalVariables()) {
                if (var.getName().equals(attr)) {
                    return condVarName + "." + dim.prefix + var.getName() + "=" + dim.activeValue;
                }
            }
        } else if ("mode".equals(targetType)) {
            if (condSmv.getModes() == null || !condSmv.getModes().contains(attr)) {
                return null;
            }
            return buildCurrentStatePropertyPredicate(condSmv, condVarName, dim, attr);
        } else if ("state".equals(targetType)) {
            Set<String> referencedModes = resolveStateConditionModes(condition, condSmv);
            return referencedModes.isEmpty()
                    ? null
                    : buildCurrentStatePropertyPredicate(condSmv, condVarName, dim, referencedModes);
        }
        return null;
    }

    private String buildApiPropertyPredicate(DeviceSmvData smv,
                                             String varName,
                                             PropertyDimension dim,
                                             String endState) {
        Map<String, String> stateTuple = resolveStateTupleCandidate(smv, endState);
        if (stateTuple == null || stateTuple.isEmpty()) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        for (String mode : smv.getModes()) {
            String state = stateTuple.get(mode);
            if (state != null) {
                labels.add(varName + "." + dim.prefix + mode + "_" + state + "=" + dim.activeValue);
            }
        }
        return joinPropertySources(labels, dim);
    }

    private Set<String> resolveStateConditionModes(RuleDto.Condition condition, DeviceSmvData smv) {
        if (condition == null || condition.getRelation() == null) {
            return Collections.emptySet();
        }
        String relation = normalizeRuleRelation(condition.getRelation());
        List<String> candidates = splitStateRuleCandidates(condition.getValue(), relation, smv);
        Set<String> modes = new LinkedHashSet<>();
        for (String candidate : candidates) {
            Map<String, String> tuple = resolveStateTupleCandidate(smv, candidate);
            if (tuple == null || tuple.isEmpty()) {
                return Collections.emptySet();
            }
            modes.addAll(tuple.keySet());
        }
        return modes;
    }

    private String buildCurrentStatePropertyPredicate(DeviceSmvData smv,
                                                      String varName,
                                                      PropertyDimension dim,
                                                      String targetMode) {
        Set<String> targetModes = targetMode == null
                ? new LinkedHashSet<>(smv.getModes())
                : Set.of(targetMode);
        return buildCurrentStatePropertyPredicate(smv, varName, dim, targetModes);
    }

    private String buildCurrentStatePropertyPredicate(DeviceSmvData smv,
                                                      String varName,
                                                      PropertyDimension dim,
                                                      Set<String> targetModes) {
        if (smv.getModes() == null || smv.getModes().isEmpty()) {
            return null;
        }
        List<String> modePredicates = new ArrayList<>();
        for (String mode : smv.getModes()) {
            if (targetModes == null || !targetModes.contains(mode)) {
                continue;
            }
            List<String> states = smv.getModeStates() != null ? smv.getModeStates().get(mode) : null;
            if (states == null) {
                continue;
            }
            List<String> statePredicates = new ArrayList<>();
            for (String state : states) {
                statePredicates.add("(" + varName + "." + mode + "=" + state
                        + " & " + varName + "." + dim.prefix + mode + "_" + state
                        + "=" + dim.activeValue + ")");
            }
            if (!statePredicates.isEmpty()) {
                modePredicates.add(statePredicates.size() == 1
                        ? statePredicates.get(0)
                        : "(" + String.join(" | ", statePredicates) + ")");
            }
        }
        return joinPropertySources(modePredicates, dim);
    }

    private String joinPropertySources(List<String> predicates, PropertyDimension dim) {
        if (predicates == null || predicates.isEmpty()) {
            return null;
        }
        return predicates.size() == 1 ? predicates.get(0)
                : "(" + String.join(" | ", predicates) + ")";
    }

    /**
     * Build content privacy condition from rule command referencing contentDevice.content.
     * e.g. "THEN Facebook.post(MobilePhone.photo)" checks "mobilephone.privacy_photo=private"
     */
    private String buildContentPrivacyCondition(RuleDto rule, Map<String, DeviceSmvData> deviceSmvMap) {
        if (rule.getCommand() == null) return null;
        String contentDevice = rule.getCommand().getContentDevice();
        String contentName = rule.getCommand().getContent();
        if (contentDevice == null || contentDevice.isBlank() || contentName == null || contentName.isBlank()) return null;

        DeviceSmvData targetSmv = DeviceSmvDataFactory.findDeviceSmvDataStrict(
                rule.getCommand().getDeviceName(), deviceSmvMap);
        DeviceManifest.API actionApi = targetSmv != null && targetSmv.getManifest() != null
                ? DeviceSmvDataFactory.findApi(targetSmv.getManifest(), rule.getCommand().getAction())
                : null;
        if (actionApi == null || !Boolean.TRUE.equals(actionApi.getAcceptsContent())) {
            throw SmvGenerationException.smvGenerationError(
                    "Rule content sensitivity cannot be attached to action '"
                            + rule.getCommand().getAction() + "' because its template API does not declare AcceptsContent=true");
        }

        DeviceSmvData contentSmv = DeviceSmvDataFactory.findDeviceSmvDataStrict(contentDevice, deviceSmvMap);
        if (contentSmv == null) {
            throw SmvGenerationException.deviceNotFound(contentDevice);
        }
        if (contentSmv.getContents() == null) {
            throw SmvGenerationException.smvGenerationError(
                    "Rule content source '" + contentDevice + "' declares no content items");
        }

        // Verify the content exists in the content device
        for (DeviceSmvData.ContentInfo ci : contentSmv.getContents()) {
            if (contentName.equals(ci.getName())) {
                return contentSmv.getVarName() + ".privacy_" + contentName + "=private";
            }
        }
        throw SmvGenerationException.smvGenerationError(
                "Rule content item '" + contentName + "' is not declared by device '" + contentDevice + "'");
    }

    private void appendInternalVariableTransitions(StringBuilder content,
                                                  List<DeviceVerificationDto> devices,
                                                  Map<String, DeviceSmvData> deviceSmvMap,
                                                  boolean isAttack) {
        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv == null || smv.getManifest() == null || smv.getManifest().getInternalVariables() == null) continue;

            String varName = smv.getVarName();
            for (DeviceManifest.InternalVariable var : smv.getManifest().getInternalVariables()) {
                if (var.getIsInside() == null || !var.getIsInside()) {
                    continue;
                }

                Integer lowerBound = var.getLowerBound();
                Integer upperBound = var.getUpperBound();
                boolean hasNumericBounds = lowerBound != null && upperBound != null;

                content.append("\n\tnext(").append(varName).append(".").append(var.getName()).append(") :=\n");
                content.append("\tcase\n");

                if (isAttack && Boolean.TRUE.equals(var.getFalsifiableWhenCompromised())) {
                    content.append("\t\t").append(varName).append(".is_attack=TRUE: ");
                    if (var.getValues() != null && !var.getValues().isEmpty()) {
                        List<String> cleanVals = new ArrayList<>();
                        for (String v : var.getValues()) {
                            cleanVals.add(v.replace(" ", ""));
                        }
                        content.append("{").append(String.join(", ", cleanVals)).append("};\n");
                    } else if (hasNumericBounds) {
                        content.append(lowerBound).append("..").append(upperBound).append(";\n");
                    } else {
                        content.append("{TRUE, FALSE};\n");
                    }
                }

                if (smv.getManifest().getTransitions() != null) {
                    for (DeviceManifest.Transition trans : smv.getManifest().getTransitions()) {
                        if (trans.getAssignments() == null) continue;

                        for (DeviceManifest.Assignment assignment : trans.getAssignments()) {
                            if (assignment == null || assignment.getAttribute() == null) {
                                throw SmvGenerationException.incompleteTrigger(
                                        smv.getVarName(), "Transition '" + trans.getName() + "'",
                                        "assignment or assignment.attribute is null");
                            }
                            if (var.getName().equals(assignment.getAttribute())) {
                                DeviceManifest.Trigger trigger = trans.getTrigger();
                                if (trigger != null) {
                                    if (trigger.getAttribute() == null || trigger.getRelation() == null
                                            || trigger.getValue() == null || assignment.getValue() == null) {
                                        throw SmvGenerationException.incompleteTrigger(
                                                smv.getVarName(), "Transition '" + trans.getName() + "'",
                                                "attribute=" + trigger.getAttribute() + ", relation=" + trigger.getRelation()
                                                        + ", value=" + trigger.getValue() + ", assignValue=" + assignment.getValue());
                                    }
                                    content.append("\t\t")
                                           .append(buildTransitionTriggerCondition(smv, varName, trans))
                                           .append(": ")
                                           .append(normalizeAssignmentLiteral(assignment.getValue())).append(";\n");
                                }
                            }
                        }
                    }
                }

                boolean numericVariable = hasNumericBounds;
                if (numericVariable) {
                    String impactedRate = "";
                    if (smv.getImpactedVariables() != null && smv.getImpactedVariables().contains(var.getName())) {
                        impactedRate = varName + "." + var.getName() + "_rate";
                    }

                    NaturalChangeRateParser.RateRange localRate = parseNaturalChangeRate(
                            var.getNaturalChangeRate(), "internal:" + var.getName());

                    String varRef = varName + "." + var.getName();

                    appendLocalNumericDynamicsBranches(content, smv, varName, var, varRef,
                            localRate, hasNumericBounds, lowerBound, upperBound);

                    // Every integer in the declared interval is admissible, combined with the
                    // device's own active effect. Clamping pins both boundaries, so saturation at
                    // the domain edge needs no separate branch.
                    List<String> rateCandidates = new ArrayList<>();
                    for (int delta : localRate.admissibleDeltas()) {
                        String expr = formatArithmeticExpr(varRef, delta);
                        if (!impactedRate.isEmpty()) {
                            expr = expr + " + " + impactedRate;
                        }
                        rateCandidates.add(
                                hasNumericBounds ? clampExpr(expr, lowerBound, upperBound) : expr);
                    }
                    content.append("\t\tTRUE: {").append(String.join(", ", rateCandidates)).append("};\n");
                } else {
                    if (smv.getManifest().getWorkingStates() != null) {
                        for (DeviceManifest.WorkingState state : smv.getManifest().getWorkingStates()) {
                            if (state.getDynamics() == null) continue;
                            for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                                if (var.getName().equals(dynamic.getVariableName()) && dynamic.getValue() != null) {
                                    String cleanValue = normalizeAssignmentLiteral(dynamic.getValue());
                                    if (smv.getModes() != null && !smv.getModes().isEmpty()) {
                                        String[] stateNames = state.getName().split(";");
                                        boolean firstCond = true;
                                        for (int c = 0; c < smv.getModes().size() && c < stateNames.length; c++) {
                                            String rawSeg = stateNames[c].trim();
                                            if (rawSeg.isEmpty()) continue;
                                            if (firstCond) {
                                                content.append("\t\t");
                                            } else {
                                                content.append(" & ");
                                            }
                                            firstCond = false;
                                            content.append(varName).append(".").append(smv.getModes().get(c))
                                                   .append("=").append(DeviceSmvDataFactory.cleanStateName(rawSeg));
                                        }
                                        if (firstCond) continue;
                                        content.append(": ").append(cleanValue).append(";\n");
                                    }
                                }
                            }
                        }
                    }
                    if (var.getValues() != null && !var.getValues().isEmpty()) {
                        content.append("\t\tTRUE: ").append(varName).append(".")
                               .append(var.getName()).append(";\n");
                    } else {
                        content.append("\t\tTRUE: ").append(varName).append(".")
                               .append(var.getName()).append(";\n");
                    }
                }

                content.append("\tesac;");
            }
        }
    }

    private String buildTransitionTriggerCondition(DeviceSmvData smv,
                                                   String varName,
                                                   DeviceManifest.Transition transition) {
        DeviceManifest.Trigger trigger = transition.getTrigger();
        if (trigger == null || trigger.getAttribute() == null || trigger.getRelation() == null
                || trigger.getValue() == null) {
            throw SmvGenerationException.incompleteTrigger(
                    smv.getVarName(), "Transition '" + transition.getName() + "'",
                    "attribute, relation, and value are required");
        }
        String triggerRelation = normalizeTriggerRelationOrThrow(
                smv.getVarName(), "Transition '" + transition.getName() + "'", trigger.getRelation());
        // A Transition is the device's own autonomous behaviour, so its guard must read what the
        // device observes (`<device>.<attr>`), never the environment pool ground truth (`a_<attr>`).
        // Reading the pool made the device omniscient: under attack the mirror
        // (`<device>.<attr> := case is_attack: <domain>; TRUE: a_<attr>; esac`) carries the falsified
        // value, but a pool-reading guard bypassed it, so a compromised sensor could not drive its own
        // controller and the canonical sensor-spoofing attack was proved impossible (SATISFIED).
        // `<device>.<attr>` is never dangling. Note that DECLARATION is not the distinguishing step:
        // `SmvDeviceModuleBuilder.appendInternalVariables` declares every manifest internal variable,
        // shared and device-local alike (including affect-only ones). What differs is ASSIGNMENT:
        //   - a mode is declared and assigned by the device module;
        //   - a device-local variable is assigned by `appendInitialValues` and
        //     `appendInternalVariableTransitions`, both of which skip non-local names;
        //   - a shared *reading* is assigned by `appendExternalVariableAssignments` below, as the mirror.
        // So the residue is "declared but never assigned" — the affect-only shared declaration
        // (IsInside=false, Reads=false) — and that is exactly what
        // `SmvModelValidator.buildLegalAttributeSet` refuses as a trigger attribute. An unassigned
        // identifier is legal SMV that NuSMV re-picks freely every step, which is why refusing it matters.
        String triggerRef = varName + "." + trigger.getAttribute();
        String triggerCondition = triggerRef + triggerRelation + trigger.getValue().replace(" ", "");
        String startStateGuard = buildStartStateCondition(smv, varName, transition.getStartState());
        return startStateGuard.isEmpty()
                ? triggerCondition
                : startStateGuard + " & " + triggerCondition;
    }

    private String buildStateEventCondition(DeviceSmvData smv,
                                            String varName,
                                            String startState,
                                            String endState) {
        if (smv.getModes() == null || smv.getModes().isEmpty()) {
            return null;
        }
        List<String> guards = new ArrayList<>();
        List<String> changedPredicates = new ArrayList<>();
        for (int modeIndex = 0; modeIndex < smv.getModes().size(); modeIndex++) {
            String mode = smv.getModes().get(modeIndex);
            String cleanStart = getStateForMode(startState, modeIndex, true);
            if (cleanStart != null && !cleanStart.isEmpty()) {
                guards.add(varName + "." + mode + "=" + cleanStart);
            }
            String cleanEnd = getStateForMode(endState, modeIndex);
            if (cleanEnd != null && !cleanEnd.isEmpty()) {
                guards.add("next(" + varName + "." + mode + ")=" + cleanEnd);
                changedPredicates.add(varName + "." + mode + "!=" + cleanEnd);
            }
        }
        if (changedPredicates.isEmpty()) {
            return null;
        }
        guards.add(changedPredicates.size() == 1
                ? changedPredicates.get(0)
                : "(" + String.join(" | ", changedPredicates) + ")");
        return String.join(" & ", guards);
    }

    private String buildStartStateCondition(DeviceSmvData smv,
                                            String varName,
                                            String startState) {
        if (smv.getModes() == null || smv.getModes().isEmpty()) {
            return "";
        }
        List<String> guards = new ArrayList<>();
        for (int modeIndex = 0; modeIndex < smv.getModes().size(); modeIndex++) {
            String cleanStart = getStateForMode(startState, modeIndex, true);
            if (cleanStart != null && !cleanStart.isEmpty()) {
                guards.add(varName + "." + smv.getModes().get(modeIndex) + "=" + cleanStart);
            }
        }
        return String.join(" & ", guards);
    }

    private String normalizeAssignmentLiteral(String value) {
        String normalized = value.replace(" ", "");
        if ("true".equalsIgnoreCase(normalized) || "false".equalsIgnoreCase(normalized)) {
            return normalized.toUpperCase(Locale.ROOT);
        }
        return normalized;
    }

    private void appendLocalNumericDynamicsBranches(StringBuilder content,
                                                    DeviceSmvData smv,
                                                    String deviceVarName,
                                                    DeviceManifest.InternalVariable var,
                                                    String varRef,
                                                    NaturalChangeRateParser.RateRange rate,
                                                    boolean hasNumericBounds,
                                                    Integer lowerBound,
                                                    Integer upperBound) {
        if (smv.getManifest() == null || smv.getManifest().getWorkingStates() == null) {
            return;
        }
        for (DeviceManifest.WorkingState state : smv.getManifest().getWorkingStates()) {
            if (state.getDynamics() == null) continue;
            for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                if (!var.getName().equals(dynamic.getVariableName()) || dynamic.getChangeRate() == null) {
                    continue;
                }
                String condition = buildStateCondition(deviceVarName, smv, state.getName());
                if (condition == null) {
                    continue;
                }
                final int dynamicRate;
                try {
                    dynamicRate = Integer.parseInt(dynamic.getChangeRate().trim());
                } catch (NumberFormatException e) {
                    throw SmvGenerationException.templateInvalid(deviceVarName,
                            "WorkingState Dynamics for local variable '" + var.getName()
                                    + "' has non-integer ChangeRate '" + dynamic.getChangeRate() + "'");
                }

                // The state's own ChangeRate is a definite effect; the declared interval is the
                // drift that may accompany it, so every admissible delta combines with that effect.
                List<String> candidates = new ArrayList<>();
                for (int delta : rate.admissibleDeltas()) {
                    candidates.add(clampIfBounded(
                            formatArithmeticExpr(formatArithmeticExpr(varRef, delta), dynamicRate),
                            hasNumericBounds, lowerBound, upperBound));
                }

                content.append("\t\t").append(condition).append(": {")
                       .append(String.join(", ", candidates)).append("};\n");
            }
        }
    }

    private String buildStateCondition(String deviceVarName, DeviceSmvData smv, String stateName) {
        if (smv.getModes() == null || smv.getModes().isEmpty() || stateName == null) {
            return null;
        }
        String[] states = stateName.split(";");
        List<String> conditions = new ArrayList<>();
        for (int c = 0; c < smv.getModes().size() && c < states.length; c++) {
            String rawSeg = states[c].trim();
            if (rawSeg.isEmpty()) continue;
            conditions.add(deviceVarName + "." + smv.getModes().get(c)
                    + "=" + DeviceSmvDataFactory.cleanStateName(rawSeg));
        }
        if (conditions.isEmpty()) {
            return null;
        }
        return String.join(" & ", conditions);
    }

    /**
     * Extract the state segment at modeIndex from a semicolon-separated state tuple.
     */
    private String getStateForMode(String multiModeState, int modeIndex) {
        return getStateForMode(multiModeState, modeIndex, false);
    }

    /**
     * Extract state value for a specific mode from multi-mode state string.
     * @param allowWildcard if true, treats "_" as wildcard (returns null); used for StartState
     */
    private String getStateForMode(String multiModeState, int modeIndex, boolean allowWildcard) {
        if (multiModeState == null) return null;
        String[] states = multiModeState.split(";");
        if (modeIndex < states.length) {
            String raw = states[modeIndex].trim();
            if (raw.isEmpty()) return null;
            if (allowWildcard && raw.equals("_")) return null;
            return DeviceSmvDataFactory.cleanStateName(raw);
        }
        return null;
    }

    private String formatArithmeticExpr(String varRef, int rate) {
        if (rate == 0) return varRef;
        if (rate > 0) return varRef + " + " + rate;
        return varRef + " - " + Math.abs(rate);
    }

    private String clampExpr(String expr, int lower, int upper) {
        return "max(" + lower + ", min(" + upper + ", " + expr + "))";
    }

    private String clampExpr(String expr, Integer lower, Integer upper) {
        if (lower == null || upper == null) {
            return expr;
        }
        return clampExpr(expr, lower.intValue(), upper.intValue());
    }

    private String clampIfBounded(String expr, boolean hasNumericBounds, Integer lower, Integer upper) {
        return hasNumericBounds ? clampExpr(expr, lower, upper) : expr;
    }

    /**
     * Resolves a declared NaturalChangeRate into the interval the generated model must admit,
     * failing generation on a malformed declaration rather than modeling a guess.
     */
    private NaturalChangeRateParser.RateRange parseNaturalChangeRate(String ncr, String contextName) {
        try {
            return NaturalChangeRateParser.parse(ncr);
        } catch (NaturalChangeRateParser.ParseException exception) {
            throw SmvGenerationException.templateInvalid(contextName,
                    "NaturalChangeRate '" + ncr
                            + "' is not a valid ordered integer or [lower,upper] range");
        }
    }

    private Map<String, String> resolveEnvironmentPoolInitValues(
            List<BoardEnvironmentVariableDto> environmentVariables,
            Map<String, DeviceManifest.InternalVariable> environmentDomains) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (environmentVariables == null || environmentVariables.isEmpty()
                || environmentDomains == null || environmentDomains.isEmpty()) {
            return resolved;
        }
        for (BoardEnvironmentVariableDto variable : environmentVariables) {
            if (variable == null) {
                throw SmvGenerationException.templateInvalid("environment", "Environment variable item cannot be null");
            }
            if (variable.getName() == null || variable.getName().isBlank()) {
                throw SmvGenerationException.templateInvalid("environment", "Environment variable name is required");
            }
            if (variable.getValue() == null || variable.getValue().isBlank()) {
                throw SmvGenerationException.templateInvalid("environment",
                        "Environment variable '" + variable.getName() + "' value is required");
            }
            String name = variable.getName().trim();
            if (resolved.containsKey(name)) {
                throw SmvGenerationException.templateInvalid("environment",
                        "Duplicate environment variable '" + name + "'");
            }
            DeviceManifest.InternalVariable definition = environmentDomains.get(name);
            if (definition == null) {
                throw SmvGenerationException.templateInvalid("environment",
                        "Environment variable '" + name + "' is not declared by the current model");
            }
            String validatedInit = validateEnvVarInitValue(name, variable.getValue(), definition);
            resolved.put(name, validatedInit);
        }
        return resolved;
    }

    /** Validate explicit environment values without changing the user's authored value. */
    private String validateEnvVarInitValue(String varName, String userInit,
                                           DeviceManifest.InternalVariable var) {
        if (var.getValues() != null && !var.getValues().isEmpty()) {
            List<String> cleanValues = new ArrayList<>();
            for (String v : var.getValues()) cleanValues.add(v.replace(" ", ""));
            String cleanInit = userInit.replace(" ", "");
            if (!cleanValues.contains(cleanInit)) {
                throw SmvGenerationException.templateInvalid("environment",
                        "Environment variable '" + varName + "' value '" + userInit
                                + "' is not in " + cleanValues);
            }
            return cleanInit;
        } else if (var.getLowerBound() != null && var.getUpperBound() != null) {
            try {
                int value = Integer.parseInt(userInit.trim());
                int lower = var.getLowerBound();
                int upper = var.getUpperBound();
                if (value < lower) {
                    throw SmvGenerationException.templateInvalid("environment",
                            "Environment variable '" + varName + "' value " + value
                                    + " is below lower bound " + lower);
                }
                if (value > upper) {
                    throw SmvGenerationException.templateInvalid("environment",
                            "Environment variable '" + varName + "' value " + value
                                    + " is above upper bound " + upper);
                }
                return userInit.trim();
            } catch (NumberFormatException e) {
                throw SmvGenerationException.templateInvalid("environment",
                        "Environment variable '" + varName + "' value '" + userInit
                                + "' is not a valid integer");
            }
        }
        throw SmvGenerationException.templateInvalid("environment",
                "Environment variable '" + varName + "' has no declared value domain");
    }

    /**
     * Normalize trigger relation and throw if unsupported for NuSMV.
     */
    private String normalizeTriggerRelationOrThrow(String deviceName, String context, String rawRelation) {
        String normalized = SmvRelationUtils.normalizeTriggerRelation(rawRelation);
        if (!SmvRelationUtils.isSupportedTriggerRelation(normalized)) {
            throw SmvGenerationException.illegalTriggerRelation(
                    deviceName, context, rawRelation,
                    List.of("=", "!=", ">", ">=", "<", "<="));
        }
        return normalized;
    }

    private static String normalizeRuleRelation(String relation) {
        return SmvRelationUtils.normalizeRelation(relation);
    }

    private static boolean isSupportedRuleRelation(String relation) {
        return SmvRelationUtils.isSupportedRelation(relation);
    }

    private static List<String> splitRuleValues(String value) {
        return SmvRelationUtils.splitRuleValues(value);
    }

    private static String cleanRuleValueByRelation(String normalizedRelation, String value) {
        return SmvRelationUtils.cleanRuleValueByRelation(normalizedRelation, value);
    }

    private static DeviceManifest.InternalVariable findInternalVariableByName(DeviceSmvData smv, String attr) {
        if (smv == null || attr == null || smv.getManifest() == null || smv.getManifest().getInternalVariables() == null) {
            return null;
        }
        for (DeviceManifest.InternalVariable iv : smv.getManifest().getInternalVariables()) {
            if (attr.equals(iv.getName())) {
                return iv;
            }
        }
        return null;
    }

    private static DeviceManifest.API findSignalApiByName(DeviceSmvData smv, String attr) {
        if (smv == null || attr == null || smv.getManifest() == null || smv.getManifest().getApis() == null) {
            return null;
        }
        for (DeviceManifest.API api : smv.getManifest().getApis()) {
            if (attr.equals(api.getName()) && Boolean.TRUE.equals(api.getSignal())) {
                return api;
            }
        }
        return null;
    }

    private static boolean isSupportedApiSignalRuleRelation(String relation) {
        return "=".equals(relation)
                || "!=".equals(relation)
                || "in".equals(relation)
                || "not in".equals(relation);
    }

    private static String normalizeApiSignalRuleValue(String relation, String value) {
        if (value == null) return null;
        if ("in".equals(relation) || "not in".equals(relation)) {
            List<String> values = splitRuleValues(value);
            if (values.isEmpty()) return null;
            List<String> normalized = new ArrayList<>();
            for (String item : values) {
                String boolLiteral = normalizeBooleanLiteral(item);
                if (boolLiteral == null) return null;
                normalized.add(boolLiteral);
            }
            return String.join(",", normalized);
        }
        return normalizeBooleanLiteral(value);
    }

    private static String normalizeBooleanLiteral(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim();
        if ("TRUE".equalsIgnoreCase(normalized)) return "TRUE";
        if ("FALSE".equalsIgnoreCase(normalized)) return "FALSE";
        return null;
    }
}
