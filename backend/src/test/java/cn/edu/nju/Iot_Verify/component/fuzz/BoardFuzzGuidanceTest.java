package cn.edu.nju.Iot_Verify.component.fuzz;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardFuzzGuidanceTest {

    @Test
    void responseGuidanceDistinguishesPartialAntecedentProgress() throws Exception {
        var snapshot = conjunctSnapshot();
        SpecificationDto safety = snapshot.specifications().get(0);
        FuzzModel model = FuzzModel.from(snapshot);
        model.validateSpecification(safety);
        // The first four choices set a/b/c/answer at state 1; the next four set state 2.
        var farScore = responseScore(model, safety, 0, 0, 0, 1, 0, 0, 0, 1);
        var partialScore = responseScore(model, safety, 1, 0, 0, 1, 0, 0, 0, 1);
        var readyScore = responseScore(model, safety, 1, 1, 1, 1, 0, 0, 0, 1);
        var violationScore = responseScore(model, safety, 1, 1, 1, 1, 0, 0, 0, 0);

        assertEquals(-1, farScore.violationStep());
        assertEquals(-1, partialScore.violationStep());
        assertEquals(-1, readyScore.violationStep());
        assertTrue(partialScore.distance() < farScore.distance(),
                () -> "satisfying an antecedent conjunct must improve guidance: "
                        + farScore.distance() + " -> " + partialScore.distance());
        assertTrue(readyScore.distance() < partialScore.distance(),
                () -> "satisfying the full antecedent must improve guidance: "
                        + partialScore.distance() + " -> " + readyScore.distance());
        assertTrue(readyScore.distance() > 0.0,
                "a satisfied antecedent alone does not establish a violated response");
        assertEquals(2, violationScore.violationStep());
        assertEquals(0.0, violationScore.distance());
    }

    @Test
    void forbiddenConjunctionGuidanceDistinguishesPartialConditionProgress() throws Exception {
        var snapshot = conjunctSnapshot();
        var forbidden = new SpecificationDto();
        forbidden.setId("forbidden-entry-combination");
        forbidden.setName("Forbidden entry combination");
        forbidden.setTemplateId("3");
        forbidden.setAConditions(snapshot.specifications().get(0).getIfConditions());
        FuzzModel model = FuzzModel.from(snapshot);
        model.validateSpecification(forbidden);
        var farScore = model.evaluate(forbidden, model.simulate(new long[]{0, 0, 0, 1}, 2));
        var partialScore = model.evaluate(forbidden, model.simulate(new long[]{1, 0, 0, 1}, 2));
        var violationScore = model.evaluate(forbidden, model.simulate(new long[]{1, 1, 1, 1}, 2));

        assertEquals(-1, farScore.violationStep());
        assertEquals(-1, partialScore.violationStep());
        assertTrue(partialScore.distance() < farScore.distance(),
                () -> "one matching conjunct must be closer than none: "
                        + farScore.distance() + " -> " + partialScore.distance());
        assertTrue(partialScore.distance() > 0.0,
                "partial satisfaction must not be reported as a violation");
        assertEquals(1, violationScore.violationStep());
        assertEquals(0.0, violationScore.distance());
    }

    private static FuzzModel.SpecEvaluation responseScore(
            FuzzModel model, SpecificationDto specification, long... genes) {
        return model.evaluate(specification, model.simulate(genes, 3));
    }

    private static BoardDataConverter.ModelInputSnapshot conjunctSnapshot() {
        List<String> names = List.of("a", "b", "c", "answer");
        var domains = names.stream().map(name -> DeviceManifest.InternalVariable.builder()
                .name(name).isInside(false).values(List.of("no", "yes"))
                .falsifiableWhenCompromised(false).trust("trusted").privacy("public").build()).toList();
        var environment = names.stream().map(name -> new BoardEnvironmentVariableDto(
                name, "answer".equals(name) ? "yes" : "no", "trusted", "public")).toList();
        var device = new DeviceVerificationDto();
        device.setVarName("source_1");
        device.setTemplateName("ConjunctSource");
        device.setVariables(List.of());
        device.setPrivacies(List.of());
        var manifest = DeviceManifest.builder().name("ConjunctSource").modes(List.of())
                .internalVariables(domains).impactedVariables(List.of()).workingStates(List.of())
                .transitions(List.of()).apis(List.of()).contents(List.of()).build();
        var specification = new SpecificationDto();
        specification.setId("response");
        specification.setName("Response guidance");
        specification.setTemplateId("4");
        specification.setAConditions(List.of());
        specification.setIfConditions(List.of(enumCondition("a"), enumCondition("b"), enumCondition("c")));
        specification.setThenConditions(List.of(enumCondition("answer")));
        specification.setDevices(List.of());
        return new BoardDataConverter.ModelInputSnapshot(List.of(), List.of(device), environment,
                List.of(), List.of(specification), Map.of("ConjunctSource", manifest));
    }

    private static SpecConditionDto enumCondition(String key) {
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
