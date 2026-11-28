package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.dto.fix.ConditionAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FixStrategyApplierTest {

    @Test
    void parameterAdjustmentRegeneratesOnlyTheTouchedRulePreview() {
        RuleDto adjusted = rule("Author wording for the threshold", "sensor-one", "30");
        RuleDto untouched = rule("Keep this custom explanation exactly", "sensor-two", "40");
        FixSuggestionDto suggestion = FixSuggestionDto.builder()
                .parameterAdjustments(List.of(ParameterAdjustment.builder()
                        .ruleIndex(0)
                        .conditionIndex(0)
                        .attribute("temperature")
                        .relation(">")
                        .newValue("25")
                        .build()))
                .build();

        List<RuleDto> result = FixStrategyApplier.apply(
                "parameter", suggestion, List.of(adjusted, untouched), Map.of(),
                Map.of("sensor-one", "Living room sensor", "actuator", "Heater"));

        assertEquals("25", result.get(0).getConditions().get(0).getValue());
        assertEquals("IF Living room sensor.temperature > 25 THEN Heater.turn_on",
                result.get(0).getRuleString());
        assertEquals("Keep this custom explanation exactly", result.get(1).getRuleString());
        assertEquals("30", adjusted.getConditions().get(0).getValue());
    }

    @Test
    void conditionAdjustmentIgnoresKeepEntriesWhenRefreshingRulePreviews() {
        RuleDto untouched = rule("Keep this authored preview", "sensor-one", "30");
        RuleDto adjusted = rule("Original second preview", "sensor-two", "40");
        FixSuggestionDto suggestion = FixSuggestionDto.builder()
                .conditionAdjustments(List.of(
                        ConditionAdjustment.builder()
                                .ruleIndex(0)
                                .conditionIndex(0)
                                .action("keep")
                                .build(),
                        ConditionAdjustment.builder()
                                .ruleIndex(1)
                                .action("add")
                                .deviceName("door-smv")
                                .attribute("contact")
                                .targetType("variable")
                                .relation("=")
                                .value("open")
                                .build()))
                .build();

        List<RuleDto> result = FixStrategyApplier.apply(
                "condition", suggestion, List.of(untouched, adjusted),
                Map.of("door-smv", "door-node"),
                Map.of("sensor-two", "Hall sensor", "door-node", "Front door",
                        "actuator", "Heater"));

        assertEquals("Keep this authored preview", result.get(0).getRuleString());
        assertNotEquals("Original second preview", result.get(1).getRuleString());
        assertEquals(2, result.get(1).getConditions().size());
        assertEquals("door-node", result.get(1).getConditions().get(1).getDeviceName());
        assertEquals("Keep this authored preview", untouched.getRuleString());
        assertEquals(1, adjusted.getConditions().size());
    }

    private RuleDto rule(String preview, String sourceDevice, String threshold) {
        return RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName(sourceDevice)
                        .attribute("temperature")
                        .targetType("variable")
                        .relation(">")
                        .value(threshold)
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("actuator")
                        .action("turn_on")
                        .build())
                .ruleString(preview)
                .build();
    }
}
