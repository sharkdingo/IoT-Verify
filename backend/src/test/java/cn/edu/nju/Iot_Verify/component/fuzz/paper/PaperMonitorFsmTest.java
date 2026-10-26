package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperMonitorFsmTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void conditionSatisfactionUsesBinaryAtomsAverageAndMaximum() {
        PaperAtom a = atom("a");
        PaperAtom b = atom("b");
        PaperAtom c = atom("c");
        PaperAtomValuation valuation = truths(a, c);

        PaperCondition conjunction = PaperCondition.allOf(
                PaperCondition.atom(a),
                PaperCondition.atom(b));
        PaperCondition disjunction = PaperCondition.anyOf(
                PaperCondition.atom(b),
                PaperCondition.atom(c));

        assertEquals(0.5, conjunction.satisfaction(valuation), EPSILON);
        assertEquals(1.0, disjunction.satisfaction(valuation), EPSILON);
        assertEquals(0.0, PaperCondition.not(PaperCondition.atom(a)).satisfaction(valuation), EPSILON);
        assertEquals(1.0, PaperCondition.not(conjunction).satisfaction(valuation), EPSILON);
        assertFalse(conjunction.test(valuation));
        assertTrue(disjunction.test(valuation));
    }

    @Test
    void atomsAndTheirNegationsUseThePaperBinarySatisfactionRule() {
        PaperAtom numeric = atom("numeric");
        PaperAtomValuation valuation = atom -> false;

        assertEquals(0.0, PaperCondition.atom(numeric).satisfaction(valuation), EPSILON);
        assertEquals(1.0, PaperCondition.not(PaperCondition.atom(numeric)).satisfaction(valuation), EPSILON);
        assertEquals(
                PaperCondition.atom(numeric),
                PaperCondition.allOf(PaperCondition.atom(numeric), PaperCondition.atom(numeric)));
    }

    @Test
    void nestedConditionsFlattenForEqualAtomicWeightsWithoutCrossingTerminalBoundaries() {
        PaperAtom a = atom("a");
        PaperAtom b = atom("b");
        PaperAtom c = atom("c");
        PaperCondition nestedAll = PaperCondition.allOf(
                PaperCondition.allOf(PaperCondition.atom(a), PaperCondition.atom(b)),
                PaperCondition.atom(c),
                PaperCondition.TRUE);
        PaperCondition nestedAny = PaperCondition.anyOf(
                PaperCondition.anyOf(PaperCondition.atom(a), PaperCondition.atom(b)),
                PaperCondition.atom(a),
                PaperCondition.FALSE);
        PaperCondition terminalBoundary = PaperCondition.allOf(
                PaperCondition.terminal(PaperCondition.allOf(
                        PaperCondition.atom(a), PaperCondition.atom(b))),
                PaperCondition.atom(c));

        assertEquals(2.0 / 3.0, nestedAll.satisfaction(truths(a, c)), EPSILON);
        assertEquals(3, ((PaperCondition.All) nestedAll).conditions().size());
        assertEquals(2, ((PaperCondition.Any) nestedAny).conditions().size());
        assertEquals(2, ((PaperCondition.All) terminalBoundary).conditions().size());
        assertTrue(((PaperCondition.All) terminalBoundary).conditions().get(0)
                instanceof PaperCondition.Terminal);
    }

    @Test
    void alwaysAndNeverMonitorsReachFalseThroughExplicitViolationState() {
        PaperAtom a = atom("a");
        PaperMonitorFsm always = PaperStructuredMonitorFactory.from(specification("1", List.of(condition("a")), List.of(), List.of()));

        assertEquals(2, always.states().size());
        assertEquals(1, always.shortestDistanceToViolation().orElseThrow());
        assertEquals(PaperTruthValue.INCONCLUSIVE, always.step(truths(a)));
        assertEquals(PaperTruthValue.FALSE, always.step(truths()));
        assertEquals(PaperStructuredMonitorFactory.VIOLATION, always.currentState().id());
        assertEquals(0, always.shortestDistanceToViolation().orElseThrow());

        PaperMonitorFsm never = PaperStructuredMonitorFactory.from(
                specification("3", List.of(condition("a")), List.of(), List.of()));
        assertEquals(PaperTruthValue.INCONCLUSIVE, never.step(truths()));
        assertEquals(PaperTruthValue.FALSE, never.step(truths(a)));
    }

    @Test
    void immediateMonitorTracksPendingConsequentAndRepeatedAntecedent() {
        PaperAtom antecedent = atom("if");
        PaperAtom consequent = atom("then");
        PaperMonitorFsm monitor = PaperStructuredMonitorFactory.from(specification(
                "4",
                List.of(),
                List.of(condition("if")),
                List.of(condition("then"))));

        assertEquals(3, monitor.states().size());
        assertEquals(6, monitor.transitions().size());
        assertEquals(2, monitor.shortestDistanceToViolation().orElseThrow());
        assertEquals(PaperTruthValue.INCONCLUSIVE, monitor.step(truths(antecedent)));
        assertEquals(PaperStructuredMonitorFactory.AWAITING_THEN, monitor.currentState().id());
        assertEquals(1, monitor.shortestDistanceToViolation().orElseThrow());

        Optional<PaperCondition> towardViolation = monitor.nextConditionTowardViolation();
        assertTrue(towardViolation.isPresent());
        assertEquals(1.0, towardViolation.orElseThrow().satisfaction(truths(antecedent)), EPSILON);

        assertEquals(PaperTruthValue.INCONCLUSIVE, monitor.step(truths(antecedent, consequent)));
        assertEquals(PaperStructuredMonitorFactory.AWAITING_THEN, monitor.currentState().id());
        assertEquals(PaperTruthValue.FALSE, monitor.step(truths(antecedent)));
    }

    @Test
    void bfsUsesOnlyConditionsOnAShortestPathToViolation() {
        PaperAtom shortGuard = atom("short");
        PaperAtom longGuard = atom("long");
        PaperMonitorFsm monitor = PaperMonitorFsm.builder("start")
                .state("start", PaperTruthValue.INCONCLUSIVE)
                .state("short", PaperTruthValue.INCONCLUSIVE)
                .state("long1", PaperTruthValue.INCONCLUSIVE)
                .state("long2", PaperTruthValue.INCONCLUSIVE)
                .state("violation", PaperTruthValue.FALSE)
                .transition("start", "short", PaperCondition.atom(shortGuard))
                .transition("start", "long1", PaperCondition.atom(longGuard))
                .transition("short", "violation", PaperCondition.TRUE)
                .transition("long1", "long2", PaperCondition.TRUE)
                .transition("long2", "violation", PaperCondition.TRUE)
                .transition("violation", "violation", PaperCondition.TRUE)
                .build();

        assertEquals(2, monitor.shortestDistanceToViolation().orElseThrow());
        PaperCondition shortestGuard = monitor.nextConditionTowardViolation().orElseThrow();
        assertEquals(0.0, shortestGuard.satisfaction(truths(longGuard)), EPSILON);
        assertEquals(1.0, shortestGuard.satisfaction(truths(shortGuard)), EPSILON);
    }

    @Test
    void distanceIncludesSolverLevelPredecessorWeights() {
        PaperAtom antecedent = atom("if");
        PaperAtom predecessor = atom("previous");
        PaperMonitorFsm monitor = PaperStructuredMonitorFactory.from(specification(
                "4",
                List.of(),
                List.of(condition("if")),
                List.of(condition("then"))));
        PaperAtomValuation valuation = truths(predecessor);

        PaperMonitorFsm.Distance distance = monitor.distanceToViolation(
                        valuation,
                        (current, level) -> level == 1 ? PaperCondition.atom(predecessor) : null,
                        2)
                .orElseThrow();

        assertEquals(2, distance.graphDistance());
        assertEquals(1.0 / 3.0, distance.weightedConditionSatisfaction(), EPSILON);
        assertEquals(5.0 / 3.0, distance.combinedDistance(), EPSILON);
        assertEquals(0.0, distance.nextCondition().orElseThrow().satisfaction(valuation), EPSILON);
        assertFalse(valuation.isTrue(antecedent));
    }

    @Test
    void explicitTrueStateCompletesTheThreeValuedContract() {
        PaperMonitorFsm monitor = PaperMonitorFsm.builder("done")
                .state("done", PaperTruthValue.TRUE)
                .transition("done", "done", PaperCondition.TRUE)
                .build();

        assertEquals(PaperTruthValue.TRUE, monitor.currentTruthValue());
        assertEquals(PaperTruthValue.TRUE, monitor.step(truths()));
        assertTrue(monitor.shortestDistanceToViolation().isEmpty());
        assertTrue(monitor.distanceToViolation(truths(), PaperPredecessorResolver.none(), 1).isEmpty());
    }

    @Test
    void factoryRejectsUnsupportedOrMalformedTemplateShapes() {
        assertThrows(IllegalArgumentException.class, () -> PaperStructuredMonitorFactory.from(
                specification("2", List.of(condition("a")), List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> PaperStructuredMonitorFactory.from(
                specification("1", List.of(condition("a")), List.of(condition("if")), List.of())));
        assertThrows(IllegalArgumentException.class, () -> PaperStructuredMonitorFactory.from(
                specification("4", List.of(), List.of(), List.of(condition("then")))));
    }

    private static PaperAtom atom(String key) {
        return PaperAtom.from(condition(key));
    }

    private static PaperAtomValuation truths(PaperAtom... atoms) {
        Set<PaperAtom> trueAtoms = Set.of(atoms);
        return trueAtoms::contains;
    }

    private static SpecConditionDto condition(String key) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("device_1");
        condition.setTargetType("variable");
        condition.setKey(key);
        condition.setRelation("=");
        condition.setValue("TRUE");
        return condition;
    }

    private static SpecificationDto specification(
            String templateId,
            List<SpecConditionDto> aConditions,
            List<SpecConditionDto> ifConditions,
            List<SpecConditionDto> thenConditions) {
        SpecificationDto specification = new SpecificationDto();
        specification.setId("spec-" + templateId);
        specification.setName("Safety requirement");
        specification.setTemplateId(templateId);
        specification.setAConditions(aConditions);
        specification.setIfConditions(ifConditions);
        specification.setThenConditions(thenConditions);
        return specification;
    }
}
