package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationResultParser;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleCheckService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckDuplicateRuleToolTest {

    @Mock
    private BoardStorageService boardStorageService;

    @Mock
    private PromptCompletionService promptCompletionService;

    private CheckDuplicateRuleTool tool;
    private RuleCheckService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new RuleCheckService(boardStorageService, promptCompletionService,
                objectMapper, new RecommendationResultParser(objectMapper));
        tool = new CheckDuplicateRuleTool(service, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void typedDuplicateCheckUsesExplicitOwnerAndNeverCallsTheProvider() {
        when(boardStorageService.getRules(7L)).thenReturn(List.of());

        var result = service.checkDuplicate(7L, validCandidate());

        assertFalse(result.isDuplicate());
        assertFalse(result.isRequiresReview());
        assertEquals("NO_EXISTING_RULES", result.getReasonCode());
        verify(boardStorageService).getRules(7L);
        verifyNoInteractions(promptCompletionService);
    }

    @Test
    void typedDuplicateCheckPreservesSelfIdAndSkipsTheRuleBeingEdited() {
        RuleDto candidate = validCandidate();
        candidate.setId(9L);
        when(boardStorageService.getRules(7L)).thenReturn(List.of(candidate));

        var result = service.checkDuplicate(7L, candidate);

        assertFalse(result.isDuplicate());
        assertEquals("NO_MATCHING_SIGNATURE", result.getReasonCode());
        assertEquals(9L, candidate.getId());
        verifyNoInteractions(promptCompletionService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTypedCandidates")
    void typedChecksRejectMalformedCandidatesBeforeReadingBoard(String kind, RuleDto candidate) throws Exception {
        BadRequestException duplicateFailure = assertThrows(BadRequestException.class,
                () -> service.checkDuplicate(7L, candidate));
        BadRequestException similarityFailure = assertThrows(BadRequestException.class,
                () -> service.checkSimilarity(7L, candidate));
        assertEquals(400, duplicateFailure.getCode());
        assertEquals(duplicateFailure.getMessage(), similarityFailure.getMessage());

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.valueToTree(candidate);
        payload.remove("createdAt");
        String arguments = mapper.writeValueAsString(java.util.Map.of("newRule", payload));
        CheckRuleSimilarityTool similarityTool = new CheckRuleSimilarityTool(service, mapper);
        for (String result : List.of(tool.execute(arguments), similarityTool.execute(arguments))) {
            JsonNode error = mapper.readTree(result);
            assertEquals(400, error.path("status").intValue());
            assertEquals("VALIDATION_ERROR", error.path("errorCode").textValue());
            assertEquals(duplicateFailure.getMessage(), error.path("error").textValue());
        }
        verifyNoInteractions(boardStorageService, promptCompletionService);
    }

    @Test
    void typedAndJsonCandidatesShareCanonicalDuplicateDecisionWithoutChangingCaller() throws Exception {
        RuleDto candidate = validCandidate();
        candidate.getConditions().get(0).setTargetType(" STATE ");
        candidate.getConditions().get(0).setRelation(" EQ ");
        candidate.getConditions().get(0).setDeviceName(" Light_1 ");
        candidate.getCommand().setAction(" turnOn ");
        when(boardStorageService.getRules(7L)).thenReturn(List.of(validCandidate()));

        ObjectMapper mapper = new ObjectMapper();
        var typedResult = service.checkDuplicate(7L, candidate);
        ObjectNode payload = mapper.valueToTree(candidate);
        payload.remove("createdAt");
        UserContextHolder.setUserId(7L);
        JsonNode toolResult = mapper.readTree(tool.execute(
                mapper.writeValueAsString(java.util.Map.of("newRule", payload))));

        assertTrue(typedResult.isDuplicate());
        assertEquals(mapper.valueToTree(typedResult), toolResult);
        assertEquals(" STATE ", candidate.getConditions().get(0).getTargetType());
        assertEquals(" EQ ", candidate.getConditions().get(0).getRelation());
        assertEquals(" turnOn ", candidate.getCommand().getAction());
        verify(boardStorageService, org.mockito.Mockito.times(2)).getRules(7L);
        verifyNoInteractions(promptCompletionService);
    }

    private static Stream<Arguments> invalidTypedCandidates() {
        RuleDto negativeId = validCandidate();
        negativeId.setId(-1L);
        RuleDto zeroId = validCandidate();
        zeroId.setId(0L);
        RuleDto stateOrdering = validCandidate();
        stateOrdering.getConditions().get(0).setRelation(">");
        RuleDto emptySet = validCandidate();
        emptySet.getConditions().get(0).setRelation("in");
        emptySet.getConditions().get(0).setValue(", ; |");
        RuleDto partialContent = validCandidate();
        partialContent.getCommand().setContent("photo");
        RuleDto eventComparison = validCandidate();
        eventComparison.getConditions().get(0).setTargetType("api");
        return Stream.of(Arguments.of("negative id", negativeId), Arguments.of("zero id", zeroId),
                Arguments.of("state ordering", stateOrdering), Arguments.of("empty set", emptySet),
                Arguments.of("partial content", partialContent), Arguments.of("API comparison", eventComparison));
    }

    private static RuleDto validCandidate() {
        return RuleDto.builder().conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("Light_1").attribute("state").targetType("state").relation("=").value("On").build()))
                .command(RuleDto.Command.builder().deviceName("Light_1").action("turnOn").build()).build();
    }

    @Test
    void checkDuplicate_rejectsUnknownAndWronglyTypedCandidateFieldsBeforeReadingRules() throws Exception {
        JsonNode unknown = new ObjectMapper().readTree(tool.execute("""
                {"newRule":{"conditions":[{"deviceName":"light","attribute":"state",
                 "targetType":"state","relation":"=","value":"on","deviceLabel":"Light"}],
                 "command":{"deviceName":"light","action":"turnOn"}}}
                """));
        JsonNode wrongType = new ObjectMapper().readTree(tool.execute("""
                {"newRule":{"conditions":[{"deviceName":"light","attribute":"state",
                 "targetType":"state","relation":"=","value":true}],
                 "command":{"deviceName":"light","action":"turnOn"}}}
                """));

        assertEquals("VALIDATION_ERROR", unknown.path("errorCode").asText());
        assertTrue(unknown.path("error").asText().contains("deviceLabel"));
        assertEquals("VALIDATION_ERROR", wrongType.path("errorCode").asText());
        assertTrue(wrongType.path("error").asText().contains("value must be a string"));
        verify(boardStorageService, never()).getRules(anyLong());
    }

    /**
     * Regression: the "no existing rules" branch used to build its response with
     * {@code Map.of(..., "matchedRule", null, ...)}, which throws NPE (Map.of forbids
     * null values). That NPE was swallowed by the catch-all and returned as a 500
     * INTERNAL_ERROR, so checking the very first rule on an empty board failed instead of
     * returning {isDuplicate:false}. This asserts the branch now returns clean JSON.
     */
    @Test
    void checkDuplicate_whenNoExistingRules_returnsNotDuplicateWithoutError() throws Exception {
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of());

        String argsJson = """
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"Light_1","attribute":"state","targetType":"state","relation":"=","value":"On"}
                    ],
                    "command":{"deviceName":"Light_1","action":"turnOn"}
                  }
                }
                """;

        String result = tool.execute(argsJson);
        JsonNode json = new ObjectMapper().readTree(result);

        // No error envelope produced (NPE would have surfaced as INTERNAL_ERROR/500).
        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertFalse(json.path("isDuplicate").asBoolean(true), "first rule on empty board must not be duplicate");
        assertFalse(json.path("requiresReview").asBoolean(true));
        assertEquals(0.0, json.path("similarity").asDouble(-1.0));
        assertEquals("NO_EXISTING_RULES", json.path("reasonCode").asText());
        assertTrue(json.path("matchedRule").isNull(), "matchedRule should be JSON null");
        assertTrue(json.path("message").asText().contains("first rule"));

        verify(boardStorageService).getRules(anyLong());
    }

    @Test
    void checkDuplicate_exactSameTypedRule_returnsDuplicateWithoutCallingAi() throws Exception {
        RuleDto existing = RuleDto.builder()
                .id(7L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("light_signal")
                        .attribute("on")
                        .targetType("api")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("light_action")
                        .action("on")
                        .build())
                .build();
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existing));

        String result = tool.execute("""
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"light_signal","attribute":"on","targetType":"api"}
                    ],
                    "command":{"deviceName":"light_action","action":"on"}
                  }
                }
                """);
        JsonNode json = new ObjectMapper().readTree(result);

        assertTrue(json.path("isDuplicate").asBoolean(false));
        assertTrue(json.path("requiresReview").asBoolean(false));
        assertEquals("light_signal.on -> light_action.on", json.path("matchedRule").asText());
        assertTrue(json.path("duplicateWith").isMissingNode());
        assertEquals("exact", json.path("matchType").asText());
        assertEquals("EXACT_MATCH", json.path("reasonCode").asText());
        assertEquals(1.0, json.path("similarity").asDouble(), 0.0001);
    }

    @Test
    void checkDuplicate_sameActionAndConditionShapeWithDifferentValue_returnsPotentialDuplicate() throws Exception {
        RuleDto existing = RuleDto.builder()
                .id(11L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("temp_sensor")
                        .attribute("temperature")
                        .targetType("variable")
                        .relation(">")
                        .value("28")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("ac")
                        .action("cool")
                        .build())
                .build();
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existing));

        String result = tool.execute("""
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"temp_sensor","attribute":"temperature","targetType":"variable","relation":"GT","value":"30"}
                    ],
                    "command":{"deviceName":"ac","action":"cool"}
                  }
                }
                """);
        JsonNode json = new ObjectMapper().readTree(result);

        assertFalse(json.path("isDuplicate").asBoolean(true));
        assertTrue(json.path("requiresReview").asBoolean(false));
        assertEquals("temp_sensor.temperature > 28 -> ac.cool", json.path("matchedRule").asText());
        assertEquals("same-shape", json.path("matchType").asText());
        assertEquals("SAME_TRIGGER_SHAPE_DIFFERENT_VALUES", json.path("reasonCode").asText());
        assertTrue(json.path("similarity").asDouble() >= 0.8);
    }

    @Test
    void checkDuplicate_inRelationValueOrder_isCanonicalized() throws Exception {
        RuleDto existing = RuleDto.builder()
                .id(12L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("home_mode")
                        .attribute("Mode")
                        .targetType("mode")
                        .relation("in")
                        .value("home, sleep")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("alarm")
                        .action("siren")
                        .build())
                .build();
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existing));

        String result = tool.execute("""
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"home_mode","attribute":"Mode","targetType":"mode","relation":"IN","value":"sleep|home"}
                    ],
                    "command":{"deviceName":"alarm","action":"siren"}
                  }
                }
                """);
        JsonNode json = new ObjectMapper().readTree(result);

        assertTrue(json.path("isDuplicate").asBoolean(false));
        assertTrue(json.path("requiresReview").asBoolean(false));
        assertEquals("exact", json.path("matchType").asText());
    }
}
