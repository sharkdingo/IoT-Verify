package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.board.PortableSceneTestImport;
import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor;
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
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.util.TraceStateIntegrity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-NuSMV regression for the away-mode presentation scene ("nobody home, door unlocked").
 *
 * <p>Pins what {@code docs/guides/away-mode-unlock-demo.md} publishes, so a drift in generation, fix,
 * or attack semantics fails here rather than in front of an audience.
 */
class AwayModeUnlockSceneNusmvTest {

    private static final long USER_ID = 1L;
    private static final Path SCENE_PATH =
            Path.of("..", "docs", "examples", "default-away-mode-unlock-scene.json");

    /**
     * Pins the runbook's published numbers: the baseline verdict, the three-state counterexample, the
     * blamed rules, each strategy's outcome, and the budget-one attack.
     *
     * <p>Two strategies must keep *declining* — parameter tuning for want of a numeric inequality,
     * condition tightening because no added guard survives re-checking — so removal stays the only
     * verified repair. And because that removal leaves the door permanently locked, this also pins the
     * asymmetry the walkthrough explains: the prohibition property is genuinely achieved, while the
     * Response property becomes vacuously true once its antecedent is unreachable.
     */
    @Test
    void awayModeUnlockScene_violatesUnlockSafetyAndIsRepairedOnlyByRemoval() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for this demo-scene regression test");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(SCENE_PATH));
        assertEquals("iot-verify.board-scene", scene.path("schema").asText());
        assertEquals(5, scene.path("version").asInt());
        assertEquals(5, scene.path("devices").size());
        assertEquals(3, scene.path("rules").size());
        assertEquals(6, scene.path("specs").size());
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "awayMode");

        SmvGenerator.GenerateResult baselineModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertEquals(0, baselineModel.disabledRuleCount(), "demo scene must not disable rules");
        assertEquals(0, baselineModel.skippedSpecCount(), "demo scene must emit all six specs");

        NusmvExecutor.NusmvResult baseline = executor.execute(baselineModel.smvFile());
        // Also guards a coupling that has nothing to do with this demo, because this scene happens to
        // contain an affect-only shared variable (`Light.illuminance`, `Reads: false`). Label
        // declaration (`SmvDeviceModuleBuilder.appendPropertyVariables`) and label initialisation
        // (`appendInitialProperty`) are two independent loops over the same variable list, kept
        // consistent only by both being capability-blind. Reads-gating either one alone emits an
        // `init` for an undeclared identifier, which is invalid SMV — verified: adding that gate
        // reddens all three tests here on NuSMV's own parse error, while a string-matching assertion
        // elsewhere stays green.
        assertTrue(baseline.isSuccess(), baseline::getErrorMessage);
        assertEquals(specs.size(), baseline.getSpecResults().size());

        // Two baseline violations sharing one root cause: the Never property (index 2) and the Response
        // property (index 3), both about the door being unlocked while nobody is home.
        List<Integer> violatedIndices = new ArrayList<>();
        for (int i = 0; i < baseline.getSpecResults().size(); i++) {
            if (!baseline.getSpecResults().get(i).isPassed()) {
                violatedIndices.add(i);
            }
        }
        assertEquals(List.of(2, 3), violatedIndices,
                "runbook claims four satisfied specs and two violations sharing one root cause");

        int violatedIndex = 2;
        NusmvExecutor.SpecCheckResult violation = baseline.getSpecResults().get(violatedIndex);
        assertTrue(violation.getSpecExpression().contains("door_1.LockState = unlocked"),
                () -> "unexpected violated formula: " + violation.getSpecExpression());

        List<TraceStateDto> states = new SmvTraceParser().parseCounterexampleStates(
                violation.getCounterexample(), baselineModel.deviceSmvMap(), rules);
        assertEquals(3, states.size(), "runbook walks a three-state counterexample");

        /*
         * `Light.illuminance` is affect-only (`Reads: false`), so the model declares `light_1.illuminance`
         * without ever constraining it and NuSMV prints an arbitrary domain member — it printed `0` in
         * every state here while the shared value was 20/19/19. Publishing that as a device reading put a
         * contradiction on one screen: "Porch Light: illuminance 0" beside an environment strip reading 20.
         *
         * The row must survive: `CounterexampleInitialStateConstraints` requires one per manifest variable
         * and reads this variable's trust from it. So the value is emptied and `observed` marks why. The
         * previous attempt at this fix nulled the value instead, which `TraceStateIntegrity` rejects on
         * every persisted read — breaking automatic fix on this very scene. Hence the integrity assertion
         * below, and hence the whole fix pipeline running afterwards on these same states.
         */
        int assertedIlluminanceRows = 0;
        for (TraceStateDto state : states) {
            TraceVariableDto shared = state.getEnvVariables().stream()
                    .filter(v -> "illuminance".equals(v.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("shared illuminance missing from the state"));
            assertTrue(shared.isObserved(), "the environment's own illuminance is a real value");
            assertFalse(shared.getValue().isBlank(), "shared illuminance must carry the pool value");

            TraceDeviceDto light = state.getDevices().stream()
                    .filter(d -> "light_1".equals(d.getDeviceId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("light_1 missing from the trace state"));
            TraceVariableDto illuminance = light.getVariables().stream()
                    .filter(v -> "illuminance".equals(v.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the row itself must stay — the fixer requires one per manifest variable"));
            assertFalse(illuminance.isObserved(),
                    "an affect-only declaration is not a reading this device took");
            assertEquals("", illuminance.getValue(),
                    "no arbitrary domain member may be published as light_1's own illuminance");
            assertEquals("untrusted", illuminance.getTrust(),
                    "the label the generator emitted must still reach the fixer");
            assertedIlluminanceRows++;
        }
        assertEquals(3, assertedIlluminanceRows, "every state must carry the marked row");

        // The check the reverted attempt tripped: this is what `TraceMapper` runs on every persisted read.
        assertEquals(3, TraceStateIntegrity.requireValidStates(states).size());

        FixResultDto fixResult = allStrategyFixer(generator, executor, 240_000).fix(
                901L, specs.get(violatedIndex).getId(), states, rules, devices, environment, specs,
                baselineModel.deviceSmvMap(), USER_ID, AttackScenarioDto.none(), true,
                List.of("parameter", "condition", "remove"), 20, Map.of());
        assertTrue(fixResult.isFixable(), () -> "expected a repairable violation: " + fixResult.getSummary());

        // Localization lists every rule that fired in the trace, so all three are candidates here
        // (the porch-light rule shares the convenience rule's trigger and fires in the same step).
        // Narrowing three candidates to one repair is the strategy search's job, not localization's.
        Set<Integer> blamed = new HashSet<>();
        for (FaultRuleDto faultRule : fixResult.getFaultRules()) {
            blamed.add(faultRule.getRuleIndex());
        }
        assertTrue(blamed.contains(1), () -> "convenience-unlock rule not blamed: " + blamed);

        // Each strategy's honest outcome. Parameter tuning has no numeric inequality to move, and
        // condition tightening cannot repair this property: occupancy evolves freely, so any guard
        // that permits an unlock while someone is home is still followed by a step where they left
        // and nothing re-locks the door. Removal is therefore the only verified repair, and the
        // runbook must not promise a non-destructive one.
        Map<String, String> attemptStatus = new java.util.HashMap<>();
        fixResult.getStrategyAttempts().forEach(a -> attemptStatus.put(a.getStrategy(), a.getStatus()));
        assertEquals("SKIPPED_NO_PARAMETERIZABLE_VALUES", attemptStatus.get("parameter"),
                "this scene is enum-valued, so parameter tuning has nothing to adjust");
        assertEquals("NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE", attemptStatus.get("condition"));
        assertEquals("VERIFIED", attemptStatus.get("remove"));

        FixSuggestionDto removeFix = fixResult.getSuggestions().stream()
                .filter(s -> "remove".equals(s.getStrategy()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no removal suggestion offered"));
        assertEquals(List.of(1), removeFix.getRemovedRuleIndices(),
                "removal must target exactly the convenience-unlock rule");

        Map<String, String> deviceRefs = devices.stream().collect(
                java.util.stream.Collectors.toMap(
                        DeviceVerificationDto::getVarName, DeviceVerificationDto::getVarName));
        for (FixSuggestionDto s : fixResult.getSuggestions()) {
            List<RuleDto> repairedRules = FixStrategyApplier.apply(
                    s.getStrategy(), s, rules, baselineModel.deviceSmvMap(), deviceRefs);
            SmvGenerator.GenerateResult repairedModel = generator.generateWithEnvironment(
                    USER_ID, devices, environment, repairedRules, specs,
                    AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
            NusmvExecutor.NusmvResult repaired = executor.execute(repairedModel.smvFile());
            assertTrue(repaired.isSuccess(), repaired::getErrorMessage);
            assertFalse(repaired.hasAnyViolation(),
                    () -> "repair from strategy '" + s.getStrategy() + "' left a violation");
            assertEquals(specs.size(), repaired.getSpecResults().size());
            // One removal clears both violations, which is the runbook's shared-root-cause claim.
            assertEquals(rules.size() - 1, repairedRules.size());

            // …but the two properties do not go green the same way, and the walkthrough says so out loud.
            // Deleting the only rule that ever unlocked the door leaves the door permanently locked, which
            // *achieves* the prohibition property (index 2) and *empties* the implication property
            // (index 3): its antecedent can no longer arise, so it holds for the empty reason.
            //
            // Pinned because a green forward verification cannot tell these apart, and presenting the
            // vacuous one as a repair is the most misleading thing this demo could do.
            Map<String, Boolean> reachable = probeReachability(executor, repairedModel, List.of(
                    "door_1.LockState = unlocked",
                    "a_occupancy = absent & door_1.LockState = unlocked"));
            assertFalse(reachable.get("door_1.LockState = unlocked"),
                    "the removal must leave the door permanently locked, as the runbook states");
            assertFalse(reachable.get("a_occupancy = absent & door_1.LockState = unlocked"),
                    "the Response property's antecedent must be unreachable after the removal, which is "
                            + "exactly why the walkthrough calls its satisfaction vacuous rather than earned");
        }

        SmvGenerator.GenerateResult attackedModel = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.anyUpToBudget(1), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        NusmvExecutor.NusmvResult attacked = executor.execute(attackedModel.smvFile());
        assertTrue(attacked.isSuccess(), attacked::getErrorMessage);
        assertEquals(specs.size(), attacked.getSpecResults().size());

        // Act two: compromising one sensor makes the untrusted-label property (template 7, index 4)
        // fail, while the privacy property (index 5) holds.
        //
        // This act is the *reason* index 4 is in the scene at all. At baseline its trust term is
        // unreachable — this scene declares its `motion` source `trusted`, and nothing without an
        // attacker can degrade a label — so its baseline green is a control condition, not an
        // achievement. Assert the attack model actually makes that term reachable, or the contrast the
        // walkthrough is built on would be two vacuous verdicts in a row.
        assertTrue(probeReachability(executor, attackedModel,
                        List.of("door_1.trust_LockState_unlocked = untrusted"))
                        .get("door_1.trust_LockState_unlocked = untrusted"),
                "the budget-one attack must make the untrusted trust label reachable; otherwise the "
                        + "template-7 property is as uninformative under attack as it is at baseline");
        assertFalse(attacked.getSpecResults().get(4).isPassed(),
                "budget-one attack must expose the untrusted-source unlock");
        assertTrue(attacked.getSpecResults().get(5).isPassed(),
                "the privacy property is not affected by a budget-one attack");
        // Name the compromised device, not merely "something was compromised". Both sensors declare
        // `FalsifiableWhenCompromised`, so a bare `is_attack = TRUE` check passes no matter which one
        // the solver picks — it stayed green even with the occupancy sensor's flag turned off, which
        // is exactly the capability-gating regression this assertion has to catch. The exhaustive
        // budget-one search reports the porch motion detector: a doorstep sensor an attacker can
        // physically reach, which is also the stronger story for the walkthrough.
        String attackTrace = attacked.getSpecResults().get(4).getCounterexample();
        assertTrue(attackTrace.contains("motion_1.is_attack = TRUE"),
                () -> "expected the porch motion detector to be the compromised point: " + attackTrace);
        assertTrue(attackTrace.contains("door_1.trust_LockState_unlocked = untrusted"),
                "the unlock must carry an untrusted control-source label");
    }

    /**
     * Pins the walkthrough's closing note: the repair no strategy can propose.
     *
     * <p>All three strategies edit or delete existing rules; none adds one. So the runbook shows what a
     * competent engineer would do instead — keep the convenience unlock and add "when nobody is home,
     * lock the front door" ahead of it — and claims three measured outcomes: the Response property
     * becomes *substantively* satisfied (its antecedent stays reachable), the Never property stays
     * violated, and the convenience feature survives.
     *
     * <p>That table was measured ad hoc while writing the guide, which is exactly the kind of number
     * that rots: the document's own opening promises every figure is pinned here. This test is that
     * pin. Without it the strongest slide in the presentation would be the one nothing checks.
     */
    @Test
    void awayModeUnlockScene_addingAnAutoLockRuleEarnsTheResponsePropertyInsteadOfEmptyingIt() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for this demo-scene regression test");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(SCENE_PATH));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "autoLock");

        // "When nobody is home, lock the front door", placed first so rule order gives it priority
        // over the convenience unlock for the same target mode.
        RuleDto autoLock = RuleDto.builder()
                .id(99L)
                .ruleString("When nobody is home, lock the front door")
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("occupancy_1")
                        .attribute("occupancy")
                        .targetType("variable")
                        .relation("=")
                        .value("absent")
                        .build()))
                .command(RuleDto.Command.builder().deviceName("door_1").action("lock").build())
                .build();
        List<RuleDto> withAutoLock = new ArrayList<>();
        withAutoLock.add(autoLock);
        withAutoLock.addAll(readRules(scene));

        SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                USER_ID, devices, environment, withAutoLock, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);
        assertEquals(0, model.disabledRuleCount(), "the added rule must be modelled, not disabled");
        assertEquals(0, model.skippedSpecCount());

        NusmvExecutor.NusmvResult result = executor.execute(model.smvFile());
        assertTrue(result.isSuccess(), result::getErrorMessage);
        assertEquals(specs.size(), result.getSpecResults().size());

        // The Response property (index 3) is earned, and the Never property (index 2) still fails —
        // rules act in one step, so one intermediate state always shows the door open with nobody home.
        assertTrue(result.getSpecResults().get(3).isPassed(),
                "adding the auto-lock rule must satisfy the Response property");
        assertFalse(result.getSpecResults().get(2).isPassed(),
                "the Never property cannot hold while the convenience unlock exists, because a lock "
                        + "command needs the step after the resident leaves");

        // …and it is earned rather than emptied: the antecedent stays reachable, unlike after the
        // removal, and the convenience feature still works.
        Map<String, Boolean> reachable = probeReachability(executor, model, List.of(
                "a_occupancy = absent & door_1.LockState = unlocked",
                "door_1.LockState = unlocked"));
        assertTrue(reachable.get("a_occupancy = absent & door_1.LockState = unlocked"),
                "the Response property would be vacuous if its antecedent were unreachable, which is "
                        + "the whole distinction this scenario exists to draw");
        assertTrue(reachable.get("door_1.LockState = unlocked"),
                "this repair must keep the convenience feature the removal deletes");
    }

    /**
     * The trap this scene was rebuilt to avoid, and the reason it declares its own
     * `Occupancy Sensor` template instead of reusing the bundled `Car`.
     *
     * <p>A device-local variable ({@code IsInside: true}) with no API writing it compiles to
     * {@code next(v) := v} — frozen for the whole run. An earlier draft keyed this scene on
     * {@code Car.location}, which made "the car is back in the garage" unreachable: the garage
     * rule was dead code, a Never property over it was vacuously satisfied, and the repair that
     * "kept" the unlock rule silently disabled it instead. All three read as a clean demo.
     *
     * <p>So assert the presented states are actually reachable. A satisfied property is only
     * evidence when the situation it forbids can arise at all.
     */
    @Test
    void awayModeUnlockScene_presentsNoVacuouslySatisfiedProperty() throws Exception {
        String nusmvPath = resolveNusmvPath();
        Assumptions.assumeTrue(nusmvPath != null && Files.exists(Path.of(nusmvPath)),
                "NuSMV executable is required for this demo-scene regression test");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode scene = objectMapper.readTree(Files.readString(SCENE_PATH));
        SmvGenerator generator = buildGenerator(objectMapper, scene);
        NusmvExecutor executor = buildExecutor(nusmvPath);
        List<DeviceVerificationDto> devices = readDevices(scene);
        List<BoardEnvironmentVariableDto> environment = readEnvironment(scene);
        List<RuleDto> rules = readRules(scene);
        List<SpecificationDto> specs = readSpecs(scene, devices, "reachability");

        SmvGenerator.GenerateResult model = generator.generateWithEnvironment(
                USER_ID, devices, environment, rules, specs,
                AttackScenarioDto.none(), true, SmvGenerator.GeneratePurpose.VERIFICATION);

        // Each `AG !(p)` below asks "is p unreachable?", so every one of them must come back false.
        List<String> mustBeReachable = List.of(
                "a_occupancy = present",
                "a_occupancy = absent",
                "door_1.LockState = unlocked",
                "door_1.LockState = unlocked & a_occupancy = present",
                "alarm_1.AlertState = strobe",
                "light_1.SwitchState = on");

        Map<String, Boolean> reachable = probeReachability(executor, model, mustBeReachable);
        for (String state : mustBeReachable) {
            assertFalse(reachable.get(state) == null,
                    () -> "the probe returned no verdict for: " + state);
            assertTrue(reachable.get(state),
                    () -> "state is unreachable, so any property over it is vacuous: " + state);
        }
    }

    /**
     * Asks NuSMV which of {@code states} the model can actually reach, as {state -> reachable}.
     *
     * <p>Each state is emitted as {@code AG !(state)} — "this never happens". A *failing* check means
     * NuSMV found a witness, so the state is reachable; the verdict is therefore inverted here once,
     * rather than at each call site where the double negative invites a wrong reading.
     *
     * <p>The probe gets its own directory. `NusmvTempArtifactRegistry` locks the *parent directory* of
     * the model file it is handed, so a probe written straight into the system temp dir asks for a lock
     * on all of it — which passes locally, where that directory is per-user and uncontended, and fails
     * on CI the moment any other NuSMV test holds it.
     */
    private Map<String, Boolean> probeReachability(NusmvExecutor executor,
                                                   SmvGenerator.GenerateResult model,
                                                   List<String> states) throws Exception {
        String smv = Files.readString(model.smvFile().toPath());
        StringBuilder probe = new StringBuilder(smv.substring(0, smv.indexOf("-- Specifications")))
                .append("-- Specifications").append(System.lineSeparator());
        for (String state : states) {
            probe.append("\tCTLSPEC AG !(").append(state).append(')').append(System.lineSeparator());
        }

        Path probeDir = Files.createTempDirectory("away-mode-reachability");
        Path probeFile = probeDir.resolve("probe.smv");
        Files.writeString(probeFile, probe.toString());
        try {
            NusmvExecutor.NusmvResult result = executor.execute(probeFile.toFile());
            assertTrue(result.isSuccess(), result::getErrorMessage);
            assertEquals(states.size(), result.getSpecResults().size(),
                    "the probe must return one verdict per requested state");
            Map<String, Boolean> reachable = new java.util.LinkedHashMap<>();
            for (int i = 0; i < states.size(); i++) {
                reachable.put(states.get(i), !result.getSpecResults().get(i).isPassed());
            }
            return reachable;
        } finally {
            // The executor writes its own artifacts (output, lock file) beside the model.
            try (var entries = Files.list(probeDir)) {
                for (Path entry : entries.toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            Files.deleteIfExists(probeDir);
        }
    }

    private RuleFixer allStrategyFixer(SmvGenerator generator, NusmvExecutor executor, int timeoutMs) {
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

    private SmvGenerator buildGenerator(ObjectMapper objectMapper, JsonNode scene) {
        Map<String, String> manifests = new java.util.LinkedHashMap<>();
        for (JsonNode template : scene.path("templates")) {
            try {
                manifests.put(template.path("name").asText(),
                        objectMapper.writeValueAsString(template.path("manifest")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        SmvModelValidator modelValidator = new SmvModelValidator();
        DeviceTemplateService templateService = mock(DeviceTemplateService.class);
        when(templateService.findTemplateByName(anyLong(), anyString())).thenAnswer(invocation -> {
            String templateName = invocation.getArgument(1, String.class);
            String manifest = manifests.get(templateName);
            return manifest == null ? Optional.empty() : Optional.of(DeviceTemplatePo.builder()
                    .id(100L).userId(USER_ID).name(templateName)
                    .manifestJson(manifest).defaultTemplate(true).build());
        });
        DeviceSmvDataFactory factory = new DeviceSmvDataFactory(
                objectMapper, templateService, modelValidator,
                new DeviceTemplateSchemaValidator(objectMapper));
        return new SmvGenerator(factory, new SmvDeviceModuleBuilder(), new SmvRuleCommentWriter(),
                new SmvMainModuleBuilder(), new SmvSpecificationBuilder(), modelValidator);
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
                    row.path("name").asText(), row.path("value").asText(),
                    row.path("trust").asText(), row.path("privacy").asText()));
        }
        return environment;
    }

    private List<RuleDto> readRules(JsonNode scene) {
        return PortableSceneTestImport.importRules(scene);
    }

    private List<SpecificationDto> readSpecs(
            JsonNode scene, List<DeviceVerificationDto> devices, String idPrefix) {
        Map<String, String> labelsById = devices.stream().collect(java.util.stream.Collectors.toMap(
                DeviceVerificationDto::getVarName, DeviceVerificationDto::getDeviceLabel));
        return PortableSceneTestImport.importSpecs(scene, idPrefix, labelsById);
    }

    private List<VariableStateDto> readVariables(JsonNode rows) {
        List<VariableStateDto> variables = new ArrayList<>();
        if (!rows.isArray()) {
            return variables;
        }
        for (JsonNode row : rows) {
            variables.add(new VariableStateDto(row.path("name").asText(),
                    row.path("value").asText(), textOrNull(row, "trust")));
        }
        return variables;
    }

    private List<PrivacyStateDto> readPrivacies(JsonNode rows) {
        List<PrivacyStateDto> privacies = new ArrayList<>();
        if (!rows.isArray()) {
            return privacies;
        }
        for (JsonNode row : rows) {
            privacies.add(new PrivacyStateDto(row.path("name").asText(), row.path("privacy").asText()));
        }
        return privacies;
    }

    private String textOrNull(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
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
