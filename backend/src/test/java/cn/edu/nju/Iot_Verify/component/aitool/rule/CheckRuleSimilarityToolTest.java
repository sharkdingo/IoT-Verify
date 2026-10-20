package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationResultParser;
import cn.edu.nju.Iot_Verify.service.recommendation.RuleCheckService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckRuleSimilarityToolTest {

    @Mock
    private BoardStorageService boardStorageService;

    @Mock
    private PromptCompletionService promptCompletionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CheckRuleSimilarityTool tool;
    private RuleCheckService service;

    @BeforeEach
    void setUp() {
        service = new RuleCheckService(boardStorageService, promptCompletionService,
                objectMapper, new RecommendationResultParser(objectMapper));
        tool = new CheckRuleSimilarityTool(service, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void typedSimilarityRequiresReviewForHighScoreEvenWithoutModelFlags() {
        when(boardStorageService.getRules(7L)).thenReturn(List.of(existingCameraRule()));
        when(promptCompletionService.complete(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"isSimilar":false,"isDuplicate":false,"similarity":0.86,
                         "mostSimilarWith":"candidate-1","reason":"overlapping trigger"}
                        """);

        var result = service.checkSimilarity(7L, existingCameraRule());

        assertFalse(result.isSimilar());
        assertFalse(result.isDuplicate());
        assertTrue(result.isRequiresReview());
        assertEquals("AI_HIGH_SCORE_REVIEW", result.getReasonCode());
        assertEquals("Camera.capture", result.getMatchedRule());
        verify(boardStorageService).getRules(7L);
    }

    @Test
    void typedSimilarityCanonicalizesAliasesAndCaseWithoutChangingCaller() {
        RuleDto candidate = existingCameraRule();
        candidate.getConditions().get(0).setDeviceName(" Door Sensor ");
        candidate.getConditions().get(0).setAttribute(" open ");
        candidate.getConditions().get(0).setTargetType(" VARIABLE ");
        candidate.getConditions().get(0).setRelation(" EQ ");
        candidate.getConditions().get(0).setValue(" true ");
        candidate.getCommand().setDeviceName(" Camera ");
        candidate.getCommand().setAction(" capture ");
        when(boardStorageService.getRules(7L)).thenReturn(List.of(existingCameraRule()));
        when(promptCompletionService.complete(anyString(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    String prompt = invocation.getArgument(1);
                    String candidateJson = prompt.substring(prompt.indexOf("## 新添加的规则") + "## 新添加的规则".length(),
                            prompt.indexOf("## 现有规则列表")).trim();
                    JsonNode submitted = objectMapper.readTree(candidateJson);
                    JsonNode condition = submitted.path("conditions").get(0);
                    assertEquals("variable", condition.path("targetType").asText());
                    assertEquals("=", condition.path("relation").asText());
                    assertEquals("Door Sensor", condition.path("deviceName").asText());
                    assertEquals("open", condition.path("attribute").asText());
                    assertEquals("true", condition.path("value").asText());
                    assertEquals("Camera", submitted.path("command").path("deviceName").asText());
                    assertEquals("capture", submitted.path("command").path("action").asText());
                    return """
                            {"isSimilar":false,"isDuplicate":false,"similarity":0.2,"reason":"different trigger"}
                            """;
                });

        var result = service.checkSimilarity(7L, candidate);

        assertEquals("AI_NO_SIGNIFICANT_SIMILARITY", result.getReasonCode());
        assertEquals(" VARIABLE ", candidate.getConditions().get(0).getTargetType());
        assertEquals(" EQ ", candidate.getConditions().get(0).getRelation());
        assertEquals(" capture ", candidate.getCommand().getAction());
        assertEquals(9L, candidate.getId());
    }

    @Test
    void checkSimilarity_rejectsUnknownRootAndInvalidApiShapeBeforeReadingRulesOrCallingAi() throws Exception {
        JsonNode unknown = objectMapper.readTree(tool.execute("""
                {"newRule":{"conditions":[{"deviceName":"sensor","attribute":"motion",
                 "targetType":"api"}],"command":{"deviceName":"alarm","action":"on"}},
                 "threshold":0.8}
                """));
        JsonNode invalidApi = objectMapper.readTree(tool.execute("""
                {"newRule":{"conditions":[{"deviceName":"sensor","attribute":"motion",
                 "targetType":"api","relation":"=","value":"true"}],
                 "command":{"deviceName":"alarm","action":"on"}}}
                """));

        assertEquals("VALIDATION_ERROR", unknown.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", invalidApi.path("errorCode").asText());
        assertTrue(invalidApi.path("error").asText().contains("must omit relation and value"));
        verifyNoInteractions(boardStorageService, promptCompletionService);
    }

    @Test
    void checkSimilarity_whenNoExistingRules_returnsNotSimilarWithoutCallingAi() throws Exception {
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of());

        String result = tool.execute("""
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"Light_1","attribute":"state","targetType":"state","relation":"=","value":"On"}
                    ],
                    "command":{"deviceName":"Light_1","action":"turnOn"}
                  }
                }
                """);
        JsonNode json = objectMapper.readTree(result);

        assertFalse(json.path("isSimilar").asBoolean(true));
        assertFalse(json.path("isDuplicate").asBoolean(true));
        assertFalse(json.path("requiresReview").asBoolean(true));
        assertEquals("NO_EXISTING_RULES", json.path("reasonCode").asText());
        assertTrue(json.path("matchedRule").isNull());
        verifyNoInteractions(promptCompletionService);
    }

    @Test
    void checkSimilarity_promptPreservesTypedConditionSemantics() throws Exception {
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existingCameraRule()));
        when(promptCompletionService.complete(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "isSimilar": false,
                          "isDuplicate": false,
                          "similarity": 0.2,
                          "reason": "different trigger"
                        }
                        """);

        tool.execute("""
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"Door Sensor","attribute":"motion","targetType":"api"},
                      {"deviceName":"Thermostat","attribute":"temperature","targetType":"variable","relation":">","value":"28"}
                    ],
                    "command":{"deviceName":"Camera","action":"capture"}
                  }
                }
                """);

        verify(promptCompletionService).complete(
                anyString(),
                argThat(prompt -> prompt.contains("\"targetType\":\"api\"")
                        && prompt.contains("\"targetType\":\"variable\"")
                        && prompt.contains("\"relation\":null")
                        && prompt.contains("\"relation\":\">\"")
                        && prompt.contains("\"value\":\"28\"")),
                anyDouble(),
                anyInt());
    }

    @Test
    void checkSimilarity_aiResponseReturnsStableSimilarityFields() throws Exception {
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existingCameraRule()));
        when(promptCompletionService.complete(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        ```json
                        {
                          "isSimilar": true,
                          "isDuplicate": false,
                          "mostSimilarWith": "candidate-1",
                          "similarity": 0.86,
                          "reason": "same target action and overlapping security trigger"
                        }
                        ```
                        """);

        String result = tool.execute("""
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"Door Sensor","attribute":"open","targetType":"variable","relation":"=","value":"true"}
                    ],
                    "command":{"deviceName":"Camera","action":"capture"}
                  }
                }
                """);
        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("isSimilar").asBoolean(false));
        assertFalse(json.path("isDuplicate").asBoolean(true));
        assertTrue(json.path("requiresReview").asBoolean(false));
        assertEquals("AI_SIMILAR", json.path("reasonCode").asText());
        assertEquals("Camera.capture", json.path("matchedRule").asText());
        assertTrue(json.path("mostSimilarWith").isMissingNode());
        assertEquals(0.86, json.path("similarity").asDouble(), 0.0001);
        assertTrue(json.path("message").asText().contains("similar"));
    }

    @Test
    void checkSimilarity_unusableAiResponse_isFailureNotNoSimilarityResult() throws Exception {
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existingCameraRule()));
        when(promptCompletionService.complete(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{}");

        String result = tool.execute("""
                {
                  "newRule":{
                    "conditions":[
                      {"deviceName":"Door Sensor","attribute":"open","targetType":"variable","relation":"=","value":"true"}
                    ],
                    "command":{"deviceName":"Camera","action":"capture"}
                  }
                }
                """);
        JsonNode json = objectMapper.readTree(result);

        assertEquals("AI_RESPONSE_INVALID", json.path("errorCode").asText());
        assertEquals(502, json.path("status").asInt());
        assertTrue(json.path("isSimilar").isMissingNode());
    }

    @Test
    void checkSimilarity_outOfRangeScore_isFailure() throws Exception {
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existingCameraRule()));
        when(promptCompletionService.complete(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"isSimilar":true,"isDuplicate":false,"similarity":1.4,
                         "reason":"invalid confidence"}
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("""
                {"newRule":{"conditions":[{"deviceName":"Door Sensor","attribute":"open",
                 "targetType":"variable","relation":"=","value":"true"}],
                 "command":{"deviceName":"Camera","action":"capture"}}}
                """));

        assertEquals("AI_RESPONSE_INVALID", json.path("errorCode").asText());
        assertEquals(502, json.path("status").asInt());
    }

    @Test
    void checkSimilarity_duplicateNotMarkedSimilar_isFailure() throws Exception {
        when(boardStorageService.getRules(anyLong())).thenReturn(List.of(existingCameraRule()));
        when(promptCompletionService.complete(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"isSimilar":false,"isDuplicate":true,"similarity":1.0,
                         "duplicateWith":"candidate-1","reason":"same rule"}
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("""
                {"newRule":{"conditions":[{"deviceName":"Door Sensor","attribute":"open",
                 "targetType":"variable","relation":"=","value":"true"}],
                 "command":{"deviceName":"Camera","action":"capture"}}}
                """));

        assertEquals("AI_RESPONSE_INVALID", json.path("errorCode").asText());
        assertEquals(502, json.path("status").asInt());
    }

    private RuleDto existingCameraRule() {
        return RuleDto.builder()
                .id(9L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("Door Sensor")
                        .attribute("open")
                        .targetType("variable")
                        .relation("=")
                        .value("true")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("Camera")
                        .action("capture")
                        .build())
                .build();
    }
}
