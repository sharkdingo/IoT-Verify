package cn.edu.nju.Iot_Verify.component.fuzz;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardTemporalGuidanceTest {

    @Test
    void forbiddenGoalDoesNotScoreAnImpossibleEarlyState() {
        for (boolean local : List.of(false, true)) {
            var spec = specification("3", ">=", "3");
            FuzzModel model = counter(local, spec);
            var simulation = model.simulate(new long[]{2}, 2);
            assertEquals("2", valueAt(simulation, 1, local));
            var evaluation = model.evaluate(spec, simulation);
            assertEquals(-1, evaluation.violationStep());
            assertTrue(Double.isInfinite(evaluation.distance()),
                    "a value that cannot exceed 2 at step 1 must not guide toward >=3 there");
        }
    }

    @Test
    void responseDoesNotUseAnAntecedentBeforeItCanBeReached() {
        var spec = specification("4", ">=", "3");
        FuzzModel model = counter(false, spec);
        var simulation = model.simulate(new long[]{2, 4}, 3);
        assertEquals("2", valueAt(simulation, 1, false));
        assertEquals("4", valueAt(simulation, 2, false));
        var evaluation = model.evaluate(spec, simulation);
        assertEquals(-1, evaluation.violationStep());
        assertTrue(Double.isInfinite(evaluation.distance()),
                "the first reachable antecedent has no successor in this three-state path");
    }

    @Test
    void aLaterReachableWindowStillGuidesAndDetectsTheViolation() {
        var spec = specification("4", ">=", "3");
        FuzzModel model = counter(false, spec);
        var far = model.evaluate(spec, model.simulate(new long[0], 4));
        var near = model.evaluate(spec, model.simulate(new long[]{0, 2, 2}, 4));
        assertEquals(-1, near.violationStep());
        assertTrue(Double.isFinite(near.distance()));
        assertTrue(near.distance() > 0.0 && near.distance() < far.distance());
        var violation = model.evaluate(spec, model.simulate(new long[]{2, 4, 2}, 4));
        assertEquals(3, violation.violationStep());
        assertEquals(0.0, violation.distance());
    }

    @Test
    void invariantNeedsAPossibleFailureAtThatStep() {
        var spec = specification("1", "<=", "2");
        FuzzModel model = counter(false, spec);
        var early = model.evaluate(spec, model.simulate(new long[]{2}, 2));
        assertEquals(-1, early.violationStep());
        assertTrue(Double.isInfinite(early.distance()));
        var later = model.evaluate(spec, model.simulate(new long[]{2, 2}, 3));
        assertEquals(-1, later.violationStep());
        assertTrue(Double.isFinite(later.distance()) && later.distance() > 0.0);
        assertEquals(2, model.evaluate(spec, model.simulate(new long[]{2, 4}, 3)).violationStep());
    }

    @Test
    void anUnfalsifiableConjunctDoesNotHideProgressTowardARealFailure() {
        for (String template : List.of("1", "4")) {
            var spec = specification(template, "<=", "7");
            var requirements = List.of(condition("count", ">=", "0"), condition("count", "<=", "7"));
            if ("1".equals(template)) {
                spec.setAConditions(requirements);
            } else {
                spec.setIfConditions(List.of(condition("count", ">=", "0")));
                spec.setThenConditions(requirements);
            }
            FuzzModel model = counter(false, spec);
            var far = model.evaluate(spec, model.simulate(new long[0], 5));
            var nearPath = model.simulate(new long[]{2, 4, 4, 2}, 5);
            assertEquals("6", valueAt(nearPath, 4, false));
            var near = model.evaluate(spec, nearPath);
            assertEquals(-1, far.violationStep());
            assertEquals(-1, near.violationStep());
            assertEquals(1.0, far.distance(), 1.0e-9,
                    "count>=0 cannot fail and must not contribute its artificially small distance");
            assertEquals(0.25, near.distance(), 1.0e-9);
            assertTrue(near.distance() < far.distance());
            var violation = model.evaluate(spec, model.simulate(new long[]{2, 4, 4, 4}, 5));
            assertEquals(4, violation.violationStep());
            assertEquals(0.0, violation.distance());
        }
    }

    @Test
    void numericSetAlternativesUseOnlyTargetsReachableAtThatStep() {
        for (String template : List.of("1", "3", "4")) {
            for (boolean toMember : List.of(false, true)) {
                for (boolean local : List.of(false, true)) {
                    String relation = toMember == "3".equals(template) ? "in" : "not in";
                    String expected = toMember ? "0,6" : "1,2,3,4,5";
                    var spec = specification(template, relation, expected);
                    if ("4".equals(template)) {
                        spec.setIfConditions(List.of(condition("count", "=", "1")));
                        spec.setThenConditions(List.of(condition("count", relation, expected)));
                    }
                    var domain = DeviceManifest.InternalVariable.builder().name("count").isInside(local)
                            .lowerBound(0).upperBound(8).naturalChangeRate("[-4,4]")
                            .falsifiableWhenCompromised(false).trust("trusted").privacy("public").build();
                    var snapshot = snapshot(List.of(domain),
                            local ? List.of(new VariableStateDto("count", "1", "trusted")) : List.of(),
                            local ? List.of() : List.of(new BoardEnvironmentVariableDto("count", "1", "trusted", "public")), spec);
                    FuzzModel model = checkedModel(snapshot, spec);
                    var farPath = model.simulate(new long[]{5}, 2);
                    var nearPath = model.simulate(new long[]{4}, 2);
                    assertEquals("5", valueAt(farPath, 1, local));
                    assertEquals("4", valueAt(nearPath, 1, local));
                    var far = model.evaluate(spec, farPath);
                    var near = model.evaluate(spec, nearPath);
                    assertEquals(-1, far.violationStep());
                    assertEquals(-1, near.violationStep());
                    assertEquals(0.625, far.distance(), 1.0e-9,
                            "target 6 is outside step 1's support; only target 0 is reachable");
                    assertEquals(0.5, near.distance(), 1.0e-9);
                    assertTrue(near.distance() < far.distance());
                    assertEquals(1, model.evaluate(spec, model.simulate(new long[]{0}, 2)).violationStep());
                }
            }
        }
    }

    @Test
    void aFixedInitialStateDoesNotSupplyMutableGuidance() {
        var forbidden = specification("3", ">=", "1");
        FuzzModel model = counter(false, forbidden);
        assertTrue(Double.isInfinite(model.evaluate(forbidden, model.simulate(new long[0], 1)).distance()));
        var invariant = specification("1", "=", "0");
        model.validateSpecification(invariant);
        assertTrue(Double.isInfinite(model.evaluate(invariant, model.simulate(new long[0], 1)).distance()));
    }

    @Test
    void aDormantLocalAssignmentStillPreventsARateOnlyCertificate() {
        var spec = specification("3", ">=", "3");
        var snapshot = counterSnapshot(true, spec);
        withModes(snapshot, List.of(workingState("off", null)), List.of(dormantAssignment()));
        FuzzModel model = checkedModel(snapshot, spec);
        var simulation = model.simulate(new long[]{2}, 2);

        assertEquals("0", valueAt(simulation, 0, true));
        assertEquals("2", valueAt(simulation, 1, true), "the assignment has not fired on this path");
        var evaluation = model.evaluate(spec, simulation);
        assertEquals(-1, evaluation.violationStep());
        assertTrue(Double.isFinite(evaluation.distance()) && evaluation.distance() > 0.0,
                "a possible future assignment must not be certified as natural-rate-only");
    }

    @Test
    void anInactiveLocalDynamicStillPreventsARateOnlyCertificate() {
        var spec = specification("3", ">=", "3");
        var snapshot = counterSnapshot(true, spec);
        withModes(snapshot, List.of(workingState("off", null), workingState("on", "4")),
                List.of(activationTransition()));
        FuzzModel model = checkedModel(snapshot, spec);
        var simulation = model.simulate(new long[]{2}, 2);

        assertEquals("2", valueAt(simulation, 1, true));
        assertEquals("off", simulation.traceStates().get(1).getDevices().get(0).getState());
        var evaluation = model.evaluate(spec, simulation);
        assertEquals(-1, evaluation.violationStep());
        assertTrue(Double.isFinite(evaluation.distance()) && evaluation.distance() > 0.0,
                "a dynamic in another legal mode must not be omitted from the writer analysis");
    }

    @Test
    void anAssignmentFromAnotherDevicePreventsASharedRateOnlyCertificate() {
        var spec = specification("3", ">=", "3");
        var snapshot = withOtherWriter(counterSnapshot(false, spec), false);
        FuzzModel model = checkedModel(snapshot, spec);
        var simulation = model.simulate(new long[]{2}, 2);

        assertEquals("2", valueAt(simulation, 1, false), "the other device's assignment has not fired");
        var evaluation = model.evaluate(spec, simulation);
        assertEquals(-1, evaluation.violationStep());
        assertTrue(Double.isFinite(evaluation.distance()) && evaluation.distance() > 0.0,
                "shared assignment analysis must include devices other than the reader");
    }

    @Test
    void anInactiveSharedImpactPreventsASharedRateOnlyCertificate() {
        var spec = specification("3", ">=", "3");
        var snapshot = withOtherWriter(counterSnapshot(false, spec), true);
        FuzzModel model = checkedModel(snapshot, spec);
        var simulation = model.simulate(new long[]{2}, 2);

        assertEquals("2", valueAt(simulation, 1, false));
        assertEquals("off", simulation.traceStates().get(1).getDevices().get(1).getState());
        var evaluation = model.evaluate(spec, simulation);
        assertEquals(-1, evaluation.violationStep());
        assertTrue(Double.isFinite(evaluation.distance()) && evaluation.distance() > 0.0,
                "a declared shared impact must be considered even while its writer is inactive");
    }

    @Test
    void theUnreachableWindowRegressionIsFoundWithinItsOriginalBudget() {
        var snapshot = mixedResponse();
        var input = new FuzzEngineInput(snapshot, new FuzzEngineConfig(List.of(), 30, 8, 3, 30L));
        var first = new FuzzEngine().run(input, null, () -> false);
        var repeated = new FuzzEngine().run(input, null, () -> false);
        assertEquals(FuzzEngineOutcome.FINDINGS_FOUND, first.outcome());
        assertEquals(1, first.findings().size());
        assertTrue(first.iterations() <= 30);
        assertTrue(first.generatedPaths() <= 90);
        assertEquals(first.findings(), repeated.findings());
    }

    private static FuzzModel counter(boolean local, SpecificationDto spec) {
        return checkedModel(counterSnapshot(local, spec), spec);
    }

    private static BoardDataConverter.ModelInputSnapshot counterSnapshot(boolean local, SpecificationDto spec) {
        var domain = DeviceManifest.InternalVariable.builder().name("count").isInside(local)
                .lowerBound(0).upperBound(8).naturalChangeRate("[-2,2]")
                .falsifiableWhenCompromised(false).trust("trusted").privacy("public").build();
        return snapshot(List.of(domain),
                local ? List.of(new VariableStateDto("count", "0", "trusted")) : List.of(),
                local ? List.of() : List.of(new BoardEnvironmentVariableDto("count", "0", "trusted", "public")),
                spec);
    }

    private static FuzzModel checkedModel(
            BoardDataConverter.ModelInputSnapshot snapshot, SpecificationDto spec) {
        FuzzModel model = FuzzModel.from(snapshot);
        model.validateSpecification(spec);
        return model;
    }

    private static void withModes(
            BoardDataConverter.ModelInputSnapshot snapshot, List<DeviceManifest.WorkingState> states,
            List<DeviceManifest.Transition> transitions) {
        snapshot.devices().get(0).setState("off");
        var manifest = snapshot.templateManifests().get("TemporalSource");
        manifest.setModes(List.of("PowerMode"));
        manifest.setInitState("off");
        manifest.setWorkingStates(states);
        manifest.setTransitions(transitions);
    }

    private static BoardDataConverter.ModelInputSnapshot withOtherWriter(
            BoardDataConverter.ModelInputSnapshot snapshot, boolean impact) {
        var writer = new DeviceVerificationDto();
        writer.setVarName("writer_1");
        writer.setDeviceLabel("Other writer");
        writer.setTemplateName("OtherWriter");
        writer.setState("off");
        writer.setVariables(List.of());
        writer.setPrivacies(List.of());
        var domain = DeviceManifest.InternalVariable.builder().name("count").isInside(false)
                .lowerBound(0).upperBound(8).naturalChangeRate("[-2,2]")
                .falsifiableWhenCompromised(false).trust("trusted").privacy("public").build();
        var manifest = DeviceManifest.builder().name("OtherWriter").modes(List.of("PowerMode"))
                .internalVariables(List.of(domain)).impactedVariables(impact ? List.of("count") : List.of())
                .initState("off")
                .workingStates(impact
                        ? List.of(workingState("off", null), workingState("on", "4"))
                        : List.of(workingState("off", null)))
                .transitions(List.of(impact ? activationTransition() : dormantAssignment()))
                .apis(List.of()).contents(List.of()).build();
        var manifests = new LinkedHashMap<>(snapshot.templateManifests());
        manifests.put("OtherWriter", manifest);
        return new BoardDataConverter.ModelInputSnapshot(snapshot.nodes(),
                List.of(snapshot.devices().get(0), writer), snapshot.environmentVariables(), snapshot.rules(),
                snapshot.specifications(), manifests);
    }

    private static DeviceManifest.WorkingState workingState(String name, String rate) {
        return DeviceManifest.WorkingState.builder().name(name).trust("trusted").privacy("public")
                .dynamics(rate == null ? List.of() : List.of(DeviceManifest.Dynamic.builder()
                        .variableName("count").changeRate(rate).build())).build();
    }

    private static DeviceManifest.Transition dormantAssignment() {
        return DeviceManifest.Transition.builder().name("assign-later").startState("off").endState("")
                .trigger(DeviceManifest.Trigger.builder().attribute("count").relation(">=").value("7").build())
                .assignments(List.of(DeviceManifest.Assignment.builder().attribute("count").value("8").build()))
                .build();
    }

    private static DeviceManifest.Transition activationTransition() {
        return DeviceManifest.Transition.builder().name("activate-writer").startState("off").endState("on")
                .trigger(DeviceManifest.Trigger.builder().attribute("count").relation(">=").value("2").build())
                .assignments(List.of()).build();
    }

    private static BoardDataConverter.ModelInputSnapshot mixedResponse() {
        List<DeviceManifest.InternalVariable> domains = new ArrayList<>();
        List<BoardEnvironmentVariableDto> environment = new ArrayList<>();
        List<SpecConditionDto> conditions = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            boolean numeric = index % 2 == 1;
            String name = "x" + index;
            var builder = DeviceManifest.InternalVariable.builder().name(name).isInside(false)
                    .falsifiableWhenCompromised(false).trust("trusted").privacy("public");
            if (numeric) builder.lowerBound(0).upperBound(8).naturalChangeRate("[-2,2]");
            else builder.values(List.of("a", "b", "c", "d"));
            domains.add(builder.build());
            environment.add(new BoardEnvironmentVariableDto(name, numeric ? "0" : "a", "trusted", "public"));
            conditions.add(condition(name, numeric ? ">=" : "=", numeric ? "3" : "b"));
        }
        var spec = specification("4", ">=", "3");
        spec.setIfConditions(conditions);
        spec.setThenConditions(List.of(condition("x0", "=", "a")));
        return snapshot(domains, List.of(), environment, spec);
    }

    private static BoardDataConverter.ModelInputSnapshot snapshot(
            List<DeviceManifest.InternalVariable> domains, List<VariableStateDto> locals,
            List<BoardEnvironmentVariableDto> environment, SpecificationDto spec) {
        var device = new DeviceVerificationDto();
        device.setVarName("source_1");
        device.setDeviceLabel("Temporal source");
        device.setTemplateName("TemporalSource");
        device.setVariables(locals);
        device.setPrivacies(List.of());
        var manifest = DeviceManifest.builder().name("TemporalSource").modes(List.of())
                .internalVariables(domains).impactedVariables(List.of()).workingStates(List.of())
                .transitions(List.of()).apis(List.of()).contents(List.of()).build();
        return new BoardDataConverter.ModelInputSnapshot(
                List.of(), List.of(device), environment, List.of(), List.of(spec), Map.of("TemporalSource", manifest));
    }

    private static SpecificationDto specification(String template, String relation, String value) {
        var spec = new SpecificationDto();
        spec.setId("temporal");
        spec.setName("Temporal safety");
        spec.setTemplateId(template);
        spec.setDevices(List.of());
        spec.setAConditions("4".equals(template) ? List.of() : List.of(condition("count", relation, value)));
        spec.setIfConditions("4".equals(template) ? List.of(condition("count", relation, value)) : List.of());
        spec.setThenConditions("4".equals(template) ? List.of(condition("count", "<", "3")) : List.of());
        return spec;
    }

    private static SpecConditionDto condition(String key, String relation, String value) {
        var condition = new SpecConditionDto();
        condition.setDeviceId("source_1");
        condition.setTargetType("variable");
        condition.setKey(key);
        condition.setRelation(relation);
        condition.setValue(value);
        return condition;
    }

    private static String valueAt(FuzzModel.Simulation simulation, int index, boolean local) {
        var state = simulation.traceStates().get(index);
        return local ? state.getDevices().get(0).getVariables().get(0).getValue()
                : state.getEnvVariables().get(0).getValue();
    }
}
