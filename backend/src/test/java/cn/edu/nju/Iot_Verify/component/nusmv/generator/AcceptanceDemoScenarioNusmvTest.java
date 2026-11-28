package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngine;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineConfig;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzEngineInput;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzFinding;
import cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.BoardSemanticFingerprint;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.RuleFixer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize.FaultLocalizer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.CounterexampleInitialStateConstraints;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.ConditionAdjustStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyApplier;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.ParameterAdjustStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.RemoveRulesFixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.configure.JwtConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
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
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.service.FixSuggestionTokenService;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static cn.edu.nju.Iot_Verify.component.board.PortableSceneTestImport.importRules;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.USER_ID;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.allStrategyFixer;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.assumeAvailableNusmvPath;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.buildExecutor;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.buildGenerator;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.readDevices;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.readEnvironment;
import static cn.edu.nju.Iot_Verify.component.nusmv.generator.FormalCheckTestSupport.readSpecs;
import static cn.edu.nju.Iot_Verify.dto.RequestLimits.MAX_RULE_CONDITIONS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcceptanceDemoScenarioNusmvTest {

    private static final Path SCENE_PATH =
            Path.of("..", "docs", "examples", "acceptance-demo-scene.json");
    private static final Path CLIMATE_SCENE_PATH =
            Path.of("..", "docs", "examples", "default-climate-conflict-scene.json");
    private static final Path FIRE_SCENE_PATH =
            Path.of("..", "docs", "examples", "default-fire-evacuation-scene.json");

    @Test
    void standaloneAutonomousWriterSetsThePoolValueInNuSmv() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to verify a standalone autonomous write");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {
                  "devices":[{"id":"writer_1","templateName":"Light Control","state":"off"}],
                  "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control","Description":"","Modes":["PowerMode"],"InitState":"off",
                    "WorkingStates":[{"Name":"off","Trust":"trusted","Privacy":"public"},
                                     {"Name":"on","Trust":"trusted","Privacy":"public"}],
                    "InternalVariables":[{"Name":"roomLight","IsInside":false,"Reads":false,
                      "FalsifiableWhenCompromised":false,"Trust":"trusted","Privacy":"public",
                      "Values":["dark","bright"]}],
                    "APIs":[],"Transitions":[{"Name":"publish brightness",
                      "Trigger":{"Attribute":"PowerMode","Relation":"=","Value":"off"},
                      "Assignments":[{"Attribute":"roomLight","Value":"bright"}]}]
                  }}]
                }
                """);
        var pool = List.of(new BoardEnvironmentVariableDto("roomLight", "dark", "trusted", "public"));
        var generated = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, readDevices(scene),
                pool, List.of(), List.of(), AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.SIMULATION);
        assertCompleteModel(generated);
        // Query the pool directly in the solver. No device in this scene has permission to read it.
        Files.writeString(generated.smvFile().toPath(), "\nCTLSPEC (a_roomLight = dark) & AX (a_roomLight = bright)\n"
                + "CTLSPEC AG (a_roomLight = dark)\n", StandardOpenOption.APPEND);
        var result = buildExecutor(nusmvPath).execute(generated.smvFile());
        assertTrue(result.isSuccess(), result::getErrorMessage);
        assertEquals(2, result.getSpecResults().size());
        assertEquals(1, violationCount(result));

        var violation = result.getSpecResults().stream().filter(spec -> !spec.isPassed()).findFirst().orElseThrow();
        var states = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), generated.deviceSmvMap(), List.of());
        assertEquals(List.of("dark", "bright"), states.stream().map(state -> state.getEnvVariables().stream()
                .filter(variable -> "roomLight".equals(variable.getName())).findFirst().orElseThrow().getValue()).toList());
        var declaration = states.get(0).getDevices().get(0).getVariables().stream()
                .filter(variable -> "roomLight".equals(variable.getName())).findFirst().orElseThrow();
        assertFalse(declaration.isObserved());
        assertEquals("", declaration.getValue());
        assertTrue(CounterexampleInitialStateConstraints.build(states.get(0), List.of(), generated.deviceSmvMap(),
                AttackScenarioDto.none(), false).contains("a_roomLight = dark"));
    }

    @Test
    void competingSharedAssignmentsUseCanonicalDeviceIdAndTemplateTransitionOrder() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check shared write precedence");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {
                  "devices":[
                    {"id":"room_2","templateName":"Light Control","state":"off"},
                    {"id":"room_10","templateName":"Light Control","state":"on"}
                  ],
                  "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control","Modes":["PowerMode"],"InitState":"off",
                    "WorkingStates":[{"Name":"off","Trust":"trusted","Privacy":"public"},
                                     {"Name":"on","Trust":"trusted","Privacy":"public"}],
                    "InternalVariables":[{"Name":"roomLight","IsInside":false,"Reads":false,
                      "FalsifiableWhenCompromised":false,"Trust":"trusted","Privacy":"public",
                      "Values":["dark","bright","dim"]}],
                    "APIs":[],"Transitions":[
                      {"Name":"switch off brightness",
                       "Trigger":{"Attribute":"PowerMode","Relation":"=","Value":"off"},
                       "Assignments":[{"Attribute":"roomLight","Value":"dark"}]},
                      {"Name":"switch on brightness",
                       "Trigger":{"Attribute":"PowerMode","Relation":"=","Value":"on"},
                       "Assignments":[{"Attribute":"roomLight","Value":"bright"}]},
                      {"Name":"switch on dimmer setting",
                       "Trigger":{"Attribute":"PowerMode","Relation":"=","Value":"on"},
                       "Assignments":[{"Attribute":"roomLight","Value":"dim"}]}
                    ]
                  }}]
                }
                """);
        var pool = List.of(new BoardEnvironmentVariableDto("roomLight", "dim", "trusted", "public"));
        var devices = readDevices(scene);
        var generator = buildGenerator(mapper, scene);
        var executor = buildExecutor(nusmvPath);
        // Portable export can place room_2 first. Model IDs use lexical order for precedence.
        for (var ordering : List.of(devices, List.of(devices.get(1), devices.get(0)))) {
            var generated = generator.generateWithEnvironment(USER_ID, ordering,
                    pool, List.of(), List.of(), AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.SIMULATION);
            assertCompleteModel(generated);
            Files.writeString(generated.smvFile().toPath(),
                    "\nCTLSPEC a_roomLight = dim & AX (a_roomLight = bright)\n",
                    StandardOpenOption.APPEND);
            var result = executor.execute(generated.smvFile());
            assertTrue(result.isSuccess(), result::getErrorMessage);
            assertEquals(List.of(true), result.getSpecResults().stream()
                    .map(NusmvExecutor.SpecCheckResult::isPassed).toList(), result::getOutput);
        }
    }

    @Test
    void localAssignmentDoesNotOverrideTheSharedClockRollover() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for this environment transition regression");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode clock = (ObjectNode) mapper.readTree(Files.readString(
                Path.of("src", "main", "resources", "deviceTemplate", "Clock.json")));
        ObjectNode local = clock.deepCopy().put("Name", "Local Timer");
        ObjectNode variable = (ObjectNode) local.path("InternalVariables").get(0);
        variable.put("IsInside", true).put("UpperBound", 1);
        variable.remove(List.of("Reads", "NaturalChangeRate"));
        ObjectNode transition = (ObjectNode) local.path("Transitions").get(0);
        ((ObjectNode) transition.path("Trigger")).put("Value", "0");
        ((ObjectNode) transition.path("Assignments").get(0)).put("Value", "1");

        ObjectNode scene = mapper.createObjectNode();
        var templates = scene.putArray("templates");
        templates.addObject().put("name", "Local Timer").set("manifest", local);
        templates.addObject().put("name", "Clock").set("manifest", clock);
        var nodes = scene.putArray("devices");
        nodes.addObject().put("id", "timer_1").put("label", "Local Timer").put("templateName", "Local Timer")
                .putArray("variables").addObject().put("name", "time").put("value", "0");
        nodes.addObject().put("id", "clock_1").put("label", "Clock").put("templateName", "Clock");
        var localZero = specCondition("timer_1", "variable", "time", "=", "0");
        var localOne = specCondition("timer_1", "variable", "time", "=", "1");
        localZero.setVariableSource("reported");
        localOne.setVariableSource("reported");
        var specs = List.of(immediateSpec("local-tick", "Local timer advances", localZero, localOne),
                immediateSpec("clock-rollover", "Shared clock rolls over",
                        specCondition("clock_1", "variable", "time", "=", "23"),
                        specCondition("clock_1", "variable", "time", "=", "0")));
        SmvGenerator.GenerateResult generated = buildGenerator(mapper, scene).generateWithEnvironment(
                USER_ID, readDevices(scene), List.of(new BoardEnvironmentVariableDto("time", "23", null, null)),
                List.of(), specs, AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(generated);
        var result = FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), generated);
        assertEquals(List.of(true, true), result.getSpecResults().stream()
                .map(NusmvExecutor.SpecCheckResult::isPassed).toList(), result::getOutput);
    }

    @ParameterizedTest
    @CsvSource({
            "2147483646, 2147483647, 2147483647, '[1,1]', none, 2147483647",
            "-2147483647, -2147483645, -2147483647, '[-2,-2]', none, -2147483647",
            "0, 20, 3, '[2147483647,2147483647]', -2147483648, 2"
    })
    void localNumericRatesSaturateAfterExactComposition(int lower, int upper, int initial,
                                                        String drift, String dynamic, int expected) throws Exception {
        assertNumericRateStep(true, lower, upper, initial, drift, List.of(dynamic), expected);
    }

    @ParameterizedTest
    @CsvSource({"2147483647, 20", "-2147483648, 3"})
    void sharedNumericRatesSaturateAfterExactComposition(String secondDynamic, int expected) throws Exception {
        assertNumericRateStep(false, 0, 20, 3, "[1,1]", List.of("2147483647", secondDynamic), expected);
    }

    private void assertNumericRateStep(boolean local, int lower, int upper, int initial,
                                       String drift, List<String> dynamics, int expected) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check numeric saturation");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = mapper.createObjectNode();
        var templates = scene.putArray("templates");
        var devices = scene.putArray("devices");
        for (int index = 0; index < dynamics.size(); index++) {
            String templateName = "Light Control " + index;
            ObjectNode manifest = mapper.createObjectNode().put("Name", templateName).put("Description", "")
                    .put("InitState", "off");
            manifest.putArray("Modes").add("PowerMode");
            manifest.putArray("APIs");
            manifest.putArray("Transitions");
            var states = manifest.putArray("WorkingStates");
            var off = states.addObject().put("Name", "off").put("Trust", "trusted").put("Privacy", "public");
            states.addObject().put("Name", "on").put("Trust", "trusted").put("Privacy", "public");
            var level = manifest.putArray("InternalVariables").addObject().put("Name", "lightLevel")
                    .put("IsInside", local).put("FalsifiableWhenCompromised", false).put("Trust", "trusted")
                    .put("Privacy", "public").put("LowerBound", lower).put("UpperBound", upper)
                    .put("NaturalChangeRate", drift);
            if (!local) {
                level.put("Reads", false);
                manifest.putArray("ImpactedVariables").add("lightLevel");
            }
            if (!"none".equals(dynamics.get(index))) {
                off.putArray("Dynamics").addObject().put("VariableName", "lightLevel")
                        .put("ChangeRate", dynamics.get(index));
            }
            templates.addObject().put("name", templateName).set("manifest", manifest);
            var device = devices.addObject().put("id", "light_" + index).put("templateName", templateName)
                    .put("state", "off");
            if (local) {
                device.putArray("variables").addObject().put("name", "lightLevel").put("value", String.valueOf(initial));
            }
        }
        List<BoardEnvironmentVariableDto> pool = local ? List.of() : List.of(
                new BoardEnvironmentVariableDto("lightLevel", String.valueOf(initial), "trusted", "public"));
        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, readDevices(scene), pool,
                List.of(), List.of(), AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.SIMULATION);
        assertCompleteModel(model);
        String variable = local ? "light_0.lightLevel" : "a_lightLevel";
        Files.writeString(model.smvFile().toPath(), "\nCTLSPEC (" + variable + " = " + initial
                + ") & AX (" + variable + " = " + expected + ")\n", StandardOpenOption.APPEND);
        var result = buildExecutor(nusmvPath).execute(model.smvFile());
        assertTrue(result.isSuccess(), result::getErrorMessage);
        assertEquals(List.of(true), result.getSpecResults().stream()
                .map(NusmvExecutor.SpecCheckResult::isPassed).toList(), result::getOutput);
    }

    @Test
    void signedAsciiTransitionLiteralsExecuteInNuSmv() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check admitted signed literals");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"counter_1","templateName":"Counter",
                    "variables":[{"name":"amount","value":"1"}]}],
                 "templates":[{"name":"Counter","manifest":{
                    "Name":"Counter", "InternalVariables":[{
                      "Name":"amount","IsInside":true,"FalsifiableWhenCompromised":false,
                      "Trust":"trusted","Privacy":"public","LowerBound":-2,"UpperBound":2}],
                    "Transitions":[{"Name":"reset","Trigger":{"Attribute":"amount","Relation":"=","Value":"+1"},
                      "Assignments":[{"Attribute":"amount","Value":"-1"}]}]
                  }}]}
                """);
        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, readDevices(scene), List.of(),
                List.of(), List.of(), AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.SIMULATION);
        assertCompleteModel(model);
        Files.writeString(model.smvFile().toPath(), "\nCTLSPEC (counter_1.amount = 1) & AX (counter_1.amount = -1)\n",
                StandardOpenOption.APPEND);
        var result = buildExecutor(nusmvPath).execute(model.smvFile());
        assertTrue(result.isSuccess(), result::getErrorMessage);
        assertEquals(List.of(true), result.getSpecResults().stream()
                .map(NusmvExecutor.SpecCheckResult::isPassed).toList(), result::getOutput);
    }

    @Test
    void frozenModeModelRejectsAnExactEnumCollisionAndKeepsCaseDistinctValues() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check case-distinct symbol use");
        ObjectMapper mapper = new ObjectMapper();
        assertModeAndEnumStayDistinctInNuSmv(mapper, nusmvPath, modeEnumSnapshot(mapper, "_next", false), "light_1");

        var conflict = assertThrows(SmvGenerationException.class,
                () -> generateFrozenModel(mapper, modeEnumSnapshot(mapper, "_Next", false)));
        assertTrue(conflict.getMessage().contains("_Next") && conflict.getMessage().contains("collides"),
                conflict::getMessage);
    }

    @Test
    void frozenBoardRejectsAnEnumCollisionBetweenDevicesInBothEngines() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to verify the separate-device enum control");
        ObjectMapper mapper = new ObjectMapper();
        assertModeAndEnumStayDistinctInNuSmv(mapper, nusmvPath, modeEnumSnapshot(mapper, "_next", true), "sensor_1");
        var snapshot = modeEnumSnapshot(mapper, "_Next", true);
        var exploration = new FuzzEngine().run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 2, 1, 1L)), null, () -> false);
        assertAll(
                () -> {
                    var conflict = assertThrows(SmvGenerationException.class, () -> generateFrozenModel(mapper, snapshot));
                    assertTrue(conflict.getMessage().contains("_Next") && conflict.getMessage().contains("sensor_1"),
                            conflict::getMessage);
                },
                () -> assertEquals("MODEL_INVALID", exploration.eligibility().get(0).reasonCode(), exploration::toString));
    }

    private void assertModeAndEnumStayDistinctInNuSmv(ObjectMapper mapper, String nusmvPath,
                                                     ModelInputSnapshot snapshot, String enumDevice) throws Exception {
        var model = generateFrozenModel(mapper, snapshot);
        Files.writeString(model.smvFile().toPath(),
                "\nCTLSPEC AG (light_1._Next = off & " + enumDevice + ".preset = _next)\n", StandardOpenOption.APPEND);
        var result = buildExecutor(nusmvPath).execute(model.smvFile());
        assertTrue(result.isSuccess(), result::getErrorMessage);
        assertEquals(List.of(true), result.getSpecResults().stream()
                .map(NusmvExecutor.SpecCheckResult::isPassed).toList(), result::getOutput);
    }

    private ModelInputSnapshot modeEnumSnapshot(ObjectMapper mapper, String preset, boolean separate) throws Exception {
        ObjectNode scene = (ObjectNode) mapper.readTree("""
                {"devices":[{"id":"light_1","templateName":"Light Control","state":"off"}],
                 "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control", "Modes":["Next"], "InitState":"off",
                    "WorkingStates":[{"Name":"off","Trust":"trusted","Privacy":"public",
                        "Dynamics":[{"VariableName":"preset","Value":"%s"}]},
                        {"Name":"on","Trust":"trusted","Privacy":"public"}],
                    "InternalVariables":[{"Name":"preset","IsInside":true,"FalsifiableWhenCompromised":false,
                        "Values":["%s","warm"],"Trust":"trusted","Privacy":"public"}]
                  }}]}
                """.formatted(preset, preset));
        if (separate) {
            var light = (ObjectNode) scene.withArray("templates").get(0).path("manifest");
            ((ObjectNode) light.path("WorkingStates").get(0)).remove("Dynamics");
            var sensor = scene.withArray("templates").addObject().put("name", "Sensor Preset")
                    .putObject("manifest").put("Name", "Sensor Preset");
            sensor.set("InternalVariables", light.remove("InternalVariables"));
            var transition = sensor.putArray("Transitions").addObject().put("Name", "restorePreset");
            transition.putObject("Trigger").put("Attribute", "preset").put("Relation", "=").put("Value", "warm");
            transition.putArray("Assignments").addObject().put("Attribute", "preset").put("Value", preset);
            scene.withArray("devices").addObject().put("id", "sensor_1").put("templateName", "Sensor Preset");
        }
        Map<String, DeviceManifest> manifests = new java.util.LinkedHashMap<>();
        for (JsonNode template : scene.withArray("templates")) {
            manifests.put(template.path("name").asText(), mapper.treeToValue(template.path("manifest"), DeviceManifest.class));
        }
        var specifications = List.of(neverSpec("never-on", "Light never turns on",
                specCondition("light_1", "mode", "Next", "=", "on")));
        return new ModelInputSnapshot(List.of(), readDevices(scene), List.of(), List.of(), specifications, manifests);
    }

    private SmvGenerator.GenerateResult generateFrozenModel(ObjectMapper mapper, ModelInputSnapshot snapshot)
            throws Exception {
        return generateFrozenModel(mapper, snapshot, false, AttackScenarioDto.none());
    }

    private SmvGenerator.GenerateResult generateFrozenModel(ObjectMapper mapper, ModelInputSnapshot snapshot,
                                                            boolean privacy, AttackScenarioDto scenario) throws Exception {
        var generator = buildGenerator(mapper, mapper.createObjectNode());
        var resolved = generator.buildDeviceSmvMapFromTemplateSnapshots(snapshot.devices(), snapshot.templateManifests());
        return generator.generateWithResolvedDeviceModel(USER_ID, snapshot.devices(), snapshot.environmentVariables(),
                snapshot.rules(), List.of(), scenario, privacy, SmvGenerator.GeneratePurpose.SIMULATION, null, resolved);
    }

    @ParameterizedTest
    @CsvSource({
            "Power, privacy_Power_off, privacy_Power_off, true",
            "private, cool, private, true",
            "Power, is_attack, is_attack, false"
    })
    void optionalNamespaceCollisionOnlyAppliesToARunThatEmitsTheSymbol(
            String mode, String literal, String collisionName, boolean privacy) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check names with the model options disabled");
        ObjectMapper mapper = new ObjectMapper();
        var base = modeEnumSnapshot(mapper, literal, true);
        DeviceManifest lamp = base.templateManifests().get("Light Control");
        lamp.setModes(List.of(mode));
        lamp.setApis(List.of(DeviceManifest.API.builder().name("turnOn").startState("off").endState("on")
                .signal(false).build()));
        var rules = List.of(rule(1L, "Turn the light on when off", command("light_1", "turnOn"),
                ruleCondition("light_1", mode, "mode", "=", "off")));
        var specs = List.of(neverSpec("never-on", "Light never turns on",
                specCondition("light_1", "mode", mode, "=", "on")));
        var snapshot = new ModelInputSnapshot(List.of(), base.devices(), List.of(), rules, specs,
                base.templateManifests());

        var explored = new FuzzEngine().run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 2, 1, 1L)), null, () -> false);
        assertEquals(List.of(1), explored.findings().stream().map(FuzzFinding::firstViolationStep).toList(),
                explored::toString);
        var model = generateFrozenModel(mapper, snapshot);
        Files.writeString(model.smvFile().toPath(), "\nCTLSPEC AX (light_1." + mode + " = on)\n",
                StandardOpenOption.APPEND);
        var checked = buildExecutor(nusmvPath).execute(model.smvFile());
        assertTrue(checked.isSuccess(), checked::getErrorMessage);
        assertEquals(List.of(true), checked.getSpecResults().stream()
                .map(NusmvExecutor.SpecCheckResult::isPassed).toList(), checked::getOutput);

        AttackScenarioDto scenario = privacy ? AttackScenarioDto.none() : AttackScenarioDto.anyUpToBudget(1);
        var conflict = assertThrows(SmvGenerationException.class,
                () -> generateFrozenModel(mapper, snapshot, privacy, scenario));
        assertTrue(conflict.getMessage().contains(collisionName) && conflict.getMessage().contains("collides"),
                conflict::getMessage);
    }

    @Test
    void authoredModeRuleSelectsTheSameFirstStepInBothEngines() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to compare the two engines");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"light_1","templateName":"Light Control","state":"off"}],
                 "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control", "Modes":["Next"], "InitState":"off",
                    "InternalVariables":[{"Name":"localLevel","IsInside":true,
                        "FalsifiableWhenCompromised":false,"Trust":"trusted","Privacy":"public",
                        "LowerBound":0,"UpperBound":5,"NaturalChangeRate":"0"}],
                    "WorkingStates":[{"Name":"off","Trust":"trusted","Privacy":"public"},
                                     {"Name":"on","Trust":"trusted","Privacy":"public"}],
                    "APIs":[{"Name":"turnOn","StartState":"off","EndState":"on","Signal":false}]
                  }}]}
                """);
        var devices = readDevices(scene);
        var rules = List.of(rule(1L, "Turn the light on when off", command("light_1", "turnOn"),
                ruleCondition("light_1", "Next", "mode", "=", "off")));
        var specs = List.of(neverSpec("never-on", "Light never turns on",
                specCondition("light_1", "mode", "Next", "=", "on")));
        assertBothEnginesFindFirstStep(mapper, scene, devices, rules, specs, List.of("off", "on"), nusmvPath);
    }

    @Test
    void authoredModeTriggerSelectsTheSameFirstStepInBothEngines() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to compare the two engines");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"light_1","templateName":"Light Control","state":"Case"}],
                 "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control", "Modes":["Next"], "InitState":"Case",
                    "WorkingStates":[{"Name":"Case","Trust":"trusted","Privacy":"public"},
                                     {"Name":"ready","Trust":"trusted","Privacy":"public"}],
                    "Transitions":[{"Name":"turnOn","Trigger":{"Attribute":"Next","Relation":"=","Value":"Case"},
                        "EndState":"ready"}]
                  }}]}
                """);
        var specs = List.of(neverSpec("never-ready", "Light never becomes ready",
                specCondition("light_1", "mode", "Next", "=", "ready")));
        assertBothEnginesFindFirstStep(
                mapper, scene, readDevices(scene), List.of(), specs, List.of("Case", "ready"), nusmvPath);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void singleModeWhitespaceUsesTheSameAuthoredStatesInBothEngines(boolean useTemplateInitialState) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to compare the two engines");
        String initialState = useTemplateInitialState ? "ready" : "off";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"light_1","templateName":"Light Control"}],
                 "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control", "Modes":["Power"], "InitState":"\\tready\\r",
                    "WorkingStates":[{"Name":"on\\t","Trust":"trusted","Privacy":"public"},
                                     {"Name":"off\\t","Trust":"trusted","Privacy":"public"},
                                     {"Name":"\\tready","Trust":"trusted","Privacy":"public"}],
                    "APIs":[{"Name":"turnOn","StartState":"\\t%s\\r","EndState":"\\ton\\t","Signal":false}]
                  }}]}
                """.formatted(initialState));
        var devices = readDevices(scene);
        devices.get(0).setState(useTemplateInitialState ? null : "\toff\r");
        var rules = List.of(rule(1L, "Turn the light on", command("light_1", "turnOn"),
                ruleCondition("light_1", "Power", "mode", "=", initialState)));
        var specs = List.of(neverSpec("never-on", "Light never turns on",
                specCondition("light_1", "mode", "Power", "=", "on")));
        assertBothEnginesFindFirstStep(mapper, scene, devices, rules, specs,
                List.of(initialState, "on"), nusmvPath);
    }

    @Test
    void stateDerivedInitialValuesUseTheSameAuthorityInBothEnginesAndRepair() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to compare the two engines");
        var mapper = new ObjectMapper();
        var scene = mapper.createObjectNode();
        scene.putArray("devices").addObject().put("id", "car_1").put("templateName", "Car").put("state", "away");
        var template = scene.putArray("templates").addObject().put("name", "Car");
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/deviceTemplate/Car.json"))) {
            template.set("manifest", mapper.readTree(resource));
        }
        var manifest = mapper.treeToValue(template.path("manifest"), DeviceManifest.class);
        var devices = readDevices(scene);
        var car = devices.get(0);
        car.setVariables(List.of(new VariableStateDto("location", "garage", "trusted")));
        var away = specCondition("car_1", "variable", "location", "=", "away");
        var garage = specCondition("car_1", "variable", "location", "=", "garage");
        away.setVariableSource("reported");
        garage.setVariableSource("reported");
        var specs = List.of(neverSpec("never-away", "The car never leaves home", away),
                neverSpec("never-garage", "The car never stays in the garage", garage));

        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, devices, List.of(), List.of(),
                specs, AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        var verdicts = verdictsBySpecId(FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), model), model);
        assertFalse(verdicts.get("never-away").isPassed());
        assertTrue(verdicts.get("never-garage").isPassed());
        var snapshot = new ModelInputSnapshot(List.of(), devices, List.of(), List.of(), specs, Map.of("Car", manifest));
        var finite = new FuzzEngine().run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 2, 2, 1, 1L)), null, () -> false);
        String recordedFingerprint = BoardSemanticFingerprint.of(devices, specs, model.deviceSmvMap());
        car.setVariables(List.of(new VariableStateDto("location", "away", "trusted")));
        assertAll(
                () -> assertEquals(List.of(List.of("never-away", 0)), finite.findings().stream()
                        .map(finding -> List.of(finding.specification().getId(), finding.firstViolationStep())).toList()),
                () -> assertEquals(recordedFingerprint, BoardSemanticFingerprint.of(devices, specs, model.deviceSmvMap())));
    }

    @Test
    void multiModeConditionsResolveAuthoredStatesBeforeEscapingForNuSmv() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to compare the two engines");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"light_1","templateName":"Light Control","state":"Case;fan idle"}],
                 "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control","Modes":["Next","FanMode"],"InitState":"Case;fan idle",
                    "WorkingStates":[
                        {"Name":"Case;fan idle","Trust":"trusted","Privacy":"public"},
                        {"Name":"night standby;_Case","Trust":"trusted","Privacy":"public"}],
                    "APIs":[{"Name":"goToStandby","StartState":"Case;_","EndState":"night standby;","Signal":false}]
                  }}]}
                """);
        var rules = List.of(rule(1L, "Enter standby while in Case", command("light_1", "goToStandby"),
                ruleCondition("light_1", "STATE", "state", "=", "Case"),
                ruleCondition("light_1", "FanMode", "mode", "not in", "_Case")));
        var neverStandby = neverSpec("never-standby", "Never reaches standby with the fan idle",
                specCondition("light_1", "state", "state", "in", "night standby;fan idle|Case;_Case"));

        assertBothEnginesFindFirstStep(mapper, scene, readDevices(scene), rules, List.of(neverStandby),
                List.of("Case;fan idle", "night standby;fan idle"), nusmvPath);
    }

    @Test
    void wildcardMembershipAndItsComplementDriveTheSameTransitionsInBothEngines() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to compare the two engines");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"light_1","templateName":"Light Control","state":"Case;fan idle"}],
                 "templates":[{"name":"Light Control","manifest":{
                    "Name":"Light Control","Modes":["Next","FanMode"],"InitState":"Case;fan idle",
                    "WorkingStates":[
                        {"Name":"Case;fan idle","Trust":"trusted","Privacy":"public"},
                        {"Name":"night standby;_Case","Trust":"trusted","Privacy":"public"}],
                    "APIs":[
                        {"Name":"goToStandby","StartState":"Case;_","EndState":"night standby;","Signal":false},
                        {"Name":"switchFan","StartState":";fan idle","EndState":";_Case","Signal":false}]
                  }}]}
                """);
        var rules = List.of(
                rule(1L, "Enter standby inside the selected set", command("light_1", "goToStandby"),
                        ruleCondition("light_1", "state", "state", "IN", "Case;_, _;_Case")),
                rule(2L, "Switch the fan outside the selected set", command("light_1", "switchFan"),
                        ruleCondition("light_1", "state", "state", "NOT_IN", "Case; | ;_Case")));
        var outside = neverSpec("outside", "Never leaves the set",
                specCondition("light_1", "state", "state", "NOT_IN", "Case;_, ;_Case"));
        var fan = neverSpec("fan", "The fan never switches",
                specCondition("light_1", "mode", "FanMode", "=", "_Case"));
        var exclusive = neverSpec("exclusive", "Membership and exclusion never overlap",
                specCondition("light_1", "state", "state", "IN", "Case; | ;_Case"),
                specCondition("light_1", "state", "state", "NOT_IN", "Case;_, _;_Case"));
        var specs = List.of(outside, fan, exclusive);
        var devices = readDevices(scene);
        var manifest = mapper.treeToValue(scene.path("templates").get(0).path("manifest"), DeviceManifest.class);
        var snapshot = new ModelInputSnapshot(List.of(), devices, List.of(), rules, specs, Map.of(manifest.getName(), manifest));
        var finite = new FuzzEngine().run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 3, 1, 1L)), null, () -> false);
        assertEquals(Map.of("outside", 1, "fan", 2), finite.findings().stream()
                .collect(java.util.stream.Collectors.toMap(finding -> finding.specification().getId(),
                        FuzzFinding::firstViolationStep)), finite::toString);

        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, devices, List.of(), rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        var verdicts = verdictsBySpecId(FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), model), model);
        assertTrue(verdicts.get("exclusive").isPassed());
        for (var finding : finite.findings()) {
            var violation = verdicts.get(finding.specification().getId());
            assertFalse(violation.isPassed());
            var trace = new SmvTraceParser().parseCounterexampleStates(
                    violation.getCounterexample(), model.deviceSmvMap(), rules);
            assertEquals(finding.states().stream().map(state -> state.getDevices().get(0).getState()).toList(),
                    trace.stream().map(state -> state.getDevices().get(0).getState()).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"on;low", "on; low"})
    void derivedLocalValueUsesTheInitialModesRegardlessOfTupleSpacing(String startingState) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the initial tuple regression");
        var mapper = new ObjectMapper();
        var scene = (ObjectNode) mapper.readTree("""
                {"devices":[{"id":"panel","templateName":"Panel"}],
                 "templates":[{"name":"Panel","manifest":{"Name":"Panel","Modes":["Power","Fan"],"InitState":"on;low",
                  "WorkingStates":[{"Name":"on;low","Trust":"trusted","Privacy":"public",
                                    "Dynamics":[{"VariableName":"preset","Value":"active"}]},
                                   {"Name":"off;high","Trust":"trusted","Privacy":"public"}],
                  "InternalVariables":[{"Name":"preset","IsInside":true,"FalsifiableWhenCompromised":false,
                                        "Values":["idle","active"],"Trust":"trusted","Privacy":"public"}]}}]}
                """);
        ((ObjectNode) scene.path("devices").get(0)).put("state", startingState);
        var devices = readDevices(scene);
        var preset = specCondition("panel", "variable", "preset", "=", "idle");
        preset.setVariableSource("reported");
        var safety = neverSpec("never-idle", "Preset never idles in the starting modes", preset);
        var specs = List.of(safety);
        var manifest = mapper.treeToValue(scene.path("templates").get(0).path("manifest"), DeviceManifest.class);
        var snapshot = new ModelInputSnapshot(List.of(), devices, List.of(), List.of(), specs,
                Map.of(manifest.getName(), manifest));
        var explored = new FuzzEngine().run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 2, 1, 1L)), null, () -> false);

        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, devices, List.of(), List.of(), specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        var verdict = verdictsBySpecId(FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), model), model)
                .get(safety.getId());
        assertAll(() -> assertTrue(verdict.isPassed()), () -> assertTrue(explored.findings().isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-start", "api-end", "transition-start", "transition-end", "mode-trigger", "short-endpoint"})
    void endpointsAndTriggersRejectUndeclaredAuthoredStatesInBothEngines(String invalid) throws Exception {
        var mapper = new ObjectMapper();
        var scene = (ObjectNode) mapper.readTree("""
                {"devices":[{"id":"panel","templateName":"Panel","state":"off;_Case"}],
                 "templates":[{"name":"Panel","manifest":{"Name":"Panel","Modes":["Power","Fan"],"InitState":"off;_Case",
                  "WorkingStates":[{"Name":"off;_Case","Trust":"trusted","Privacy":"public"},
                                   {"Name":"Case;ready","Trust":"trusted","Privacy":"public"}]}}]}
                """);
        var manifestNode = (ObjectNode) scene.path("templates").get(0).path("manifest");
        if (invalid.startsWith("api-") || "short-endpoint".equals(invalid)) {
            manifestNode.putArray("APIs").addObject().put("Name", "start").put("Signal", false)
                    .put("StartState", "api-start".equals(invalid) ? "off;Case" : "off;_Case")
                    .put("EndState", switch (invalid) {
                        case "api-end" -> "Case;Case";
                        case "short-endpoint" -> "Case";
                        default -> "Case;ready";
                    });
        } else {
            var change = manifestNode.putArray("Transitions").addObject().put("Name", "change")
                    .put("StartState", switch (invalid) {
                        case "transition-start" -> "off;Case";
                        case "transition-end" -> "off;ready";
                        default -> "_";
                    })
                    .put("EndState", "transition-end".equals(invalid) ? ";Case" : "Case;");
            change.putObject("Trigger").put("Attribute", "Fan").put("Relation", "=")
                    .put("Value", switch (invalid) {
                        case "mode-trigger" -> "Case";
                        case "transition-end" -> "ready";
                        default -> "_Case";
                    });
        }

        var devices = readDevices(scene);
        var specs = List.of(neverSpec("never-ready", "Never ready",
                specCondition("panel", "state", "state", "=", "Case;ready")));
        var manifest = mapper.treeToValue(manifestNode, DeviceManifest.class);
        var snapshot = new ModelInputSnapshot(List.of(), devices, List.of(), List.of(), specs,
                Map.of(manifest.getName(), manifest));
        var explored = new FuzzEngine().run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 2, 1, 1L)), null, () -> false);
        String expected = "short-endpoint".equals(invalid) ? "segments" : "Fan";
        assertAll(
                () -> {
                    var failure = assertThrows(BaseException.class, () ->
                            buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, devices, List.of(), List.of(),
                                    specs, AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION));
                    assertTrue(failure.getMessage().contains(expected), failure::getMessage);
                },
                () -> {
                    var eligibility = explored.eligibility().get(0);
                    assertEquals("MODEL_INVALID", eligibility.reasonCode());
                    assertTrue(eligibility.reason().contains(expected), eligibility::reason);
                });
    }

    private void assertBothEnginesFindFirstStep(ObjectMapper mapper, JsonNode scene,
            List<DeviceVerificationDto> devices, List<RuleDto> rules, List<SpecificationDto> specs,
            List<String> authoredStates, String nusmvPath) throws Exception {
        var manifest = mapper.treeToValue(scene.path("templates").get(0).path("manifest"), DeviceManifest.class);
        var snapshot = new ModelInputSnapshot(List.of(), devices, List.of(), rules, specs, Map.of(manifest.getName(), manifest));
        var explored = new FuzzEngine().run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 2, 1, 1L)), null, () -> false);
        assertEquals(List.of(1), explored.findings().stream().map(FuzzFinding::firstViolationStep).toList(),
                explored::toString);

        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, devices, List.of(), rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        var verdicts = verdictsBySpecId(FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), model), model);
        var violation = verdicts.get(specs.get(0).getId());
        assertAll(() -> assertCompleteModel(model), () -> assertFalse(violation.isPassed()));

        var trace = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), model.deviceSmvMap(), rules);
        var finiteModes = explored.findings().get(0).states().stream()
                .map(state -> state.getDevices().get(0).getMode()).toList();
        assertEquals(finiteModes, trace.stream().map(state -> state.getDevices().get(0).getMode()).toList());
        var finiteStates = explored.findings().get(0).states().stream()
                .map(state -> state.getDevices().get(0).getState()).toList();
        assertAll(
                () -> assertEquals(authoredStates, finiteStates, "Fuzz playback state names"),
                () -> assertEquals(authoredStates,
                        trace.stream().map(state -> state.getDevices().get(0).getState()).toList(),
                        "NuSMV playback state names"));
        var initialDevice = trace.get(0).getDevices().get(0);
        var finiteDevice = explored.findings().get(0).states().get(0).getDevices().get(0);
        assertEquals(finiteDevice.getVariables().stream()
                        .map(variable -> List.of(variable.getName(), variable.getValue())).toList(),
                initialDevice.getVariables().stream()
                        .map(variable -> List.of(variable.getName(), variable.getValue())).toList());
        var constraints = CounterexampleInitialStateConstraints.build(
                trace.get(0), rules, model.deviceSmvMap(), AttackScenarioDto.none(), true);
        String[] initialStates = authoredStates.get(0).split(";");
        for (int index = 0; index < manifest.getModes().size(); index++) {
            String authoredMode = manifest.getModes().get(index);
            String authoredState = initialStates[index];
            String modeToken = DeviceSmvDataFactory.sanitizeSmvToken(authoredMode);
            String stateToken = DeviceSmvDataFactory.sanitizeSmvToken(authoredState);
            assertTrue(initialDevice.getTrustPrivacy().stream().anyMatch(label ->
                    authoredMode.equals(label.getMode()) && authoredState.equals(label.getName())));
            assertTrue(initialDevice.getPrivacies().stream().anyMatch(label -> "state".equals(label.getPropertyScope())
                    && authoredMode.equals(label.getMode()) && authoredState.equals(label.getName())));
            assertTrue(constraints.contains("light_1." + modeToken + " = " + stateToken));
            assertTrue(constraints.stream().anyMatch(constraint -> constraint.startsWith("light_1.trust_" + modeToken + "_")));
            assertTrue(constraints.stream().anyMatch(constraint -> constraint.startsWith("light_1.privacy_" + modeToken + "_")));
        }
    }

    @Test
    void onlyTheSelectedObservableActionProducesItsPulse() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check observable action identity");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {
                  "devices": [{"id":"control_1", "templateName":"Two Controls", "state":"off;idle"}],
                  "templates": [{"name":"Two Controls", "manifest":{
                    "Name":"Two Controls", "Description":"", "InternalVariables":[],
                    "Modes":["PowerMode","FanMode"], "InitState":"off;idle",
                    "WorkingStates":[
                      {"Name":"off;idle","Trust":"trusted","Privacy":"public"},
                      {"Name":"on;idle","Trust":"trusted","Privacy":"public"},
                      {"Name":"off;running","Trust":"trusted","Privacy":"public"},
                      {"Name":"on;running","Trust":"trusted","Privacy":"public"}],
                    "Transitions":[], "APIs":[
                      {"Name":"both","StartState":"off;idle","EndState":"on;running","Signal":true},
                      {"Name":"powerOn","StartState":"off;","EndState":"on;","Signal":false},
                      {"Name":"startFan","StartState":";idle","EndState":";running","Signal":false}]
                  }}]
                }
                """);
        var generator = buildGenerator(mapper, scene);
        var executor = buildExecutor(nusmvPath);
        var devices = readDevices(scene);
        var initialState = ruleCondition("control_1", "state", "state", "=", "off;idle");
        var neverBoth = neverSpec("never-both", "Combined action never occurs",
                specCondition("control_1", "api", "both", "=", "TRUE"));
        var reachBothModes = immediateSpec("both-modes", "Both modes change on the first transition",
                specCondition("control_1", "state", "state", "=", "off;idle"),
                specCondition("control_1", "state", "state", "=", "on;running"));
        var specs = List.of(neverBoth, reachBothModes);
        var separateCommands = List.of(
                rule(1L, "Turn on power", command("control_1", "powerOn"), initialState),
                rule(2L, "Start the fan", command("control_1", "startFan"), initialState));
        var combinedCommand = List.of(rule(3L, "Run the combined command", command("control_1", "both"), initialState));

        for (var rules : List.of(separateCommands, combinedCommand)) {
            var model = generator.generateWithEnvironment(USER_ID, devices, List.of(), rules, specs,
                    AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
            assertCompleteModel(model);
            var checks = verdictsBySpecId(FormalCheckTestSupport.checkProperties(executor, model), model);
            assertTrue(checks.get("both-modes").isPassed());
            assertEquals(rules.size() == 2, checks.get("never-both").isPassed(),
                    "Disjoint actions can change both modes without executing the combined observable action");
        }
    }

    @Test
    void apiBooleanSpecificationsAcceptCaseInsensitiveScalarsAndMembership() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check API Boolean literals");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = mapper.createObjectNode();
        scene.putArray("devices").addObject().put("id", "light_1")
                .put("templateName", "Light").put("state", "off");
        scene.putArray("templates").addObject().put("name", "Light").set("manifest", mapper.readTree(
                Files.readString(Path.of("src", "main", "resources", "deviceTemplate", "Light.json"))));
        var specs = List.of(
                neverSpec("eq", "The on event is never false",
                        specCondition("light_1", "api", "on", "=", " false ")),
                neverSpec("in", "The on event is never true",
                        specCondition("light_1", "api", "on", "in", "true")),
                neverSpec("not-in", "The on event is never outside the Boolean domain",
                        specCondition("light_1", "api", "on", "not in", "fAlSe, TrUe")));

        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, readDevices(scene),
                List.of(), List.of(), specs, AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        var checks = verdictsBySpecId(FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), model), model);
        assertFalse(checks.get("eq").isPassed());
        assertTrue(checks.get("in").isPassed());
        assertTrue(checks.get("not-in").isPassed());
    }

    @ParameterizedTest
    @CsvSource({"=, TRUE", "IN, true", "NOT_IN, false"})
    void conditionRepairOffersTheSameApiEventGuardForEquivalentComparisons(String relation, String value)
            throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to verify the event guard");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"lamp_1","templateName":"Ordinary Lamp","state":"dark"},
                            {"id":"button_1","templateName":"Ordinary Button","state":"released"}],
                 "templates":[{"name":"Ordinary Lamp","manifest":{
                    "Name":"Ordinary Lamp","Modes":["LightState"],"InitState":"dark",
                    "WorkingStates":[{"Name":"dark","Trust":"trusted","Privacy":"public"},
                                     {"Name":"lit","Trust":"trusted","Privacy":"public"}],
                    "APIs":[{"Name":"light","StartState":"dark","EndState":"lit","Signal":false}]}},
                    {"name":"Ordinary Button","manifest":{
                    "Name":"Ordinary Button","Modes":["ButtonState"],"InitState":"released",
                    "WorkingStates":[{"Name":"released","Trust":"trusted","Privacy":"public"},
                                     {"Name":"pressed","Trust":"trusted","Privacy":"public"}],
                    "APIs":[{"Name":"press","StartState":"released","EndState":"pressed","Signal":true}]}}]}
                """);
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        var devices = readDevices(scene);
        var rules = List.of(
                rule(1L, "Turn on the lamp", command("lamp_1", "light"),
                        ruleCondition("lamp_1", "state", "state", "=", "dark")),
                rule(2L, "Press the button once", command("button_1", "press"),
                        ruleCondition("button_1", "state", "state", "=", "released")));
        var safety = neverSpec("never-simultaneous", "A press must not coincide with a lit lamp",
                specCondition("lamp_1", "state", "state", "=", "lit"),
                specCondition("button_1", "api", "press", relation, value));
        var specs = List.of(safety);
        var baseline = generator.generateWithEnvironment(USER_ID, devices, List.of(), rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baseline);
        var verdict = verdictsBySpecId(FormalCheckTestSupport.checkProperties(executor, baseline), baseline)
                .get(safety.getId());
        assertFalse(verdict.isPassed());
        var states = new SmvTraceParser().parseCounterexampleStates(
                verdict.getCounterexample(), baseline.deviceSmvMap(), rules);
        var fixes = allStrategyFixer(generator, executor, 30_000).fix(
                818L, safety.getId(), states, rules, devices, List.of(), specs,
                baseline.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                List.of("condition"), 8, Map.of(), () -> { });

        var proposal = listedRepair(fixes, "condition", "the button event guarding the lamp",
                candidate -> candidate.getConditionAdjustments().size() == 1
                        && candidate.getConditionAdjustments().stream().anyMatch(change ->
                        "add".equals(change.getAction()) && change.getRuleIndex() == 0
                                && "button_1".equals(change.getDeviceName()) && "press".equals(change.getAttribute())
                                && "api".equals(change.getTargetType())
                                && change.getRelation() == null && change.getValue() == null));
        assertEveryListedAlternativeHolds(List.of(proposal), safety.getId(), rules, devices, List.of(), specs,
                baseline.deviceSmvMap(), generator, executor);
    }

    @Test
    void variableWritesPreserveBooleanLikeEnumLiteralCase() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to distinguish case-sensitive enum literals");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {
                  "devices": [{"id":"control_1", "templateName":"Enum Control", "state":"idle",
                    "variables":[{"name":"localFlag","value":"True"}]}],
                  "templates": [{"name":"Enum Control", "manifest":{
                    "Name":"Enum Control", "Description":"", "Modes":["ControlMode"], "InitState":"idle",
                    "ImpactedVariables":["roomFlag"],
                    "InternalVariables":[
                      {"Name":"localFlag","IsInside":true,"FalsifiableWhenCompromised":false,
                        "Trust":"trusted","Privacy":"public","Values":["tr ue","Tr ue"]},
                      {"Name":"roomFlag","IsInside":false,"Reads":true,"FalsifiableWhenCompromised":false,
                        "Trust":"trusted","Privacy":"public","Values":["tr ue","Tr ue"]}],
                    "WorkingStates":[
                      {"Name":"idle","Trust":"trusted","Privacy":"public",
                        "Dynamics":[{"VariableName":"roomFlag","Value":"True"}]},
                      {"Name":"active","Trust":"trusted","Privacy":"public"}],
                    "APIs":[], "Transitions":[
                      {"Name":"update flag", "Trigger":{"Attribute":"localFlag","Relation":"=","Value":"True"},
                        "Assignments":[{"Attribute":"localFlag","Value":"true"}]}]
                  }}]
                }
                """);
        var localBefore = specCondition("control_1", "variable", "localFlag", "=", "True");
        var localAfter = specCondition("control_1", "variable", "localFlag", "=", "true");
        localBefore.setVariableSource("reported");
        localAfter.setVariableSource("reported");
        var specs = List.of(
                immediateSpec("local", "Assignment writes the lowercase enum literal", localBefore, localAfter),
                immediateSpec("shared", "Dynamics writes the title-case enum literal",
                        specCondition("control_1", "variable", "roomFlag", "=", "true"),
                        specCondition("control_1", "variable", "roomFlag", "=", "True")));

        var model = buildGenerator(mapper, scene).generateWithEnvironment(USER_ID, readDevices(scene),
                List.of(new BoardEnvironmentVariableDto("roomFlag", "true", "trusted", "public")),
                List.of(), specs, AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(model);
        var checks = verdictsBySpecId(FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), model), model);
        assertTrue(checks.get("local").isPassed());
        assertTrue(checks.get("shared").isPassed());
        String smv = Files.readString(model.smvFile().toPath());
        assertTrue(smv.contains("localFlag: {true, True};"), () -> smv);
        assertTrue(smv.contains("a_roomFlag: {true, True};"), () -> smv);
    }

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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for this acceptance smoke test");

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
        List<RuleDto> rules = importRules(scene);
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
                        && "taking photo".equals(device.getState())));

        SmvGenerator.GenerateResult simulationModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.SIMULATION);
        NusmvExecutor.SimulationOutput simulation =
                executor.executeInteractiveSimulation(simulationModel.smvFile(), 6);
        assertTrue(simulation.isSuccess(), simulation::getErrorMessage);
        List<TraceStateDto> simulationStates = traceParser.parseCounterexampleStates(
                simulation.getTraceText(), simulationModel.deviceSmvMap(), rules);
        assertTrue(simulationStates.size() >= 2, "Simulation must produce an animatable model trajectory");
        assertTrue(hasDeviceState(simulationStates, "camera_1", "taking photo"),
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
                Map.of(), () -> { });
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
    void coupledClimateThresholds_parameterStrategyFindsClosestSingleChange() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the climate parameter-fix regression");

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
                List.of("parameter"), 20, Map.of(), () -> { });

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
                "parameter", suggestion, rules, Map.of(), Map.of());
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, repairedRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(repairedModel);
        NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
        assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
        assertEquals(specs.size(), repaired.getSpecResults().size());
        assertEquals(0, violationCount(repaired),
                "The parameter suggestion must satisfy both sides of the climate boundary");
    }

    @ParameterizedTest
    @ValueSource(ints = {MAX_RULE_CONDITIONS - 1, MAX_RULE_CONDITIONS})
    void occupancySafety_conditionStrategySolvesCandidateValueWithinRuleLimit(int conditionCount) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the free condition-value regression");

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
        // Redundant conjuncts approach the authoring limit without changing the baseline behavior.
        for (int index = 1; index < conditionCount; index++) {
            rules.get(0).getConditions().add(ruleCondition("temperature_1", "temperature", "variable", "<=", "18"));
        }
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
                List.of("condition"), 20, Map.of(), () -> { });

        assertTrue(fixResult.isFixable(), fixResult::getSummary);
        assertEquals(1, fixResult.getFaultRules().size());
        assertVerifiedAttempt(fixResult, "condition");
        Map<String, String> persistenceRefs = Map.of(
                "occupancy_1", "occupancy_1", "ac_1", "ac_1", "temperature_1", "temperature_1");
        for (FixSuggestionDto candidate : listed(fixResult, "condition")) {
            List<RuleDto> candidateRules = FixStrategyApplier.apply(
                    "condition", candidate, rules, persistenceRefs, Map.of());
            int repairedConditionCount = candidateRules.get(0).getConditions().size();
            assertTrue(repairedConditionCount <= MAX_RULE_CONDITIONS,
                    () -> "Listed an inapplicable repair with " + repairedConditionCount + " conditions");
        }
        boolean needsReplacement = conditionCount == MAX_RULE_CONDITIONS;
        FixSuggestionDto suggestion = listedRepair(fixResult, "condition",
                "a solved guard requiring occupancy instead of copying occupied=absent",
                candidate -> addsOccupancyGuard(candidate, 0)
                        && needsReplacement == candidate.getConditionAdjustments().stream()
                                .anyMatch(adjustment -> "remove".equals(adjustment.getAction())));

        List<RuleDto> repairedRules = FixStrategyApplier.apply(
                "condition", suggestion, rules, persistenceRefs, Map.of());
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, repairedRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(repairedModel);
        NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
        assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
        assertEquals(specs.size(), repaired.getSpecResults().size());
        assertEquals(0, violationCount(repaired),
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
        assertEquals(0, violationCount(occupied), "The repaired rule must still heat the occupied cold room");
    }

    @Test
    void combinedClimateSafety_allThreeStrategiesIndependentlyRepairTheSameCounterexample() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the combined three-strategy regression");

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
                "parameter", expectedParameterRepair, rules, Map.of(), Map.of());
        SmvGenerator.GenerateResult expectedParameterModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, expectedParameterRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(expectedParameterModel);
        NusmvExecutor.NusmvResult expectedParameterVerification =
                FormalCheckTestSupport.checkProperties(executor, expectedParameterModel);
        assertTrue(expectedParameterVerification.isSuccess(), expectedParameterVerification::getErrorMessage);
        assertEquals(0, violationCount(expectedParameterVerification),
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
                strategies, 20, Map.of(), () -> { });

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
                strategies, 20, Map.of(), () -> { });
        assertEquals("SKIPPED_NO_SPEC", attemptStatus(unknownSpecResult, "parameter"));
        assertEquals("SKIPPED_NO_SPEC", attemptStatus(unknownSpecResult, "condition"));
        // Response preservation cannot establish a repair when its target is not attributable.
        assertEquals("INCONCLUSIVE", attemptStatus(unknownSpecResult, "remove"));
        assertTrue(listed(unknownSpecResult, "remove").isEmpty());
    }

    @Test
    void inverseClimateBoundary_allThreeStrategiesSkipDuplicateAndRepair() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the inverse-boundary fix regression");

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
                strategies, 20, Map.of(), () -> { });

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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the joint-repair regression");

        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(objectMapper, "30");
        ObjectNode occupancyManifest = (ObjectNode) scene.withArray("templates").get(
                scene.withArray("templates").size() - 1).path("manifest");
        var preset = occupancyManifest.withArray("InternalVariables").addObject().put("Name", "preset")
                .put("IsInside", true).put("FalsifiableWhenCompromised", false)
                .put("Trust", "trusted").put("Privacy", "public");
        // The ordinary model is identical, but solver-only repair names must avoid these legal enum literals.
        preset.putArray("Values").add("idle").add("param_r0_c0").add("param_r0_c0_1")
                .add("lambda_r0_c0").add("condition_value_r0_c1").add("iot_verify_guard_probe_0");
        var keepPreset = occupancyManifest.withArray("Transitions").addObject().put("Name", "holdPreset");
        keepPreset.putObject("Trigger").put("Attribute", "preset").put("Relation", "=").put("Value", "idle");
        keepPreset.putArray("Assignments").addObject().put("Attribute", "preset").put("Value", "idle");
        // Device instances also occupy the suffixes available after skipping the enum literals.
        var sceneDevices = scene.withArray("devices");
        ObjectNode occupancyDevice = (ObjectNode) sceneDevices.get(sceneDevices.size() - 1);
        for (String id : List.of("param_r0_c0_2", "lambda_r0_c0_1", "condition_value_r0_c1_1",
                "iot_verify_guard_probe_1")) {
            sceneDevices.add(occupancyDevice.deepCopy().put("id", id).put("label", id));
        }
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
                strategies, 20, Map.of(), () -> { });

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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the counterexample-learning regression");

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
    void conditionRepairSignsAndAppliesTheAuthoredWildcardSet() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the wildcard repair regression");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = withOccupancySensor(mapper, "30");
        var templates = scene.withArray("templates");
        ((ObjectNode) templates.get(templates.size() - 1)).set("manifest", mapper.readTree("""
                {"Name":"Occupancy Sensor","Modes":["RoomPresence","PresencePhase"],"InitState":"present;entry",
                 "WorkingStates":[
                     {"Name":"present;entry","Trust":"trusted","Privacy":"public"},
                     {"Name":"absent;entry","Trust":"trusted","Privacy":"public"},
                     {"Name":"arrived;entry","Trust":"trusted","Privacy":"public"},
                     {"Name":"arrived;boost","Trust":"trusted","Privacy":"public"}],
                 "Transitions":[
                     {"Name":"leaveRoom","StartState":"present;entry","EndState":"absent;",
                      "Trigger":{"Attribute":"PresencePhase","Relation":"=","Value":"entry"}},
                     {"Name":"returnToRoom","StartState":"absent;entry","EndState":"arrived;",
                      "Trigger":{"Attribute":"PresencePhase","Relation":"=","Value":"entry"}}]}
                """));
        var sceneDevices = scene.withArray("devices");
        ObjectNode occupancy = (ObjectNode) sceneDevices.get(sceneDevices.size() - 1);
        occupancy.put("state", "present;entry").remove("variables");
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        var devices = readDevices(scene);
        var environment = readEnvironment(scene);
        var rules = List.of(rule(83L, "Heat the room while the temperature is at least 28",
                command("ac_1", "heat"), ruleCondition("temperature_1", "temperature", "variable", ">=", "28")));
        String authoredSet = "absent;_ | arrived;boost";
        var safety = neverSpec("heat-during-absence", "Do not heat while the room is briefly empty",
                specCondition("occupancy_1", "state", "state", "in", authoredSet),
                specCondition("ac_1", "mode", "HvacMode", "=", "heat"));
        var specs = List.of(safety);
        var baseline = generator.generateWithEnvironment(USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baseline);
        var verdict = verdictsBySpecId(FormalCheckTestSupport.checkProperties(executor, baseline), baseline)
                .get(safety.getId());
        assertFalse(verdict.isPassed());
        var states = new SmvTraceParser().parseCounterexampleStates(
                verdict.getCounterexample(), baseline.deviceSmvMap(), rules);

        long traceId = 315L;
        var fixes = allStrategyFixer(generator, executor, 30_000).fix(
                traceId, safety.getId(), states, rules, devices, environment, specs,
                baseline.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false,
                List.of("condition"), 8, Map.of(), () -> { });
        var proposal = listedRepair(fixes, "condition", "the original wildcard set as a trigger",
                candidate -> candidate.getConditionAdjustments().stream()
                        .anyMatch(change -> "add".equals(change.getAction()) && "state".equals(change.getTargetType())
                                && "in".equals(change.getRelation()) && authoredSet.equals(change.getValue())));

        JwtConfig jwt = new JwtConfig();
        jwt.setSecret("test-secret-that-is-long-enough-for-hmac-signatures");
        var tokens = new FixSuggestionTokenService(mapper, jwt);
        String token = tokens.issue(USER_ID, traceId, proposal, Map.of());
        var visibleProposal = mapper.readValue(mapper.writeValueAsBytes(proposal), FixSuggestionDto.class);
        var trustedProposal = tokens.verify(USER_ID, traceId, "condition", visibleProposal, token, Map.of());
        assertEveryListedAlternativeHolds(List.of(trustedProposal), safety.getId(), rules, devices, environment,
                specs, baseline.deviceSmvMap(), generator, executor);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '/', value = {
            "false / in / on;running",
            "true / not in / on;idle | off;_",
            "true / in / _;running"
    })
    void repairKeepsAGuardWhenDisjointModeWritesCanTriggerConflictingFeedback(
            boolean autonomousFan, String feedbackRelation, String feedbackValue) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for feedback pruning");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = (ObjectNode) mapper.readTree("""
                {"devices":[{"id":"panel","templateName":"Panel","state":"off;idle"},
                            {"id":"lamp","templateName":"Lamp","state":"off"},
                            {"id":"gate","templateName":"Startup Gate","state":"opening"}],
                 "templates":[
                     {"name":"Panel","manifest":{"Name":"Panel","Modes":["PowerMode","FanMode"],
                      "InitState":"off;idle","WorkingStates":[
                       {"Name":"off;idle","Trust":"trusted","Privacy":"public"},
                       {"Name":"on;idle","Trust":"trusted","Privacy":"public"},
                       {"Name":"off;running","Trust":"trusted","Privacy":"public"},
                       {"Name":"on;running","Trust":"trusted","Privacy":"public"}],
                      "APIs":[{"Name":"powerOn","StartState":"off;","EndState":"on;","Signal":false},
                              {"Name":"startFan","StartState":";idle","EndState":";running","Signal":false}]}},
                     {"name":"Lamp","manifest":{"Name":"Lamp","Modes":["LampPower"],"InitState":"off",
                      "WorkingStates":[{"Name":"off","Trust":"trusted","Privacy":"public"},
                                       {"Name":"on","Trust":"trusted","Privacy":"public"}],
                      "APIs":[{"Name":"turnOn","StartState":"off","EndState":"on","Signal":false},
                              {"Name":"turnOff","StartState":"on","EndState":"off","Signal":false}]}},
                     {"name":"Startup Gate","manifest":{"Name":"Startup Gate","Modes":["GatePhase"],
                      "InitState":"opening","WorkingStates":[
                       {"Name":"opening","Trust":"trusted","Privacy":"public"},
                       {"Name":"waiting","Trust":"trusted","Privacy":"public"},
                       {"Name":"settled","Trust":"trusted","Privacy":"public"}],
                      "Transitions":[{"Name":"wait","EndState":"waiting",
                                       "Trigger":{"Attribute":"GatePhase","Relation":"=","Value":"opening"}},
                                     {"Name":"settle","EndState":"settled",
                                       "Trigger":{"Attribute":"GatePhase","Relation":"=","Value":"waiting"}}]}}]}
                """);
        var opening = ruleCondition("gate", "state", "state", "=", "opening");
        var primary = rule(84L, "Turn on panel power", command("panel", "powerOn"),
                ruleCondition("panel", "PowerMode", "mode", "=", "off"));
        var rules = new ArrayList<>(List.of(
                primary,
                rule(85L, "Turn on the lamp at startup", command("lamp", "turnOn"), opening),
                rule(86L, "Illuminate when the panel is powered", command("lamp", "turnOn"),
                        ruleCondition("panel", "PowerMode", "mode", "=", "on")),
                rule(87L, "Turn off the lamp when the panel is running", command("lamp", "turnOff"),
                        ruleCondition("panel", "state", "state", feedbackRelation, feedbackValue))));
        if (autonomousFan) {
            ObjectNode panelManifest = (ObjectNode) scene.withArray("templates").get(0).path("manifest");
            var transition = panelManifest.putArray("Transitions").addObject()
                    .put("Name", "autoFan").put("StartState", ";idle").put("EndState", ";running");
            transition.putObject("Trigger").put("Attribute", "PowerMode").put("Relation", "=").put("Value", "off");
        } else {
            rules.add(rule(88L, "Start the fan at startup", command("panel", "startFan"), opening));
        }

        var safety = neverSpec("startup-safety", "Startup must leave the panel off while the lamp is lit",
                specCondition("gate", "state", "state", "=", "waiting"),
                specCondition("panel", "state", "state", "=", "on;running"),
                specCondition("lamp", "state", "state", "=", "on"));
        var feedback = neverSpec("feedback-reachability", "The startup feedback turns the lamp off",
                specCondition("gate", "state", "state", "=", "settled"),
                specCondition("panel", "state", "state", "=", "on;running"),
                specCondition("lamp", "state", "state", "=", "off"));
        var operation = responseSpec("startup-response", "The panel eventually powers on",
                specCondition("gate", "state", "state", "=", "opening"),
                specCondition("panel", "mode", "PowerMode", "=", "on"));
        var specs = List.of(safety, feedback, operation);
        var devices = readDevices(scene);
        var generator = buildGenerator(mapper, scene);
        var executor = buildExecutor(nusmvPath);
        var baseline = generator.generateWithEnvironment(USER_ID, devices, List.of(), rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baseline);
        var verdicts = verdictsBySpecId(FormalCheckTestSupport.checkProperties(executor, baseline), baseline);
        assertFalse(verdicts.get(safety.getId()).isPassed(), "Both panel modes change while startup lights the lamp");
        assertFalse(verdicts.get(feedback.getId()).isPassed(), "The resulting tuple enables the lamp-off feedback");
        assertTrue(verdicts.get(operation.getId()).isPassed(), "A repair must keep the panel operation available");
        var states = new SmvTraceParser().parseCounterexampleStates(
                verdicts.get(safety.getId()).getCounterexample(), baseline.deviceSmvMap(), rules);

        FixContext ctx = FixContext.builder().traceId(316L).faultRules(List.of()).repairRuleIndices(List.of(0))
                .allRules(rules).devices(devices).environmentVariables(List.of()).specs(specs)
                .deviceSmvMap(baseline.deviceSmvMap()).violatedSpecIndex(0).userId(USER_ID)
                .attackScenario(AttackScenarioDto.none()).enablePrivacy(false).maxAttempts(12)
                .preferredRanges(Map.of()).counterexampleInitialState(states.get(0))
                .requireCounterexampleReplay(true).build();
        var outcome = new ConditionAdjustStrategy(generator, executor, new FixConfig()).tryFix(ctx);
        var retainedGuard = outcome.suggestions().stream().filter(suggestion -> suggestion.getConditionAdjustments()
                .stream().anyMatch(change -> "add".equals(change.getAction()) && "lamp".equals(change.getDeviceName())
                        && "state".equals(change.getTargetType()) && "on".equals(change.getValue()))).findFirst();
        assertTrue(retainedGuard.isPresent(), () -> "Expected the lamp-on repair, got " + outcome.suggestions());
        assertEveryListedAlternativeHolds(List.of(retainedGuard.orElseThrow()), safety.getId(),
                rules, devices, List.of(), specs, baseline.deviceSmvMap(), generator, executor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quiet-first", "loud-first", "incompatible-quiet", "autonomous-quiet", "raw-blocker",
            "expired-gate", "separate-steps", "complete-guard"})
    void repairUsesSameModeRulePriorityBeforeFollowingACommandOutcome(String scenario) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for competing repair effects");

        boolean loudFirst = "loud-first".equals(scenario);
        boolean incompatibleQuiet = "incompatible-quiet".equals(scenario);
        boolean autonomousQuiet = "autonomous-quiet".equals(scenario);
        boolean rawBlocker = "raw-blocker".equals(scenario);
        boolean expiredGate = "expired-gate".equals(scenario);
        boolean separateSteps = "separate-steps".equals(scenario);
        boolean completeGuard = "complete-guard".equals(scenario);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode scene = (ObjectNode) mapper.readTree("""
                {"devices":[{"id":"panel","templateName":"Panel","state":"off"},
                            {"id":"relay","templateName":"Relay","state":"ready"},
                            {"id":"lamp","templateName":"Lamp","state":"off"},
                            {"id":"gate","templateName":"Startup Gate","state":"opening"}],
                 "templates":[
                     {"name":"Panel","manifest":{"Name":"Panel","Modes":["PanelPower"],"InitState":"off",
                      "WorkingStates":[{"Name":"off","Trust":"trusted","Privacy":"public"},
                                       {"Name":"on","Trust":"trusted","Privacy":"public"}],
                      "APIs":[{"Name":"powerOn","StartState":"off","EndState":"on","Signal":false}]}},
                     {"name":"Relay","manifest":{"Name":"Relay","Modes":["RelayMode"],"InitState":"ready",
                      "WorkingStates":[{"Name":"ready","Trust":"trusted","Privacy":"public"},
                                       {"Name":"quiet","Trust":"trusted","Privacy":"public"},
                                       {"Name":"loud","Trust":"trusted","Privacy":"public"}],
                      "APIs":[{"Name":"selectQuiet","StartState":"ready","EndState":"quiet","Signal":false},
                              {"Name":"selectLoud","StartState":"ready","EndState":"loud","Signal":false}]}},
                     {"name":"Lamp","manifest":{"Name":"Lamp","Modes":["LampPower"],"InitState":"off",
                      "WorkingStates":[{"Name":"off","Trust":"trusted","Privacy":"public"},
                                       {"Name":"on","Trust":"trusted","Privacy":"public"}],
                      "APIs":[{"Name":"turnOn","StartState":"off","EndState":"on","Signal":false},
                              {"Name":"turnOff","StartState":"on","EndState":"off","Signal":false}]}},
                     {"name":"Startup Gate","manifest":{"Name":"Startup Gate","Modes":["GatePhase"],
                      "InitState":"opening","WorkingStates":[
                       {"Name":"opening","Trust":"trusted","Privacy":"public"},
                       {"Name":"waiting","Trust":"trusted","Privacy":"public"},
                       {"Name":"settled","Trust":"trusted","Privacy":"public"}],
                      "Transitions":[{"Name":"wait","EndState":"waiting",
                                       "Trigger":{"Attribute":"GatePhase","Relation":"=","Value":"opening"}},
                                     {"Name":"settle","EndState":"settled",
                                       "Trigger":{"Attribute":"GatePhase","Relation":"=","Value":"waiting"}}]}}]}
                """);
        var panelOn = ruleCondition("panel", "state", "state", "=", "on");
        var quiet = incompatibleQuiet
                ? rule(91L, "Select quiet before power on", command("relay", "selectQuiet"),
                        ruleCondition("panel", "state", "state", "not in", "on"),
                        ruleCondition("gate", "state", "state", "not in", "opening"))
                : rule(91L, "Select quiet", command("relay", "selectQuiet"), panelOn);
        var loud = rule(92L, "Select loud", command("relay", "selectLoud"), rawBlocker
                ? ruleCondition("panel", "state", "state", "not in", "off") : panelOn);
        var rules = new ArrayList<>(List.of(rule(90L, "Power the panel", command("panel", "powerOn"),
                ruleCondition("panel", "state", "state", "=", "off"))));
        if (expiredGate) {
            loud.getConditions().add(ruleCondition("gate", "state", "state", "=", "opening"));
        }
        ObjectNode panel = (ObjectNode) scene.withArray("templates").get(0).path("manifest");
        if (separateSteps) {
            panel.withArray("WorkingStates").addObject().put("Name", "cooled")
                    .put("Trust", "trusted").put("Privacy", "public");
            panel.withArray("APIs").addObject().put("Name", "coolDown").put("StartState", "on")
                    .put("EndState", "cooled").put("Signal", false);
            rules.add(rule(97L, "Cool the panel after it turns on", command("panel", "coolDown"), panelOn));
            loud.getConditions().add(ruleCondition("panel", "state", "state", "=", "cooled"));
        } else if (completeGuard) {
            panel.withArray("Modes").add("PanelArmed");
            panel.put("InitState", "off;dormant");
            ((ObjectNode) scene.withArray("devices").get(0)).put("state", "off;dormant");
            ((ObjectNode) panel.withArray("WorkingStates").get(0)).put("Name", "off;dormant");
            ((ObjectNode) panel.withArray("WorkingStates").get(1)).put("Name", "on;armed");
            ((ObjectNode) panel.withArray("APIs").get(0)).put("StartState", "off;dormant").put("EndState", "on;armed");
            loud.getConditions().add(ruleCondition("panel", "PanelArmed", "mode", "in", "armed"));
        }
        ObjectNode relay = (ObjectNode) scene.withArray("templates").get(1).path("manifest");
        if (rawBlocker) {
            ((ObjectNode) scene.withArray("devices").get(1)).put("state", "ready;idle");
            relay.setAll((ObjectNode) mapper.readTree("""
                    {"Modes":["RelayPower","RelayFan"],"InitState":"ready;idle",
                     "WorkingStates":[{"Name":"ready;idle","Trust":"trusted","Privacy":"public"},
                                      {"Name":"quiet;softFan","Trust":"trusted","Privacy":"public"},
                                      {"Name":"loud;boost","Trust":"trusted","Privacy":"public"},
                                      {"Name":"quiet;loudFan","Trust":"trusted","Privacy":"public"}],
                     "APIs":[{"Name":"selectQuiet","StartState":"ready;idle","EndState":"quiet;","Signal":false},
                             {"Name":"selectLoud","StartState":"ready;idle","EndState":"loud;boost","Signal":false},
                             {"Name":"runFan","StartState":"ready;idle","EndState":";loudFan","Signal":false}]}
                    """));
        }
        if (autonomousQuiet || rawBlocker) {
            relay.setAll((ObjectNode) mapper.readTree("""
                    {"InternalVariables":[{"Name":"signalLevel","IsInside":true,"FalsifiableWhenCompromised":false,
                      "Values":["coldSignal","readySignal"],"Trust":"trusted","Privacy":"public"}],
                     "Transitions":[
                       {"Name":"armRelay","Trigger":{"Attribute":"signalLevel","Relation":"=","Value":"coldSignal"},
                        "Assignments":[{"Attribute":"signalLevel","Value":"readySignal"}]},
                       {"Name":"fallBackToQuiet","StartState":"ready","EndState":"quiet",
                        "Trigger":{"Attribute":"signalLevel","Relation":"=","Value":"readySignal"}}]}
                    """));
        }
        if (rawBlocker) {
            ObjectNode fallback = (ObjectNode) relay.withArray("Transitions").get(1);
            fallback.put("StartState", "ready;idle").put("EndState", ";softFan");
            rules.addAll(List.of(quiet, loud, rule(96L, "Run the relay fan", command("relay", "runFan"), panelOn)));
        } else if (autonomousQuiet || expiredGate || separateSteps || completeGuard) {
            rules.add(loud);
        } else {
            rules.add(loudFirst ? loud : quiet);
            rules.add(loudFirst ? quiet : loud);
        }
        rules.addAll(List.of(
                rule(93L, "Light the lamp at startup", command("lamp", "turnOn"),
                        ruleCondition("gate", "state", "state", "=", "opening")),
                rule(94L, "Turn off the startup lamp", command("lamp", "turnOff"),
                        ruleCondition("gate", "state", "state", "=", "waiting")),
                rule(95L, "Light the lamp when the relay is loud", command("lamp", "turnOn"),
                        ruleCondition("relay", "state", "state", "in", rawBlocker ? "_;loudFan" : "loud"))));

        var safety = neverSpec("competing-startup-safety", "Keep the panel off during the lit startup step",
                specCondition("gate", "state", "state", "=", "waiting"),
                specCondition("panel", "state", "state", "=", "on"),
                specCondition("lamp", "state", "state", "=", "on"));
        var operation = responseSpec("competing-startup-response", "The panel eventually powers on",
                specCondition("gate", "state", "state", "=", "opening"),
                specCondition("panel", "state", "state", "=", "on"));
        boolean expectLoud = loudFirst || incompatibleQuiet || autonomousQuiet || completeGuard;
        String selectedState = expiredGate || separateSteps ? "ready" : rawBlocker ? "quiet;softFan"
                : expectLoud ? "loud" : "quiet";
        var selected = neverSpec("relay-selected", "The expected relay state is unreachable",
                specCondition("relay", "state", "state", "=", selectedState));
        var downstream = neverSpec("relay-downstream", "Only the loud route relights the lamp",
                specCondition("gate", "state", "state", "=", "settled"),
                specCondition("lamp", "state", "state", "=", "on"));
        var specs = List.of(safety, operation, selected, downstream);
        var devices = readDevices(scene);
        var generator = buildGenerator(mapper, scene);
        var executor = buildExecutor(nusmvPath);
        if (autonomousQuiet) {
            var fallback = temporalSpec("relay-autonomous", "4", "The ready relay falls back to quiet at startup",
                    List.of(specCondition("relay", "state", "state", "=", "ready"),
                            specCondition("gate", "state", "state", "=", "waiting")),
                    List.of(specCondition("relay", "state", "state", "=", "quiet")));
            var rulesWithoutLoud = rules.stream().filter(rule -> rule != loud).toList();
            var fallbackModel = generator.generateWithEnvironment(USER_ID, devices, List.of(), rulesWithoutLoud,
                    List.of(fallback), AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
            assertCompleteModel(fallbackModel);
            assertTrue(verdictsBySpecId(FormalCheckTestSupport.checkProperties(executor, fallbackModel), fallbackModel)
                    .get(fallback.getId()).isPassed(), "The autonomous write is active at the command's step");
        }
        if (rawBlocker) {
            var fanControl = neverSpec("relay-fan-without-blocker", "The fan command's result is unreachable",
                    specCondition("relay", "state", "state", "=", "quiet;loudFan"));
            var rulesWithoutLoud = rules.stream().filter(rule -> rule != loud).toList();
            var controlModel = generator.generateWithEnvironment(USER_ID, devices, List.of(), rulesWithoutLoud,
                    List.of(fanControl), AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
            assertCompleteModel(controlModel);
            assertFalse(verdictsBySpecId(FormalCheckTestSupport.checkProperties(executor, controlModel), controlModel)
                    .get(fanControl.getId()).isPassed(), "Without the raw blocker, the disjoint fan command runs");
        }
        var baseline = generator.generateWithEnvironment(USER_ID, devices, List.of(), rules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertCompleteModel(baseline);
        var verdicts = verdictsBySpecId(FormalCheckTestSupport.checkProperties(executor, baseline), baseline);
        assertFalse(verdicts.get(selected.getId()).isPassed(), "The expected relay state is reachable");
        assertEquals(!expectLoud, verdicts.get(downstream.getId()).isPassed(), "Only the selected loud command relights the lamp");
        assertFalse(verdicts.get(safety.getId()).isPassed());
        assertTrue(verdicts.get(operation.getId()).isPassed());
        var states = new SmvTraceParser().parseCounterexampleStates(
                verdicts.get(safety.getId()).getCounterexample(), baseline.deviceSmvMap(), rules);

        FixContext ctx = FixContext.builder().traceId(317L).faultRules(List.of()).repairRuleIndices(List.of(0))
                .allRules(rules).devices(devices).environmentVariables(List.of()).specs(specs)
                .deviceSmvMap(baseline.deviceSmvMap()).violatedSpecIndex(0).userId(USER_ID)
                .attackScenario(AttackScenarioDto.none()).enablePrivacy(false).maxAttempts(12)
                .preferredRanges(Map.of()).counterexampleInitialState(states.get(0))
                .requireCounterexampleReplay(true).build();
        var outcome = new ConditionAdjustStrategy(generator, executor, new FixConfig()).tryFix(ctx);
        var independentStartupGuard = outcome.suggestions().stream().filter(suggestion ->
                suggestion.getConditionAdjustments().stream().anyMatch(change ->
                        "add".equals(change.getAction()) && "lamp".equals(change.getDeviceName())
                                && "state".equals(change.getTargetType()) && "on".equals(change.getValue())))
                .findFirst();
        assertEquals(!expectLoud, independentStartupGuard.isPresent(),
                () -> "Lamp guard eligibility must follow selected relay priority, got " + outcome.suggestions());
        if (!expectLoud) {
            assertEveryListedAlternativeHolds(List.of(independentStartupGuard.orElseThrow()), safety.getId(),
                    rules, devices, List.of(), specs, baseline.deviceSmvMap(), generator, executor);
        }
        if (expiredGate) {
            Map<String, DeviceManifest> manifests = new java.util.LinkedHashMap<>();
            for (JsonNode template : scene.withArray("templates")) {
                manifests.put(template.path("name").asText(),
                        mapper.treeToValue(template.path("manifest"), DeviceManifest.class));
            }
            Map<String, String> persistenceDeviceRefs = baseline.deviceSmvMap().keySet().stream()
                    .collect(java.util.stream.Collectors.toMap(ref -> ref, ref -> ref));
            var repaired = FixStrategyApplier.apply("condition", independentStartupGuard.orElseThrow(),
                    rules, persistenceDeviceRefs, Map.of());
            var config = new FuzzEngineConfig(List.of(), 1, 4, 1, 1L);
            for (List<RuleDto> candidate : List.of(rules, repaired)) {
                var snapshot = new ModelInputSnapshot(List.of(), devices, List.of(), candidate, List.of(safety), manifests);
                var finite = new FuzzEngine().run(new FuzzEngineInput(snapshot, config), null, () -> false);
                assertEquals(candidate == rules ? List.of(1) : List.of(),
                        finite.findings().stream().map(FuzzFinding::firstViolationStep).toList());
            }
        }
    }

    @Test
    void environmentOnlyViolation_isNotMisreportedAsRuleRepair() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the no-fault-rule regression");

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
                strategies, 20, Map.of(), () -> { });

        assertFalse(fixResult.isFixable());
        assertTrue(fixResult.getFaultRules().isEmpty());
        assertTrue(fixResult.getSuggestions().isEmpty());
        strategies.forEach(strategy ->
                assertEquals("SKIPPED_NO_FAULT_RULES", attemptStatus(fixResult, strategy)));
    }

    @Test
    void cumulativeClimateRepairs_preserveEarlierRepairsAcrossSharedEnvironmentChanges() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the cumulative shared-environment regression");
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
                    List.of(strategies.get(round)), 20, Map.of(), () -> { });
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
            rules = FixStrategyApplier.apply(chosen.getStrategy(), chosen, rules, refs, Map.of());
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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for feedback-loop repair");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", "relay-cycle-repair-scene.json")));
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = importRules(scene);
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
                model.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false, List.of("condition"), 20, Map.of(), () -> { });
        assertVerifiedAttempt(fixes, "condition");
        assertTrue(fixes.getSuggestions().stream().allMatch(suggestion ->
                suggestion.getRemovedRuleDescriptions().isEmpty() && suggestion.getPreexistingViolations().isEmpty()));
        assertEveryListedAlternativeHolds(fixes.getSuggestions(), specs.get(0).getId(), rules, devices,
                environment, specs, model.deviceSmvMap(), generator, executor);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void cameraChains_listAffordableRepairsWithoutBreakingExistingResponses(boolean forked) throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for complete repair enumeration");
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
        List<RuleDto> rules = importRules(scene);
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
                model.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), false, List.of("remove"), 2, Map.of(), () -> { });
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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for contradictory policy checks");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", "emergency-policy-conflict-scene.json")));
        SmvGenerator generator = buildGenerator(mapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = importRules(scene);
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
                USER_ID, AttackScenarioDto.none(), false, List.of("parameter", "condition", "remove"), 20, Map.of(), () -> { });
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
    void salusVentilationThreshold_repairsAnIdleRuleBeforeTheEnvironmentCrossesTheLimit() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the Salus section 5.1 regression");
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(
                Path.of("..", "docs", "examples", "salus-ventilation-repair-scene.json")));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = importRules(scene);
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
                USER_ID, AttackScenarioDto.none(), false, List.of("parameter"), 20, Map.of(), () -> { });

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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the upper-domain fix regression");

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
                strategies, 20, Map.of(), () -> { });

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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the strict-boundary fix regression");

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
                List.of("parameter"), 20, Map.of(), () -> { });

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
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the fire condition-fix regression");

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
                List.of("condition"), 20, Map.of(), () -> { });

        FixSuggestionDto suggestion = listedRepair(fixResult, "condition",
                "the over-constrained door rule losing one condition",
                candidate -> candidate.getConditionAdjustments().stream()
                        .anyMatch(adjustment -> adjustment.getRuleIndex() == 1
                                && "remove".equals(adjustment.getAction())));

        List<RuleDto> repairedRules = FixStrategyApplier.apply(
                "condition", suggestion, rules, Map.of(), Map.of());
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, repairedRules, specs,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        NusmvExecutor.NusmvResult repaired = FormalCheckTestSupport.checkProperties(executor, repairedModel);
        assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
        assertEquals(0, violationCount(repaired),
                "The condition suggestion must repair the complete evacuation response chain");
    }

    @Test
    void budgetOneAttack_rejectsRemovalThatDisablesTheCameraResponse() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required for the attacked repair regression");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(SCENE_PATH));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = importRules(scene);
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
                List.of("remove"), 10, Map.of(), () -> { });

        assertTriggerPreservationRejected(fixResult);
        // The same-budget candidate passes its formulas by disabling the only photo source.
        // That removes the camera response's trigger, so it must not be offered as a repair.
        SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules.subList(1, rules.size()), specs,
                AttackScenarioDto.anyUpToBudget(1), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertAllPropertiesPassWithUnreachableResponse(executor, repairedModel, 1);
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
                    suggestion.getStrategy(), suggestion, originalRules, persistenceDeviceRefs, Map.of());
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

    @Test
    void composedModeLabelsThatChangeFormalVerdictsInvalidateRepairEvidence() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check two-mode labels");
        var mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"fan_1","templateName":"Room Fan","state":"off;high"}],
                 "templates":[{"name":"Room Fan","manifest":{
                    "Name":"Room Fan","Modes":["Power","Speed"],"InitState":"off;low",
                    "WorkingStates":[{"Name":"off;low","Trust":"trusted","Privacy":"public"},
                                     {"Name":"on;high","Trust":"untrusted","Privacy":"private"}],
                    "APIs":[{"Name":"boost","StartState":"off;low","EndState":"on;high","Signal":false}]
                  }}]}
                """);
        var generator = buildGenerator(mapper, scene);
        var original = readDevices(scene);
        var explicit = readDevices(scene);
        explicit.get(0).setCurrentStateTrust("trusted");
        explicit.get(0).setCurrentStatePrivacy("public");
        var trust = specCondition("fan_1", "trust", "Speed", "=", "trusted");
        trust.setPropertyScope("state");
        var privacy = specCondition("fan_1", "privacy", "Speed", "=", "public");
        privacy.setPropertyScope("state");
        var specs = List.of(neverSpec("trust", "High speed stays untrusted", trust),
                neverSpec("privacy", "High speed stays private", privacy));
        var originalModel = generator.generateWithEnvironment(USER_ID, original, List.of(), List.of(), specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        var editedModel = generator.generateWithEnvironment(USER_ID, explicit, List.of(), List.of(), specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertEquals(List.of(true, true), checkVerdicts(nusmvPath, originalModel, specs));
        assertEquals(List.of(false, false), checkVerdicts(nusmvPath, editedModel, specs));
        assertNotEquals(BoardSemanticFingerprint.of(original, specs, originalModel.deviceSmvMap()),
                BoardSemanticFingerprint.of(explicit, specs, editedModel.deviceSmvMap()));
    }

    @Test
    void quotedAuthoredStateDiffersFromNumericLookingStateInRepairEvidence() throws Exception {
        String nusmvPath = assumeAvailableNusmvPath("NuSMV executable is required to check distinct authored states");
        var mapper = new ObjectMapper();
        JsonNode scene = mapper.readTree("""
                {"devices":[{"id":"dial_1","templateName":"Room Dial","state":"30"}],
                 "templates":[{"name":"Room Dial","manifest":{
                    "Name":"Room Dial","Modes":["Setting"],"InitState":"30",
                    "WorkingStates":[{"Name":"30","Trust":"trusted","Privacy":"public"},
                                     {"Name":"second","Trust":"trusted","Privacy":"public"}]
                  }}]}
                """);
        String quotedState = "\"30\"";
        ((ObjectNode) scene.path("templates").get(0).path("manifest").path("WorkingStates").get(1))
                .put("Name", quotedState);
        var generator = buildGenerator(mapper, scene);
        var devices = readDevices(scene);
        var original = List.of(neverSpec("setting", "Keep the selected setting out",
                specCondition("dial_1", "state", "state", "=", "30")));
        var edited = List.of(neverSpec("setting", "Keep the selected setting out",
                specCondition("dial_1", "state", "state", "=", quotedState)));
        var originalModel = generator.generateWithEnvironment(USER_ID, devices, List.of(), List.of(), original,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        var editedModel = generator.generateWithEnvironment(USER_ID, devices, List.of(), List.of(), edited,
                AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertEquals(List.of(false), checkVerdicts(nusmvPath, originalModel, original));
        assertEquals(List.of(true), checkVerdicts(nusmvPath, editedModel, edited));
        assertNotEquals(BoardSemanticFingerprint.of(devices, original, originalModel.deviceSmvMap()),
                BoardSemanticFingerprint.of(devices, edited, editedModel.deviceSmvMap()));
    }

    private List<Boolean> checkVerdicts(String nusmvPath, SmvGenerator.GenerateResult model,
                                         List<SpecificationDto> specs) throws Exception {
        var checked = FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), model);
        assertTrue(checked.isSuccess(), checked::getErrorMessage);
        var verdicts = verdictsBySpecId(checked, model);
        assertEquals(specs.stream().map(SpecificationDto::getId).collect(java.util.stream.Collectors.toSet()),
                verdicts.keySet());
        return specs.stream().map(spec -> verdicts.get(spec.getId()).isPassed()).toList();
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

    private boolean hasDeviceState(List<TraceStateDto> states, String deviceId, String expectedState) {
        return states.stream()
                .flatMap(state -> state.getDevices().stream())
                .anyMatch(device -> deviceId.equals(device.getDeviceId())
                        && expectedState.equals(device.getState()));
    }
}
