package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Condition cardinality must be part of a rule's semantic identity.
 *
 * <p>`exactlyMatches` also gates the stale-write check behind "delete this rule if it has not
 * changed". Canonicalizing conditions into a set made a repeated condition invisible, so a rule
 * that had genuinely been edited from `[C, C]` to `[C]` compared equal and the delete went through
 * against a rule the user had never reviewed.
 */
class RuleSemanticSignatureCardinalityTest {

    private static RuleDto.Condition condition(String device, String value) {
        RuleDto.Condition condition = new RuleDto.Condition();
        condition.setDeviceName(device);
        condition.setTargetType("state");
        condition.setAttribute("state");
        condition.setRelation("=");
        condition.setValue(value);
        return condition;
    }

    private static RuleDto rule(List<RuleDto.Condition> conditions) {
        RuleDto.Command command = new RuleDto.Command();
        command.setDeviceName("light-1");
        command.setAction("off");

        RuleDto rule = new RuleDto();
        rule.setConditions(conditions);
        rule.setCommand(command);
        return rule;
    }

    @Test
    void aRepeatedConditionIsNotTheSameRuleAsASingleOne() {
        RuleDto twice = rule(List.of(condition("light-1", "on"), condition("light-1", "on")));
        RuleDto once = rule(List.of(condition("light-1", "on")));

        assertFalse(RuleSemanticSignature.exactlyMatches(twice, once),
                "dropping a duplicate condition changes the rule, so a captured snapshot is stale");
    }

    @Test
    void reorderingConditionsIsStillTheSameRule() {
        RuleDto forward = rule(List.of(condition("light-1", "on"), condition("light-2", "off")));
        RuleDto reversed = rule(List.of(condition("light-2", "off"), condition("light-1", "on")));

        // Order carries no meaning in a conjunction, so this must stay order-insensitive.
        assertTrue(RuleSemanticSignature.exactlyMatches(forward, reversed));
    }

    @Test
    void identicalRulesStillMatch() {
        assertTrue(RuleSemanticSignature.exactlyMatches(
                rule(List.of(condition("light-1", "on"))),
                rule(List.of(condition("light-1", "on")))));
    }

    @Test
    void differentConditionsStillDoNotMatch() {
        assertFalse(RuleSemanticSignature.exactlyMatches(
                rule(List.of(condition("light-1", "on"))),
                rule(List.of(condition("light-1", "off")))));
    }

    private static SpecConditionDto specCondition(String device, String value) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setSide("a");
        condition.setDeviceId(device);
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue(value);
        return condition;
    }

    private static SpecificationDto spec(List<SpecConditionDto> aConditions) {
        SpecificationDto specification = new SpecificationDto();
        specification.setName("Safety requirement");
        specification.setTemplateId("1");
        specification.setAConditions(aConditions);
        return specification;
    }

    @Test
    void aRepeatedSpecificationConditionIsNotTheSameSpecification() {
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                spec(List.of(specCondition("light-1", "on"), specCondition("light-1", "on"))),
                spec(List.of(specCondition("light-1", "on")))),
                "dropping a duplicate condition changes the specification");
    }

    @Test
    void reorderingSpecificationConditionsIsStillTheSameSpecification() {
        assertTrue(SpecificationSemanticSignature.exactlyMatches(
                spec(List.of(specCondition("light-1", "on"), specCondition("light-2", "off"))),
                spec(List.of(specCondition("light-2", "off"), specCondition("light-1", "on")))));
    }

    @Test
    void differentSpecificationConditionsStillDoNotMatch() {
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                spec(List.of(specCondition("light-1", "on"))),
                spec(List.of(specCondition("light-1", "off")))));
    }
}
