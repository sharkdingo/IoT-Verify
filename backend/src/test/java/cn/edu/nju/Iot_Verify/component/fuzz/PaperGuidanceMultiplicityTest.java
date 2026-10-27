package cn.edu.nju.Iot_Verify.component.fuzz;

import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperInitialValue;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperSeed;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperGuidanceMultiplicityTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void repeatedConjunctsPreservePaperSeedRankingWithoutChangingViolations() {
        var specification = new SpecificationDto();
        specification.setId("repeated-conjuncts");
        specification.setName("Repeated conjunction");
        specification.setTemplateId("3");
        specification.setAConditions(List.of(condition("a"), condition("a"), condition("a"),
                condition("b"), condition("c")));
        specification.setIfConditions(List.of());
        specification.setThenConditions(List.of());
        specification.setDevices(List.of());
        FuzzModel model = FuzzModel.from(snapshot(specification));
        model.validateSpecification(specification);
        var domain = model.paperEventDomain(1);
        var moreOccurrences = model.simulatePaperSeed(seed("yes", "no", "no"), domain, 1, () -> false);
        var fewerOccurrences = model.simulatePaperSeed(seed("no", "yes", "yes"), domain, 1, () -> false);
        assertEquals(List.of("yes", "no", "no"), values(moreOccurrences));
        assertEquals(List.of("no", "yes", "yes"), values(fewerOccurrences));

        var preferred = model.evaluatePaper(specification, moreOccurrences, FuzzEngine.PAPER_SOLVER_LEVELS);
        var other = model.evaluatePaper(specification, fewerOccurrences, FuzzEngine.PAPER_SOLVER_LEVELS);

        assertEquals(-1, preferred.violationStep());
        assertEquals(-1, other.violationStep());
        assertEquals(23.0 / 35.0, preferred.distance(), EPSILON);
        assertEquals(27.0 / 35.0, other.distance(), EPSILON);
        assertTrue(preferred.distance() < other.distance(),
                "three satisfied authored occurrences must outrank two, even when the atoms repeat");
        var violation = model.evaluatePaper(specification,
                model.simulatePaperSeed(seed("yes", "yes", "yes"), domain, 1, () -> false),
                FuzzEngine.PAPER_SOLVER_LEVELS);
        assertEquals(0, violation.violationStep());
        assertEquals(0.0, violation.distance());
    }

    @Test
    void repeatedModelLiteralsShareTheirRuleProducerConstruction() {
        for (int ruleCount : List.of(4, 8)) {
            var specification = new SpecificationDto();
            specification.setId("repeated-producer");
            specification.setName("Repeated producer");
            specification.setTemplateId("3");
            specification.setAConditions(List.of(powerCondition(), powerCondition(),
                    powerCondition(), powerCondition()));
            specification.setIfConditions(List.of());
            specification.setThenConditions(List.of());
            specification.setDevices(List.of());
            List<CountingRule> rules = new ArrayList<>();
            List<PaperInitialValue> initial = new ArrayList<>();
            initial.add(new PaperInitialValue(PaperInitialValue.Type.DEVICE_STATE,
                    "source_1", "state", "off"));
            for (int index = 1; index <= ruleCount; index++) {
                rules.add(producerRule(index));
                initial.add(initial("e" + index, "yes"));
            }
            FuzzModel model = FuzzModel.from(producerSnapshot(specification, rules));
            model.validateSpecification(specification);
            var simulation = model.simulatePaperSeed(new PaperSeed(0L, initial, List.of()),
                    model.paperEventDomain(1), 1, () -> false);
            assertEquals("off", simulation.traceStates().get(0).getDevices().get(0).getState());
            rules.forEach(rule -> rule.conditionReads = 0);

            var evaluation = model.evaluatePaper(specification, simulation, FuzzEngine.PAPER_SOLVER_LEVELS);

            assertEquals(-1, evaluation.violationStep());
            assertEquals(6.0 / 7.0, evaluation.distance(), EPSILON);
            int reads = rules.stream().mapToInt(rule -> rule.conditionReads).sum();
            assertTrue(reads > 0);
            // Power=on and each distinct eN have at most one producer scan across all occurrences.
            assertTrue(reads <= ruleCount * (ruleCount + 1),
                    () -> "repeated producer scans for " + ruleCount + " rules: " + reads);
        }
    }

    private static CountingRule producerRule(int index) {
        List<RuleDto.Condition> conditions = new ArrayList<>();
        for (int occurrence = 0; occurrence < 4; occurrence++) {
            conditions.add(RuleDto.Condition.builder().deviceName("source_1").targetType("mode")
                    .attribute("Power").relation("=").value("on").build());
        }
        conditions.add(RuleDto.Condition.builder().deviceName("source_1").targetType("variable")
                .attribute("e" + index).relation("=").value("yes").build());
        var rule = new CountingRule();
        rule.setId((long) index);
        rule.setConditions(conditions);
        rule.setCommand(RuleDto.Command.builder().deviceName("source_1").action("turnOn").build());
        return rule;
    }

    private static BoardDataConverter.ModelInputSnapshot producerSnapshot(
            SpecificationDto specification, List<CountingRule> rules) {
        var names = rules.stream().map(rule -> "e" + rule.getId()).toList();
        var variables = names.stream().map(name -> DeviceManifest.InternalVariable.builder()
                .name(name).isInside(false).values(List.of("no", "yes"))
                .falsifiableWhenCompromised(false).trust("trusted").privacy("public").build()).toList();
        var environment = names.stream().map(name -> new BoardEnvironmentVariableDto(
                name, "no", "trusted", "public")).toList();
        var device = new DeviceVerificationDto();
        device.setVarName("source_1");
        device.setTemplateName("ProducerSource");
        device.setState("off");
        device.setVariables(List.of());
        device.setPrivacies(List.of());
        var workingStates = List.of("off", "on").stream().map(name -> DeviceManifest.WorkingState.builder()
                .name(name).trust("trusted").privacy("public").dynamics(List.of()).build()).toList();
        var action = DeviceManifest.API.builder().name("turnOn").signal(false).acceptsContent(false)
                .startState("").endState("on").build();
        var manifest = DeviceManifest.builder().name("ProducerSource").modes(List.of("Power"))
                .internalVariables(variables).impactedVariables(List.of()).workingStates(workingStates)
                .initState("off").transitions(List.of()).apis(List.of(action)).contents(List.of()).build();
        return new BoardDataConverter.ModelInputSnapshot(List.of(), List.of(device), environment,
                new ArrayList<>(rules), List.of(specification), Map.of("ProducerSource", manifest));
    }

    private static SpecConditionDto powerCondition() {
        var condition = condition("Power");
        condition.setTargetType("mode");
        condition.setVariableSource(null);
        condition.setValue("on");
        return condition;
    }

    private static class CountingRule extends RuleDto {
        private int conditionReads;

        @Override
        public List<Condition> getConditions() {
            conditionReads++;
            return super.getConditions();
        }
    }

    private static PaperSeed seed(String a, String b, String c) {
        return new PaperSeed(0L, List.of(initial("a", a), initial("b", b), initial("c", c)), List.of());
    }

    private static PaperInitialValue initial(String key, String value) {
        return new PaperInitialValue(PaperInitialValue.Type.ENVIRONMENT_VALUE, "environment", key, value);
    }

    private static List<String> values(FuzzModel.Simulation simulation) {
        return simulation.traceStates().get(0).getEnvVariables().stream().map(value -> value.getValue()).toList();
    }

    private static BoardDataConverter.ModelInputSnapshot snapshot(SpecificationDto specification) {
        List<String> names = List.of("a", "b", "c");
        var variables = names.stream().map(name -> DeviceManifest.InternalVariable.builder()
                .name(name).isInside(false).values(List.of("no", "yes"))
                .falsifiableWhenCompromised(false).trust("trusted").privacy("public").build()).toList();
        var environment = names.stream().map(name -> new BoardEnvironmentVariableDto(
                name, "no", "trusted", "public")).toList();
        var device = new DeviceVerificationDto();
        device.setVarName("source_1");
        device.setTemplateName("MultiplicitySource");
        device.setVariables(List.of());
        device.setPrivacies(List.of());
        var manifest = DeviceManifest.builder().name("MultiplicitySource").modes(List.of())
                .internalVariables(variables).impactedVariables(List.of()).workingStates(List.of())
                .transitions(List.of()).apis(List.of()).contents(List.of()).build();
        return new BoardDataConverter.ModelInputSnapshot(List.of(), List.of(device), environment,
                List.of(), List.of(specification), Map.of("MultiplicitySource", manifest));
    }

    private static SpecConditionDto condition(String key) {
        var condition = new SpecConditionDto();
        condition.setDeviceId("source_1");
        condition.setTargetType("variable");
        condition.setVariableSource("environment");
        condition.setKey(key);
        condition.setRelation("=");
        condition.setValue("yes");
        return condition;
    }
}
