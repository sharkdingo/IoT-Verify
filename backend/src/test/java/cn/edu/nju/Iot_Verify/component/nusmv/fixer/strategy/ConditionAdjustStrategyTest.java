package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.NusmvResult;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.GuardProbe;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext.EmittedSpec;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.stream.IntStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConditionAdjustStrategyTest {

    @Mock private SmvGenerator smvGenerator;
    @Mock private NusmvExecutor nusmvExecutor;

    private ConditionAdjustStrategy strategy;

    @BeforeEach
    void setUp() {
        FixConfig fixConfig = new FixConfig();
        fixConfig.setMaxCandidatesPerRule(5);
        strategy = new ConditionAdjustStrategy(smvGenerator, nusmvExecutor, fixConfig);
    }

    private SmvGenerator.GenerateResult createGenResult() throws IOException {
        File tempDir = Files.createTempDirectory("smv-test").toFile();
        File smvFile = new File(tempDir, "test.smv");
        smvFile.createNewFile();
        return new SmvGenerator.GenerateResult(smvFile, Map.of());
    }

    private FixContext ctx(List<FaultRuleDto> faultRules, List<RuleDto> allRules,
                            List<SpecificationDto> specs) {
        return FixContext.builder()
                .faultRules(faultRules)
                .repairRuleIndices(faultRules == null ? List.of()
                        : faultRules.stream().map(FaultRuleDto::getRuleIndex).toList())
                .allRules(allRules)
                .devices(List.of())
                .specs(specs)
                .deviceSmvMap(Map.of())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(20)
                .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 6})
    void tryFix_redundantTriggersTestsTargetPairBeforeGuardingRequiredResponse(int targetRule) throws Exception {
        List<Integer> values = List.of(30, 31, 40);
        List<RuleDto> rules = IntStream.range(0, 9).mapToObj(index -> RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").targetType("variable")
                        .relation(">=").value(values.get(index % 3).toString()).build()))
                .command(RuleDto.Command.builder().deviceName("ac_" + (index / 3 + 1)).action("turnOn").build())
                .build()).toList();
        DeviceSmvData sensor =
                new DeviceSmvData();
        sensor.setVarName("sensor_1");
        sensor.setVariables(List.of(DeviceManifest.InternalVariable
                .builder().name("temperature").lowerBound(0).upperBound(50).build()));
        DeviceSmvData occupancy =
                new DeviceSmvData();
        occupancy.setVarName("occupancy_1");
        occupancy.setVariables(List.of(DeviceManifest.InternalVariable
                .builder().name("occupied").lowerBound(0).upperBound(1).build()));
        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        deviceMap.put("sensor_1", sensor);
        deviceMap.put("occupancy_1", occupancy);
        for (int index = 1; index <= 3; index++) {
            DeviceSmvData actuator = new DeviceSmvData();
            actuator.setVarName("ac_" + index);
            actuator.setVariables(List.of(DeviceManifest.InternalVariable.builder()
                    .name("requested").lowerBound(0).upperBound(1).build()));
            deviceMap.put(actuator.getVarName(), actuator);
        }
        SpecConditionDto clause = new SpecConditionDto();
        clause.setDeviceId("occupancy_1");
        clause.setTargetType("variable");
        clause.setKey("occupied");
        clause.setRelation("=");
        clause.setValue("0");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("3");
        SpecConditionDto targetClause = new SpecConditionDto();
        targetClause.setDeviceId("ac_" + (targetRule / 3 + 1));
        targetClause.setTargetType("variable");
        targetClause.setKey("requested");
        targetClause.setRelation("=");
        targetClause.setValue("0");
        spec.setAConditions(List.of(clause, targetClause));
        FixContext context = FixContext.builder()
                .faultRules(List.of(0, 3, 6).stream()
                        .map(index -> FaultRuleDto.builder().ruleIndex(index).build()).toList())
                .repairRuleIndices(List.of(0, 3, 6, 1, 2, 4, 5, 7, 8)).allRules(rules).devices(List.of()).specs(List.of(spec))
                .deviceSmvMap(deviceMap)
                .violatedSpecIndex(0).userId(1L).attackScenario(AttackScenarioDto.none()).maxAttempts(20).build();
        AtomicReference<ParameterizationConfig> active =
                new AtomicReference<>();
        List<Set<String>> searched = new ArrayList<>();
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenAnswer(invocation -> {
                    ParameterizationConfig config = invocation.getArgument(7);
                    active.set(config);
                    searched.add(config.getFixedConditionLambdas().entrySet().stream()
                            .filter(entry -> entry.getKey().endsWith("_c1") && entry.getValue())
                            .map(Map.Entry::getKey).collect(Collectors.toSet()));
                    return createGenResult();
                });
        lenient().when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenAnswer(invocation -> { active.set(null); return createGenResult(); });
        Set<String> pair = Set.of("r" + targetRule + "_c1", "r" + (targetRule + 1) + "_c1");
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(invocation -> {
            ParameterizationConfig config = active.get();
            if (config == null || !searched.get(searched.size() - 1).equals(pair)) {
                return NusmvResult.success("", List.of(new SpecCheckResult("AG safe", true, null)));
            }
            StringBuilder witness = new StringBuilder("  -> State: 1.1 <-\n");
            config.getFixedConditionLambdas().forEach((key, value) -> witness.append("    ")
                    .append(config.getConditionLambdas().get(key)).append(" = ")
                    .append(value ? "TRUE" : "FALSE").append('\n'));
            config.getCandidateConditionValues().values().forEach(info -> witness.append("    ")
                    .append(info.getFrozenVarName()).append(" = 1\n"));
            return NusmvResult.success(witness.toString(), List.of(new SpecCheckResult("AG safe", false, null)));
        });

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertEquals(1, outcome.suggestions().size(), "The primary/backup guards must be tested before the full group");
        assertEquals(List.of(targetRule, targetRule + 1), outcome.suggestions().get(0).getConditionAdjustments().stream()
                .map(adjustment -> adjustment.getRuleIndex()).toList());
        assertTrue(outcome.alternativesComplete(), "The unrestricted UNSAT must remain the final proof");
    }

    @Test
    void tryFix_aNewlyBrokenPropertyGuidesACombinedProbeBeforeJointSearch() throws Exception {
        List<RuleDto> rules = IntStream.range(0, 6).mapToObj(index -> RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature").targetType("variable")
                        .relation(">=").value(List.of("30", "31", "40").get(index % 3)).build()))
                .command(RuleDto.Command.builder().deviceName("ac_" + (index / 3 + 1)).action("turnOn").build())
                .build()).toList();
        Map<String, DeviceSmvData> deviceMap = new LinkedHashMap<>();
        for (String name : List.of("sensor_1", "occupancy_1", "ac_1", "ac_2")) {
            DeviceSmvData device = new DeviceSmvData();
            device.setVarName(name);
            String variable = name.equals("sensor_1") ? "temperature"
                    : name.equals("occupancy_1") ? "occupied" : "requested";
            device.setVariables(List.of(DeviceManifest.InternalVariable.builder()
                    .name(variable).lowerBound(0).upperBound(name.equals("sensor_1") ? 50 : 1).build()));
            deviceMap.put(name, device);
        }
        SpecConditionDto occupied = new SpecConditionDto();
        occupied.setDeviceId("occupancy_1");
        occupied.setTargetType("variable");
        occupied.setKey("occupied");
        occupied.setRelation("=");
        occupied.setValue("0");
        List<SpecificationDto> specs = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            SpecConditionDto actuator = new SpecConditionDto();
            actuator.setDeviceId(index == 0 ? "ac_2" : "ac_1");
            actuator.setTargetType("variable");
            actuator.setKey("requested");
            actuator.setRelation("=");
            actuator.setValue("1");
            SpecificationDto spec = new SpecificationDto();
            spec.setId(index == 0 ? "s1" : "protected");
            spec.setName("Safety requirement");
            spec.setTemplateId("3");
            spec.setAConditions(List.of(occupied, actuator));
            specs.add(spec);
        }
        FixContext context = FixContext.builder()
                .faultRules(List.of(FaultRuleDto.builder().ruleIndex(3).build()))
                .repairRuleIndices(List.of(3, 0, 4, 5, 1, 2)).allRules(rules).devices(List.of()).specs(specs)
                .deviceSmvMap(deviceMap).violatedSpecIndex(0).userId(1L)
                .attackScenario(AttackScenarioDto.none()).maxAttempts(20).build();
        context.recordBaselineVerdict(new FixContext.BaselineVerdict(Set.of("s1"), Set.of()));
        Set<String> targetPair = Set.of("r3_c1", "r4_c1");
        Set<String> combined = Set.of("r0_c1", "r1_c1", "r3_c1", "r4_c1");
        AtomicReference<ParameterizationConfig> active = new AtomicReference<>();
        AtomicReference<Set<String>> selected = new AtomicReference<>();
        List<Set<String>> searched = new ArrayList<>();
        Set<Set<String>> witnessed = new java.util.HashSet<>();
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(ParameterizationConfig.class), any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenAnswer(invocation -> {
                    ParameterizationConfig config = invocation.getArgument(7);
                    active.set(config);
                    Set<String> changes = config.getFixedConditionLambdas().entrySet().stream()
                            .filter(entry -> !entry.getKey().endsWith("_c0") && entry.getValue())
                            .map(Map.Entry::getKey).collect(Collectors.toSet());
                    selected.set(changes);
                    searched.add(changes);
                    return createGenResult();
                });
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class), any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenAnswer(invocation -> {
                    active.set(null);
                    return new SmvGenerator.GenerateResult(createGenResult().smvFile(), Map.of(), List.of(), 0, 0,
                            List.of(new EmittedSpec(null, "s1", "CTLSPEC AG(safe_b)"),
                                    new EmittedSpec(null, "protected", "CTLSPEC AG(safe_a)")));
                });
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenAnswer(invocation -> {
            ParameterizationConfig config = active.get();
            if (config == null) {
                boolean protectsBoth = combined.equals(selected.get());
                return NusmvResult.success("", List.of(new SpecCheckResult("AG(safe_b)", true, null),
                        new SpecCheckResult("AG(safe_a)", protectsBoth,
                                protectsBoth ? null : "  -> State: 1.1 <-\n    sensor_1.temperature = 34\n")));
            }
            if ((!targetPair.equals(selected.get()) && !combined.equals(selected.get()))
                    || !witnessed.add(selected.get())) {
                return NusmvResult.success("", List.of(new SpecCheckResult("AG safe", true, null)));
            }
            StringBuilder witness = new StringBuilder("  -> State: 1.1 <-\n");
            config.getFixedConditionLambdas().forEach((key, value) -> witness.append("    ")
                    .append(config.getConditionLambdas().get(key)).append(" = ")
                    .append(value ? "TRUE" : "FALSE").append('\n'));
            config.getCandidateConditionValues().values().forEach(info -> witness.append("    ")
                    .append(info.getFrozenVarName()).append(" = 1\n"));
            return NusmvResult.success(witness.toString(), List.of(new SpecCheckResult("AG safe", false, null)));
        });

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertEquals(1, outcome.suggestions().size(),
                "A rejected target-only repair must guide a joint edit that preserves the earlier property");
        assertEquals(searched.indexOf(targetPair) + 1, searched.indexOf(combined),
                "The protected-property pair must be combined before unrelated probes consume the budget");
        assertTrue(searched.indexOf(combined) < 10);
        assertEquals(List.of(0, 1, 3, 4), outcome.suggestions().get(0).getConditionAdjustments().stream()
                .map(adjustment -> adjustment.getRuleIndex()).sorted().toList());
        assertTrue(outcome.suggestions().get(0).getPreexistingViolations().isEmpty());
        assertTrue(searched.size() <= 20);
        assertTrue(outcome.alternativesComplete(), "The final unrestricted proof must remain available");
    }

    @Test
    void tryFix_nullFaultRules_findsNothing() {
        assertTrue(strategy.tryFix(ctx(null, List.of(), List.of())).suggestions().isEmpty());
    }

    @Test
    void tryFix_emptyFaultRules_findsNothing() {
        assertTrue(strategy.tryFix(ctx(List.of(), List.of(), List.of())).suggestions().isEmpty());
    }

    @Test
    void semanticExclusion_ignoresFreeValueWhenCandidateIsDisabled() {
        ParameterizationConfig.ConditionValueInfo valueInfo =
                ParameterizationConfig.ConditionValueInfo.builder()
                        .frozenVarName("condition_value_r0_c1")
                        .values(List.of("off", "on"))
                        .build();
        Map<String, String> lambdas = new LinkedHashMap<>();
        lambdas.put("r0_c0", "lambda_r0_c0");
        lambdas.put("r0_c1", "lambda_r0_c1");
        Map<String, ParameterizationConfig.ConditionValueInfo> values =
                Map.of("r0_c1", valueInfo);

        String disabled = ConditionAdjustStrategy.semanticExclusion(
                lambdas, values, Map.of(
                        "lambda_r0_c0", "TRUE",
                        "lambda_r0_c1", "FALSE",
                        "condition_value_r0_c1", "off"));
        String enabled = ConditionAdjustStrategy.semanticExclusion(
                lambdas, values, Map.of(
                        "lambda_r0_c0", "TRUE",
                        "lambda_r0_c1", "TRUE",
                        "condition_value_r0_c1", "off"));

        assertEquals("!(lambda_r0_c0=TRUE & lambda_r0_c1=FALSE)", disabled);
        assertEquals("!(lambda_r0_c0=TRUE & lambda_r0_c1=TRUE & condition_value_r0_c1=off)", enabled);
    }

    @Test
    void tryFix_negatedSpecTrue_provesNoConditionRepairExists() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build(),
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("humidity").relation(">").value("60").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());

        // Negated spec universally true → no fix
        SpecCheckResult passing = mock(SpecCheckResult.class);
        when(passing.isPassed()).thenReturn(true);
        NusmvResult result = mock(NusmvResult.class);
        when(result.isSuccess()).thenReturn(true);
        when(result.getSpecResults()).thenReturn(List.of(passing));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(result);

        FixContext context = ctx(List.of(fault), List.of(rule), List.of(spec));
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertTrue(outcome.suggestions().isEmpty());
        assertTrue(outcome.alternativesComplete(), "¬ρ holding under every assignment proves no repair exists");
        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", context.strategyNoResult("condition").status());
        ArgumentCaptor<ParameterizationConfig> configs = ArgumentCaptor.forClass(ParameterizationConfig.class);
        verify(smvGenerator, atLeast(2)).generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                configs.capture(), any(SmvGenerator.TempModelContext.class), anyMap());
        assertEquals(Map.of("r0_c0", false, "r0_c1", true),
                configs.getAllValues().get(0).getFixedConditionLambdas());
        assertTrue(configs.getAllValues().get(configs.getAllValues().size() - 1)
                .getFixedConditionLambdas().isEmpty(), "Joint search must retain all lambda assignments");
    }

    @Test
    void tryFix_removesCondition_fixesViolation() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build(),
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("humidity").relation(">").value("60").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .ruleString(" Cool when hot and humid ")
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        // Parameterized model generation
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());

        // NuSMV returns counterexample: lambda_r0_c0 = TRUE (keep), lambda_r0_c1 = FALSE (remove)
        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    lambda_r0_c0 = TRUE\n    lambda_r0_c1 = FALSE\n");

        // Forward verification passes
        SmvGenerator.GenerateResult verifyGenResult = createGenResult();
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenReturn(verifyGenResult);

        SpecCheckResult allPass = mock(SpecCheckResult.class);
        when(allPass.isPassed()).thenReturn(true);
        NusmvResult verifyResult = mock(NusmvResult.class);
        when(verifyResult.isSuccess()).thenReturn(true);
        when(verifyResult.getSpecResults()).thenReturn(List.of(allPass));

        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong()))
                .thenReturn(negatedResult)
                .thenReturn(verifyResult);

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(
                ctx(List.of(fault), List.of(rule), List.of(spec)));

        assertEquals(1, outcome.suggestions().size());
        FixSuggestionDto suggestion = outcome.suggestions().get(0);
        assertEquals("condition", suggestion.getStrategy());
        assertNotNull(suggestion.getConditionAdjustments());
        // UX-6: action="keep" entries are filtered out; only "remove" and "add" are returned
        assertEquals(1, suggestion.getConditionAdjustments().size());
        assertEquals("remove", suggestion.getConditionAdjustments().get(0).getAction());
        // The structured field carries the bare rule name; the UI wraps it in its own quotes.
        assertEquals("Cool when hot and humid",
                suggestion.getConditionAdjustments().get(0).getRuleDescription());
    }

    @Test
    void tryFix_removingOnlyConditionEmptiesRule_rejectedAndRetries() throws Exception {
        // Single-condition rule. NuSMV proposes removing that one condition (lambda=FALSE), which would
        // leave the rule with zero conditions. NuSMV treats an empty-condition rule as fail-closed so it
        // "verifies", but RuleDto forbids empty conditions and apply rejects it — so the strategy must
        // NOT return this candidate; it should exclude the config and retry until attempts run out.
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());

        // lambda_r0_c0 = FALSE → remove the only condition → would empty the rule.
        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    lambda_r0_c0 = FALSE\n");
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(negatedResult);

        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(Map.of())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(2)
                .build();

        // The empty-rule candidate is rejected (never forward-verified), so no suggestion is returned;
        // the config is excluded and the loop retries until attempts are exhausted.
        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        verify(nusmvExecutor, times(2)).executeRepairSearch(any(File.class), anyLong());
        // Forward verification must never run for the empty-rule candidate.
        verify(smvGenerator, never()).generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList());
    }

    @Test
    void tryFix_onlyRuleEmptyingCandidatesAvoidTheCounterexample_isARejectionNotAnAbsence() throws Exception {
        // The one candidate that avoids the counterexample removes the rule's only condition; the next
        // solve is UNSAT. Candidates existed and were ruled out, which the status must not call "none".
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult emptying = mock(NusmvResult.class);
        when(emptying.isSuccess()).thenReturn(true);
        when(emptying.getSpecResults()).thenReturn(List.of(failing));
        when(emptying.getOutput()).thenReturn("  -> State: 1.1 <-\n    lambda_r0_c0 = FALSE\n");
        SpecCheckResult unsat = mock(SpecCheckResult.class);
        when(unsat.isPassed()).thenReturn(true);
        NusmvResult exhausted = mock(NusmvResult.class);
        when(exhausted.isSuccess()).thenReturn(true);
        when(exhausted.getSpecResults()).thenReturn(List.of(unsat));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(emptying, exhausted);

        FixContext context = ctx(List.of(fault), List.of(rule), List.of(spec));
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertTrue(outcome.suggestions().isEmpty());
        assertTrue(outcome.alternativesComplete());
        assertEquals("ALL_CANDIDATES_REJECTED", context.strategyNoResult("condition").status());
    }

    @Test
    void tryFix_allKept_exhaustsAttemptsAndReturnsNull() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());

        // NuSMV keeps all conditions (lambda = TRUE for all) — strategy should retry with exclusion
        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult negatedResult = mock(NusmvResult.class);
        when(negatedResult.isSuccess()).thenReturn(true);
        when(negatedResult.getSpecResults()).thenReturn(List.of(failing));
        when(negatedResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    lambda_r0_c0 = TRUE\n");
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(negatedResult);

        // Use maxAttempts=2 to verify retry behavior without excessive looping
        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(Map.of())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(2)
                .build();

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        // Verify it retried (2 calls = 2 attempts)
        verify(nusmvExecutor, times(2)).executeRepairSearch(any(File.class), anyLong());
    }

    @Test
    void tryFix_jointSolverFailure_endsTheSearchWithoutRepeatingTheIdenticalModel() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());

        NusmvResult failedResult = mock(NusmvResult.class);
        when(failedResult.isSuccess()).thenReturn(false);
        when(failedResult.getErrorMessage()).thenReturn("NuSMV crashed");
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(failedResult);

        // Budget left for two more runs: a failure must not spend it re-running the same model.
        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(Map.of())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(3)
                .build();

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.suggestions().isEmpty());
        assertFalse(outcome.alternativesComplete(), "a failed run proves nothing");
        verify(nusmvExecutor, times(1)).executeRepairSearch(any(File.class), anyLong());
        assertNotNull(context.strategySolverFailure("condition"));
        assertEquals(1, context.strategySearchProgress("condition").attemptsUsed());
    }

    @Test
    void tryFix_failedPinnedConfiguration_movesOnInsteadOfRepeatingIt() throws Exception {
        RuleDto rule = twoConditionRule();
        ArgumentCaptor<ParameterizationConfig> configCaptor =
                ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configCaptor.capture(),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenReturn(createGenResult());
        NusmvResult pass = NusmvResult.success("", List.of(new SpecCheckResult("AG safe", true, null)));
        // Pinned r0_c0 fails; pinned r0_c1: remove it, verified; joint search: ¬ρ holds.
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(
                NusmvResult.error("NuSMV exited with code 1"),
                witness("TRUE", "FALSE"), pass,
                pass);

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(twoConditionCtx(rule, 20));

        assertEquals(List.of(1), outcome.suggestions().stream()
                .map(suggestion -> suggestion.getConditionAdjustments().get(0).getConditionIndex())
                .toList());
        assertTrue(outcome.alternativesComplete(), "the joint search covers the configuration that failed");
        List<ParameterizationConfig> configs = configCaptor.getAllValues();
        assertEquals(3, configs.size());
        assertEquals(Map.of("r0_c0", true, "r0_c1", false), configs.get(1).getFixedConditionLambdas(),
                "the failed configuration must not be pinned again");
    }

    @Test
    void tryFix_busySolverThenConclusiveUnsat_retriesAndClearsFailureOutcome() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor_1").attribute("temperature")
                        .targetType("variable").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        // The concurrency cap refusing a permit says nothing about the model, so the same run is retried.
        NusmvResult busy = NusmvResult.busy("NuSMV execution is busy, please retry later");
        SpecCheckResult unsat = mock(SpecCheckResult.class);
        when(unsat.isPassed()).thenReturn(true);
        NusmvResult conclusive = mock(NusmvResult.class);
        when(conclusive.isSuccess()).thenReturn(true);
        when(conclusive.getSpecResults()).thenReturn(List.of(unsat));
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(busy, conclusive);

        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex())).allRules(List.of(rule)).devices(List.of())
                .specs(List.of(spec)).deviceSmvMap(Map.of()).violatedSpecIndex(0)
                .userId(1L).attackScenario(AttackScenarioDto.none()).maxAttempts(2).build();

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.suggestions().isEmpty());
        assertTrue(outcome.alternativesComplete());
        assertNull(context.strategySolverFailure("condition"));
        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", context.strategyNoResult("condition").status());
        assertEquals(2, context.strategySearchProgress("condition").attemptsUsed());
    }

    /**
     * An incomplete assignment can be neither interpreted nor excluded, so rerunning the same model can
     * only read the same output: the search moves on to a different model or ends.
     */
    @Test
    void tryFix_partialExtraction_neitherExcludesNorRepeatsTheIdenticalRun() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build(),
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("humidity").relation(">").value("60").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        ArgumentCaptor<ParameterizationConfig> configCaptor =
                ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configCaptor.capture(),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());

        // NuSMV returns counterexample with only 1 of 2 expected lambdas (partial extraction)
        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        NusmvResult partialResult = mock(NusmvResult.class);
        when(partialResult.isSuccess()).thenReturn(true);
        when(partialResult.getSpecResults()).thenReturn(List.of(failing));
        // Only lambda_r0_c0 extracted, lambda_r0_c1 missing → partial
        when(partialResult.getOutput()).thenReturn(
                "  -> State: 1.1 <-\n    lambda_r0_c0 = TRUE\n");
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(partialResult);

        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex()))
                .allRules(List.of(rule))
                .devices(List.of())
                .specs(List.of(spec))
                .deviceSmvMap(Map.of())
                .violatedSpecIndex(0)
                .userId(1L)
                .attackScenario(AttackScenarioDto.none())
                .enablePrivacy(false)
                .maxAttempts(3)
                .build();

        assertTrue(strategy.tryFix(context).suggestions().isEmpty());
        assertNotNull(context.strategySolverFailure("condition"));
        List<ParameterizationConfig> runs = configCaptor.getAllValues();
        assertEquals(new java.util.HashSet<>(runs).size(), runs.size(), "an identical model was rerun: " + runs);

        // A prioritized fixed configuration may be present, but a partial witness must
        // never add a semantic assignment exclusion beginning with "!(".
        for (ParameterizationConfig captured : configCaptor.getAllValues()) {
            assertTrue(captured.getExclusionInvars() == null
                            || captured.getExclusionInvars().stream().noneMatch(value -> value.startsWith("!(")),
                    "Partial extraction must NOT add an assignment exclusion, but found: "
                            + captured.getExclusionInvars());
        }
    }

    @Test
    void tryFix_preservesFailedAssignmentsAcrossPrioritizedAndJointSearch() throws Exception {
        RuleDto rule = RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder().deviceName("sensor_1")
                                .attribute("temperature").relation(">").value("30").build(),
                        RuleDto.Condition.builder().deviceName("sensor_1")
                                .attribute("humidity").relation(">").value("60").build(),
                        RuleDto.Condition.builder().deviceName("sensor_1")
                                .attribute("pressure").relation(">").value("10").build(),
                        RuleDto.Condition.builder().deviceName("sensor_1")
                                .attribute("light").relation(">").value("20").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");

        ArgumentCaptor<ParameterizationConfig> configCaptor =
                ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configCaptor.capture(),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenReturn(createGenResult());

        SpecCheckResult failing = mock(SpecCheckResult.class);
        when(failing.isPassed()).thenReturn(false);
        SpecCheckResult passing = mock(SpecCheckResult.class);
        when(passing.isPassed()).thenReturn(true);

        NusmvResult removeFirst = mock(NusmvResult.class);
        when(removeFirst.isSuccess()).thenReturn(true);
        when(removeFirst.getSpecResults()).thenReturn(List.of(failing));
        when(removeFirst.getOutput()).thenReturn("""
                  -> State: 1.1 <-
                    lambda_r0_c0 = FALSE
                    lambda_r0_c1 = TRUE
                    lambda_r0_c2 = TRUE
                    lambda_r0_c3 = TRUE
                """);
        NusmvResult removeSecond = mock(NusmvResult.class);
        when(removeSecond.isSuccess()).thenReturn(true);
        when(removeSecond.getSpecResults()).thenReturn(List.of(failing));
        when(removeSecond.getOutput()).thenReturn("""
                  -> State: 1.1 <-
                    lambda_r0_c0 = TRUE
                    lambda_r0_c1 = FALSE
                    lambda_r0_c2 = TRUE
                    lambda_r0_c3 = TRUE
                """);
        NusmvResult forwardFailure = mock(NusmvResult.class);
        when(forwardFailure.isSuccess()).thenReturn(true);
        when(forwardFailure.getSpecResults()).thenReturn(List.of(failing));
        NusmvResult conclusive = mock(NusmvResult.class);
        when(conclusive.isSuccess()).thenReturn(true);
        when(conclusive.getSpecResults()).thenReturn(List.of(passing));

        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(
                removeFirst, forwardFailure,
                conclusive,
                removeSecond, forwardFailure,
                conclusive);

        FixContext context = FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(fault.getRuleIndex())).allRules(List.of(rule)).devices(List.of())
                .specs(List.of(spec)).deviceSmvMap(Map.of()).violatedSpecIndex(0)
                .userId(1L).attackScenario(AttackScenarioDto.none()).maxAttempts(6).build();

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);
        assertTrue(outcome.suggestions().isEmpty());
        // Forward verification could not attribute either verdict, so either removal might be a repair.
        assertFalse(outcome.alternativesComplete());

        List<ParameterizationConfig> configs = configCaptor.getAllValues();
        assertEquals(4, configs.size());
        String firstFailedAssignment =
                "!(lambda_r0_c0=FALSE & lambda_r0_c1=TRUE & lambda_r0_c2=TRUE & lambda_r0_c3=TRUE)";
        String secondFailedAssignment =
                "!(lambda_r0_c0=TRUE & lambda_r0_c1=FALSE & lambda_r0_c2=TRUE & lambda_r0_c3=TRUE)";
        assertTrue(configs.get(2).getExclusionInvars().contains(firstFailedAssignment),
                "Changing prioritized configurations must retain earlier failed assignments");
        assertTrue(configs.get(3).getExclusionInvars().containsAll(
                        List.of(firstFailedAssignment, secondFailedAssignment)),
                "Joint search must retain failed assignments from prioritized probes");
    }

    @Test
    void tryFix_listsEachVerifiedConditionRepairAndExcludesItsSupersets() throws Exception {
        RuleDto rule = twoConditionRule();
        ArgumentCaptor<ParameterizationConfig> configCaptor =
                ArgumentCaptor.forClass(ParameterizationConfig.class);
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configCaptor.capture(),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenReturn(createGenResult());
        NusmvResult pass = NusmvResult.success("", List.of(new SpecCheckResult("AG safe", true, null)));
        // Pinned r0_c0: remove it, verified. Pinned r0_c1: remove it, verified. Joint search: ¬ρ holds.
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(
                witness("FALSE", "TRUE"), pass,
                witness("TRUE", "FALSE"), pass,
                pass);

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(twoConditionCtx(rule, 20));

        assertEquals(List.of(0, 1), outcome.suggestions().stream()
                .map(suggestion -> suggestion.getConditionAdjustments().get(0).getConditionIndex())
                .toList());
        assertTrue(outcome.suggestions().stream().allMatch(suggestion ->
                suggestion.getConditionAdjustments().size() == 1
                        && "remove".equals(suggestion.getConditionAdjustments().get(0).getAction())));
        assertTrue(outcome.alternativesComplete(), "the joint search proved no other repair remains");

        List<ParameterizationConfig> configs = configCaptor.getAllValues();
        assertEquals(3, configs.size());
        // A listed repair excludes every assignment that removes at least its condition, not only
        // the assignment that produced it.
        assertEquals(Map.of("r0_c0", true, "r0_c1", false), configs.get(1).getFixedConditionLambdas());
        assertEquals(List.of("!(lambda_r0_c0=FALSE)"),
                configs.get(1).getExclusionInvars());
        assertEquals(List.of("!(lambda_r0_c0=FALSE)", "!(lambda_r0_c1=FALSE)"),
                configs.get(2).getExclusionInvars());
    }

    @Test
    void tryFix_attemptBudgetEndingTheSearchLeavesTheListingIncomplete() throws Exception {
        RuleDto rule = twoConditionRule();
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenReturn(createGenResult());
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(
                witness("FALSE", "TRUE"),
                NusmvResult.success("", List.of(new SpecCheckResult("AG safe", true, null))));

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(twoConditionCtx(rule, 1));

        assertEquals(1, outcome.suggestions().size());
        assertFalse(outcome.alternativesComplete(), "the one attempt ended the search before ¬ρ was settled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryFix_aRejectionsViolatingPathExcludesEveryAssignmentItCannotTellApart() throws Exception {
        RuleDto rule = twoConditionRule();
        ArgumentCaptor<ParameterizationConfig> configCaptor =
                ArgumentCaptor.forClass(ParameterizationConfig.class);
        ArgumentCaptor<List<GuardProbe>> probesCaptor = ArgumentCaptor.forClass(List.class);
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), configCaptor.capture(),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), probesCaptor.capture()))
                .thenAnswer(invocation -> forwardGenResult());
        NusmvResult pass = NusmvResult.success("", List.of(new SpecCheckResult("AG safe", true, null)));
        // Removing temperature>30 still violates: humidity>60 is false where the rule would have acted,
        // so every assignment that keeps humidity>60 leaves the rule idle there too.
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(
                witness("FALSE", "TRUE"), forward(probeTrace("TRUE", "FALSE", "TRUE", "FALSE")),
                pass,
                witness("TRUE", "FALSE"), pass,
                pass);

        FixStrategy.StrategyOutcome outcome = strategy.tryFix(twoConditionCtx(rule, 20));

        assertEquals(List.of(GuardProbe.Kind.CONDITION, GuardProbe.Kind.CONDITION,
                        GuardProbe.Kind.RULE_BASE, GuardProbe.Kind.RULE_CONDITIONS),
                probesCaptor.getAllValues().get(0).stream().map(GuardProbe::kind).toList());
        List<ParameterizationConfig> configs = configCaptor.getAllValues();
        assertEquals(4, configs.size());
        assertEquals(Map.of("r0_c0", false, "r0_c1", true), configs.get(1).getFixedConditionLambdas());
        assertEquals(List.of("!(lambda_r0_c0=FALSE & lambda_r0_c1=TRUE)", "((!lambda_r0_c1))"),
                configs.get(1).getExclusionInvars());
        assertTrue(configs.get(3).getExclusionInvars().contains("((!lambda_r0_c1))"),
                "the joint search keeps what the rejection taught");
        assertEquals(List.of(1), outcome.suggestions().stream()
                .map(suggestion -> suggestion.getConditionAdjustments().get(0).getConditionIndex())
                .toList());
        assertTrue(outcome.alternativesComplete());
    }

    @Test
    void tryFix_aRejectionEveryAssignmentShares_endsTheSearchAsComplete() throws Exception {
        RuleDto rule = twoConditionRule();
        when(smvGenerator.generateParameterizedWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(ParameterizationConfig.class),
                any(SmvGenerator.TempModelContext.class), anyMap()))
                .thenReturn(createGenResult());
        when(smvGenerator.generateWithResolvedDeviceModel(
                anyLong(), anyList(), anyList(), anyList(), anyList(),
                any(), anyBoolean(), any(SmvGenerator.GeneratePurpose.class),
                any(SmvGenerator.TempModelContext.class), anyMap(), anyList()))
                .thenAnswer(invocation -> forwardGenResult());
        // The rule's command could never start on the violating path, whatever its conditions are.
        when(nusmvExecutor.executeRepairSearch(any(File.class), anyLong())).thenReturn(
                witness("FALSE", "TRUE"), forward(probeTrace("TRUE", "FALSE", "FALSE", "FALSE")));

        FixContext context = twoConditionCtx(rule, 20);
        FixStrategy.StrategyOutcome outcome = strategy.tryFix(context);

        assertTrue(outcome.suggestions().isEmpty());
        assertTrue(outcome.alternativesComplete(), "the rejection's path refutes every assignment");
        assertEquals("ALL_CANDIDATES_REJECTED", context.strategyNoResult("condition").status());
        verify(nusmvExecutor, times(2)).executeRepairSearch(any(File.class), anyLong());
    }

    /** A forward-verification model whose one specification is the violated target {@code s1}. */
    private SmvGenerator.GenerateResult forwardGenResult() throws IOException {
        return new SmvGenerator.GenerateResult(createGenResult().smvFile(), Map.of(), List.of(), 0, 0,
                List.of(new EmittedSpec(null, "s1", "CTLSPEC AG(safe)")));
    }

    /** A forward-verification verdict on {@code s1}: a {@code null} trace means it passes. */
    private static NusmvResult forward(String trace) {
        return NusmvResult.success("", List.of(new SpecCheckResult("AG safe", trace == null, trace)));
    }

    /** A violating path whose first state reports the given guard-probe values in probe order. */
    private static String probeTrace(String... values) {
        StringBuilder trace = new StringBuilder("Trace Description: CTL Counterexample \n"
                + "Trace Type: Counterexample \n  -> State: 1.1 <-\n");
        for (int i = 0; i < values.length; i++) {
            trace.append("    ").append(SmvConstants.GUARD_PROBE_PREFIX).append(i)
                    .append(" = ").append(values[i]).append('\n');
        }
        return trace.toString();
    }

    private static RuleDto twoConditionRule() {
        return RuleDto.builder()
                .conditions(List.of(
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("temperature").relation(">").value("30").build(),
                        RuleDto.Condition.builder()
                                .deviceName("sensor_1").attribute("humidity").relation(">").value("60").build()))
                .command(RuleDto.Command.builder().deviceName("ac_1").action("turnOn").build())
                .build();
    }

    private FixContext twoConditionCtx(RuleDto rule, int maxAttempts) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("s1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        FaultRuleDto fault = FaultRuleDto.builder().ruleIndex(0).build();
        return FixContext.builder()
                .faultRules(List.of(fault)).repairRuleIndices(List.of(0)).allRules(List.of(rule))
                .devices(List.of()).specs(List.of(spec)).deviceSmvMap(Map.of()).violatedSpecIndex(0)
                .userId(1L).attackScenario(AttackScenarioDto.none()).maxAttempts(maxAttempts).build();
    }

    /** A ¬ρ counterexample assigning the two condition lambdas of {@link #twoConditionRule()}. */
    private static NusmvResult witness(String lambda0, String lambda1) {
        return NusmvResult.success("  -> State: 1.1 <-\n    lambda_r0_c0 = " + lambda0
                        + "\n    lambda_r0_c1 = " + lambda1 + "\n",
                List.of(new SpecCheckResult("AG not_safe", false, null)));
    }
}
