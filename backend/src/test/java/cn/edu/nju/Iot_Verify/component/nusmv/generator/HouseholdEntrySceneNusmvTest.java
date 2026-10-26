package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.board.PortableSceneTestImport;
import cn.edu.nju.Iot_Verify.component.nusmv.SpecResultAlignment;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyUtils;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvRuleCommentWriter;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvSpecificationBuilder;
import cn.edu.nju.Iot_Verify.component.nusmv.parser.SmvTraceParser;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.verification.AntecedentReachability;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HouseholdEntrySceneNusmvTest {
    @Test
    void aTriggerReachableFromOnlyOneInitialStateIsStillReachable() throws Exception {
        var device = new DeviceSmvData();
        device.setVarName("sensor_1");
        device.setModes(List.of("SensorState"));
        device.setModeStates(Map.of("SensorState", List.of("active", "idle")));
        var condition = new SpecConditionDto();
        condition.setDeviceId("sensor_1");
        condition.setTargetType("mode");
        condition.setKey("SensorState");
        condition.setRelation("=");
        condition.setValue("active");
        var spec = new SpecificationDto();
        spec.setId("response");
        spec.setName("Safety requirement");
        spec.setTemplateId("4");
        spec.setIfConditions(List.of(condition));
        spec.setThenConditions(List.of(condition));
        var context = SmvGenerationContext.collecting();
        String properties = new SmvSpecificationBuilder().build(List.of(spec), Map.of("sensor_1", device), false, context);
        Path dir = Files.createTempDirectory("response-multiple-initial-states-");
        Path model = dir.resolve("model.smv");
        try {
            Files.writeString(model, "MODULE main\nVAR sensor_1 : Sensor;\n" + properties
                    + "\nMODULE Sensor\nFROZENVAR SensorState : {active, idle};\n");
            var config = new NusmvConfig();
            config.setPath(System.getenv().getOrDefault("NUSMV_PATH",
                    "D:/NuSMV/NuSMV-2.7.1-win64/NuSMV-2.7.1-win64/bin/NuSMV.exe"));
            Assumptions.assumeTrue(Files.exists(Path.of(config.getPath())), "NuSMV is required for the reachability regression");
            config.setCommandPrefix("");
            var result = new NusmvExecutor(config).execute(model.toFile());
            assertTrue(result.isSuccess(), result::getErrorMessage);
            var emitted = context.warningsSnapshot().emittedSpecs();
            var aligned = SpecResultAlignment.align(result.getSpecResults(), emitted);
            assertEquals(2, result.getSpecResults().size());
            assertTrue(aligned.aligned().get(0).isPassed());
            assertEquals(AntecedentReachability.REACHABLE, aligned.antecedentReachability(0, emitted.get(0)),
                    "one unreachable initial branch does not make the trigger globally unreachable");
        } finally {
            // NuSMV's debug outputs are bounded by the same temporary folder as this test model.
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        }
    }
    @Test
    void removingUnsafeCouplingPreservesVisitorLightingAndAuthorizedEntry() throws Exception {
        String nusmvPath = System.getenv().getOrDefault("NUSMV_PATH",
                "D:/NuSMV/NuSMV-2.7.1-win64/NuSMV-2.7.1-win64/bin/NuSMV.exe");
        Assumptions.assumeTrue(Files.exists(Path.of(nusmvPath)), "NuSMV is required for the household regression");
        ObjectMapper mapper = new ObjectMapper();
        var scene = mapper.readTree(Files.readString(Path.of("..", "docs", "examples", "household-entry-scene.json")));
        var imported = PortableSceneTestImport.importScene(scene, "entry-");
        var rules = PortableSceneTestImport.importRules(scene);
        var input = new BoardDataConverter(mock(BoardStorageService.class)).toModelInputSnapshot(
                new BoardSemanticSnapshotDto(imported.getNodes(), imported.getEnvironmentVariables(), rules,
                        imported.getSpecs(), imported.getTemplateSnapshots()));
        var validator = new SmvModelValidator();
        var factory = new DeviceSmvDataFactory(mapper, mock(DeviceTemplateService.class), validator,
                new DeviceTemplateSchemaValidator(mapper));
        var devices = factory.buildDeviceSmvMapFromTemplateSnapshots(input.devices(), input.templateManifests());
        var generator = new SmvGenerator(factory, new SmvDeviceModuleBuilder(), new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(), new SmvSpecificationBuilder(), validator);
        var config = new NusmvConfig();
        config.setPath(nusmvPath);
        config.setCommandPrefix("");
        config.setTimeoutMs(60_000);
        var executor = new NusmvExecutor(config);
        var baseline = generator.generateWithResolvedDeviceModel(1L, input.devices(), input.environmentVariables(),
                rules, input.specifications(), AttackScenarioDto.none(), false, SmvGenerator.GeneratePurpose.VERIFICATION, null, devices);
        assertEquals(0, baseline.disabledRuleCount());
        assertEquals(0, baseline.skippedSpecCount());
        var checked = executor.execute(baseline.smvFile());
        assertTrue(checked.isSuccess(), checked::getErrorMessage);
        var alignment = SpecResultAlignment.align(checked.getSpecResults(), baseline.emittedSpecs());
        assertEquals(6, checked.getSpecResults().size());
        assertEquals(List.of(false, true, true), alignment.aligned().stream().map(NusmvExecutor.SpecCheckResult::isPassed).toList());
        for (int i = 0; i < 3; i++) {
            assertEquals(AntecedentReachability.REACHABLE, alignment.antecedentReachability(i, baseline.emittedSpecs().get(i)));
        }
        var trace = new SmvTraceParser().parseCounterexampleStates(
                alignment.aligned().get(0).getCounterexample(), devices, rules);
        assertFalse(trace.isEmpty(), "the unsafe lamp-to-door chain must have a concrete counterexample");

        var context = FixContext.builder().deadline(Instant.now().plusSeconds(90))
                .userId(1L).devices(input.devices()).environmentVariables(input.environmentVariables())
                .allRules(rules).specs(input.specifications()).violatedSpecIndex(0).deviceSmvMap(devices)
                .attackScenario(AttackScenarioDto.none()).enablePrivacy(false).build();
        var usefulRepair = List.of(rules.get(0), rules.get(2));
        assertTrue(FixStrategyUtils.forwardVerify(generator, executor, context, usefulRepair, "remove").isAccepted());
        assertEquals(3, context.baselineVerdict().reachableResponseIds().size());
        RuleDto guarded = mapper.convertValue(rules.get(1), RuleDto.class);
        guarded.getConditions().add(RuleDto.Condition.builder().deviceName("entry_1")
                .targetType("variable").attribute("entryRequest").relation("=").value("authorized_owner").build());
        assertTrue(FixStrategyUtils.forwardVerify(generator, executor, context,
                List.of(rules.get(0), guarded, rules.get(2)), "condition").isAccepted(),
                "an in-place authorization guard must preserve both useful responses");
        assertFalse(FixStrategyUtils.forwardVerify(generator, executor, context, List.of(rules.get(0)), "remove").isAccepted(),
                "permanently locking the door breaks authorized entry");
        assertFalse(FixStrategyUtils.forwardVerify(generator, executor, context, List.of(rules.get(2)), "remove").isAccepted(),
                "removing visitor lighting breaks the useful visitor response");
    }
}
