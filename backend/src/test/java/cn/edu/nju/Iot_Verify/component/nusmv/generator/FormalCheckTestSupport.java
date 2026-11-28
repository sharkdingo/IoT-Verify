package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.board.PortableSceneTestImport;
import cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.RuleFixer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize.FaultLocalizer;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.ConditionAdjustStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.ParameterAdjustStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.RemoveRulesFixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvRuleCommentWriter;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvSpecificationBuilder;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared model fixtures, solver setup and verdict alignment for formal tests. */
public final class FormalCheckTestSupport {
    static final long USER_ID = 1L;

    private FormalCheckTestSupport() {}

    public static String assumeAvailableNusmvPath(String reason) {
        String nusmvPath = System.getenv("NUSMV_PATH");
        if (nusmvPath == null || nusmvPath.isBlank()) {
            Path bundled = Path.of("D:/NuSMV/NuSMV-2.7.1-win64/NuSMV-2.7.1-win64/bin/NuSMV.exe");
            nusmvPath = Files.exists(bundled) ? bundled.toString() : "NuSMV";
        }
        assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)), reason);
        return nusmvPath;
    }

    static SmvGenerator buildGenerator(ObjectMapper objectMapper, JsonNode scene) throws JsonProcessingException {
        Map<String, String> manifests = new LinkedHashMap<>();
        for (JsonNode template : scene.path("templates")) {
            manifests.put(template.path("name").asText(),
                    objectMapper.writeValueAsString(template.path("manifest")));
        }
        return buildTemplateBackedModel(objectMapper, USER_ID, name -> 100L, manifests::get).generator();
    }

    public record TemplateBackedModel(SmvGenerator generator, DeviceSmvDataFactory factory) {}

    public static TemplateBackedModel buildTemplateBackedModel(
            long userId, Map<String, DeviceManifest> manifests, ToLongFunction<String> templateId) {
        ObjectMapper mapper = new ObjectMapper();
        return buildTemplateBackedModel(mapper, userId, templateId, name -> {
            DeviceManifest manifest = manifests.get(name);
            return manifest == null ? null : mapper.writeValueAsString(manifest);
        });
    }

    private static TemplateBackedModel buildTemplateBackedModel(
            ObjectMapper mapper, long userId, ToLongFunction<String> templateId, ManifestJsonLookup lookup) {
        SmvModelValidator validator = new SmvModelValidator();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);
        when(templateService.findTemplateByName(anyLong(), anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(1, String.class);
            String manifest = lookup.find(name);
            return manifest == null ? Optional.empty() : Optional.of(DeviceTemplatePo.builder()
                    .id(templateId.applyAsLong(name)).userId(userId).name(name)
                    .manifestJson(manifest).defaultTemplate(true).build());
        });
        DeviceSmvDataFactory factory = new DeviceSmvDataFactory(
                mapper, templateService, validator, new DeviceTemplateSchemaValidator(mapper));
        SmvGenerator generator = new SmvGenerator(factory, new SmvDeviceModuleBuilder(), new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(), new SmvSpecificationBuilder(), validator);
        return new TemplateBackedModel(generator, factory);
    }

    @FunctionalInterface
    private interface ManifestJsonLookup {
        String find(String name) throws JsonProcessingException;
    }

    static RuleFixer allStrategyFixer(SmvGenerator generator, NusmvExecutor executor, int timeoutMs) {
        FixConfig fixConfig = new FixConfig();
        fixConfig.setFixTimeoutMs(timeoutMs);
        fixConfig.setMaxRefineAttempts(20);
        fixConfig.setMaxCandidatesPerRule(5);
        return new RuleFixer(
                new FaultLocalizer(),
                List.of(new ParameterAdjustStrategy(generator, executor, fixConfig),
                        new ConditionAdjustStrategy(generator, executor, fixConfig),
                        new RemoveRulesFixStrategy(generator, executor)),
                fixConfig);
    }

    static NusmvExecutor buildExecutor(String nusmvPath) {
        NusmvConfig config = new NusmvConfig();
        config.setPath(nusmvPath);
        config.setCommandPrefix("");
        config.setTimeoutMs(120_000);
        config.setMaxConcurrent(2);
        config.setAcquirePermitTimeoutMs(10_000);
        return new NusmvExecutor(config);
    }

    static NusmvExecutor.NusmvResult checkProperties(NusmvExecutor executor, SmvGenerator.GenerateResult model)
            throws InterruptedException {
        var result = executor.execute(model.smvFile());
        assertTrue(result.isSuccess(), result::getErrorMessage);
        var alignment = SpecResultAlignment.align(result.getSpecResults(), model.emittedSpecs());
        assertEquals(alignment.expectedResultCount(), result.getSpecResults().size());
        assertEquals(0, alignment.backFilled());
        assertTrue(alignment.aligned().stream().noneMatch(Objects::isNull));
        for (int i = 0; i < model.emittedSpecs().size(); i++) {
            if (model.emittedSpecs().get(i).antecedentExpression() != null) assertNotNull(alignment.antecedents().get(i));
        }
        return NusmvExecutor.NusmvResult.success(result.getOutput(), alignment.aligned());
    }

    static List<DeviceVerificationDto> readDevices(JsonNode scene) {
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

    static List<SpecificationDto> readSpecs(
            JsonNode scene, List<DeviceVerificationDto> devices, String idPrefix) {
        Map<String, String> labelsByDeviceId = devices.stream().collect(Collectors.toMap(
                DeviceVerificationDto::getVarName, DeviceVerificationDto::getDeviceLabel));
        return PortableSceneTestImport.importSpecs(scene, idPrefix, labelsByDeviceId);
    }

    static List<BoardEnvironmentVariableDto> readEnvironment(JsonNode scene) {
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

    private static List<VariableStateDto> readVariables(JsonNode rows) {
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

    private static List<PrivacyStateDto> readPrivacies(JsonNode rows) {
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

    private static String textOrNull(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
