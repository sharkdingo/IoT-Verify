package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvRuleCommentWriter;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvSpecificationBuilder;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplexApartmentScenarioNusmvTest {

    private static final long USER_ID = 1L;

    @Test
    void complexApartmentScenario_generatesFaithfulSmvAndViolationTrace() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for this integration smoke test");

        SmvGenerator generator = buildGenerator();
        List<DeviceVerificationDto> devices = buildDevices();
        List<RuleDto> rules = buildRules();
        List<SpecificationDto> specs = buildSpecs();

        SmvGenerator.GenerateResult generated = generator.generate(
                USER_ID, devices, rules, specs, AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        String smv = Files.readString(generated.smvFile().toPath());

        assertTrue(smv.contains("next(camera_1.MachineState) :="));
        assertTrue(smv.contains("motion_1.motion=active & camera_1.MachineState=on: takingphoto;"));
        assertTrue(smv.contains("next(alarm_1.AlertState) :="));
        assertTrue(smv.contains("motion_1.motion=active: siren;"));
        assertTrue(smv.contains("next(thermostat_1.ThermostatMode) :="));
        assertTrue(smv.contains("temp_sensor_1.temperature>28: cool;"));
        assertTrue(smv.contains("(window_1.WindowState=open) & !((temp_sensor_1.temperature>28)): off;"));
        assertTrue(smv.contains("CTLSPEC AG !(camera_1.MachineState=takingphoto)"));
        assertTrue(smv.contains("CTLSPEC AG((home_mode_1.Mode=sleep) -> AX(!(camera_1.MachineState=takingphoto)))"));
        assertTrue(smv.contains("CTLSPEC AG((window_1.WindowState=open) -> AF(thermostat_1.ThermostatMode=off))"));

        NusmvExecutor.NusmvResult result = FormalCheckTestSupport.checkProperties(buildExecutor(nusmvPath), generated);
        assertTrue(result.isSuccess(), () -> "NuSMV failed: " + result.getErrorMessage());
        assertTrue(result.hasAnyViolation(), "The conflict scenario should produce at least one violation");
        assertTrue(result.getSpecResults().stream()
                        .anyMatch(spec -> spec.isPassed()
                                && spec.getSpecExpression() != null
                                && spec.getSpecExpression().contains("alarm_1.AlertState = siren")),
                "The motion-to-alarm response spec should pass after rule guards read the current source state");
        assertTrue(result.getSpecResults().stream()
                        .anyMatch(spec -> !spec.isPassed()
                                && spec.getSpecExpression() != null
                                && spec.getSpecExpression().contains("camera_1.MachineState = takingphoto")),
                "The privacy conflict should remain visible as a camera-photo violation");

        NusmvExecutor.SpecCheckResult failed = result.getSpecResults().stream()
                .filter(spec -> !spec.isPassed())
                .findFirst()
                .orElseThrow();
        assertTrue(failed.getCounterexample() != null && !failed.getCounterexample().isBlank());

        List<TraceStateDto> states = new SmvTraceParser()
                .parseCounterexampleStates(failed.getCounterexample(), generated.deviceSmvMap());
        assertFalse(states.isEmpty(), "Counterexample should be parseable for animation/history replay");
        assertTrue(states.stream()
                .flatMap(state -> state.getDevices().stream())
                .anyMatch(device -> isState(device, "camera_1", "takingphoto")
                        || isState(device, "thermostat_1", "cool")));
    }

    private SmvGenerator buildGenerator() throws Exception {
        SmvModelValidator modelValidator = new SmvModelValidator();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);
        when(templateService.findTemplateByName(anyLong(), anyString())).thenAnswer(invocation -> {
            String templateName = invocation.getArgument(1, String.class);
            Path manifestPath = Path.of("src/main/resources/deviceTemplate", templateName + ".json");
            if (!Files.exists(manifestPath)) {
                return Optional.empty();
            }
            return Optional.of(DeviceTemplatePo.builder()
                    .id(100L)
                    .userId(USER_ID)
                    .name(templateName)
                    .manifestJson(Files.readString(manifestPath))
                    .defaultTemplate(true)
                    .build());
        });

        ObjectMapper objectMapper = new ObjectMapper();
        DeviceSmvDataFactory factory = new DeviceSmvDataFactory(
                objectMapper, templateService, modelValidator, new DeviceTemplateSchemaValidator(objectMapper));
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

    private static List<DeviceVerificationDto> buildDevices() {
        return List.of(
                device("motion_1", "Motion Detector", "",
                        vars(new VariableStateDto("motion", "active", "trusted")), List.of()),
                device("temp_sensor_1", "Temperature Sensor", "",
                        vars(new VariableStateDto("temperature", "29", "trusted")), List.of()),
                device("window_1", "Window", "open",
                        vars(new VariableStateDto("contact", "open", "trusted")), List.of()),
                device("camera_1", "Camera", "on", List.of(),
                        List.of(new PrivacyStateDto("MachineState_takingphoto", "private"))),
                device("alarm_1", "Alarm", "off", List.of(), List.of()),
                device("light_1", "Light", "off", List.of(), List.of()),
                device("thermostat_1", "Thermostat", "auto;auto",
                        vars(new VariableStateDto("temperature", "29", "trusted"),
                                new VariableStateDto("thermostatOperatingState", "idle", "trusted"),
                                new VariableStateDto("thermostatSetpoint", "24", "trusted")),
                        List.of()),
                device("home_mode_1", "Home Mode", "sleep;idle", List.of(), List.of()),
                device("shade_1", "Window Shade", "open", List.of(), List.of())
        );
    }

    private static List<RuleDto> buildRules() {
        return List.of(
                rule("IF temperature > 28 THEN thermostat cool",
                        command("thermostat_1", "cool"),
                        condition("temp_sensor_1", "temperature", "variable", ">", "28")),
                rule("IF window is open THEN thermostat off",
                        command("thermostat_1", "off"),
                        condition("window_1", "state", "state", "=", "open")),
                rule("IF motion active THEN camera takes photo",
                        command("camera_1", "take photo"),
                        condition("motion_1", "motion", "variable", "=", "active")),
                rule("IF motion active THEN alarm siren",
                        command("alarm_1", "siren"),
                        condition("motion_1", "motion", "variable", "=", "active")),
                rule("IF motion active THEN light on",
                        command("light_1", "on"),
                        condition("motion_1", "motion", "variable", "=", "active")),
                rule("IF sleep mode THEN close shade",
                        command("shade_1", "close"),
                        condition("home_mode_1", "Mode", "mode", "=", "sleep"))
        );
    }

    private static List<SpecificationDto> buildSpecs() {
        return List.of(
                spec("spec-never-camera-photo", "3", "Never camera photo",
                        List.of(specCondition("a1", "a", "camera_1", "Camera", "state", "state", "=", "taking photo")),
                        List.of(), List.of()),
                spec("spec-sleep-no-photo", "4", "Sleep mode implies no photo",
                        List.of(),
                        List.of(specCondition("if1", "if", "home_mode_1", "Home Mode", "mode", "Mode", "=", "sleep")),
                        List.of(specCondition("then1", "then", "camera_1", "Camera", "state", "state", "!=", "taking photo"))),
                spec("spec-window-open-eventually-off", "5", "Open window should turn thermostat off",
                        List.of(),
                        List.of(specCondition("if2", "if", "window_1", "Window", "state", "state", "=", "open")),
                        List.of(specCondition("then2", "then", "thermostat_1", "Thermostat", "mode", "ThermostatMode", "=", "off"))),
                spec("spec-motion-eventually-alarm", "5", "Motion should trigger alarm",
                        List.of(),
                        List.of(specCondition("if3", "if", "motion_1", "Motion Detector", "variable", "motion", "=", "active")),
                        List.of(specCondition("then3", "then", "alarm_1", "Alarm", "mode", "AlertState", "=", "siren")))
        );
    }

    private static DeviceVerificationDto device(String varName,
                                                String templateName,
                                                String state,
                                                List<VariableStateDto> variables,
                                                List<PrivacyStateDto> privacies) {
        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName(varName);
        dto.setTemplateName(templateName);
        dto.setState(state);
        dto.setCurrentStateTrust("trusted");
        dto.setVariables(variables);
        dto.setPrivacies(privacies);
        return dto;
    }

    private static List<VariableStateDto> vars(VariableStateDto... values) {
        return new ArrayList<>(List.of(values));
    }

    private static RuleDto rule(String name, RuleDto.Command command, RuleDto.Condition... conditions) {
        return RuleDto.builder()
                .ruleString(name)
                .conditions(List.of(conditions))
                .command(command)
                .build();
    }

    private static RuleDto.Condition condition(String deviceName,
                                               String attribute,
                                               String targetType,
                                               String relation,
                                               String value) {
        return RuleDto.Condition.builder()
                .deviceName(deviceName)
                .attribute(attribute)
                .targetType(targetType)
                .relation(relation)
                .value(value)
                .build();
    }

    private static RuleDto.Command command(String deviceName, String action) {
        return RuleDto.Command.builder()
                .deviceName(deviceName)
                .action(action)
                .build();
    }

    private static SpecificationDto spec(String id,
                                         String templateId,
                                         String label,
                                         List<SpecConditionDto> aConditions,
                                         List<SpecConditionDto> ifConditions,
                                         List<SpecConditionDto> thenConditions) {
        SpecificationDto dto = new SpecificationDto();
        dto.setId(id);
        dto.setName("Safety requirement");
        dto.setTemplateId(templateId);
        dto.setTemplateLabel(label);
        dto.setAConditions(aConditions);
        dto.setIfConditions(ifConditions);
        dto.setThenConditions(thenConditions);
        dto.setDevices(List.of());
        return dto;
    }

    private static SpecConditionDto specCondition(String id,
                                                  String side,
                                                  String deviceId,
                                                  String deviceLabel,
                                                  String targetType,
                                                  String key,
                                                  String relation,
                                                  String value) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId(id);
        condition.setSide(side);
        condition.setDeviceId(deviceId);
        condition.setDeviceLabel(deviceLabel);
        condition.setTargetType(targetType);
        condition.setKey(key);
        // This scenario's only variable condition is the antecedent of a response specification ("motion
        // should trigger alarm"), and the rule it is asserting about fires on the device's own reading. So
        // it asks `reported`: the two questions coincide in the clean model but only this one lines up with
        // what the automation actually reacts to.
        if ("variable".equals(targetType)) {
            condition.setVariableSource("reported");
        }
        condition.setRelation(relation);
        condition.setValue(value);
        return condition;
    }

    private static boolean isState(TraceDeviceDto device, String id, String state) {
        return id.equals(device.getDeviceId()) && state.equals(device.getState());
    }

    private static String resolveNusmvPath() {
        String env = System.getenv("NUSMV_PATH");
        if (env != null && !env.isBlank()) {
            return env;
        }
        Path bundled = Path.of("D:/NuSMV/NuSMV-2.7.1-win64/NuSMV-2.7.1-win64/bin/NuSMV.exe");
        return Files.exists(bundled) ? bundled.toString() : "NuSMV";
    }
}
