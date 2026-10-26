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
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NusmvEnvironmentPoolTest {

    private static final long USER_ID = 7L;

    @Test
    void mergeWithDefaults_usesLowerBoundOrFirstEnumValueForMissingPoolValues() {
        DeviceManifest.InternalVariable temperature = envNumber("temperature", 15, 35);
        DeviceManifest.InternalVariable presence = DeviceManifest.InternalVariable.builder()
                .name("presence")
                .isInside(false).reads(true)
                .values(List.of("away", "home"))
                .trust("untrusted")
                .privacy("private")
                .build();

        Map<String, DeviceSmvData> smvMap = new LinkedHashMap<>();
        smvMap.put("sensor_1", smv("sensor_1", "Sensor", Map.of(
                "temperature", temperature,
                "presence", presence)));

        Map<String, BoardEnvironmentVariableDto> merged = NusmvEnvironmentPool
                .mergeWithDefaults(List.of(), smvMap)
                .stream()
                .collect(Collectors.toMap(BoardEnvironmentVariableDto::getName, item -> item));

        assertEquals("15", merged.get("temperature").getValue());
        assertEquals("trusted", merged.get("temperature").getTrust());
        assertEquals("public", merged.get("temperature").getPrivacy());
        assertEquals("away", merged.get("presence").getValue());
        assertEquals("untrusted", merged.get("presence").getTrust());
        assertEquals("private", merged.get("presence").getPrivacy());
    }

    @Test
    void mergeWithDefaults_treatsUnlabeledExternalEnvironmentAsUntrusted() {
        DeviceManifest.InternalVariable externalReading = DeviceManifest.InternalVariable.builder()
                .name("externalReading")
                .isInside(false).reads(true)
                .lowerBound(0)
                .upperBound(10)
                .build();
        Map<String, DeviceSmvData> smvMap = Map.of(
                "sensor_1", smv("sensor_1", "Sensor", Map.of("externalReading", externalReading)));

        BoardEnvironmentVariableDto merged = NusmvEnvironmentPool
                .mergeWithDefaults(List.of(), smvMap)
                .get(0);

        assertEquals("untrusted", merged.getTrust());
        assertEquals("public", merged.getPrivacy());
    }

    @Test
    void expandDevices_appliesEnvironmentPoolOnlyToTemplatesThatDeclareReadPermission() {
        DeviceManifest.InternalVariable temperature = envNumber("temperature", 15, 35);
        Map<String, DeviceSmvData> smvMap = new LinkedHashMap<>();
        smvMap.put("sensor_1", smv("sensor_1", "Temperature Sensor", Map.of("temperature", temperature)));
        smvMap.put("light_1", smv("light_1", "Light", Map.of()));

        List<DeviceVerificationDto> expanded = NusmvEnvironmentPool.expandDevices(
                List.of(device("sensor_1", "Temperature Sensor"), device("light_1", "Light")),
                List.of(new BoardEnvironmentVariableDto("temperature", "28", "untrusted", "private")),
                smvMap);

        DeviceVerificationDto sensor = expanded.stream()
                .filter(device -> "sensor_1".equals(device.getVarName()))
                .findFirst()
                .orElseThrow();
        DeviceVerificationDto light = expanded.stream()
                .filter(device -> "light_1".equals(device.getVarName()))
                .findFirst()
                .orElseThrow();

        assertEquals("28", sensor.getVariables().get(0).getValue());
        assertEquals("untrusted", sensor.getVariables().get(0).getTrust());
        assertEquals("private", sensor.getPrivacies().get(0).getPrivacy());
        assertNull(light.getVariables());
        assertNull(light.getPrivacies());
    }

    @Test
    void mergeWithDefaults_collectsImpactedEnvironmentEvenWhenReadableMapIsNull() {
        DeviceManifest.InternalVariable temperature = envNumber("temperature", 15, 35);
        DeviceSmvData actuator = smv("ac_1", "Air Conditioner", Map.of());
        actuator.setEnvVariables(null);
        actuator.setImpactedEnvironmentVariables(Map.of("temperature", temperature));

        Map<String, DeviceSmvData> smvMap = new LinkedHashMap<>();
        smvMap.put("ac_1", actuator);

        List<BoardEnvironmentVariableDto> merged = NusmvEnvironmentPool.mergeWithDefaults(List.of(), smvMap);

        assertEquals(1, merged.size());
        assertEquals("temperature", merged.get(0).getName());
        assertEquals("15", merged.get(0).getValue());
    }

    @Test
    void expandedEnvironmentPoolInitializesSharedSmvVariable() throws Exception {
        DeviceManifest.InternalVariable temperature = envNumber("temperature", 15, 35);
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Temperature Sensor")
                .internalVariables(List.of(temperature))
                .build();
        SmvGenerator generator = generatorFor(manifest);
        List<DeviceVerificationDto> rawDevices = List.of(device("sensor_1", "Temperature Sensor"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);

        List<BoardEnvironmentVariableDto> merged = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("temperature", "28", "trusted", "public")),
                rawMap);
        List<DeviceVerificationDto> expanded = NusmvEnvironmentPool.expandDevices(rawDevices, merged, rawMap);
        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                USER_ID, expanded, merged, List.of(), List.of(), AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);

        String smv = Files.readString(generated.smvFile().toPath());

        assertTrue(smv.contains("a_temperature: 15..35;"), smv);
        assertTrue(smv.contains("init(a_temperature) := 28;"), smv);
        assertTrue(smv.contains("sensor_1.temperature := a_temperature;"), smv);
    }

    @Test
    void impactedOnlyDeviceUsesOwnEnvironmentDomainWithoutReadPermission() throws Exception {
        DeviceManifest.InternalVariable temperature = envNumber("temperature", 15, 35);
        DeviceManifest sensorDomain = DeviceManifest.builder()
                .name("Temperature Sensor")
                .internalVariables(List.of(temperature))
                .build();
        DeviceManifest airConditioner = DeviceManifest.builder()
                .name("Air Conditioner")
                .modes(List.of("MachineState"))
                .initState("cool")
                .impactedVariables(List.of("temperature"))
                .internalVariables(List.of(impactNumber("temperature", 15, 35)))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("cool").trust("trusted")
                                .privacy("public")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("-2").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted")
                                .privacy("public").build()))
                .build();

        SmvGenerator generator = generatorFor(Map.of(
                "Air Conditioner", airConditioner,
                "Temperature Sensor", sensorDomain));
        List<DeviceVerificationDto> rawDevices = List.of(device("ac_1", "Air Conditioner", "cool"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);

        List<BoardEnvironmentVariableDto> merged = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("temperature", "28", "trusted", "public")),
                rawMap);
        List<DeviceVerificationDto> expanded = NusmvEnvironmentPool.expandDevices(rawDevices, merged, rawMap);
        assertNull(expanded.get(0).getVariables());
        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                USER_ID, expanded, merged, List.of(), List.of(), AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);

        String smv = Files.readString(generated.smvFile().toPath());

        assertEquals("28", merged.get(0).getValue());
        assertTrue(smv.contains("a_temperature: 15..35;"), smv);
        assertTrue(smv.contains("init(a_temperature) := 28;"), smv);
        assertTrue(smv.contains("temperature_rate := case"), smv);
        assertTrue(smv.contains("a_temperature + ac_1.temperature_rate"), smv);
        assertTrue(!smv.contains("ac_1.temperature := a_temperature;"), smv);
    }

    /**
     * A pool label must reach the model for a value a device only *writes*.
     *
     * <p>`applyEnvironmentPoolLabels` used to key off `envVariables`, which is the rule/spec
     * source-capability set and therefore excludes affect-only declarations (`Reads: false`). The
     * device module nevertheless declares `trust_<name>`/`privacy_<name>` for every declared variable
     * and initialises them from these instance overrides, so the labels were silently dropped for
     * exactly the variables a device only writes — and the Environment Pool renders those rows as
     * editable, because they enter the panel through `ImpactedVariables`. A user could set a source
     * label, watch it persist, and get a model built from the template value instead.
     *
     * <p>Asserting the *opposite* of the template values is what makes this falsifiable: the manifest
     * below declares `untrusted`/`public`, so seeing `trusted`/`private` in the model can only come
     * from the pool.
     */
    @Test
    void environmentPoolLabelsReachAffectOnlySharedVariables() throws Exception {
        DeviceManifest.InternalVariable affectOnly = impactNumber("temperature", 15, 35);
        affectOnly.setTrust("untrusted");
        affectOnly.setPrivacy("public");
        DeviceManifest airConditioner = DeviceManifest.builder()
                .name("Air Conditioner")
                .modes(List.of("MachineState"))
                .initState("cool")
                .impactedVariables(List.of("temperature"))
                .internalVariables(List.of(affectOnly))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("cool").trust("trusted")
                                .privacy("public")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("-2").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted")
                                .privacy("public").build()))
                .build();

        SmvGenerator generator = generatorFor(Map.of("Air Conditioner", airConditioner));
        List<DeviceVerificationDto> rawDevices = List.of(device("ac_1", "Air Conditioner", "cool"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);

        // The pool asserts the opposite of the manifest on both dimensions.
        List<BoardEnvironmentVariableDto> merged = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("temperature", "28", "trusted", "private")),
                rawMap);
        List<DeviceVerificationDto> expanded = NusmvEnvironmentPool.expandDevices(rawDevices, merged, rawMap);
        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                USER_ID, expanded, merged, List.of(), List.of(), AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);

        String smv = Files.readString(generated.smvFile().toPath());
        assertTrue(smv.contains("init(trust_temperature) := trusted;"),
                "the pool's trust label must initialise the affect-only variable's label: " + smv);
        assertTrue(smv.contains("init(privacy_temperature) := private;"),
                "the pool's privacy label must initialise the affect-only variable's label: " + smv);
    }

    /**
     * Invariant 13 (§9 of shared-value-semantics.md): every device declaring a shared name carries the
     * same labels, because the pool is their only writer.
     *
     * <p>Nothing pinned this. The suite asserted pool-over-*template* on a single device twice, so
     * `applyEnvironmentPoolLabels` could have written one device and skipped the rest — or been keyed
     * off the narrower read-capability set — and stayed green. A whole-file `contains` cannot see the
     * difference either: one matching module satisfies it. So this asserts the label per module and
     * counts the modules, which is the only shape that fails when the fan-out is partial.
     *
     * <p>The two devices declare the shared value through the two different entry points — one
     * read-capable, one affect-only — and use different template names so their generated module names
     * differ (a module name is `<template>_<instance>`, so one template would yield one module).
     */
    @Test
    void environmentPoolLabelsAreIdenticalAcrossEveryDeclaringDevice() throws Exception {
        DeviceManifest.InternalVariable reader = envNumber("temperature", 15, 35);
        reader.setTrust("untrusted");
        reader.setPrivacy("public");
        DeviceManifest thermometer = DeviceManifest.builder()
                .name("Temperature Sensor")
                .internalVariables(List.of(reader))
                .build();

        DeviceManifest.InternalVariable affectOnly = impactNumber("temperature", 15, 35);
        affectOnly.setTrust("untrusted");
        affectOnly.setPrivacy("public");
        DeviceManifest airConditioner = DeviceManifest.builder()
                .name("Air Conditioner")
                .modes(List.of("MachineState"))
                .initState("cool")
                .impactedVariables(List.of("temperature"))
                .internalVariables(List.of(affectOnly))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("cool").trust("trusted")
                                .privacy("public")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("-2").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted")
                                .privacy("public").build()))
                .build();

        SmvGenerator generator = generatorFor(Map.of(
                "Temperature Sensor", thermometer,
                "Air Conditioner", airConditioner));
        List<DeviceVerificationDto> rawDevices = List.of(
                device("sensor_1", "Temperature Sensor"),
                device("ac_1", "Air Conditioner", "cool"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);

        // Both manifests declare untrusted/public, so trusted/private can only come from the pool.
        List<BoardEnvironmentVariableDto> merged = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("temperature", "28", "trusted", "private")),
                rawMap);
        List<DeviceVerificationDto> expanded = NusmvEnvironmentPool.expandDevices(rawDevices, merged, rawMap);
        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                USER_ID, expanded, merged, List.of(), List.of(), AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);

        String smv = Files.readString(generated.smvFile().toPath());
        Map<String, String> modules = deviceModules(smv);
        assertEquals(2, modules.size(), "expected one module per device: " + modules.keySet());
        modules.forEach((moduleName, body) -> {
            assertTrue(body.contains("init(trust_temperature) := trusted;"),
                    "module '" + moduleName + "' must carry the pool's trust label: " + body);
            assertTrue(body.contains("init(privacy_temperature) := private;"),
                    "module '" + moduleName + "' must carry the pool's privacy label: " + body);
        });
    }

    /** Splits generated SMV into its device modules, keyed by module name; `main` is excluded. */
    private static Map<String, String> deviceModules(String smv) {
        Map<String, String> modules = new LinkedHashMap<>();
        String[] sections = smv.split("(?m)^MODULE\\s+");
        for (String section : sections) {
            String trimmed = section.strip();
            if (trimmed.isEmpty()) continue;
            String header = trimmed.lines().findFirst().orElse("").strip();
            // A generated comment can contain the word MODULE, so require a legal NuSMV identifier.
            if (!header.matches("[A-Za-z_][A-Za-z0-9_]*") || "main".equals(header)) continue;
            modules.put(header, trimmed);
        }
        return modules;
    }

    /**
     * A label property over an affect-only value must reach NuSMV, not just pass admission.
     *
     * <p>Admission is capability-blind here on purpose (`NusmvRequestValidator.validatePropertyReference`):
     * the device module declares `trust_<name>`/`privacy_<name>` for every declared variable, so
     * narrowing it would reject a formula the generator emits and NuSMV decides. That argument only
     * holds if generation agrees, and nothing pinned the *whole* chain — a reviewer reading
     * `SmvSpecificationBuilder` reported it as narrowing admission and throwing at generation, which
     * would have turned such a specification into a skipped spec instead of a verdict.
     *
     * <p>It does not: its own `internalVariable` lookup is equally blind. This asserts the emitted
     * formula rather than the absence of an error, because a skipped spec is silent in the SMV text.
     */
    @Test
    void labelSpecificationOverAffectOnlyValueIsEmittedRatherThanSkipped() throws Exception {
        DeviceManifest.InternalVariable affectOnly = impactNumber("temperature", 15, 35);
        affectOnly.setTrust("untrusted");
        affectOnly.setPrivacy("public");
        DeviceManifest airConditioner = DeviceManifest.builder()
                .name("Air Conditioner")
                .modes(List.of("MachineState"))
                .initState("cool")
                .impactedVariables(List.of("temperature"))
                .internalVariables(List.of(affectOnly))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("cool").trust("trusted")
                                .privacy("public")
                                .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                        .variableName("temperature").changeRate("-2").build()))
                                .build(),
                        DeviceManifest.WorkingState.builder().name("off").trust("trusted")
                                .privacy("public").build()))
                .build();

        SmvGenerator generator = generatorFor(Map.of("Air Conditioner", airConditioner));
        List<DeviceVerificationDto> rawDevices = List.of(device("ac_1", "Air Conditioner", "cool"));
        Map<String, DeviceSmvData> rawMap = generator.buildDeviceSmvMap(USER_ID, rawDevices);
        List<BoardEnvironmentVariableDto> merged = NusmvEnvironmentPool.mergeWithDefaults(
                List.of(new BoardEnvironmentVariableDto("temperature", "28", "untrusted", "public")),
                rawMap);
        List<DeviceVerificationDto> expanded = NusmvEnvironmentPool.expandDevices(rawDevices, merged, rawMap);

        SpecConditionDto labelOfAffectOnly = new SpecConditionDto();
        labelOfAffectOnly.setDeviceId("ac_1");
        labelOfAffectOnly.setTargetType("privacy");
        labelOfAffectOnly.setPropertyScope("variable");
        labelOfAffectOnly.setKey("temperature");
        labelOfAffectOnly.setRelation("=");
        labelOfAffectOnly.setValue("public");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("affect_only_label");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setTemplateLabel("Affect-only label property");
        spec.setDevices(List.of());
        spec.setAConditions(List.of(labelOfAffectOnly));

        SmvGenerator.GenerateResult generated = generator.generateWithEnvironment(
                USER_ID, expanded, merged, List.of(), List.of(spec), AttackScenarioDto.none(), true,
                SmvGenerator.GeneratePurpose.VERIFICATION);

        assertEquals(0, generated.skippedSpecCount(),
                "a label property over an affect-only value must not be skipped");
        assertTrue(generated.generationIssues().isEmpty(),
                () -> "generation must report no issue for it: " + generated.generationIssues());
        // The generator's own compact form, not NuSMV's echoed spacing.
        String smv = Files.readString(generated.smvFile().toPath());
        assertTrue(smv.contains("CTLSPEC AG(ac_1.privacy_temperature=public)"),
                "the label property must be emitted as a checkable formula: " + smv);
    }

    @Test
    void unrelatedTemplateCannotSupplyMissingImpactDomain() throws Exception {
        DeviceManifest sensorDomain = DeviceManifest.builder()
                .name("Temperature Sensor")
                .internalVariables(List.of(envNumber("temperature", 15, 35)))
                .build();
        DeviceManifest airConditioner = DeviceManifest.builder()
                .name("Air Conditioner")
                .modes(List.of("MachineState"))
                .initState("cool")
                .impactedVariables(List.of("temperature"))
                .workingStates(List.of(DeviceManifest.WorkingState.builder()
                        .name("cool")
                        .trust("trusted")
                        .privacy("public")
                        .dynamics(List.of(DeviceManifest.Dynamic.builder()
                                .variableName("temperature")
                                .changeRate("-2")
                                .build()))
                        .build()))
                .build();
        SmvGenerator generator = generatorFor(Map.of(
                "Air Conditioner", airConditioner,
                "Temperature Sensor", sensorDomain));
        List<DeviceVerificationDto> devices = List.of(device("ac_1", "Air Conditioner", "cool"));

        cn.edu.nju.Iot_Verify.exception.BadRequestException exception = assertThrows(
                cn.edu.nju.Iot_Verify.exception.BadRequestException.class, () ->
                generator.generateWithEnvironment(
                        USER_ID, devices,
                        List.of(new BoardEnvironmentVariableDto("temperature", "28", "trusted", "public")),
                        List.of(), List.of(), AttackScenarioDto.none(), true,
                        SmvGenerator.GeneratePurpose.VERIFICATION));

        assertTrue(exception.getMessage().contains("unknown or non-writable"), exception.getMessage());
    }

    private static DeviceManifest.InternalVariable envNumber(String name, int lower, int upper) {
        return DeviceManifest.InternalVariable.builder()
                .name(name)
                .isInside(false).reads(true)
                .falsifiableWhenCompromised(true)
                .lowerBound(lower)
                .upperBound(upper)
                .naturalChangeRate("0")
                .trust("trusted")
                .privacy("public")
                .build();
    }

    /** An affect-only shared declaration: one array, read capability explicitly withheld. */
    private static DeviceManifest.InternalVariable impactNumber(String name, int lower, int upper) {
        return DeviceManifest.InternalVariable.builder()
                .name(name)
                .isInside(false)
                .reads(false)
                .falsifiableWhenCompromised(false)
                .lowerBound(lower)
                .upperBound(upper)
                .naturalChangeRate("0")
                .trust("trusted")
                .privacy("public")
                .build();
    }

    private static DeviceSmvData smv(String varName,
                                     String templateName,
                                     Map<String, DeviceManifest.InternalVariable> envVariables) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        smv.setTemplateName(templateName);
        smv.setModuleName(templateName.replace(" ", "") + "_" + varName);
        smv.setModes(List.of());
        smv.setVariables(List.copyOf(envVariables.values()));
        smv.setEnvVariables(new LinkedHashMap<>(envVariables));
        smv.setManifest(DeviceManifest.builder()
                .name(templateName)
                .internalVariables(List.copyOf(envVariables.values()))
                .build());
        return smv;
    }

    private static DeviceVerificationDto device(String varName, String templateName) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName(varName);
        device.setTemplateName(templateName);
        return device;
    }

    private static DeviceVerificationDto device(String varName, String templateName, String state) {
        DeviceVerificationDto device = device(varName, templateName);
        device.setState(state);
        return device;
    }

    private static SmvGenerator generatorFor(DeviceManifest manifest) throws Exception {
        return generatorFor(Map.of("Temperature Sensor", manifest));
    }

    private static SmvGenerator generatorFor(Map<String, DeviceManifest> manifests) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);
        when(templateService.findTemplateByName(anyLong(), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1, String.class);
            DeviceManifest manifest = manifests.get(name);
            if (manifest == null) {
                return Optional.empty();
            }
            return Optional.of(DeviceTemplatePo.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .name(name)
                    .manifestJson(objectMapper.writeValueAsString(manifest))
                    .defaultTemplate(true)
                    .build());
        });
        SmvModelValidator validator = new SmvModelValidator();
        return new SmvGenerator(
                new DeviceSmvDataFactory(objectMapper, templateService, validator,
                        new DeviceTemplateSchemaValidator(objectMapper)),
                new SmvDeviceModuleBuilder(),
                new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(),
                new SmvSpecificationBuilder(),
                validator);
    }
}
