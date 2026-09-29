package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig.ConditionValueInfo;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyUtils.Verification;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyUtils.Witness;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.GuardProbe;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rejection's lemma is read off its violating path: wherever a scoped rule's other guard terms held, an
 * assignment escapes the rejection only if that rule's trigger conditions evaluate differently there. These
 * pin how each probe value becomes a literal, and that a path which does not settle every value teaches
 * nothing rather than a guess.
 */
class CounterexampleLemmaTest {

    // Rule 0 as the search model has it: an original condition, a fixed candidate and a chosen-value one.
    private final RuleDto.Condition temperature = condition("sensor_1", "temperature", "variable", ">", "30");
    private final RuleDto.Condition door = condition("door_1", "Mode", "mode", "=", "open");
    private final RuleDto.Condition occupancy = condition("occupancy_1", "occupied", "variable", "=", "present");
    private final List<RuleDto> augmentedRules = List.of(
            rule(temperature, door, occupancy),
            rule(condition("sensor_1", "temperature", "variable", "<", "10")));
    // The rejected candidate keeps only the original condition; rule 1 is outside the repair scope.
    private final List<RuleDto> candidateRules = List.of(
            rule(condition("sensor_1", "temperature", "variable", ">", "30")),
            rule(condition("sensor_1", "temperature", "variable", "<", "10")));
    private final CounterexampleLemma.Attempt attempt = CounterexampleLemma.plan(
                    augmentedRules,
                    Map.of("r0_c0", "lambda_r0_c0", "r0_c1", "lambda_r0_c1", "r0_c2", "lambda_r0_c2"),
                    Map.of("r0_c2", valueChosenBy("condition_value_r0_c2")))
            .attempt(candidateRules);

    @Test
    void probesDescribeTheSearchModelsConditionsAndTheCandidatesOwnGuard() {
        assertEquals(List.of(GuardProbe.Kind.CONDITION, GuardProbe.Kind.CONDITION, GuardProbe.Kind.OPERAND,
                        GuardProbe.Kind.RULE_BASE, GuardProbe.Kind.RULE_CONDITIONS),
                attempt.probes().stream().map(GuardProbe::kind).toList());
        List<GuardProbe> probes = attempt.probes();
        assertTrue(probes.get(0).condition() == temperature && probes.get(1).condition() == door
                && probes.get(2).condition() == occupancy, "atoms probe the search model's conditions");
        // The model renders probes of rules it holds by identity; a copy would be silently omitted.
        assertTrue(probes.get(3).rule() == candidateRules.get(0) && probes.get(4).rule() == candidateRules.get(0),
                "guard probes name the verified candidate's rule instance");
        assertEquals(probes.size(), probes.stream().map(GuardProbe::name).distinct().count());
    }

    @Test
    void whereTheRejectedGuardHeldAnAssignmentEscapesOnlyByFailingIt() {
        // temperature>30 held, so keeping it cannot make the guard fail; the door was closed, so keeping that
        // condition can; the chosen occupancy candidate fails exactly when its value is not the observed one.
        String lemma = attempt.learn(rejectedOn("target", trace(
                state(true, false, "present", true, true))), specs("target"));

        assertEquals("(!(!lambda_r0_c1 & (!lambda_r0_c2 | (present=condition_value_r0_c2))))", lemma);
    }

    @Test
    void whereTheRejectedGuardFailedAnAssignmentEscapesOnlyBySatisfyingIt() {
        String lemma = attempt.learn(rejectedOn("target", trace(
                state(false, true, "absent", true, false))), specs("target"));

        assertEquals("((!lambda_r0_c0 & (!lambda_r0_c2 | (absent=condition_value_r0_c2))))", lemma);
    }

    @Test
    void aRuleThatCannotFireAnywhereOnThePathRefutesEveryAssignment() {
        // State 2 reprints only a condition; the FALSE base it inherits still keeps the rule out.
        String lemma = attempt.learn(rejectedOn("target", trace(
                state(true, true, "present", false, false),
                assign(1, FALSE))), specs("target"));

        assertEquals(CounterexampleLemma.REFUTES_ALL, lemma);
    }

    @Test
    void laterStatesInheritUnprintedValuesAndEachContributesItsOwnEscape() {
        String lemma = attempt.learn(rejectedOn("target", trace(
                state(true, false, "present", true, true),
                assign(4, FALSE) + assign(0, FALSE),
                "")), specs("target"));

        // State 3 changes no probe, so it repeats state 2's escape rather than adding one.
        assertEquals("(!(!lambda_r0_c1 & (!lambda_r0_c2 | (present=condition_value_r0_c2)))"
                + " | (!lambda_r0_c0 & !lambda_r0_c1 & (!lambda_r0_c2 | (present=condition_value_r0_c2))))", lemma);
    }

    @Test
    void aChosenValueIsRelatedToTheObservedOperandTheWayItsConditionRelatesThem() {
        RuleDto.Condition warm = condition("sensor_1", "temperature", "variable", "GT", "27");
        RuleDto.Condition somebody = condition("occupancy_1", "occupied", "variable", "in", "present");
        CounterexampleLemma.Attempt chosen = CounterexampleLemma.plan(
                        List.of(rule(warm, somebody)),
                        Map.of("r0_c0", "lambda_r0_c0", "r0_c1", "lambda_r0_c1"),
                        Map.of("r0_c0", valueChosenBy("condition_value_r0_c0"),
                                "r0_c1", valueChosenBy("condition_value_r0_c1")))
                .attempt(List.of(rule(condition("sensor_1", "temperature", "variable", ">", "27"))));

        String lemma = chosen.learn(rejectedOn("target", trace(
                assign(0, "-3") + assign(1, "absent") + assign(2, TRUE) + assign(3, FALSE))), specs("target"));

        assertEquals("(((!lambda_r0_c0 | (-3>condition_value_r0_c0))"
                + " & (!lambda_r0_c1 | (absent=condition_value_r0_c1))))", lemma);
    }

    @Test
    void anOperandThatIsNotASingleLiteralTeachesNothing() {
        assertNull(attempt.learn(rejectedOn("target", trace(
                assign(0, TRUE) + assign(1, FALSE) + assign(2, "3.5") + assign(3, TRUE) + assign(4, TRUE))),
                specs("target")));
    }

    @Test
    void aGuardProbeThatIsNotBooleanTeachesNothing() {
        assertNull(attempt.learn(rejectedOn("target", trace(
                assign(0, TRUE) + assign(1, FALSE) + assign(2, "present") + assign(3, "1") + assign(4, TRUE))),
                specs("target")));
        assertNull(attempt.learn(rejectedOn("target", trace(
                assign(0, TRUE) + assign(1, FALSE) + assign(2, "present") + assign(3, TRUE) + assign(4, "1"))),
                specs("target")));
    }

    @Test
    void aPathThatDoesNotReportEveryProbeTeachesNothing() {
        assertNull(attempt.learn(rejectedOn("target", trace(
                assign(0, TRUE) + assign(1, FALSE) + assign(2, "present") + assign(3, TRUE))), specs("target")));
    }

    @Test
    void probesThatContradictTheReportedGuardTeachNothing() {
        RuleDto.Condition hot = condition("sensor_1", "temperature", "variable", ">", "30");
        CounterexampleLemma.Attempt fixedOnly = CounterexampleLemma.plan(
                        List.of(rule(hot)), Map.of("r0_c0", "lambda_r0_c0"), Map.of())
                .attempt(List.of(rule(condition("sensor_1", "temperature", "variable", ">", "30"))));

        // Every condition held, yet the guard is reported false: the path is not of the model described.
        assertNull(fixedOnly.learn(rejectedOn("target", trace(
                assign(0, TRUE) + assign(1, TRUE) + assign(2, FALSE))), specs("target")));
        // Every condition held and so did the guard: no assignment's guard can differ here.
        assertEquals(CounterexampleLemma.REFUTES_ALL, fixedOnly.learn(rejectedOn("target", trace(
                assign(0, TRUE) + assign(1, TRUE) + assign(2, TRUE))), specs("target")));
    }

    @Test
    void aViolationTheArgumentDoesNotCoverTeachesNothing() {
        String path = trace(state(true, false, "present", true, true));
        assertNotNull(attempt.learn(rejectedOn("target", path), specs("target")), "the ordinary case learns");

        assertNull(attempt.learn(Verification.rejected(), specs("target")), "no witness");
        assertNull(attempt.learn(rejectedOn("missing", path), specs("target")), "unknown specification");
        assertNull(attempt.learn(rejectedOn("target", path), List.of(spec("target", "1"), spec("target", "1"))),
                "ambiguous specification");
        // Trust and privacy labels follow the conditions' own labels, which the argument does not cover.
        assertNull(attempt.learn(rejectedOn("target", path), List.of(spec("target", "7"))), "template 7");
        SpecificationDto trust = spec("target", "1");
        trust.setIfConditions(List.of(specCondition(" Trust ")));
        assertNull(attempt.learn(rejectedOn("target", path), List.of(trust)), "a trust condition");
        SpecificationDto privacy = spec("target", "1");
        privacy.setThenConditions(List.of(specCondition("privacy")));
        assertNull(attempt.learn(rejectedOn("target", path), List.of(privacy)), "a privacy condition");
        SpecificationDto untyped = spec("target", "1");
        untyped.setAConditions(List.of(specCondition(null)));
        assertNull(attempt.learn(rejectedOn("target", path), List.of(untyped)), "an untyped condition");
        SpecificationDto hole = spec("target", "1");
        hole.setAConditions(Arrays.asList(specCondition("variable"), null));
        assertNull(attempt.learn(rejectedOn("target", path), List.of(hole)), "a missing condition");
    }

    @Test
    void aRuleTheSearchCannotFullySwitchIsNotDescribed() {
        RuleDto.Condition hot = condition("sensor_1", "temperature", "variable", ">", "30");
        RuleDto.Condition open = condition("door_1", "Mode", "mode", "=", "open");

        assertNull(CounterexampleLemma.plan(List.of(rule(hot, open)), Map.of("r0_c0", "lambda_r0_c0"), Map.of()),
                "a condition without a search variable");
        assertNull(CounterexampleLemma.plan(List.of(rule(hot, null)), Map.of("r0_c0", "lambda_r0_c0",
                "r0_c1", "lambda_r0_c1"), Map.of()), "a missing condition");
        assertNull(CounterexampleLemma.plan(List.of(rule(hot)), Map.of("r1_c0", "lambda_r1_c0"), Map.of()),
                "a key past the rules");
        assertNull(CounterexampleLemma.plan(List.of(rule(hot)), Map.of("c0", "lambda_c0"), Map.of()),
                "a malformed key");
        RuleDto.Condition approximately = condition("sensor_1", "temperature", "variable", "~", "30");
        assertNull(CounterexampleLemma.plan(List.of(rule(approximately)), Map.of("r0_c0", "lambda_r0_c0"),
                Map.of("r0_c0", valueChosenBy("condition_value_r0_c0"))), "an unsupported chosen relation");
    }

    @Test
    void valuationsSkipInputsAndCarryUnprintedValuesForward() {
        String trace = "Trace Description: CTL Counterexample \r\n"
                + "Trace Type: Counterexample \r\n"
                + "  -> State: 1.1 <-\r\n"
                + "    a = TRUE\r\n"
                + "    b = x\r\n"
                + "    unrelated = 7\r\n"
                + "  -> Input: 1.2 <-\r\n"
                + "    a = FALSE\r\n"
                + "  -- Loop starts here\r\n"
                + "  -> State: 1.2 <-\r\n"
                + "    b = y\r\n";

        assertEquals(List.of(Map.of("a", "TRUE", "b", "x"), Map.of("a", "TRUE", "b", "y")),
                CounterexampleLemma.valuations(trace, List.of("a", "b")));
        assertNull(CounterexampleLemma.valuations(trace, List.of("a", "c")), "a name the first state lacks");
        assertNull(CounterexampleLemma.valuations("Trace Description: none\n", List.of("a")), "no state");
    }

    private static final String TRUE = "TRUE";
    private static final String FALSE = "FALSE";

    /** A first state of {@link #attempt}'s probes, in probe order. */
    private static String state(boolean temperatureHolds, boolean doorHolds, String occupied,
                                boolean base, boolean conditions) {
        return assign(0, bool(temperatureHolds)) + assign(1, bool(doorHolds)) + assign(2, occupied)
                + assign(3, bool(base)) + assign(4, bool(conditions))
                + "    lock_1.Mode = locked\n";
    }

    private static String assign(int probe, String value) {
        return "    " + SmvConstants.GUARD_PROBE_PREFIX + probe + " = " + value + "\n";
    }

    private static String bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** A NuSMV counterexample whose states print the given assignments. */
    private static String trace(String... states) {
        StringBuilder trace = new StringBuilder("Trace Description: CTL Counterexample \nTrace Type: Counterexample \n");
        for (int i = 0; i < states.length; i++) {
            trace.append("  -> State: 1.").append(i + 1).append(" <-\n").append(states[i]);
        }
        return trace.toString();
    }

    private static Verification rejectedOn(String specId, String trace) {
        return Verification.rejected(new Witness(specId, trace));
    }

    private static List<SpecificationDto> specs(String... ids) {
        return Arrays.stream(ids).map(id -> spec(id, "1")).toList();
    }

    private static SpecificationDto spec(String id, String templateId) {
        SpecificationDto spec = new SpecificationDto();
        spec.setId(id);
        spec.setTemplateId(templateId);
        spec.setAConditions(List.of(specCondition("variable")));
        return spec;
    }

    private static SpecConditionDto specCondition(String targetType) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("sensor_1");
        condition.setTargetType(targetType);
        condition.setKey("temperature");
        condition.setRelation(">");
        condition.setValue("40");
        return condition;
    }

    private static ConditionValueInfo valueChosenBy(String frozenVarName) {
        return ConditionValueInfo.builder().frozenVarName(frozenVarName).build();
    }

    private static RuleDto.Condition condition(String device, String attribute, String targetType,
                                               String relation, String value) {
        return RuleDto.Condition.builder().deviceName(device).attribute(attribute).targetType(targetType)
                .relation(relation).value(value).build();
    }

    private static RuleDto rule(RuleDto.Condition... conditions) {
        return RuleDto.builder()
                .conditions(new ArrayList<>(Arrays.asList(conditions)))
                .command(RuleDto.Command.builder().deviceName("lock_1").action("unlock").build())
                .build();
    }
}
