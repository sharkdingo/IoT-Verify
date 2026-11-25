package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvModelValidator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvRuleCommentWriter;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvSpecificationBuilder;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultDeviceTemplateFlowTest {

    private static final long USER_ID = 11L;
    private static final Path TEMPLATE_DIR = Path.of("src/main/resources/deviceTemplate");

    @TestFactory
    Stream<DynamicTest> everyDefaultTemplateCanBecomeOneBoardDevice() throws Exception {
        Map<String, DeviceManifest> templates = loadDefaultTemplates();
        SmvGenerator generator = generatorFor(templates);

        return templates.keySet().stream()
                .sorted()
                .map(templateName -> DynamicTest.dynamicTest(templateName, () -> {
                    DeviceManifest manifest = templates.get(templateName);
                    DeviceVerificationDto rawDevice = device(
                            "dev_" + DeviceSmvDataFactory.toVarName(templateName).toLowerCase(),
                            templateName,
                            initialState(manifest));
                    Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, List.of(rawDevice));
                    List<BoardEnvironmentVariableDto> environment =
                            NusmvEnvironmentPool.mergeWithDefaults(List.of(), rawMap);

                    SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                            USER_ID,
                            List.of(rawDevice),
                            environment,
                            List.of(),
                            List.of(),
                            AttackScenarioDto.none(), true,
                            SmvGenerator.GeneratePurpose.VERIFICATION);
                    String smv = Files.readString(generated.smvFile().toPath());

                    DeviceSmvData smvData = generated.deviceSmvMap().get(rawDevice.getVarName());
                    assertNotNull(smvData, "SMV data should be present for " + templateName);
                    assertTrue(smv.contains("MODULE main"), smv);
                    assertTrue(smv.contains(smvData.getVarName() + ": " + smvData.getModuleName() + ";"), smv);

                    for (String envName : smvData.getEnvVariables().keySet()) {
                        assertTrue(smv.contains("a_" + envName + ":"), smv);
                        assertTrue(smv.contains(smvData.getVarName() + "." + envName + " := a_" + envName + ";"), smv);
                    }
                    for (String impacted : smvData.getImpactedVariables()) {
                        assertTrue(smvData.getImpactedEnvironmentVariables().containsKey(impacted),
                                "Impacted variable '" + impacted + "' must have a template-library environment domain");
                        assertTrue(smv.contains("a_" + impacted + ":"),
                                "Impacted environment '" + impacted + "' should be declared in main");
                    }
                }));
    }

    @Test
    void temperatureSensorAndAirConditionerShareOneEnvironmentPoolVariable() throws Exception {
        Map<String, DeviceManifest> templates = loadDefaultTemplates();
        SmvGenerator generator = generatorFor(templates);
        List<DeviceVerificationDto> rawDevices = List.of(
                device("temp_sensor_1", "Temperature Sensor", null),
                device("ac_1", "Air Conditioner", "cool"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);
        List<BoardEnvironmentVariableDto> environment = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("temperature", "30", "trusted", "public")),
                rawMap);
        List<RuleDto> rules = List.of(rule(
                condition("temp_sensor_1", "temperature", "variable", ">", "28"),
                command("ac_1", "cool")));

        String smv = generateSmv(generator, rawDevices, environment, rules);

        assertTrue(smv.contains("a_temperature: 0..100;"), smv);
        assertTrue(smv.contains("init(a_temperature) := 30;"), smv);
        assertTrue(smv.contains("temp_sensor_1.temperature := a_temperature;"), smv);
        assertFalse(smv.contains("ac_1.temperature := a_temperature;"), smv);
        assertTrue(smv.contains("temperature_rate := case"), smv);
        assertTrue(smv.contains("a_temperature + ac_1.temperature_rate"), smv);
        assertTrue(smv.contains("temp_sensor_1.temperature > 28"), smv);
        assertTrue(smv.contains(": cool;"), smv);
    }

    @Test
    void airQualityMonitorAndPurifierShareTwoEnvironmentPoolVariables() throws Exception {
        Map<String, DeviceManifest> templates = loadDefaultTemplates();
        SmvGenerator generator = generatorFor(templates);
        List<DeviceVerificationDto> rawDevices = List.of(
                device("aq_1", "Air Quality Monitor", null),
                device("purifier_1", "Air Purifier", "on"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);
        List<BoardEnvironmentVariableDto> environment = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("airQuality", "80", "trusted", "private"),
                        new BoardEnvironmentVariableDto("carbonDioxide", "70", "trusted", "private")),
                rawMap);

        String smv = generateSmv(generator, rawDevices, environment, List.of());

        assertTrue(smv.contains("a_airQuality: 0..100;"), smv);
        assertTrue(smv.contains("a_carbonDioxide: 0..100;"), smv);
        assertTrue(smv.contains("aq_1.airQuality := a_airQuality;"), smv);
        assertTrue(smv.contains("aq_1.carbonDioxide := a_carbonDioxide;"), smv);
        assertFalse(smv.contains("purifier_1.airQuality := a_airQuality;"), smv);
        assertTrue(smv.contains("airQuality_rate := case"), smv);
        assertTrue(smv.contains("carbonDioxide_rate := case"), smv);
    }

    @Test
    void illuminanceSensorAndLightShowReadVsImpactSeparation() throws Exception {
        Map<String, DeviceManifest> templates = loadDefaultTemplates();
        SmvGenerator generator = generatorFor(templates);
        List<DeviceVerificationDto> rawDevices = List.of(
                device("lux_1", "Illuminance Sensor", null),
                device("light_1", "Light", "on"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);
        List<BoardEnvironmentVariableDto> environment = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("illuminance", "40", "trusted", "public")),
                rawMap);

        String smv = generateSmv(generator, rawDevices, environment, List.of());

        assertTrue(smv.contains("a_illuminance: 0..100;"), smv);
        // The sensor reads the shared value, so it gets a read mirror.
        assertTrue(smv.contains("lux_1.illuminance := a_illuminance;"), smv);
        // The light only affects it. Both devices now declare the value in the SAME array; the
        // difference is the explicit Reads flag, not which array the declaration lives in. So the
        // light gets no read mirror while still contributing its effect.
        assertFalse(smv.contains("light_1.illuminance := a_illuminance;"), smv);
        assertTrue(smv.contains("illuminance_rate := case"), smv);
        assertTrue(smv.contains("a_illuminance + light_1.illuminance_rate"), smv);
    }

    /**
     * One array, one declaration per shared value, with read capability stated rather than implied.
     *
     * <p>An affect-only declaration used to require repeating the whole domain in a second array
     * ({@code EnvironmentDomains}) whose only job was to withhold rule/spec source capability — so the
     * user expressed one boolean by choosing between two array shapes, and three arrays encoded a
     * 2x2 truth table. This pins that the merged form still separates the two capabilities.
     */
    @Test
    void anAffectOnlySharedDeclarationNeedsNoSecondArray() throws Exception {
        DeviceManifest light = loadDefaultTemplates().get("Light");

        DeviceManifest.InternalVariable illuminance = light.getInternalVariables().stream()
                .filter(variable -> "illuminance".equals(variable.getName()))
                .findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, illuminance.getIsInside(), "shared, not device-local");
        assertEquals(Boolean.FALSE, illuminance.getReads(), "affects it without reading it");
        assertTrue(light.getImpactedVariables().contains("illuminance"),
                "and still declares that it writes it");

        // Every shared declaration in every bundled template states its read capability explicitly;
        // none is left to a default, because a default would grant capability from a missing field.
        loadDefaultTemplates().forEach((name, manifest) -> {
            for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
                if (Boolean.FALSE.equals(variable.getIsInside())) {
                    assertNotNull(variable.getReads(),
                            name + "." + variable.getName() + " must declare Reads explicitly");
                } else {
                    assertNull(variable.getReads(),
                            name + "." + variable.getName() + " is device-local; Reads is meaningless");
                }
            }
        });
    }

    /**
     * An affect-only shared value is not a rule or specification condition source.
     *
     * <p>The rule builder used to offer every shared declaration, and the request validator accepted
     * them, but the generator emits no read mirror for {@code Reads=false} — so the condition
     * referenced a value the device never observes and the user could not tell a working rule from a
     * hollow one. Both surfaces now agree: the UI hides it and this boundary refuses it.
     */
    @Test
    void anAffectOnlySharedValueIsNotAvailableAsAConditionSource() throws Exception {
        DeviceManifest light = loadDefaultTemplates().get("Light");
        DeviceManifest.InternalVariable illuminance = light.getInternalVariables().stream()
                .filter(variable -> "illuminance".equals(variable.getName()))
                .findFirst().orElseThrow();

        assertEquals(Boolean.FALSE, illuminance.getReads(),
                "Light affects illuminance without reading it");
        assertTrue(light.getImpactedVariables().contains("illuminance"));

        // The generator's read-capability set is what a condition source may draw from, and it must
        // exclude this declaration exactly as the rule builder now does.
        Map<String, DeviceManifest> templates = loadDefaultTemplates();
        SmvGenerator generator = generatorFor(templates);
        DeviceVerificationDto device = device("light_1", "Light", "on");
        Map<String, DeviceSmvData> map = generator.buildDeviceSmvMap(USER_ID, List.of(device));

        assertFalse(map.get("light_1").getEnvVariables().containsKey("illuminance"),
                "an affect-only declaration must not appear in the read-capability set");
        assertTrue(map.get("light_1").getImpactedEnvironmentVariables().containsKey("illuminance"),
                "but its domain and effect must still be modelled");
    }

    private static String generateSmv(SmvGenerator generator,
                                      List<DeviceVerificationDto> devices,
                                      List<BoardEnvironmentVariableDto> environment,
                                      List<RuleDto> rules) throws Exception {
        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                USER_ID,
                devices,
                environment,
                rules,
                List.of(),
                AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);
        return Files.readString(generated.smvFile().toPath());
    }

    private static Map<String, DeviceManifest> loadDefaultTemplates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, DeviceManifest> result = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(TEMPLATE_DIR)) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                DeviceManifest manifest = mapper.readValue(file.toFile(), DeviceManifest.class);
                String templateName = file.getFileName().toString().replaceFirst("\\.json$", "");
                manifest.setName(templateName);
                result.put(templateName, manifest);
            }
        }
        return result;
    }

    private static SmvGenerator generatorFor(Map<String, DeviceManifest> templates) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);
        when(templateService.findTemplateByName(anyLong(), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1, String.class);
            DeviceManifest manifest = templates.get(name);
            if (manifest == null) {
                return Optional.empty();
            }
            return Optional.of(DeviceTemplatePo.builder()
                    .id((long) name.hashCode())
                    .userId(USER_ID)
                    .name(name)
                    .manifestJson(mapper.writeValueAsString(manifest))
                    .defaultTemplate(true)
                    .build());
        });
        SmvModelValidator validator = new SmvModelValidator();
        return new SmvGenerator(
                new DeviceSmvDataFactory(mapper, templateService, validator,
                        new DeviceTemplateSchemaValidator(mapper)),
                new SmvDeviceModuleBuilder(),
                new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(),
                new SmvSpecificationBuilder(),
                validator);
    }

    private static DeviceVerificationDto device(String varName, String templateName, String state) {
        DeviceVerificationDto dto = new DeviceVerificationDto();
        dto.setVarName(varName);
        dto.setTemplateName(templateName);
        dto.setState(state);
        dto.setCurrentStateTrust("trusted");
        return dto;
    }

    private static String initialState(DeviceManifest manifest) {
        if (manifest == null || manifest.getModes() == null || manifest.getModes().isEmpty()) {
            return null;
        }
        return manifest.getInitState();
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

    private static RuleDto rule(RuleDto.Condition condition, RuleDto.Command command) {
        return RuleDto.builder()
                .ruleString("IF " + condition.getDeviceName() + "." + condition.getAttribute()
                        + " " + condition.getRelation() + " " + condition.getValue()
                        + " THEN " + command.getDeviceName() + "." + command.getAction())
                .conditions(List.of(condition))
                .command(command)
                .build();
    }
}
