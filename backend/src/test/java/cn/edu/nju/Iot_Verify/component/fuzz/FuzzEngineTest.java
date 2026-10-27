package cn.edu.nju.Iot_Verify.component.fuzz;

import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperEvent;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperEventDomainSnapshot;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperEnvironmentDomain;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperInitialValue;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperInitialValueDomain;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperSeed;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvModelValidator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvMainModuleBuilder;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest.API;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest.Content;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzEngineTest {

    private static final double EPSILON = 1.0e-9;

    private final FuzzEngine engine = new FuzzEngine();

    @Test
    void tracesFreezeDeviceLocalAndSharedEnvironmentTokenSources() {
        FuzzModel bundledModel = FuzzModel.from(provenanceSnapshot(false));
        var bundledState = bundledModel.simulate(new long[0], 1).traceStates().get(0);
        var bundledDevice = bundledState.getDevices().get(0);

        assertEquals(ModelTokenSource.BUNDLED, bundledDevice.getModelTokenSource());
        assertEquals(ModelTokenSource.BUNDLED,
                bundledDevice.getVariables().stream()
                        .filter(variable -> "profile".equals(variable.getName()))
                        .findFirst().orElseThrow().getModelTokenSource());
        assertEquals(ModelTokenSource.BUNDLED,
                bundledState.getEnvVariables().stream()
                        .filter(variable -> "weather".equals(variable.getName()))
                        .findFirst().orElseThrow().getModelTokenSource());
        assertTrue(bundledDevice.getVariables().stream()
                .allMatch(variable -> variable.getTrust() == null));
        assertTrue(bundledState.getEnvVariables().stream()
                .allMatch(variable -> variable.getTrust() == null));
        assertTrue(bundledDevice.getTrustPrivacy().isEmpty());
        assertTrue(bundledDevice.getPrivacies().isEmpty());
        assertTrue(bundledState.getTrustPrivacies().isEmpty());

        FuzzModel mixedModel = FuzzModel.from(provenanceSnapshot(true));
        var mixedState = mixedModel.simulate(new long[0], 1).traceStates().get(0);
        var customDevice = mixedState.getDevices().stream()
                .filter(device -> "custom_1".equals(device.getDeviceId()))
                .findFirst().orElseThrow();

        assertEquals(ModelTokenSource.CUSTOM, customDevice.getModelTokenSource());
        assertEquals(ModelTokenSource.CUSTOM,
                customDevice.getVariables().get(0).getModelTokenSource());
        assertEquals(ModelTokenSource.UNKNOWN,
                mixedState.getEnvVariables().stream()
                        .filter(variable -> "weather".equals(variable.getName()))
                        .findFirst().orElseThrow().getModelTokenSource());
    }

    @Test
    void paperModeIsDeterministicAndUsesItsOwnSearchKernel() {
        SpecConditionDto allLegalStates = stateCondition("off,on");
        allLegalStates.setRelation("in");
        SpecificationDto specification = specification("all-legal", "1", allLegalStates);
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(specification),
                List.of(),
                switchManifest());
        FuzzEngineConfig config = new FuzzEngineConfig(
                List.of(),
                2,
                3,
                4,
                19L,
                FuzzExplorationMode.PAPER_COMPATIBLE);

        FuzzEngineResult first = engine.run(new FuzzEngineInput(snapshot, config), null, () -> false);
        FuzzEngineResult second = engine.run(new FuzzEngineInput(snapshot, config), null, () -> false);

        assertEquals(FuzzEngineOutcome.BUDGET_EXHAUSTED, first.outcome());
        assertEquals(2, first.iterations());
        assertEquals(8, first.generatedPaths());
        assertEquals(first.findings(), second.findings());
        assertEquals(first.eligibility(), second.eligibility());
        assertTrue(first.limitations().contains("PAPER_EVENT_FSM_DISTANCE_ENABLED"));
        assertTrue(first.limitations().contains("PAPER_RANDOM_INITIAL_STATE_ENABLED"));
        assertTrue(first.limitations().contains("PAPER_MULTI_TARGET_PRODUCT_EXTENSION"));
        assertTrue(first.limitations().contains("NUSMV_REMAINS_PROOF_AUTHORITY"));
    }

    @Test
    void paperSeedRandomizesInitialStateAndUsesEnvironmentRateAsTheStepInput() {
        SpecificationDto specification = specificationForDevice(
                "never-zero", "3", "numeric_1", "variable", "temperature", "=", "0");
        FuzzModel model = FuzzModel.from(symmetricNumericSnapshot(List.of(specification)));
        PaperEventDomainSnapshot domain = model.paperEventDomain(3);
        long nonce = 0L;
        while ("2".equals(domain.materializeInitialState(nonce).get(0).value())) {
            nonce++;
        }

        FuzzModel.Simulation baseline = model.simulatePaperSeed(
                PaperSeed.empty(nonce), domain, 3, () -> false);
        FuzzModel.Simulation withRate = model.simulatePaperSeed(
                new PaperSeed(nonce, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT,
                        "temperature",
                        "rate:1",
                        2))),
                domain,
                3,
                () -> false);
        FuzzModel.Simulation withInitialRate = model.simulatePaperSeed(
                new PaperSeed(nonce, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT,
                        "temperature",
                        "rate:1",
                        1))),
                domain,
                3,
                () -> false);

        String randomizedInitial = traceEnvironmentValue(baseline, 0, "temperature");
        int withRateAtPositionTwo = Integer.parseInt(traceEnvironmentValue(withRate, 1, "temperature"));
        assertNotEquals("2", randomizedInitial);
        assertEquals(randomizedInitial, traceEnvironmentValue(withRate, 0, "temperature"));
        assertEquals(Math.min(4, Integer.parseInt(randomizedInitial) + 1), withRateAtPositionTwo);
        assertEquals(
                Integer.toString(Math.min(4, Integer.parseInt(randomizedInitial) + 1)),
                traceEnvironmentValue(withInitialRate, 0, "temperature"));
        assertTrue(withRate.inputEvents().stream().anyMatch(event ->
                event.step() == 1
                        && event.kind() == FuzzInputEventKind.ENVIRONMENT_RATE
                        && event.source() == FuzzInputEventSource.SEED_EVENT
                        && "temperature".equals(event.property())
                        && "rate:1".equals(event.value())));
        assertTrue(withRate.inputEvents().stream().anyMatch(event ->
                event.step() == 0
                        && event.kind() == FuzzInputEventKind.ENVIRONMENT_VALUE
                        && event.source() == FuzzInputEventSource.RANDOM_INITIAL_STATE
                        && "temperature".equals(event.property())
                        && !event.value().startsWith("rate:")));
        int previousStep = -1;
        for (FuzzInputEvent event : withRate.inputEvents()) {
            assertTrue(event.step() >= previousStep);
            previousStep = event.step();
        }
    }

    @Test
    void paperRateEventMatchesFigureFiveWithoutBaselineNaturalEvolution() {
        FuzzModel model = FuzzModel.from(paperFigureNumericSnapshot());
        PaperEventDomainSnapshot domain = model.paperEventDomain(3);
        long nonce = 0L;
        while (!"26".equals(domain.materializeInitialState(nonce).get(0).value())) {
            nonce++;
        }

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(nonce, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT,
                        "temperature",
                        "rate:-2",
                        2))),
                domain,
                3,
                () -> false);

        assertEquals("26", traceEnvironmentValue(simulation, 0, "temperature"));
        assertEquals("24", traceEnvironmentValue(simulation, 1, "temperature"));
    }

    @Test
    void enumeratedPaperRatesAreCanonicalAndAppliedAsStepDeltas() {
        FuzzModel model = FuzzModel.from(paperFigureNumericSnapshot());
        PaperEventDomainSnapshot domain = new PaperEventDomainSnapshot(
                3,
                List.of(),
                List.of(new PaperEnvironmentDomain(
                        "temperature",
                        "environment",
                        "temperature",
                        List.of("26"),
                        List.of("-1", "+1"))));
        assertEquals(List.of("rate:-1", "rate:1"),
                domain.environment("temperature").eventValues());

        FuzzModel.Simulation decreased = model.simulatePaperSeed(
                new PaperSeed(7L, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT,
                        "temperature",
                        "rate:-1",
                        2))),
                domain,
                3,
                () -> false);
        FuzzModel.Simulation increased = model.simulatePaperSeed(
                new PaperSeed(7L, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT,
                        "temperature",
                        "rate:1",
                        2))),
                domain,
                3,
                () -> false);

        assertEquals("25", traceEnvironmentValue(decreased, 1, "temperature"));
        assertEquals("27", traceEnvironmentValue(increased, 1, "temperature"));
        assertTrue(decreased.inputEvents().stream().anyMatch(event ->
                event.step() == 1
                        && event.kind() == FuzzInputEventKind.ENVIRONMENT_RATE
                        && event.source() == FuzzInputEventSource.SEED_EVENT
                        && "rate:-1".equals(event.value())));
        assertTrue(increased.inputEvents().stream().anyMatch(event ->
                event.step() == 1
                        && event.kind() == FuzzInputEventKind.ENVIRONMENT_RATE
                        && event.source() == FuzzInputEventSource.SEED_EVENT
                        && "rate:1".equals(event.value())));
    }

    @Test
    void paperForcedTransitionValueOverridesScheduledEnvironmentRate() {
        FuzzModel model = FuzzModel.from(paperForcedEnvironmentAssignmentSnapshot());
        PaperEventDomainSnapshot domain = model.paperEventDomain(2);
        long nonce = 0L;
        while (!"26".equals(domain.materializeInitialState(nonce).stream()
                .filter(event -> event.type() == PaperEvent.Type.ENVIRONMENT
                        && "temperature".equals(event.name()))
                .findFirst()
                .orElseThrow()
                .value())) {
            nonce++;
        }

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(nonce, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT,
                        "temperature",
                        "rate:-2",
                        2))),
                domain,
                2,
                () -> false);

        assertEquals("30", traceEnvironmentValue(simulation, 1, "temperature"));
    }

    @Test
    void sharedEnvironmentAssignmentsFollowCapturedDeviceOrderLikeNuSmvCaseBranches() {
        BoardDataConverter.ModelInputSnapshot zFirstSnapshot = sharedWriteOrderSnapshot(true);
        BoardDataConverter.ModelInputSnapshot aFirstSnapshot = sharedWriteOrderSnapshot(false);
        FuzzModel zFirst = FuzzModel.from(zFirstSnapshot);
        FuzzModel aFirst = FuzzModel.from(aFirstSnapshot);

        FuzzModel.Simulation zFirstTrace = zFirst.simulate(new long[0], 2);
        FuzzModel.Simulation aFirstTrace = aFirst.simulate(new long[0], 2);
        String zFirstFormalCase = sharedWeatherFormalCase(zFirstSnapshot);
        String aFirstFormalCase = sharedWeatherFormalCase(aFirstSnapshot);

        assertEquals("calm", traceEnvironmentValue(zFirstTrace, 1, "weather"));
        assertEquals("rain", traceEnvironmentValue(aFirstTrace, 1, "weather"));
        assertTrue(zFirstFormalCase.indexOf("z_device") >= 0);
        assertTrue(zFirstFormalCase.indexOf("z_device") < zFirstFormalCase.indexOf("a_device"));
        assertTrue(aFirstFormalCase.indexOf("a_device") >= 0);
        assertTrue(aFirstFormalCase.indexOf("a_device") < aFirstFormalCase.indexOf("z_device"));
    }

    @Test
    void paperRateAndDeviceImpactAreCombinedBeforeOneBoundaryClamp() {
        FuzzModel model = FuzzModel.from(numericImpactSnapshot("100"));
        PaperEventDomainSnapshot domain = model.paperEventDomain(3);
        long nonce = 0L;
        while (true) {
            List<PaperEvent> initial = domain.materializeInitialState(nonce);
            boolean startsCool = initial.stream().anyMatch(event ->
                    event.type() == PaperEvent.Type.DEVICE && "cool".equals(event.value()));
            boolean startsAtUpperBoundary = initial.stream().anyMatch(event ->
                    event.type() == PaperEvent.Type.ENVIRONMENT
                            && "temperature".equals(event.name())
                            && "100".equals(event.value()));
            if (startsCool && startsAtUpperBoundary) {
                FuzzModel.Simulation baseline = model.simulatePaperSeed(
                        PaperSeed.empty(nonce), domain, 3, () -> false);
                if ("100".equals(traceEnvironmentValue(baseline, 1, "temperature"))) {
                    break;
                }
            }
            nonce++;
            assertTrue(nonce < 100_000L);
        }

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(nonce, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT,
                        "temperature",
                        "rate:1",
                        3))),
                domain,
                3,
                () -> false);

        assertEquals("100", traceEnvironmentValue(simulation, 1, "temperature"));
        assertEquals("100", traceEnvironmentValue(simulation, 2, "temperature"));
    }

    @Test
    void paperDiscreteEnvironmentStaysStableWithoutAnInputOrFormalEffect() {
        FuzzModel model = FuzzModel.from(snapshot(List.of(), List.of(), switchManifest()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(4);
        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                PaperSeed.empty(17L), domain, 4, () -> false);
        String initial = traceEnvironmentValue(simulation, 0, "weather");

        for (int step = 1; step < simulation.traceStates().size(); step++) {
            assertEquals(initial, traceEnvironmentValue(simulation, step, "weather"));
        }
        assertTrue(simulation.inputEvents().stream().noneMatch(event ->
                event.kind() == FuzzInputEventKind.ENVIRONMENT_VALUE
                        && event.source() == FuzzInputEventSource.MODEL_CHOICE));

        String changed = "calm".equals(initial) ? "rain" : "calm";
        FuzzModel.Simulation withDirectEvent = model.simulatePaperSeed(
                new PaperSeed(17L, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT, "weather", changed, 2))),
                domain,
                4,
                () -> false);
        assertEquals(initial, traceEnvironmentValue(withDirectEvent, 0, "weather"));
        assertEquals(changed, traceEnvironmentValue(withDirectEvent, 1, "weather"));
        assertEquals(changed, traceEnvironmentValue(withDirectEvent, 2, "weather"));
    }

    @Test
    void paperDiscreteRatePrefixedValueRemainsADirectValue() {
        DeviceManifest manifest = switchManifest();
        manifest.getInternalVariables().get(0).setValues(List.of("calm", "rate:idle"));
        FuzzModel model = FuzzModel.from(snapshot(List.of(), List.of(), manifest));
        PaperEventDomainSnapshot domain = model.paperEventDomain(2);

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(17L, List.of(new PaperEvent(
                        PaperEvent.Type.ENVIRONMENT, "weather", "rate:idle", 2))),
                domain,
                2,
                () -> false);

        assertFalse(domain.environment("weather").hasRateDomain());
        assertEquals("rate:idle", traceEnvironmentValue(simulation, 1, "weather"));
        assertTrue(simulation.inputEvents().stream().anyMatch(event ->
                event.step() == 1
                        && event.kind() == FuzzInputEventKind.ENVIRONMENT_VALUE
                        && event.source() == FuzzInputEventSource.SEED_EVENT
                        && "rate:idle".equals(event.value())));
    }

    @Test
    void paperEventUpdatesTheTargetStateButRulesReadThePreviousState() {
        RuleDto turnOnWhenRain = RuleDto.builder()
                .id(72L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .targetType("variable")
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("turnOn")
                        .build())
                .build();
        FuzzModel model = FuzzModel.from(snapshot(
                List.of(), List.of(turnOnWhenRain), switchManifest()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(3);
        long nonce = 0L;
        while (domain.materializeInitialState(nonce).stream()
                .filter(event -> event.type() == PaperEvent.Type.ENVIRONMENT)
                .noneMatch(event -> "weather".equals(event.name())
                        && "calm".equals(event.value()))) {
            nonce++;
        }

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(nonce, List.of(
                        new PaperEvent(PaperEvent.Type.DEVICE, "switch_1", "off", 1),
                        new PaperEvent(PaperEvent.Type.ENVIRONMENT, "weather", "rain", 2))),
                domain,
                3,
                () -> false);

        assertEquals("off", simulation.traceStates().get(0).getDevices().get(0).getState());
        assertEquals("off", simulation.traceStates().get(1).getDevices().get(0).getState());
        assertEquals("on", simulation.traceStates().get(2).getDevices().get(0).getState());
        assertEquals("rain", traceEnvironmentValue(simulation, 1, "weather"));
        assertTrue(simulation.traceStates().get(1).getTriggeredRules().isEmpty());
        assertEquals(1, simulation.traceStates().get(2).getTriggeredRules().size());
    }

    @Test
    void paperRuntimeMonitoringStopsAtTheFirstViolatingPrefix() {
        SpecificationDto neverOff = specification(
                "never-off", "3", stateCondition("off"));
        FuzzModel model = FuzzModel.from(snapshot(
                List.of(neverOff), List.of(), switchManifest()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(20);
        AtomicInteger cancellationChecks = new AtomicInteger();

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(11L, List.of(new PaperEvent(
                        PaperEvent.Type.DEVICE, "switch_1", "off", 1))),
                domain,
                20,
                () -> cancellationChecks.incrementAndGet() > 1,
                List.of(neverOff));

        assertFalse(simulation.cancelled());
        assertEquals(1, simulation.traceStates().size());
        assertEquals(0, cancellationChecks.get());
    }

    @Test
    void paperRuntimeMonitoringWaitsForAllUnresolvedProductTargets() {
        SpecificationDto neverOff = specification(
                "never-off", "3", stateCondition("off"));
        SpecificationDto neverOn = specification(
                "never-on", "3", stateCondition("on"));
        RuleDto turnOn = RuleDto.builder()
                .id(73L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .targetType("mode")
                        .attribute("PowerMode")
                        .relation("=")
                        .value("off")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("turnOn")
                        .build())
                .build();
        FuzzModel model = FuzzModel.from(snapshot(
                List.of(neverOff, neverOn), List.of(turnOn), switchManifest()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(20);

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(12L, List.of(new PaperEvent(
                        PaperEvent.Type.DEVICE, "switch_1", "off", 1))),
                domain,
                20,
                () -> false,
                List.of(neverOff, neverOn));

        assertEquals(2, simulation.traceStates().size());
        assertEquals("off", simulation.traceStates().get(0).getDevices().get(0).getState());
        assertEquals("on", simulation.traceStates().get(1).getDevices().get(0).getState());
    }

    @Test
    void paperRuntimeMonitoringKeepsTheBoundWhenNoTargetViolates() {
        SpecConditionDto legalStates = stateCondition("off,on");
        legalStates.setRelation("in");
        SpecificationDto alwaysLegal = specification(
                "always-legal", "1", legalStates);
        FuzzModel model = FuzzModel.from(snapshot(
                List.of(alwaysLegal), List.of(), switchManifest()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(5);

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                PaperSeed.empty(13L),
                domain,
                5,
                () -> false,
                List.of(alwaysLegal));

        assertEquals(5, simulation.traceStates().size());
    }

    @Test
    void paperTraceRetainsDeterministicModelChoiceEvidenceInCausalOrder() {
        FuzzModel model = FuzzModel.from(symmetricNumericSnapshot(List.of()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(3);
        PaperSeed seed = PaperSeed.empty(31L);

        FuzzModel.Simulation first = model.simulatePaperSeed(seed, domain, 3, () -> false);
        FuzzModel.Simulation second = model.simulatePaperSeed(seed, domain, 3, () -> false);

        assertEquals(first.inputEvents(), second.inputEvents());
        assertTrue(first.inputEvents().stream().anyMatch(event ->
                event.source() == FuzzInputEventSource.MODEL_CHOICE && event.step() > 0));
        int previousStep = -1;
        for (FuzzInputEvent event : first.inputEvents()) {
            assertTrue(event.step() >= previousStep);
            previousStep = event.step();
        }
    }

    @Test
    void paperDeviceAndLocalInputsRetainInitialAndSeedProvenance() {
        FuzzModel model = FuzzModel.from(snapshotWithDeterministicLocal(List.of()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(2);
        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(7L, List.of(new PaperEvent(
                        PaperEvent.Type.DEVICE, "switch_1", "on", 1))),
                domain,
                2,
                () -> false);

        assertTrue(simulation.inputEvents().stream().anyMatch(event ->
                event.kind() == FuzzInputEventKind.DEVICE_STATE
                        && event.source() == FuzzInputEventSource.RANDOM_INITIAL_STATE
                        && "switch_1".equals(event.targetId())));
        assertTrue(simulation.inputEvents().stream().anyMatch(event ->
                event.kind() == FuzzInputEventKind.DEVICE_VARIABLE
                        && event.source() == FuzzInputEventSource.RANDOM_INITIAL_STATE
                        && "firmware".equals(event.property())));
        assertTrue(simulation.inputEvents().stream().anyMatch(event ->
                event.kind() == FuzzInputEventKind.DEVICE_STATE
                        && event.source() == FuzzInputEventSource.SEED_EVENT
                        && "on".equals(event.value())));
    }

    @Test
    void paperStepZeroReplayOrdersBaselineThenOverridesThenOrdinaryEvents() {
        FuzzModel model = FuzzModel.from(snapshotWithMutableLocal(List.of()));
        PaperEventDomainSnapshot domain = model.paperEventDomain(2);
        long nonce = 73L;
        List<PaperInitialValue> baseline = domain.materializeInitialValues(nonce);
        List<PaperInitialValue> overrides = baseline.stream()
                .map(value -> new PaperInitialValue(
                        value.type(),
                        value.targetId(),
                        value.property(),
                        differentInitialValue(domain, value)))
                .toList();
        PaperInitialValue deviceBaseline = baseline.stream()
                .filter(value -> value.type() == PaperInitialValue.Type.DEVICE_STATE)
                .findFirst()
                .orElseThrow();
        PaperInitialValue environmentBaseline = baseline.stream()
                .filter(value -> value.type() == PaperInitialValue.Type.ENVIRONMENT_VALUE
                        && "weather".equals(value.property()))
                .findFirst()
                .orElseThrow();
        PaperEvent ordinary = new PaperEvent(
                PaperEvent.Type.DEVICE,
                deviceBaseline.targetId(),
                deviceBaseline.value(),
                1);
        PaperEvent laterOrdinary = new PaperEvent(
                PaperEvent.Type.ENVIRONMENT,
                environmentBaseline.property(),
                environmentBaseline.value(),
                2);
        PaperSeed seed = new PaperSeed(
                nonce, overrides, List.of(ordinary, laterOrdinary));

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                seed, domain, 2, () -> false);
        List<FuzzInputEvent> evidence = simulation.inputEvents();

        int baselineCount = baseline.size();
        int overrideCount = seed.initialOverrides().size();
        assertEquals(baselineCount + overrideCount + 1,
                evidence.stream().filter(event -> event.step() == 0).count());
        assertEquals(baseline.stream().map(PaperInitialValue::value).toList(),
                evidence.subList(0, baselineCount).stream()
                        .map(FuzzInputEvent::value)
                        .toList());
        assertTrue(evidence.subList(0, baselineCount).stream().allMatch(event ->
                event.source() == FuzzInputEventSource.RANDOM_INITIAL_STATE));
        assertEquals(seed.initialOverrides().stream().map(PaperInitialValue::value).toList(),
                evidence.subList(baselineCount, baselineCount + overrideCount).stream()
                        .map(FuzzInputEvent::value)
                        .toList());
        assertTrue(evidence.subList(baselineCount, baselineCount + overrideCount)
                .stream().allMatch(event ->
                        event.source() == FuzzInputEventSource.SEED_EVENT));
        FuzzInputEvent ordinaryEvidence = evidence.get(baselineCount + overrideCount);
        assertEquals(FuzzInputEventSource.SEED_EVENT, ordinaryEvidence.source());
        assertEquals(FuzzInputEventKind.DEVICE_STATE, ordinaryEvidence.kind());
        assertEquals(ordinary.value(), ordinaryEvidence.value());
        assertEquals(ordinary.value(),
                simulation.traceStates().get(0).getDevices().get(0).getState());
        List<FuzzInputEvent> stepOneEvidence = evidence.stream()
                .filter(event -> event.step() == 1)
                .toList();
        assertEquals(FuzzInputEventSource.SEED_EVENT,
                stepOneEvidence.get(0).source());
        assertEquals(laterOrdinary.value(), stepOneEvidence.get(0).value());
        assertTrue(stepOneEvidence.stream().skip(1).anyMatch(event ->
                event.source() == FuzzInputEventSource.MODEL_CHOICE));
    }

    @Test
    void paperLocalOnlyInitialTargetSupportsOverridesWithoutOrdinaryEvents() {
        FuzzModel model = FuzzModel.from(localOnlySnapshot());
        PaperEventDomainSnapshot domain = model.paperEventDomain(1);
        PaperInitialValue baseline = domain.materializeInitialValues(19L).get(0);
        PaperInitialValue override = new PaperInitialValue(
                PaperInitialValue.Type.DEVICE_VARIABLE,
                baseline.targetId(),
                baseline.property(),
                "beta".equals(baseline.value()) ? "alpha" : "beta");

        FuzzModel.Simulation simulation = model.simulatePaperSeed(
                new PaperSeed(19L, List.of(override), List.of()),
                domain,
                1,
                () -> false);

        assertTrue(domain.deviceDomains().isEmpty());
        assertTrue(domain.environmentDomains().isEmpty());
        assertEquals(1, domain.localVariableDomains().size());
        assertEquals(List.of(
                        FuzzInputEventSource.RANDOM_INITIAL_STATE,
                        FuzzInputEventSource.SEED_EVENT),
                simulation.inputEvents().stream().map(FuzzInputEvent::source).toList());
        assertEquals(override.value(), simulation.traceStates().get(0)
                .getDevices().get(0).getVariables().get(0).getValue());
    }

    @Test
    void paperEngineRunsACompletePopulationWithOnlyLocalInitialTargets() {
        SpecConditionDto legalProfile = conditionForDevice(
                "local_1", "variable", "profile", "in", "alpha,beta");
        SpecificationDto specification = specification(
                "local-profile-domain", "1", legalProfile);
        FuzzEngineConfig config = new FuzzEngineConfig(
                List.of(),
                2,
                1,
                4,
                29L,
                FuzzExplorationMode.PAPER_COMPATIBLE);

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(localOnlySnapshot(List.of(specification)), config),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.BUDGET_EXHAUSTED, result.outcome());
        assertEquals(2, result.iterations());
        assertEquals(8, result.generatedPaths());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void paperDistanceFollowsRulePredecessorConditions() {
        SpecificationDto neverOn = specification("never-on-paper", "3", stateCondition("on"));
        RuleDto turnOnWhenRain = RuleDto.builder()
                .id(71L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .targetType("variable")
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("turnOn")
                        .build())
                .build();
        FuzzModel withRule = FuzzModel.from(snapshot(
                List.of(neverOn), List.of(turnOnWhenRain), switchManifest()));
        FuzzModel withoutRule = FuzzModel.from(snapshot(
                List.of(neverOn), List.of(), switchManifest()));

        FuzzModel.Simulation ruleTrace = withRule.simulate(new long[]{1L}, 2);
        FuzzModel.Simulation plainTrace = withoutRule.simulate(new long[]{1L}, 2);
        double ruleAwareDistance = withRule.evaluatePaper(
                neverOn, ruleTrace, FuzzEngine.PAPER_SOLVER_LEVELS).distance();
        double plainDistance = withoutRule.evaluatePaper(
                neverOn, plainTrace, FuzzEngine.PAPER_SOLVER_LEVELS).distance();

        assertEquals("rain", traceEnvironmentValue(ruleTrace, 1, "weather"));
        assertTrue(ruleAwareDistance < plainDistance);
        assertEquals(1.0, plainDistance);
    }

    @Test
    void paperDistanceKeepsMissingDirectConjunctAsZeroOnlyAtFirstPredecessorLevel() {
        SpecConditionDto firstOn = conditionForDevice(
                "switch_1", "state", "state", "=", "on");
        SpecConditionDto secondOn = conditionForDevice(
                "switch_2", "state", "state", "=", "on");
        SpecificationDto neverBothOn = specification("never-both-on", "3", firstOn);
        neverBothOn.setAConditions(List.of(firstOn, secondOn));
        RuleDto turnFirstOnWhenCalm = RuleDto.builder()
                .id(74L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .targetType("variable")
                        .attribute("weather")
                        .relation("=")
                        .value("calm")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("turnOn")
                        .build())
                .build();
        DeviceManifest manifest = switchManifest();
        manifest.getApis().stream()
                .filter(api -> "turnOn".equals(api.getName()))
                .findFirst()
                .orElseThrow()
                .setStartState("");
        FuzzModel model = FuzzModel.from(twoSwitchSnapshot(
                List.of(neverBothOn), List.of(turnFirstOnWhenCalm), manifest));

        double distance = model.evaluatePaper(
                neverBothOn,
                model.simulate(new long[0], 1),
                FuzzEngine.PAPER_SOLVER_LEVELS).distance();

        assertEquals(6.0 / 7.0, distance, EPSILON);
    }

    @Test
    void paperDistanceFollowsTwoRulePredecessorLevels() {
        SpecificationDto neverThirdOn = specificationForDevice(
                "never-third-on", "3", "switch_3", "state", "state", "=", "on");
        RuleDto firstHop = RuleDto.builder()
                .id(72L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .targetType("state")
                        .attribute("state")
                        .relation("=")
                        .value("off")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_2")
                        .action("turnOn")
                        .build())
                .build();
        RuleDto secondHop = RuleDto.builder()
                .id(73L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_2")
                        .targetType("state")
                        .attribute("state")
                        .relation("=")
                        .value("on")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_3")
                        .action("turnOn")
                        .build())
                .build();
        DeviceManifest manifest = switchManifest();
        manifest.getApis().stream()
                .filter(api -> "turnOn".equals(api.getName()))
                .findFirst()
                .orElseThrow()
                .setStartState("");
        BoardDataConverter.ModelInputSnapshot snapshot = new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(
                        switchDevice("switch_1", "First switch"),
                        switchDevice("switch_2", "Second switch"),
                        switchDevice("switch_3", "Third switch")),
                List.of(new BoardEnvironmentVariableDto(
                        "weather", "calm", "trusted", "public")),
                List.of(firstHop, secondHop),
                List.of(neverThirdOn),
                Map.of("Switch", manifest));
        FuzzModel model = FuzzModel.from(snapshot);

        double distance = model.evaluatePaper(
                neverThirdOn,
                model.simulate(new long[0], 1),
                FuzzEngine.PAPER_SOLVER_LEVELS).distance();

        assertEquals(6.0 / 7.0, distance, EPSILON);
    }

    @Test
    void paperDistanceUsesBinaryAtomicSatisfactionForNumericInequalities() {
        SpecificationDto greaterOrEqual = specificationForDevice(
                "never-ge", "3", "numeric_1", "variable", "temperature", ">=", "3");
        SpecificationDto greater = specificationForDevice(
                "never-gt", "3", "numeric_1", "variable", "temperature", ">", "2");
        SpecificationDto lessOrEqual = specificationForDevice(
                "never-le", "3", "numeric_1", "variable", "temperature", "<=", "1");
        SpecificationDto less = specificationForDevice(
                "never-lt", "3", "numeric_1", "variable", "temperature", "<", "2");
        SpecificationDto equal = specificationForDevice(
                "never-eq", "3", "numeric_1", "variable", "temperature", "=", "3");
        SpecificationDto in = specificationForDevice(
                "never-in", "3", "numeric_1", "variable", "temperature", "in", "1,3");
        List<SpecificationDto> specifications = List.of(
                greaterOrEqual, greater, lessOrEqual, less, equal, in);
        FuzzModel model = FuzzModel.from(symmetricNumericSnapshot(specifications));
        FuzzModel.Simulation simulation = model.simulate(new long[0], 1);

        assertEquals(1.0, model.evaluatePaper(greaterOrEqual, simulation, 1).distance(), EPSILON);
        assertEquals(1.0, model.evaluatePaper(greater, simulation, 1).distance(), EPSILON);
        assertEquals(1.0, model.evaluatePaper(lessOrEqual, simulation, 1).distance(), EPSILON);
        assertEquals(1.0, model.evaluatePaper(less, simulation, 1).distance(), EPSILON);
        assertEquals(1.0, model.evaluatePaper(equal, simulation, 1).distance(), EPSILON);
        assertEquals(1.0, model.evaluatePaper(in, simulation, 1).distance(), EPSILON);
    }

    @Test
    void anUnobservableResponseHasNoFiniteGuidance() {
        // A response needs a successor state. A one-state path supplies no measured progress.
        SpecificationDto immediate = immediateSpecification("immediate-unobservable");
        immediate.setIfConditions(List.of(conditionForDevice(
                "local_1", "variable", "profile", "=", "alpha")));
        immediate.setThenConditions(List.of(conditionForDevice(
                "local_1", "variable", "profile", "=", "alpha")));
        FuzzModel model = FuzzModel.from(localOnlySnapshot(List.of(immediate)));
        model.validateSpecification(immediate);
        FuzzModel.Simulation singleState = model.simulate(new long[0], 1);
        assertEquals(1, singleState.states().size(), "pathLength 1 must yield only the initial state");

        FuzzModel.SpecEvaluation evaluation = model.evaluate(immediate, singleState);

        assertEquals(-1, evaluation.violationStep(), "no violation is observable without a successor state");
        assertTrue(Double.isInfinite(evaluation.distance()),
                "an unobservable specification scored " + evaluation.distance()
                        + ", manufacturing guidance without an observable response");
    }

    @Test
    void paperApiPredecessorsHandleNegativePolarityAndRequireAStateChange() {
        SpecificationDto apiMustStayFalse = specificationForDevice(
                "api-must-stay-false", "1", "signal_1", "api", "turnedOn", "=", "FALSE");
        RuleDto signalWhenRain = RuleDto.builder()
                .id(91L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("signal_1")
                        .targetType("variable")
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("signal_1")
                        .action("turnedOn")
                        .build())
                .build();

        FuzzModel changing = FuzzModel.from(signalSwitchSnapshot(
                List.of(apiMustStayFalse), List.of(signalWhenRain), "off", false));
        FuzzModel noOp = FuzzModel.from(signalSwitchSnapshot(
                List.of(apiMustStayFalse), List.of(signalWhenRain), "on", true));
        FuzzModel noRule = FuzzModel.from(signalSwitchSnapshot(
                List.of(apiMustStayFalse), List.of(), "off", false));

        double changingDistance = changing.evaluatePaper(
                apiMustStayFalse, changing.simulate(new long[0], 1), 3).distance();
        double noOpDistance = noOp.evaluatePaper(
                apiMustStayFalse, noOp.simulate(new long[0], 1), 3).distance();
        double noRuleDistance = noRule.evaluatePaper(
                apiMustStayFalse, noRule.simulate(new long[0], 1), 3).distance();

        assertEquals(5.0 / 7.0, changingDistance, EPSILON);
        assertEquals(6.0 / 7.0, noOpDistance, EPSILON);
        assertEquals(1.0, noRuleDistance, EPSILON);
    }

    @Test
    void paperRulePredecessorsAccountForEarlierOverlappingActions() {
        SpecificationDto neverZulu = new SpecificationDto();
        neverZulu.setId("never-zulu");
        neverZulu.setName("Safety requirement");
        neverZulu.setTemplateId("3");
        neverZulu.setAConditions(List.of(conditionForDevice(
                "multi_1", "mode", "ModeB", "=", "zulu")));
        neverZulu.setIfConditions(List.of());
        neverZulu.setThenConditions(List.of());
        RuleDto highPriority = RuleDto.builder()
                .id(81L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("multi_1")
                        .targetType("mode")
                        .attribute("ModeA")
                        .relation("=")
                        .value("alpha")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("multi_1")
                        .action("changeA")
                        .build())
                .build();
        RuleDto lowPriority = RuleDto.builder()
                .id(82L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("multi_1")
                        .targetType("mode")
                        .attribute("ModeA")
                        .relation("=")
                        .value("alpha")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("multi_1")
                        .action("changeBoth")
                        .build())
                .build();
        FuzzModel lowOnly = FuzzModel.from(multiModeSnapshot(
                List.of(neverZulu), List.of(lowPriority)));
        FuzzModel blocked = FuzzModel.from(multiModeSnapshot(
                List.of(neverZulu), List.of(highPriority, lowPriority)));
        RuleDto inactiveHighPriority = RuleDto.builder()
                .id(83L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("multi_1")
                        .targetType("mode")
                        .attribute("ModeA")
                        .relation("=")
                        .value("beta")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("multi_1")
                        .action("changeA")
                        .build())
                .build();
        FuzzModel inactiveBlocker = FuzzModel.from(multiModeSnapshot(
                List.of(neverZulu), List.of(inactiveHighPriority, lowPriority)));

        double lowOnlyDistance = lowOnly.evaluatePaper(
                neverZulu,
                lowOnly.simulate(new long[]{0L}, 1),
                FuzzEngine.PAPER_SOLVER_LEVELS).distance();
        double blockedDistance = blocked.evaluatePaper(
                neverZulu,
                blocked.simulate(new long[]{0L}, 1),
                FuzzEngine.PAPER_SOLVER_LEVELS).distance();
        double inactiveBlockerDistance = inactiveBlocker.evaluatePaper(
                neverZulu,
                inactiveBlocker.simulate(new long[]{0L}, 1),
                FuzzEngine.PAPER_SOLVER_LEVELS).distance();

        assertEquals(5.0 / 7.0, lowOnlyDistance, EPSILON);
        assertEquals(17.0 / 21.0, blockedDistance, EPSILON);
        assertEquals(lowOnlyDistance, inactiveBlockerDistance, EPSILON);
    }

    @Test
    void fixedSeedProducesTheSameSearchArtifacts() {
        SpecificationDto specification = specification("never-rain", "3", variableCondition("rain"));
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(specification),
                List.of(),
                switchManifest());
        FuzzEngineConfig config = new FuzzEngineConfig(List.of(), 3, 4, 6, Long.MIN_VALUE);

        FuzzEngineResult first = engine.run(new FuzzEngineInput(snapshot, config), null, () -> false);
        FuzzEngineResult second = engine.run(new FuzzEngineInput(snapshot, config), null, () -> false);

        assertEquals(first.effectiveSeed(), second.effectiveSeed());
        assertTrue(first.effectiveSeed() >= 0);
        assertTrue(first.effectiveSeed() <= FuzzEngine.MAX_SAFE_SEED);
        assertEquals(first.outcome(), second.outcome());
        assertEquals(first.iterations(), second.iterations());
        assertEquals(first.generatedPaths(), second.generatedPaths());
        assertEquals(first.findings(), second.findings());
        assertEquals(first.eligibility(), second.eligibility());
        assertTrue(first.limitations().stream().allMatch(value -> value.matches("[A-Z0-9_]+")));
    }

    @Test
    void reportsTemplateAndTrustPrivacyEligibilityPerSpecification() {
        SpecificationDto always = specification("always", "1", stateCondition("off"));
        SpecificationDto never = specification("never", "3", stateCondition("on"));
        SpecificationDto immediate = immediateSpecification("immediate");
        SpecificationDto unsupportedTemplate = specification("eventually", "2", stateCondition("off"));
        SpecificationDto trust = specification("trust", "1", condition("trust", "PowerMode", "=", "trusted"));
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(always, never, immediate, unsupportedTemplate, trust),
                List.of(),
                switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 0, 3, 2, 5L)),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.BUDGET_EXHAUSTED, result.outcome());
        assertTrue(result.eligibility().get(0).supported());
        assertTrue(result.eligibility().get(1).supported());
        assertTrue(result.eligibility().get(2).supported());
        assertEquals("UNSUPPORTED_TEMPLATE", result.eligibility().get(3).reasonCode());
        assertEquals("TRUST_PRIVACY_UNSUPPORTED", result.eligibility().get(4).reasonCode());
    }

    @Test
    void reportsAReportedReadingAsUnexploredRatherThanAnsweringTheEnvironmentQuestion() {
        /*
         * The explorer keeps ONE value per shared reading and models no compromised device, so it cannot
         * tell "what this device said" from "what the home held". Left eligible, such a specification was
         * evaluated against the pool value and handed a verdict — the falsification case the author asked
         * about was never covered and nothing said so. `environment` stays eligible, because that is the one
         * question the single value does represent, and a legacy condition that never chose is blocked at the
         * run gate rather than mislabelled here.
         */
        SpecConditionDto reported = condition("variable", "weather", "=", "rain");
        reported.setVariableSource("reported");
        SpecConditionDto environment = condition("variable", "weather", "=", "rain");
        environment.setVariableSource("environment");
        SpecConditionDto unchosen = condition("variable", "weather", "=", "rain");

        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(specification("reported", "1", reported),
                        specification("environment", "1", environment),
                        specification("unchosen", "1", unchosen)),
                List.of(),
                switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 0, 3, 2, 5L)),
                null,
                () -> false);

        assertEquals("REPORTED_READING_UNSUPPORTED", result.eligibility().get(0).reasonCode());
        assertTrue(result.eligibility().get(1).supported(),
                "the environment reading is exactly what the explorer's single value represents");
        assertTrue(result.eligibility().get(2).supported(),
                "an unchosen reading is blocked at the run gate, not reported as unexplorable here");
    }

    @Test
    void usesTypedUnsupportedSemanticsInsteadOfIdentifierSubstrings() {
        SpecificationDto ordinary = specification(
                "ordinary", "1", conditionForDevice("attack_alarm", "state", "state", "=", "off"));
        RuleDto ordinaryRule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("attack_alarm")
                        .targetType("variable")
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("attack_alarm")
                        .action("privacyMode")
                        .build())
                .build();
        DeviceManifest ordinaryManifest = switchManifest();
        List<API> ordinaryApis = new ArrayList<>(ordinaryManifest.getApis());
        ordinaryApis.add(API.builder().name("privacyMode").signal(false).acceptsContent(false)
                .startState("off").endState("on").build());
        ordinaryManifest.setApis(ordinaryApis);
        ordinaryManifest.getInternalVariables().get(0).setFalsifiableWhenCompromised(true);
        ordinaryManifest.setContents(List.of(Content.builder().name("message").privacy("private").build()));
        BoardDataConverter.ModelInputSnapshot ordinarySnapshot = new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(switchDevice("attack_alarm", "Attack alarm")),
                List.of(new BoardEnvironmentVariableDto("weather", "calm", "trusted", "public")),
                List.of(ordinaryRule),
                List.of(ordinary),
                Map.of("Switch", ordinaryManifest));

        FuzzEngineResult ordinaryResult = engine.run(
                new FuzzEngineInput(ordinarySnapshot, new FuzzEngineConfig(List.of(), 0, 2, 1, 1L)),
                null, () -> false);
        assertTrue(ordinaryResult.eligibility().get(0).supported());

        RuleDto contentRule = RuleDto.builder()
                .conditions(ordinaryRule.getConditions())
                .command(RuleDto.Command.builder()
                        .deviceName("attack_alarm")
                        .action("sendMessage")
                        .contentDevice("phone")
                        .content("hello")
                        .build())
                .build();
        FuzzEngineResult contentResult = engine.run(
                new FuzzEngineInput(new BoardDataConverter.ModelInputSnapshot(
                        List.of(), List.of(switchDevice("attack_alarm", "Attack alarm")), List.of(),
                        List.of(contentRule), List.of(ordinary), Map.of("Switch", ordinaryManifest)),
                        new FuzzEngineConfig(List.of(), 0, 2, 1, 1L)), null, () -> false);
        assertEquals("CONTENT_COMMAND_UNSUPPORTED", contentResult.eligibility().get(0).reasonCode());
    }

    @Test
    void findsViolationAfterGeneratedEnvironmentInputTriggersRule() {
        SpecificationDto neverOn = specification("never-on", "3", stateCondition("on"));
        RuleDto turnOnWhenRain = RuleDto.builder()
                .id(7L)
                .ruleString("When rain is rain, turn on")
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .targetType("variable")
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("turnOn")
                        .build())
                .build();
        RuleDto laterKeepOffRule = RuleDto.builder()
                .id(8L)
                .ruleString("Later conflicting rule keeps the switch off")
                .conditions(turnOnWhenRain.getConditions())
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("keepOff")
                        .build())
                .build();
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(neverOn),
                List.of(turnOnWhenRain, laterKeepOffRule),
                switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 2, 3, 16, 1L)),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.FINDINGS_FOUND, result.outcome());
        assertEquals(1, result.findings().size());
        FuzzFinding finding = result.findings().get(0);
        assertEquals(2, finding.firstViolationStep());
        assertEquals("on", finding.states().get(2).getDevices().get(0).getState());
        assertEquals("7", finding.states().get(2).getTriggeredRules().get(0).getRuleId());
        assertTrue(finding.inputEvents().stream().anyMatch(event ->
                event.kind() == FuzzInputEventKind.ENVIRONMENT_VALUE
                        && "weather".equals(event.property())
                        && "rain".equals(event.value())));
    }

    @Test
    void exhaustsExactIterationBudgetWhenNoViolationExists() {
        SpecificationDto alwaysOff = specification("always-off", "1", stateCondition("off"));
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(alwaysOff),
                List.of(),
                switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 2, 3, 4, 42L)),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.BUDGET_EXHAUSTED, result.outcome());
        assertEquals(2, result.iterations());
        assertEquals(8, result.generatedPaths());
        assertTrue(result.findings().isEmpty());
        assertFalse(result.eligibility().isEmpty());
        assertTrue(result.eligibility().get(0).supported());
    }

    @Test
    void blocksLowerPriorityMultiModeActionAsAWholeWhenAnyModeOverlaps() {
        RuleDto.Condition initialTuple = RuleDto.Condition.builder()
                .deviceName("multi_1")
                .targetType("state")
                .attribute("state")
                .relation("=")
                .value("alpha;xray")
                .build();
        RuleDto higherPriority = RuleDto.builder()
                .id(11L)
                .conditions(List.of(initialTuple))
                .command(RuleDto.Command.builder().deviceName("multi_1").action("changeB").build())
                .build();
        RuleDto lowerPriority = RuleDto.builder()
                .id(12L)
                .conditions(List.of(initialTuple))
                .command(RuleDto.Command.builder().deviceName("multi_1").action("changeBoth").build())
                .build();
        SpecConditionDto forbiddenTuple = conditionForDevice(
                "multi_1", "state", "state", "=", "alpha;yellow");
        SpecificationDto neverAtomicResult = specification("atomic-order", "3", forbiddenTuple);
        BoardDataConverter.ModelInputSnapshot snapshot = multiModeSnapshot(
                List.of(neverAtomicResult), List.of(higherPriority, lowerPriority));

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 2, 1, 3L)),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.FINDINGS_FOUND, result.outcome());
        FuzzFinding finding = result.findings().get(0);
        assertEquals(1, finding.firstViolationStep());
        assertEquals("alpha;yellow", finding.states().get(1).getDevices().get(0).getState());
        assertEquals(List.of("11"), finding.states().get(1).getTriggeredRules().stream()
                .map(rule -> rule.getRuleId())
                .toList());
    }

    @Test
    void allowsMatchingRulesWithDisjointAffectedModesToRunTogether() {
        RuleDto.Condition initialTuple = RuleDto.Condition.builder()
                .deviceName("multi_1")
                .targetType("state")
                .attribute("state")
                .relation("=")
                .value("alpha;xray")
                .build();
        RuleDto changeB = RuleDto.builder()
                .id(31L)
                .conditions(List.of(initialTuple))
                .command(RuleDto.Command.builder().deviceName("multi_1").action("changeB").build())
                .build();
        RuleDto changeA = RuleDto.builder()
                .id(32L)
                .conditions(List.of(initialTuple))
                .command(RuleDto.Command.builder().deviceName("multi_1").action("changeA").build())
                .build();
        RuleDto blockedCombined = RuleDto.builder()
                .id(33L)
                .conditions(List.of(initialTuple))
                .command(RuleDto.Command.builder().deviceName("multi_1").action("changeBoth").build())
                .build();
        SpecificationDto neverConcurrentResult = specification(
                "disjoint-order",
                "3",
                conditionForDevice("multi_1", "state", "state", "=", "beta;yellow"));

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(
                        multiModeSnapshot(
                                List.of(neverConcurrentResult),
                                List.of(changeB, changeA, blockedCombined)),
                        new FuzzEngineConfig(List.of(), 1, 2, 1, 3L)),
                null,
                () -> false);

        FuzzFinding finding = result.findings().get(0);
        assertEquals("beta;yellow", finding.states().get(1).getDevices().get(0).getState());
        assertEquals(List.of("31", "32"), finding.states().get(1).getTriggeredRules().stream()
                .map(rule -> rule.getRuleId())
                .toList());
    }

    @Test
    void reportsProgressOnlyWhenTheIntegerPercentChanges() {
        SpecificationDto alwaysOff = specification("progress", "1", stateCondition("off"));
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(alwaysOff), List.of(), switchManifest());
        FuzzEngineConfig config = new FuzzEngineConfig(List.of(), 5_000, 1, 1, 42L);
        List<Integer> progressUpdates = new ArrayList<>();

        FuzzEngineResult observed = engine.run(
                new FuzzEngineInput(snapshot, config),
                (percent, message) -> progressUpdates.add(percent),
                () -> false);
        FuzzEngineResult silent = engine.run(
                new FuzzEngineInput(snapshot, config), null, () -> false);

        assertTrue(progressUpdates.size() <= 101);
        assertEquals(0, progressUpdates.get(0));
        assertEquals(100, progressUpdates.get(progressUpdates.size() - 1));
        assertEquals(progressUpdates.size(), progressUpdates.stream().distinct().count());
        assertEquals(silent.outcome(), observed.outcome());
        assertEquals(silent.iterations(), observed.iterations());
        assertEquals(silent.generatedPaths(), observed.generatedPaths());
        assertEquals(silent.findings(), observed.findings());
    }

    @Test
    void groupedTargetRulesStillReadConditionsFromOtherDevices() {
        RuleDto crossDeviceRule = RuleDto.builder()
                .id(41L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_2")
                        .targetType("state")
                        .attribute("state")
                        .relation("=")
                        .value("off")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("turnOn")
                        .build())
                .build();
        SpecificationDto neverOn = specification(
                "cross-device", "3", conditionForDevice("switch_1", "state", "state", "=", "on"));

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(
                        twoSwitchSnapshot(List.of(neverOn), List.of(crossDeviceRule)),
                        new FuzzEngineConfig(List.of(), 1, 2, 1, 8L)),
                null,
                () -> false);

        FuzzFinding finding = result.findings().get(0);
        assertEquals("on", finding.states().get(1).getDevices().get(0).getState());
        assertEquals("off", finding.states().get(1).getDevices().get(1).getState());
        assertEquals("41", finding.states().get(1).getTriggeredRules().get(0).getRuleId());
    }

    @Test
    void parsesEveryBundledManifestWithExplicitCanonicalInitialValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<Path> manifestFiles;
        try (var paths = Files.list(Path.of("src/main/resources/deviceTemplate"))) {
            manifestFiles = paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }

        for (Path path : manifestFiles) {
            assertDoesNotThrow(() -> {
                DeviceManifest manifest = objectMapper.readValue(path.toFile(), DeviceManifest.class);
                FuzzModel.from(explicitSnapshotForManifest(manifest));
            }, path.toString());
        }
    }

    @Test
    void mutatesTheOnlyGenomeBetweenIterations() {
        SpecificationDto neverOn = specification("single-population", "3", stateCondition("on"));
        RuleDto turnOnWhenRain = RuleDto.builder()
                .id(21L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .targetType("variable")
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("turnOn")
                        .build())
                .build();
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(neverOn), List.of(turnOnWhenRain), switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 2, 3, 1, 4L)),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.FINDINGS_FOUND, result.outcome());
        assertEquals(2, result.iterations());
        assertEquals(2, result.generatedPaths());
        assertEquals(2, result.findings().get(0).firstViolationStep());
    }

    @Test
    void tracksOnlySemanticChoicesAndSchedulesSinglePopulationRestarts() {
        SpecificationDto neverRain = specification("semantic-choices", "3", variableCondition("rain"));
        FuzzModel model = FuzzModel.from(snapshotWithDeterministicLocal(List.of(neverRain)));

        FuzzModel.Simulation simulation = model.simulate(new long[]{0L, 0L, 0L, 0L}, 3);

        assertEquals(Map.of(1, 2, 3, 2), simulation.mutableChoiceBounds());
        assertFalse(simulation.cancelled());
        assertFalse(FuzzEngine.isRandomRestartSlot(19, 1, 0));
        assertTrue(FuzzEngine.isRandomRestartSlot(20, 1, 0));
        assertFalse(FuzzEngine.isRandomRestartSlot(21, 1, 0));
        assertEquals(19L, FuzzEngine.parentOrdinalForSlot(21, 1, 0));
    }

    @Test
    void aMutatedGeneStillReachesEveryChoiceWhenItsSlotLaterOffersMore() {
        // A gene is a residue, not a choice index: GeneCursor decodes floorMod(gene, bound), and the same
        // gene index sees different bounds on different trajectories because nextCandidates clamps and dedups
        // at a domain edge. Mutation used to store the small index, which destroyed the residues for every
        // larger bound — a gene mutated while its slot showed 2 candidates could then never select index 2
        // once the slot showed 3, and a bound-2 mutation orbited {0,1} permanently.
        //
        // Pinned as a property of the arithmetic, because that is where the loss happened. Mutating at the
        // narrow bound must leave the gene able to decode to every choice at the wider bound.
        int narrowBound = 2;
        int widerBound = 3;
        Set<Integer> reachableAtWiderBound = new LinkedHashSet<>();
        for (long seed = 0; seed < 400; seed++) {
            long gene = new SplittableRandom(seed).nextLong();
            for (int step = 0; step < 8; step++) {
                gene = FuzzEngine.mutatedGene(gene, narrowBound, new SplittableRandom(seed + step));
                // The mutation must still be correct at the bound it was applied for.
                assertTrue(Math.floorMod(gene, (long) narrowBound) < narrowBound);
                reachableAtWiderBound.add((int) Math.floorMod(gene, (long) widerBound));
            }
        }

        assertEquals(Set.of(0, 1, 2), reachableAtWiderBound,
                "a repeatedly mutated gene lost the residues for a wider bound, so the search can no "
                        + "longer reach every candidate at that slot");
    }

    @Test
    void anExhaustedTimeBudgetEndsTheSearchAsBoundedRatherThanCancelled() {
        // A deadline is not a cancellation. Routing it through `cancelled` would make the service settle the
        // task as CANCELLED — reporting a timeout as something the user did, and persisting no result. It
        // ends the search the way a spent iteration budget does, and discloses why.
        SpecificationDto unviolable = specification("always-off", "1", stateCondition("off"));
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(unviolable), List.of(), switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 500, 5, 4, 11L)),
                null,
                () -> false,
                () -> true);

        assertEquals(FuzzEngineOutcome.BUDGET_EXHAUSTED, result.outcome());
        assertTrue(result.limitations().contains(FuzzEngine.TIME_BUDGET_EXHAUSTED_LIMITATION),
                "a run the clock ended must disclose it, or it is indistinguishable from a full search");
        // Stopped before the requested budget, and says so truthfully rather than claiming the full run.
        assertTrue(result.iterations() < 500,
                "iterations " + result.iterations() + " should be short of the requested budget");
    }

    @Test
    void anUnexpiredTimeBudgetAddsNoLimitationAndRunsTheFullSearch() {
        // The mirror case: the disclosure must not appear on an ordinary run, or it stops meaning anything.
        SpecificationDto unviolable = specification("always-off", "1", stateCondition("off"));
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(unviolable), List.of(), switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 3, 5, 2, 11L)),
                null,
                () -> false,
                () -> false);

        assertEquals(FuzzEngineOutcome.BUDGET_EXHAUSTED, result.outcome());
        assertFalse(result.limitations().contains(FuzzEngine.TIME_BUDGET_EXHAUSTED_LIMITATION));
        assertEquals(3, result.iterations());
    }

    @Test
    void aCancelledRunIsNeverRelabelledAsATimeout() {
        // Both signals true at once: cancellation is the user's own decision and must win, because the
        // service persists no result for it. A timeout label here would claim a completed bounded search.
        SpecificationDto unviolable = specification("always-off", "1", stateCondition("off"));
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                List.of(unviolable), List.of(), switchManifest());

        FuzzEngineResult result = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 50, 5, 4, 11L)),
                null,
                () -> true,
                () -> true);

        assertEquals(FuzzEngineOutcome.CANCELLED, result.outcome());
        assertFalse(result.limitations().contains(FuzzEngine.TIME_BUDGET_EXHAUSTED_LIMITATION));
    }

    @Test
    void aMutatedGeneSurvivesTheSignedLongBoundaryWithoutWrapping() {
        // Both halves of the residue rebuild can leave the long range: `gene - currentChoice` underflows at
        // Long.MIN_VALUE with an odd bound (floorMod is non-negative, so it subtracts), and `base + nextChoice`
        // overflows at Long.MAX_VALUE when raising to a higher choice — landing on Long.MIN_VALUE, which
        // decodes back to the original choice, i.e. a mutation that silently does nothing. Every extreme is
        // covered here rather than a sampled few, because each bound parity hits a different branch.
        for (long gene : new long[]{
                Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 2,
                Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 2, 0L, -1L}) {
            for (int bound = 2; bound <= 8; bound++) {
                long mutated = FuzzEngine.mutatedGene(gene, bound, new SplittableRandom(7L));
                int before = (int) Math.floorMod(gene, (long) bound);
                int after = (int) Math.floorMod(mutated, (long) bound);
                assertTrue(after >= 0 && after < bound,
                        "gene " + gene + " at bound " + bound + " decoded to " + after);
                assertNotEquals(before, after,
                        "mutation left the choice unchanged at gene " + gene + ", bound " + bound);
            }
        }
    }

    @Test
    void paperParentRotationEventuallyUsesCandidatesBeyondPopulationSize() {
        int populationSize = 2;
        int parentCount = 5;
        Set<Integer> visited = new LinkedHashSet<>();

        for (int generation = 1; generation <= 3; generation++) {
            for (int slot = 0; slot < populationSize; slot++) {
                visited.add(FuzzEngine.paperParentIndexForSlot(
                        generation,
                        populationSize,
                        slot,
                        parentCount));
            }
        }

        assertEquals(Set.of(0, 1, 2, 3, 4), visited);
        assertEquals(0, FuzzEngine.paperParentIndexForSlot(1, populationSize, 0, parentCount));
        assertEquals(2, FuzzEngine.paperParentIndexForSlot(2, populationSize, 0, parentCount));
        assertEquals(4, FuzzEngine.paperParentIndexForSlot(3, populationSize, 0, parentCount));
    }

    @Test
    void stopsOnlyAfterEveryMonitoredTargetHasAViolatingPrefix() {
        SpecificationDto neverOff = specification("never-off", "3", stateCondition("off"));
        SpecificationDto neverRain = specification("never-rain", "3", variableCondition("rain"));
        FuzzModel model = FuzzModel.from(snapshot(
                List.of(neverOff, neverRain),
                List.of(),
                switchManifest()));
        model.validateSpecification(neverOff);
        model.validateSpecification(neverRain);

        FuzzModel.Simulation firstTargetOnly = model.simulate(
                new long[]{1L}, 10, () -> false, List.of(neverOff));
        FuzzModel.Simulation bothTargets = model.simulate(
                new long[]{1L}, 10, () -> false, List.of(neverOff, neverRain));

        assertEquals(1, firstTargetOnly.traceStates().size());
        assertEquals(2, bothTargets.traceStates().size());
        assertEquals(0, model.evaluate(neverOff, bothTargets).violationStep());
        assertEquals(1, model.evaluate(neverRain, bothTargets).violationStep());
    }

    @Test
    void cancelsInsidePathFormationWithoutPublishingAPartialState() {
        FuzzModel model = FuzzModel.from(snapshot(
                List.of(specification("cancel", "1", stateCondition("off"))),
                List.of(),
                switchManifest()));
        AtomicInteger checks = new AtomicInteger();

        FuzzModel.Simulation simulation = model.simulate(
                new long[20],
                20,
                () -> checks.incrementAndGet() >= 3,
                List.of());

        assertTrue(simulation.cancelled());
        assertEquals(1, simulation.traceStates().size());
        assertTrue(simulation.inputEvents().isEmpty());
    }

    @Test
    void keepsIndependentGuidanceForOpposingSpecifications() {
        SpecificationDto neverLow = specificationForDevice(
                "never-low", "3", "numeric_1", "variable", "temperature", "=", "0");
        SpecificationDto neverHigh = specificationForDevice(
                "never-high", "3", "numeric_1", "variable", "temperature", "=", "4");
        BoardDataConverter.ModelInputSnapshot snapshot = symmetricNumericSnapshot(List.of(neverLow, neverHigh));
        long regressionSeed = 160L;
        FuzzEngineConfig config = new FuzzEngineConfig(List.of(), 2, 3, 2, regressionSeed);

        FuzzEngineResult initialPopulation = engine.run(
                new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 1, 3, 2, regressionSeed)),
                null,
                () -> false);
        FuzzEngineResult first = engine.run(new FuzzEngineInput(snapshot, config), null, () -> false);
        FuzzEngineResult second = engine.run(new FuzzEngineInput(snapshot, config), null, () -> false);

        assertTrue(initialPopulation.findings().isEmpty());
        assertEquals(FuzzEngineOutcome.FINDINGS_FOUND, first.outcome());
        assertEquals(2, first.iterations());
        assertEquals(2, first.findings().size());
        assertTrue(first.findings().stream().anyMatch(finding ->
                "never-low".equals(finding.specification().getId())));
        assertTrue(first.findings().stream().anyMatch(finding ->
                "never-high".equals(finding.specification().getId())));
        assertEquals(first.findings(), second.findings());
        assertEquals(first.generatedPaths(), second.generatedPaths());
    }

    /**
     * MEDIC §3.1, Fig. 2b combines a device's effect with the environment step contemporaneously, so
     * a device that is already in an acting state moves the value on the very first transition. The
     * snapshot starts in {@code cool} (ChangeRate -1) with drift {@code [-1, 1]}, so seed 0 gives
     * 50 - 1 - 1 = 48. Reading a stored rate instead produced 49 and delayed every effect by a step.
     */
    @Test
    void numericEnvironmentAppliesTheActiveImpactRateInTheSameStep() {
        FuzzModel centered = FuzzModel.from(numericImpactSnapshot("50"));
        FuzzModel.Simulation centeredPath = centered.simulate(new long[]{0L, 0L}, 3);

        assertEquals("48", centeredPath.traceStates().get(1).getEnvVariables().get(0).getValue());
        assertEquals("46", centeredPath.traceStates().get(2).getEnvVariables().get(0).getValue());

        FuzzModel upperBoundary = FuzzModel.from(numericImpactSnapshot("100"));
        FuzzModel.Simulation boundaryPath = upperBoundary.simulate(new long[]{0L}, 2);

        assertEquals("98", boundaryPath.traceStates().get(1).getEnvVariables().get(0).getValue());
    }

    @Test
    void numericEnvironmentWithExplicitZeroNaturalRateDoesNotInventBoundaryDisturbance() {
        FuzzModel model = FuzzModel.from(numericImpactSnapshot("100", "0"));
        FuzzModel.Simulation path = model.simulate(new long[]{0L}, 2);

        // NaturalChangeRate=0 adds no drift, but the device's own declared cooling effect still
        // applies in this step: 100 - 1 = 99.
        assertEquals("99", path.traceStates().get(1).getEnvVariables().get(0).getValue());
    }

    /**
     * A declared interval constrains the per-step change, so the explorer must be able to reach
     * every value in it — not just the endpoints, and not only whichever candidate one seed happens
     * to select. Asserting a single seed's pick pinned list order rather than semantics.
     */
    @Test
    void numericEnvironmentCanReachEveryValueTheDeclaredIntervalAdmits() {
        Set<String> reachable = new HashSet<>();
        for (long seed = 0L; seed < 64L; seed++) {
            FuzzModel model = FuzzModel.from(numericImpactSnapshot("10", "[2,3]", false));
            reachable.add(model.simulate(new long[]{seed}, 2)
                    .traceStates().get(1).getEnvVariables().get(0).getValue());
        }

        // "[2, 3]" excludes 0, so every step must rise by 2 or 3. Holding still would be a
        // different declaration ([0, 3]) and the explorer must not invent it.
        assertEquals(Set.of("12", "13"), reachable);
    }

    @Test
    void numericLocalVariableCanReachEveryValueTheDeclaredIntervalAdmits() {
        Set<String> reachable = new HashSet<>();
        for (long seed = 0L; seed < 64L; seed++) {
            FuzzModel model = FuzzModel.from(numericLocalSnapshot("10", "[2,3]"));
            reachable.add(model.simulate(new long[]{seed}, 2)
                    .traceStates().get(1).getDevices().get(0).getVariables().get(0).getValue());
        }

        assertEquals(Set.of("12", "13"), reachable);
    }

    @Test
    void boundedModelRejectsRateSyntaxThatFormalAndTemplateAdmissionReject() {
        IllegalArgumentException unbracketed = assertThrows(IllegalArgumentException.class,
                () -> FuzzModel.from(numericImpactSnapshot("10", "1,2", false)));
        assertTrue(unbracketed.getMessage().contains("NaturalChangeRate '1,2'"),
                unbracketed.getMessage());

        IllegalArgumentException extraEndpoint = assertThrows(IllegalArgumentException.class,
                () -> FuzzModel.from(numericLocalSnapshot("10", "[1,2,999]")));
        assertTrue(extraEndpoint.getMessage().contains("NaturalChangeRate '[1,2,999]'"),
                extraEndpoint.getMessage());
    }

    @Test
    void numericSetGuidanceApproachesTheNearestMember() {
        for (String separator : List.of(",", ";", "|")) {
            String members = "60" + separator + "50" + separator + "60";
            for (String template : List.of("1", "3")) {
                String relation = "1".equals(template) ? "not in" : "in";
                var far = numericScoreAt("0", template, relation, members);
                var near = numericScoreAt("49", template, relation, members);
                var otherSide = numericScoreAt("61", template, relation, members);
                var member = numericScoreAt("50", template, relation, members);

                assertEquals(-1, far.violationStep());
                assertEquals(-1, near.violationStep());
                assertEquals(0.5, far.distance(), EPSILON);
                assertEquals(0.01, near.distance(), EPSILON);
                assertEquals(near.distance(), otherSide.distance(), EPSILON);
                assertEquals(0, member.violationStep());
                assertEquals(0.0, member.distance());
            }
        }
    }

    @Test
    void numericSetGuidanceApproachesTheNearestLegalNonMember() {
        for (String template : List.of("1", "3")) {
            String relation = "1".equals(template) ? "in" : "not in";
            var middle = numericScoreAt("6", template, relation, "7,5,6,6");
            var edge = numericScoreAt("5", template, relation, "5,6,7");
            var outside = numericScoreAt("4", template, relation, "5,6,7");
            var lowerEdge = numericScoreAt("0", template, relation, "0,1");
            var upperEdge = numericScoreAt("100", template, relation, "99,100");

            assertEquals(-1, middle.violationStep());
            assertEquals(-1, edge.violationStep());
            assertEquals(0.02, middle.distance(), EPSILON);
            assertEquals(0.01, edge.distance(), EPSILON);
            assertEquals(0, outside.violationStep());
            assertEquals(0.0, outside.distance());
            assertEquals(0.02, lowerEdge.distance(), EPSILON);
            assertEquals(0.02, upperEdge.distance(), EPSILON);
        }
    }

    @Test
    void numericResponseGuidanceUsesSetDistancesOnBothSides() {
        var response = immediateSpecification("numeric-response");
        response.setIfConditions(List.of(conditionForDevice(
                "counter_1", "variable", "count", "in", "50,60")));
        response.setThenConditions(List.of(conditionForDevice(
                "counter_1", "variable", "count", "<", "0")));
        var farModel = FuzzModel.from(numericLocalSnapshot("0", "[-100,100]"));
        var nearModel = FuzzModel.from(numericLocalSnapshot("49", "[-100,100]"));
        farModel.validateSpecification(response);
        nearModel.validateSpecification(response);
        var far = farModel.evaluate(response, farModel.simulate(new long[]{0, 0}, 3));
        var near = nearModel.evaluate(response, nearModel.simulate(new long[]{49, 49}, 3));
        assertEquals(-1, far.violationStep());
        assertEquals(-1, near.violationStep());
        assertTrue(near.distance() < far.distance());

        response.setIfConditions(List.of(conditionForDevice(
                "counter_1", "variable", "count", ">=", "0")));
        response.setThenConditions(List.of(conditionForDevice(
                "counter_1", "variable", "count", "in", "5,6,7")));
        var middleModel = FuzzModel.from(numericLocalSnapshot("6", "[-100,100]"));
        var edgeModel = FuzzModel.from(numericLocalSnapshot("5", "[-100,100]"));
        middleModel.validateSpecification(response);
        edgeModel.validateSpecification(response);
        var middle = middleModel.evaluate(response, middleModel.simulate(new long[]{6}, 2));
        var edge = edgeModel.evaluate(response, edgeModel.simulate(new long[]{5}, 2));
        assertEquals(-1, middle.violationStep());
        assertEquals(-1, edge.violationStep());
        assertEquals(0.02, middle.distance(), EPSILON);
        assertEquals(0.01, edge.distance(), EPSILON);
    }

    @Test
    void numericSignedZeroHasTheSameTruthAndDistanceAsIntegerZero() {
        for (String relation : List.of("=", "!=", ">", ">=", "<", "<=", "in", "not in")) {
            boolean matchesZero = List.of("=", ">=", "<=", "in").contains(relation);
            String expected = relation.contains("in") ? "-0,1" : "-0";
            var forbidden = numericScoreAt("0", "3", relation, expected);
            var invariant = numericScoreAt("0", "1", relation, expected);
            assertEquals(matchesZero ? 0 : -1, forbidden.violationStep(), relation);
            assertEquals(matchesZero ? -1 : 0, invariant.violationStep(), relation);
            assertEquals(matchesZero, forbidden.distance() == 0.0, relation);
            assertEquals(!matchesZero, invariant.distance() == 0.0, relation);
        }
    }

    @Test
    void numericSetGuidanceRespectsClosedIntegerDomains() {
        for (int boundary : List.of(Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            var specification = specificationForDevice(
                    "wide-set", "3", "wide_1", "variable", "wideValue", "not in", Integer.toString(boundary));
            var snapshot = fullRangeNumericSnapshot(List.of(specification));
            var domain = snapshot.templateManifests().get("WideNumeric").getInternalVariables().get(0);
            domain.setLowerBound(Integer.MIN_VALUE);
            domain.setUpperBound(Integer.MAX_VALUE);
            domain.setNaturalChangeRate("[-1,1]");
            snapshot.environmentVariables().get(0).setValue(Integer.toString(boundary));
            FuzzModel model = FuzzModel.from(snapshot);
            model.validateSpecification(specification);
            long holdingChoice = boundary == Integer.MIN_VALUE ? 0L : 1L;
            var evaluation = model.evaluate(specification, model.simulate(new long[]{holdingChoice}, 2));
            assertEquals(-1, evaluation.violationStep());
            assertTrue(evaluation.distance() > 0.0);
            assertEquals(1.0 / 4_294_967_295.0, evaluation.distance(), 1.0e-15);
        }

        List<String> members = new ArrayList<>();
        for (int value = 0; value <= 100; value++) members.add(Integer.toString(value));
        var covered = numericScoreAt("50", "3", "not in", String.join(",", members));
        assertEquals(-1, covered.violationStep());
        assertTrue(Double.isInfinite(covered.distance()), "the set covers every legal integer");

        var specification = specificationForDevice(
                "single-point-set", "1", "counter_1", "variable", "count", "in", "0");
        var snapshot = numericLocalSnapshot("0", "0");
        snapshot.templateManifests().get("Counter").getInternalVariables().get(0).setUpperBound(0);
        FuzzModel model = FuzzModel.from(snapshot);
        model.validateSpecification(specification);
        var singlePoint = model.evaluate(specification, model.simulate(new long[0], 1));
        assertEquals(-1, singlePoint.violationStep());
        assertTrue(Double.isInfinite(singlePoint.distance()), "the only legal value cannot falsify membership");
    }

    @Test
    void sharedNumericSignedZeroMatchesInBoardAndPaperMonitors() {
        for (String relation : List.of("=", "!=", ">", ">=", "<", "<=", "in", "not in")) {
            boolean matchesZero = List.of("=", ">=", "<=", "in").contains(relation);
            String expected = relation.contains("in") ? "-0,1" : "-0";
            var specification = specificationForDevice(
                    "shared-zero", "3", "wide_1", "variable", "wideValue", relation, expected);
            FuzzModel model = FuzzModel.from(fullRangeNumericSnapshot(List.of(specification)));
            model.validateSpecification(specification);
            var simulation = model.simulate(new long[0], 1);
            var board = model.evaluate(specification, simulation);
            var paper = model.evaluatePaper(specification, simulation, FuzzEngine.PAPER_SOLVER_LEVELS);
            assertEquals(matchesZero ? 0 : -1, board.violationStep(), relation);
            assertEquals(matchesZero ? 0 : -1, paper.violationStep(), relation);
            assertEquals(matchesZero, board.distance() == 0.0, relation);
            assertEquals(matchesZero, paper.distance() == 0.0, relation);
        }
    }

    @Test
    void numericDistanceDoesNotOverflowAcrossTheFullIntegerDomain() {
        SpecificationDto neverTarget = specificationForDevice(
                "never-one",
                "3",
                "wide_1",
                "variable",
                "wideValue",
                "=",
                "1");
        var snapshot = fullRangeNumericSnapshot(List.of(neverTarget));
        snapshot.templateManifests().get("WideNumeric").getInternalVariables().get(0)
                .setNaturalChangeRate("[-1,1]");
        FuzzModel model = FuzzModel.from(snapshot);
        FuzzModel.Simulation simulation = model.simulate(new long[]{1}, 2);

        double distance = model.evaluate(neverTarget, simulation).distance();
        assertTrue(distance > 0.0);
        assertEquals(1.0 / 4_000_000_000.0, distance, 1.0e-15);
    }

    @Test
    void classifiesNullSpecificationAndManifestFieldsWithoutThrowing() {
        SpecificationDto missingTemplate = specification("missing-template", "1", stateCondition("off"));
        missingTemplate.setName("Safety requirement");
        missingTemplate.setTemplateId(null);
        FuzzEngineResult specificationResult = engine.run(
                new FuzzEngineInput(
                        snapshot(List.of(missingTemplate), List.of(), switchManifest()),
                        new FuzzEngineConfig(List.of(), 1, 2, 1, 1L)),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.NO_ELIGIBLE_SPECIFICATIONS, specificationResult.outcome());
        assertEquals("UNSUPPORTED_TEMPLATE", specificationResult.eligibility().get(0).reasonCode());

        DeviceManifest malformedManifest = switchManifest();
        malformedManifest.setWorkingStates(java.util.Arrays.asList((DeviceManifest.WorkingState) null));
        FuzzEngineResult manifestResult = engine.run(
                new FuzzEngineInput(
                        snapshot(List.of(specification("valid", "1", stateCondition("off"))),
                                List.of(), malformedManifest),
                        new FuzzEngineConfig(List.of(), 1, 2, 1, 1L)),
                null,
                () -> false);

        assertEquals(FuzzEngineOutcome.NO_ELIGIBLE_SPECIFICATIONS, manifestResult.outcome());
        assertEquals("MODEL_INVALID", manifestResult.eligibility().get(0).reasonCode());
    }

    @Test
    void falseStrictAndNotEqualPredicatesHaveNonZeroSearchDistance() {
        SpecificationDto notEqual = specification(
                "not-equal", "3", conditionForDevice("climate_1", "variable", "temperature", "!=", "50"));
        SpecificationDto strictGreater = specification(
                "strict-greater", "3", conditionForDevice("climate_1", "variable", "temperature", ">", "50"));
        BoardDataConverter.ModelInputSnapshot snapshot = numericImpactSnapshot("50");
        FuzzModel model = FuzzModel.from(snapshot);
        FuzzModel.Simulation initialOnly = model.simulate(new long[]{0L}, 1);

        model.validateSpecification(notEqual);
        model.validateSpecification(strictGreater);
        assertTrue(model.evaluate(notEqual, initialOnly).distance() > 0.0);
        assertTrue(model.evaluate(strictGreater, initialOnly).distance() > 0.0);
    }

    @Test
    void failsClosedOnMissingInitialEnvironmentAndNoEffectApi() {
        SpecificationDto specification = specification("fail-closed", "1", stateCondition("off"));
        BoardDataConverter.ModelInputSnapshot complete = snapshot(
                List.of(specification), List.of(), switchManifest());
        BoardDataConverter.ModelInputSnapshot missingEnvironment = new BoardDataConverter.ModelInputSnapshot(
                complete.nodes(),
                complete.devices(),
                List.of(),
                complete.rules(),
                complete.specifications(),
                complete.templateManifests());

        FuzzEngineResult missingEnvironmentResult = engine.run(
                new FuzzEngineInput(
                        missingEnvironment,
                        new FuzzEngineConfig(List.of(), 1, 2, 1, 2L)),
                null,
                () -> false);

        assertEquals("MODEL_INVALID", missingEnvironmentResult.eligibility().get(0).reasonCode());

        DeviceManifest noEffectApiManifest = switchManifest();
        noEffectApiManifest.getApis().get(0).setEndState("off");
        FuzzEngineResult noEffectApiResult = engine.run(
                new FuzzEngineInput(
                        snapshot(List.of(specification), List.of(), noEffectApiManifest),
                        new FuzzEngineConfig(List.of(), 1, 2, 1, 2L)),
                null,
                () -> false);

        assertEquals("MODEL_INVALID", noEffectApiResult.eligibility().get(0).reasonCode());
    }

    private static String traceEnvironmentValue(
            FuzzModel.Simulation simulation,
            int step,
            String name) {
        return simulation.traceStates().get(step).getEnvVariables().stream()
                .filter(variable -> name.equals(variable.getName()))
                .findFirst()
                .orElseThrow()
                .getValue();
    }

    private static BoardDataConverter.ModelInputSnapshot provenanceSnapshot(boolean includeCustom) {
        DeviceVerificationDto bundled = provenanceDevice(
                "bundled_1", "Bundled", ModelTokenSource.BUNDLED);
        List<DeviceVerificationDto> devices = new ArrayList<>();
        devices.add(bundled);
        Map<String, DeviceManifest> manifests = new LinkedHashMap<>();
        manifests.put("Bundled", provenanceManifest("Bundled"));
        if (includeCustom) {
            devices.add(provenanceDevice("custom_1", "Custom", ModelTokenSource.CUSTOM));
            manifests.put("Custom", provenanceManifest("Custom"));
        }
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                devices,
                List.of(new BoardEnvironmentVariableDto(
                        "weather", "calm", "trusted", "public")),
                List.of(),
                List.of(),
                manifests);
    }

    private static DeviceVerificationDto provenanceDevice(
            String id, String templateName, ModelTokenSource source) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName(id);
        device.setDeviceLabel(id);
        device.setTemplateName(templateName);
        device.setModelTokenSource(source);
        device.setVariables(List.of(new VariableStateDto(
                "profile", "home", "trusted")));
        device.setPrivacies(List.of());
        return device;
    }

    private static DeviceManifest provenanceManifest(String name) {
        DeviceManifest.InternalVariable profile = DeviceManifest.InternalVariable.builder()
                .name("profile")
                .isInside(true)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("private")
                .values(List.of("home", "away"))
                .build();
        DeviceManifest.InternalVariable weather = DeviceManifest.InternalVariable.builder()
                .name("weather")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .values(List.of("calm", "rain"))
                .build();
        return DeviceManifest.builder()
                .name(name)
                .modes(List.of())
                .internalVariables(List.of(profile, weather))
                .impactedVariables(List.of())
                .workingStates(List.of())
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
    }

    private static String sharedWeatherFormalCase(
            BoardDataConverter.ModelInputSnapshot snapshot) {
        DeviceSmvDataFactory factory = new DeviceSmvDataFactory(
                new ObjectMapper(),
                null,
                org.mockito.Mockito.mock(SmvModelValidator.class),
                null);
        var deviceSmvMap = factory.buildDeviceSmvMapFromTemplateSnapshots(
                snapshot.devices(), snapshot.templateManifests());
        String model = new SmvMainModuleBuilder().build(
                null,
                snapshot.devices(),
                snapshot.environmentVariables(),
                snapshot.rules(),
                deviceSmvMap,
                AttackScenarioDto.none(),
                true,
                SmvGenerationContext.noop());
        int start = model.indexOf("next(a_weather)");
        int end = start < 0 ? -1 : model.indexOf("esac;", start);
        assertTrue(start >= 0 && end > start);
        return model.substring(start, end);
    }

    private static BoardDataConverter.ModelInputSnapshot snapshot(
            List<SpecificationDto> specifications,
            List<RuleDto> rules,
            DeviceManifest manifest) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("switch_1");
        device.setDeviceLabel("Switch");
        device.setTemplateName("Switch");
        device.setState("off");
        device.setVariables(List.of());
        device.setPrivacies(List.of());
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(new BoardEnvironmentVariableDto("weather", "calm", "trusted", "public")),
                rules,
                specifications,
                Map.of("Switch", manifest));
    }

    private static BoardDataConverter.ModelInputSnapshot snapshotWithDeterministicLocal(
            List<SpecificationDto> specifications) {
        DeviceManifest manifest = switchManifest();
        DeviceManifest.InternalVariable fixed = DeviceManifest.InternalVariable.builder()
                .name("firmware")
                .isInside(true)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("private")
                .values(List.of("stable"))
                .build();
        List<DeviceManifest.InternalVariable> variables = new ArrayList<>(manifest.getInternalVariables());
        variables.add(fixed);
        manifest.setInternalVariables(variables);
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(specifications, List.of(), manifest);
        snapshot.devices().get(0).setVariables(List.of(new VariableStateDto("firmware", "stable", "trusted")));
        return snapshot;
    }

    private static BoardDataConverter.ModelInputSnapshot snapshotWithMutableLocal(
            List<SpecificationDto> specifications) {
        DeviceManifest manifest = switchManifest();
        DeviceManifest.InternalVariable profile = DeviceManifest.InternalVariable.builder()
                .name("profile")
                .isInside(true)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("private")
                .values(List.of("eco", "comfort"))
                .build();
        DeviceManifest.InternalVariable level = DeviceManifest.InternalVariable.builder()
                .name("level")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .lowerBound(0)
                .upperBound(4)
                .naturalChangeRate("[-1,1]")
                .build();
        List<DeviceManifest.InternalVariable> variables = new ArrayList<>(
                manifest.getInternalVariables());
        variables.add(profile);
        variables.add(level);
        manifest.setInternalVariables(variables);
        BoardDataConverter.ModelInputSnapshot snapshot = snapshot(
                specifications, List.of(), manifest);
        snapshot.devices().get(0).setVariables(List.of(
                new VariableStateDto("profile", "eco", "trusted")));
        return new BoardDataConverter.ModelInputSnapshot(
                snapshot.nodes(),
                snapshot.devices(),
                List.of(
                        new BoardEnvironmentVariableDto(
                                "weather", "calm", "trusted", "public"),
                        new BoardEnvironmentVariableDto(
                                "level", "2", "trusted", "public")),
                snapshot.rules(),
                snapshot.specifications(),
                snapshot.templateManifests());
    }

    private static BoardDataConverter.ModelInputSnapshot localOnlySnapshot() {
        return localOnlySnapshot(List.of());
    }

    private static BoardDataConverter.ModelInputSnapshot localOnlySnapshot(
            List<SpecificationDto> specifications) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("local_1");
        device.setDeviceLabel("Local-only device");
        device.setTemplateName("LocalOnly");
        device.setVariables(List.of(new VariableStateDto(
                "profile", "alpha", "trusted")));
        device.setPrivacies(List.of());
        DeviceManifest.InternalVariable profile = DeviceManifest.InternalVariable.builder()
                .name("profile")
                .isInside(true)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("private")
                .values(List.of("alpha", "beta"))
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("LocalOnly")
                .modes(List.of())
                .internalVariables(List.of(profile))
                .impactedVariables(List.of())
                .workingStates(List.of())
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(),
                List.of(),
                specifications,
                Map.of("LocalOnly", manifest));
    }

    private static String differentInitialValue(
            PaperEventDomainSnapshot domain, PaperInitialValue current) {
        PaperInitialValueDomain valueDomain = domain.initialValueDomain(current.target());
        if (!valueDomain.legalValues().isEmpty()) {
            return valueDomain.legalValues().stream()
                    .filter(value -> !value.equals(current.value()))
                    .findFirst()
                    .orElseThrow();
        }
        String lower = Integer.toString(valueDomain.lowerBound());
        return lower.equals(current.value())
                ? Integer.toString(valueDomain.upperBound())
                : lower;
    }

    private static BoardDataConverter.ModelInputSnapshot symmetricNumericSnapshot(
            List<SpecificationDto> specifications) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("numeric_1");
        device.setDeviceLabel("Numeric source");
        device.setTemplateName("Numeric");
        device.setVariables(List.of());
        device.setPrivacies(List.of());

        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .lowerBound(0)
                .upperBound(4)
                .naturalChangeRate("[-1,1]")
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Numeric")
                .modes(List.of())
                .internalVariables(List.of(temperature))
                .impactedVariables(List.of())
                .workingStates(List.of())
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(new BoardEnvironmentVariableDto("temperature", "2", "trusted", "public")),
                List.of(),
                specifications,
                Map.of("Numeric", manifest));
    }

    private static BoardDataConverter.ModelInputSnapshot fullRangeNumericSnapshot(
            List<SpecificationDto> specifications) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("wide_1");
        device.setDeviceLabel("Wide numeric source");
        device.setTemplateName("WideNumeric");
        device.setVariables(List.of());
        device.setPrivacies(List.of());

        DeviceManifest.InternalVariable wideValue = DeviceManifest.InternalVariable.builder()
                .name("wideValue")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .lowerBound(-2_000_000_000)
                .upperBound(2_000_000_000)
                .naturalChangeRate("[0,0]")
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("WideNumeric")
                .modes(List.of())
                .internalVariables(List.of(wideValue))
                .impactedVariables(List.of())
                .workingStates(List.of())
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(new BoardEnvironmentVariableDto("wideValue", "0", "trusted", "public")),
                List.of(),
                specifications,
                Map.of("WideNumeric", manifest));
    }

    private static BoardDataConverter.ModelInputSnapshot sharedWriteOrderSnapshot(boolean zFirst) {
        DeviceVerificationDto zDevice = new DeviceVerificationDto();
        zDevice.setVarName("z_device");
        zDevice.setDeviceLabel("First writer");
        zDevice.setTemplateName("SharedWriter");
        zDevice.setState("off");
        zDevice.setVariables(List.of());
        zDevice.setPrivacies(List.of());
        DeviceVerificationDto aDevice = new DeviceVerificationDto();
        aDevice.setVarName("a_device");
        aDevice.setDeviceLabel("Second writer");
        aDevice.setTemplateName("SharedWriter");
        aDevice.setState("on");
        aDevice.setVariables(List.of());
        aDevice.setPrivacies(List.of());

        DeviceManifest.InternalVariable weather = DeviceManifest.InternalVariable.builder()
                .name("weather")
                .isInside(false)
                .values(List.of("calm", "rain"))
                .trust("trusted")
                .privacy("public")
                .build();
        DeviceManifest.Transition offWritesCalm = DeviceManifest.Transition.builder()
                .name("off-writes-calm")
                .startState("off")
                .endState("")
                .trigger(DeviceManifest.Trigger.builder()
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build())
                .assignments(List.of(DeviceManifest.Assignment.builder()
                        .attribute("weather")
                        .value("calm")
                        .build()))
                .build();
        DeviceManifest.Transition onWritesRain = DeviceManifest.Transition.builder()
                .name("on-writes-rain")
                .startState("on")
                .endState("")
                .trigger(DeviceManifest.Trigger.builder()
                        .attribute("weather")
                        .relation("=")
                        .value("rain")
                        .build())
                .assignments(List.of(DeviceManifest.Assignment.builder()
                        .attribute("weather")
                        .value("rain")
                        .build()))
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("SharedWriter")
                .modes(List.of("PowerMode"))
                .internalVariables(List.of(weather))
                .impactedVariables(List.of())
                .initState("off")
                .workingStates(List.of(workingState("off"), workingState("on")))
                .transitions(List.of(offWritesCalm, onWritesRain))
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                zFirst ? List.of(zDevice, aDevice) : List.of(aDevice, zDevice),
                List.of(new BoardEnvironmentVariableDto("weather", "rain", "trusted", "public")),
                List.of(),
                List.of(),
                Map.of("SharedWriter", manifest));
    }

    private static BoardDataConverter.ModelInputSnapshot signalSwitchSnapshot(
            List<SpecificationDto> specifications,
            List<RuleDto> rules,
            String currentState,
            boolean wildcardStart) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("signal_1");
        device.setDeviceLabel("Signal switch");
        device.setTemplateName("SignalSwitch");
        device.setState(currentState);
        device.setVariables(List.of());
        device.setPrivacies(List.of());

        DeviceManifest.InternalVariable weather = DeviceManifest.InternalVariable.builder()
                .name("weather")
                .isInside(false)
                .values(List.of("calm", "rain"))
                .trust("trusted")
                .privacy("public")
                .build();
        DeviceManifest.API turnedOn = DeviceManifest.API.builder()
                .name("turnedOn")
                .signal(true)
                .acceptsContent(false)
                .startState(wildcardStart ? "" : "off")
                .endState("on")
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("SignalSwitch")
                .modes(List.of("PowerMode"))
                .internalVariables(List.of(weather))
                .impactedVariables(List.of())
                .initState("off")
                .workingStates(List.of(workingState("off"), workingState("on")))
                .transitions(List.of())
                .apis(List.of(turnedOn))
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(new BoardEnvironmentVariableDto("weather", "rain", "trusted", "public")),
                rules,
                specifications,
                Map.of("SignalSwitch", manifest));
    }

    private static BoardDataConverter.ModelInputSnapshot paperFigureNumericSnapshot() {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("numeric_figure_1");
        device.setDeviceLabel("Figure numeric source");
        device.setTemplateName("FigureNumeric");
        device.setVariables(List.of());
        device.setPrivacies(List.of());

        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .lowerBound(0)
                .upperBound(50)
                .naturalChangeRate("[-2,2]")
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("FigureNumeric")
                .modes(List.of())
                .internalVariables(List.of(temperature))
                .impactedVariables(List.of())
                .workingStates(List.of())
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(new BoardEnvironmentVariableDto("temperature", "26", "trusted", "public")),
                List.of(),
                List.of(),
                Map.of("FigureNumeric", manifest));
    }

    private static BoardDataConverter.ModelInputSnapshot paperForcedEnvironmentAssignmentSnapshot() {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("forced_1");
        device.setDeviceLabel("Forced environment source");
        device.setTemplateName("ForcedEnvironment");
        device.setState("off");
        device.setVariables(List.of());
        device.setPrivacies(List.of());

        DeviceManifest.InternalVariable temperature = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .lowerBound(0)
                .upperBound(50)
                .naturalChangeRate("[-2,2]")
                .build();
        DeviceManifest.WorkingState off = DeviceManifest.WorkingState.builder()
                .name("off")
                .trust("trusted")
                .privacy("public")
                .dynamics(List.of())
                .build();
        DeviceManifest.Transition forceTemperature = DeviceManifest.Transition.builder()
                .name("force-temperature")
                .startState("off")
                .endState("")
                .trigger(DeviceManifest.Trigger.builder()
                        .attribute("temperature")
                        .relation(">=")
                        .value("0")
                        .build())
                .assignments(List.of(DeviceManifest.Assignment.builder()
                        .attribute("temperature")
                        .value("30")
                        .build()))
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("ForcedEnvironment")
                .modes(List.of("PowerMode"))
                .internalVariables(List.of(temperature))
                .impactedVariables(List.of())
                .initState("off")
                .workingStates(List.of(off))
                .transitions(List.of(forceTemperature))
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(new BoardEnvironmentVariableDto("temperature", "26", "trusted", "public")),
                List.of(),
                List.of(),
                Map.of("ForcedEnvironment", manifest));
    }

    private static DeviceManifest switchManifest() {
        DeviceManifest.InternalVariable weather = DeviceManifest.InternalVariable.builder()
                .name("weather")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .values(List.of("calm", "rain"))
                .build();
        DeviceManifest.WorkingState off = DeviceManifest.WorkingState.builder()
                .name("off")
                .trust("trusted")
                .privacy("public")
                .dynamics(List.of())
                .build();
        DeviceManifest.WorkingState on = DeviceManifest.WorkingState.builder()
                .name("on")
                .trust("trusted")
                .privacy("public")
                .dynamics(List.of())
                .build();
        DeviceManifest.API turnOn = DeviceManifest.API.builder()
                .name("turnOn")
                .signal(false)
                .acceptsContent(false)
                .startState("off")
                .endState("on")
                .build();
        DeviceManifest.API turnOff = DeviceManifest.API.builder()
                .name("turnOff")
                .signal(false)
                .acceptsContent(false)
                .startState("on")
                .endState("off")
                .build();
        DeviceManifest.API keepOff = DeviceManifest.API.builder()
                .name("keepOff")
                .signal(false)
                .acceptsContent(false)
                .startState("")
                .endState("off")
                .build();
        return DeviceManifest.builder()
                .name("Switch")
                .modes(List.of("PowerMode"))
                .internalVariables(List.of(weather))
                .impactedVariables(List.of())
                .initState("off")
                .workingStates(List.of(off, on))
                .transitions(List.of())
                .apis(List.of(turnOn, turnOff, keepOff))
                .contents(List.of())
                .build();
    }

    private static BoardDataConverter.ModelInputSnapshot multiModeSnapshot(
            List<SpecificationDto> specifications,
            List<RuleDto> rules) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("multi_1");
        device.setDeviceLabel("Multi mode device");
        device.setTemplateName("Multi");
        device.setState("alpha;xray");
        device.setVariables(List.of());
        device.setPrivacies(List.of());

        DeviceManifest.API changeB = DeviceManifest.API.builder()
                .name("changeB")
                .signal(false)
                .acceptsContent(false)
                .startState("alpha;xray")
                .endState(";yellow")
                .build();
        DeviceManifest.API changeBoth = DeviceManifest.API.builder()
                .name("changeBoth")
                .signal(false)
                .acceptsContent(false)
                .startState("alpha;xray")
                .endState("beta;zulu")
                .build();
        DeviceManifest.API changeA = DeviceManifest.API.builder()
                .name("changeA")
                .signal(false)
                .acceptsContent(false)
                .startState("alpha;xray")
                .endState("beta;")
                .build();
        List<DeviceManifest.WorkingState> states = List.of(
                workingState("alpha;xray"),
                workingState("alpha;yellow"),
                workingState("beta;xray"),
                workingState("beta;yellow"),
                workingState("beta;zulu"));
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Multi")
                .modes(List.of("ModeA", "ModeB"))
                .internalVariables(List.of())
                .impactedVariables(List.of())
                .initState("alpha;xray")
                .workingStates(states)
                .transitions(List.of())
                .apis(List.of(changeB, changeA, changeBoth))
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(),
                rules,
                specifications,
                Map.of("Multi", manifest));
    }

    private static BoardDataConverter.ModelInputSnapshot twoSwitchSnapshot(
            List<SpecificationDto> specifications,
            List<RuleDto> rules) {
        return twoSwitchSnapshot(specifications, rules, switchManifest());
    }

    private static BoardDataConverter.ModelInputSnapshot twoSwitchSnapshot(
            List<SpecificationDto> specifications,
            List<RuleDto> rules,
            DeviceManifest manifest) {
        DeviceVerificationDto first = switchDevice("switch_1", "First switch");
        DeviceVerificationDto second = switchDevice("switch_2", "Second switch");
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(first, second),
                List.of(new BoardEnvironmentVariableDto("weather", "calm", "trusted", "public")),
                rules,
                specifications,
                Map.of("Switch", manifest));
    }

    private static DeviceVerificationDto switchDevice(String id, String label) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName(id);
        device.setDeviceLabel(label);
        device.setTemplateName("Switch");
        device.setState("off");
        device.setVariables(List.of());
        device.setPrivacies(List.of());
        return device;
    }

    private static BoardDataConverter.ModelInputSnapshot explicitSnapshotForManifest(DeviceManifest manifest) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("device_1");
        device.setDeviceLabel(manifest.getName());
        device.setTemplateName(manifest.getName());
        device.setState(manifest.getModes() == null || manifest.getModes().isEmpty()
                ? null
                : manifest.getInitState());
        List<VariableStateDto> localValues = new ArrayList<>();
        Map<String, BoardEnvironmentVariableDto> environment = new LinkedHashMap<>();
        // Every shared value, read or affect-only, now lives in one array, so one pass covers both.
        Map<String, DeviceManifest.InternalVariable> impactDomains = new LinkedHashMap<>();
        for (DeviceManifest.InternalVariable variable : safe(manifest.getInternalVariables())) {
            if (!Boolean.TRUE.equals(variable.getIsInside())) {
                impactDomains.put(variable.getName(), variable);
            }
        }
        for (DeviceManifest.InternalVariable variable : safe(manifest.getInternalVariables())) {
            String value = initialValue(variable.getValues(), variable.getLowerBound());
            if (Boolean.TRUE.equals(variable.getIsInside())) {
                // FuzzModel refuses to default a local value, so this harness supplies one — and it must be
                // the value the starting state declares, or the bounded explorer would start from a step-0
                // state NuSMV proves unreachable, and the two engines would describe different systems.
                String derived = DeviceManifestModes.localInitialValue(
                        manifest, variable, manifest.getInitState());
                localValues.add(new VariableStateDto(variable.getName(),
                        derived != null ? derived : value, variable.getTrust()));
            } else {
                environment.put(variable.getName(), new BoardEnvironmentVariableDto(
                        variable.getName(), value, variable.getTrust(), variable.getPrivacy()));
            }
        }
        for (String impacted : safe(manifest.getImpactedVariables())) {
            if (environment.containsKey(impacted)) {
                continue;
            }
            DeviceManifest.InternalVariable domain = impactDomains.get(impacted);
            if (domain != null) {
                environment.put(impacted, new BoardEnvironmentVariableDto(
                        impacted,
                        initialValue(domain.getValues(), domain.getLowerBound()),
                        domain.getTrust(),
                        domain.getPrivacy()));
            }
        }
        device.setVariables(localValues);
        device.setPrivacies(List.of());
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.copyOf(environment.values()),
                List.of(),
                List.of(),
                Map.of(manifest.getName(), manifest));
    }

    private static String initialValue(List<String> values, Integer lowerBound) {
        return values != null && !values.isEmpty() ? values.get(0) : String.valueOf(lowerBound);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static DeviceManifest.WorkingState workingState(String name) {
        return DeviceManifest.WorkingState.builder()
                .name(name)
                .trust("trusted")
                .privacy("public")
                .dynamics(List.of())
                .build();
    }

    private static BoardDataConverter.ModelInputSnapshot numericImpactSnapshot(String temperature) {
        return numericImpactSnapshot(temperature, "[-1,1]");
    }

    private static BoardDataConverter.ModelInputSnapshot numericImpactSnapshot(
            String temperature,
            String naturalChangeRate) {
        return numericImpactSnapshot(temperature, naturalChangeRate, true);
    }

    private static BoardDataConverter.ModelInputSnapshot numericImpactSnapshot(
            String temperature,
            String naturalChangeRate,
            boolean hasImpact) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("climate_1");
        device.setDeviceLabel("Climate device");
        device.setTemplateName("Climate");
        device.setState("cool");
        device.setVariables(List.of());
        device.setPrivacies(List.of());

        DeviceManifest.InternalVariable temperatureDomain = DeviceManifest.InternalVariable.builder()
                .name("temperature")
                .isInside(false)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .lowerBound(0)
                .upperBound(100)
                .naturalChangeRate(naturalChangeRate)
                .build();
        DeviceManifest.Dynamic cooling = DeviceManifest.Dynamic.builder()
                .variableName("temperature")
                .changeRate("-1")
                .build();
        DeviceManifest.WorkingState cool = DeviceManifest.WorkingState.builder()
                .name("cool")
                .trust("trusted")
                .privacy("public")
                .dynamics(hasImpact ? List.of(cooling) : List.of())
                .build();
        DeviceManifest.WorkingState idle = DeviceManifest.WorkingState.builder()
                .name("idle")
                .trust("trusted")
                .privacy("public")
                .dynamics(List.of())
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Climate")
                .modes(List.of("ClimateMode"))
                .internalVariables(List.of(temperatureDomain))
                .impactedVariables(hasImpact ? List.of("temperature") : List.of())
                .initState("cool")
                .workingStates(List.of(cool, idle))
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(new BoardEnvironmentVariableDto("temperature", temperature, "trusted", "public")),
                List.of(),
                List.of(),
                Map.of("Climate", manifest));
    }

    private static FuzzModel.SpecEvaluation numericScoreAt(
            String actual, String template, String relation, String expected) {
        SpecificationDto specification = specificationForDevice(
                "numeric-guidance", template, "counter_1", "variable", "count", relation, expected);
        // Every domain value is reachable at step 1; hold the chosen value to test its distance.
        FuzzModel model = FuzzModel.from(numericLocalSnapshot(actual, "[-100,100]"));
        model.validateSpecification(specification);
        return model.evaluate(specification, model.simulate(new long[]{Integer.parseInt(actual)}, 2));
    }

    private static BoardDataConverter.ModelInputSnapshot numericLocalSnapshot(
            String value,
            String naturalChangeRate) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("counter_1");
        device.setDeviceLabel("Counter");
        device.setTemplateName("Counter");
        device.setVariables(List.of(new VariableStateDto("count", value, "trusted")));
        device.setPrivacies(List.of());

        DeviceManifest.InternalVariable count = DeviceManifest.InternalVariable.builder()
                .name("count")
                .isInside(true)
                .falsifiableWhenCompromised(false)
                .trust("trusted")
                .privacy("public")
                .lowerBound(0)
                .upperBound(100)
                .naturalChangeRate(naturalChangeRate)
                .build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Counter")
                .modes(List.of())
                .internalVariables(List.of(count))
                .impactedVariables(List.of())
                .workingStates(List.of())
                .transitions(List.of())
                .apis(List.of())
                .contents(List.of())
                .build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(),
                List.of(device),
                List.of(),
                List.of(),
                List.of(),
                Map.of("Counter", manifest));
    }

    private static SpecificationDto specification(String id, String templateId, SpecConditionDto condition) {
        SpecificationDto specification = new SpecificationDto();
        specification.setId(id);
        specification.setName("Safety requirement");
        specification.setTemplateId(templateId);
        specification.setAConditions(List.of(condition));
        specification.setIfConditions(List.of());
        specification.setThenConditions(List.of());
        specification.setDevices(List.of());
        return specification;
    }

    private static SpecificationDto specificationForDevice(
            String id,
            String templateId,
            String deviceId,
            String type,
            String key,
            String relation,
            String value) {
        return specification(id, templateId, conditionForDevice(deviceId, type, key, relation, value));
    }

    private static SpecificationDto immediateSpecification(String id) {
        SpecConditionDto antecedent = stateCondition("off");
        antecedent.setSide("if");
        SpecConditionDto consequent = stateCondition("off");
        consequent.setSide("then");
        SpecificationDto specification = new SpecificationDto();
        specification.setId(id);
        specification.setName("Safety requirement");
        specification.setTemplateId("4");
        specification.setAConditions(List.of());
        specification.setIfConditions(List.of(antecedent));
        specification.setThenConditions(List.of(consequent));
        specification.setDevices(List.of());
        return specification;
    }

    private static SpecConditionDto stateCondition(String value) {
        return condition("state", "state", "=", value);
    }

    private static SpecConditionDto variableCondition(String value) {
        return condition("variable", "weather", "=", value);
    }

    private static SpecConditionDto condition(String type, String key, String relation, String value) {
        return conditionForDevice("switch_1", type, key, relation, value);
    }

    private static SpecConditionDto conditionForDevice(
            String deviceId,
            String type,
            String key,
            String relation,
            String value) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setSide("a");
        condition.setDeviceId(deviceId);
        condition.setDeviceLabel("Switch");
        condition.setTargetType(type);
        condition.setKey(key);
        condition.setRelation(relation);
        condition.setValue(value);
        return condition;
    }
}
