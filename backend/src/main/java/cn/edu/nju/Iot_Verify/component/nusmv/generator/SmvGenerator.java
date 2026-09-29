package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvRuleCommentWriter;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvSpecificationBuilder;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackPointDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * SMV 生成器
 *
 * 职责：协调数据准备 + 各模块构建器，生成完整 NuSMV 模型文件
 * 同时提供 buildDeviceSmvMap（供 trace 解析复用）和 generateSpecString（预览）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmvGenerator {

    private final DeviceSmvDataFactory deviceSmvDataFactory;
    private final SmvDeviceModuleBuilder deviceModuleBuilder;
    private final SmvRuleCommentWriter ruleCommentWriter;
    private final SmvMainModuleBuilder mainModuleBuilder;
    private final SmvSpecificationBuilder specBuilder;
    private final SmvModelValidator modelValidator;

    /** Parsed manifests and device semantics captured together at the model boundary. */
    public record CapturedDeviceModel(Map<String, DeviceManifest> templateManifests,
                                      Map<String, DeviceSmvData> deviceSmvMap) {
        public CapturedDeviceModel {
            templateManifests = Collections.unmodifiableMap(new LinkedHashMap<>(templateManifests));
            deviceSmvMap = Collections.unmodifiableMap(new LinkedHashMap<>(deviceSmvMap));
        }
    }

    /** generate() 的返回值，包含 SMV 文件和构建过程中使用的 deviceSmvMap */
    public record GenerateResult(File smvFile,
                                 Map<String, DeviceSmvData> deviceSmvMap,
                                 List<String> generationWarnings,
                                 int disabledRuleCount,
                                 int skippedSpecCount,
                                 List<ModelGenerationIssueDto> generationIssues,
                                 List<SmvGenerationContext.EmittedSpec> emittedSpecs) {
        public GenerateResult(File smvFile, Map<String, DeviceSmvData> deviceSmvMap) {
            this(smvFile, deviceSmvMap, List.of(), 0, 0, List.of(), List.of());
        }

        public GenerateResult(File smvFile,
                              Map<String, DeviceSmvData> deviceSmvMap,
                              List<String> generationWarnings,
                              int disabledRuleCount,
                              int skippedSpecCount,
                              List<SmvGenerationContext.EmittedSpec> emittedSpecs) {
            this(smvFile, deviceSmvMap, generationWarnings, disabledRuleCount, skippedSpecCount,
                    List.of(), emittedSpecs);
        }

        private static GenerateResult from(File smvFile,
                                           Map<String, DeviceSmvData> deviceSmvMap,
                                           SmvGenerationContext context) {
            SmvGenerationContext.WarningSnapshot snapshot = context.warningsSnapshot();
            return new GenerateResult(smvFile, deviceSmvMap, snapshot.checkLogWarnings(),
                    snapshot.disabledRuleCount(), snapshot.skippedSpecCount(), snapshot.generationIssues(),
                    snapshot.emittedSpecs());
        }
    }

    public enum GeneratePurpose {
        VERIFICATION,
        SIMULATION
    }

    /**
     * Diagnostic identity for retained NuSMV temp directories.
     *
     * <p>The generated directory name is not a security boundary; it is an operator-facing
     * breadcrumb so a retained {@code model.smv/request.json/output.txt/result.json}
     * set can be traced back to the user and, when available, the async task or fix trace
     * that produced it.</p>
     */
    public record TempModelContext(String scope, Long id) {
        public static TempModelContext direct() {
            return new TempModelContext("direct", null);
        }

        public static TempModelContext sync() {
            return new TempModelContext("sync", null);
        }

        public static TempModelContext task(Long taskId) {
            return new TempModelContext("task", taskId);
        }

        public static TempModelContext savedTrace() {
            return new TempModelContext("saved_trace", null);
        }

        public static TempModelContext fixTrace(Long traceId) {
            return new TempModelContext("trace", traceId);
        }

        String directorySegment() {
            String safeScope = safePart(scope == null || scope.isBlank() ? "direct" : scope);
            if (id == null) {
                return safeScope;
            }
            return safeScope + "_" + safePart(id);
        }
    }

    /**
     * 生成完整的 NuSMV 模型文件并写入临时目录
     */
    public GenerateResult generate(Long userId, List<DeviceVerificationDto> devices,
                                   List<RuleDto> rules, List<SpecificationDto> specs,
                                   AttackScenarioDto attackScenario, boolean enablePrivacy) throws IOException {
        return generate(userId, devices, rules, specs, attackScenario, enablePrivacy,
                GeneratePurpose.VERIFICATION);
    }

    public GenerateResult generate(Long userId, List<DeviceVerificationDto> devices,
                                   List<RuleDto> rules, List<SpecificationDto> specs,
                                   AttackScenarioDto attackScenario, boolean enablePrivacy,
                                   GeneratePurpose purpose) throws IOException {
        return generateWithEnvironment(userId, devices, null, rules, specs,
                attackScenario, enablePrivacy, purpose, TempModelContext.direct());
    }

    public GenerateResult generate(Long userId, List<DeviceVerificationDto> devices,
                                   List<RuleDto> rules, List<SpecificationDto> specs,
                                   AttackScenarioDto attackScenario, boolean enablePrivacy,
                                   GeneratePurpose purpose,
                                   TempModelContext tempModelContext) throws IOException {
        return generateWithEnvironment(userId, devices, null, rules, specs,
                attackScenario, enablePrivacy, purpose, tempModelContext);
    }

    public GenerateResult generateWithEnvironment(Long userId,
                                                  List<DeviceVerificationDto> devices,
                                                  List<BoardEnvironmentVariableDto> environmentVariables,
                                                  List<RuleDto> rules,
                                                  List<SpecificationDto> specs,
                                                  AttackScenarioDto attackScenario,
                                                  boolean enablePrivacy) throws IOException {
        return generateWithEnvironment(userId, devices, environmentVariables, rules, specs,
                attackScenario, enablePrivacy, GeneratePurpose.VERIFICATION);
    }

    public GenerateResult generateWithEnvironment(Long userId,
                                                  List<DeviceVerificationDto> devices,
                                                  List<BoardEnvironmentVariableDto> environmentVariables,
                                                  List<RuleDto> rules,
                                                  List<SpecificationDto> specs,
                                                  AttackScenarioDto attackScenario,
                                                  boolean enablePrivacy,
                                                  GeneratePurpose purpose) throws IOException {
        return generateWithEnvironment(userId, devices, environmentVariables, rules, specs,
                attackScenario, enablePrivacy, purpose, TempModelContext.direct());
    }

    public GenerateResult generateWithEnvironment(Long userId,
                                                  List<DeviceVerificationDto> devices,
                                                  List<BoardEnvironmentVariableDto> environmentVariables,
                                                  List<RuleDto> rules,
                                                  List<SpecificationDto> specs,
                                                  AttackScenarioDto attackScenario,
                                                  boolean enablePrivacy,
                                                  GeneratePurpose purpose,
                                                  TempModelContext tempModelContext) throws IOException {
        AttackScenarioDto safeAttackScenario = validateAttackScenario(attackScenario);
        if (devices == null || devices.isEmpty()) {
            throw SmvGenerationException.invalidBuilderInput("SmvGenerator", "devices", "must not be null or empty");
        }
        List<SpecificationDto> safeSpecs = (specs != null) ? specs : List.of();
        List<RuleDto> safeRules = (rules != null) ? rules : List.of();
        log.info("Generating NuSMV model: userId={}, devices={}, rules={}, specs={}, attack={}, attackBudget={}, privacy={}",
                userId, devices.size(), safeRules.size(), safeSpecs.size(), safeAttackScenario.isEnabled(),
                safeAttackScenario.effectiveBudget(), enablePrivacy);

        Map<String, DeviceSmvData> deviceSmvMap = deviceSmvDataFactory.buildDeviceSmvMap(userId, devices);
        return generateWithResolvedDeviceModel(userId, devices, environmentVariables, safeRules, safeSpecs,
                safeAttackScenario, enablePrivacy, purpose, tempModelContext, deviceSmvMap);
    }

    /**
     * Generate from manifests resolved at request acceptance. This is the async-safe path: no mutable
     * template repository read occurs between validation and model generation.
     */
    public GenerateResult generateWithResolvedDeviceModel(Long userId,
                                                           List<DeviceVerificationDto> devices,
                                                           List<BoardEnvironmentVariableDto> environmentVariables,
                                                           List<RuleDto> rules,
                                                           List<SpecificationDto> specs,
                                                           AttackScenarioDto attackScenario,
                                                           boolean enablePrivacy,
                                                           GeneratePurpose purpose,
                                                           TempModelContext tempModelContext,
                                                           Map<String, DeviceSmvData> resolvedDeviceSmvMap)
            throws IOException {
        return generateWithResolvedDeviceModel(userId, devices, environmentVariables, rules, specs,
                attackScenario, enablePrivacy, purpose, tempModelContext, resolvedDeviceSmvMap, List.of());
    }

    /**
     * As above, with {@code guardProbes} appended to the main module. The probes are {@code DEFINE}s, so
     * they change what a counterexample reports, never which specifications hold.
     */
    public GenerateResult generateWithResolvedDeviceModel(Long userId,
                                                           List<DeviceVerificationDto> devices,
                                                           List<BoardEnvironmentVariableDto> environmentVariables,
                                                           List<RuleDto> rules,
                                                           List<SpecificationDto> specs,
                                                           AttackScenarioDto attackScenario,
                                                           boolean enablePrivacy,
                                                           GeneratePurpose purpose,
                                                           TempModelContext tempModelContext,
                                                           Map<String, DeviceSmvData> resolvedDeviceSmvMap,
                                                           List<GuardProbe> guardProbes)
            throws IOException {
        AttackScenarioDto safeAttackScenario = validateAttackScenario(attackScenario);
        if (devices == null || devices.isEmpty()) {
            throw SmvGenerationException.invalidBuilderInput("SmvGenerator", "devices", "must not be null or empty");
        }
        List<SpecificationDto> safeSpecs = specs != null ? specs : List.of();
        List<RuleDto> safeRules = rules != null ? rules : List.of();
        Map<String, DeviceSmvData> deviceSmvMap = requireCompleteResolvedDeviceMap(devices, resolvedDeviceSmvMap);
        applyEnvironmentPoolLabels(deviceSmvMap, environmentVariables);
        SmvGenerationContext context = SmvGenerationContext.collecting();
        String smvContent = buildSmvContent(deviceSmvMap, userId, devices, environmentVariables, safeRules, safeSpecs,
                safeAttackScenario, enablePrivacy, context, guardProbes != null ? guardProbes : List.of());

        Path tempDir = Files.createTempDirectory(resolveTempDirPrefix(purpose, userId, tempModelContext));
        File smvFile = tempDir.resolve("model.smv").toFile();

        try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(smvFile), StandardCharsets.UTF_8))) {
            writer.print(smvContent);
        }

        log.debug("Generated NuSMV model file: {}", smvFile.getAbsolutePath());
        return GenerateResult.from(smvFile, deviceSmvMap, context);
    }

    /**
     * 构建设备 SMV 数据映射（供外部直接调用，如无 GenerateResult 可用时）
     */
    public Map<String, DeviceSmvData> buildDeviceSmvMap(Long userId,
                                                         List<DeviceVerificationDto> devices) {
        return deviceSmvDataFactory.buildDeviceSmvMap(userId, devices);
    }

    public CapturedDeviceModel captureDeviceModel(Long userId,
                                                   List<DeviceVerificationDto> devices) {
        Map<String, DeviceManifest> manifests = new LinkedHashMap<>();
        Map<String, DeviceSmvData> deviceSmvMap =
                deviceSmvDataFactory.buildDeviceSmvMap(userId, devices, manifests);
        return new CapturedDeviceModel(manifests, deviceSmvMap);
    }

    /** Resolve only from manifests captured with the submitted board snapshot. */
    public CapturedDeviceModel captureDeviceModelFromTemplateSnapshots(
            List<DeviceVerificationDto> devices,
            Map<String, DeviceManifest> templateSnapshots) {
        if (templateSnapshots == null) {
            throw SmvGenerationException.smvGenerationError(
                    "Captured device-template manifests are missing");
        }
        Map<String, DeviceManifest> referenced = new LinkedHashMap<>();
        for (DeviceVerificationDto device : devices == null ? List.<DeviceVerificationDto>of() : devices) {
            if (device == null || device.getTemplateName() == null || device.getTemplateName().isBlank()) {
                continue;
            }
            String templateName = device.getTemplateName();
            DeviceManifest manifest = templateSnapshots.get(templateName);
            if (manifest != null) {
                referenced.putIfAbsent(templateName, manifest);
            }
        }
        Map<String, DeviceSmvData> deviceSmvMap =
                deviceSmvDataFactory.buildDeviceSmvMapFromTemplateSnapshots(devices, referenced);
        return new CapturedDeviceModel(referenced, deviceSmvMap);
    }

    public Map<String, DeviceSmvData> buildDeviceSmvMapFromTemplateSnapshots(
            List<DeviceVerificationDto> devices,
            Map<String, DeviceManifest> templateSnapshots) {
        return deviceSmvDataFactory.buildDeviceSmvMapFromTemplateSnapshots(devices, templateSnapshots);
    }

    /**
     * Generate a parameterized SMV model for ActFeedback §5 fix strategies.
     * Uses negated spec and FROZENVAR parameterization for threshold/condition solving.
     */
    public GenerateResult generateParameterized(Long userId, List<DeviceVerificationDto> devices,
                                                 List<RuleDto> rules, List<SpecificationDto> specs,
                                                 AttackScenarioDto attackScenario, boolean enablePrivacy,
                                                 ParameterizationConfig config) throws IOException {
        return generateParameterizedWithEnvironment(userId, devices, null, rules, specs,
                attackScenario, enablePrivacy, config, TempModelContext.direct());
    }

    public GenerateResult generateParameterized(Long userId, List<DeviceVerificationDto> devices,
                                                 List<RuleDto> rules, List<SpecificationDto> specs,
                                                 AttackScenarioDto attackScenario, boolean enablePrivacy,
                                                 ParameterizationConfig config,
                                                 TempModelContext tempModelContext) throws IOException {
        return generateParameterizedWithEnvironment(userId, devices, null, rules, specs,
                attackScenario, enablePrivacy, config, tempModelContext);
    }

    public GenerateResult generateParameterizedWithEnvironment(Long userId,
                                                               List<DeviceVerificationDto> devices,
                                                               List<BoardEnvironmentVariableDto> environmentVariables,
                                                               List<RuleDto> rules,
                                                               List<SpecificationDto> specs,
                                                               AttackScenarioDto attackScenario,
                                                               boolean enablePrivacy,
                                                               ParameterizationConfig config) throws IOException {
        return generateParameterizedWithEnvironment(userId, devices, environmentVariables, rules, specs,
                attackScenario, enablePrivacy, config, TempModelContext.direct());
    }

    public GenerateResult generateParameterizedWithEnvironment(Long userId,
                                                               List<DeviceVerificationDto> devices,
                                                               List<BoardEnvironmentVariableDto> environmentVariables,
                                                               List<RuleDto> rules,
                                                               List<SpecificationDto> specs,
                                                               AttackScenarioDto attackScenario,
                                                               boolean enablePrivacy,
                                                               ParameterizationConfig config,
                                                               TempModelContext tempModelContext) throws IOException {
        AttackScenarioDto safeAttackScenario = validateAttackScenario(attackScenario);
        if (devices == null || devices.isEmpty()) {
            throw SmvGenerationException.invalidBuilderInput("SmvGenerator", "devices", "must not be null or empty");
        }
        List<SpecificationDto> safeSpecs = (specs != null) ? specs : List.of();
        List<RuleDto> safeRules = (rules != null) ? rules : List.of();

        Map<String, DeviceSmvData> deviceSmvMap = deviceSmvDataFactory.buildDeviceSmvMap(userId, devices);
        applyEnvironmentPoolLabels(deviceSmvMap, environmentVariables);
        SmvGenerationContext context = SmvGenerationContext.collecting();
        String smvContent = buildParameterizedSmvContent(deviceSmvMap, userId, devices, environmentVariables, safeRules, safeSpecs,
                safeAttackScenario, enablePrivacy, config, context);

        Path tempDir = Files.createTempDirectory(resolveFixTempDirPrefix(userId, tempModelContext));
        File smvFile = tempDir.resolve("model.smv").toFile();

        try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(smvFile), StandardCharsets.UTF_8))) {
            writer.print(smvContent);
        }

        log.debug("Generated parameterized NuSMV model: {}", smvFile.getAbsolutePath());
        return GenerateResult.from(smvFile, deviceSmvMap, context);
    }

    public GenerateResult generateParameterizedWithResolvedDeviceModel(
            Long userId,
            List<DeviceVerificationDto> devices,
            List<BoardEnvironmentVariableDto> environmentVariables,
            List<RuleDto> rules,
            List<SpecificationDto> specs,
            AttackScenarioDto attackScenario,
            boolean enablePrivacy,
            ParameterizationConfig config,
            TempModelContext tempModelContext,
            Map<String, DeviceSmvData> resolvedDeviceSmvMap) throws IOException {
        AttackScenarioDto safeAttackScenario = validateAttackScenario(attackScenario);
        if (devices == null || devices.isEmpty()) {
            throw SmvGenerationException.invalidBuilderInput("SmvGenerator", "devices", "must not be null or empty");
        }
        List<SpecificationDto> safeSpecs = specs != null ? specs : List.of();
        List<RuleDto> safeRules = rules != null ? rules : List.of();
        Map<String, DeviceSmvData> deviceSmvMap = requireCompleteResolvedDeviceMap(devices, resolvedDeviceSmvMap);
        applyEnvironmentPoolLabels(deviceSmvMap, environmentVariables);
        SmvGenerationContext context = SmvGenerationContext.collecting();
        String smvContent = buildParameterizedSmvContent(
                deviceSmvMap, userId, devices, environmentVariables, safeRules, safeSpecs,
                safeAttackScenario, enablePrivacy, config, context);

        Path tempDir = Files.createTempDirectory(resolveFixTempDirPrefix(userId, tempModelContext));
        File smvFile = tempDir.resolve("model.smv").toFile();
        try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(smvFile), StandardCharsets.UTF_8))) {
            writer.print(smvContent);
        }
        log.debug("Generated parameterized NuSMV model from captured templates: {}", smvFile.getAbsolutePath());
        return GenerateResult.from(smvFile, deviceSmvMap, context);
    }

    // ==================== 内部方法 ====================

    private AttackScenarioDto validateAttackScenario(AttackScenarioDto attackScenario) {
        if (attackScenario == null) {
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvGenerator", "attackScenario", "must not be null");
        }
        AttackScenarioDto scenario = attackScenario;
        if (scenario.getMode() == null) {
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvGenerator", "attackScenario.mode", "must not be null");
        }
        List<AttackPointDto> points = scenario.getPoints() != null ? scenario.getPoints() : List.of();
        if (scenario.getMode() == AttackScenarioDto.Mode.NONE) {
            if ((scenario.getBudget() != null && scenario.getBudget() != 0) || !points.isEmpty()) {
                throw SmvGenerationException.invalidBuilderInput(
                        "SmvGenerator", "attackScenario", "NONE must not contain a budget or attack points");
            }
            return scenario;
        }
        if (scenario.getMode() == AttackScenarioDto.Mode.ANY_UP_TO_BUDGET) {
            int budget = scenario.getBudget() != null ? scenario.getBudget() : 0;
            if (budget < 1 || budget > 50 || !points.isEmpty()) {
                throw SmvGenerationException.invalidBuilderInput(
                        "SmvGenerator", "attackScenario",
                        "ANY_UP_TO_BUDGET requires a 1..50 budget and no explicit points");
            }
            return scenario;
        }
        if ((scenario.getBudget() != null && scenario.getBudget() != 0)
                || points.isEmpty() || points.size() > 50) {
            throw SmvGenerationException.invalidBuilderInput(
                    "SmvGenerator", "attackScenario",
                    "EXACT_POINTS requires 1..50 explicit points and no budget");
        }
        Set<String> identities = new HashSet<>();
        for (AttackPointDto point : points) {
            boolean validDevice = point != null && point.getKind() == AttackPointDto.Kind.DEVICE
                    && point.getDeviceId() != null && !point.getDeviceId().isBlank()
                    && point.getRuleId() == null;
            boolean validLink = point != null && point.getKind() == AttackPointDto.Kind.AUTOMATION_LINK
                    && point.getRuleId() != null && point.getRuleId() > 0
                    && (point.getDeviceId() == null || point.getDeviceId().isBlank());
            if ((!validDevice && !validLink) || !identities.add(point.identityKey())) {
                throw SmvGenerationException.invalidBuilderInput(
                        "SmvGenerator", "attackScenario.points",
                        "must contain distinct well-formed device or automation-link points");
            }
        }
        return scenario;
    }

    private Map<String, DeviceSmvData> requireCompleteResolvedDeviceMap(
            List<DeviceVerificationDto> devices,
            Map<String, DeviceSmvData> resolvedDeviceSmvMap) {
        if (resolvedDeviceSmvMap == null) {
            throw SmvGenerationException.smvGenerationError("Captured device model is missing");
        }
        Map<String, DeviceSmvData> copy = new LinkedHashMap<>(resolvedDeviceSmvMap);
        for (DeviceVerificationDto device : devices) {
            if (device != null && device.getVarName() != null && !copy.containsKey(device.getVarName())) {
                throw SmvGenerationException.smvGenerationError(
                        "Captured device model is missing device '" + device.getVarName() + "'");
            }
        }
        return copy;
    }

    private void applyEnvironmentPoolLabels(Map<String, DeviceSmvData> deviceSmvMap,
                                            List<BoardEnvironmentVariableDto> environmentVariables) {
        if (deviceSmvMap == null || deviceSmvMap.isEmpty()
                || environmentVariables == null || environmentVariables.isEmpty()) {
            return;
        }

        Map<String, BoardEnvironmentVariableDto> environmentByName = new LinkedHashMap<>();
        for (BoardEnvironmentVariableDto variable : environmentVariables) {
            if (variable == null || variable.getName() == null || variable.getName().isBlank()) {
                continue;
            }
            environmentByName.putIfAbsent(variable.getName().trim(), variable);
        }

        // `sharedDeclarations`, not `envVariables`: the latter is the rule/spec *source-capability*
        // set and excludes affect-only declarations (`Reads: false`). Labels are not a read — the
        // device module declares `trust_<name>`/`privacy_<name>` for every declared variable and
        // initialises them from these instance overrides — so keying the pool's labels off the
        // capability set silently dropped them for exactly the variables a device only writes.
        // The Environment Pool renders those rows as editable (they enter through
        // `ImpactedVariables`), so a user could set a source label, have it persist, and get a model
        // built from the template value instead.
        for (DeviceSmvData smv : deviceSmvMap.values()) {
            if (smv == null || smv.getSharedDeclarations() == null) {
                continue;
            }
            for (String variableName : smv.getSharedDeclarations().keySet()) {
                BoardEnvironmentVariableDto environment = environmentByName.get(variableName);
                if (environment == null) {
                    continue;
                }
                String trust = DeviceSmvDataFactory.normalizeTrustPrivacy(environment.getTrust());
                if (trust != null) {
                    smv.getInstanceVariableTrust().put(variableName, trust);
                }
                String privacy = DeviceSmvDataFactory.normalizeTrustPrivacy(environment.getPrivacy());
                if (privacy != null) {
                    smv.getInstanceVariablePrivacy().put(variableName, privacy);
                }
            }
        }
    }

    private String buildSmvContent(Map<String, DeviceSmvData> deviceSmvMap,
                                   Long userId,
                                   List<DeviceVerificationDto> devices,
                                   List<BoardEnvironmentVariableDto> environmentVariables,
                                   List<RuleDto> rules,
                                   List<SpecificationDto> specs,
                                   AttackScenarioDto attackScenario,
                                   boolean enablePrivacy,
                                   SmvGenerationContext context,
                                   List<GuardProbe> guardProbes) {
        return buildSmvContentInternal(deviceSmvMap, userId, devices, environmentVariables, rules, specs,
                attackScenario, enablePrivacy, null, context, guardProbes);
    }

    private String buildParameterizedSmvContent(Map<String, DeviceSmvData> deviceSmvMap,
                                                Long userId,
                                                List<DeviceVerificationDto> devices,
                                                List<BoardEnvironmentVariableDto> environmentVariables,
                                                List<RuleDto> rules,
                                                List<SpecificationDto> specs,
                                                AttackScenarioDto attackScenario,
                                                boolean enablePrivacy,
                                                ParameterizationConfig config,
                                                SmvGenerationContext context) {
        return buildSmvContentInternal(deviceSmvMap, userId, devices, environmentVariables, rules, specs,
                attackScenario, enablePrivacy, config, context, List.of());
    }

    private String buildSmvContentInternal(Map<String, DeviceSmvData> deviceSmvMap,
                                           Long userId,
                                           List<DeviceVerificationDto> devices,
                                           List<BoardEnvironmentVariableDto> environmentVariables,
                                           List<RuleDto> rules,
                                           List<SpecificationDto> specs,
                                           AttackScenarioDto attackScenario,
                                           boolean enablePrivacy,
                                           ParameterizationConfig config,
                                           SmvGenerationContext context,
                                           List<GuardProbe> guardProbes) {

        AttackScenarioDto safeAttackScenario = validateAttackScenario(attackScenario);
        log.debug("Building SMV content: {} devices, {} rules, {} specs, attack={}, attackBudget={}, privacy={}",
            devices.size(), rules != null ? rules.size() : 0, specs != null ? specs.size() : 0,
            safeAttackScenario.isEnabled(), safeAttackScenario.effectiveBudget(), enablePrivacy);

        // 前置校验：P1/P2/P3/P5 — 在生成 SMV 文本前检测模板数据不合法项
        modelValidator.validate(deviceSmvMap);

        // 前置校验：隐私规约需要 enablePrivacy=true
        if (!enablePrivacy && specs != null) {
            validateNoPrivacySpecs(specs);
        }

        StringBuilder content = new StringBuilder();

        content.append(ruleCommentWriter.build(rules));

        AttackSurface attackSurface = AttackSurface.analyze(rules, deviceSmvMap);
        Set<String> generatedModules = new HashSet<>();
        for (DeviceVerificationDto device : devices) {
            DeviceSmvData smv = deviceSmvMap.get(device.getVarName());
            if (smv != null && generatedModules.add(smv.getModuleName())) {
                content.append(deviceModuleBuilder.build(
                        smv, deviceAttackActivation(safeAttackScenario, attackSurface, smv.getVarName()),
                        enablePrivacy));
            }
        }

        content.append(config != null
                ? mainModuleBuilder.buildParameterized(userId, devices, environmentVariables, rules,
                        deviceSmvMap, safeAttackScenario, enablePrivacy, config, context)
                : mainModuleBuilder.build(userId, devices, environmentVariables, rules, deviceSmvMap,
                        safeAttackScenario, enablePrivacy, context, guardProbes));

        if (config != null) {
            // Only emit the negated spec (¬ρ)
            content.append(specBuilder.buildNegated(specs, config.getNegatedSpecIndex(),
                    deviceSmvMap, enablePrivacy, context));
        } else {
            content.append(specBuilder.build(specs, deviceSmvMap, enablePrivacy, context));
        }

        return content.toString();
    }

    private AttackActivation deviceAttackActivation(AttackScenarioDto attackScenario,
                                                      AttackSurface attackSurface,
                                                      String deviceId) {
        if (!attackScenario.isEnabled() || !attackSurface.includesDevice(deviceId)) {
            return AttackActivation.DISABLED;
        }
        if (attackScenario.getMode() == AttackScenarioDto.Mode.ANY_UP_TO_BUDGET) {
            return AttackActivation.NONDETERMINISTIC;
        }
        return attackScenario.selectedDeviceIds().contains(deviceId)
                ? AttackActivation.FIXED_COMPROMISED
                : AttackActivation.FIXED_SAFE;
    }

    private String resolveTempDirPrefix(GeneratePurpose purpose, Long userId, TempModelContext tempModelContext) {
        String prefix = purpose == GeneratePurpose.SIMULATION ? "nusmv_sim" : "nusmv_verify";
        return prefix + "_user_" + safePart(userId) + "_"
                + safeContext(tempModelContext).directorySegment() + "_";
    }

    private String resolveFixTempDirPrefix(Long userId, TempModelContext tempModelContext) {
        return "nusmv_fix_user_" + safePart(userId) + "_"
                + safeContext(tempModelContext).directorySegment() + "_";
    }

    private static TempModelContext safeContext(TempModelContext tempModelContext) {
        return tempModelContext != null ? tempModelContext : TempModelContext.direct();
    }

    private static String safePart(Object value) {
        String raw = value == null ? "unknown" : String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return "unknown";
        }
        String safe = raw.replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "")
                .toLowerCase(Locale.ROOT);
        return safe.isEmpty() ? "unknown" : safe;
    }

    /**
     * 校验：当 enablePrivacy=false 时，规约中不得包含 targetType="privacy" 的条件。
     * 否则 SmvSpecificationBuilder 会生成 privacy_* 变量引用，但这些变量未被声明，
     * 导致 NuSMV 运行时报 undefined variable 错误。
     */
    private void validateNoPrivacySpecs(List<SpecificationDto> specs) {
        for (SpecificationDto spec : specs) {
            if (spec == null) continue;
            if (hasPrivacyCondition(spec.getAConditions())
                    || hasPrivacyCondition(spec.getIfConditions())
                    || hasPrivacyCondition(spec.getThenConditions())) {
                throw SmvGenerationException.privacySpecWithoutPrivacyEnabled(spec.getId());
            }
        }
    }

    private boolean hasPrivacyCondition(List<SpecConditionDto> conditions) {
        if (conditions == null) return false;
        return conditions.stream().anyMatch(c ->
                c != null && c.getTargetType() != null
                        && "privacy".equalsIgnoreCase(c.getTargetType().trim()));
    }
}
