package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.configure.JwtConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyApplier;
import cn.edu.nju.Iot_Verify.dto.fix.ConditionAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.PreexistingViolationDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixSuggestionTokenServiceTest {

    private FixSuggestionTokenService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        JwtConfig config = new JwtConfig();
        config.setSecret("test-secret-that-is-long-enough-for-hmac-signatures");
        objectMapper = new ObjectMapper();
        service = new FixSuggestionTokenService(objectMapper, config);
    }

    @Test
    void verify_restoresAllSignedRemovalIndicesAfterPublicJsonRoundTrip() throws Exception {
        FixSuggestionDto serverSuggestion = FixSuggestionDto.builder()
                .strategy("remove")
                .description("Remove conflicting automation")
                .removedRuleIndices(List.of(2, 4))
                .removedRuleDescriptions(List.of("Rule 3", "Rule 5"))
                .build();
        Map<String, PreferredRange> ranges = Map.of();

        String token = service.issue(7L, 11L, serverSuggestion, ranges);
        FixSuggestionDto clientSuggestion = objectMapper.readValue(
                objectMapper.writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        assertNull(clientSuggestion.getRemovedRuleIndices());

        FixSuggestionDto trusted = service.verify(
                7L, 11L, "remove", clientSuggestion, token, ranges);

        assertEquals(List.of(2, 4), trusted.getRemovedRuleIndices());
        assertEquals(List.of("Rule 3", "Rule 5"), trusted.getRemovedRuleDescriptions());

        List<RuleDto> rules = IntStream.range(0, 6)
                .mapToObj(index -> RuleDto.builder().ruleString("Rule " + index).build())
                .toList();
        List<RuleDto> applied = FixStrategyApplier.apply("remove", trusted, rules, Map.of());

        assertEquals(List.of("Rule 0", "Rule 1", "Rule 3", "Rule 5"),
                applied.stream().map(RuleDto::getRuleString).toList());
    }

    @Test
    void verify_rejectsVisibleSuggestionOrRangeTampering() {
        FixSuggestionDto suggestion = FixSuggestionDto.builder()
                .strategy("parameter")
                .description("Adjust threshold")
                .build();
        Map<String, PreferredRange> ranges = Map.of(
                "rule:1:condition:0", new PreferredRange(10, 20));
        String token = service.issue(7L, 11L, suggestion, ranges);

        suggestion.setDescription("Apply a different change");
        assertThrows(BadRequestException.class, () -> service.verify(
                7L, 11L, "parameter", suggestion, token, ranges));

        suggestion.setDescription("Adjust threshold");
        Map<String, PreferredRange> changedRanges = Map.of(
                "rule:1:condition:0", new PreferredRange(0, 100));
        assertThrows(BadRequestException.class, () -> service.verify(
                7L, 11L, "parameter", suggestion, token, changedRanges));
    }

    // The pre-existing list is what tells the user this suggestion leaves other specifications violated.
    // A client that dropped or edited it would present the repair as cleaner than it was verified to be.
    @Test
    void verify_rejectsDroppedOrEditedPreexistingViolations() throws Exception {
        FixSuggestionDto suggestion = FixSuggestionDto.builder()
                .strategy("remove")
                .description("Remove conflicting automation")
                .removedRuleIndices(List.of(1))
                .removedRuleDescriptions(List.of("Rule 2"))
                .preexistingViolations(List.of(PreexistingViolationDto.builder()
                        .specId("spec_other")
                        .templateId("1")
                        .formulaPreview("AG !(window.open & heater.on)")
                        .build()))
                .build();
        String token = service.issue(7L, 11L, suggestion, Map.of());
        byte[] wire = objectMapper.writeValueAsBytes(suggestion);

        FixSuggestionDto dropped = objectMapper.readValue(wire, FixSuggestionDto.class);
        dropped.setPreexistingViolations(List.of());
        assertThrows(BadRequestException.class, () -> service.verify(
                7L, 11L, "remove", dropped, token, Map.of()));

        FixSuggestionDto edited = objectMapper.readValue(wire, FixSuggestionDto.class);
        edited.getPreexistingViolations().get(0).setFormulaPreview("AG TRUE");
        assertThrows(BadRequestException.class, () -> service.verify(
                7L, 11L, "remove", edited, token, Map.of()));

        FixSuggestionDto unchanged = objectMapper.readValue(wire, FixSuggestionDto.class);
        assertEquals(suggestion.getPreexistingViolations(), service.verify(
                7L, 11L, "remove", unchanged, token, Map.of()).getPreexistingViolations());
    }

    @Test
    void verify_restoresAllSignedParameterLocatorsAfterPublicJsonRoundTrip() throws Exception {
        ParameterAdjustment firstAdjustment = ParameterAdjustment.builder()
                .targetId("param_abcdefghijklmnopqrstuvwx")
                .ruleIndex(4)
                .conditionIndex(2)
                .attribute("temperature")
                .relation(">")
                .originalValue("30")
                .newValue("25")
                .lowerBound(0)
                .upperBound(40)
                .description("Adjust temperature threshold")
                .build();
        ParameterAdjustment secondAdjustment = ParameterAdjustment.builder()
                .targetId("param_zyxwvutsrqponmlkjihgfedc")
                .ruleIndex(1)
                .conditionIndex(0)
                .attribute("temperature")
                .relation(">")
                .originalValue("30")
                .newValue("28")
                .lowerBound(0)
                .upperBound(40)
                .description("Adjust backup temperature threshold")
                .build();
        FixSuggestionDto serverSuggestion = FixSuggestionDto.builder()
                .strategy("parameter")
                .description("Adjust coordinated thresholds")
                .parameterAdjustments(List.of(firstAdjustment, secondAdjustment))
                .build();
        String token = service.issue(7L, 11L, serverSuggestion, Map.of());

        FixSuggestionDto clientSuggestion = objectMapper.readValue(
                objectMapper.writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        clientSuggestion.getParameterAdjustments().forEach(adjustment -> {
            assertEquals(0, adjustment.getRuleIndex());
            assertEquals(0, adjustment.getConditionIndex());
        });

        FixSuggestionDto trusted = service.verify(
                7L, 11L, "parameter", clientSuggestion, token, Map.of());

        assertEquals(4, trusted.getParameterAdjustments().get(0).getRuleIndex());
        assertEquals(2, trusted.getParameterAdjustments().get(0).getConditionIndex());
        assertEquals(1, trusted.getParameterAdjustments().get(1).getRuleIndex());
        assertEquals(0, trusted.getParameterAdjustments().get(1).getConditionIndex());

        List<RuleDto> rules = IntStream.range(0, 5)
                .mapToObj(ruleIndex -> RuleDto.builder()
                        .conditions(IntStream.range(0, 3)
                                .mapToObj(conditionIndex -> RuleDto.Condition.builder()
                                        .deviceName("sensor_1")
                                        .targetType("variable")
                                        .attribute("temperature")
                                        .relation(">")
                                        .value("30")
                                        .build())
                                .toList())
                        .command(RuleDto.Command.builder()
                                .deviceName("heater_1").action("on").build())
                        .build())
                .toList();
        List<RuleDto> applied = FixStrategyApplier.apply(
                "parameter", trusted, rules, Map.of());

        assertEquals("30", applied.get(0).getConditions().get(0).getValue());
        assertEquals("25", applied.get(4).getConditions().get(2).getValue());
        assertEquals("28", applied.get(1).getConditions().get(0).getValue());
    }

    @Test
    void verify_restoresAllSignedConditionLocatorsAndDevicesAfterPublicJsonRoundTrip() throws Exception {
        ConditionAdjustment firstAdjustment = ConditionAdjustment.builder()
                .ruleIndex(3)
                .conditionIndex(1)
                .action("add")
                .attribute("presence")
                .targetType("variable")
                .description("Add presence guard")
                .ruleDescription("Rule 4")
                .deviceLabel("Hall sensor")
                .deviceName("sensor_2")
                .relation("=")
                .value("TRUE")
                .build();
        ConditionAdjustment secondAdjustment = ConditionAdjustment.builder()
                .ruleIndex(1)
                .conditionIndex(1)
                .action("add")
                .attribute("humidity")
                .targetType("variable")
                .description("Add humidity guard")
                .ruleDescription("Rule 2")
                .deviceLabel("Humidity sensor")
                .deviceName("sensor_3")
                .relation("<=")
                .value("70")
                .build();
        FixSuggestionDto serverSuggestion = FixSuggestionDto.builder()
                .strategy("condition")
                .description("Add coordinated trigger conditions")
                .conditionAdjustments(List.of(firstAdjustment, secondAdjustment))
                .build();
        String token = service.issue(7L, 11L, serverSuggestion, Map.of());

        FixSuggestionDto clientSuggestion = objectMapper.readValue(
                objectMapper.writeValueAsBytes(serverSuggestion), FixSuggestionDto.class);
        clientSuggestion.getConditionAdjustments().forEach(adjustment -> {
            assertEquals(0, adjustment.getRuleIndex());
            assertEquals(0, adjustment.getConditionIndex());
            assertNull(adjustment.getDeviceName());
        });

        FixSuggestionDto trusted = service.verify(
                7L, 11L, "condition", clientSuggestion, token, Map.of());

        ConditionAdjustment firstRestored = trusted.getConditionAdjustments().get(0);
        assertEquals(3, firstRestored.getRuleIndex());
        assertEquals(1, firstRestored.getConditionIndex());
        assertEquals("sensor_2", firstRestored.getDeviceName());
        ConditionAdjustment secondRestored = trusted.getConditionAdjustments().get(1);
        assertEquals(1, secondRestored.getRuleIndex());
        assertEquals(1, secondRestored.getConditionIndex());
        assertEquals("sensor_3", secondRestored.getDeviceName());

        List<RuleDto> rules = IntStream.range(0, 4)
                .mapToObj(ruleIndex -> RuleDto.builder()
                        .conditions(List.of(RuleDto.Condition.builder()
                                .deviceName("base_sensor")
                                .targetType("variable")
                                .attribute("temperature")
                                .relation(">")
                                .value("20")
                                .build()))
                        .command(RuleDto.Command.builder()
                                .deviceName("heater_1").action("on").build())
                        .build())
                .toList();
        List<RuleDto> applied = FixStrategyApplier.apply(
                "condition", trusted, rules, Map.of(),
                Map.of("sensor_2", "node_sensor_2", "sensor_3", "node_sensor_3"));

        assertEquals(2, applied.get(1).getConditions().size());
        assertEquals("node_sensor_3", applied.get(1).getConditions().get(1).getDeviceName());
        assertEquals("70", applied.get(1).getConditions().get(1).getValue());
        assertEquals(2, applied.get(3).getConditions().size());
        assertEquals("node_sensor_2", applied.get(3).getConditions().get(1).getDeviceName());
        assertEquals("TRUE", applied.get(3).getConditions().get(1).getValue());
    }
}
