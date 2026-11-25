package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardSemanticValidatorTest {

    @Test
    void rejectsContradictoryStateAndModeConditions() {
        BoardSemanticValidator.BoardContext context = context();

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleCondition("state", "state", "=", "heat;run"),
                        ruleCondition("mode", "MachineState", "=", "idle")
                ), null);

        assertEquals("CONTRADICTORY_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void acceptsCompatiblePartialStateAndModeConditions() {
        BoardSemanticValidator.BoardContext context = context();

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleCondition("state", "state", "=", "heat;_"),
                        ruleCondition("mode", "MachineState", "=", "run")
                ), null);

        assertNull(issue);
    }

    @Test
    void acceptsSpecificationForACombinationNotListedAsAWorkingState() {
        var recommendationContext = twoModeContext();
        var authoringContext = BoardSemanticValidator.context(
                List.of(recommendationContext.nodesById().get("device-1")),
                List.of(recommendationContext.templatesByName().get("heater")));

        assertNull(BoardSemanticValidator.validateSpecConditionGroup(authoringContext, List.of(
                specCondition("mode", "HeatMode", "=", "heat"),
                specCondition("mode", "MachineState", "=", "idle")), "a"));
    }

    @Test
    void rejectsContradictionEvenWhenUnrelatedModesMakeTheFullProductLarge() {
        var context = context();
        var template = context.templatesByName().get("heater");
        var manifest = template.getManifest();
        var modes = IntStream.rangeClosed(1, 13).mapToObj(i -> "Mode" + i).toList();
        var allLow = String.join(";", Collections.nCopies(modes.size(), "lo"));
        var allHigh = String.join(";", Collections.nCopies(modes.size(), "hi"));
        manifest.setModes(modes);
        manifest.setWorkingStates(List.of(state(allLow), state(allHigh)));
        manifest.setInitState(allLow);
        manifest.setApis(List.of());
        var node = context.nodesById().get("device-1");
        node.setState(allLow);
        var authoring = BoardSemanticValidator.context(List.of(node), List.of(template));

        var issue = BoardSemanticValidator.validateSpecConditionGroup(authoring, List.of(
                specCondition("mode", "Mode1", "=", "lo"),
                specCondition("mode", "Mode1", "=", "hi")), "a");

        assertNotNull(issue);
        assertEquals("CONTRADICTORY_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void recommendationKeepsModeValueReachedByPartialApiWrite() {
        var context = twoModeContext();

        assertNull(BoardSemanticValidator.validateSpecConditionGroup(context, List.of(
                specCondition("mode", "HeatMode", "=", "heat")), "a"));
    }

    @Test
    void recommendationCanUseComposedStateAsAnotherCommandsStartState() {
        var context = twoModeContext();
        var manifest = context.templatesByName().get("heater").getManifest();
        manifest.setApis(List.of(manifest.getApis().get(0),
                DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("run").signal(false).startState("heat;idle").endState(";run").build()));
        var command = RuleDto.Command.builder().deviceName("device-1").action("run").build();

        assertNull(BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                ruleCondition("mode", "MachineState", "=", "idle")), command));
    }

    @Test
    void recommendationAllowsSimultaneousAutonomousWritesToDifferentModes() {
        var context = twoModeContext();
        var manifest = context.templatesByName().get("heater").getManifest();
        manifest.setApis(List.of());
        var trigger = DeviceTemplateDto.DeviceManifest.Trigger.builder()
                .attribute("temperature").relation("=").value("0").build();
        manifest.setTransitions(List.of(
                DeviceTemplateDto.DeviceManifest.Transition.builder()
                        .name("warm").startState("eco;idle").endState("heat;").trigger(trigger).build(),
                DeviceTemplateDto.DeviceManifest.Transition.builder()
                        .name("run").startState("eco;idle").endState(";run").trigger(trigger).build()));

        assertNull(BoardSemanticValidator.validateSpecConditionGroup(context, List.of(
                specCondition("state", "state", "=", "heat;run")), "a"));
    }

    @Test
    void rejectsContradictoryNumericConditions() {
        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateSpecConditionGroup(context(), List.of(
                        specCondition("variable", "temperature", ">=", "10"),
                        specCondition("variable", "temperature", "<", "10")
                ), "if");

        assertEquals("CONTRADICTORY_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void acceptsOneKeyAskedFromBothSources() {
        /*
         * "The home is actually below 10 AND this sensor reports at or above 10" is the falsified-reading
         * specification the whole variableSource field exists to express: satisfiable exactly when the
         * device lies. Grouping by (device, key) alone folded the two readings into one group and refused
         * it as contradictory — a false reject of the feature's own headline case.
         */
        SpecConditionDto inTheHome = specCondition("variable", "temperature", "<", "10");
        inTheHome.setVariableSource("environment");
        SpecConditionDto asReported = specCondition("variable", "temperature", ">=", "10");
        asReported.setVariableSource("reported");

        assertNull(BoardSemanticValidator.validateSpecConditionGroup(
                        context(), List.of(inTheHome, asReported), "a"),
                "two different questions about one key are independently satisfiable");
    }

    @Test
    void stillRejectsContradictoryConditionsAskingTheSameQuestion() {
        // The negative half: folding the reading into the group key must not stop the check from catching a
        // genuine contradiction, or it would trade a false reject for a false accept.
        SpecConditionDto low = specCondition("variable", "temperature", "<", "10");
        low.setVariableSource("reported");
        SpecConditionDto high = specCondition("variable", "temperature", ">=", "10");
        high.setVariableSource("reported");

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateSpecConditionGroup(context(), List.of(low, high), "a");

        assertEquals("CONTRADICTORY_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void rejectsContradictoryFiniteDomainConditions() {
        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateSpecConditionGroup(context(), List.of(
                        specCondition("privacy", "MachineState", "=", "private"),
                        specCondition("privacy", "MachineState", "=", "public")
                ), "a");

        assertEquals("CONTRADICTORY_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void acceptsAnEnumMemberExcludedOnlyUnderDifferentCase() {
        var issue = BoardSemanticValidator.validateSpecConditionGroup(booleanLikeEnumContext(), List.of(
                specCondition("variable", "flag", "in", "True"),
                specCondition("variable", "flag", "!=", "true")
        ), "a");

        assertNull(issue);
    }

    @Test
    void rejectsConflictingEnumMembersWithDifferentCase() {
        var issue = BoardSemanticValidator.validateSpecConditionGroup(booleanLikeEnumContext(), List.of(
                specCondition("variable", "flag", "=", "True"),
                specCondition("variable", "flag", "=", "true")
        ), "a");

        assertNotNull(issue);
        assertEquals("CONTRADICTORY_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void differentEnumCaseDoesNotMakeAFixedValueReachable() {
        var issue = BoardSemanticValidator.validateRuleConditionGroup(booleanLikeEnumContext(), List.of(
                ruleCondition("variable", "flag", "=", "true")
        ), null);

        assertNotNull(issue);
        assertEquals("UNREACHABLE_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void apiSignalConjunctionComparesBooleanLiteralsIgnoringCase() {
        var context = context();
        context.templatesByName().get("heater").getManifest().getApis().get(0).setSignal(true);

        var issue = BoardSemanticValidator.validateSpecConditionGroup(context, List.of(
                specCondition("api", "start", "in", "fAlSe, TrUe"),
                specCondition("api", "start", "=", "FALSE")
        ), "a");

        assertNull(issue);
    }

    @Test
    void rejectsCommandWhoseRequiredStartStateCannotMeetConditions() {
        RuleDto.Command command = RuleDto.Command.builder()
                .deviceName("device-1")
                .action("start")
                .build();

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context(), List.of(
                        ruleCondition("mode", "MachineState", "=", "run")
                ), command);

        assertEquals("COMMAND_PRESTATE_INCOMPATIBLE", issue.reasonCode());
    }

    @Test
    void wildcardCommandStartStateAcceptsAnySatisfiableTargetCondition() {
        BoardSemanticValidator.BoardContext context = context();
        context.templatesByName().get("heater").getManifest().getApis().get(0).setStartState("_");
        RuleDto.Command command = RuleDto.Command.builder()
                .deviceName("device-1")
                .action("start")
                .build();

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleCondition("mode", "MachineState", "=", "run")
                ), command);

        assertNull(issue);
    }

    @Test
    void rejectsLegalStateThatIsUnreachableFromCurrentState() {
        BoardSemanticValidator.BoardContext context = context();
        context.nodesById().get("device-1").setState("eco;idle");

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateSpecConditionGroup(context, List.of(
                        specCondition("state", "state", "=", "heat;run")
                ), "a");

        assertEquals("UNREACHABLE_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void rejectsFixedVariableValueThatCannotChangeFromCurrentValue() {
        BoardSemanticValidator.BoardContext context = context();
        context.nodesById().get("device-1").setVariables(List.of(
                new VariableStateDto("temperature", "20", "trusted")));

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleCondition("variable", "temperature", "=", "80")
                ), null);

        assertEquals("UNREACHABLE_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void keepsOpenNumericDomainWhenNaturalChangeCanMoveTheValue() {
        BoardSemanticValidator.BoardContext context = context();
        context.nodesById().get("device-1").setVariables(List.of(
                new VariableStateDto("temperature", "20", "trusted")));
        context.templatesByName().get("heater").getManifest()
                .getInternalVariables().get(0).setNaturalChangeRate("[-1, 1]");

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleCondition("variable", "temperature", "=", "80")
                ), null);

        assertNull(issue);
    }

    @Test
    void treatsZeroRateRangeAsFixed() {
        BoardSemanticValidator.BoardContext context = context();
        context.nodesById().get("device-1").setVariables(List.of(
                new VariableStateDto("temperature", "20", "trusted")));
        context.templatesByName().get("heater").getManifest()
                .getInternalVariables().get(0).setNaturalChangeRate("[0, 0]");

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleCondition("variable", "temperature", "=", "80")
                ), null);

        assertEquals("UNREACHABLE_CONDITION_GROUP", issue.reasonCode());
    }

    @Test
    void rejectsCommandWhoseDeclaredStartStateIsUnreachable() {
        BoardSemanticValidator.BoardContext context = context();
        context.nodesById().get("device-1").setState("eco;idle");
        RuleDto.Command command = RuleDto.Command.builder()
                .deviceName("device-1")
                .action("start")
                .build();

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(), command);

        assertEquals("COMMAND_PRESTATE_UNREACHABLE", issue.reasonCode());
    }

    /**
     * A shared environment variable that no template writes is NOT frozen at its pool value.
     *
     * <p>`SmvMainModuleBuilder.appendEnvTransitions` emits a final `TRUE: {<all declared values>}`
     * branch for every enum environment variable, so the pool value is only `init` and any declared
     * value is reachable on step 1. Narrowing these the way local variables are narrowed silently
     * dropped the platform's flagship requests — "sound the alarm when smoke is detected" while the
     * pool reads `clear` — into `filteredItems` as "legal but unreachable".
     */
    @Test
    void keepsSharedEnvironmentEnumValuesReachableWhenNoTemplateWritesThem() {
        BoardSemanticValidator.BoardContext context = sharedSensorContext("clear");

        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleConditionOn("sensor-1", "variable", "smoke", "=", "detected")
                ), null);

        assertNull(issue, "a declared environment value must stay reachable from any pool value");
    }

    @Test
    void stillRejectsAnUndeclaredSharedEnvironmentValue() {
        BoardSemanticValidator.BoardContext context = sharedSensorContext("clear");

        // Widening reachability must not stop rejecting a value outside the declared domain.
        BoardSemanticValidator.GroupValidationIssue issue =
                BoardSemanticValidator.validateRuleConditionGroup(context, List.of(
                        ruleConditionOn("sensor-1", "variable", "smoke", "=", "smouldering")
                ), null);

        assertNotNull(issue, "domain/reachability outcome for an undeclared value");
    }

    /** A smoke sensor exposing a shared (IsInside=false) enum variable that nothing writes. */
    private static BoardSemanticValidator.BoardContext sharedSensorContext(String poolValue) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor-1");
        node.setTemplateName("Smoke Sensor");
        node.setLabel("Smoke Sensor");
        node.setState("Working");

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Smoke Sensor")
                .modes(List.of())
                .workingStates(List.of(state("Working")))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("smoke")
                        .isInside(false)
                        .falsifiableWhenCompromised(false)
                        .trust("trusted")
                        .privacy("public")
                        .values(List.of("clear", "detected", "tested"))
                        .build()))
                .apis(List.of())
                .build();
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Smoke Sensor");
        template.setManifest(manifest);

        BoardEnvironmentVariableDto environment = new BoardEnvironmentVariableDto();
        environment.setName("smoke");
        environment.setValue(poolValue);
        environment.setTrust("trusted");
        environment.setPrivacy("public");

        return BoardSemanticValidator.recommendationContext(
                List.of(node), List.of(template), List.of(environment));
    }

    private static BoardSemanticValidator.BoardContext booleanLikeEnumContext() {
        var context = context();
        var flag = DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                .name("flag").isInside(true).falsifiableWhenCompromised(false)
                .trust("trusted").privacy("public").values(List.of("True", "true")).build();
        context.templatesByName().get("heater").getManifest().setInternalVariables(List.of(flag));
        context.nodesById().get("device-1").setVariables(List.of(new VariableStateDto("flag", "True", "trusted")));
        return context;
    }

    private static BoardSemanticValidator.BoardContext context() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("device-1");
        node.setTemplateName("Heater");
        node.setLabel("Heater");

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Heater")
                .modes(List.of("HeatMode", "MachineState"))
                .workingStates(List.of(
                        state("eco;idle"),
                        state("eco;run"),
                        state("heat;idle"),
                        state("heat;run")))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(true)
                        .falsifiableWhenCompromised(false)
                        .trust("trusted")
                        .privacy("public")
                        .lowerBound(0)
                        .upperBound(100)
                        .build()))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("start")
                        .signal(false)
                        .startState("heat;idle")
                        .endState("heat;run")
                        .build()))
                .build();
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Heater");
        template.setManifest(manifest);
        return BoardSemanticValidator.recommendationContext(List.of(node), List.of(template), List.of());
    }

    private static BoardSemanticValidator.BoardContext twoModeContext() {
        var context = context();
        var manifest = context.templatesByName().get("heater").getManifest();
        manifest.setInitState("eco;idle");
        manifest.setWorkingStates(List.of(state("eco;idle"), state("heat;run")));
        manifest.setApis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                .name("warm").signal(false).startState("eco;_").endState("heat;").build()));
        return context;
    }

    private static DeviceTemplateDto.DeviceManifest.WorkingState state(String name) {
        return DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                .name(name)
                .trust("trusted")
                .privacy("public")
                .dynamics(List.of())
                .build();
    }

    private static RuleDto.Condition ruleConditionOn(String deviceId,
                                                     String targetType,
                                                     String attribute,
                                                     String relation,
                                                     String value) {
        return RuleDto.Condition.builder()
                .deviceName(deviceId)
                .targetType(targetType)
                .attribute(attribute)
                .relation(relation)
                .value(value)
                .build();
    }

    private static RuleDto.Condition ruleCondition(String targetType,
                                                   String attribute,
                                                   String relation,
                                                   String value) {
        return RuleDto.Condition.builder()
                .deviceName("device-1")
                .targetType(targetType)
                .attribute(attribute)
                .relation(relation)
                .value(value)
                .build();
    }

    private static SpecConditionDto specCondition(String targetType,
                                                  String key,
                                                  String relation,
                                                  String value) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("device-1");
        condition.setTargetType(targetType);
        condition.setKey(key);
        condition.setPropertyScope("privacy".equals(targetType) ? "state" : null);
        condition.setRelation(relation);
        condition.setValue(value);
        return condition;
    }

    /**
     * A person and an assistant expressing the same intention must get the same answer.
     *
     * <p>An affect-only shared declaration is not a condition source: the generator emits no read
     * mirror for it. RuleBuilderDialog hides it and NusmvRequestValidator refuses it, so this
     * assistant-facing validator must refuse it too. Before this, an AI-authored rule could reference a
     * value the device never observes while the equivalent click-through was impossible — the same
     * intention producing two different models depending on who expressed it.
     */
    @Test
    void anAssistantCannotUseAnAffectOnlySharedValueAsAConditionSource() {
        DeviceTemplateDto.DeviceManifest.InternalVariable affectOnly =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("illuminance").isInside(false).reads(false)
                        .falsifiableWhenCompromised(false)
                        .trust("trusted").privacy("public")
                        .lowerBound(0).upperBound(100).naturalChangeRate("[-1, 1]")
                        .build();
        DeviceTemplateDto.DeviceManifest.InternalVariable readable =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("lux").isInside(false).reads(true)
                        .falsifiableWhenCompromised(true)
                        .trust("untrusted").privacy("public")
                        .lowerBound(0).upperBound(100).naturalChangeRate("[-1, 1]")
                        .build();
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Light");
        template.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("Light")
                .modes(List.of("Power"))
                .initState("on")
                .internalVariables(List.of(affectOnly, readable))
                .impactedVariables(List.of("illuminance"))
                .workingStates(List.of(DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                        .name("on").trust("trusted").privacy("public").build()))
                .build());

        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("light_1");
        node.setTemplateName("Light");
        node.setState("on");

        BoardSemanticValidator.BoardContext context = BoardSemanticValidator.context(
                List.of(node), List.of(template), List.of());

        RuleDto.Condition onAffectOnly = new RuleDto.Condition();
        onAffectOnly.setDeviceName("light_1");
        onAffectOnly.setTargetType("variable");
        onAffectOnly.setAttribute("illuminance");
        onAffectOnly.setRelation(">");
        onAffectOnly.setValue("50");
        String rejection = BoardSemanticValidator.validateRuleCondition(context, onAffectOnly, 0);
        assertNotNull(rejection, "an affect-only value must not be a condition source");
        assertTrue(rejection.contains("illuminance"), rejection);

        RuleDto.Condition onReadable = new RuleDto.Condition();
        onReadable.setDeviceName("light_1");
        onReadable.setTargetType("variable");
        onReadable.setAttribute("lux");
        onReadable.setRelation(">");
        onReadable.setValue("50");
        assertNull(BoardSemanticValidator.validateRuleCondition(context, onReadable, 0),
                "a readable shared value stays a valid condition source");

        // The narrowing must not spread to a trust label. The device module declares trust_<name> for
        // every declared variable, so a property reference to an affect-only value is something the
        // generated model really permits -- and a check stricter than the model refuses the assistant a
        // specification the UI offers and NuSMV would have decided.
        SpecConditionDto trustOfAffectOnly = new SpecConditionDto();
        trustOfAffectOnly.setDeviceId("light_1");
        trustOfAffectOnly.setTargetType("trust");
        trustOfAffectOnly.setPropertyScope("variable");
        trustOfAffectOnly.setKey("illuminance");
        trustOfAffectOnly.setRelation("=");
        trustOfAffectOnly.setValue("untrusted");
        assertNull(BoardSemanticValidator.validateSpecCondition(context, trustOfAffectOnly, "A", 0),
                "a trust label on an affect-only value is permitted: asking about a label is not a read");

        // A specification *condition* compares the value itself, so there the narrowing does apply.
        SpecConditionDto valueOfAffectOnly = new SpecConditionDto();
        valueOfAffectOnly.setDeviceId("light_1");
        valueOfAffectOnly.setTargetType("variable");
        valueOfAffectOnly.setKey("illuminance");
        valueOfAffectOnly.setRelation(">");
        valueOfAffectOnly.setValue("50");
        assertNotNull(BoardSemanticValidator.validateSpecCondition(context, valueOfAffectOnly, "A", 0),
                "an affect-only value must not be a specification condition source either");
    }
}
