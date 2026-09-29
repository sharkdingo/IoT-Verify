package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvRuleCommentWriter;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvSpecificationBuilder;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueReasonCode;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SmvGenerator 五项修复的单元测试。
 * 纯 POJO 构造，不依赖 Spring 上下文。
 */
class SmvGeneratorFixesTest {

    private SmvModelValidator validator;
    private SmvMainModuleBuilder mainBuilder;
    private SmvDeviceModuleBuilder deviceBuilder;
    private SmvSpecificationBuilder specBuilder;

    @BeforeEach
    void setUp() {
        validator = new SmvModelValidator();
        mainBuilder = new SmvMainModuleBuilder();
        deviceBuilder = new SmvDeviceModuleBuilder();
        specBuilder = new SmvSpecificationBuilder();
    }

    // ======================== helpers ========================

    private DeviceManifest.InternalVariable numericVar(String name, boolean isInside, int lo, int hi) {
        return DeviceManifest.InternalVariable.builder()
                .name(name)
                .isInside(isInside)
                .falsifiableWhenCompromised(!isInside)
                .lowerBound(lo)
                .upperBound(hi)
                .naturalChangeRate(isInside ? null : "0")
                .build();
    }

    private DeviceVerificationDto device(String varName, String templateName) {
        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName(varName);
        dto.setTemplateName(templateName);
        return dto;
    }

    /** 构建一个最小的 DeviceSmvData（单 mode 或多 mode） */
    private DeviceSmvData buildSmvData(String varName, String templateName,
                                       List<String> modes,
                                       Map<String, List<String>> modeStates,
                                       List<DeviceManifest.InternalVariable> vars,
                                       DeviceManifest manifest) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        smv.setTemplateName(templateName);
        smv.setModuleName(templateName + "_" + varName);
        smv.getModes().addAll(modes);
        smv.getModeStates().putAll(modeStates);
        smv.getVariables().addAll(vars);
        smv.setManifest(manifest);
        smv.setSensor(manifest.getApis() == null || manifest.getApis().isEmpty());
        if (manifest.getImpactedVariables() != null) {
            for (String impacted : manifest.getImpactedVariables()) {
                DeviceManifest.InternalVariable definition = vars.stream()
                        .filter(var -> impacted.equals(var.getName()))
                        .findFirst()
                        .orElseGet(() -> numericVar(impacted, false, 0, 100));
                smv.getImpactedEnvironmentVariables().put(impacted, definition);
            }
        }
        return smv;
    }

    @Test
    @DisplayName("Builder input: main module builder null devices throws categorized exception")
    void mainBuilder_nullDevices_throwsCategorizedException() {
        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.build(1L, null, List.of(), Map.of(), AttackScenarioDto.none(), false));
        assertEquals("INVALID_BUILDER_INPUT", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains("SmvMainModuleBuilder"));
        assertTrue(ex.getMessage().contains("devices"));
    }

    @Test
    @DisplayName("Builder input: device module builder null data throws categorized exception")
    void deviceBuilder_nullSmv_throwsCategorizedException() {
        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> deviceBuilder.build(null, false, false));
        assertEquals("INVALID_BUILDER_INPUT", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains("SmvDeviceModuleBuilder"));
        assertTrue(ex.getMessage().contains("smv"));
    }

    @Test
    @DisplayName("Main namespace: device varName cannot collide with generated environment identifier")
    void mainBuilder_deviceVarNameCollidesWithGeneratedEnvironmentName_throwsCategorizedException() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 0, 50);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of())
                .internalVariables(List.of(temperature))
                .workingStates(List.of())
                .build();
        DeviceSmvData smv = buildSmvData("a_temperature", "Sensor", List.of(), Map.of(),
                List.of(temperature), manifest);
        smv.getEnvVariables().put("temperature", temperature);

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.build(1L,
                        List.of(device("a_temperature", "Sensor")),
                        List.of(),
                        Map.of("a_temperature", smv),
                        AttackScenarioDto.none(), false));

        assertEquals("INVALID_BUILDER_INPUT", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains("main namespace"));
        assertTrue(ex.getMessage().contains("a_temperature"));
    }

    @Test
    @DisplayName("Main namespace: internal attacked-node counter cannot collide with device varName")
    void mainBuilder_attackCounterCollidesWithDeviceVarName_throwsCategorizedException() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of())
                .workingStates(List.of())
                .build();
        String internalCounter = SmvConstants.NUSMV_COMPROMISED_POINT_COUNT;
        DeviceSmvData smv = buildSmvData(internalCounter, "Meter", List.of(), Map.of(),
                List.of(), manifest);

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.build(1L,
                        List.of(device(internalCounter, "Meter")),
                        List.of(),
                        Map.of(internalCounter, smv),
                        AttackScenarioDto.anyUpToBudget(3), false));

        assertEquals("INVALID_BUILDER_INPUT", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains(internalCounter));
    }

    @Test
    @DisplayName("Main namespace: fix lambda cannot collide with device varName")
    void mainBuilder_fixLambdaCollidesWithDeviceVarName_throwsCategorizedException() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of())
                .workingStates(List.of())
                .build();
        DeviceSmvData smv = buildSmvData("lambda_r0_c0", "Meter", List.of(), Map.of(),
                List.of(), manifest);
        ParameterizationConfig config = ParameterizationConfig.builder()
                .conditionLambdas(Map.of("r0_c0", "lambda_r0_c0"))
                .build();

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.buildParameterized(1L,
                        List.of(device("lambda_r0_c0", "Meter")),
                        null,
                        List.of(),
                        Map.of("lambda_r0_c0", smv),
                        AttackScenarioDto.none(), false,
                        config,
                        SmvGenerationContext.noop()));

        assertEquals("INVALID_BUILDER_INPUT", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains("lambda_r0_c0"));
    }

    @Test
    @DisplayName("Parameterized main module emits every pinned initial-state constraint as INIT")
    void mainBuilder_parameterizedInitialState_emitsInitConstraints() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Power"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("light_1", "Light",
                List.of("Power"), Map.of("Power", List.of("off", "on")),
                List.of(), manifest);
        ParameterizationConfig config = ParameterizationConfig.builder()
                .conditionLambdas(Map.of("r0_c0", "lambda_r0_c0"))
                .initialStateConstraints(List.of(
                        "light_1.Power = on",
                        "light_1.trust_Power_on = trusted"))
                .build();

        String model = mainBuilder.buildParameterized(
                1L,
                List.of(device("light_1", "Light")),
                List.of(),
                Map.of("light_1", smv),
                AttackScenarioDto.none(), false,
                config);

        assertTrue(model.contains("\nINIT light_1.Power = on;"), model);
        assertTrue(model.contains("\nINIT light_1.trust_Power_on = trusted;"), model);
    }

    @Test
    @DisplayName("Trace probes record the ordered rule branch that drives the next state")
    void mainBuilder_ruleExecutionProbes_areDeterministicAndRespectCasePriority() {
        DeviceManifest sensorManifest = DeviceManifest.builder()
                .modes(List.of("ButtonMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("idle").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("pressed").trust("trusted").build()))
                .build();
        DeviceManifest lightManifest = DeviceManifest.builder()
                .modes(List.of("Power"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("turnOn").startState("off").endState("on").build()))
                .build();

        DeviceSmvData sensor = buildSmvData("button_1", "Button",
                List.of("ButtonMode"), Map.of("ButtonMode", List.of("idle", "pressed")),
                List.of(), sensorManifest);
        DeviceSmvData light = buildSmvData("light_1", "Light",
                List.of("Power"), Map.of("Power", List.of("off", "on")),
                List.of(), lightManifest);
        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("button_1", sensor);
        deviceMap.put("light_1", light);

        RuleDto.Condition pressed = RuleDto.Condition.builder()
                .deviceName("button_1").attribute("state").targetType("state")
                .relation("=").value("pressed").build();
        RuleDto first = RuleDto.builder().id(1L).conditions(List.of(pressed))
                .command(RuleDto.Command.builder().deviceName("light_1").action("turnOn").build())
                .ruleString("First light rule").build();
        RuleDto second = RuleDto.builder().id(2L).conditions(List.of(pressed))
                .command(RuleDto.Command.builder().deviceName("light_1").action("turnOn").build())
                .ruleString("Second light rule").build();

        String model = mainBuilder.build(1L,
                List.of(device("button_1", "Button"), device("light_1", "Light")),
                List.of(first, second), deviceMap, AttackScenarioDto.none(), false);

        assertTrue(model.contains("iot_verify_rule_fired_0: boolean;"));
        assertTrue(model.contains("init(iot_verify_rule_fired_0) := FALSE;"));
        assertTrue(model.contains("next(iot_verify_rule_fired_0) := ("));
        String secondProbe = model.lines()
                .filter(line -> line.contains("next(iot_verify_rule_fired_1)"))
                .findFirst().orElseThrow();
        assertTrue(secondProbe.contains(" & !("),
                "A later case branch must not be reported as fired when an earlier branch wins");
    }

    // ======================== P1: Trigger.Attribute 合法性 ========================

    @Test
    @DisplayName("P1: legal trigger attribute passes validation")
    void triggerAttribute_legal_passes() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(numericVar("temperature", false, 0, 50)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("t1").startState("on").endState("off")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("temperature").relation("=").value("50").build())
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("clock_1", "Clock",
                List.of("Mode"), Map.of("Mode", List.of("on", "off")),
                List.of(numericVar("temperature", false, 0, 50)), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("clock_1", smv);
        assertDoesNotThrow(() -> validator.validate(map));
    }

    @Test
    @DisplayName("P1: illegal trigger attribute throws SmvGenerationException")
    void triggerAttribute_illegal_throws() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(numericVar("temperature", false, 0, 50)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("t1").startState("on").endState("on")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("nonexistent").relation("=").value("1").build())
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "Dev",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(numericVar("temperature", false, 0, 50)), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);
        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> validator.validate(map));
        assertTrue(ex.getMessage().contains("nonexistent"));
        assertTrue(ex.getMessage().contains("t1"));
    }

    /**
     * A Transition Trigger must not read a value the device only writes.
     *
     * <p>An affect-only shared declaration ({@code IsInside: false, Reads: false}) gets no
     * {@code device.<name> := a_<name>} mirror, yet `appendInternalVariables` still declares the
     * identifier — so a Trigger naming one used to compile into a comparison against a variable that is
     * declared, never initialised and never assigned. NuSMV treats that as an unconstrained free state
     * variable that re-picks a value from its domain every step, unrelated to the shared value it appears
     * to name, and answers the model without complaint. Measured before the fix on a `0..100` lamp:
     * {@code EF (lamp_1.illuminance >= 80 & a_illuminance <= 21)} was **true** — the guard fired on noise
     * while the real reading sat at 20.
     *
     * <p>Both halves are asserted together so a future edit cannot split them: the affect-only name is
     * refused, and a read-capable shared name on the same manifest still works. Without the second half,
     * deleting the whole variable branch of `buildLegalAttributeSet` would leave this test green.
     */
    @Test
    @DisplayName("P1: a Trigger may not read an affect-only shared value, but may read a readable one")
    void triggerAttribute_affectOnlySharedValue_throwsWhileReadableIsAccepted() {
        DeviceManifest.InternalVariable affectOnly = numericVar("illuminance", false, 0, 100);
        affectOnly.setReads(false);
        DeviceManifest.InternalVariable readable = numericVar("brightness", false, 0, 100);
        readable.setReads(true);

        DeviceManifest rejected = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(affectOnly, readable))
                .impactedVariables(List.of("illuminance"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build()))
                // A real state change, so the only thing that can reject this manifest is the trigger
                // attribute itself. With `startState == endState` the test passed only because the
                // trigger check happens to run first, and reverting the fix then failed it on the
                // *wrong* assertion — a test that depends on validation order tests the order.
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("autoOff").startState("on").endState("off")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("illuminance").relation(">=").value("80").build())
                        .build()))
                .build();
        Map<String, DeviceSmvData> rejectedMap = new LinkedHashMap<>();
        rejectedMap.put("lamp_1", buildSmvData("lamp_1", "Lamp",
                List.of("Mode"), Map.of("Mode", List.of("on", "off")),
                List.of(affectOnly, readable), rejected));

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> validator.validate(rejectedMap));
        assertTrue(ex.getMessage().contains("illuminance"),
                () -> "the message must name the offending attribute: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("autoOff"),
                () -> "the message must name the Transition: " + ex.getMessage());

        // Same shape, read-capable attribute: still legal, so the narrowing is about capability and not
        // about shared-ness.
        DeviceManifest accepted = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(affectOnly, readable))
                .impactedVariables(List.of("illuminance"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("autoOff").startState("on").endState("off")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("brightness").relation(">=").value("80").build())
                        .build()))
                .build();
        Map<String, DeviceSmvData> acceptedMap = new LinkedHashMap<>();
        acceptedMap.put("lamp_1", buildSmvData("lamp_1", "Lamp",
                List.of("Mode"), Map.of("Mode", List.of("on", "off")),
                List.of(affectOnly, readable), accepted));

        assertDoesNotThrow(() -> validator.validate(acceptedMap),
                "a read-capable shared value stays a legal trigger attribute");
    }

    @Test
    @DisplayName("P1: illegal trigger relation throws SmvGenerationException")
    void triggerRelation_illegal_throws() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(numericVar("temperature", false, 0, 50)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("t1").startState("on").endState("on")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("temperature").relation("CONTAINS").value("1").build())
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "Dev",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(numericVar("temperature", false, 0, 50)), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);
        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> validator.validate(map));
        assertEquals("ILLEGAL_TRIGGER_RELATION", ex.getErrorCategory());
    }

    // ======================== P2: StartState/EndState 格式 ========================

    @Test
    @DisplayName("P2: multi-mode API EndState wrong segment count throws")
    void multiModeEndState_wrongSegments_throws() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "State"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("auto;cool").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("setAuto").startState("auto;cool")
                        // 只有 1 段，但设备有 2 个 mode → 应报错
                        .endState("auto")
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("thermo_1", "Thermostat",
                List.of("Mode", "State"),
                Map.of("Mode", List.of("auto"), "State", List.of("cool")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("thermo_1", smv);
        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> validator.validate(map));
        assertTrue(ex.getMessage().contains("1 segments"));
        assertTrue(ex.getMessage().contains("expected 2"));
    }

    // ======================== P3: env var 冲突检测 ========================

    @Test
    @DisplayName("P3: same-name env var with different range throws")
    void envVarConflict_differentRange_throws() {
        DeviceManifest.InternalVariable timeA = numericVar("time", false, 0, 23);
        DeviceManifest.InternalVariable timeB = numericVar("time", false, 0, 47);

        DeviceManifest mA = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(timeA))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceManifest mB = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(timeB))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();

        DeviceSmvData smvA = buildSmvData("clock_1", "Clock",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(timeA), mA);
        smvA.getEnvVariables().put("time", timeA);

        DeviceSmvData smvB = buildSmvData("sensor_1", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(timeB), mB);
        smvB.getEnvVariables().put("time", timeB);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("clock_1", smvA);
        map.put("sensor_1", smvB);

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> validator.validate(map));
        assertTrue(ex.getMessage().contains("range mismatch"));
        assertTrue(ex.getMessage().contains("time"));
    }

    /**
     * Two devices assigning different values to one discrete shared value is a contradiction, not a
     * merge: enums have no additive composition, so the generator's sequential case branches resolved
     * it by device iteration order. NuSMV reported AX (airQuality = good) true under one order and
     * false under the other, for the same scene -- a verdict no user can see or act on.
     */
    @Test
    @DisplayName("Conflicting discrete writers are rejected instead of resolved by device order")
    void discreteWriterConflict_differentValues_throws() {
        Map<String, DeviceSmvData> map = discreteWriterScene("good", "poor");

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> validator.validate(map));
        assertTrue(ex.getMessage().contains("airQuality"), ex.getMessage());
        assertTrue(ex.getMessage().contains("purifier_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("smoker_1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("good"), ex.getMessage());
        assertTrue(ex.getMessage().contains("poor"), ex.getMessage());
    }

    @Test
    @DisplayName("Discrete writers that agree are order-independent and allowed")
    void discreteWriterConflict_sameValue_passes() {
        validator.validate(discreteWriterScene("good", "good"));
    }

    private Map<String, DeviceSmvData> discreteWriterScene(String firstValue, String secondValue) {
        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        String[] devices = {"purifier_1", "smoker_1"};
        String[] values = {firstValue, secondValue};
        for (int index = 0; index < devices.length; index++) {
            DeviceManifest.InternalVariable domain = DeviceManifest.InternalVariable.builder()
                    .name("airQuality").isInside(false).reads(false)
                    .falsifiableWhenCompromised(false)
                    .trust("trusted").privacy("public")
                    .values(List.of("poor", "good"))
                    .build();
            DeviceManifest manifest = DeviceManifest.builder()
                    .modes(List.of("Mode"))
                    .internalVariables(List.of(domain))
                    .impactedVariables(List.of("airQuality"))
                    .workingStates(List.of(DeviceManifest.WorkingState.builder()
                            .name("on").trust("trusted")
                            .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                    .variableName("airQuality").value(values[index]).build()))
                            .build()))
                    .build();
            DeviceSmvData smv = buildSmvData(devices[index], "Dev" + index,
                    List.of("Mode"), Map.of("Mode", List.of("on")), List.of(domain), manifest);
            smv.getImpactedVariables().add("airQuality");
            smv.getImpactedEnvironmentVariables().put("airQuality", domain);
            map.put(devices[index], smv);
        }
        return map;
    }

    @Test
    @DisplayName("P3: same-name env var with same range passes")
    void envVarConflict_sameRange_passes() {
        DeviceManifest.InternalVariable timeA = numericVar("time", false, 0, 23);
        DeviceManifest.InternalVariable timeB = numericVar("time", false, 0, 23);

        DeviceManifest mA = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(timeA))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceManifest mB = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(timeB))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();

        DeviceSmvData smvA = buildSmvData("clock_1", "Clock",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(timeA), mA);
        smvA.getEnvVariables().put("time", timeA);

        DeviceSmvData smvB = buildSmvData("sensor_1", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(timeB), mB);
        smvB.getEnvVariables().put("time", timeB);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("clock_1", smvA);
        map.put("sensor_1", smvB);

        assertDoesNotThrow(() -> validator.validate(map));
    }

    @Test
    @DisplayName("P3: shared numeric environment without an explicit natural rate is rejected")
    void sharedNumericEnvironment_withoutNaturalRate_throws() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 0, 100);
        temperature.setNaturalChangeRate(null);
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(List.of(temperature))
                .build();
        DeviceSmvData device = buildSmvData(
                "sensor_1", "Sensor", List.of(), Map.of(), List.of(temperature), manifest);
        device.getEnvVariables().put("temperature", temperature);

        SmvGenerationException exception = assertThrows(SmvGenerationException.class, () ->
                validator.validate(Map.of("sensor_1", device)));

        assertTrue(exception.getMessage().contains("must explicitly define NaturalChangeRate"),
                exception.getMessage());
    }

    @Test
    @DisplayName("P3: every formal-model entry point rejects rate syntax outside the schema grammar")
    void naturalChangeRate_nonCanonicalSyntax_isNeverReinterpretedByFormalModel() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 0, 100);
        temperature.setNaturalChangeRate("1,2");
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(List.of(temperature))
                .build();
        DeviceSmvData smv = buildSmvData(
                "sensor_1", "Sensor", List.of(), Map.of(), List.of(temperature), manifest);
        smv.getEnvVariables().put("temperature", temperature);
        Map<String, DeviceSmvData> map = Map.of("sensor_1", smv);

        SmvGenerationException validationException = assertThrows(SmvGenerationException.class,
                () -> validator.validate(map));
        assertTrue(validationException.getMessage().contains("invalid NaturalChangeRate '1,2'"),
                validationException.getMessage());

        temperature.setNaturalChangeRate("[1,2,999]");
        DeviceVerificationDto dto = device("sensor_1", "Sensor");
        SmvGenerationException generationException = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.build(
                        1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false));
        assertTrue(generationException.getMessage().contains("NaturalChangeRate '[1,2,999]'"),
                generationException.getMessage());
    }

    @Test
    @DisplayName("P3: same-name env var with different natural change rate throws")
    void envVarConflict_differentNaturalChangeRate_throws() {
        DeviceManifest.InternalVariable first = numericVar("temperature", false, 0, 100);
        first.setNaturalChangeRate("[-1,1]");
        DeviceManifest.InternalVariable second = numericVar("temperature", false, 0, 100);
        second.setNaturalChangeRate("[0,1]");

        DeviceSmvData firstDevice = buildSmvData("sensor_1", "Sensor A", List.of(), Map.of(), List.of(first),
                DeviceManifest.builder().internalVariables(List.of(first)).build());
        firstDevice.getEnvVariables().put("temperature", first);
        DeviceSmvData secondDevice = buildSmvData("sensor_2", "Sensor B", List.of(), Map.of(), List.of(second),
                DeviceManifest.builder().internalVariables(List.of(second)).build());
        secondDevice.getEnvVariables().put("temperature", second);

        SmvGenerationException exception = assertThrows(SmvGenerationException.class, () ->
                validator.validate(Map.of("sensor_1", firstDevice, "sensor_2", secondDevice)));

        assertTrue(exception.getMessage().contains("natural-change-rate mismatch"), exception.getMessage());
    }

    @Test
    @DisplayName("P3: same-name env var with different default trust throws")
    void envVarConflict_differentDefaultTrust_throws() {
        DeviceManifest.InternalVariable trusted = numericVar("temperature", false, 0, 100);
        trusted.setTrust("trusted");
        DeviceManifest.InternalVariable untrusted = numericVar("temperature", false, 0, 100);
        untrusted.setTrust("untrusted");

        DeviceSmvData firstDevice = buildSmvData("sensor_1", "Sensor A", List.of(), Map.of(), List.of(trusted),
                DeviceManifest.builder().internalVariables(List.of(trusted)).build());
        firstDevice.getEnvVariables().put("temperature", trusted);
        DeviceSmvData secondDevice = buildSmvData("sensor_2", "Sensor B", List.of(), Map.of(), List.of(untrusted),
                DeviceManifest.builder().internalVariables(List.of(untrusted)).build());
        secondDevice.getEnvVariables().put("temperature", untrusted);

        SmvGenerationException exception = assertThrows(SmvGenerationException.class, () ->
                validator.validate(Map.of("sensor_1", firstDevice, "sensor_2", secondDevice)));

        assertTrue(exception.getMessage().contains("default trust mismatch"), exception.getMessage());
    }

    @Test
    @DisplayName("P3: equivalent natural change rate syntax remains compatible")
    void envVarConflict_equivalentNaturalChangeRate_passes() {
        DeviceManifest.InternalVariable first = numericVar("temperature", false, 0, 100);
        first.setNaturalChangeRate("1");
        DeviceManifest.InternalVariable second = numericVar("temperature", false, 0, 100);
        second.setNaturalChangeRate("[0,1]");

        DeviceSmvData firstDevice = buildSmvData("sensor_1", "Sensor A", List.of(), Map.of(), List.of(first),
                DeviceManifest.builder().internalVariables(List.of(first)).build());
        firstDevice.getEnvVariables().put("temperature", first);
        DeviceSmvData secondDevice = buildSmvData("sensor_2", "Sensor B", List.of(), Map.of(), List.of(second),
                DeviceManifest.builder().internalVariables(List.of(second)).build());
        secondDevice.getEnvVariables().put("temperature", second);

        assertDoesNotThrow(() -> validator.validate(Map.of("sensor_1", firstDevice, "sensor_2", secondDevice)));
    }

    // ======================== P4: env transition 引用已声明标识符 ========================

    @Test
    @DisplayName("P4: env transition condition uses the declared device mirror clock_1.time")
    void envTransition_usesDeclaredMirror() {
        DeviceManifest.InternalVariable timeVar = numericVar("time", false, 0, 23);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(timeVar))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("running").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("reset")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("time").relation("=").value("23").build())
                        .assignments(List.of(DeviceManifest.Assignment.builder()
                                .attribute("time").value("0").build()))
                        .build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("start").startState("running").endState("running").build()))
                .build();

        DeviceSmvData smv = buildSmvData("clock_1", "Clock",
                List.of("Mode"), Map.of("Mode", List.of("running")),
                List.of(timeVar), manifest);
        smv.getEnvVariables().put("time", timeVar);
        smv.getCurrentModeStates().put("Mode", "running");

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("clock_1");
        dto.setTemplateName("Clock");
        dto.setState("running");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("clock_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        // P4 was about never comparing against an UNDECLARED identifier. `clock_1.time` satisfies
        // that: the mirror `clock_1.time := a_time` is emitted in main for every shared reading, so
        // the guard is fully constrained by the pool value. It is additionally the *sound* reference —
        // a Transition is the device's own behaviour and must read what the device observes, so that a
        // compromised sensor's falsified reading can drive its own controller. Reading `a_time`
        // directly would bypass the attack mirror and hide sensor spoofing.
        assertTrue(result.contains("clock_1.time := a_time"),
                "The read mirror must be emitted, got:\n" + result);
        assertTrue(result.contains("clock_1.time=23"),
                "Transition guard should read the device mirror, got:\n" + result);
        assertFalse(result.contains("a_time=23"),
                "Transition guard must NOT read the pool ground truth directly");
    }

    /**
     * The sensor-spoofing attack must stay reachable. A compromised device's falsified reading lives on
     * its own mirror (`<device>.<attr>`), so an autonomous Transition guard that read `a_<attr>` instead
     * made the device immune to its own compromise and the engine returned SATISFIED for the canonical
     * attack.
     */
    @Test
    @DisplayName("Attack: an autonomous transition guard reads the falsifiable mirror, not the pool")
    void autonomousTransitionUnderAttack_readsFalsifiableMirror() {
        DeviceManifest.InternalVariable smoke = numericVar("smokeLevel", false, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(smoke))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("silent").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("alarming").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("raise alarm")
                        .startState("silent")
                        .endState("alarming")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("smokeLevel").relation(">=").value("60").build())
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("detector_1", "SmokeDetector",
                List.of("Mode"), Map.of("Mode", List.of("silent", "alarming")),
                List.of(smoke), manifest);
        smv.getEnvVariables().put("smokeLevel", smoke);
        smv.getCurrentModeStates().put("Mode", "silent");

        DeviceVerificationDto dto = device("detector_1", "SmokeDetector");
        dto.setState("silent");

        assertDoesNotThrow(() -> validator.validate(Map.of("detector_1", smv)),
                "the trigger attribute must be a legal read for this test to exercise the guard");

        String result = mainBuilder.build(1L, List.of(dto), List.of(),
                Map.of("detector_1", smv), AttackScenarioDto.anyUpToBudget(1), false);

        // The attack branch is what a compromised sensor feeds itself; it must be on the identifier the
        // guard reads, otherwise compromise cannot influence the device's own behaviour.
        assertTrue(result.contains("detector_1.is_attack=TRUE"),
                "the falsifiable mirror must carry an attack branch, got:\n" + result);
        assertTrue(result.contains("detector_1.Mode=silent & detector_1.smokeLevel>=60: alarming;"),
                "the transition guard must read the device mirror, got:\n" + result);
        assertFalse(result.contains("a_smokeLevel>=60"),
                "the guard must not bypass the attack mirror by reading the pool ground truth");
    }

    @Test
    @DisplayName("Environment-triggered state transition reads the device's own mirror of the shared value")
    void stateTransitionTriggeredByEnvironment_usesDeviceMirror() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 0, 50);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temperature))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("warm activation")
                        .startState("off")
                        .endState("on")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("temperature").relation(">=").value("30").build())
                        .build()))
                .build();
        DeviceSmvData smv = buildSmvData("heater_1", "Heater",
                List.of("Mode"), Map.of("Mode", List.of("off", "on")),
                List.of(temperature), manifest);
        smv.getEnvVariables().put("temperature", temperature);
        smv.getCurrentModeStates().put("Mode", "off");

        DeviceVerificationDto dto = device("heater_1", "Heater");
        dto.setState("off");
        String result = mainBuilder.build(
                1L, List.of(dto), List.of(), Map.of("heater_1", smv), AttackScenarioDto.none(), false);

        assertTrue(result.contains("heater_1.temperature := a_temperature"), result);
        assertTrue(result.contains("heater_1.Mode=off & heater_1.temperature>=30: on;"), result);
        assertFalse(result.contains("a_temperature>=30"), result);
    }

    // ======================== P5: trust/privacy next=self ========================

    @Test
    @DisplayName("P5: multi-mode actuator trust has next() with self-hold")
    void trustNextSelfHold_multiMode() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "State"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("home;idle").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("away;active").trust("untrusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("goHome").startState("away;active").endState("home;idle").build()))
                .build();

        DeviceSmvData smv = buildSmvData("lock_1", "Lock",
                List.of("Mode", "State"),
                Map.of("Mode", List.of("home", "away"), "State", List.of("idle", "active")),
                List.of(), manifest);
        smv.getCurrentModeStates().put("Mode", "home");
        smv.getCurrentModeStates().put("State", "idle");
        smv.setInstanceStateTrust("trusted");

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("lock_1");
        dto.setTemplateName("Lock");
        dto.setState("home;idle");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("lock_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        // P5 核心断言：trust_Mode_home 必须有 next() 且包含自保持
        assertTrue(result.contains("next(lock_1.trust_Mode_home)"),
                "Should have next() for trust_Mode_home, got:\n" + result);
        assertTrue(result.contains("TRUE: lock_1.trust_Mode_home;"),
                "Should self-hold trust_Mode_home, got:\n" + result);
    }

    @Test
    @DisplayName("State privacy override initializes only the selected current state")
    void statePrivacyOverride_appliesOnlyToCurrentState() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("off").privacy("public").build(),
                        DeviceManifest.WorkingState.builder()
                                .name("on").privacy("public").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("turnOn").startState("off").endState("on").build()))
                .build();
        DeviceSmvData smv = buildSmvData("light_1", "Light",
                List.of("Mode"), Map.of("Mode", List.of("off", "on")),
                List.of(), manifest);
        smv.getCurrentModeStates().put("Mode", "on");
        smv.setCurrentState("on");
        smv.setInstanceStatePrivacy("private");

        String result = deviceBuilder.build(smv, false, true);

        assertTrue(result.contains("init(privacy_Mode_on) := private;"), result);
        assertTrue(result.contains("init(privacy_Mode_off) := public;"), result);
    }

    /**
     * A blank segment of a partial instance-state tuple must fall through to the template's InitState.
     *
     * <p>`appendInitialValues` used to fall back to `cleanStateName(currentState)` for any mode with no
     * entry in `currentModeStates`. `cleanStateName` **deletes** `;`, which is right for a lone state name
     * and wrong for a tuple: on a multi-mode device it compared the whole tuple, semicolons stripped,
     * against each mode's own domain — so a partial tuple leaked into the mode it had deliberately left
     * blank, silently overriding InitState.
     *
     * <p>The two modes below share the value `shared`, which is what makes the bug visible: with instance
     * state `"shared;"`, Beta was initialised to `shared` rather than to the template's `distinct`. It went
     * unnoticed because the leak only lands when the stripped tuple happens to be a legal value of the
     * other mode — `"other;"` fell through correctly by accident, since `other` is not in Beta's domain.
     * Both cases are asserted so neither accident can pass for correctness.
     */
    @Test
    @DisplayName("P5: a blank tuple segment uses InitState, not the whole tuple")
    void partialInstanceStateTuple_doesNotLeakIntoTheModeItLeftBlank() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Alpha", "Beta"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("shared;distinct").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("shared;shared").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("other;distinct").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("goOther").startState("").endState("other;").signal(true).build()))
                .build();

        // Instance names only Alpha; Beta is deliberately blank and must come from InitState.
        DeviceSmvData leaky = buildSmvData("probe_1", "TupleProbe",
                List.of("Alpha", "Beta"),
                Map.of("Alpha", List.of("shared", "other"), "Beta", List.of("distinct", "shared")),
                List.of(), manifest);
        leaky.setCurrentState("shared;");
        leaky.getCurrentModeStates().put("Alpha", "shared");
        leaky.getTemplateInitModeStates().put("Alpha", "shared");
        leaky.getTemplateInitModeStates().put("Beta", "distinct");

        String result = deviceBuilder.build(leaky, false, false);
        assertTrue(result.contains("init(Alpha) := shared;"), result);
        assertTrue(result.contains("init(Beta) := distinct;"),
                () -> "a blank segment must use the template InitState, not the stripped tuple: " + result);

        // The accidental-pass case: `other` is not in Beta's domain, so this fell through even before the
        // fix. Asserted so a regression cannot hide behind it.
        DeviceSmvData benign = buildSmvData("probe_2", "TupleProbe",
                List.of("Alpha", "Beta"),
                Map.of("Alpha", List.of("shared", "other"), "Beta", List.of("distinct", "shared")),
                List.of(), manifest);
        benign.setCurrentState("other;");
        benign.getCurrentModeStates().put("Alpha", "other");
        benign.getTemplateInitModeStates().put("Alpha", "shared");
        benign.getTemplateInitModeStates().put("Beta", "distinct");

        String benignResult = deviceBuilder.build(benign, false, false);
        assertTrue(benignResult.contains("init(Alpha) := other;"), benignResult);
        assertTrue(benignResult.contains("init(Beta) := distinct;"), benignResult);
    }

    @Test
    @DisplayName("P5: trust conflict in WorkingStates throws")
    void trustConflict_throws() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "State"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("home;idle").trust("trusted").build(),
                        // 同一个 Mode_home 出现不同 trust
                        DeviceManifest.WorkingState.builder().name("home;active").trust("untrusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("act").startState("home;idle").endState("home;active").build()))
                .build();

        DeviceSmvData smv = buildSmvData("lock_1", "Lock",
                List.of("Mode", "State"),
                Map.of("Mode", List.of("home"), "State", List.of("idle", "active")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("lock_1", smv);

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> validator.validate(map));
        assertTrue(ex.getMessage().contains("trust/privacy conflict"));
        assertTrue(ex.getMessage().contains("Mode_home"));
    }

    // ======================== P6: privacy spec + enablePrivacy=false ========================

    /** 通过反射调用 SmvGenerator.validateNoPrivacySpecs */
    private void invokeValidateNoPrivacySpecs(List<SpecificationDto> specs) throws Exception {
        // SmvGenerator 的构造函数需要所有依赖，但 validateNoPrivacySpecs 不使用它们
        SmvGenerator generator = new SmvGenerator(null, null, null, null, null, null);
        Method method = SmvGenerator.class.getDeclaredMethod("validateNoPrivacySpecs", List.class);
        method.setAccessible(true);
        try {
            method.invoke(generator, specs);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof SmvGenerationException) {
                throw (SmvGenerationException) e.getCause();
            }
            throw e;
        }
    }

    private SpecConditionDto makeCondition(String targetType) {
        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("dev1");
        cond.setTargetType(targetType);
        cond.setKey("temperature");
        cond.setRelation("=");
        cond.setValue("trusted");
        return cond;
    }

    @Test
    @DisplayName("P6: privacy condition in aConditions throws when privacy disabled")
    void privacyInAConditions_throws() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_privacy_1");
        spec.setAConditions(List.of(makeCondition("privacy")));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> invokeValidateNoPrivacySpecs(List.of(spec)));
        assertTrue(ex.getMessage().contains("spec_privacy_1"));
        assertTrue(ex.getMessage().contains("privacy"));
        assertEquals("PRIVACY_SPEC_WITHOUT_PRIVACY", ex.getErrorCategory());
    }

    @Test
    @DisplayName("P6: privacy condition in ifConditions throws when privacy disabled")
    void privacyInIfConditions_throws() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_privacy_2");
        spec.setAConditions(List.of());
        spec.setIfConditions(List.of(makeCondition("privacy")));
        spec.setThenConditions(List.of());

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> invokeValidateNoPrivacySpecs(List.of(spec)));
        assertTrue(ex.getMessage().contains("spec_privacy_2"));
    }

    @Test
    @DisplayName("P6: privacy condition in thenConditions throws when privacy disabled")
    void privacyInThenConditions_throws() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_privacy_3");
        spec.setAConditions(List.of());
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of(makeCondition("privacy")));

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> invokeValidateNoPrivacySpecs(List.of(spec)));
        assertTrue(ex.getMessage().contains("spec_privacy_3"));
    }

    @Test
    @DisplayName("P6: privacy condition targetType is case-insensitive")
    void privacyTargetType_caseInsensitive_throws() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_privacy_upper");
        spec.setAConditions(List.of(makeCondition("PRIVACY")));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> invokeValidateNoPrivacySpecs(List.of(spec)));
        assertTrue(ex.getMessage().contains("spec_privacy_upper"));
        assertEquals("PRIVACY_SPEC_WITHOUT_PRIVACY", ex.getErrorCategory());
    }

    @Test
    @DisplayName("P6: non-privacy specs pass validation")
    void nonPrivacySpecs_passes() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_trust_1");
        spec.setAConditions(List.of(makeCondition("trust")));
        spec.setIfConditions(List.of(makeCondition("state")));
        spec.setThenConditions(List.of(makeCondition("variable")));

        assertDoesNotThrow(() -> invokeValidateNoPrivacySpecs(List.of(spec)));
    }

    @Test
    @DisplayName("P6: empty specs list passes validation")
    void emptySpecs_passes() {
        assertDoesNotThrow(() -> invokeValidateNoPrivacySpecs(List.of()));
    }

    // ======================== P7: attackBudget=0 时 INVAR 约束 ========================

    /** 构建最小传感器设备用于 attackBudget 测试 */
    private DeviceSmvData buildSensorSmvForIntensity(String varName, int lo, int hi) {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(numericVar("temperature", false, lo, hi)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData(varName, "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(numericVar("temperature", false, lo, hi)),
                manifest);
        smv.getCurrentModeStates().put("Mode", "on");
        smv.setInstanceStateTrust("trusted");
        return smv;
    }

    @Test
    @DisplayName("P7: isAttack=true, attackBudget=0 generates FROZENVAR + INVAR attackBudget<=0")
    void attackWithZeroIntensity_generatesInvarZero() {
        DeviceSmvData smv = buildSensorSmvForIntensity("ts_1", 0, 100);
        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("ts_1");
        dto.setTemplateName("Sensor");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ts_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.anyUpToBudget(0), false);

        assertTrue(result.contains("FROZENVAR"), "Should declare FROZENVAR section");
        assertTrue(result.contains("iot_verify_compromised_point_count: 0..1"),
                "Counter domain should match the current number of attackable points");
        assertTrue(result.contains("INVAR iot_verify_compromised_point_count <= 0"));
        assertTrue(result.contains("init(iot_verify_compromised_point_count)"));
    }

    @Test
    @DisplayName("P7: isAttack=true, attackBudget=3 generates INVAR attackBudget<=3")
    void attackWithPositiveIntensity_generatesCorrectInvar() {
        DeviceSmvData smv = buildSensorSmvForIntensity("ts_1", 0, 100);
        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("ts_1");
        dto.setTemplateName("Sensor");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ts_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.anyUpToBudget(3), false);

        assertTrue(result.contains("INVAR iot_verify_compromised_point_count <= 3"),
                "The request budget should constrain the internal attacked-node counter");
    }

    // ======================== P8: 规格不再注入 attackBudget ========================

    @Test
    @DisplayName("P8: spec does not contain attackBudget injection in antecedent")
    void specNoIntensityInjection() {
        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("ts_1");
        cond.setTargetType("variable");
        cond.setKey("temperature");
        // `reported`, because this fixture declares the variable on the device without populating the
        // shared/env maps, so there is no pool identifier to read. These two cases are about attack-budget
        // injection, not about sourcing; asking `environment` here would skip the spec and make the
        // assertions below vacuous, which is exactly how they were silently neutered.
        cond.setVariableSource("reported");
        cond.setRelation("GT");
        cond.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_1");
        spec.setTemplateId("1"); // AG(A)
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        DeviceSmvData smv = buildSensorSmvForIntensity("ts_1", 0, 100);
        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ts_1", smv);

        String result = specBuilder.build(List.of(spec), map, false);

        // Positive first: without it, a skipped specification yields an empty string and every
        // assertFalse below passes vacuously. Both cases here were silently neutered exactly that way
        // when variableSource became required, and stayed green.
        assertTrue(result.contains("ts_1.temperature>30"),
                () -> "the specification must be emitted for this check to mean anything, got: " + result);
        assertFalse(result.contains("attackBudget<="), "Spec should not inject attackBudget constraint");
    }

    @Test
    @DisplayName("P8: templateId=7 safety spec does not contain attackBudget injection")
    void safetySpecNoIntensityInjection() {
        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("ts_1");
        cond.setTargetType("variable");
        cond.setKey("temperature");
        // `reported`, because this fixture declares the variable on the device without populating the
        // shared/env maps, so there is no pool identifier to read. These two cases are about attack-budget
        // injection, not about sourcing; asking `environment` here would skip the spec and make the
        // assertions below vacuous, which is exactly how they were silently neutered.
        cond.setVariableSource("reported");
        cond.setRelation("GT");
        cond.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_7");
        spec.setTemplateId("7");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        DeviceSmvData smv = buildSensorSmvForIntensity("ts_1", 0, 100);
        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ts_1", smv);

        String result = specBuilder.build(List.of(spec), map, false);

        assertTrue(result.contains("ts_1.temperature>30"),
                () -> "the specification must be emitted for this check to mean anything, got: " + result);
        assertFalse(result.contains("attackBudget<="), "Safety spec should not inject attackBudget constraint");
        assertFalse(result.contains(".is_attack=FALSE"),
                "Safety checks must include attacked target paths instead of excluding them");
    }

    // ======================== P8b: MEDIC attack behavior ========================

    @Test
    @DisplayName("P8b: attack mode does not freeze an actuator without a TAP rule command")
    void attackMode_actuator_withoutRuleCommandDoesNotFreezeStateMachine() {
        DeviceManifest actuatorManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("turnOn").startState("off").endState("on").build()))
                .build();
        DeviceSmvData actuator = buildSmvData(
                "light_1", "Light",
                List.of("Mode"),
                Map.of("Mode", List.of("off", "on")),
                List.of(), actuatorManifest);
        actuator.setSensor(false);
        actuator.setInstanceStateTrust("trusted");
        actuator.getCurrentModeStates().put("Mode", "off");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("light_1", actuator);

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("light_1");
        dto.setTemplateName("Light");
        dto.setState("off");

        String smv = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.anyUpToBudget(10), false);

        assertFalse(smv.contains("light_1.is_attack=TRUE: light_1.Mode;"),
                "Attack modeling must not freeze the whole actuator state machine, got:\n" + smv);
        assertFalse(smv.contains("light_1.is_attack=TRUE: {off, on};"),
                "MEDIC models command-delivery failure, not arbitrary actuator takeover");
        assertFalse(smv.contains("light_1.is_attack=TRUE: untrusted;"),
                "Command failure must not relabel a pre-existing actuator state as untrusted");
    }

    @Test
    @DisplayName("P8b: attacked sensor may report any value within its declared domain")
    void attackMode_sensor_readingTamperedWithinDomain() {
        DeviceSmvData sensor = buildSensorSmvForIntensity("ts_1", 0, 100);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ts_1", sensor);

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("ts_1");
        dto.setTemplateName("Sensor");
        dto.setState("on");

        String smv = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.anyUpToBudget(10), false);

        assertTrue(smv.contains("ts_1.is_attack=TRUE: 0..100;"),
                "An attacked sensor reading should be nondeterministic within its declared domain, got:\n" + smv);
        assertFalse(smv.contains("0..120"),
                "Attack budget must not widen the user-declared sensor domain");
    }

    @Test
    @DisplayName("P8b: device and automation-link compromise are separate command-failure points")
    void attackMode_deviceAndAutomationLinkAreSeparatePoints() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("heat").trust("trusted").build()))
                .internalVariables(List.of(temperature))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("heat").startState("off").endState("heat").build()))
                .build();
        DeviceSmvData thermostat = buildSmvData(
                "thermostat_1", "Thermostat",
                List.of("Mode"),
                Map.of("Mode", List.of("off", "heat")),
                List.of(temperature), manifest);
        thermostat.setSensor(false);

        DeviceVerificationDto dto = device("thermostat_1", "Thermostat");
        dto.setState("off");
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("thermostat_1")
                        .attribute("temperature")
                        .targetType("variable")
                        .relation(">")
                        .value("30")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("thermostat_1")
                        .action("heat")
                        .build())
                .build();
        String smv = mainBuilder.build(
                1L, List.of(dto), List.of(rule), Map.of("thermostat_1", thermostat), AttackScenarioDto.anyUpToBudget(1), false);

        assertTrue(smv.contains("thermostat_1.is_attack=TRUE: 0..100;"),
                "A compromised composite device may tamper with its declared external reading");
        assertTrue(smv.contains("thermostat_1.is_attack=FALSE"),
                "The same compromised device should drop the TAP rule command when attacked");
        assertTrue(smv.contains("iot_verify_automation_link_compromised_0: boolean;"));
        assertTrue(smv.contains("init(iot_verify_automation_link_compromised_0) := {TRUE, FALSE};"));
        assertTrue(smv.contains("iot_verify_automation_link_compromised_0=FALSE"),
                "A separately compromised automation delivery link must drop its rule command");
        assertTrue(smv.contains("iot_verify_compromised_point_count: 0..2"),
                "One device plus one automation link should produce two countable attack points");
        assertTrue(smv.contains("+ toint(iot_verify_automation_link_compromised_0)"));
        assertFalse(smv.contains("thermostat_1.is_attack=TRUE: thermostat_1.Mode;"),
                "Command loss must not freeze unrelated device-internal transitions");
        assertTrue(smv.contains("next(thermostat_1.trust_temperature) :=\n\tcase\n"
                        + "\t\tthermostat_1.is_attack=TRUE: untrusted;"),
                "Tampered external readings must be labeled untrusted");

        String module = deviceBuilder.build(thermostat, true, false);
        assertTrue(module.contains("is_attack=TRUE: untrusted;"),
                "The initial external-reading label must agree with later attack states");
    }

    @Test
    @DisplayName("P8b: an explicitly falsifiable local reading is tampered from the initial state")
    void attackMode_compositeDeviceLocalReadingUsesExplicitTemplateSemantics() {
        DeviceManifest.InternalVariable location = numericVar("location", true, 0, 10);
        location.setFalsifiableWhenCompromised(true);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("idle").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("active").trust("trusted").build()))
                .internalVariables(List.of(location))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("activate").startState("idle").endState("active").build()))
                .build();
        DeviceSmvData phone = buildSmvData(
                "phone_1", "Phone", List.of("Mode"),
                Map.of("Mode", List.of("idle", "active")), List.of(location), manifest);
        phone.setSensor(false);

        String deviceModule = deviceBuilder.build(phone, true, false);
        assertTrue(deviceModule.contains("init(location) :=\n\tcase\n\t\tis_attack=TRUE: 0..10;"));
        assertTrue(deviceModule.contains("init(trust_location) :=\n\tcase\n\t\tis_attack=TRUE: untrusted;"));

        String main = mainBuilder.build(
                1L, List.of(device("phone_1", "Phone")), List.of(),
                Map.of("phone_1", phone), AttackScenarioDto.anyUpToBudget(1), false);
        assertTrue(main.contains("phone_1.is_attack=TRUE: 0..10;"));
    }

    @Test
    @DisplayName("P8b: a local actuator value is not randomized unless the template declares it falsifiable")
    void attackMode_nonFalsifiableLocalActuatorValueRemainsModelDriven() {
        DeviceManifest.InternalVariable progress = numericVar("progress", true, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("running").build()))
                .internalVariables(List.of(progress))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("start").startState("").endState("running").build()))
                .build();
        DeviceSmvData washer = buildSmvData(
                "washer_1", "Washer", List.of("Mode"), Map.of("Mode", List.of("running")),
                List.of(progress), manifest);
        washer.setSensor(false);

        String deviceModule = deviceBuilder.build(washer, true, false);
        assertFalse(deviceModule.contains("is_attack=TRUE: 0..100"));

        String main = mainBuilder.build(
                1L, List.of(device("washer_1", "Washer")), List.of(),
                Map.of("washer_1", washer), AttackScenarioDto.anyUpToBudget(1), false);
        assertFalse(main.contains("washer_1.is_attack=TRUE: 0..100"));
        assertFalse(main.contains("washer_1.is_attack"),
                "An inert device must not consume or expose a compromise choice");
        assertTrue(main.contains("iot_verify_compromised_point_count: 0..0"));
    }

    @Test
    @DisplayName("P8b: attacked target drops TAP rule command through its transition guard")
    void attackMode_actuator_ruleCommandRequiresUnattackedTarget() {
        DeviceManifest actuatorManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("locked").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("unlocked").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("unlock").startState("locked").endState("unlocked").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("autoRelock")
                        .startState("unlocked")
                        .endState("locked")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("Mode").relation("=").value("unlocked").build())
                        .build()))
                .build();
        DeviceSmvData lock = buildSmvData(
                "lock_1", "Lock",
                List.of("Mode"),
                Map.of("Mode", List.of("locked", "unlocked")),
                List.of(), actuatorManifest);
        lock.setSensor(false);
        lock.setInstanceStateTrust("trusted");
        lock.getCurrentModeStates().put("Mode", "locked");

        DeviceManifest sensorManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(numericVar("temperature", true, 0, 100)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData sensor = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(numericVar("temperature", true, 0, 100)), sensorManifest);
        sensor.setSensor(true);
        sensor.setInstanceStateTrust("trusted");
        sensor.getCurrentModeStates().put("Mode", "on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("lock_1", lock);
        map.put("sensor_1", sensor);

        DeviceVerificationDto lockDto = new DeviceVerificationDto();
        lockDto.setVarName("lock_1");
        lockDto.setTemplateName("Lock");
        lockDto.setState("locked");

        DeviceVerificationDto sensorDto = new DeviceVerificationDto();
        sensorDto.setVarName("sensor_1");
        sensorDto.setTemplateName("Sensor");
        sensorDto.setState("on");

        RuleDto.Condition cond = new RuleDto.Condition();
        cond.setDeviceName("sensor_1");
        cond.setTargetType("variable");
        cond.setAttribute("temperature");
        cond.setRelation(">");
        cond.setValue("30");

        RuleDto.Command cmd = new RuleDto.Command();
        cmd.setDeviceName("lock_1");
        cmd.setAction("unlock");

        RuleDto rule = new RuleDto();
        rule.setConditions(List.of(cond));
        rule.setCommand(cmd);

        String smv = mainBuilder.build(1L, List.of(lockDto, sensorDto), List.of(rule), map, AttackScenarioDto.anyUpToBudget(10), false);

        String transitionBlock = "next(lock_1.Mode)";
        int blockStart = smv.indexOf(transitionBlock);
        assertTrue(blockStart >= 0, "next(lock_1.Mode) transition block must exist, got:\n" + smv);

        int esacIdx = smv.indexOf("esac;", blockStart);
        assertTrue(esacIdx > blockStart, "case...esac must close lock_1 transition block, got:\n" + smv);
        String block = smv.substring(blockStart, esacIdx);
        int ruleIdx = block.indexOf("lock_1.is_attack=FALSE");
        assertTrue(ruleIdx >= 0, "Rule with is_attack=FALSE guard must exist in lock_1 transition block, got:\n" + block);
        assertFalse(block.contains("lock_1.is_attack=TRUE: lock_1.Mode;"),
                "A dropped command must not freeze the target's whole state machine, got:\n" + block);
        assertTrue(block.contains("lock_1.Mode=unlocked") && block.contains(": locked;"),
                "The target's template-defined auto-relock transition must remain possible, got:\n" + block);
    }

    @Test
    @DisplayName("P8b: attack mode does not freeze unrelated multi-mode state transitions")
    void attackMode_multiModeActuator_doesNotFreezeEachMode() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("auto;high").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("manual;low").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("setAuto").endState("auto;high").build()))
                .build();
        DeviceSmvData hvac = buildSmvData(
                "hvac_1", "HVAC",
                List.of("Mode", "FanMode"),
                Map.of("Mode", List.of("auto", "manual"), "FanMode", List.of("high", "low")),
                List.of(), manifest);
        hvac.setSensor(false);
        hvac.setInstanceStateTrust("trusted");
        hvac.getCurrentModeStates().put("Mode", "auto");
        hvac.getCurrentModeStates().put("FanMode", "high");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("hvac_1", hvac);

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("hvac_1");
        dto.setTemplateName("HVAC");
        dto.setState("auto;high");

        String smv = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.anyUpToBudget(5), false);

        assertFalse(smv.contains("hvac_1.is_attack=TRUE: hvac_1.Mode;"),
                "Attack modeling must not freeze Mode, got:\n" + smv);
        assertFalse(smv.contains("hvac_1.is_attack=TRUE: hvac_1.FanMode;"),
                "Attack modeling must not freeze FanMode, got:\n" + smv);
    }

    @Test
    @DisplayName("P8b: isAttack=false does NOT generate hijack branch for actuator")
    void noAttackMode_actuator_noHijackBranch() {
        DeviceManifest sensorManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(numericVar("temperature", true, 0, 100)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData sensor = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(numericVar("temperature", true, 0, 100)), sensorManifest);
        sensor.setSensor(true);
        sensor.setInstanceStateTrust("trusted");
        sensor.getCurrentModeStates().put("Mode", "on");

        DeviceManifest actuatorManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("turnOn").startState("off").endState("on").build()))
                .build();
        DeviceSmvData actuator = buildSmvData(
                "light_1", "Light",
                List.of("Mode"),
                Map.of("Mode", List.of("off", "on")),
                List.of(), actuatorManifest);
        actuator.setSensor(false);
        actuator.setInstanceStateTrust("trusted");
        actuator.getCurrentModeStates().put("Mode", "off");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", sensor);
        map.put("light_1", actuator);

        DeviceVerificationDto sensorDto = new DeviceVerificationDto();
        sensorDto.setVarName("sensor_1");
        sensorDto.setTemplateName("Sensor");
        sensorDto.setState("on");

        DeviceVerificationDto lightDto = new DeviceVerificationDto();
        lightDto.setVarName("light_1");
        lightDto.setTemplateName("Light");
        lightDto.setState("off");

        RuleDto.Condition cond = new RuleDto.Condition();
        cond.setDeviceName("sensor_1");
        cond.setTargetType("variable");
        cond.setAttribute("temperature");
        cond.setRelation(">");
        cond.setValue("30");

        RuleDto.Command cmd = new RuleDto.Command();
        cmd.setDeviceName("light_1");
        cmd.setAction("turnOn");

        RuleDto rule = new RuleDto();
        rule.setConditions(List.of(cond));
        rule.setCommand(cmd);

        String smv = mainBuilder.build(1L, List.of(sensorDto, lightDto), List.of(rule), map, AttackScenarioDto.none(), false);

        assertTrue(smv.contains("sensor_1.temperature>30") && smv.contains("light_1.Mode=off: on;"),
                "Rule-generated transition (temperature>30 -> turnOn) must be present, got:\n" + smv);
        assertFalse(smv.contains("is_attack=TRUE"),
                "Non-attack mode should not have hijack branch, got:\n" + smv);
        assertFalse(smv.contains("is_attack=FALSE"),
                "Non-attack mode should not have is_attack guard on rules, got:\n" + smv);
    }

    // ======================== P9: attack budget does not alter declared domains ========================

    @Test
    @DisplayName("P9: zero attack budget preserves the sensor domain")
    void attackBudgetZero_preservesSensorDomain() {
        DeviceSmvData smv = buildSensorSmvForIntensity("ts_1", 0, 100);
        String result = deviceBuilder.build(smv, true, false);

        assertTrue(result.contains("temperature: 0..100"));
        assertFalse(result.contains("0..120"));
    }

    @Test
    @DisplayName("P9: high attack budget still preserves the user-declared sensor domain")
    void highAttackBudget_preservesSensorDomain() {
        DeviceSmvData smv = buildSensorSmvForIntensity("ts_1", 0, 100);
        String result = deviceBuilder.build(smv, true, false);

        assertTrue(result.contains("temperature: 0..100"));
        assertFalse(result.contains("0..120"));
    }

    // ======================== P10: SmvGenerator attackBudget clamp ========================

    private SmvGenerator buildGeneratorForClampTests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);

        DeviceManifest manifest = DeviceManifest.builder()
                .name("SensorTemplate")
                .modes(List.of("Mode"))
                .initState("on")
                .internalVariables(List.of(DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(true)
                        .falsifiableWhenCompromised(false)
                        .trust("trusted")
                        .privacy("public")
                        .lowerBound(0)
                        .upperBound(100)
                        .build()))
                .workingStates(List.of(DeviceManifest.WorkingState.builder()
                        .name("on").trust("trusted").privacy("public").build()))
                .build();

        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("SensorTemplate")
                .manifestJson(mapper.writeValueAsString(manifest))
                .build();

        when(templateService.findTemplateByName(anyLong(), eq("SensorTemplate")))
                .thenReturn(Optional.of(template));

        DeviceSmvDataFactory factory = new DeviceSmvDataFactory(
                mapper, templateService, new SmvModelValidator(), new DeviceTemplateSchemaValidator(mapper));
        return new SmvGenerator(
                factory,
                new SmvDeviceModuleBuilder(),
                new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(),
                new SmvSpecificationBuilder(),
                new SmvModelValidator()
        );
    }

    private DeviceVerificationDto buildClampTestDevice() {
        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("ts_1");
        dto.setTemplateName("SensorTemplate");
        dto.setState("on");
        return dto;
    }

    @Test
    @DisplayName("P10: SmvGenerator rejects an attack scenario with an oversized budget")
    void generatorRejectsAttackScenarioBudgetAboveUpperBound() throws Exception {
        SmvGenerator generator = buildGeneratorForClampTests();
        DeviceVerificationDto dto = buildClampTestDevice();

        SmvGenerationException error = assertThrows(SmvGenerationException.class, () -> generator.generate(
                1L, List.of(dto), List.of(), List.of(), AttackScenarioDto.anyUpToBudget(999), false));

        assertEquals(SmvGenerationException.ErrorCategories.INVALID_BUILDER_INPUT, error.getErrorCategory());
        assertTrue(error.getMessage().contains("ANY_UP_TO_BUDGET requires a 1..50 budget"));
    }

    @Test
    @DisplayName("P10: SmvGenerator rejects an attack scenario with a negative budget")
    void generatorRejectsAttackScenarioBudgetBelowLowerBound() throws Exception {
        SmvGenerator generator = buildGeneratorForClampTests();
        DeviceVerificationDto dto = buildClampTestDevice();

        SmvGenerationException error = assertThrows(SmvGenerationException.class, () -> generator.generate(
                1L, List.of(dto), List.of(), List.of(), AttackScenarioDto.anyUpToBudget(-7), false));

        assertEquals(SmvGenerationException.ErrorCategories.INVALID_BUILDER_INPUT, error.getErrorCategory());
        assertTrue(error.getMessage().contains("ANY_UP_TO_BUDGET requires a 1..50 budget"));
    }

    @Test
    @DisplayName("P10: SmvGenerator rejects malformed structured attack scenarios")
    void generatorRejectsMalformedAttackScenarios() throws Exception {
        SmvGenerator generator = buildGeneratorForClampTests();
        DeviceVerificationDto dto = buildClampTestDevice();

        SmvGenerationException zeroEnabled = assertThrows(SmvGenerationException.class, () -> generator.generate(
                1L, List.of(dto), List.of(), List.of(), AttackScenarioDto.anyUpToBudget(0), false));
        AttackScenarioDto malformedNone = AttackScenarioDto.builder()
                .mode(AttackScenarioDto.Mode.NONE)
                .budget(1)
                .points(List.of())
                .build();
        SmvGenerationException positiveDisabled = assertThrows(SmvGenerationException.class, () -> generator.generate(
                1L, List.of(dto), List.of(), List.of(), malformedNone, false));

        assertTrue(zeroEnabled.getMessage().contains("ANY_UP_TO_BUDGET requires a 1..50 budget"));
        assertTrue(positiveDisabled.getMessage().contains("NONE must not contain a budget"));
    }

    // ======================== P11: rule fail-closed + env init conflict ========================

    @Test
    @DisplayName("P11: unresolved multi-mode state condition returns null (fail-closed)")
    void unresolvedMultiModeStateCondition_returnsNull() throws Exception {
        DeviceManifest sourceManifest = DeviceManifest.builder()
                .modes(List.of("Mode", "FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("auto;on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("manual;off").trust("trusted").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "thermostat_1", "Thermostat",
                List.of("Mode", "FanMode"),
                Map.of("Mode", List.of("auto", "manual"), "FanMode", List.of("on", "off")),
                List.of(), sourceManifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("thermostat_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("thermostat_1");
        condition.setAttribute("state");
        condition.setRelation("=");
        condition.setValue("unknown_state");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertNull(expr, "Unresolvable multi-mode state condition should fail-closed");
    }

    @Test
    @DisplayName("P11: null/blank rule attribute fails closed")
    void nullRuleAttribute_returnsNull() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("sensor_1");
        condition.setAttribute("   ");
        condition.setRelation("=");
        condition.setValue("on");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertNull(expr, "Blank attribute should fail-closed");
    }

    @Test
    @DisplayName("P11: rule relation with surrounding spaces is normalized")
    void ruleRelationWithSpaces_isNormalized() throws Exception {
        DeviceManifest.InternalVariable temp = numericVar("temperature", true, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temp))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(temp), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("sensor_1");
        condition.setTargetType("variable");
        condition.setAttribute("temperature");
        condition.setRelation(" EQ ");
        condition.setValue("30");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertEquals("sensor_1.temperature=30", expr);
    }

    @Test
    @DisplayName("P11: rule IN relation with empty value list fails closed")
    void ruleInWithEmptyValueList_returnsNull() throws Exception {
        DeviceManifest.InternalVariable temp = numericVar("temperature", true, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temp))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(temp), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("sensor_1");
        condition.setTargetType("variable");
        condition.setAttribute("temperature");
        condition.setRelation("IN");
        condition.setValue(" , ; | ");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertNull(expr, "Empty IN list should fail-closed");
    }

    @Test
    @DisplayName("P11: unknown rule attribute with relation fails closed")
    void unknownRuleAttributeWithRelation_returnsNull() throws Exception {
        DeviceManifest.InternalVariable temp = numericVar("temperature", true, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temp))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(temp), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("sensor_1");
        condition.setAttribute("non_existing_attr");
        condition.setRelation("=");
        condition.setValue("1");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertNull(expr, "Unknown attribute should fail-closed");
    }

    @Test
    @DisplayName("P11: API signal condition with relation maps to api signal var")
    void apiSignalConditionWithRelation_mapsToApiSignalVar() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("auto").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("fanAuto").signal(true).endState("auto").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "fan_1", "Fan",
                List.of("FanMode"),
                Map.of("FanMode", List.of("off", "auto")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("fan_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("fan_1");
        condition.setTargetType("api");
        condition.setAttribute("fanAuto");
        condition.setRelation(" EQ ");
        condition.setValue(" true ");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertEquals("fan_1.fanAuto_a=TRUE", expr);
    }

    @Test
    @DisplayName("P11: API signal condition with non-boolean value fails closed")
    void apiSignalConditionWithNonBooleanValue_returnsNull() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("auto").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("fanAuto").signal(true).endState("auto").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "fan_1", "Fan",
                List.of("FanMode"),
                Map.of("FanMode", List.of("off", "auto")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("fan_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("fan_1");
        condition.setTargetType("api");
        condition.setAttribute("fanAuto");
        condition.setRelation("=");
        condition.setValue("on");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertNull(expr, "API signal relation value should be boolean");
    }

    @Test
    @DisplayName("P11: relation-null API condition is an event pulse, not a persistent end state")
    void apiSignalConditionWithoutRelation_useNextFalse_usesOnlyCurrentPulse() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("auto").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("fanAuto").signal(true).endState("auto").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "fan_1", "Fan",
                List.of("FanMode"),
                Map.of("FanMode", List.of("off", "auto")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("fan_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("fan_1");
        condition.setTargetType("api");
        condition.setAttribute("fanAuto");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, false, null, null, 0, null);

        assertEquals("fan_1.fanAuto_a=TRUE", expr);
    }

    @Test
    @DisplayName("P11: next-step API condition uses only the next event pulse")
    void apiSignalConditionWithoutRelation_useNextTrue_usesOnlyNextPulse() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("auto").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("fanAuto").signal(true).endState("auto").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "fan_1", "Fan",
                List.of("FanMode"),
                Map.of("FanMode", List.of("off", "auto")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("fan_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("fan_1");
        condition.setTargetType("api");
        condition.setAttribute("fanAuto");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, true, null, null, 0, null);

        assertEquals("next(fan_1.fanAuto_a)=TRUE", expr);
    }

    @Test
    @DisplayName("P11: API signal no-mode condition does not fallback to undeclared state var")
    void apiSignalConditionWithoutRelation_noModeFallback_useNextTrue() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("powerOn").signal(true).endState("on").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "plug_1", "SmartPlug",
                List.of(),
                Map.of(),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("plug_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("plug_1");
        condition.setTargetType("api");
        condition.setAttribute("powerOn");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, true, null, null, 0, null);

        assertEquals("next(plug_1.powerOn_a)=TRUE", expr);
    }

    @Test
    @DisplayName("P11: main build uses current expression for relation-null API signal condition")
    void mainBuild_ruleWithNullRelationApiSignal_usesCurrentExpr() {
        DeviceManifest fanManifest = DeviceManifest.builder()
                .modes(List.of("FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("auto").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("fanAuto").signal(true).endState("auto").build()))
                .build();
        DeviceSmvData fan = buildSmvData(
                "fan_1", "Fan",
                List.of("FanMode"),
                Map.of("FanMode", List.of("off", "auto")),
                List.of(), fanManifest);

        DeviceManifest lockManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("locked").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("unlocked").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("unlock").startState("locked").endState("unlocked").build()))
                .build();
        DeviceSmvData lock = buildSmvData(
                "lock_1", "Lock",
                List.of("Mode"),
                Map.of("Mode", List.of("locked", "unlocked")),
                List.of(), lockManifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("fan_1", fan);
        map.put("lock_1", lock);

        DeviceVerificationDto fanDto = new DeviceVerificationDto();
        fanDto.setVarName("fan_1");
        fanDto.setTemplateName("Fan");
        fanDto.setState("off");

        DeviceVerificationDto lockDto = new DeviceVerificationDto();
        lockDto.setVarName("lock_1");
        lockDto.setTemplateName("Lock");
        lockDto.setState("locked");

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("fan_1");
        condition.setTargetType("api");
        condition.setAttribute("fanAuto");

        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("lock_1");
        command.setAction("unlock");

        RuleDto rule = new RuleDto();
        rule.setConditions(List.of(condition));
        rule.setCommand(command);

        String smv = mainBuilder.build(1L, List.of(fanDto, lockDto), List.of(rule), map, AttackScenarioDto.none(), false);

        assertTrue(smv.contains("fan_1.fanAuto_a=TRUE & lock_1.Mode=locked: unlocked;"),
                "State transition should use the current rule condition/event, got:\n" + smv);
        assertFalse(smv.contains("fan_1.FanMode=auto & lock_1.Mode=locked: unlocked;"),
                "Remaining in the API end state must not retrigger an event rule, got:\n" + smv);
    }

    @Test
    @DisplayName("P11: multi-mode state tuple with useNext builds conjunction on next(mode)")
    void stateTupleCondition_useNext_buildsTupleConjunction() throws Exception {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("auto;on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("manual;off").trust("trusted").build()))
                .build();
        DeviceSmvData source = buildSmvData(
                "thermostat_1", "Thermostat",
                List.of("Mode", "FanMode"),
                Map.of("Mode", List.of("auto", "manual"), "FanMode", List.of("on", "off")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("thermostat_1", source);

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("thermostat_1");
        condition.setTargetType("state");
        condition.setAttribute("state");
        condition.setRelation("=");
        condition.setValue("auto;on");

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildSingleCondition", RuleDto.Condition.class, Map.class, boolean.class, String.class, Integer.class, int.class, ParameterizationConfig.class);
        method.setAccessible(true);
        String expr = (String) method.invoke(mainBuilder, condition, map, true, null, null, 0, null);

        assertEquals("(next(thermostat_1.Mode)=auto & next(thermostat_1.FanMode)=on)", expr);
    }

    @Test
    @DisplayName("P11: self-referencing state condition in target transition avoids recursive next")
    void selfReferencingStateCondition_targetTransition_noRecursiveNext() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("turnOn").startState("off").endState("on").build()))
                .build();

        DeviceSmvData fan = buildSmvData(
                "fan_1", "Fan",
                List.of("Mode"),
                Map.of("Mode", List.of("off", "on")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("fan_1", fan);

        DeviceVerificationDto fanDto = new DeviceVerificationDto();
        fanDto.setVarName("fan_1");
        fanDto.setTemplateName("Fan");
        fanDto.setState("off");

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("fan_1");
        condition.setTargetType("state");
        condition.setAttribute("state");
        condition.setRelation("=");
        condition.setValue("off");

        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("fan_1");
        command.setAction("turnOn");

        RuleDto rule = new RuleDto();
        rule.setConditions(List.of(condition));
        rule.setCommand(command);

        String smv = mainBuilder.build(1L, List.of(fanDto), List.of(rule), map, AttackScenarioDto.none(), false);
        assertTrue(smv.contains("fan_1.Mode=off"), "Rule guard should use current state on self-reference");
        assertFalse(smv.contains("next(fan_1.Mode)=off"),
                "Self-referencing target transition must not read next(fan_1.Mode) recursively");
    }

    @Test
    @DisplayName("P11: non-canonical template command target is rejected")
    void nonCanonicalTemplateCommandTarget_throwsDeviceNotFound() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", true, 0, 100);
        DeviceManifest sensorManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temperature))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData sensor = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(temperature), sensorManifest);

        DeviceManifest lightManifest = DeviceManifest.builder()
                .modes(List.of("Power"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder().name("turnOn").startState("off").endState("on").build()))
                .build();
        DeviceSmvData light1 = buildSmvData(
                "light_1", "Light",
                List.of("Power"),
                Map.of("Power", List.of("off", "on")),
                List.of(), lightManifest);
        DeviceSmvData light2 = buildSmvData(
                "light_2", "Light",
                List.of("Power"),
                Map.of("Power", List.of("off", "on")),
                List.of(), lightManifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", sensor);
        map.put("light_1", light1);
        map.put("light_2", light2);

        DeviceVerificationDto sensorDto = new DeviceVerificationDto();
        sensorDto.setVarName("sensor_1");
        sensorDto.setTemplateName("Sensor");
        sensorDto.setState("on");
        DeviceVerificationDto lightDto1 = new DeviceVerificationDto();
        lightDto1.setVarName("light_1");
        lightDto1.setTemplateName("Light");
        lightDto1.setState("off");
        DeviceVerificationDto lightDto2 = new DeviceVerificationDto();
        lightDto2.setVarName("light_2");
        lightDto2.setTemplateName("Light");
        lightDto2.setState("off");

        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName("sensor_1");
        condition.setTargetType("variable");
        condition.setAttribute("temperature");
        condition.setRelation(">");
        condition.setValue("30");

        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("Light");
        command.setAction("turnOn");

        RuleDto rule = new RuleDto();
        rule.setConditions(List.of(condition));
        rule.setCommand(command);

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.build(1L, List.of(sensorDto, lightDto1, lightDto2), List.of(rule), map, AttackScenarioDto.none(), false));
        assertEquals("DEVICE_NOT_FOUND", ex.getErrorCategory());
    }

    @Test
    @DisplayName("P11: non-canonical template contentDevice is rejected")
    void nonCanonicalTemplateContentDevice_throwsDeviceNotFound() throws Exception {
        DeviceManifest phoneManifest = DeviceManifest.builder().build();
        DeviceSmvData phone1 = buildSmvData("phone_1", "Phone",
                List.of("Mode"), Map.of("Mode", List.of("on")), List.of(), phoneManifest);
        DeviceSmvData phone2 = buildSmvData("phone_2", "Phone",
                List.of("Mode"), Map.of("Mode", List.of("on")), List.of(), phoneManifest);

        DeviceManifest targetManifest = DeviceManifest.builder()
                .apis(List.of(DeviceManifest.API.builder()
                        .name("send").acceptsContent(true).build()))
                .build();
        DeviceSmvData target = buildSmvData("target_1", "Target",
                List.of(), Map.of(), List.of(), targetManifest);

        DeviceSmvData.ContentInfo photo = new DeviceSmvData.ContentInfo();
        photo.setName("photo");
        phone1.getContents().add(photo);
        DeviceSmvData.ContentInfo photo2 = new DeviceSmvData.ContentInfo();
        photo2.setName("photo");
        phone2.getContents().add(photo2);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("phone_1", phone1);
        map.put("phone_2", phone2);
        map.put("target_1", target);

        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("target_1");
        command.setAction("send");
        command.setContentDevice("Phone");
        command.setContent("photo");

        RuleDto rule = new RuleDto();
        rule.setCommand(command);
        rule.setConditions(List.of());

        Method method = SmvMainModuleBuilder.class.getDeclaredMethod(
                "buildContentPrivacyCondition", RuleDto.class, Map.class);
        method.setAccessible(true);

        java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(mainBuilder, rule, map));
        assertTrue(ex.getCause() instanceof SmvGenerationException);
        assertEquals("DEVICE_NOT_FOUND", ((SmvGenerationException) ex.getCause()).getErrorCategory());
    }

    @Test
    @DisplayName("P11: trigger relation keyword is normalized in generated transition")
    void triggerRelationKeyword_normalizedInOutput() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", true, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temperature))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("t1")
                        .startState("off")
                        .endState("on")
                        .trigger(DeviceManifest.Trigger.builder()
                                .attribute("temperature")
                                .relation("GTE")
                                .value("30")
                                .build())
                        .build()))
                .build();
        DeviceSmvData smvData = buildSmvData(
                "heater_1", "Heater",
                List.of("Mode"),
                Map.of("Mode", List.of("off", "on")),
                List.of(temperature), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("heater_1", smvData);

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("heater_1");
        dto.setTemplateName("Heater");
        dto.setState("off");

        String smv = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);
        assertTrue(smv.contains("heater_1.temperature>=30"), "Trigger relation should be normalized to >=, got:\n" + smv);
        assertFalse(smv.contains("heater_1.temperatureGTE30"), "Raw relation keyword should not appear in output");
    }

    @Test
    @DisplayName("P11: API signal transition on no-mode device is rejected")
    void apiSignalTransition_noModeDevice_isRejected() {
        DeviceManifest manifest = DeviceManifest.builder()
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("powerOn").signal(true).startState("off").endState("on").build()))
                .build();

        DeviceSmvData smvData = buildSmvData(
                "plug_1", "SmartPlug",
                List.of(),
                Map.of(),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("plug_1", smvData);

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("plug_1");
        dto.setTemplateName("SmartPlug");
        dto.setState("on");

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false));
        assertEquals("TEMPLATE_INVALID", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains("powerOn"));
    }

    @Test
    @DisplayName("P11: device-expanded env mirrors do not initialize shared env variables")
    void envInitMirrorsWithoutPool_doNotInitializeSharedVariable() {
        DeviceManifest.InternalVariable time = numericVar("time", false, 0, 23);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(time))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();

        DeviceSmvData d1 = buildSmvData("clock_1", "Clock",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(time), manifest);
        d1.getEnvVariables().put("time", time);
        d1.getCurrentModeStates().put("Mode", "on");
        d1.getVariableValues().put("time", "10");

        DeviceSmvData d2 = buildSmvData("clock_2", "Clock",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(time), manifest);
        d2.getEnvVariables().put("time", time);
        d2.getCurrentModeStates().put("Mode", "on");
        d2.getVariableValues().put("time", "12");

        DeviceVerificationDto dto1 = new DeviceVerificationDto();
        dto1.setVarName("clock_1");
        dto1.setTemplateName("Clock");
        dto1.setState("on");

        DeviceVerificationDto dto2 = new DeviceVerificationDto();
        dto2.setVarName("clock_2");
        dto2.setTemplateName("Clock");
        dto2.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("clock_1", d1);
        map.put("clock_2", d2);

        String smvText = mainBuilder.build(1L, List.of(dto1, dto2), List.of(), map, AttackScenarioDto.none(), false);
        assertTrue(smvText.contains("a_time: 0..23;"), "Environment variable must still be declared globally");
        assertFalse(smvText.contains("init(a_time)"),
                "Only the top-level environment pool may initialize shared environment variables");
    }

    @Test
    @DisplayName("P11: explicit environment pool value is emitted as init(a_var)")
    void environmentPoolValue_emitsInitAssignment() {
        DeviceManifest.InternalVariable time = numericVar("time", false, 0, 23);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(time))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();

        DeviceSmvData smv = buildSmvData("clock_1", "Clock",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(time), manifest);
        smv.getEnvVariables().put("time", time);
        smv.getCurrentModeStates().put("Mode", "on");
        smv.getVariableValues().put("time", "12");

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("clock_1");
        dto.setTemplateName("Clock");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("clock_1", smv);

        String smvText = mainBuilder.build(1L, List.of(dto),
                List.of(new BoardEnvironmentVariableDto("time", "10", "trusted", "public")),
                List.of(), map, AttackScenarioDto.none(), false, SmvGenerationContext.noop());
        assertTrue(smvText.contains("a_time: 0..23;"), "Environment variable must be declared globally");
        assertTrue(smvText.contains("init(a_time) := 10;"),
                "Explicit environment pool value must initialize a_time");
        assertFalse(smvText.contains("init(a_time) := 12;"),
                "Expanded per-device read mirrors must not override the environment pool");
    }

    @Test
    @DisplayName("P11: shared environment trust and visibility labels reach every reading device")
    void environmentPoolLabels_areAppliedByGenerator() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);
        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .isInside(false).reads(true)
                .falsifiableWhenCompromised(true)
                .trust("untrusted")
                .privacy("public")
                .lowerBound(0)
                .upperBound(100)
                .naturalChangeRate("[-1, 1]")
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Temperature Sensor")
                .internalVariables(List.of(temperature))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Temperature Sensor")
                .manifestJson(mapper.writeValueAsString(manifest))
                .build();
        when(templateService.findTemplateByName(anyLong(), eq("Temperature Sensor")))
                .thenReturn(Optional.of(template));

        SmvGenerator generator = new SmvGenerator(
                new DeviceSmvDataFactory(mapper, templateService, new SmvModelValidator(),
                        new DeviceTemplateSchemaValidator(mapper)),
                new SmvDeviceModuleBuilder(),
                new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(),
                new SmvSpecificationBuilder(),
                new SmvModelValidator());
        DeviceVerificationDto sensor = device("temperature_sensor_1", "Temperature Sensor");

        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                1L,
                List.of(sensor),
                List.of(new BoardEnvironmentVariableDto(
                        "temperature", "20", "trusted", "private")),
                List.of(),
                List.of(),
                AttackScenarioDto.none(), true);
        String smv = Files.readString(generated.smvFile().toPath());

        assertTrue(smv.contains("init(trust_temperature) := trusted;"),
                "The board's control-source label must override the template default");
        assertTrue(smv.contains("init(privacy_temperature) := private;"),
                "The board's data-visibility label must override the template default");
    }

    @Test
    @DisplayName("P11: referencing content propagates its classification without mutating the source")
    void contentPrivacy_isStableSourceClassification() {
        DeviceSmvData source = new DeviceSmvData();
        source.setModuleName("Phone_phone_1");
        source.setVarName("phone_1");
        source.setManifest(new DeviceManifest());
        DeviceSmvData.ContentInfo photo = new DeviceSmvData.ContentInfo();
        photo.setName("photo");
        photo.setPrivacy("private");
        source.getContents().add(photo);

        String module = deviceBuilder.build(source, false, true);

        assertTrue(module.contains("FROZENVAR\n\tprivacy_photo: {public, private};"));
        assertTrue(module.contains("init(privacy_photo) := private;"));
        assertFalse(module.contains("next(privacy_photo)"),
                "A rule reference must not rewrite the source content's visibility classification");
    }

    @Test
    @DisplayName("P11: malformed env init is rejected instead of being changed")
    void envDefaultRange_nonNumericInit_rejected() {
        DeviceManifest.InternalVariable mystery = DeviceManifest.InternalVariable.builder()
                .name("mystery").isInside(false).reads(true).build();
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(mystery))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();

        DeviceSmvData smv = buildSmvData("device_1", "Device",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(mystery), manifest);
        smv.getEnvVariables().put("mystery", mystery);
        smv.getCurrentModeStates().put("Mode", "on");

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("device_1");
        dto.setTemplateName("Device");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("device_1", smv);

        SmvGenerationException ex = assertThrows(SmvGenerationException.class, () -> mainBuilder.build(1L, List.of(dto),
                List.of(new BoardEnvironmentVariableDto("mystery", "abc", "trusted", "public")),
                List.of(), map, AttackScenarioDto.none(), false, SmvGenerationContext.noop()));
        assertEquals("TEMPLATE_INVALID", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains("mystery"));
    }

    // ======================== P12: spec key validation and env mapping ========================

    @Test
    @DisplayName("P12: state tuple condition in spec keeps tuple conjunction semantics")
    void specStateTupleCondition_keepsTupleConjunction() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("auto;on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("manual;off").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("thermostat_1", "Thermostat",
                List.of("Mode", "FanMode"),
                Map.of("Mode", List.of("auto", "manual"), "FanMode", List.of("on", "off")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("thermostat_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("thermostat_1");
        cond.setTargetType("state");
        cond.setRelation("=");
        cond.setValue("auto;on");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_state_tuple");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), map, false);
        assertTrue(result.contains("(thermostat_1.Mode=auto & thermostat_1.FanMode=on)"),
                "State tuple should compile to conjunction, got:\n" + result);
    }

    @Test
    @DisplayName("P12: state tuple IN condition in spec splits candidates without breaking tuple segments")
    void specStateTupleInCondition_preservesTupleSegments() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "FanMode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("auto;on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("manual;off").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("thermostat_1", "Thermostat",
                List.of("Mode", "FanMode"),
                Map.of("Mode", List.of("auto", "manual"), "FanMode", List.of("on", "off")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("thermostat_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("thermostat_1");
        cond.setTargetType("state");
        cond.setKey("state");
        cond.setRelation("in");
        cond.setValue("auto;on,manual;off");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_state_tuple_in");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), map, false);
        assertTrue(result.contains("((thermostat_1.Mode=auto & thermostat_1.FanMode=on) | "
                        + "(thermostat_1.Mode=manual & thermostat_1.FanMode=off))"),
                "State tuple IN should preserve semicolon-separated tuple segments, got:\n" + result);
    }

    @Test
    @DisplayName("P12: mode condition in spec compiles to explicit mode comparison")
    void specModeCondition_compilesToModeComparison() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "State"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("home;idle").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("sleep;idle").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("home_mode", "Home Mode",
                List.of("Mode", "State"),
                Map.of("Mode", List.of("home", "sleep"), "State", List.of("idle")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("home_mode", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("home_mode");
        cond.setTargetType("mode");
        cond.setKey("Mode");
        cond.setRelation("=");
        cond.setValue("sleep");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_mode");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), map, false);
        assertTrue(result.contains("home_mode.Mode=sleep"),
                "Mode condition should compile to explicit mode comparison, got:\n" + result);
    }

    @Test
    @DisplayName("P12: safety spec mode condition injects matching mode trust variable")
    void safetySpec_modeCondition_injectsModeTrust() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "State"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("home;idle").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("sleep;idle").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("home_mode", "Home Mode",
                List.of("Mode", "State"),
                Map.of("Mode", List.of("home", "sleep"), "State", List.of("idle")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("home_mode", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("home_mode");
        cond.setTargetType("mode");
        cond.setKey("Mode");
        cond.setRelation("=");
        cond.setValue("sleep");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_safe_mode");
        spec.setTemplateId("7");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), map, false);
        assertTrue(result.contains("home_mode.Mode=sleep"),
                "Safety spec should keep the mode comparison, got:\n" + result);
        assertTrue(result.contains("home_mode.trust_Mode_sleep=untrusted"),
                "Safety spec should inject trust for the concrete mode value, got:\n" + result);
    }

    @Test
    @DisplayName("P12: non-canonical spec device reference is omitted with a readable issue")
    void specNonCanonicalDeviceReference_isSkippedWithReadableIssue() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build()))
                .build();
        DeviceSmvData sensor1 = buildSmvData(
                "sensor_1", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on", "off")),
                List.of(), manifest);
        DeviceSmvData sensor2 = buildSmvData(
                "sensor_2", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on", "off")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", sensor1);
        map.put("sensor_2", sensor2);

        SpecConditionDto a = new SpecConditionDto();
        a.setDeviceId("Sensor");
        a.setTargetType("state");
        a.setRelation("=");
        a.setValue("on");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_ambiguous_device");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(a));

        SmvGenerationContext context = SmvGenerationContext.collecting();
        String result = specBuilder.build(List.of(spec), map, false, context);
        SmvGenerationContext.WarningSnapshot snapshot = context.warningsSnapshot();

        assertFalse(result.contains("CTLSPEC FALSE"));
        assertTrue(result.contains("-- specification skipped:"));
        assertTrue(result.contains("device 'Sensor' not found"));
        assertEquals(1, snapshot.skippedSpecCount());
        assertTrue(snapshot.emittedSpecs().isEmpty());
        assertEquals("SPECIFICATION_SKIPPED", snapshot.generationIssues().get(0).getIssueType());
        assertEquals(ModelGenerationIssueReasonCode.SPEC_UNKNOWN_DEVICE,
                snapshot.generationIssues().get(0).getReasonCode());
        assertEquals("A referenced device is missing or no longer matches this specification.",
                snapshot.generationIssues().get(0).getReason());
    }

    /**
     * The classifier matches on substrings of the thrown message, so ARM ORDER is load-bearing: both
     * variableSource messages necessarily talk about values, and the `contains("value")` arm would otherwise
     * claim the value is outside its domain — sending the user to check a range that was never wrong.
     */
    @Test
    void missingVariableSource_isReportedAsAnUnansweredQuestionNotABadValue() {
        DeviceSmvData smv = buildSensorSmvForIntensity("ts_1", 0, 100);
        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ts_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("ts_1");
        cond.setTargetType("variable");
        cond.setKey("temperature");
        cond.setRelation("GT");
        cond.setValue("30");
        // No variableSource: the author never chose.

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_no_source");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));

        SmvGenerationContext context = SmvGenerationContext.collecting();
        specBuilder.build(List.of(spec), map, false, context);
        SmvGenerationContext.WarningSnapshot snapshot = context.warningsSnapshot();

        assertEquals(1, snapshot.skippedSpecCount());
        assertEquals(ModelGenerationIssueReasonCode.SPEC_VARIABLE_SOURCE_REQUIRED,
                snapshot.generationIssues().get(0).getReasonCode());
    }

    @Test
    void environmentOnADeviceLocalVariable_isNotReportedAsAnUnansweredQuestion() {
        // The author DID choose; the choice is illegal for this declaration. Reporting
        // SPEC_VARIABLE_SOURCE_REQUIRED here would tell them to make a choice they already made.
        DeviceSmvData smv = buildSmvData(
                "ts_1", "Sensor", List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(numericVar("localOnly", true, 0, 100)),
                DeviceManifest.builder()
                        .modes(List.of("Mode"))
                        .internalVariables(List.of(numericVar("localOnly", true, 0, 100)))
                        .workingStates(List.of(
                                DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                        .build());
        smv.getCurrentModeStates().put("Mode", "on");
        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ts_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("ts_1");
        cond.setTargetType("variable");
        cond.setKey("localOnly");
        cond.setVariableSource("environment");
        cond.setRelation("GT");
        cond.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_local_env");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));

        SmvGenerationContext context = SmvGenerationContext.collecting();
        specBuilder.build(List.of(spec), map, false, context);
        SmvGenerationContext.WarningSnapshot snapshot = context.warningsSnapshot();

        assertEquals(1, snapshot.skippedSpecCount());
        assertNotEquals(ModelGenerationIssueReasonCode.SPEC_VARIABLE_SOURCE_REQUIRED,
                snapshot.generationIssues().get(0).getReasonCode(),
                "a chosen-but-illegal reading must not be reported as an unanswered question");
    }

    @Test
    @DisplayName("P12: ambiguous multi-mode state value skips the spec with an actionable issue")
    void specStateValueAmbiguousAcrossModes_skipsWithActionableIssue() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("M1", "M2"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on;off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("off;on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("dev_1", "Device",
                List.of("M1", "M2"),
                Map.of("M1", List.of("on", "off"), "M2", List.of("on", "off")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("dev_1");
        cond.setTargetType("state");
        cond.setRelation("=");
        cond.setValue("on");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_state_ambiguous");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        SmvGenerationContext context = SmvGenerationContext.collecting();
        String result = specBuilder.build(List.of(spec), map, false, context);
        assertFalse(result.contains("CTLSPEC FALSE"),
                "An invalid specification must not become an artificial violation, got:\n" + result);
        assertTrue(result.contains("ambiguous across modes"),
                "Technical diagnostics should remain in the generated model comment, got:\n" + result);
        assertEquals("A selected state matches more than one device mode; choose an unambiguous mode and state.",
                context.warningsSnapshot().generationIssues().get(0).getReason());
        assertEquals(ModelGenerationIssueReasonCode.SPEC_AMBIGUOUS_STATE,
                context.warningsSnapshot().generationIssues().get(0).getReasonCode());
    }

    @Test
    @DisplayName("P12: variable condition maps env variable key to a_var")
    void variableCondition_envKey_mapsToEnvVar() {
        DeviceManifest.InternalVariable tempEnv = numericVar("temperature", false, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(tempEnv))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("sensor_1", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(tempEnv), manifest);
        smv.getEnvVariables().put("temperature", tempEnv);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("sensor_1");
        cond.setTargetType("variable");
        cond.setKey("temperature");
        // Shared declaration; each of these asserts the pool identifier a_temperature.
        cond.setVariableSource("environment");
        cond.setRelation("GT");
        cond.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_env_var");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), map, false);
        assertTrue(result.contains("a_temperature>30"),
                "Environment variable should be referenced as a_temperature, got:\n" + result);
        assertFalse(result.contains("sensor_1.temperature>30"),
                "Should not reference env variable as device internal variable");
    }

    @Test
    @DisplayName("P12: unresolved trust key skips the spec with a device-type explanation")
    void unresolvedTrustKey_skipsWithDeviceTypeExplanation() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of())
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("device_1", "Device",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("device_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("device_1");
        cond.setTargetType("trust");
        cond.setPropertyScope("variable");
        cond.setKey("not_exists");
        cond.setRelation("=");
        cond.setValue("trusted");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_bad_trust");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        SmvGenerationContext context = SmvGenerationContext.collecting();
        String result = specBuilder.build(List.of(spec), map, false, context);
        assertFalse(result.contains("CTLSPEC FALSE"),
                "An invalid trust key must not become an artificial violation, got:\n" + result);
        assertTrue(result.contains("property variable 'not_exists' not found"),
                "The generated model comment should retain technical diagnostics, got:\n" + result);
        assertEquals("A trust or privacy condition references a property that the device type does not define.",
                context.warningsSnapshot().generationIssues().get(0).getReason());
        assertEquals(ModelGenerationIssueReasonCode.SPEC_UNDECLARED_SECURITY_PROPERTY,
                context.warningsSnapshot().generationIssues().get(0).getReasonCode());
    }

    @Test
    @DisplayName("State trust specification checks the active state label in the selected mode")
    void stateTrustCondition_isGuardedByCurrentModeState() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("light_1", "Light",
                List.of("Mode"), Map.of("Mode", List.of("off", "on")),
                List.of(), manifest);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("light_1");
        cond.setTargetType("trust");
        cond.setPropertyScope("state");
        cond.setKey("Mode");
        cond.setRelation("=");
        cond.setValue("untrusted");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_current_state_trust");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), Map.of("light_1", smv), false);

        assertTrue(result.contains("light_1.Mode=off & light_1.trust_Mode_off=untrusted"), result);
        assertTrue(result.contains("light_1.Mode=on & light_1.trust_Mode_on=untrusted"), result);
        assertFalse(result.contains("CTLSPEC AG(light_1.trust_Mode_on=untrusted)"), result);
    }

    @Test
    @DisplayName("P12: spec relation with surrounding spaces is normalized")
    void specRelationWithSpaces_isNormalized() {
        DeviceManifest.InternalVariable tempEnv = numericVar("temperature", false, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(tempEnv))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("sensor_1", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(tempEnv), manifest);
        smv.getEnvVariables().put("temperature", tempEnv);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("sensor_1");
        cond.setTargetType("variable");
        cond.setKey("temperature");
        // Shared declaration; each of these asserts the pool identifier a_temperature.
        cond.setVariableSource("environment");
        cond.setRelation(" GT ");
        cond.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_env_var_rel_spaces");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), map, false);
        assertTrue(result.contains("a_temperature>30"),
                "Relation with spaces should normalize to >, got:\n" + result);
    }

    @Test
    @DisplayName("P12: unsupported relation skips the spec with an actionable issue")
    void unsupportedRelation_skipsWithActionableIssue() {
        DeviceManifest.InternalVariable tempEnv = numericVar("temperature", false, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(tempEnv))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("sensor_1", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(tempEnv), manifest);
        smv.getEnvVariables().put("temperature", tempEnv);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("sensor_1");
        cond.setTargetType("variable");
        cond.setKey("temperature");
        // Shared declaration; each of these asserts the pool identifier a_temperature.
        cond.setVariableSource("environment");
        cond.setRelation("CONTAINS");
        cond.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_bad_relation");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        SmvGenerationContext context = SmvGenerationContext.collecting();
        String result = specBuilder.build(List.of(spec), map, false, context);
        assertFalse(result.contains("CTLSPEC FALSE"),
                "An unsupported relation must not become an artificial violation, got:\n" + result);
        assertTrue(result.contains("unsupported relation"),
                "The generated model comment should retain technical diagnostics, got:\n" + result);
        assertEquals("A condition uses a comparison that is not supported for the selected field.",
                context.warningsSnapshot().generationIssues().get(0).getReason());
        assertEquals(ModelGenerationIssueReasonCode.SPEC_UNSUPPORTED_RELATION,
                context.warningsSnapshot().generationIssues().get(0).getReasonCode());
    }

    @Test
    @DisplayName("P12: safety spec environment variable key maps trust to trust_var")
    void safetySpec_envVariableKey_mapsTrustWithoutPrefix() {
        DeviceManifest.InternalVariable tempEnv = numericVar("temperature", false, 0, 100);
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(tempEnv))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData smv = buildSmvData("sensor_1", "Sensor",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(tempEnv), manifest);
        smv.getEnvVariables().put("temperature", tempEnv);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("sensor_1", smv);

        SpecConditionDto cond = new SpecConditionDto();
        cond.setDeviceId("sensor_1");
        cond.setTargetType("variable");
        cond.setKey("temperature");
        // Shared declaration; each of these asserts the pool identifier a_temperature.
        cond.setVariableSource("environment");
        cond.setRelation("GT");
        cond.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec_safe_env_var");
        spec.setTemplateId("7");
        spec.setAConditions(List.of(cond));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        String result = specBuilder.build(List.of(spec), map, false);
        assertTrue(result.contains("a_temperature>30"),
                "Safety spec should keep env expression on a_temperature, got:\n" + result);
        assertTrue(result.contains("sensor_1.trust_temperature=untrusted"),
                "Safety spec should inject trust_temperature, got:\n" + result);
        assertFalse(result.contains("sensor_1.trust_a_temperature"),
                "Safety spec should never reference trust_a_temperature");
    }

    // ======================== P4-chain: 全空分段回归 ========================

    @Test
    @DisplayName("P4-chain: workingState name ';' (all empty segments) produces no guardless CASE branch — numeric rate path")
    void allEmptySegments_numericRate_noGuardlessBranch() {
        DeviceManifest.InternalVariable temp = numericVar("temperature", false, 0, 50);
        temp.setNaturalChangeRate("1");

        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Power", "Speed"))
                .internalVariables(List.of(temp))
                .impactedVariables(List.of("temperature"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name(";")  // all segments empty
                                .trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("5").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder()
                                .name("on;fast")
                                .trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("3").build()))
                                .build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("t1").startState(";").endState("on;fast").build()))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "TestDev",
                List.of("Power", "Speed"),
                Map.of("Power", List.of("on"), "Speed", List.of("fast")),
                List.of(temp), manifest);
        smv.getImpactedVariables().add("temperature");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("dev_1");
        dto.setTemplateName("TestDev");
        dto.setState("on;fast");

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        assertNotNull(result);
        // The rate is a DEFINE inside the device module, so assert there rather than on main.
        String deviceModule = deviceBuilder.build(smv, false, false);
        // A state whose every mode segment is empty guards nothing, so it must contribute no branch.
        assertFalse(deviceModule.contains(" : 5;"),
                "All-empty-segment state should contribute no rate branch, got:\n" + deviceModule);
        // The valid state "on;fast" still produces its guarded branch.
        assertTrue(deviceModule.contains(" : 3;"),
                "Valid state 'on;fast' should produce its guarded rate branch, got:\n" + deviceModule);
    }

    @Test
    @DisplayName("P4-chain: workingState name ';' (all empty segments) produces no guardless CASE branch — enum value path")
    void allEmptySegments_enumValue_noGuardlessBranch() {
        DeviceManifest.InternalVariable status = DeviceManifest.InternalVariable.builder()
                .name("status").isInside(true).values(List.of("idle", "active", "error")).build();

        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Power", "Speed"))
                .internalVariables(List.of(status))
                .impactedVariables(List.of("status"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name(";")  // all segments empty
                                .trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("status").value("error").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder()
                                .name("on;fast")
                                .trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("status").value("active").build()))
                                .build()))
                .transitions(List.of(DeviceManifest.Transition.builder()
                        .name("t1").startState(";").endState("on;fast").build()))
                .build();

        DeviceSmvData smv = buildSmvData("dev_2", "TestDev2",
                List.of("Power", "Speed"),
                Map.of("Power", List.of("on"), "Speed", List.of("fast")),
                List.of(status), manifest);
        smv.getImpactedVariables().add("status");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_2", smv);

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("dev_2");
        dto.setTemplateName("TestDev2");
        dto.setState("on;fast");

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        // The all-empty state should NOT produce a guardless ": error;" line
        assertFalse(result.matches("(?s).*\\t\\t:\\s*error;.*"),
                "All-empty-segment state should not emit guardless ': error;' branch, got:\n" + result);
        // The valid state "on;fast" should still produce its guarded branch
        assertTrue(result.contains(": active;"),
                "Valid state 'on;fast' should produce its ': active;' value branch, got:\n" + result);
    }

    // ======================== Trust/privacy normalization regression ========================

    @Test
    @DisplayName("Normalization: padded trust/privacy values are trimmed and lowercased in SMV output")
    void paddedTrustPrivacy_normalizedInOutput() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").privacy("public").build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("untrusted").privacy("private").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("toggle").startState("on").endState("off").build()))
                .build();

        DeviceSmvData smv = buildSmvData("ac_1", "AC",
                List.of("Mode"), Map.of("Mode", List.of("on", "off")),
                List.of(), manifest);
        smv.getCurrentModeStates().put("Mode", "on");
        // Simulate padded instance trust/privacy from DTO
        smv.setInstanceStateTrust(" Trusted ");
        smv.getInstanceVariablePrivacy().put("Mode_on", " Public ");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ac_1", smv);

        // Validation should pass (padded values accepted)
        assertDoesNotThrow(() -> validator.validate(map));

        // Build device module and verify lowercase output
        String deviceModule = deviceBuilder.build(smv, false, true);
        // All trust/privacy init values in SMV must be lowercase enum literals
        assertFalse(deviceModule.contains("Trusted"), "SMV must not contain 'Trusted' (uppercase), got:\n" + deviceModule);
        assertFalse(deviceModule.contains("Public"), "SMV must not contain 'Public' (uppercase), got:\n" + deviceModule);
        assertTrue(deviceModule.contains("trusted"), "SMV should contain lowercase 'trusted'");
        assertTrue(deviceModule.contains("public"), "SMV should contain lowercase 'public'");
    }

    @Test
    @DisplayName("Normalization: case-variant trust on same mode_state is NOT flagged as conflict")
    void caseVariantTrust_noConflict() {
        // Two workingStates for the same mode_state "Mode_on" with different-case trust: "Trusted" vs "trusted"
        // After normalization, both become "trusted" — should NOT throw trust/privacy conflict
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode", "Lock"))
                .internalVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on;locked").trust("Trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on;unlocked").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("lock").startState("on;unlocked").endState("on;locked").build()))
                .build();

        DeviceSmvData smv = buildSmvData("lock_1", "SmartLock",
                List.of("Mode", "Lock"),
                Map.of("Mode", List.of("on"), "Lock", List.of("locked", "unlocked")),
                List.of(), manifest);

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("lock_1", smv);

        // Both have Mode_on trust = "Trusted"/"trusted" — after normalize both are "trusted", no conflict
        assertDoesNotThrow(() -> validator.validate(map),
                "Case-variant trust values ('Trusted' vs 'trusted') should not cause a conflict");
    }

    @Test
    @DisplayName("A10: numeric env next() candidates are clamped and keep attack range consistent")
    void numericEnvTransition_attackRangeAndCandidatesClamped() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 15, 35);
        temperature.setNaturalChangeRate("[-2,3]");

        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temperature))
                .impactedVariables(List.of("temperature"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("on")
                                .trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature")
                                        .changeRate("5")
                                        .build()))
                                .build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("setOn")
                        .startState("on")
                        .endState("on")
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("ac_1", "AC",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(temperature), manifest);
        smv.getCurrentModeStates().put("Mode", "on");
        smv.getEnvVariables().put("temperature", temperature);
        smv.getImpactedVariables().add("temperature");

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("ac_1");
        dto.setTemplateName("AC");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ac_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.anyUpToBudget(50), false);

        assertTrue(result.contains("a_temperature: 15..35;"),
                "Attack mode must preserve the environment domain declared by the template, got:\n" + result);
        assertTrue(result.contains("max(15, min(35, a_temperature + ac_1.temperature_rate))"),
                "Candidates should remain clamped to the declared domain, got:\n" + result);
        // "[-2, 3]" admits every integer from -2 to 3, each combined with the device effect and
        // clamped to the declared domain.
        assertTrue(result.contains("TRUE: {max(15, min(35, a_temperature - 2 + ac_1.temperature_rate)), "
                        + "max(15, min(35, a_temperature - 1 + ac_1.temperature_rate)), "
                        + "max(15, min(35, a_temperature + ac_1.temperature_rate)), "
                        + "max(15, min(35, a_temperature + 1 + ac_1.temperature_rate)), "
                        + "max(15, min(35, a_temperature + 2 + ac_1.temperature_rate)), "
                        + "max(15, min(35, a_temperature + 3 + ac_1.temperature_rate))}"),
                "Every declared drift must be admitted and clamped, got:\n" + result);
    }

    @Test
    @DisplayName("A10-boundary: extreme NCR boundary branch candidates are clamped")
    void numericEnvTransition_extremeNcr_boundaryBranchesClamped() {
        // NCR=[-30,30] on range 15..35 — boundary candidates would overflow without clamp
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 15, 35);
        temperature.setNaturalChangeRate("[-30,30]");

        // Device that does NOT impact temperature (no dynamics for temperature)
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temperature))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("on")
                                .trust("trusted")
                                .build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("turnOn")
                        .startState("on")
                        .endState("on")
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "DEV",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(temperature), manifest);
        smv.getCurrentModeStates().put("Mode", "on");
        smv.getEnvVariables().put("temperature", temperature);
        // No impactedVariables → no rate expression → exercises no-rate boundary branches

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("dev_1");
        dto.setTemplateName("DEV");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        // A rate far wider than the domain still yields only in-domain candidates, because every
        // admissible delta is clamped. Clamping is what pins both boundaries, so the generator emits
        // no at-boundary guard: the extreme candidates simply saturate.
        assertFalse(result.contains("a_temperature>=35:"),
                "clamping already pins the upper boundary; no at-boundary branch should remain:\n" + result);
        assertFalse(result.contains("a_temperature<=15:"),
                "clamping already pins the lower boundary; no at-boundary branch should remain:\n" + result);
        for (int delta = -30; delta <= 30; delta++) {
            String shifted = delta == 0 ? "a_temperature"
                    : delta > 0 ? "a_temperature + " + delta
                    : "a_temperature - " + Math.abs(delta);
            assertTrue(result.contains("max(15, min(35, " + shifted + "))"),
                    "declared rate [-30, 30] must admit and clamp delta " + delta + ", got:\n" + result);
        }

        temperature.setNaturalChangeRate("[2,3]");
        String increasingResult = mainBuilder.build(
                1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);
        // "[2, 3]" excludes 0, so the value must rise every step: no stutter candidate is emitted.
        assertTrue(increasingResult.contains("TRUE: {max(15, min(35, a_temperature + 2)), "
                        + "max(15, min(35, a_temperature + 3))}"),
                "a mandatory rise must admit exactly its declared drifts, got:\n"
                        + increasingResult);
    }

    @Test
    @DisplayName("A10-boundary: impacted environment combines every declared rate before clamping")
    void numericEnvTransition_withRate_extremeNcr_combinesDeclaredRatesAtBoundaries() {
        // NCR=[-30,30] on range 15..35, WITH a device that impacts temperature
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 15, 35);
        temperature.setNaturalChangeRate("[-30,30]");

        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temperature))
                .impactedVariables(List.of("temperature"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("on")
                                .trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature")
                                        .changeRate("5")
                                        .build()))
                                .build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("setOn")
                        .startState("on")
                        .endState("on")
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("ac_1", "AC",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(temperature), manifest);
        smv.getCurrentModeStates().put("Mode", "on");
        smv.getEnvVariables().put("temperature", temperature);
        smv.getImpactedVariables().add("temperature");

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("ac_1");
        dto.setTemplateName("AC");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ac_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        assertFalse(result.contains("a_temperature=35-(ac_1.temperature_rate)"),
                "declared dynamics should not be replaced by a special upper-boundary branch, got:\n" + result);
        assertFalse(result.contains("a_temperature=15-(ac_1.temperature_rate)"),
                "declared dynamics should not be replaced by a special lower-boundary branch, got:\n" + result);

        // Every state combines each admissible drift with the device effect, then clamps.
        for (int delta : new int[]{-30, -1, 0, 1, 30}) {
            String shifted = delta == 0 ? "a_temperature"
                    : delta > 0 ? "a_temperature + " + delta
                    : "a_temperature - " + Math.abs(delta);
            assertTrue(result.contains(
                            "max(15, min(35, " + shifted + " + ac_1.temperature_rate))"),
                    "delta " + delta + " must combine with the device effect, got:\n" + result);
        }
    }

    @Test
    @DisplayName("A10-boundary: explicit zero natural rate uses only the declared device effect")
    void numericEnvTransition_withRate_zeroNcr_usesOnlyDeclaredDeviceEffect() {
        DeviceManifest.InternalVariable temperature = numericVar("temperature", false, 15, 35);
        temperature.setNaturalChangeRate("0");

        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(temperature))
                .impactedVariables(List.of("temperature"))
                .workingStates(List.of(DeviceManifest.WorkingState.builder()
                        .name("on").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("setOn").startState("on").endState("on").build()))
                .build();

        DeviceSmvData smv = buildSmvData("ac_1", "AC",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(temperature), manifest);
        smv.getCurrentModeStates().put("Mode", "on");
        smv.getEnvVariables().put("temperature", temperature);
        smv.getImpactedVariables().add("temperature");

        DeviceVerificationDto dto = device("ac_1", "AC");
        dto.setState("on");
        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("ac_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        assertFalse(result.contains("a_temperature=35-(ac_1.temperature_rate)"), result);
        assertFalse(result.contains("a_temperature=15-(ac_1.temperature_rate)"), result);
        assertFalse(result.contains("toint(a_temperature) - 1 + ac_1.temperature_rate"), result);
        assertFalse(result.contains("a_temperature + 1 + ac_1.temperature_rate"), result);
        // NaturalChangeRate=0 declares no independent drift, so the device effect stands alone and
        // no hidden [-1, 1] term is layered on top.
        assertTrue(result.contains("TRUE: {max(15, min(35, a_temperature + ac_1.temperature_rate))}"),
                "only the declared device effect should remain, got:\n" + result);
    }

    @Test
    @DisplayName("A10-internal: internal variable boundary branches are clamped with extreme NCR")
    void internalVariable_extremeNcr_boundaryBranchesClamped() {
        // Internal variable "power" 0..10 with NCR=[-8,8], no impacted rate
        DeviceManifest.InternalVariable power = numericVar("power", true, 0, 10);
        power.setNaturalChangeRate("[-8,8]");

        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(power))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("on")
                                .trust("trusted")
                                .build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("turnOn")
                        .startState("on")
                        .endState("on")
                        .build()))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "DEV",
                List.of("Mode"), Map.of("Mode", List.of("on")),
                List.of(power), manifest);
        smv.getCurrentModeStates().put("Mode", "on");

        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName("dev_1");
        dto.setTemplateName("DEV");
        dto.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);

        String result = mainBuilder.build(1L, List.of(dto), List.of(), map, AttackScenarioDto.none(), false);

        // A declared interval constrains the per-step change, so every integer in [-8, 8] is an
        // admissible next value and each candidate is clamped to the declared domain. Clamping alone
        // pins both boundaries -- verified against NuSMV -- so no at-boundary branch is emitted:
        // saturating candidates keep the value inside 0..10 while still allowing it to hold or leave.
        for (int delta = -8; delta <= 8; delta++) {
            String shifted = delta == 0 ? "dev_1.power"
                    : delta > 0 ? "dev_1.power + " + delta
                    : "dev_1.power - " + Math.abs(delta);
            String candidate = "max(0, min(10, " + shifted + "))";
            assertTrue(result.contains(candidate),
                    "declared rate [-8, 8] must admit " + candidate + ", got:\n" + result);
        }
        assertFalse(result.contains("dev_1.power>=10:"),
                "clamping already pins the upper boundary; no at-boundary branch should remain");
        assertFalse(result.contains("dev_1.power<=0:"),
                "clamping already pins the lower boundary; no at-boundary branch should remain");
    }

    @Test
    @DisplayName("mainBuilder: local numeric WorkingState.Dynamics works without ImpactedVariables")
    void mainBuilder_localNumericDynamicsWithoutImpactedVariables_updatesLocalVariable() {
        DeviceManifest.InternalVariable var = numericVar("waterTemperature", true, 0, 100);
        var.setNaturalChangeRate("[-1, 1]");

        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("MachineState"))
                .initState("on")
                .internalVariables(List.of(var))
                .impactedVariables(List.of())
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("waterTemperature").changeRate("1").build()))
                                .build()))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "Water Heater",
                List.of("MachineState"),
                Map.of("MachineState", List.of("off", "on")),
                List.of(var),
                manifest);

        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("dev_1");
        device.setTemplateName("Water Heater");
        device.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);

        String result = mainBuilder.build(1L, List.of(device), List.of(), map, AttackScenarioDto.none(), false);

        assertFalse(result.contains("next(dev_1.waterTemperature_rate)"),
                "Local-only dynamics should not create environment-impact rate variables, got:\n" + result);
        // [-1, 1] has no interior, so its candidate set is unchanged: each admissible drift combines
        // with the state's own ChangeRate of +1.
        assertTrue(result.contains("dev_1.MachineState=on: {max(0, min(100, dev_1.waterTemperature - 1 + 1)), max(0, min(100, dev_1.waterTemperature + 1)), max(0, min(100, dev_1.waterTemperature + 1 + 1))};"),
                "WorkingState.Dynamics.ChangeRate should update local numeric variable directly, got:\n" + result);

        var.setNaturalChangeRate("[2,3]");
        String sameDirection = mainBuilder.build(
                1L, List.of(device), List.of(), map, AttackScenarioDto.none(), false);
        // "[2, 3]" excludes 0, so each declared drift combines with the state's ChangeRate of +1 and
        // there is no dynamic-only candidate: the natural change is mandatory.
        assertTrue(sameDirection.contains("dev_1.MachineState=on: {max(0, min(100, dev_1.waterTemperature + 2 + 1)), max(0, min(100, dev_1.waterTemperature + 3 + 1))};"),
                "Local dynamics must admit exactly the declared drifts, got:\n"
                        + sameDirection);
        assertTrue(sameDirection.contains("TRUE: {max(0, min(100, dev_1.waterTemperature + 2)), "
                        + "max(0, min(100, dev_1.waterTemperature + 3))}"),
                "The local fallback must admit every declared drift and the stutter, got:\n"
                        + sameDirection);
    }

    // ======================== NPE guard: ImpactedVariables without InternalVariables ========================

    @Test
    @DisplayName("mainBuilder: ImpactedVariables present but InternalVariables null — should not NPE and should generate _rate transition")
    void mainBuilder_impactedVarsWithoutInternalVars_generatesRate() {
        // Manifest with ImpactedVariables but NO InternalVariables (null)
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Power"))
                .initState("on")
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("3").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("0").build()))
                                .build()))
                .internalVariables(null)       // explicitly null
                .impactedVariables(List.of("temperature"))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "Heater",
                List.of("Power"),
                Map.of("Power", List.of("on", "off")),
                List.of(),   // empty variables list (matches null internalVariables)
                manifest);
        smv.getImpactedVariables().add("temperature");
        smv.setCurrentState("on");
        smv.getCurrentModeStates().put("Power", "on");

        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("dev_1");
        device.setTemplateName("Heater");
        device.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);

        // Must not throw, and the impact rate must exist as a DEFINE over the current state so the
        // device's effect reaches the environment in the same step rather than one step later.
        String result = assertDoesNotThrow(() ->
                mainBuilder.build(1L, List.of(device), List.of(), map, AttackScenarioDto.none(), false));
        assertNotNull(result);

        String deviceModule = deviceBuilder.build(smv, false, false);
        assertTrue(deviceModule.contains("temperature_rate := case"),
                "Expected a temperature_rate definition for the ImpactedVariable, got:\n" + deviceModule);
    }

    @Test
    @DisplayName("Non-integer ChangeRate is rejected instead of silently omitting one state effect")
    void mainBuilder_nonIntegerChangeRate_rejected() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Power"))
                .initState("on")
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("humidity").changeRate("abc").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("humidity").changeRate("2").build()))
                                .build()))
                .internalVariables(List.of(
                        numericVar("temp", true, 0, 50),
                        numericVar("humidity", false, 0, 100)))
                .impactedVariables(List.of("humidity"))
                .build();

        DeviceSmvData smv = buildSmvData("dev_1", "Heater",
                List.of("Power"),
                Map.of("Power", List.of("on", "off")),
                List.of(numericVar("temp", true, 0, 50), numericVar("humidity", false, 0, 100)),
                manifest);
        smv.getImpactedVariables().add("humidity");
        smv.setCurrentState("on");
        smv.getCurrentModeStates().put("Power", "on");

        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("dev_1");
        device.setTemplateName("Heater");
        device.setState("on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("dev_1", smv);

        SmvGenerationException exception = assertThrows(SmvGenerationException.class, () ->
                validator.validate(map));
        assertEquals("TEMPLATE_INVALID", exception.getErrorCategory());
        assertTrue(exception.getMessage().contains("non-integer ChangeRate 'abc'"), exception.getMessage());
    }

    @Test
    @DisplayName("Boolean WorkingState Dynamics executes Value without a numeric rate variable")
    void mainBuilder_booleanDynamicsUsesDiscreteValue() {
        DeviceManifest.InternalVariable occupied = DeviceManifest.InternalVariable.builder()
                .name("occupied")
                .isInside(true)
                .falsifiableWhenCompromised(false)
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Power"))
                .initState("off")
                .internalVariables(List.of(occupied))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("occupied").value("FALSE").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("occupied").value("TRUE").build()))
                                .build()))
                .build();
        DeviceSmvData smv = buildSmvData("presence_1", "Presence",
                List.of("Power"), Map.of("Power", List.of("off", "on")), List.of(occupied), manifest);
        smv.getCurrentModeStates().put("Power", "off");
        DeviceVerificationDto dto = device("presence_1", "Presence");
        dto.setState("off");

        validator.validate(Map.of("presence_1", smv));
        String result = mainBuilder.build(
                1L, List.of(dto), List.of(), Map.of("presence_1", smv), AttackScenarioDto.none(), false);

        assertTrue(result.contains("presence_1.Power=off: FALSE;"), result);
        assertTrue(result.contains("presence_1.Power=on: TRUE;"), result);
        assertTrue(result.contains("TRUE: presence_1.occupied;"),
                "A local boolean value must hold when no declared evolution applies, got:\n" + result);
        assertFalse(result.contains("occupied_rate"), result);
    }

    @Test
    @DisplayName("Local enum variable stutters when no declared evolution applies")
    void mainBuilder_localEnumWithoutEvolutionStutters() {
        DeviceManifest.InternalVariable phase = DeviceManifest.InternalVariable.builder()
                .name("phase")
                .isInside(true)
                .falsifiableWhenCompromised(false)
                .values(List.of("idle", "running"))
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .internalVariables(List.of(phase))
                .build();
        DeviceSmvData smv = buildSmvData(
                "device_1", "Device", List.of(), Map.of(), List.of(phase), manifest);
        DeviceVerificationDto dto = device("device_1", "Device");

        validator.validate(Map.of("device_1", smv));
        String result = mainBuilder.build(
                1L, List.of(dto), List.of(), Map.of("device_1", smv), AttackScenarioDto.none(), false);

        assertTrue(result.contains("TRUE: device_1.phase;"), result);
        assertFalse(result.contains("TRUE: {idle, running};"), result);
    }

    @Test
    @DisplayName("Stateful sensor exposes read-only state trust and privacy labels")
    void deviceBuilder_statefulSensorDeclaresStateLabels() {
        DeviceManifest manifest = DeviceManifest.builder()
                .modes(List.of("Detection"))
                .initState("clear")
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder()
                                .name("clear").trust("trusted").privacy("public").build(),
                        DeviceManifest.WorkingState.builder()
                                .name("motion").trust("untrusted").privacy("private").build()))
                .apis(List.of())
                .build();
        DeviceSmvData sensor = buildSmvData(
                "motion_1", "Stateful Motion Sensor",
                List.of("Detection"), Map.of("Detection", List.of("clear", "motion")),
                List.of(), manifest);
        sensor.setCurrentState("clear");
        sensor.getCurrentModeStates().put("Detection", "clear");

        String module = deviceBuilder.build(sensor, true, true);

        assertTrue(module.contains("trust_Detection_clear: {untrusted, trusted};"), module);
        assertTrue(module.contains("trust_Detection_motion: {untrusted, trusted};"), module);
        assertTrue(module.contains("privacy_Detection_motion: {private, public};"), module);
        assertTrue(module.contains("init(trust_Detection_clear) := trusted;"), module);
        assertTrue(module.contains("init(trust_Detection_motion) := untrusted;"), module);
        assertTrue(module.contains("init(privacy_Detection_motion) := private;"), module);
        assertFalse(module.contains("next(trust_Detection_motion)"),
                "A sensor with no command API keeps template-authored state labels read-only");
    }

    /** Builds the lock/sensor pair used by the unresolvable-action tests. */
    private Map<String, DeviceSmvData> lockAndSensorMap() {
        DeviceManifest lockManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("locked").trust("trusted").build(),
                        DeviceManifest.WorkingState.builder().name("unlocked").trust("trusted").build()))
                .apis(List.of(DeviceManifest.API.builder()
                        .name("unlock").startState("locked").endState("unlocked").build()))
                .build();
        DeviceSmvData lock = buildSmvData("lock_1", "Lock", List.of("Mode"),
                Map.of("Mode", List.of("locked", "unlocked")), List.of(), lockManifest);
        lock.setSensor(false);
        lock.setInstanceStateTrust("trusted");
        lock.getCurrentModeStates().put("Mode", "locked");

        DeviceManifest sensorManifest = DeviceManifest.builder()
                .modes(List.of("Mode"))
                .internalVariables(List.of(numericVar("temperature", true, 0, 100)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("on").trust("trusted").build()))
                .build();
        DeviceSmvData sensor = buildSmvData("sensor_1", "Sensor", List.of("Mode"),
                Map.of("Mode", List.of("on")),
                List.of(numericVar("temperature", true, 0, 100)), sensorManifest);
        sensor.setSensor(true);
        sensor.setInstanceStateTrust("trusted");
        sensor.getCurrentModeStates().put("Mode", "on");

        Map<String, DeviceSmvData> map = new LinkedHashMap<>();
        map.put("lock_1", lock);
        map.put("sensor_1", sensor);
        return map;
    }

    private List<DeviceVerificationDto> lockAndSensorDtos() {
        DeviceVerificationDto lockDto = device("lock_1", "Lock");
        lockDto.setState("locked");
        DeviceVerificationDto sensorDto = device("sensor_1", "Sensor");
        sensorDto.setState("on");
        return List.of(lockDto, sensorDto);
    }

    private RuleDto ruleCommanding(String action) {
        RuleDto.Condition cond = new RuleDto.Condition();
        cond.setDeviceName("sensor_1");
        cond.setTargetType("variable");
        cond.setAttribute("temperature");
        cond.setRelation(">");
        cond.setValue("30");

        RuleDto.Command cmd = new RuleDto.Command();
        cmd.setDeviceName("lock_1");
        cmd.setAction(action);

        RuleDto rule = new RuleDto();
        rule.setId(1L);
        rule.setConditions(List.of(cond));
        rule.setCommand(cmd);
        return rule;
    }

    @Test
    @DisplayName("A rule condition attribute with surrounding whitespace still resolves")
    void paddedConditionAttribute_resolvesLikeAdmissionValidation() {
        RuleDto rule = ruleCommanding("unlock");
        rule.getConditions().get(0).setAttribute("  temperature  ");

        SmvGenerationContext context = SmvGenerationContext.collecting();
        String smv = mainBuilder.build(1L, lockAndSensorDtos(), List.of(rule),
                lockAndSensorMap(), AttackScenarioDto.none(), false, context);

        assertEquals(0, context.warningsSnapshot().disabledRuleCount(),
                "Admission resolves the attribute trimmed, so generation must too, got:\n" + smv);
        assertTrue(smv.contains("sensor_1.temperature"), smv);
    }

    @Test
    @DisplayName("An unresolvable rule command action is reported, not silently dropped")
    void unresolvableCommandAction_isRecordedAsDisabledRule() {
        SmvGenerationContext context = SmvGenerationContext.collecting();
        String smv = mainBuilder.build(1L, lockAndSensorDtos(), List.of(ruleCommanding("open sesame")),
                lockAndSensorMap(), AttackScenarioDto.none(), false, context);

        SmvGenerationContext.WarningSnapshot snapshot = context.warningsSnapshot();
        assertEquals(1, snapshot.disabledRuleCount(),
                "A rule absent from the model must be counted, got:\n" + smv);
        assertEquals(ModelGenerationIssueReasonCode.RULE_UNRESOLVABLE_COMMAND_ACTION,
                snapshot.generationIssues().get(0).getReasonCode());
        assertEquals("RULE_DISABLED", snapshot.generationIssues().get(0).getIssueType());
    }

    @Test
    @DisplayName("A rule command action with surrounding whitespace still resolves to its API")
    void paddedCommandAction_resolvesLikeAdmissionValidation() {
        SmvGenerationContext context = SmvGenerationContext.collecting();
        String smv = mainBuilder.build(1L, lockAndSensorDtos(), List.of(ruleCommanding("  unlock  ")),
                lockAndSensorMap(), AttackScenarioDto.none(), false, context);

        assertEquals(0, context.warningsSnapshot().disabledRuleCount(),
                "Admission compares trimmed, so generation must too, got:\n" + smv);
        assertTrue(smv.contains("unlocked"), smv);
    }

    // ======================== guard probes ========================

    @Test
    @DisplayName("Guard probes define the components of the guard the rule's transition reads")
    void guardProbes_renderTheComponentsOfTheRulesGuard() {
        RuleDto rule = ruleCommanding("unlock");
        RuleDto.Condition hot = rule.getConditions().get(0);

        String smv = mainBuilder.build(1L, lockAndSensorDtos(), null, List.of(rule), lockAndSensorMap(),
                AttackScenarioDto.none(), false, SmvGenerationContext.noop(), List.of(
                        GuardProbe.ofRule(0, GuardProbe.Kind.RULE_BASE, rule),
                        GuardProbe.ofRule(1, GuardProbe.Kind.RULE_CONDITIONS, rule),
                        GuardProbe.ofCondition(2, GuardProbe.Kind.CONDITION, hot),
                        GuardProbe.ofCondition(3, GuardProbe.Kind.OPERAND, hot)));

        assertTrue(smv.contains("\nDEFINE"
                + "\n\tiot_verify_guard_probe_0 := lock_1.Mode=locked;"
                + "\n\tiot_verify_guard_probe_1 := sensor_1.temperature>30;"
                + "\n\tiot_verify_guard_probe_2 := sensor_1.temperature>30;"
                + "\n\tiot_verify_guard_probe_3 := sensor_1.temperature;"), smv);
        // The probes decompose exactly the guard the transition reads: its conditions, then its base terms.
        assertTrue(smv.contains("sensor_1.temperature>30 & lock_1.Mode=locked: unlocked;"), smv);
    }

    @Test
    @DisplayName("Under attack a rule-base probe carries the command-delivery guard")
    void guardProbes_ruleBaseUnderAttackIncludesDeliveryGuard() {
        RuleDto rule = ruleCommanding("unlock");

        String smv = mainBuilder.build(1L, lockAndSensorDtos(), null, List.of(rule), lockAndSensorMap(),
                AttackScenarioDto.anyUpToBudget(10), false, SmvGenerationContext.noop(),
                List.of(GuardProbe.ofRule(0, GuardProbe.Kind.RULE_BASE, rule)));

        assertTrue(smv.contains("\n\tiot_verify_guard_probe_0 := lock_1.Mode=locked & lock_1.is_attack=FALSE & "
                + SmvConstants.AUTOMATION_LINK_ATTACK_PREFIX + "0=FALSE;"), smv);
    }

    @Test
    @DisplayName("A guard probe that cannot be rendered is omitted, not defined as a constant")
    void guardProbes_unrenderableProbesAreOmitted() {
        RuleDto rule = ruleCommanding("unlock");
        // Equal by value, but not a rule of this model: the model holds its rules by identity.
        RuleDto copy = ruleCommanding("unlock");
        RuleDto.Condition unknownDevice = RuleDto.Condition.builder().deviceName("ghost_1")
                .attribute("temperature").targetType("variable").relation(">").value("30").build();

        String smv = mainBuilder.build(1L, lockAndSensorDtos(), null, List.of(rule), lockAndSensorMap(),
                AttackScenarioDto.none(), false, SmvGenerationContext.noop(), List.of(
                        GuardProbe.ofRule(0, GuardProbe.Kind.RULE_BASE, copy),
                        GuardProbe.ofRule(1, GuardProbe.Kind.RULE_CONDITIONS, copy),
                        GuardProbe.ofCondition(2, GuardProbe.Kind.CONDITION, unknownDevice),
                        GuardProbe.ofCondition(3, GuardProbe.Kind.OPERAND, unknownDevice),
                        GuardProbe.ofRule(4, GuardProbe.Kind.RULE_BASE, rule)));

        assertFalse(smv.contains("iot_verify_guard_probe_0"), smv);
        assertFalse(smv.contains("iot_verify_guard_probe_1"), smv);
        assertFalse(smv.contains("iot_verify_guard_probe_2"), smv);
        assertFalse(smv.contains("iot_verify_guard_probe_3"), smv);
        assertTrue(smv.contains("\n\tiot_verify_guard_probe_4 := lock_1.Mode=locked;"), smv);
    }

    @Test
    @DisplayName("A guard probe name joins the main namespace and fails closed on a collision")
    void guardProbes_collidingWithADeviceName_throwsCategorizedException() {
        RuleDto rule = ruleCommanding("unlock");
        Map<String, DeviceSmvData> map = lockAndSensorMap();
        DeviceSmvData lock = map.remove("lock_1");
        lock.setVarName("iot_verify_guard_probe_0");
        map.put("iot_verify_guard_probe_0", lock);
        rule.getCommand().setDeviceName("iot_verify_guard_probe_0");
        DeviceVerificationDto lockDto = device("iot_verify_guard_probe_0", "Lock");
        lockDto.setState("locked");

        SmvGenerationException ex = assertThrows(SmvGenerationException.class,
                () -> mainBuilder.build(1L, List.of(lockDto, lockAndSensorDtos().get(1)), null, List.of(rule), map,
                        AttackScenarioDto.none(), false, SmvGenerationContext.noop(),
                        List.of(GuardProbe.ofRule(0, GuardProbe.Kind.RULE_BASE, rule))));

        assertEquals("INVALID_BUILDER_INPUT", ex.getErrorCategory());
        assertTrue(ex.getMessage().contains("main namespace"), ex.getMessage());
        assertTrue(ex.getMessage().contains("iot_verify_guard_probe_0"), ex.getMessage());
    }
}
