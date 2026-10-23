package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BoardItemVersionTest {
    @Test
    void ruleVersionBindsOwnerIdentityLifetimeLabelAndEveryEditableField() {
        String version = BoardItemVersion.rule(1L, rule());
        assertTrue(version.matches(BoardItemVersion.PATTERN));
        assertEquals(version, BoardItemVersion.rule(1L, rule()));
        assertNotEquals(version, BoardItemVersion.rule(2L, rule()));
        List<Consumer<RuleDto>> edits = List.of(
                rule -> rule.setId(2L),
                rule -> rule.setCreatedAt(rule.getCreatedAt().plusSeconds(1)),
                rule -> rule.setRuleString("New name"),
                rule -> rule.getConditions().get(0).setDeviceName("other"),
                rule -> rule.getConditions().get(0).setAttribute("humidity"),
                rule -> rule.getConditions().get(0).setTargetType("mode"),
                rule -> rule.getConditions().get(0).setRelation("<"),
                rule -> rule.getConditions().get(0).setValue("31"),
                rule -> rule.getCommand().setDeviceName("other"),
                rule -> rule.getCommand().setAction("close"),
                rule -> rule.getCommand().setContentDevice("other"),
                rule -> rule.getCommand().setContent("private"),
                rule -> rule.setConditions(List.of(rule.getConditions().get(0), rule.getConditions().get(0))));
        for (int index = 0; index < edits.size(); index++) {
            RuleDto changed = rule();
            edits.get(index).accept(changed);
            assertNotEquals(version, BoardItemVersion.rule(1L, changed), "edit " + index);
        }
    }

    @Test
    void structuredHashInputCannotConfuseAdjacentRuleFields() {
        RuleDto left = rule();
        left.getCommand().setContentDevice("a|b");
        left.getCommand().setContent("c");
        RuleDto right = rule();
        right.getCommand().setContentDevice("a");
        right.getCommand().setContent("b|c");
        assertNotEquals(BoardItemVersion.rule(1L, left), BoardItemVersion.rule(1L, right));
    }

    @Test
    void specificationVersionUsesTheSameAuthoredIdentityAsTheFinalCompareAndSet() {
        SpecificationDto baseline = specification();
        String version = BoardItemVersion.specification(1L, baseline);
        assertNotEquals(version, BoardItemVersion.specification(2L, baseline));
        List<Consumer<SpecificationDto>> edits = List.of(
                spec -> spec.setId("another-spec"),
                spec -> spec.setTemplateId("3"),
                spec -> spec.getAConditions().get(0).setVariableSource("reported"),
                spec -> spec.getAConditions().get(0).setValue("31"),
                spec -> spec.setAConditions(List.of(spec.getAConditions().get(0), spec.getAConditions().get(0))),
                spec -> { spec.setIfConditions(spec.getAConditions()); spec.setAConditions(List.of()); });
        for (int index = 0; index < edits.size(); index++) {
            SpecificationDto changed = specification();
            edits.get(index).accept(changed);
            assertNotEquals(version, BoardItemVersion.specification(1L, changed), "edit " + index);
        }
        SpecificationDto displayed = specification();
        displayed.setTemplateLabel("Translated title");
        displayed.setFormula("cached preview");
        displayed.getAConditions().get(0).setDeviceLabel("Renamed device");
        displayed.getAConditions().get(0).setId("another-render-key");
        displayed.getAConditions().get(0).setRelation("GT");
        assertEquals(version, BoardItemVersion.specification(1L, displayed));
    }

    @Test
    void specificationConditionOrderDoesNotInventAConflictButDuplicateCountDoes() {
        SpecificationDto first = specification();
        SpecConditionDto second = specification().getAConditions().get(0);
        second.setValue("40");
        first.setAConditions(List.of(first.getAConditions().get(0), second));
        SpecificationDto reordered = specification();
        reordered.setAConditions(List.of(second, reordered.getAConditions().get(0)));
        assertEquals(BoardItemVersion.specification(1L, first), BoardItemVersion.specification(1L, reordered));
        reordered.setAConditions(List.of(second, second, reordered.getAConditions().get(1)));
        assertNotEquals(BoardItemVersion.specification(1L, first), BoardItemVersion.specification(1L, reordered));
    }

    private RuleDto rule() {
        return RuleDto.builder().id(1L).userId(1L).createdAt(LocalDateTime.of(2026, 10, 23, 1, 2, 3))
                .ruleString("Cooling threshold")
                .conditions(List.of(RuleDto.Condition.builder().deviceName("sensor").targetType("variable")
                        .attribute("temperature").relation(">").value("30").build()))
                .command(RuleDto.Command.builder().deviceName("fan").action("open")
                        .contentDevice("sensor").content("reading").build()).build();
    }

    private SpecificationDto specification() {
        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec-1");
        spec.setTemplateId("1");
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("sensor");
        condition.setTargetType("variable");
        condition.setKey("temperature");
        condition.setVariableSource("environment");
        condition.setRelation(">");
        condition.setValue("30");
        spec.setAConditions(List.of(condition));
        return spec;
    }
}
