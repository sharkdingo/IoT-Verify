package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.RuleFixer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize.FaultLocalizer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.ConditionAdjustStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyApplier;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.ParameterAdjustStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.RemoveRulesFixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvRuleCommentWriter;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvSpecificationBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneTestImport;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.PreexistingViolationDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcceptanceDemoScenarioNusmvTest {

    private static final long USER_ID = 1L;
    private static final Path SCENE_PATH =
            Path.of("..", "docs", "examples", "acceptance-demo-scene.json");
    private static final Path MULTI_VIOLATION_SCENE_PATH =
            Path.of("..", "docs", "examples", "multi-violation-repair-scene.json");
    private static final Path CLIMATE_SCENE_PATH =
            Path.of("..", "docs", "examples", "default-climate-conflict-scene.json");
    private static final Path FIRE_SCENE_PATH =
            Path.of("..", "docs", "examples", "default-fire-evacuation-scene.json");
    private static final List<AdditionalScenarioExpectation> ADDITIONAL_SCENARIOS = List.of(
            new AdditionalScenarioExpectation(
                    "fire",
                    Path.of("..", "docs", "examples", "default-fire-evacuation-scene.json"),
                    4, 2, 3, 5, 1, 4,
                    "door_1", "unlocked",
                    "When the alarm sounds, unlock the front door for evacuation",
                    2),
            new AdditionalScenarioExpectation(
                    "climate",
                    Path.of("..", "docs", "examples", "default-climate-conflict-scene.json"),
                    2, 2, 2, 4, 2, 3,
                    "ac_1", "heat",
                    "Unsafe conflicting rule: when the room is hot, heat the living room",
                    1),
            new AdditionalScenarioExpectation(
                    "rfid",
                    Path.of("..", "docs", "examples", "default-rfid-access-scene.json"),
                    3, 0, 2, 5, 0, 3,
                    "door_1", "unlocked",
                    null,
                    2));

    @Test
    void documentedSceneTemplateSnapshots_matchBundledTemplates() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceTemplateSchemaValidator schemaValidator = new DeviceTemplateSchemaValidator(objectMapper);
        Path examplesDir = Path.of("..", "docs", "examples");
        Path bundledTemplatesDir = Path.of("src", "main", "resources", "deviceTemplate");

        try (var scenePaths = Files.list(examplesDir)) {
            for (Path scenePath : scenePaths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                JsonNode scene = objectMapper.readTree(Files.readString(scenePath));
                int checked = 0;
                for (JsonNode template : scene.path("templates")) {
                    String templateName = template.path("name").asText();
                    Path bundledTemplate = bundledTemplatesDir.resolve(templateName + ".json");
                    if (Files.exists(bundledTemplate)) {
                        // A copied bundled manifest must stay byte-equal, or an import would silently
                        // create a second device type that differs from the default it shadows.
                        JsonNode bundledManifest = objectMapper.readTree(Files.readString(bundledTemplate));
                        assertEquals(bundledManifest, template.path("manifest"),
                                () -> scenePath.getFileName() + " has a stale template snapshot: " + templateName);
                    } else {
                        // A scene may legitimately define its own device type (the away-mode scene declares
                        // `Occupancy Sensor`). That is not exempt from checking — it must satisfy the same
                        // canonical schema the template endpoint enforces, or the scene would fail to import.
                        schemaValidator.validateRawManifest(templateName, template.path("manifest"));
                    }
                    checked++;
                }
                assertTrue(checked > 0,
                        () -> scenePath.getFileName() + " declares no templates, so this scan checked nothing");
            }
        }
    }

    @Test
    void acceptanceScene_explainsWhyRemovalWouldDisableTheCameraResponse() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for this acceptance smoke test");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(SCENE_PATH));
        assertEquals("iot-verify.board-scene", scene.path("schema").asText());
        assertEquals(6, scene.path("version").asInt());
        assertEquals(4, scene.path("devices").size());
        assertEquals(3, scene.path("rules").size());
        assertEquals(5, scene.path("specs").size());

        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "acceptance");

        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertEquals(0, generated.disabledRuleCount());
        assertEquals(0, generated.skippedSpecCount());

        NusmvExecutor.NusmvResult verification = FormalCheckTestSupport.checkProperties(executor, generated);
        assertTrue(verification.isSuccess(), verification::getErrorMessage);
        assertEquals(specs.size(), verification.getSpecResults().size());
        assertEquals(1, verification.getSpecResults().stream().filter(result -> !result.isPassed()).count());

        NusmvExecutor.SpecCheckResult cameraViolation = verification.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .findFirst()
                .orElseThrow();
        assertTrue(cameraViolation.getSpecExpression().contains("camera_1.MachineState = takingphoto"));
        assertNotNull(cameraViolation.getCounterexample());

        SmvTraceParser traceParser = new SmvTraceParser();
        List<TraceStateDto> counterexampleStates = traceParser.parseCounterexampleStates(
                cameraViolation.getCounterexample(), generated.deviceSmvMap(), rules);
        assertTrue(counterexampleStates.size() >= 2, "The violation must produce an animatable trace");
        assertTrue(counterexampleStates.stream()
                .flatMap(state -> state.getDevices().stream())
                .anyMatch(device -> "camera_1".equals(device.getDeviceId())
                        && "takingphoto".equals(device.getState())));

        SmvGenerator.GenerateResult simulationModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.SIMULATION);
        NusmvExecutor.SimulationOutput simulation =
                executor.executeInteractiveSimulation(simulationModel.smvFile(), 6);
        assertTrue(simulation.isSuccess(), simulation::getErrorMessage);
        List<TraceStateDto> simulationStates = traceParser.parseCounterexampleStates(
                simulation.getTraceText(), simulationModel.deviceSmvMap(), rules);
        assertTrue(simulationStates.size() >= 2, "Simulation must produce an animatable model trajectory");
        assertTrue(hasDeviceState(simulationStates, "camera_1", "takingphoto"),
                "The simulation should show the camera-photo step");
        assertTrue(hasDeviceState(simulationStates, "alarm_1", "siren"),
                "The simulation should show the alarm step");
        assertTrue(hasDeviceState(simulationStates, "light_1", "on"),
                "The simulation should show the light step");

        SmvGenerator.GenerateResult attackedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.anyUpToBudget(1), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        NusmvExecutor.NusmvResult attackedVerification = FormalCheckTestSupport.checkProperties(executor, attackedModel);
        assertTrue(attackedVerification.isSuccess(), attackedVerification::getErrorMessage);
        assertEquals(specs.size(), attackedVerification.getSpecResults().size());
        assertEquals(4, attackedVerification.getSpecResults().stream()
                .filter(result -> !result.isPassed()).count(),
                "Budget-one attack branches should expose source-label and automation-link failures");

        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(120_000);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(new RemoveRulesFixStrategy(generator, executor)),
                fixConfig);
        FixResultDto fixResult = fixer.fix(
                1L,
                specs.get(0).getId(),
                counterexampleStates,
                rules,
                devices,
                environment,
                specs,
                generated.deviceSmvMap(),
                USER_ID,
                AttackScenarioDto.none(), true,
                List.of("remove"),
                10,
                Map.of());
        assertTriggerPreservationRejected(fixResult);
        assertEquals(1, fixResult.getFaultRules().size(),
                "The camera violation should localize only the photo automation");
        assertTrue(fixResult.getUnusedPreferredRangeSelections().isEmpty());
        var repairedModel = generator.generateWithEnvironment(USER_ID, devices, environment,
                rules.subList(1, rules.size()), specs, AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertAllPropertiesPassWithUnreachableResponse(executor, repairedModel, 1);
    }
    @Test
    void multiViolationScene_rejectsRootCauseRemovalThatDisablesTheCameraResponse() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the multi-violation scene regression");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(MULTI_VIOLATION_SCENE_PATH));
        assertEquals("iot-verify.board-scene", scene.path("schema").asText());
        assertEquals(6, scene.path("version").asInt());
        assertEquals(4, scene.path("devices").size());
        assertEquals(2, scene.path("environmentVariables").size());
        assertEquals(3, scene.path("rules").size());
        assertEquals(5, scene.path("specs").size());
        assertEquals("untrusted", scene.path("environmentVariables").get(1).path("trust").asText());

        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "multi-violation");

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertEquals(0, baselineModel.disabledRuleCount());
        assertEquals(0, baselineModel.skippedSpecCount());

        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(5, baseline.getSpecResults().size());
        assertEquals(2, violationCount(baseline));
        NusmvExecutor.SpecCheckResult cameraNever = baseline.getSpecResults().stream()
                .filter(result -> result.getSpecExpression().contains("camera_1.MachineState = takingphoto"))
                .filter(result -> !result.getSpecExpression().contains("trust_MachineState_takingphoto"))
                .findFirst()
                .orElseThrow();
        NusmvExecutor.SpecCheckResult untrustedCamera = baseline.getSpecResults().stream()
                .filter(result -> result.getSpecExpression().contains("trust_MachineState_takingphoto"))
                .findFirst()
                .orElseThrow();
        assertFalse(cameraNever.isPassed(), "The camera never-taking-photo property must be violated");
        assertFalse(untrustedCamera.isPassed(), "The untrusted-source camera property must be violated");
        assertNotNull(cameraNever.getCounterexample());
        assertNotNull(untrustedCamera.getCounterexample());

        SmvTraceParser traceParser = new SmvTraceParser();
        List<TraceStateDto> cameraViolationStates = traceParser.parseCounterexampleStates(
                cameraNever.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(cameraViolationStates.size() >= 2);
        assertTrue(hasDeviceState(cameraViolationStates, "camera_1", "takingphoto"));

        SmvGenerator.GenerateResult simulationModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.SIMULATION);
        NusmvExecutor.SimulationOutput simulation =
                executor.executeInteractiveSimulation(simulationModel.smvFile(), 6);
        assertTrue(simulation.isSuccess(), simulation::getErrorMessage);
        List<TraceStateDto> simulationStates = traceParser.parseCounterexampleStates(
                simulation.getTraceText(), simulationModel.deviceSmvMap(), rules);
        assertTrue(hasDeviceState(simulationStates, "camera_1", "takingphoto"));
        assertTrue(hasDeviceState(simulationStates, "alarm_1", "siren"));
        assertTrue(hasDeviceState(simulationStates, "light_1", "on"));

        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(120_000);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(new RemoveRulesFixStrategy(generator, executor)),
                fixConfig);
        FixResultDto fixResult = fixer.fix(
                200L,
                specs.get(0).getId(),
                cameraViolationStates,
                rules,
                devices,
                environment,
                specs,
                baselineModel.deviceSmvMap(),
                USER_ID,
                AttackScenarioDto.none(), true,
                List.of("remove"),
                10,
                Map.of());
        assertTriggerPreservationRejected(fixResult);
        var repairedModel = generator.generateWithEnvironment(USER_ID, devices, environment,
                rules.subList(1, rules.size()), specs, AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertAllPropertiesPassWithUnreachableResponse(executor, repairedModel, 1);
    }
    @Test
    void coupledClimateThresholds_parameterStrategyFindsClosestSingleChange() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the climate parameter-fix regression");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(CLIMATE_SCENE_PATH));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);

        List<RuleDto> rules = List.of(
                rule(1L, "When temperature is at least 28, heat the room",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "28")),
                rule(2L, "Otherwise, turn the air conditioner off",
                        command("ac_1", "off"),
                        ruleCondition("temperature_1", "temperature", "variable", "<", "35")));
        List<SpecificationDto> specs = List.of(
                immediateSpec("climate-low-off", "Below 35 must turn cooling off",
                        specCondition("temperature_1", "variable", "temperature", "<", "35"),
                        specCondition("ac_1", "mode", "HvacMode", "=", "off")),
                immediateSpec("climate-high-heat", "At least 35 must heat",
                        specCondition("temperature_1", "variable", "temperature", ">=", "35"),
                        specCondition("ac_1", "mode", "HvacMode", "=", "heat")));

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(specs.size(), baseline.getSpecResults().size());
        assertEquals(1, violationCount(baseline));

        NusmvExecutor.SpecCheckResult violation = baseline.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .findFirst()
                .orElseThrow();
        assertNotNull(violation.getCounterexample());
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(120_000);
        fixConfig.setMaxRefineAttempts(20);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(new ParameterAdjustStrategy(generator, executor, fixConfig)),
                fixConfig);
        FixResultDto fixResult = fixer.fix(
                301L, specs.get(0).getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                List.of("parameter"), 20, Map.of());

        assertTrue(fixResult.isFixable(), fixResult::getSummary);
        assertEquals(1, fixResult.getFaultRules().size());
        assertFalse(fixResult.getParameterTargets().isEmpty(),
                "The frontend must receive the numeric target catalog even before applying a suggestion");
        assertVerifiedAttempt(fixResult, "parameter");
        FixSuggestionDto suggestion = fixResult.getSuggestions().stream()
                .filter(candidate -> "parameter".equals(candidate.getStrategy()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fixResult.getStrategyAttempts().toString()));
        assertTrue(suggestion.getParameterAdjustments().stream()
                .anyMatch(adjustment -> adjustment.getRuleIndex() == 0
                        && "35".equals(adjustment.getNewValue())),
                () -> "Expected the unsafe heat threshold to move from 28 to 35: "
                        + suggestion.getParameterAdjustments());

        List<RuleDto> repairedRules = FixStrategyApplier.apply(
                "parameter", suggestion, rules, baselineModel.deviceSmvMap());
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, repairedRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(repairedModel);
        NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
        assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
        assertEquals(specs.size(), repaired.getSpecResults().size());
        assertFalse(repaired.hasAnyViolation(),
                "The parameter suggestion must satisfy both sides of the climate boundary");
    }

    @Test
    void occupancySafety_conditionStrategySolvesCandidateValueInsteadOfCopyingPolicyValue() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the free condition-value regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "17");

        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of(
                rule(21L, "When it is cold, heat the room",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", "<=", "18")));
        SpecificationDto occupancySafety = neverSpec(
                "occupancy-heating-safety",
                "Do not heat an unoccupied room",
                specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        List<SpecificationDto> specs = List.of(occupancySafety);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(specs.size(), baseline.getSpecResults().size());
        assertEquals(1, violationCount(baseline));

        NusmvExecutor.SpecCheckResult violation = baseline.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .findFirst()
                .orElseThrow();
        assertNotNull(violation.getCounterexample());
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(120_000);
        fixConfig.setMaxCandidatesPerRule(5);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(new ConditionAdjustStrategy(generator, executor, fixConfig)),
                fixConfig);
        FixResultDto fixResult = fixer.fix(
                304L, occupancySafety.getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                List.of("condition"), 20, Map.of());

        assertTrue(fixResult.isFixable(), fixResult::getSummary);
        assertEquals(1, fixResult.getFaultRules().size());
        assertVerifiedAttempt(fixResult, "condition");
        FixSuggestionDto suggestion = listedRepair(fixResult, "condition",
                "a solved guard requiring occupancy instead of copying occupied=absent",
                candidate -> addsOccupancyGuard(candidate, 0));

        List<RuleDto> repairedRules = FixStrategyApplier.apply(
                "condition", suggestion, rules, baselineModel.deviceSmvMap(),
                Map.of("occupancy_1", "occupancy_1"));
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, repairedRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(repairedModel);
        NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
        assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
        assertEquals(specs.size(), repaired.getSpecResults().size());
        assertFalse(repaired.hasAnyViolation(),
                "The solved occupied=present guard must repair the complete model");

        // Salus §5.2's guard must keep useful heating available when the room is occupied.
        DeviceVerificationDto occupancy = devices.stream()
                .filter(device -> "occupancy_1".equals(device.getVarName())).findFirst().orElseThrow();
        occupancy.getVariables().get(0).setValue("present");
        SpecificationDto occupiedHeating = immediateSpec("occupied-heating", "Heat an occupied cold room",
                specCondition("temperature_1", "variable", "temperature", "<=", "18"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        SmvGenerator.GenerateResult occupiedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, repairedRules, List.of(occupancySafety, occupiedHeating),
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(occupiedModel);
        NusmvExecutor.NusmvResult occupied = FormalCheckTestSupport.checkProperties(executor, occupiedModel);
        assertTrue(occupied.isSuccess(), occupied::getErrorMessage);
        assertFalse(occupied.hasAnyViolation(), "The repaired rule must still heat the occupied cold room");
    }

    @Test
    void combinedClimateSafety_allThreeStrategiesIndependentlyRepairTheSameCounterexample() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the combined three-strategy regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "30");
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of(
                rule(31L, "Unsafe early heat rule",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "28")),
                rule(32L, "At or below 35, turn the air conditioner off",
                        command("ac_1", "off"),
                        ruleCondition("temperature_1", "temperature", "variable", "<=", "35")),
                rule(33L, "At least 36, retain required heating",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "36")));

        SpecificationDto neverUnsafeLowHeat = neverSpec(
                "combined-never-low-heat",
                "Do not heat an unoccupied room below 35",
                specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                specCondition("temperature_1", "variable", "temperature", "<", "35"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        SpecificationDto lowOff = temporalSpec(
                "combined-low-off",
                "4",
                "An unoccupied room at or below 35 must be off",
                List.of(
                        specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                        specCondition("temperature_1", "variable", "temperature", "<=", "35")),
                List.of(specCondition("ac_1", "mode", "HvacMode", "=", "off")));
        SpecificationDto highHeat = immediateSpec(
                "combined-high-heat",
                "At least 36 must still heat",
                specCondition("temperature_1", "variable", "temperature", ">=", "36"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        List<SpecificationDto> specs = List.of(neverUnsafeLowHeat, lowOff, highHeat);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(specs.size(), baseline.getSpecResults().size());
        assertEquals(2, violationCount(baseline),
                "The early heat rule must violate both low-temperature safety properties");

        NusmvExecutor.SpecCheckResult violation = baseline.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .filter(result -> result.getSpecExpression().contains("occupancy_1.occupied = absent"))
                .findFirst()
                .orElseThrow();
        assertNotNull(violation.getCounterexample());
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        FixSuggestionDto expectedParameterRepair = FixSuggestionDto.builder()
                .strategy("parameter")
                .parameterAdjustments(List.of(ParameterAdjustment.builder()
                        .ruleIndex(0)
                        .conditionIndex(0)
                        .attribute("temperature")
                        .relation(">=")
                        .originalValue("28")
                        .newValue("37")
                        .lowerBound(0)
                        .upperBound(100)
                        .build()))
                .build();
        List<RuleDto> expectedParameterRules = FixStrategyApplier.apply(
                "parameter", expectedParameterRepair, rules, baselineModel.deviceSmvMap());
        SmvGenerator.GenerateResult expectedParameterModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, expectedParameterRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(expectedParameterModel);
        NusmvExecutor.NusmvResult expectedParameterVerification =
                FormalCheckTestSupport.checkProperties(executor, expectedParameterModel);
        assertTrue(expectedParameterVerification.isSuccess(), expectedParameterVerification::getErrorMessage);
        assertFalse(expectedParameterVerification.hasAnyViolation(),
                "The intended 28-to-37 threshold edit must be a valid, non-duplicate repair before search begins");

        FixConfig fixConfig = new FixConfig();
        // This test asserts all strategies, not timeout behavior; allow headroom on shared runners.
        fixConfig.setFixTimeoutMs(30_000);
        fixConfig.setMaxRefineAttempts(20);
        fixConfig.setMaxCandidatesPerRule(5);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(
                        new ParameterAdjustStrategy(generator, executor, fixConfig),
                        new ConditionAdjustStrategy(generator, executor, fixConfig),
                        new RemoveRulesFixStrategy(generator, executor)),
                fixConfig);
        List<String> strategies = List.of("parameter", "condition", "remove");
        FixResultDto fixResult = fixer.fix(
                305L, neverUnsafeLowHeat.getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                strategies, 20, Map.of());

        assertTrue(fixResult.isFixable(), fixResult::getSummary);
        assertEquals(3, fixResult.getStrategyAttempts().size());
        strategies.forEach(strategy -> assertVerifiedAttempt(fixResult, strategy));

        FixSuggestionDto parameter = listedRepair(fixResult, "parameter",
                "rule 0's 28 moved to 37, skipping the duplicate boundary at 36",
                suggestion -> suggestion.getParameterAdjustments().stream()
                        .anyMatch(adjustment -> adjustment.getRuleIndex() == 0
                                && "28".equals(adjustment.getOriginalValue())
                                && "37".equals(adjustment.getNewValue())));
        FixSuggestionDto condition = listedRepair(fixResult, "condition",
                "a positive occupancy guard on rule 0", suggestion -> addsOccupancyGuard(suggestion, 0));
        FixSuggestionDto removal = listedRepair(fixResult, "remove", "removing rule 0",
                suggestion -> List.of(0).equals(suggestion.getRemovedRuleIndices()));
        // lowOff is violated by the original rules too, so an alternative may legitimately leave it
        // failing; the scenario's intended repairs fix every specification.
        for (FixSuggestionDto suggestion : List.of(parameter, condition, removal)) {
            assertTrue(suggestion.getPreexistingViolations().isEmpty(),
                    () -> suggestion.getStrategy() + " must repair every combined-scene specification: "
                            + suggestion.getPreexistingViolations());
        }
        assertEveryListedAlternativeHolds(fixResult.getSuggestions(), neverUnsafeLowHeat.getId(), rules, devices,
                environment, specs, baselineModel.deviceSmvMap(), generator, executor);

        FixResultDto unknownSpecResult = fixer.fix(
                306L, "UNKNOWN_SPEC", states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                strategies, 20, Map.of());
        assertEquals("SKIPPED_NO_SPEC", attemptStatus(unknownSpecResult, "parameter"));
        assertEquals("SKIPPED_NO_SPEC", attemptStatus(unknownSpecResult, "condition"));
        // Response preservation cannot establish a repair when its target is not attributable.
        assertEquals("INCONCLUSIVE", attemptStatus(unknownSpecResult, "remove"));
        assertTrue(listed(unknownSpecResult, "remove").isEmpty());
    }

    @Test
    void inverseClimateBoundary_allThreeStrategiesSkipDuplicateAndRepair() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the inverse-boundary fix regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "20");
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of(
                rule(41L, "Unsafe early cooling at or below 22",
                        command("ac_1", "cool"),
                        ruleCondition("temperature_1", "temperature", "variable", "<=", "22")),
                rule(42L, "Safe frost-protection cooling at or below 14",
                        command("ac_1", "cool"),
                        ruleCondition("temperature_1", "temperature", "variable", "<=", "14")),
                rule(43L, "At or above 15, turn cooling off",
                        command("ac_1", "off"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "15")));
        SpecificationDto safety = neverSpec(
                "inverse-boundary-safety",
                "Do not cool an unoccupied room above 15 degrees",
                specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                specCondition("temperature_1", "variable", "temperature", ">", "15"),
                specCondition("ac_1", "mode", "HvacMode", "=", "cool"));
        List<SpecificationDto> specs = List.of(safety);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        NusmvExecutor.SpecCheckResult violation = baseline.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .findFirst()
                .orElseThrow();
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        // This test asserts all strategies, not timeout behavior; allow headroom on shared runners.
        RuleFixer fixer = allStrategyFixer(generator, executor, 30_000);
        List<String> strategies = List.of("parameter", "condition", "remove");
        FixResultDto fixResult = fixer.fix(
                307L, safety.getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                strategies, 20, Map.of());

        strategies.forEach(strategy -> assertVerifiedAttempt(fixResult, strategy));
        listedRepair(fixResult, "parameter",
                "rule 0's 22 tightened past the duplicate <=14 boundary to <=13",
                suggestion -> suggestion.getParameterAdjustments().stream()
                        .anyMatch(adjustment -> adjustment.getRuleIndex() == 0
                                && "22".equals(adjustment.getOriginalValue())
                                && "13".equals(adjustment.getNewValue())));
        listedRepair(fixResult, "condition", "a positive occupancy guard on the unsafe cooling rule",
                suggestion -> addsOccupancyGuard(suggestion, 0));
        listedRepair(fixResult, "remove", "removing rule 0",
                suggestion -> List.of(0).equals(suggestion.getRemovedRuleIndices()));
        assertEveryListedAlternativeHolds(fixResult.getSuggestions(), safety.getId(), rules, devices, environment,
                specs, baselineModel.deviceSmvMap(), generator, executor);
    }

    @Test
    void redundantUnsafeRules_requireJointEditsOrPairRemoval() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the joint-repair regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "30");
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of(
                rule(51L, "Unsafe primary heating rule",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "28")),
                rule(52L, "Unsafe redundant secondary heating rule",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "29")),
                rule(53L, "At or below 35, turn heating off",
                        command("ac_1", "off"),
                        ruleCondition("temperature_1", "temperature", "variable", "<=", "35")),
                rule(54L, "At 36 or above, heating is required",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "36")));
        SpecificationDto safety = neverSpec(
                "redundant-heating-safety",
                "Do not heat an unoccupied room below 35 degrees",
                specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                specCondition("temperature_1", "variable", "temperature", "<", "35"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        SpecificationDto requiredHeating = temporalSpec(
                "required-heating-response", "4", "Keep heating available at 36 degrees or above",
                List.of(specCondition("temperature_1", "variable", "temperature", ">=", "36")),
                List.of(specCondition("ac_1", "mode", "HvacMode", "=", "heat")));
        List<SpecificationDto> specs = List.of(safety, requiredHeating);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        assertTrue(verdictsBySpecId(baseline, baselineModel).get(requiredHeating.getId()).isPassed(),
                "The required heating response must hold before attempting a repair");
        NusmvExecutor.SpecCheckResult violation = baseline.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .findFirst()
                .orElseThrow();
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        RuleFixer fixer = allStrategyFixer(generator, executor, 30_000);
        List<String> strategies = List.of("parameter", "condition", "remove");
        FixResultDto fixResult = fixer.fix(
                308L, safety.getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                strategies, 20, Map.of());

        assertEquals(Set.of(0), fixResult.getFaultRules().stream()
                .map(FaultRuleDto::getRuleIndex)
                .collect(java.util.stream.Collectors.toSet()),
                "Command priority should localize only the executed rule; the backup rule is dormant in this trace");
        strategies.forEach(strategy -> assertVerifiedAttempt(fixResult, strategy));

        listedRepair(fixResult, "parameter",
                "both redundant temperature triggers moved beyond the unsafe boundary",
                suggestion -> List.of(0, 1).stream().allMatch(ruleIndex ->
                        suggestion.getParameterAdjustments().stream()
                                .anyMatch(adjustment -> adjustment.getRuleIndex() == ruleIndex
                                        && "temperature".equals(adjustment.getAttribute())
                                        && Integer.parseInt(adjustment.getNewValue()) >= 36)));
        listedRepair(fixResult, "condition", "the occupancy guard on both redundant rules",
                suggestion -> addsOccupancyGuard(suggestion, 0) && addsOccupancyGuard(suggestion, 1));
        // Removal checks smaller sets first, so the pair is listed only because neither single removal
        // repaired the trace.
        assertEquals(List.of(List.of(0, 1)), listed(fixResult, "remove").stream()
                        .map(FixSuggestionDto::getRemovedRuleIndices)
                        .toList(),
                "Neither single removal is sufficient; the minimal destructive repair is the pair");
        assertEveryListedAlternativeHolds(fixResult.getSuggestions(), safety.getId(), rules, devices, environment,
                specs, baselineModel.deviceSmvMap(), generator, executor);
    }

    @Test
    void conditionSearch_learnsFromARejectedPathWithoutLosingARepair() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the counterexample-learning regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "30");
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of(
                rule(81L, "In fan-only mode, heat",
                        command("ac_1", "heat"),
                        ruleCondition("ac_1", "HvacMode", "mode", "=", "fanOnly")),
                rule(82L, "At least 28, switch to fan-only",
                        command("ac_1", "fanOnly"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "28")));
        SpecificationDto safety = neverSpec(
                "learning-heating-safety",
                "Do not heat an unoccupied room below 35 degrees",
                specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                specCondition("temperature_1", "variable", "temperature", "<", "35"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        SpecificationDto response = responseSpec(
                "learning-warm-response",
                "At 28 degrees the air conditioner must leave auto",
                specCondition("temperature_1", "variable", "temperature", ">=", "28"),
                specCondition("ac_1", "mode", "HvacMode", "!=", "auto"));
        List<SpecificationDto> specs = List.of(safety, response);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        Map<String, NusmvExecutor.SpecCheckResult> baselineVerdicts = verdictsBySpecId(baseline, baselineModel);
        assertFalse(baselineVerdicts.get(safety.getId()).isPassed());
        assertTrue(baselineVerdicts.get(response.getId()).isPassed(),
                "The response must hold originally, so a guard on the fan-only rule newly breaks it");
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                baselineVerdicts.get(safety.getId()).getCounterexample(), baselineModel.deviceSmvMap(), rules);
        List<FaultRuleDto> faultRules = new FaultLocalizer().localize(states, rules, baselineModel.deviceSmvMap());
        List<Integer> repairScope = faultRules.stream().map(FaultRuleDto::getRuleIndex).toList();
        // RuleFixer searches fault rules first, so the fan-only rule's guards are rejected — and learned
        // from — before the heating rule's repairs are found. A lemma that excluded too much would drop them.
        assertEquals(List.of(1, 0), repairScope, "The fan-only rule fires first on the violating path");

        // Driven without a request deadline: RuleFixer's time share for alternatives would bound the
        // search by the clock, and this checks which candidates the search itself has to examine.
        FixContext ctx = FixContext.builder()
                .traceId(314L)
                .faultRules(faultRules)
                .repairRuleIndices(repairScope)
                .allRules(rules)
                .devices(devices)
                .environmentVariables(environment)
                .specs(specs)
                .deviceSmvMap(baselineModel.deviceSmvMap())
                .violatedSpecIndex(0)
                .userId(USER_ID)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(20)
                .preferredRanges(Map.of())
                .counterexampleInitialState(states.get(0))
                .requireCounterexampleReplay(true)
                .build();
        FixConfig fixConfig = new FixConfig();
        fixConfig.setMaxCandidatesPerRule(5);
        FixStrategy.StrategyOutcome outcome =
                new ConditionAdjustStrategy(generator, executor, fixConfig).tryFix(ctx);

        // Every guard on the fan-only rule that avoids this counterexample stops it from firing somewhere
        // at 28 degrees or above, which breaks the response. Checked one by one, the temperature guard's
        // values alone exceed this budget; learning from a rejected path excludes them together.
        assertTrue(outcome.alternativesComplete(),
                () -> "The search must be exhausted within its attempts, used "
                        + ctx.strategySearchProgress("condition"));
        Set<Set<String>> changeSets = new HashSet<>();
        for (FixSuggestionDto suggestion : outcome.suggestions()) {
            Set<String> changes = new HashSet<>();
            suggestion.getConditionAdjustments().forEach(adjustment -> changes.add(
                    adjustment.getRuleIndex() + " " + adjustment.getAction() + " " + adjustment.getAttribute()));
            changeSets.add(changes);
        }
        assertEquals(Set.of(Set.of("0 add occupied"), Set.of("0 add temperature")), changeSets,
                "Exactly the heating rule's two guards repair the violation without breaking the response");
        assertEveryListedAlternativeHolds(outcome.suggestions(), safety.getId(), rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), generator, executor);
    }

    @Test
    void environmentOnlyViolation_isNotMisreportedAsRuleRepair() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the no-fault-rule regression");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(CLIMATE_SCENE_PATH));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of();
        SpecificationDto safety = neverSpec(
                "environment-only-temperature-limit",
                "Ambient temperature must never reach 31",
                specCondition("temperature_1", "variable", "temperature", ">=", "31"));
        List<SpecificationDto> specs = List.of(safety);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                baseline.getSpecResults().get(0).getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        List<String> strategies = List.of("parameter", "condition", "remove");
        // This test asserts strategy fall-through, not timeout behavior.
        FixResultDto fixResult = allStrategyFixer(generator, executor, 30_000).fix(
                309L, safety.getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                strategies, 20, Map.of());

        assertFalse(fixResult.isFixable());
        assertTrue(fixResult.getFaultRules().isEmpty());
        assertTrue(fixResult.getSuggestions().isEmpty());
        strategies.forEach(strategy ->
                assertEquals("SKIPPED_NO_FAULT_RULES", attemptStatus(fixResult, strategy)));
    }

    @Test
    void cumulativeClimateRepairs_preserveEarlierRepairsAcrossSharedEnvironmentChanges() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the cumulative shared-environment regression");
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "30");
        ObjectNode occupancyManifest = (ObjectNode) scene.withArray("templates").get(2).path("manifest");
        occupancyManifest.putArray("Modes").add("OccupancyState");
        occupancyManifest.put("InitState", "absent");
        occupancyManifest.set("WorkingStates", objectMapper.readTree("""
                [{"Name":"absent","Trust":"trusted","Privacy":"public",
                  "Dynamics":[{"VariableName":"occupied","Value":"absent"}]},
                 {"Name":"present","Trust":"trusted","Privacy":"public",
                  "Dynamics":[{"VariableName":"occupied","Value":"present"}]}]
                """));
        List<DeviceVerificationDto> prototypes = readDevices(scene);
        prototypes.get(2).setState("absent");
        prototypes.get(2).setCurrentStateTrust("trusted");
        prototypes.get(2).setCurrentStatePrivacy("public");
        List<DeviceVerificationDto> devices = new ArrayList<>();
        List<RuleDto> rules = new ArrayList<>();
        List<SpecificationDto> specs = new ArrayList<>();
        Map<String, String> suffixes = Map.of("Temperature Sensor", "temperature",
                "Air Conditioner", "ac", "Occupancy Sensor", "occupancy");
        for (int zoneIndex = 0; zoneIndex < 3; zoneIndex++) {
            String zone = List.of("zone_a", "zone_b", "zone_c").get(zoneIndex);
            for (DeviceVerificationDto prototype : prototypes) {
                DeviceVerificationDto device = objectMapper.convertValue(prototype, DeviceVerificationDto.class);
                device.setVarName(zone + "_" + suffixes.get(device.getTemplateName()));
                device.setDeviceLabel(zone + " " + device.getTemplateName());
                devices.add(device);
            }
            String sensor = zone + "_temperature";
            String actuator = zone + "_ac";
            for (String threshold : List.of("28", "29")) {
                rules.add(rule((long) rules.size() + 1, zone + " early heating " + threshold,
                        command(actuator, "heat"), ruleCondition(sensor, "temperature", "variable", ">=", threshold)));
            }
            rules.add(rule((long) rules.size() + 1, zone + " shutoff at or below 35",
                    command(actuator, "off"), ruleCondition(sensor, "temperature", "variable", "<=", "35")));
            rules.add(rule((long) rules.size() + 1, zone + " required heating at least 36",
                    command(actuator, "heat"), ruleCondition(sensor, "temperature", "variable", ">=", "36")));
            specs.add(neverSpec("scene_spec_" + (zoneIndex * 2 + 1), zone + " no low-temperature heating",
                    specCondition(zone + "_occupancy", "variable", "occupied", "=", "absent"),
                    specCondition(sensor, "variable", "temperature", "<", "35"),
                    specCondition(actuator, "mode", "HvacMode", "=", "heat")));
            specs.add(immediateSpec("scene_spec_" + (zoneIndex * 2 + 2), zone + " required high-temperature heating",
                    specCondition(sensor, "variable", "temperature", ">=", "36"),
                    specCondition(actuator, "mode", "HvacMode", "=", "heat")));
        }
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        RuleFixer fixer = allStrategyFixer(generator, executor, 60_000);
        Set<String> protectedSpecs = new HashSet<>();
        List<String> strategies = List.of("parameter", "condition", "remove");
        for (int round = 0; round < strategies.size(); round++) {
            String targetId = specs.get(round * 2).getId();
            SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                    USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), false,
                    SmvGenerator.GeneratePurpose.VERIFICATION);
            assertCompleteModel(model);
            NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, model);
            assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
            Map<String, NusmvExecutor.SpecCheckResult> before = verdictsBySpecId(baseline, model);
            assertEquals(3 - round, violationCount(baseline));
            before.forEach((id, verdict) -> { if (verdict.isPassed()) protectedSpecs.add(id); });
            List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                    before.get(targetId).getCounterexample(), model.deviceSmvMap(), rules);
            FixResultDto fixes = fixer.fix(320L + round, targetId, states, rules, devices, environment,
                    specs, model.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                    List.of(strategies.get(round)), 20, Map.of());
            assertVerifiedAttempt(fixes, strategies.get(round));
            assertEveryListedAlternativeHolds(fixes.getSuggestions(), targetId, rules, devices,
                    environment, specs, model.deviceSmvMap(), generator, executor);
            FixSuggestionDto chosen = fixes.getSuggestions().get(0);
            if (round == 0) {
                assertEquals(List.of("34", "35"), chosen.getParameterAdjustments().stream()
                        .map(ParameterAdjustment::getNewValue).sorted().toList(),
                        "Keep the original first repair that exposed the later shared-environment failure");
            }
            Map<String, String> refs = model.deviceSmvMap().keySet().stream()
                    .collect(java.util.stream.Collectors.toMap(ref -> ref, ref -> ref));
            rules = FixStrategyApplier.apply(chosen.getStrategy(), chosen, rules, model.deviceSmvMap(), refs);
            SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                    USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), false,
                    SmvGenerator.GeneratePurpose.VERIFICATION);
            assertCompleteModel(repairedModel);
            NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
            assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
            Map<String, NusmvExecutor.SpecCheckResult> after = verdictsBySpecId(repaired, repairedModel);
            assertTrue(after.get(targetId).isPassed());
            for (String id : protectedSpecs) assertTrue(after.get(id).isPassed(), id);
            assertEquals(2 - round, violationCount(repaired));
        }
    }

    @Test
    void relayCycle_conditionRepairKeepsBothRelaysReachable() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for feedback-loop repair");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", "relay-cycle-repair-scene.json")));
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "relay_cycle");
        SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), false,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, model);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        assertEquals(4, baseline.getSpecResults().size());
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                verdictsBySpecId(baseline, model).get(specs.get(0).getId()).getCounterexample(),
                model.deviceSmvMap(), rules);
        assertTrue(hasDeviceState(states, "heating_relay", "on"));
        assertTrue(hasDeviceState(states, "cooling_relay", "on"));
        FixResultDto fixes = allStrategyFixer(generator, executor, 60_000).fix(
                576L, specs.get(0).getId(), states, rules, devices, environment, specs,
                model.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false, List.of("condition"), 20, Map.of());
        assertVerifiedAttempt(fixes, "condition");
        assertTrue(fixes.getSuggestions().stream().allMatch(suggestion ->
                suggestion.getRemovedRuleDescriptions().isEmpty() && suggestion.getPreexistingViolations().isEmpty()));
        assertEveryListedAlternativeHolds(fixes.getSuggestions(), specs.get(0).getId(), rules, devices,
                environment, specs, model.deviceSmvMap(), generator, executor);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void cameraChains_listAffordableRepairsWithoutBreakingExistingResponses(boolean forked) throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for complete repair enumeration");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = (ObjectNode) mapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", forked
                        ? "forked-chain-repair-scene.json" : "entry-camera-chain-scene.json")));
        assertEquals(forked ? 21 : 8, scene.path("devices").size());
        assertEquals(forked ? 19 : 7, scene.path("rules").size());
        if (forked) {
            // Check complete enumeration on the independent two-path scene. The full variant
            // separately verifies repairs with every shipped background device and requirement.
            while (scene.withArray("devices").size() > 7) scene.withArray("devices").remove(7);
            while (scene.withArray("rules").size() > 7) scene.withArray("rules").remove(7);
            while (scene.withArray("specs").size() > 2) scene.withArray("specs").remove(2);
            while (scene.withArray("environmentVariables").size() > 1) scene.withArray("environmentVariables").remove(1);
            while (scene.withArray("templates").size() > 3) scene.withArray("templates").remove(3);
        }
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "entry_camera");
        SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), false,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, model);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        assertTrue(verdictsBySpecId(baseline, model).get(specs.get(1).getId()).isPassed());
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                verdictsBySpecId(baseline, model).get(specs.get(0).getId()).getCounterexample(),
                model.deviceSmvMap(), rules);
        assertTrue(hasDeviceState(states, "camera_power", "on"), "The chain must actually reach the camera");
        FixResultDto fixes = allStrategyFixer(generator, executor, 60_000).fix(
                575L, specs.get(0).getId(), states, rules, devices, environment, specs,
                model.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false, List.of("remove"), 2, Map.of());
        assertVerifiedAttempt(fixes, "remove");
        Set<Set<Integer>> expected = new HashSet<>();
        for (int a = 1; a <= (forked ? 3 : 6); a++) {
            if (forked) {
                for (int b = 4; b <= 6; b++) expected.add(Set.of(a, b));
            } else {
                expected.add(Set.of(a));
            }
        }
        Set<Set<Integer>> actual = fixes.getSuggestions().stream()
                .map(suggestion -> Set.copyOf(suggestion.getRemovedRuleIndices()))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(actual.size(), fixes.getSuggestions().size(), "Every choice must be distinct");
        assertTrue(expected.containsAll(actual), "Every choice cuts both paths and preserves entry lighting");
        assertEquals(Boolean.TRUE, fixes.getStrategyAttempts().get(0).getAlternativesComplete());
        assertEquals(expected, actual, "A complete search must list every minimal repair");
        assertEveryListedAlternativeHolds(fixes.getSuggestions(), specs.get(0).getId(), rules, devices,
                environment, specs, model.deviceSmvMap(), generator, executor);
    }

    @Test
    void conflictingEmergencyRequirements_cannotBeRepairedByBreakingThePassingRequirement() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for contradictory policy checks");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", "emergency-policy-conflict-scene.json")));
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "emergency_conflict");
        SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), false,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, model);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        String target = specs.get(0).getId();
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                verdictsBySpecId(baseline, model).get(target).getCounterexample(), model.deviceSmvMap(), rules);
        assertOpposingRuleSelection(states, rules, "front_door", "locked", "unlocked",
                state -> "absent".equals(traceEnvironmentValue(state, "home"))
                        && hasDeviceState(List.of(state), "kitchen_alarm", "on"));
        FixResultDto fixes = allStrategyFixer(generator, executor, 60_000).fix(
                550L, target, states, rules, devices, environment, specs, model.deviceSmvMap(),
                USER_ID, AttackScenarioDto.none(), false, List.of("parameter", "condition", "remove"), 20, Map.of());
        System.out.println("EMERGENCY_CONFLICT attempts=" + fixes.getStrategyAttempts());
        assertFalse(fixes.isFixable());
        assertTrue(fixes.getSuggestions().isEmpty());
        assertEquals("ALL_CANDIDATES_REJECTED", attemptStatus(fixes, "remove"));
        assertTrue(fixes.getStrategyAttempts().stream().noneMatch(attempt ->
                Set.of("VERIFIED", "FAILED_MODEL_GENERATION", "FAILED_SOLVER_EXECUTION", "TIMED_OUT", "SKIPPED_TIMEOUT")
                        .contains(attempt.getStatus())));

        // A user decision, not an automatic repair: give fire evacuation precedence over away locking.
        SpecConditionDto noAlarm = specCondition("kitchen_alarm", "mode", "SwitchState", "=", "off");
        noAlarm.setSide("if");
        List<SpecConditionDto> securityConditions = new ArrayList<>(specs.get(1).getIfConditions());
        securityConditions.add(noAlarm);
        specs.get(1).setIfConditions(securityConditions);
        RuleDto securityRule = rules.stream().filter(rule -> "Away door lock".equals(rule.getRuleString()))
                .findFirst().orElseThrow();
        List<RuleDto.Condition> sources = new ArrayList<>(securityRule.getConditions());
        sources.add(ruleCondition("kitchen_alarm", "SwitchState", "mode", "=", "off"));
        securityRule.setConditions(sources);
        SmvGenerator.GenerateResult revisedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), false,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(revisedModel);
        NusmvExecutor.NusmvResult revised = FormalCheckTestSupport.checkProperties(executor, revisedModel);
        assertTrue(revised.isSuccess(), revised::getErrorMessage);
        assertEquals(0, violationCount(revised));
    }

    private String traceEnvironmentValue(TraceStateDto state, String name) {
        return state.getEnvVariables().stream().filter(variable -> name.equals(variable.getName()))
                .map(variable -> variable.getValue()).findFirst().orElseThrow();
    }

    /** Matching commands may oppose each other, but only the selected branch is execution evidence. */
    private void assertOpposingRuleSelection(List<TraceStateDto> states, List<RuleDto> rules,
                                            String target, String winner, String blocked,
                                            Predicate<TraceStateDto> overlap) {
        int winnerIndex = -1;
        int blockedIndex = -1;
        for (int i = 0; i < rules.size(); i++) {
            RuleDto.Command command = rules.get(i).getCommand();
            if (!target.equals(command.getDeviceName())) continue;
            if (winner.equals(command.getAction())) winnerIndex = i;
            if (blocked.equals(command.getAction())) blockedIndex = i;
        }
        assertTrue(winnerIndex >= 0 && blockedIndex > winnerIndex);
        for (int i = 0; i < states.size() - 1; i++) {
            if (!overlap.test(states.get(i))) continue;
            Set<Integer> recorded = states.get(i + 1).getTriggeredRules().stream()
                    .map(rule -> rule.getRuleIndex()).collect(java.util.stream.Collectors.toSet());
            if (recorded.contains(winnerIndex) && !recorded.contains(blockedIndex)
                    && hasDeviceState(List.of(states.get(i + 1)), target, winner)) return;
        }
        throw new AssertionError("The counterexample must expose opposing matches and the actual first-match winner");
    }

    @Test
    void elderlyCareScene_rejectsRemovalThatDisablesItsPhotoResponse() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the care-apartment regression");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", "elderly-care-comprehensive-scene.json")));
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "care");
        SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, model);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(12, baseline.getSpecResults().size());
        assertEquals(1, violationCount(baseline));
        Map<String, NusmvExecutor.SpecCheckResult> verdicts = verdictsBySpecId(baseline, model);
        assertTrue(verdicts.get(specs.get(0).getId()).isPassed(), "Night-time bed exit must light the way in the next step");
        assertTrue(verdicts.get(specs.get(6).getId()).isPassed(), "The declared window effect must lower CO2");
        String target = specs.get(11).getId();
        assertFalse(verdicts.get(target).isPassed(), "The lamp-to-camera privacy defect must remain visible");
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                verdicts.get(target).getCounterexample(), model.deviceSmvMap(), rules);
        FixResultDto fixes = allStrategyFixer(generator, executor, 60_000).fix(
                316L, target, states, rules, devices, environment, specs, model.deviceSmvMap(),
                USER_ID, AttackScenarioDto.none(), true, List.of("remove"), 20, Map.of());
        assertTriggerPreservationRejected(fixes);
        var editedRules = new ArrayList<>(rules);
        editedRules.remove(6);
        var repairedModel = generator.generateWithEnvironment(USER_ID, devices, environment,
                editedRules, specs, AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertAllPropertiesPassWithUnreachableResponse(executor, repairedModel, 7);
    }
    @Test
    void salusVentilationThreshold_repairsAnIdleRuleBeforeTheEnvironmentCrossesTheLimit() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the Salus section 5.1 regression");
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", "salus-ventilation-repair-scene.json")));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "salus_ventilation");
        SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs, AttackScenarioDto.none(), false,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, model);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        Map<String, NusmvExecutor.SpecCheckResult> verdicts = verdictsBySpecId(baseline, model);
        assertFalse(verdicts.get(specs.get(0).getId()).isPassed());
        assertFalse(verdicts.get(specs.get(1).getId()).isPassed(),
                "The permitted zero-rise path leaves the original fan off at 999 ppm forever");
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                verdicts.get(specs.get(0).getId()).getCounterexample(), model.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);
        assertTrue(new FaultLocalizer().localize(states, rules, model.deviceSmvMap()).isEmpty(),
                "CO2 crosses the limit before the original ventilation rule ever fires");

        FixResultDto result = allStrategyFixer(generator, executor, 30_000).fix(
                315L, specs.get(0).getId(), states, rules, devices, environment, specs, model.deviceSmvMap(),
                USER_ID, AttackScenarioDto.none(), false, List.of("parameter"), 20, Map.of());

        assertVerifiedAttempt(result, "parameter");
        listedRepair(result, "parameter", "the ventilation threshold moved earlier, without deleting the fan rule",
                suggestion -> suggestion.getParameterAdjustments().stream().anyMatch(adjustment ->
                        adjustment.getRuleIndex() == 0 && "1000".equals(adjustment.getOriginalValue())
                                && Integer.parseInt(adjustment.getNewValue()) < 1000));
        assertTrue(result.getSuggestions().stream().allMatch(suggestion ->
                        suggestion.getPreexistingViolations().isEmpty()),
                "Every listed threshold must also make the fan run, including when CO2 stops rising");
        assertEveryListedAlternativeHolds(result.getSuggestions(), specs.get(0).getId(), rules, devices,
                environment, specs, model.deviceSmvMap(), generator, executor);
    }

    @Test
    void upperDomainBoundary_parameterNoFixDoesNotBlockConditionOrRemoval() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the upper-domain fix regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "100");
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of(
                rule(61L, "Unsafe heating at the maximum declared temperature",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">=", "100")),
                rule(62L, "Below the maximum temperature, turn heating off",
                        command("ac_1", "off"),
                        ruleCondition("temperature_1", "temperature", "variable", "<=", "99")));
        SpecificationDto safety = neverSpec(
                "upper-domain-heating-safety",
                "Do not heat an unoccupied room at the maximum temperature",
                specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                specCondition("temperature_1", "variable", "temperature", ">=", "100"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        List<SpecificationDto> specs = List.of(safety);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                baseline.getSpecResults().get(0).getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        List<String> strategies = List.of("parameter", "condition", "remove");
        FixResultDto fixResult = allStrategyFixer(generator, executor, 10_000).fix(
                310L, safety.getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                strategies, 20, Map.of());

        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", attemptStatus(fixResult, "parameter"));
        assertVerifiedAttempt(fixResult, "condition");
        assertVerifiedAttempt(fixResult, "remove");
        assertTrue(listed(fixResult, "parameter").isEmpty(), fixResult::getSummary);
        listedRepair(fixResult, "condition", "an occupancy guard when the numeric boundary cannot move",
                suggestion -> addsOccupancyGuard(suggestion, 0));
        listedRepair(fixResult, "remove", "removing rule 0",
                suggestion -> List.of(0).equals(suggestion.getRemovedRuleIndices()));
        assertEveryListedAlternativeHolds(fixResult.getSuggestions(), safety.getId(), rules, devices, environment,
                specs, baselineModel.deviceSmvMap(), generator, executor);
    }

    @Test
    void strictUpperBoundary_parameterRepairMakesRuleUnreachableAndStillVerifies() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the strict-boundary fix regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "100");
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = List.of(
                rule(63L, "Heat above 99 degrees",
                        command("ac_1", "heat"),
                        ruleCondition("temperature_1", "temperature", "variable", ">", "99")),
                rule(64L, "At or below 99 degrees, turn heating off",
                        command("ac_1", "off"),
                        ruleCondition("temperature_1", "temperature", "variable", "<=", "99")));
        SpecificationDto safety = neverSpec(
                "strict-upper-boundary-safety",
                "Do not heat an unoccupied room at 100 degrees",
                specCondition("occupancy_1", "variable", "occupied", "=", "absent"),
                specCondition("temperature_1", "variable", "temperature", ">=", "100"),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        List<SpecificationDto> specs = List.of(safety);

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baselineModel);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                baseline.getSpecResults().get(0).getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        FixResultDto fixResult = allStrategyFixer(generator, executor, 20_000).fix(
                311L, safety.getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                List.of("parameter"), 20, Map.of());

        assertVerifiedAttempt(fixResult, "parameter");
        FixSuggestionDto parameter = listedRepair(fixResult, "parameter", "rule 0's > 99 moved to > 100",
                suggestion -> suggestion.getParameterAdjustments().stream()
                        .anyMatch(candidate -> candidate.getRuleIndex() == 0
                                && ">".equals(candidate.getRelation())
                                && "99".equals(candidate.getOriginalValue())
                                && "100".equals(candidate.getNewValue())));
        ParameterAdjustment adjustment = parameter.getParameterAdjustments().stream()
                .filter(candidate -> candidate.getRuleIndex() == 0)
                .findFirst()
                .orElseThrow();
        assertTrue(adjustment.getDescription().contains("rule unreachable"),
                adjustment::getDescription);

        assertEveryListedAlternativeHolds(fixResult.getSuggestions(), safety.getId(), rules, devices, environment,
                specs, baselineModel.deviceSmvMap(), generator, executor);
    }

    @Test
    void fireEvacuationOverconstrainedDoorRule_conditionStrategyRepairsResponseChain() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the fire condition-fix regression");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(FIRE_SCENE_PATH));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);

        List<RuleDto> rules = List.of(
                rule(11L, "When smoke is detected, sound the alarm",
                        command("alarm_1", "siren"),
                        ruleCondition("smoke_1", "smoke", "variable", "=", "detected")),
                rule(12L, "When the alarm sounds but is also off, unlock the door",
                        command("door_1", "unlock"),
                        apiRuleCondition("alarm_1", "siren"),
                        ruleCondition("alarm_1", "AlertState", "mode", "=", "off")),
                rule(13L, "When the alarm sounds, turn on the exit light",
                        command("light_1", "on"),
                        apiRuleCondition("alarm_1", "siren")));
        List<SpecificationDto> specs = List.of(
                responseSpec("fire-door-response", "Smoke must eventually unlock the door",
                        specCondition("smoke_1", "variable", "smoke", "=", "detected"),
                        specCondition("door_1", "state", "state", "=", "unlocked")),
                responseSpec("fire-alarm-response", "Smoke must eventually sound the alarm",
                        specCondition("smoke_1", "variable", "smoke", "=", "detected"),
                        specCondition("alarm_1", "mode", "AlertState", "=", "siren")),
                responseSpec("fire-light-response", "Smoke must eventually turn on the exit light",
                        specCondition("smoke_1", "variable", "smoke", "=", "detected"),
                        specCondition("light_1", "mode", "SwitchState", "=", "on")));

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(1, violationCount(baseline));
        NusmvExecutor.SpecCheckResult violation = baseline.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .findFirst()
                .orElseThrow();

        SmvTraceParser traceParser = new SmvTraceParser();
        List<TraceStateDto> states = traceParser.parseCounterexampleStates(
                violation.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(120_000);
        fixConfig.setMaxCandidatesPerRule(0);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(new ConditionAdjustStrategy(generator, executor, fixConfig)),
                fixConfig);
        FixResultDto fixResult = fixer.fix(
                302L, specs.get(0).getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                List.of("condition"), 20, Map.of());

        FixSuggestionDto suggestion = listedRepair(fixResult, "condition",
                "the over-constrained door rule losing one condition",
                candidate -> candidate.getConditionAdjustments().stream()
                        .anyMatch(adjustment -> adjustment.getRuleIndex() == 1
                                && "remove".equals(adjustment.getAction())));

        List<RuleDto> repairedRules = FixStrategyApplier.apply(
                "condition", suggestion, rules, baselineModel.deviceSmvMap());
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, repairedRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
        assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
        assertFalse(repaired.hasAnyViolation(),
                "The condition suggestion must repair the complete evacuation response chain");
    }

    @Test
    void budgetOneAttack_rejectsRemovalThatDisablesTheCameraResponse() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the attacked repair regression");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(SCENE_PATH));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "attacked-repair");

        SmvGenerator.GenerateResult attackedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.anyUpToBudget(1), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        NusmvExecutor.NusmvResult attacked = FormalCheckTestSupport.checkProperties(executor, attackedModel);
        assertTrue(attacked.isSuccess(), attacked::getErrorMessage);
        assertEquals(4, violationCount(attacked));

        NusmvExecutor.SpecCheckResult cameraViolation = attacked.getSpecResults().stream()
                .filter(result -> !result.isPassed())
                .filter(result -> result.getSpecExpression() != null
                        && result.getSpecExpression().contains("camera_1.MachineState = takingphoto"))
                .filter(result -> !result.getSpecExpression().contains("trust_"))
                .findFirst()
                .orElseThrow();
        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                cameraViolation.getCounterexample(), attackedModel.deviceSmvMap(), rules);
        assertTrue(states.size() >= 2);

        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(120_000);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(new RemoveRulesFixStrategy(generator, executor)),
                fixConfig);
        FixResultDto fixResult = fixer.fix(
                303L, specs.get(0).getId(), states, rules, devices, environment, specs,
                attackedModel.deviceSmvMap(), USER_ID, AttackScenarioDto.anyUpToBudget(1), true,
                List.of("remove"), 10, Map.of());

        assertTriggerPreservationRejected(fixResult);
        // The same-budget candidate passes its formulas by disabling the only photo source.
        // That removes the camera response's trigger, so it must not be offered as a repair.
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules.subList(1, rules.size()), specs,
                AttackScenarioDto.anyUpToBudget(1), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertAllPropertiesPassWithUnreachableResponse(executor, repairedModel, 1);
    }

    @Test
    void additionalDefaultTemplateScenes_areImportableAnimatableAndSemanticallyStable() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for the default-scene regression");

        ObjectMapper objectMapper = new ObjectMapper();
        SmvTraceParser traceParser = new SmvTraceParser();

        for (AdditionalScenarioExpectation expectation : ADDITIONAL_SCENARIOS) {
            JsonNode scene = objectMapper.readTree(Files.readString(expectation.path()));
            assertEquals("iot-verify.board-scene", scene.path("schema").asText(), expectation.name());
            assertEquals(6, scene.path("version").asInt(), expectation.name());
            assertEquals(expectation.deviceCount(), scene.path("devices").size(), expectation.name());
            assertEquals(expectation.environmentCount(), scene.path("environmentVariables").size(),
                    expectation.name());
            assertEquals(expectation.ruleCount(), scene.path("rules").size(), expectation.name());
            assertEquals(expectation.specCount(), scene.path("specs").size(), expectation.name());

            SmvGenerator generator = buildGenerator(objectMapper, scene);
            NusmvExecutor executor = buildExecutor(nusmvPath);
            List<DeviceVerificationDto> devices = readDevices(scene);
            List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
            List<RuleDto> rules = readRules(scene);
            List<SpecificationDto> specs = readSpecs(scene, devices, expectation.name());

            SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                    USER_ID, devices, environment, rules, specs,
                    AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
            assertEquals(0, baselineModel.disabledRuleCount(), expectation.name());
            assertEquals(0, baselineModel.skippedSpecCount(), expectation.name());
            NusmvExecutor.NusmvResult baseline = FormalCheckTestSupport.checkProperties(executor, baselineModel);
            assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
            assertEquals(expectation.specCount(), baseline.getSpecResults().size(), expectation.name());
            assertEquals(expectation.baselineViolationCount(), violationCount(baseline), expectation.name());

            SmvGenerator.GenerateResult simulationModel = generator.generateWithEnvironment(
                    USER_ID, devices, environment, rules, specs,
                    AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.SIMULATION);
            NusmvExecutor.SimulationOutput simulation =
                    executor.executeInteractiveSimulation(simulationModel.smvFile(), 6);
            assertTrue(simulation.isSuccess(), simulation::getErrorMessage);
            List<TraceStateDto> simulationStates = traceParser.parseCounterexampleStates(
                    simulation.getTraceText(), simulationModel.deviceSmvMap(), rules);
            assertTrue(simulationStates.size() >= 2, expectation.name() + " must be animatable");
            assertTrue(hasDeviceState(
                            simulationStates,
                            expectation.simulationDeviceId(),
                            expectation.simulationState()),
                    expectation.name() + " must expose its main automation state");

            SmvGenerator.GenerateResult attackedModel = generator.generateWithEnvironment(
                    USER_ID, devices, environment, rules, specs,
                    AttackScenarioDto.anyUpToBudget(1), true, SmvGenerator.GeneratePurpose.VERIFICATION);
            assertEquals(0, attackedModel.disabledRuleCount(), expectation.name());
            assertEquals(0, attackedModel.skippedSpecCount(), expectation.name());
            NusmvExecutor.NusmvResult attacked = FormalCheckTestSupport.checkProperties(executor, attackedModel);
            assertTrue(attacked.isSuccess(), attacked::getErrorMessage);
            assertEquals(expectation.attackViolationCount(), violationCount(attacked), expectation.name());

            if (expectation.removedRuleDescription() != null) {
                FixSuggestionDto removal = findVerifiedRemoval(
                        expectation,
                        baseline,
                        baselineModel,
                        traceParser,
                        generator,
                        executor,
                        rules,
                        devices,
                        environment,
                        specs);
                assertEquals(List.of(expectation.removedRuleDescription()),
                        removal.getRemovedRuleDescriptions(), expectation.name());

                Set<Integer> removedIndices = new HashSet<>(removal.getRemovedRuleIndices());
                List<RuleDto> repairedRules = new ArrayList<>();
                for (int index = 0; index < rules.size(); index++) {
                    if (!removedIndices.contains(index)) {
                        repairedRules.add(rules.get(index));
                    }
                }
                assertEquals(expectation.repairedRuleCount(), repairedRules.size(), expectation.name());

                SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                        USER_ID, devices, environment, repairedRules, specs,
                        AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
                NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
                assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
                assertFalse(repaired.hasAnyViolation(),
                        expectation.name() + " verified removal must satisfy every emitted specification");
            }
        }
    }

    private FixSuggestionDto findVerifiedRemoval(
            AdditionalScenarioExpectation expectation,
            NusmvExecutor.NusmvResult baseline,
            SmvGenerator.GenerateResult baselineModel,
            SmvTraceParser traceParser,
            SmvGenerator generator,
            NusmvExecutor executor,
            List<RuleDto> rules,
            List<DeviceVerificationDto> devices,
            List<BoardEnvironmentVariableDto> environment,
            List<SpecificationDto> specs) {
        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(120_000);
        RuleFixer fixer = new RuleFixer(
                new FaultLocalizer(),
                List.of(new RemoveRulesFixStrategy(generator, executor)),
                fixConfig);

        for (int index = 0; index < baseline.getSpecResults().size(); index++) {
            NusmvExecutor.SpecCheckResult result = baseline.getSpecResults().get(index);
            if (result.isPassed() || result.getCounterexample() == null) {
                continue;
            }
            List<TraceStateDto> states = traceParser.parseCounterexampleStates(
                    result.getCounterexample(), baselineModel.deviceSmvMap(), rules);
            FixResultDto fixResult = fixer.fix(
                    100L + index,
                    specs.get(index).getId(),
                    states,
                    rules,
                    devices,
                    environment,
                    specs,
                    baselineModel.deviceSmvMap(),
                    USER_ID,
                    AttackScenarioDto.none(), true,
                    List.of("remove"),
                    10,
                    Map.of());
            Optional<FixSuggestionDto> removal = fixResult.getSuggestions().stream()
                    .filter(suggestion -> "remove".equals(suggestion.getStrategy()))
                    .filter(suggestion -> suggestion.getRemovedRuleDescriptions()
                            .contains(expectation.removedRuleDescription()))
                    .findFirst();
            if (removal.isPresent()) {
                return removal.get();
            }
        }
        throw new AssertionError(expectation.name() + " did not produce the expected verified removal");
    }

    private void assertTriggerPreservationRejected(FixResultDto result) {
        assertFalse(result.isFixable());
        assertTrue(result.getSuggestions().isEmpty());
        assertEquals("ALL_CANDIDATES_REJECTED", attemptStatus(result, "remove"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("response triggers unreachable")),
                result.getWarnings().toString());
    }

    private void assertAllPropertiesPassWithUnreachableResponse(NusmvExecutor executor,
            SmvGenerator.GenerateResult model, int responseIndex) throws Exception {
        var raw = executor.execute(model.smvFile());
        assertTrue(raw.isSuccess(), raw::getErrorMessage);
        var aligned = cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment.align(raw.getSpecResults(), model.emittedSpecs());
        assertEquals(aligned.expectedResultCount(), raw.getSpecResults().size());
        assertEquals(0, aligned.backFilled());
        assertTrue(aligned.aligned().stream().allMatch(NusmvExecutor.SpecCheckResult::isPassed));
        assertEquals(cn.edu.nju.Iot_Verify.dto.verification.AntecedentReachability.UNREACHABLE,
                aligned.antecedentReachability(responseIndex, model.emittedSpecs().get(responseIndex)));
    }
    private long violationCount(NusmvExecutor.NusmvResult result) {
        return result.getSpecResults().stream().filter(spec -> !spec.isPassed()).count();
    }

    /** NuSMV prints verdicts in its own order; the product attributes them to specifications this way. */
    private Map<String, NusmvExecutor.SpecCheckResult> verdictsBySpecId(
            NusmvExecutor.NusmvResult result, SmvGenerator.GenerateResult model) {
        SpecResultAlignment.Alignment alignment =
                SpecResultAlignment.align(result.getSpecResults(), model.emittedSpecs());
        assertEquals(0, alignment.backFilled(), "Every verdict must be attributable to its specification");
        Map<String, NusmvExecutor.SpecCheckResult> verdicts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < alignment.aligned().size(); i++) {
            verdicts.put(model.emittedSpecs().get(i).specId(), alignment.aligned().get(i));
        }
        return verdicts;
    }

    private void assertCompleteModel(SmvGenerator.GenerateResult result) {
        assertEquals(0, result.disabledRuleCount(), "A repair scenario must not hide an invalid rule");
        assertEquals(0, result.skippedSpecCount(), "A repair scenario must emit every submitted specification");
    }

    private void assertVerifiedAttempt(FixResultDto result, String strategy) {
        assertTrue(result.getStrategyAttempts().stream()
                        .anyMatch(attempt -> strategy.equals(attempt.getStrategy())
                                && "VERIFIED".equals(attempt.getStatus())),
                () -> "Expected a VERIFIED " + strategy + " attempt, got " + result.getStrategyAttempts());
    }

    private String attemptStatus(FixResultDto result, String strategy) {
        return result.getStrategyAttempts().stream()
                .filter(attempt -> strategy.equals(attempt.getStrategy()))
                .map(attempt -> attempt.getStatus())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing strategy attempt for " + strategy));
    }

    private List<FixSuggestionDto> listed(FixResultDto result, String strategy) {
        return result.getSuggestions().stream()
                .filter(suggestion -> strategy.equals(suggestion.getStrategy()))
                .toList();
    }

    /**
     * The repair a scenario is built around, among the alternatives its strategy listed. Which other
     * minimal repairs a strategy also lists, and so which one comes first, depends on how far its search got
     * within its time share, so a scenario names the repair it expects instead of taking the first.
     */
    private FixSuggestionDto listedRepair(
            FixResultDto result, String strategy, String expected, Predicate<FixSuggestionDto> matches) {
        List<FixSuggestionDto> alternatives = listed(result, strategy);
        return alternatives.stream()
                .filter(matches)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected " + strategy + " to list " + expected
                        + "; listed " + alternatives + ", attempts " + result.getStrategyAttempts()));
    }

    private boolean addsOccupancyGuard(FixSuggestionDto suggestion, int ruleIndex) {
        return suggestion.getConditionAdjustments().stream()
                .anyMatch(adjustment -> adjustment.getRuleIndex() == ruleIndex
                        && "add".equals(adjustment.getAction())
                        && "occupied".equals(adjustment.getAttribute())
                        && "present".equalsIgnoreCase(adjustment.getValue()));
    }

    private RuleFixer allStrategyFixer(
            SmvGenerator generator, NusmvExecutor executor, int timeoutMs) {
        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(timeoutMs);
        fixConfig.setMaxRefineAttempts(20);
        fixConfig.setMaxCandidatesPerRule(5);
        return new RuleFixer(
                new FaultLocalizer(),
                List.of(
                        new ParameterAdjustStrategy(generator, executor, fixConfig),
                        new ConditionAdjustStrategy(generator, executor, fixConfig),
                        new RemoveRulesFixStrategy(generator, executor)),
                fixConfig);
    }

    /**
     * Every listed alternative, applied on its own, must satisfy the target and leave violated exactly the
     * specifications it reports as pre-existing — the user may choose any of them, not only the one a
     * scenario is built around.
     */
    private void assertEveryListedAlternativeHolds(
            List<FixSuggestionDto> suggestions,
            String targetSpecId,
            List<RuleDto> originalRules,
            List<DeviceVerificationDto> devices,
            List<BoardEnvironmentVariableDto> environment,
            List<SpecificationDto> specs,
            Map<String, cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData> deviceSmvMap,
            SmvGenerator generator,
            NusmvExecutor executor) throws Exception {
        boolean enablePrivacy = specs.stream()
                .flatMap(spec -> java.util.stream.Stream.of(spec.getAConditions(), spec.getIfConditions(), spec.getThenConditions()))
                .flatMap(List::stream).anyMatch(condition -> "privacy".equals(condition.getTargetType()));
        SmvGenerator.GenerateResult originalModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, originalRules, specs,
                AttackScenarioDto.none(), enablePrivacy, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(originalModel);
        NusmvExecutor.NusmvResult original = FormalCheckTestSupport.checkProperties(executor, originalModel);
        assertTrue(original.isSuccess(), original::getErrorMessage);
        assertEquals(specs.size(), original.getSpecResults().size());
        Set<String> protectedSpecs = verdictsBySpecId(original, originalModel).entrySet().stream()
                .filter(entry -> entry.getValue().isPassed()).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
        // The test board's device ids are the SMV names the fixer reports, so an added condition maps to itself.
        Map<String, String> persistenceDeviceRefs = deviceSmvMap.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(ref -> ref, ref -> ref));
        for (FixSuggestionDto suggestion : suggestions) {
            List<RuleDto> repairedRules = FixStrategyApplier.apply(
                    suggestion.getStrategy(), suggestion, originalRules, deviceSmvMap, persistenceDeviceRefs);
            SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                    USER_ID, devices, environment, repairedRules, specs,
                    AttackScenarioDto.none(), enablePrivacy, SmvGenerator.GeneratePurpose.VERIFICATION);
            assertCompleteModel(repairedModel);
            NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
            assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
            assertEquals(specs.size(), repaired.getSpecResults().size());
            Set<String> violated = new HashSet<>();
            verdictsBySpecId(repaired, repairedModel).forEach((specId, verdict) -> {
                if (!verdict.isPassed()) violated.add(specId);
            });
            Set<String> reported = new HashSet<>();
            for (PreexistingViolationDto violation : suggestion.getPreexistingViolations()) {
                reported.add(violation.getSpecId());
            }
            assertFalse(violated.contains(targetSpecId),
                    () -> suggestion.getStrategy() + " alternative must satisfy the target: " + suggestion);
            for (String protectedSpec : protectedSpecs) {
                assertFalse(violated.contains(protectedSpec),
                        () -> suggestion.getStrategy() + " alternative must preserve " + protectedSpec);
            }
            assertEquals(reported, violated,
                    () -> suggestion.getStrategy() + " alternative must report exactly what it leaves violated: "
                            + suggestion);
        }
    }

    private ObjectNode withOccupancySensor(ObjectMapper objectMapper, String temperatureValue) throws Exception {
        ObjectNode scene = (ObjectNode) objectMapper.readTree(Files.readString(CLIMATE_SCENE_PATH));
        ObjectNode occupancyManifest = (ObjectNode) objectMapper.readTree("""
                {
                  "Name": "Occupancy Sensor",
                  "Description": "Stable occupancy input for condition-repair regression",
                  "InternalVariables": [{
                    "Name": "occupied",
                    "IsInside": true,
                    "FalsifiableWhenCompromised": false,
                    "Values": ["present", "absent"],
                    "Trust": "trusted",
                    "Privacy": "public"
                  }],
                  "Modes": [],
                  "InitState": "",
                  "WorkingStates": [],
                  "Transitions": [],
                  "APIs": []
                }
                """);
        ObjectNode occupancyTemplate = objectMapper.createObjectNode();
        occupancyTemplate.put("name", "Occupancy Sensor");
        occupancyTemplate.set("manifest", occupancyManifest);
        scene.withArray("templates").add(occupancyTemplate);

        ObjectNode occupancyDevice = objectMapper.createObjectNode();
        occupancyDevice.put("id", "occupancy_1");
        occupancyDevice.put("templateName", "Occupancy Sensor");
        occupancyDevice.put("label", "Living-room Occupancy Sensor");
        ObjectNode occupiedValue = objectMapper.createObjectNode();
        occupiedValue.put("name", "occupied");
        occupiedValue.put("value", "absent");
        occupiedValue.put("trust", "trusted");
        occupancyDevice.putArray("variables").add(occupiedValue);
        scene.withArray("devices").add(occupancyDevice);
        scene.withArray("environmentVariables").forEach(row -> {
            if ("temperature".equals(row.path("name").asText())) {
                ((ObjectNode) row).put("value", temperatureValue);
            }
        });
        return scene;
    }

    private RuleDto rule(Long id, String description, RuleDto.Command command,
                         RuleDto.Condition... conditions) {
        return RuleDto.builder()
                .id(id)
                .ruleString(description)
                .conditions(new ArrayList<>(List.of(conditions)))
                .command(command)
                .build();
    }

    private RuleDto.Command command(String deviceId, String action) {
        return RuleDto.Command.builder().deviceName(deviceId).action(action).build();
    }

    private RuleDto.Condition ruleCondition(String deviceId, String attribute, String targetType,
                                            String relation, String value) {
        return RuleDto.Condition.builder()
                .deviceName(deviceId)
                .attribute(attribute)
                .targetType(targetType)
                .relation(relation)
                .value(value)
                .build();
    }

    private RuleDto.Condition apiRuleCondition(String deviceId, String api) {
        return RuleDto.Condition.builder()
                .deviceName(deviceId)
                .attribute(api)
                .targetType("api")
                .build();
    }

    private SpecificationDto immediateSpec(String id, String label,
                                           SpecConditionDto ifCondition,
                                           SpecConditionDto thenCondition) {
        return temporalSpec(id, "4", label, ifCondition, thenCondition);
    }

    private SpecificationDto responseSpec(String id, String label,
                                          SpecConditionDto ifCondition,
                                          SpecConditionDto thenCondition) {
        return temporalSpec(id, "5", label, ifCondition, thenCondition);
    }

    private SpecificationDto neverSpec(
            String id, String label, SpecConditionDto... conditions) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId(id);
        spec.setName("Safety requirement");
        spec.setTemplateId("3");
        spec.setTemplateLabel(label);
        for (SpecConditionDto condition : conditions) {
            condition.setSide("a");
        }
        spec.setAConditions(List.of(conditions));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        spec.setDevices(List.of());
        return spec;
    }

    private SpecificationDto temporalSpec(String id, String templateId, String label,
                                          SpecConditionDto ifCondition,
                                          SpecConditionDto thenCondition) {
        return temporalSpec(id, templateId, label, List.of(ifCondition), List.of(thenCondition));
    }

    private SpecificationDto temporalSpec(String id, String templateId, String label,
                                           List<SpecConditionDto> ifConditions,
                                           List<SpecConditionDto> thenConditions) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId(id);
        spec.setName("Safety requirement");
        spec.setTemplateId(templateId);
        spec.setTemplateLabel(label);
        spec.setAConditions(List.of());
        ifConditions.forEach(condition -> condition.setSide("if"));
        thenConditions.forEach(condition -> condition.setSide("then"));
        spec.setIfConditions(ifConditions);
        spec.setThenConditions(thenConditions);
        spec.setDevices(List.of());
        return spec;
    }

    private SpecConditionDto specCondition(String deviceId, String targetType, String key,
                                           String relation, String value) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId(deviceId + "-" + key + "-" + relation + "-" + value);
        condition.setSide("a");
        condition.setDeviceId(deviceId);
        condition.setTargetType(targetType);
        condition.setKey(key);
        // Which question each variable condition asks, filled here rather than at 20 call sites.
        // `temperature`/`smoke` are shared declarations (IsInside=false) and these scenarios assert what
        // the home actually reached, so they ask `environment`. `occupied` is device-local
        // (IsInside=true, declared at line 1400) — it has no pool value at all, so `reported` is the
        // only meaningful answer and asking `environment` of it would name an identifier the model
        // never declares.
        if ("variable".equals(targetType)) {
            condition.setVariableSource("occupied".equals(key) ? "reported" : "environment");
        }
        condition.setRelation(relation);
        condition.setValue(value);
        return condition;
    }

    private SmvGenerator buildGenerator(ObjectMapper objectMapper, JsonNode scene) throws Exception {
        Map<String, String> manifests = new java.util.LinkedHashMap<>();
        for (JsonNode template : scene.path("templates")) {
            manifests.put(template.path("name").asText(),
                    objectMapper.writeValueAsString(template.path("manifest")));
        }

        SmvModelValidator modelValidator = new SmvModelValidator();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);
        when(templateService.findTemplateByName(anyLong(), anyString())).thenAnswer(invocation -> {
            String templateName = invocation.getArgument(1, String.class);
            String manifest = manifests.get(templateName);
            if (manifest == null) {
                return Optional.empty();
            }
            return Optional.of(DeviceTemplatePo.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .name(templateName)
                    .manifestJson(manifest)
                    .defaultTemplate(true)
                    .build());
        });

        DeviceSmvDataFactory factory = new DeviceSmvDataFactory(
                objectMapper,
                templateService,
                modelValidator,
                new DeviceTemplateSchemaValidator(objectMapper));
        return new SmvGenerator(
                factory,
                new SmvDeviceModuleBuilder(),
                new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(),
                new SmvSpecificationBuilder(),
                modelValidator);
    }

    private NusmvExecutor buildExecutor(String nusmvPath) {
        NusmvConfig config = new NusmvConfig();
        config.setPath(nusmvPath);
        config.setCommandPrefix("");
        config.setTimeoutMs(120_000);
        config.setMaxConcurrent(2);
        config.setAcquirePermitTimeoutMs(10_000);
        return new NusmvExecutor(config);
    }

    private List<DeviceVerificationDto> readDevices(JsonNode scene) {
        List<DeviceVerificationDto> devices = new ArrayList<>();
        for (JsonNode row : scene.path("devices")) {
            DeviceVerificationDto device = new DeviceVerificationDto();
            device.setVarName(row.path("id").asText());
            device.setDeviceLabel(row.path("label").asText());
            device.setTemplateName(row.path("templateName").asText());
            device.setState(textOrNull(row, "state"));
            device.setCurrentStateTrust(textOrNull(row, "currentStateTrust"));
            device.setCurrentStatePrivacy(textOrNull(row, "currentStatePrivacy"));
            device.setVariables(readVariables(row.path("variables")));
            device.setPrivacies(readPrivacies(row.path("privacies")));
            devices.add(device);
        }
        return devices;
    }

    private List<BoardEnvironmentVariableDto> readEnvironment(JsonNode scene) {
        List<BoardEnvironmentVariableDto> environment = new ArrayList<>();
        for (JsonNode row : scene.path("environmentVariables")) {
            environment.add(new BoardEnvironmentVariableDto(
                    row.path("name").asText(),
                    row.path("value").asText(),
                    row.path("trust").asText(),
                    row.path("privacy").asText()));
        }
        return environment;
    }

    private List<RuleDto> readRules(JsonNode scene) {
        return PortableSceneTestImport.importRules(scene);
    }

    private List<SpecificationDto> readSpecs(
            JsonNode scene,
            List<DeviceVerificationDto> devices,
            String idPrefix) {
        return PortableSceneTestImport.importSpecs(scene, idPrefix, labelsByDeviceId(devices));
    }

    private Map<String, String> labelsByDeviceId(List<DeviceVerificationDto> devices) {
        return devices.stream().collect(java.util.stream.Collectors.toMap(
                DeviceVerificationDto::getVarName,
                DeviceVerificationDto::getDeviceLabel));
    }

    private List<VariableStateDto> readVariables(JsonNode rows) {
        List<VariableStateDto> variables = new ArrayList<>();
        if (!rows.isArray()) {
            return variables;
        }
        for (JsonNode row : rows) {
            variables.add(new VariableStateDto(
                    row.path("name").asText(),
                    row.path("value").asText(),
                    textOrNull(row, "trust")));
        }
        return variables;
    }

    private List<PrivacyStateDto> readPrivacies(JsonNode rows) {
        List<PrivacyStateDto> privacies = new ArrayList<>();
        if (!rows.isArray()) {
            return privacies;
        }
        for (JsonNode row : rows) {
            privacies.add(new PrivacyStateDto(
                    row.path("name").asText(),
                    row.path("privacy").asText()));
        }
        return privacies;
    }

    private String textOrNull(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private boolean hasDeviceState(List<TraceStateDto> states, String deviceId, String expectedState) {
        return states.stream()
                .flatMap(state -> state.getDevices().stream())
                .anyMatch(device -> deviceId.equals(device.getDeviceId())
                        && expectedState.equals(device.getState()));
    }

    private static String resolveNusmvPath() {
        String env = System.getenv("NUSMV_PATH");
        if (env != null && !env.isBlank()) {
            return env;
        }
        Path bundled = Path.of("D:/NuSMV/NuSMV-2.7.1-win64/NuSMV-2.7.1-win64/bin/NuSMV.exe");
        return Files.exists(bundled) ? bundled.toString() : "NuSMV";
    }

    private record AdditionalScenarioExpectation(
            String name,
            Path path,
            int deviceCount,
            int environmentCount,
            int ruleCount,
            int specCount,
            int baselineViolationCount,
            int attackViolationCount,
            String simulationDeviceId,
            String simulationState,
            String removedRuleDescription,
            int repairedRuleCount) {
    }
}
