package cn.edu.nju.Iot_Verify.dto.fix;

import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixDtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /*
     * The source-model fields keep their JSON names after being changed from boxed to primitive.
     *
     * `Boolean sourceModelComplete` → `boolean` makes Lombok emit `isSourceModelComplete()` instead of
     * `getSourceModelComplete()`. Jackson derives the same property name from either, but the frontend's
     * `validateSourceModel` *requires* `sourceModelComplete`, `sourceDisabledRuleCount` and
     * `sourceSkippedSpecCount` and rejects the whole response if one is missing — so a rename here would surface
     * as "the fix result is malformed" rather than as a missing field. Asserted rather than assumed.
     */
    @Test
    void sourceModelFieldsKeepTheirWireNamesAsPrimitives() throws Exception {
        FixResultDto result = FixResultDto.builder()
                .sourceModelComplete(true)
                .sourceDisabledRuleCount(2)
                .sourceSkippedSpecCount(3)
                .build();

        String json = objectMapper.writeValueAsString(result);

        org.assertj.core.api.Assertions.assertThat(json)
                .contains("\"sourceModelComplete\":true")
                .contains("\"sourceDisabledRuleCount\":2")
                .contains("\"sourceSkippedSpecCount\":3");

        // The teeth: an all-defaults builder must render false/0, not null. `doesNotContain("isSource…")` was
        // unfalsifiable on its own - Jackson never emits a getter name as a key under either typing - so it could
        // not detect a revert to `Boolean`/`Integer`, which is exactly what this test exists to pin.
        org.assertj.core.api.Assertions.assertThat(objectMapper.writeValueAsString(FixResultDto.builder().build()))
                .contains("\"sourceModelComplete\":false")
                .contains("\"sourceDisabledRuleCount\":0")
                .contains("\"sourceSkippedSpecCount\":0");
    }

    @Test
    void externalFixDtosHideInternalRuleAndConditionLocators() throws Exception {
        ParameterAdjustment parameter = ParameterAdjustment.builder()
                .targetId("param_abcdefghijklmnopqrstuvwx")
                .ruleIndex(3)
                .conditionIndex(4)
                .attribute("temperature")
                .relation(">")
                .originalValue("30")
                .newValue("25")
                .lowerBound(0)
                .upperBound(50)
                .description("Adjust the kitchen temperature threshold")
                .modelTokenSource(ModelTokenSource.BUNDLED)
                .build();
        ParameterTarget parameterTarget = ParameterTarget.builder()
                .targetId("param_zyxwvutsrqponmlkjihgfedc")
                .ruleIndex(11)
                .conditionIndex(12)
                .attribute("workingState")
                .relation("=")
                .originalValue("off")
                .lowerBound(0)
                .upperBound(1)
                .description("Frozen parameter target")
                .modelTokenSource(ModelTokenSource.CUSTOM)
                .build();
        ConditionAdjustment condition = ConditionAdjustment.builder()
                .ruleIndex(5)
                .conditionIndex(6)
                .action("remove")
                .attribute("motion")
                .deviceName("kitchen_motion_1")
                .deviceLabel("Kitchen motion sensor")
                .description("Remove the conflicting motion condition")
                .build();
        FaultRuleDto faultRule = FaultRuleDto.builder()
                .ruleIndex(7)
                .ruleId(42L)
                .conflictWithRuleIndex(8)
                .targetDeviceId("kitchen_light_17")
                .targetActionId("turnOn")
                .targetDeviceLabel("Kitchen light")
                .targetActionLabel("Turn on")
                .transitionNumber(1)
                .ruleString("IF kitchen.motion = active THEN kitchen.light.turnOn")
                .build();
        FixSuggestionDto suggestion = FixSuggestionDto.builder()
                .strategy("remove")
                .parameterAdjustments(List.of(parameter))
                .conditionAdjustments(List.of(condition))
                .removedRuleIndices(List.of(9))
                .removedRuleDescriptions(List.of("IF kitchen.motion = active THEN kitchen.light.turnOn"))
                .build();

        String json = objectMapper.writeValueAsString(FixResultDto.builder()
                .faultRules(List.of(faultRule))
                .suggestions(List.of(suggestion))
                .parameterTargets(List.of(parameterTarget))
                .build());

        assertFalse(json.contains("ruleIndex"));
        assertFalse(json.contains("conditionIndex"));
        assertFalse(json.contains("conflictWithRuleIndex"));
        assertFalse(json.contains("ruleId"));
        assertFalse(json.contains("targetDeviceId"));
        assertFalse(json.contains("targetActionId"));
        assertFalse(json.contains("kitchen_motion_1"));
        assertFalse(json.contains("removedRuleIndices"));
        assertTrue(json.contains("Kitchen motion sensor"));
        assertTrue(json.contains("Kitchen light"));
        assertTrue(json.contains("removedRuleDescriptions"));
        assertTrue(json.contains("Adjust the kitchen temperature threshold"));
        assertTrue(json.contains("\"modelTokenSource\":\"BUNDLED\""));
        assertTrue(json.contains("\"modelTokenSource\":\"CUSTOM\""));
    }

    /*
     * Every listed suggestion is a verified one, so `verified` was deleted rather than kept at a constant true;
     * the attempt counter was deleted because one number could not mean the same thing across strategies. A
     * client still sending `verified` must fail loudly under the strict mapper instead of having it ignored.
     */
    @Test
    void fixAttemptsAndSuggestionsCarryOnlyTheCurrentVerdictFields() throws Exception {
        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(FixResultDto.builder()
                .suggestions(List.of(FixSuggestionDto.builder()
                        .strategy("remove")
                        .description("Remove the conflicting rule")
                        .removedRuleDescriptions(List.of("Old rule"))
                        .build()))
                .strategyAttempts(List.of(
                        FixStrategyAttemptDto.builder()
                                .strategy("remove").status("VERIFIED").reason("listed")
                                .alternativesComplete(true).build(),
                        FixStrategyAttemptDto.builder()
                                .strategy("parameter").status("TIMED_OUT").reason("deadline").build()))
                .build()));

        JsonNode verified = root.path("strategyAttempts").path(0);
        assertEquals(List.of("strategy", "status", "reason", "alternativesComplete"), fieldNames(verified));
        assertTrue(verified.path("alternativesComplete").booleanValue());
        JsonNode timedOut = root.path("strategyAttempts").path(1);
        assertTrue(timedOut.has("alternativesComplete") && timedOut.path("alternativesComplete").isNull(),
                "completeness describes a listing, so an attempt that listed nothing sends an explicit null");
        assertFalse(root.path("suggestions").path(0).has("verified"));

        String staleSuggestion = "{\"strategy\":\"remove\",\"description\":\"d\",\"verified\":true}";
        assertThrows(UnrecognizedPropertyException.class,
                () -> objectMapper.readValue(staleSuggestion, FixSuggestionDto.class));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void nonApplicableFixCollectionsSerializeAsEmptyArrays() throws Exception {
        FixSuggestionDto suggestion = FixSuggestionDto.builder()
                .strategy("remove")
                .description("Remove the conflicting rule")
                .build();

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(
                FixResultDto.builder().suggestions(List.of(suggestion)).build()));
        JsonNode suggestionJson = root.path("suggestions").path(0);

        assertEquals(0, suggestionJson.path("parameterAdjustments").size());
        assertEquals(0, suggestionJson.path("conditionAdjustments").size());
        assertEquals(0, suggestionJson.path("removedRuleDescriptions").size());
        assertTrue(suggestionJson.path("preexistingViolations").isArray());
        assertEquals(0, suggestionJson.path("preexistingViolations").size());
        assertEquals(0, root.path("faultRules").size());
        assertEquals(0, root.path("strategyAttempts").size());
        assertEquals("NOT_CHECKED", root.path("templateSnapshotComparison").asText());
        assertEquals(0, root.path("warnings").size());
        assertEquals(0, root.path("unusedPreferredRangeSelections").size());
    }
}
