package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.AttackActivation;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.PropertyDimension;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SMV 设备模块构建器
 * 职责：构建单个设备的 NuSMV 模块定义
 */
@Slf4j
@Component
public class SmvDeviceModuleBuilder {

    private static final String NUSMV_FALSE = "FALSE";
    private static final String DEFAULT_PRIVACY = "public";
    private static final String DEFAULT_TRUST = "trusted";


    public String build(DeviceSmvData smv, boolean isAttack, boolean enablePrivacy) {
        return build(smv, isAttack ? AttackActivation.NONDETERMINISTIC : AttackActivation.DISABLED,
                enablePrivacy);
    }

    public String build(DeviceSmvData smv,
                        AttackActivation attackActivation,
                        boolean enablePrivacy) {
        // 参数验证
        if (smv == null) {
            log.error("SmvDeviceModuleBuilder.build: smv 参数不能为 null");
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvDeviceModuleBuilder",
                    "smv",
                    "must not be null");
        }
        if (smv.getModuleName() == null || smv.getModuleName().isEmpty()) {
            log.error("SmvDeviceModuleBuilder.build: smv.moduleName 为空");
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvDeviceModuleBuilder",
                    "smv.moduleName",
                    "must not be blank");
        }

        StringBuilder content = new StringBuilder();
        String moduleName = smv.getModuleName();

        content.append("\nMODULE ").append(moduleName);

        // 检查 manifest 是否为 null
        if (smv.getManifest() == null) {
            log.warn("SmvDeviceModuleBuilder.build: smv.manifest 为 null，将视为传感器设备");
            smv.setManifest(new DeviceManifest());
        }

        boolean isSensor = smv.isSensor();
        AttackActivation safeAttackActivation = attackActivation != null
                ? attackActivation : AttackActivation.DISABLED;
        boolean isAttack = safeAttackActivation.isModeled();

        // FROZENVAR for sensors (attack mode + variable trust/privacy)
        appendFrozenVarSection(content, smv, isAttack, isSensor, enablePrivacy);

        appendVariables(content, smv, isSensor, enablePrivacy);
        appendAssignments(content, smv, safeAttackActivation, isSensor, enablePrivacy);

        return content.toString();
    }

    private void appendFrozenVarSection(StringBuilder content, DeviceSmvData smv, boolean isAttack, boolean isSensor, boolean enablePrivacy) {
        boolean hasFrozenVar = false;
        StringBuilder frozenVars = new StringBuilder();

        if (isAttack) {
            frozenVars.append("\n\tis_attack: boolean;");
            hasFrozenVar = true;
        }

        if (isSensor) {
            hasFrozenVar |= appendStatePropertyVariables(
                    frozenVars, smv, PropertyDimension.TRUST);
            if (enablePrivacy) {
                hasFrozenVar |= appendStatePropertyVariables(
                        frozenVars, smv, PropertyDimension.PRIVACY);
            }
            for (DeviceManifest.InternalVariable var : smv.getVariables()) {
                frozenVars.append("\n\ttrust_").append(var.getName()).append(": {trusted, untrusted};");
                if (enablePrivacy) {
                    frozenVars.append("\n\tprivacy_").append(var.getName()).append(": {public, private};");
                }
                hasFrozenVar = true;
            }
        }

        // Content visibility is a classification of the source data, not a value
        // changed merely because an automation rule references that content.
        if (enablePrivacy) {
            for (DeviceSmvData.ContentInfo ci : smv.getContents()) {
                frozenVars.append("\n\tprivacy_").append(ci.getName()).append(": {public, private};");
                hasFrozenVar = true;
            }
        }

        if (hasFrozenVar) {
            content.append("\nFROZENVAR").append(frozenVars);
        }
    }

    private void appendVariables(StringBuilder content, DeviceSmvData smv, boolean isSensor, boolean enablePrivacy) {
        StringBuilder varContent = new StringBuilder();

        appendModeVariables(varContent, smv);
        appendInternalVariables(varContent, smv);
        appendSignalVariables(varContent, smv);
        if (!isSensor) {
            appendPropertyVariables(varContent, smv, PropertyDimension.TRUST);
            if (enablePrivacy) {
                appendPropertyVariables(varContent, smv, PropertyDimension.PRIVACY);
            }
        }
        if (!varContent.isEmpty()) {
            content.append("\nVAR").append(varContent);
        }

        StringBuilder defineContent = new StringBuilder();
        appendVariableRates(defineContent, smv);
        if (!defineContent.isEmpty()) {
            content.append("\nDEFINE").append(defineContent);
        }
    }

    private void appendAssignments(StringBuilder content,
                                   DeviceSmvData smv,
                                   AttackActivation attackActivation,
                                   boolean isSensor,
                                   boolean enablePrivacy) {
        StringBuilder assignContent = new StringBuilder();
        boolean isAttack = attackActivation.isModeled();

        appendInitialValues(assignContent, smv, isAttack);

        if (isAttack) {
            assignContent.append("\n\tinit(is_attack) := ")
                    .append(attackActivation.initialExpression()).append(";");
        }

        appendInitialProperty(assignContent, smv, isAttack, PropertyDimension.TRUST);
        if (enablePrivacy) {
            appendInitialProperty(assignContent, smv, isAttack, PropertyDimension.PRIVACY);
        }

        // content 隐私初始化
        if (enablePrivacy) {
            for (DeviceSmvData.ContentInfo ci : smv.getContents()) {
                String cp = DeviceSmvDataFactory.normalizeTrustPrivacy(ci.getPrivacy());
                assignContent.append("\n\tinit(privacy_").append(ci.getName()).append(") := ")
                        .append(cp).append(";");
            }
        }

        if (!assignContent.isEmpty()) {
            content.append("\nASSIGN").append(assignContent);
        }
    }

    private void appendModeVariables(StringBuilder content, DeviceSmvData smv) {
        if (smv.getModes().isEmpty()) {
            // 无模式设备（纯传感器）：不声明 state 变量
        } else {
            // 单模式和多模式统一处理：用模式名作为变量名
            for (String mode : smv.getModes()) {
                List<String> modeStateList = smv.getModeStates().get(mode);
                if (modeStateList == null || modeStateList.isEmpty()) {
                    throw SmvGenerationException.templateInvalid(smv.getVarName(),
                            "Mode '" + mode + "' has no declared states");
                }
                content.append("\n\t").append(mode).append(": {").append(String.join(", ", modeStateList)).append("};");
            }
        }
    }

    private void appendInternalVariables(StringBuilder content, DeviceSmvData smv) {
        for (DeviceManifest.InternalVariable var : smv.getVariables()) {
            String varDef;
            if (var.getValues() != null && !var.getValues().isEmpty()) {
                // 去除空格，与 sample.smv 一致（如 "not authorized" → "notauthorized"）
                List<String> cleanValues = new ArrayList<>();
                for (String v : var.getValues()) {
                    cleanValues.add(v.replace(" ", ""));
                }
                varDef = "{" + String.join(", ", cleanValues) + "}";
            } else if (var.getLowerBound() != null && var.getUpperBound() != null) {
                int lower = var.getLowerBound();
                int upper = var.getUpperBound();
                varDef = lower + ".." + upper;
            } else {
                throw SmvGenerationException.templateInvalid(smv.getVarName(),
                        "Variable '" + var.getName() + "' has no declared value domain");
            }
            content.append("\n\t").append(var.getName()).append(": ").append(varDef).append(";");
        }
    }

    private void appendSignalVariables(StringBuilder content, DeviceSmvData smv) {
        for (DeviceSmvData.SignalInfo sig : smv.getSignalVars()) {
            content.append("\n\t").append(sig.getName()).append(": boolean;");
        }
    }

    /**
     * Emits each numeric impact rate as a {@code DEFINE} over the device's current state.
     *
     * <p>MEDIC §3.1, Fig. 2b combines the device effect with the environment step
     * <em>contemporaneously</em>: {@code v' - v ∈ [lower + env.D.v, upper + env.D.v]}. A state
     * variable cannot express that, because {@code next(a_v)} reads the unprimed rate while
     * {@code next(rate)} is derived from the current mode — so a device that starts already cooling
     * applied nothing on the first transition, and switching it on took two steps to move the
     * value. NuSMV confirmed the lag: with a rate variable, an AC initialised to {@code cool} left
     * {@code AG (a_temperature = 30 -> AX a_temperature <= 27)} false, and the {@code DEFINE} form
     * makes it true. A definition is also strictly cheaper — it adds no BDD variable and needs no
     * {@code init}/{@code next}.
     */
    private void appendVariableRates(StringBuilder content, DeviceSmvData smv) {
        for (String varName : smv.getImpactedVariables()) {
            // Only numeric impacted variables use a rate. Enum/boolean dynamics use Value.
            if (!isNumericVariable(smv, varName)) continue;
            content.append("\n\t").append(varName).append("_rate := ")
                   .append(rateDefinitionExpression(smv, varName)).append(";");
        }
    }

    /**
     * The rate a device currently applies to one impacted variable, as a case over its live state.
     * Every state without a declared {@code Dynamics} entry contributes no effect.
     */
    private String rateDefinitionExpression(DeviceSmvData smv, String varName) {
        StringBuilder expression = new StringBuilder("case ");
        if (smv.getManifest() != null && smv.getManifest().getWorkingStates() != null) {
            for (DeviceManifest.WorkingState state : smv.getManifest().getWorkingStates()) {
                if (state.getDynamics() == null) continue;
                for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                    if (!varName.equals(dynamic.getVariableName())) continue;
                    if (smv.getModes() == null || smv.getModes().isEmpty()) {
                        throw SmvGenerationException.templateInvalid(smv.getVarName(),
                                "WorkingState Dynamics requires Modes so state '" + state.getName()
                                        + "' can guard impacted variable '" + varName + "'");
                    }
                    /*
                     * A null guard would be a *silent* omission — this state's physical effect on the variable
                     * dropped, the `esac` falling through to `TRUE : 0`, and `modelComplete` still reporting true
                     * because nothing incremented `disabledRuleCount`. In a tool that answers "is this system
                     * safe", a device modelled as having no effect is the worst possible failure.
                     *
                     * It cannot happen. `stateGuardExpression` returns null only when *every* mode segment of the
                     * state name is empty, and the template validator rejects that at the write boundary.
                     * Measured against the running API: `";"`, `"_;_"`, `" ; "` all answer **400 "WorkingState
                     * must define a concrete value for mode 'power'"**, and `"_"` / `";;"` are rejected on
                     * semicolon count. So the `continue` is unreachable, and it is left as a fail-safe rather
                     * than converted into a recorded skip that no input can produce.
                     *
                     * If the validator ever admits a wildcard state, this must become
                     * `warnings.recordDisabledRule(...)` — the count is what keeps `modelComplete` honest.
                     */
                    String guard = stateGuardExpression(smv, state.getName());
                    if (guard == null) continue;
                    expression.append(guard).append(" : ")
                              .append(parseDynamicChangeRate(smv.getVarName(), varName, dynamic))
                              .append("; ");
                }
            }
        }
        return expression.append("TRUE : 0; esac").toString();
    }

    /** Conjunction pinning every concrete mode segment of a WorkingState name. */
    private String stateGuardExpression(DeviceSmvData smv, String stateName) {
        String[] segments = stateName.split(";");
        StringBuilder guard = new StringBuilder();
        for (int index = 0; index < smv.getModes().size() && index < segments.length; index++) {
            String segment = segments[index].trim();
            if (segment.isEmpty()) continue;
            if (guard.length() > 0) guard.append(" & ");
            guard.append(smv.getModes().get(index)).append(" = ")
                 .append(DeviceSmvDataFactory.cleanStateName(segment));
        }
        return guard.length() == 0 ? null : guard.toString();
    }

    private int parseDynamicChangeRate(String deviceName, String varName,
                                       DeviceManifest.Dynamic dynamic) {
        String rawRate = dynamic.getChangeRate();
        try {
            return Integer.parseInt(rawRate != null ? rawRate.trim() : "");
        } catch (NumberFormatException e) {
            throw SmvGenerationException.templateInvalid(deviceName,
                    "WorkingState Dynamics for '" + varName
                            + "' has non-integer ChangeRate '" + rawRate + "'");
        }
    }


    private void appendPropertyVariables(StringBuilder content, DeviceSmvData smv, PropertyDimension dim) {
        String domain = (dim == PropertyDimension.TRUST) ? "{untrusted, trusted}" : "{private, public}";
        appendStatePropertyVariables(content, smv, dim);
        for (DeviceManifest.InternalVariable var : smv.getVariables()) {
            content.append("\n\t").append(dim.prefix).append(var.getName())
                   .append(": ").append(domain).append(";");
        }
    }

    private boolean appendStatePropertyVariables(StringBuilder content,
                                                 DeviceSmvData smv,
                                                 PropertyDimension dim) {
        String domain = (dim == PropertyDimension.TRUST) ? "{untrusted, trusted}" : "{private, public}";
        boolean appended = false;
        for (String mode : smv.getModes()) {
            List<String> states = smv.getModeStates().get(mode);
            if (states == null) continue;
            for (String state : states) {
                content.append("\n\t").append(dim.prefix).append(mode).append("_").append(state)
                       .append(": ").append(domain).append(";");
                appended = true;
            }
        }
        return appended;
    }

    private void appendInitialProperty(StringBuilder content, DeviceSmvData smv, boolean isAttack,
                                       PropertyDimension dim) {
        String defaultVal = (dim == PropertyDimension.TRUST) ? DEFAULT_TRUST : DEFAULT_PRIVACY;

        // 状态级 trust/privacy 初始化
        Set<String> initialized = new HashSet<>();
        for (String mode : smv.getModes()) {
            List<String> states = smv.getModeStates().get(mode);
            if (states == null) continue;

            // Bug1 修复：确定当前状态
            String currentState = smv.getCurrentModeStates().get(mode);
            if (currentState == null && smv.getCurrentState() != null) {
                // H1 修复：多模式设备 fallback 时解析分号分隔的状态
                int modeIdx = smv.getModes().indexOf(mode);
                String[] stateParts = smv.getCurrentState().split(";");
                if (modeIdx >= 0 && modeIdx < stateParts.length && !stateParts[modeIdx].trim().isEmpty()) {
                    String cleanPart = DeviceSmvDataFactory.cleanStateName(stateParts[modeIdx]);
                    if (cleanPart != null && !cleanPart.isEmpty()) {
                        currentState = cleanPart;
                    }
                }
            }

            for (String state : states) {
                String key = mode + "_" + state;
                if (!initialized.add(key)) continue;

                String value;
                if (dim == PropertyDimension.TRUST) {
                    // Bug1 修复：instanceStateTrust 仅应用于当前状态
                    boolean isCurrentState = state.equals(currentState);
                    String instanceOverride = isCurrentState ? smv.getInstanceStateTrust() : null;
                    String extractedTrust = smv.getModeStateTrust().get(key);
                    value = instanceOverride != null ? instanceOverride
                            : extractedTrust != null ? extractedTrust
                            : resolveManifestStateProperty(
                                    smv, mode, state, defaultVal, PropertyDimension.TRUST);
                } else {
                    boolean isCurrentState = state.equals(currentState);
                    String instanceOverride = isCurrentState ? smv.getInstanceStatePrivacy() : null;
                    value = instanceOverride != null ? instanceOverride
                            : resolveManifestStateProperty(
                                    smv, mode, state, defaultVal, PropertyDimension.PRIVACY);
                }
                value = DeviceSmvDataFactory.normalizeTrustPrivacy(value);

                content.append("\n\tinit(").append(dim.prefix).append(mode).append("_").append(state)
                       .append(") := ").append(value).append(";");
            }
        }

        // 内部变量级 trust/privacy 初始化
        for (DeviceManifest.InternalVariable var : smv.getVariables()) {
            String value;
            if (dim == PropertyDimension.TRUST) {
                String instanceVarTrust = smv.getInstanceVariableTrust().get(var.getName());
                value = instanceVarTrust != null ? instanceVarTrust
                        : DeviceSmvDataFactory.normalizeTrustPrivacy(var.getTrust() != null ? var.getTrust() : defaultVal);
                value = DeviceSmvDataFactory.normalizeTrustPrivacy(value);
                if (isAttack && Boolean.TRUE.equals(var.getFalsifiableWhenCompromised())) {
                    content.append("\n\tinit(trust_").append(var.getName()).append(") :=\n");
                    content.append("\tcase\n");
                    content.append("\t\tis_attack=TRUE: untrusted;\n");
                    content.append("\t\tTRUE: ").append(value).append(";\n");
                    content.append("\tesac;");
                    continue;
                }
            } else {
                String instanceVarPrivacy = smv.getInstanceVariablePrivacy().get(var.getName());
                value = instanceVarPrivacy != null ? instanceVarPrivacy
                        : DeviceSmvDataFactory.normalizeTrustPrivacy(var.getPrivacy() != null ? var.getPrivacy() : defaultVal);
                value = DeviceSmvDataFactory.normalizeTrustPrivacy(value);
            }
            content.append("\n\tinit(").append(dim.prefix).append(var.getName()).append(") := ").append(value).append(";");
        }
    }

    /** Resolve a WorkingState label directly when low-level callers did not pre-extract it. */
    private String resolveManifestStateProperty(DeviceSmvData smv,
                                                String mode,
                                                String state,
                                                String defaultVal,
                                                PropertyDimension dim) {
        if (smv.getManifest() != null && smv.getManifest().getWorkingStates() != null) {
            int modeIdx = smv.getModes().indexOf(mode);
            for (DeviceManifest.WorkingState ws : smv.getManifest().getWorkingStates()) {
                if (ws.getName() == null) continue;
                String wsName = DeviceSmvDataFactory.cleanStateName(ws.getName());
                // 单模式或非复合名：直接匹配
                if (!ws.getName().contains(";")) {
                    if (state.equals(wsName)) {
                        return normalizeWorkingStateProperty(ws, dim, defaultVal);
                    }
                } else {
                    // 多模式复合名（如 "on;locked"）：按分号拆分，匹配对应 mode 索引的段
                    String[] parts = ws.getName().split(";", -1);
                    if (modeIdx >= 0 && modeIdx < parts.length && !parts[modeIdx].trim().isEmpty()) {
                        String cleanPart = DeviceSmvDataFactory.cleanStateName(parts[modeIdx].trim());
                        if (state.equals(cleanPart)) {
                            return normalizeWorkingStateProperty(ws, dim, defaultVal);
                        }
                    }
                }
            }
        }
        return DeviceSmvDataFactory.normalizeTrustPrivacy(defaultVal);
    }

    private String normalizeWorkingStateProperty(DeviceManifest.WorkingState state,
                                                 PropertyDimension dim,
                                                 String defaultVal) {
        String value = dim == PropertyDimension.TRUST ? state.getTrust() : state.getPrivacy();
        return DeviceSmvDataFactory.normalizeTrustPrivacy(value != null ? value : defaultVal);
    }

    private void appendInitialValues(StringBuilder content, DeviceSmvData smv, boolean isAttack) {
        // 优先级：用户输入 > 模板 InitState > 非确定性
        for (String mode : smv.getModes()) {
            List<String> modeStateList = smv.getModeStates().get(mode);
            if (modeStateList == null || modeStateList.isEmpty()) continue;

            // 优先使用用户指定的当前模式状态
            boolean hasExplicitModeState = smv.getCurrentModeStates().containsKey(mode);
            String userState = smv.getCurrentModeStates().get(mode);
            if (hasExplicitModeState && (userState == null || !modeStateList.contains(userState))) {
                throw SmvGenerationException.templateInvalid(smv.getVarName(),
                        "Initial state '" + userState + "' for mode '" + mode
                                + "' is not in " + modeStateList);
            }
            if (userState == null && smv.getCurrentState() != null && smv.getModes().size() == 1) {
                // Single-mode only. `cleanStateName` deletes `;`, which is right for a lone state name and
                // wrong for a tuple: on a multi-mode device this compared the *whole* tuple, semicolons
                // stripped, against each mode's own domain, so a partial tuple leaked into the mode it had
                // deliberately left blank. Measured on a two-mode probe whose modes share the value
                // `shared` and whose InitState is `shared;distinct`: instance state `"shared;"` produced
                // `init(Beta) := shared` instead of falling through to the template's `distinct`, silently
                // overriding InitState. `"other;"` fell through correctly only because `other` happens not
                // to be in Beta's domain — the bug was invisible whenever the accident went the other way.
                //
                // A multi-mode device needs no such fallback: `currentModeStates` is already the
                // positional parse of the tuple, so a blank segment means "not specified for this mode"
                // and must reach the InitState branch below.
                String cleanCurrentState = DeviceSmvDataFactory.cleanStateName(smv.getCurrentState());
                if (modeStateList.contains(cleanCurrentState)) {
                    userState = cleanCurrentState;
                } else {
                    throw SmvGenerationException.templateInvalid(smv.getVarName(),
                            "Initial state '" + smv.getCurrentState() + "' for mode '" + mode
                                    + "' is not in " + modeStateList);
                }
            }

            if (userState != null && modeStateList.contains(userState)) {
                content.append("\n\tinit(").append(mode).append(") := ").append(userState).append(";");
            } else {
                // fallback: 模板 InitState
                String templateState = smv.getTemplateInitModeStates().get(mode);
                if (templateState != null) {
                    if (!modeStateList.contains(templateState)) {
                        throw SmvGenerationException.templateInvalid(smv.getVarName(),
                                "Template initial state '" + templateState + "' for mode '" + mode
                                        + "' is not in " + modeStateList);
                    }
                    content.append("\n\tinit(").append(mode).append(") := ").append(templateState).append(";");
                } else {
                    // 最终 fallback: 非确定性初始状态
                    content.append("\n\tinit(").append(mode).append(") := {")
                           .append(String.join(", ", modeStateList)).append("};");
                }
            }
        }

        for (DeviceManifest.InternalVariable var : smv.getVariables()) {
            if (var.getIsInside() == null || !var.getIsInside()) {
                // 外部变量由主模块的 appendSensorEnvAssignments 用简单赋值处理，
                // 不能同时有 init() 和简单赋值
                continue;
            }
            String initValue = validateInternalInitValue(smv, var);
            if (isAttack && Boolean.TRUE.equals(var.getFalsifiableWhenCompromised())) {
                content.append("\n\tinit(").append(var.getName()).append(") :=\n")
                        .append("\tcase\n")
                        .append("\t\tis_attack=TRUE: ").append(variableDomainExpression(var)).append(";\n")
                        .append("\t\tTRUE: ")
                        .append(initValue != null ? initValue : variableDomainExpression(var)).append(";\n")
                        .append("\tesac;");
            } else if (initValue != null) {
                content.append("\n\tinit(").append(var.getName()).append(") := ").append(initValue).append(";");
            }
        }

        for (DeviceSmvData.SignalInfo sig : smv.getSignalVars()) {
            content.append("\n\tinit(").append(sig.getName()).append(") := ").append(NUSMV_FALSE).append(";");
        }

        // No init for an impact rate: it is a DEFINE over the current state, so it already holds the
        // device's live effect in step 0 instead of starting at zero and lagging by one step.
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
        throw SmvGenerationException.templateInvalid("device",
                "Variable '" + variable.getName() + "' has no declared value domain");
    }

    private boolean isNumericVariable(DeviceSmvData smv, String varName) {
        DeviceManifest.InternalVariable impactedDomain = smv.getImpactedEnvironmentVariables().get(varName);
        return impactedDomain != null
                && impactedDomain.getLowerBound() != null
                && impactedDomain.getUpperBound() != null;
    }

    /** Validate explicit local initial values without changing their authored meaning. */
    private String validateInternalInitValue(DeviceSmvData smv, DeviceManifest.InternalVariable var) {
        String userValue = smv.getVariableValues().get(var.getName());

        if (var.getValues() != null && !var.getValues().isEmpty()) {
            // 枚举变量
            List<String> cleanValues = new ArrayList<>();
            for (String v : var.getValues()) {
                cleanValues.add(v.replace(" ", ""));
            }
            if (userValue == null) {
                // Shared Board/explorer defaults retain the starting state's meaning; SMV cleans spaces.
                return DeviceManifestModes.localInitialValue(
                        smv.getManifest(), var, smv.getCurrentState()).replace(" ", "");
            }
            String cleanUser = userValue.replace(" ", "");
            if (cleanValues.contains(cleanUser)) {
                return cleanUser;
            }
            throw SmvGenerationException.templateInvalid(smv.getVarName(),
                    "Initial value '" + userValue + "' for enum variable '" + var.getName()
                            + "' is not in " + cleanValues);
        }

        if (var.getLowerBound() != null && var.getUpperBound() != null) {
            // 数值变量
            int lower = var.getLowerBound();
            int upper = var.getUpperBound();
            if (userValue == null) {
                return DeviceManifestModes.localInitialValue(smv.getManifest(), var, smv.getCurrentState());
            }
            try {
                int parsed = Integer.parseInt(userValue.trim());
                if (parsed < lower || parsed > upper) {
                    throw SmvGenerationException.templateInvalid(smv.getVarName(),
                            "Initial value " + parsed + " for variable '" + var.getName()
                                    + "' is outside [" + lower + ".." + upper + "]");
                }
                return String.valueOf(parsed);
            } catch (NumberFormatException e) {
                throw SmvGenerationException.templateInvalid(smv.getVarName(),
                        "Initial value '" + userValue + "' for variable '" + var.getName()
                                + "' is not a valid integer");
            }
        }

        if (userValue != null) {
            throw SmvGenerationException.templateInvalid(smv.getVarName(),
                    "Variable '" + var.getName() + "' has an explicit value but no declared domain");
        }
        // No explicit value and no declared domain: preserve the existing non-deterministic state.
        return null;
    }

}
