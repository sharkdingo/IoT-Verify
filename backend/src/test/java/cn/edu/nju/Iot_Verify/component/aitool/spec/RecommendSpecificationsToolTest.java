package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.service.recommendation.DeviceInfoHelper;
import cn.edu.nju.Iot_Verify.service.recommendation.SpecificationRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationResultParser;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.testutil.LogCapture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RecommendSpecificationsToolTest {

    @Mock
    private DeviceInfoHelper deviceInfoHelper;

    @Mock
    private BoardStorageService boardStorageService;

    @Mock
    private PromptCompletionService promptCompletionService;

    private BoardSemanticSnapshotDto snapshot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecommendSpecificationsTool tool;

    @BeforeEach
    void setUp() {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of());
        lenient().when(boardStorageService.getSemanticSnapshot(1L)).thenAnswer(invocation -> snapshot);
        tool = new RecommendSpecificationsTool(new SpecificationRecommendationService(deviceInfoHelper, boardStorageService,
                promptCompletionService, objectMapper, new RecommendationResultParser(objectMapper)), objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void promptAndValidationUseOneCapturedSceneEvenIfBoardChangesDuringCompletion() throws Exception {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("thermostat");
        node.setLabel("Thermostat");
        node.setTemplateName("Thermostat");
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Thermostat");
        template.setManifest(DeviceTemplateDto.DeviceManifest.builder().name("Thermostat")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(true).lowerBound(15).upperBound(35).build()))
                .build());
        snapshot = new BoardSemanticSnapshotDto(
                List.of(node), List.of(), List.of(), List.of(), List.of(template));
        tool = new RecommendSpecificationsTool(new SpecificationRecommendationService(new DeviceInfoHelper(), boardStorageService,
                promptCompletionService, objectMapper, new RecommendationResultParser(objectMapper)), objectMapper);
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    assertTrue(invocation.<String>getArgument(1).contains("thermostat"));
                    snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of());
                    return """
                            {"recommendations":[{"rationale":"Limit temperature","templateId":"3",
                              "aConditions":[{"deviceId":"thermostat","targetType":"variable",
                                "key":"temperature","variableSource":"reported","relation":">","value":"25"}],
                              "ifConditions":[],"thenConditions":[]}]}
                            """;
                });

        JsonNode result = objectMapper.readTree(tool.execute("{}"));

        assertEquals(1, result.path("validatedCount").asInt(), result.toString());
        verify(boardStorageService).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);
    }

    @Test
    void execute_rejectsUnknownRequestFieldBeforeReadingBoardOrCallingAi() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute("{\"catgory\":\"security\"}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("$ contains unsupported field(s): catgory.", json.path("error").asText());
        verify(deviceInfoHelper, never()).getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class));
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_withUnparseableAiResponse_returnsUpstreamErrorNotEmptySuccess() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("not-json");

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals("AI_RESPONSE_INVALID", json.path("errorCode").asText());
        assertEquals(502, json.path("status").asInt());
        assertEquals("response_parse", json.path("phase").asText());
    }

    @Test
    void execute_doesNotLogRejectedModelCandidateContent() throws Exception {
        String sensitive = "private-spec-value-never-log";
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            "private-spec-value-never-log",
                            {
                              "rationale": "private-spec-value-never-log",
                              "templateId": "invalid",
                              "aConditions": [],
                              "ifConditions": [],
                              "thenConditions": []
                            }
                          ]
                        }
                        """);

        try (LogCapture logs = LogCapture.forClass(SpecificationRecommendationService.class)) {
            JsonNode json = objectMapper.readTree(tool.execute("{}"));

            assertEquals(2, json.path("filteredCount").asInt());
            assertFalse(logs.messages().stream().anyMatch(message -> message.contains(sensitive)));
        }
    }

    @Test
    void execute_whenAiReturnsNoCandidates_reportsNoFiltering() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"recommendations\":[]}");

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals(0, json.path("rawCandidateCount").asInt());
        assertEquals(0, json.path("filteredCount").asInt());
        assertTrue(json.path("message").asText().contains("returned no specification candidates"));
    }

    @Test
    void execute_withChineseLanguage_returnsLocalizedBackendMessage() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(
                anyString(),
                contains("输出语言: 简体中文"),
                anyDouble(),
                anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "温度过高时必须保持安全状态",
                              "templateId": "1",
                              "templateLabel": "always",
                              "aConditions": [
                                {"deviceId":"node-thermostat","deviceLabel":"Thermostat","targetType":"variable","variableSource":"reported","key":"temperature","relation":">","value":"30"}
                              ],
                              "ifConditions": [],
                              "thenConditions": [],
                              "confidence": 0.88
                            }
                          ]
                        }
                        """);

        String result = tool.execute("""
                {
                  "language": "zh-CN",
                  "maxRecommendations": 3
                }
                """);

        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertEquals("基于当前设备找到 1 条规约推荐。", json.path("message").asText());
        assertEquals(1, json.path("count").asInt());
        assertEquals("1", json.path("recommendations").get(0).path("templateId").asText());
        assertFalse(json.path("recommendations").get(0).has("templateLabel"));
        assertFalse(json.path("recommendations").get(0).path("aConditions").get(0).has("side"));
    }

    @Test
    void execute_stripsVolunteeredCategoryInsteadOfDiscardingTheCandidate() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(homeModeDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "category": "safety",
                              "rationale": "Sleep mode should never be active",
                              "templateId": "3",
                              "aConditions": [
                                {"deviceId":"node-home-mode","deviceLabel":"Home Mode","targetType":"mode","key":"Mode","relation":"=","value":"sleep"}
                              ],
                              "ifConditions": [],
                              "thenConditions": []
                            }
                          ]
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        // A specification's classification is templateId; a volunteered category is a label with no
        // verification meaning, so it is stripped rather than allowed to discard a valid candidate.
        assertEquals(1, json.path("validatedCount").asInt());
        assertEquals(0, json.path("filteredCount").asInt());
        assertTrue(json.path("recommendations").get(0).path("category").isMissingNode(),
                "a volunteered category must not reach the recommendation DTO");
    }

    @Test
    void execute_acceptsModeConditionWithCanonicalDeviceId() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(homeModeDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "Sleep mode should never be active",
                              "templateId": "3",
                              "templateLabel": "never",
                              "aConditions": [
                                {"deviceId":"node-home-mode","deviceLabel":"Home Mode","targetType":"mode","key":"Mode","relation":"=","value":"sleep"}
                              ],
                              "ifConditions": [],
                              "thenConditions": [],
                              "confidence": 0.81
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertEquals(1, json.path("count").asInt());
        JsonNode condition = json.path("recommendations").get(0).path("aConditions").get(0);
        assertEquals("mode", condition.path("targetType").asText());
        assertEquals("Mode", condition.path("key").asText());
        assertEquals("sleep", condition.path("value").asText());
    }

    @Test
    void execute_returnsStatePrivacyAsSemanticScopeAndModeRatherThanGeneratedKey() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(homeModeDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [{
                            "rationale": "Current home mode state must not carry private data",
                            "templateId": "3",
                            "aConditions": [{
                              "deviceId":"node-home-mode",
                              "targetType":"privacy",
                              "propertyScope":"STATE",
                              "key":"mode",
                              "relation":"=",
                              "value":"PRIVATE"
                            }],
                            "ifConditions": [],
                            "thenConditions": []
                          }]
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{}"));
        JsonNode condition = json.path("recommendations").get(0).path("aConditions").get(0);

        assertEquals(1, json.path("count").asInt());
        assertEquals("state", condition.path("propertyScope").asText());
        assertEquals("Mode", condition.path("key").asText());
        assertEquals("private", condition.path("value").asText());
        assertTrue(condition.toString().contains("\"Mode\""));
        assertTrue(!condition.toString().contains("Mode_"));
    }

    @Test
    void execute_normalizesSetRelationRecommendation() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(homeModeDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "Home mode should avoid away-only behavior",
                              "templateId": "3",
                              "templateLabel": "never",
                              "aConditions": [
                                {"deviceId":"node-home-mode","deviceLabel":"Home Mode","targetType":"mode","key":"Mode","relation":"not_in","value":"away"}
                              ],
                              "ifConditions": [],
                              "thenConditions": [],
                              "confidence": 0.81
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);
        JsonNode condition = json.path("recommendations").get(0).path("aConditions").get(0);

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertEquals(1, json.path("count").asInt());
        assertEquals("not in", condition.path("relation").asText());
        assertEquals("away", condition.path("value").asText());
    }

    @Test
    void execute_acceptsFullStateTupleSetRecommendation() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(homeModeDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "Occupied idle modes should be explicitly checked",
                              "templateId": "3",
                              "templateLabel": "never",
                              "aConditions": [
                                {"deviceId":"node-home-mode","deviceLabel":"Home Mode","targetType":"state","key":"state","relation":"IN","value":"home;idle,sleep;idle"}
                              ],
                              "ifConditions": [],
                              "thenConditions": [],
                              "confidence": 0.81
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);
        JsonNode condition = json.path("recommendations").get(0).path("aConditions").get(0);

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertEquals(1, json.path("count").asInt());
        assertEquals("state", condition.path("targetType").asText());
        assertEquals("state", condition.path("key").asText());
        assertEquals("in", condition.path("relation").asText());
        assertEquals("home;idle,sleep;idle", condition.path("value").asText());
    }

    @Test
    void execute_defaultsApiConditionValueToTrue() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(cameraDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "Snapshot signal should be observable",
                              "templateId": "1",
                              "templateLabel": "always",
                              "aConditions": [
                                {"deviceId":"node-camera","deviceLabel":"Camera","targetType":"api","key":"take photo"}
                              ],
                              "ifConditions": [],
                              "thenConditions": [],
                              "confidence": 0.75
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertEquals(1, json.path("count").asInt());
        JsonNode condition = json.path("recommendations").get(0).path("aConditions").get(0);
        assertEquals("api", condition.path("targetType").asText());
        assertEquals("=", condition.path("relation").asText());
        assertEquals("TRUE", condition.path("value").asText());
    }

    @Test
    void execute_canonicalizesAiFieldCaseBeforeReturningRecommendation() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(homeModeDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "Lowercase AI fields should still apply",
                              "templateId": "3",
                              "templateLabel": "never",
                              "aConditions": [
                                {"deviceId":"node-home-mode","deviceLabel":"Home Mode","targetType":"mode","key":"mode","relation":"EQ","value":"SLEEP"}
                              ],
                              "ifConditions": [],
                              "thenConditions": [],
                              "confidence": 0.81
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);
        JsonNode condition = json.path("recommendations").get(0).path("aConditions").get(0);

        assertEquals(1, json.path("count").asInt());
        assertEquals("mode", condition.path("targetType").asText());
        assertEquals("Mode", condition.path("key").asText());
        assertEquals("=", condition.path("relation").asText());
        assertEquals("sleep", condition.path("value").asText());
    }

    @ParameterizedTest
    @CsvSource({"variable,reading,On", "variable,reading,on"})
    void execute_preservesExactCaseSensitiveEnumValue(String targetType, String key, String value) throws Exception {
        JsonNode json = recommendCaseSensitiveEnum(targetType, key, "=", value);

        assertEquals(1, json.path("validatedCount").asInt(), json.toString());
        assertEquals(value, json.path("recommendations").get(0).path("aConditions").get(0).path("value").asText());
    }

    @ParameterizedTest
    @CsvSource({"variable,reading"})
    void execute_filtersAmbiguousEnumCaseInsteadOfGuessing(String targetType, String key) throws Exception {
        JsonNode json = recommendCaseSensitiveEnum(targetType, key, "=", "ON");

        assertEquals(0, json.path("validatedCount").asInt(), json.toString());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals(0, json.path("recommendations").size());
    }

    @Test
    void execute_keepsDistinctEnumMembersInSetCondition() throws Exception {
        JsonNode json = recommendCaseSensitiveEnum("variable", "reading", "in", "on,On");

        assertEquals(1, json.path("validatedCount").asInt(), json.toString());
        assertEquals("on,On", json.path("recommendations").get(0).path("aConditions").get(0).path("value").asText());
    }

    private JsonNode recommendCaseSensitiveEnum(String targetType, String key, String relation, String value) throws Exception {
        var template = cn.edu.nju.Iot_Verify.testutil.CaseSensitiveEnumFixture.template();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("case-sensor");
        node.setLabel("Case sensor");
        node.setTemplateName(template.getName());
        node.setState("idle");
        snapshot = new BoardSemanticSnapshotDto(List.of(node),
                List.of(new cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto("reading", "on", "trusted", "public")),
                List.of(), List.of(), List.of(template));
        tool = new RecommendSpecificationsTool(new SpecificationRecommendationService(new DeviceInfoHelper(), boardStorageService,
                promptCompletionService, objectMapper, new RecommendationResultParser(objectMapper)), objectMapper);
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"recommendations":[{"rationale":"Keep the declared enum meaning","templateId":"3",
                          "aConditions":[{"deviceId":"case-sensor","targetType":"%s","key":"%s",%s
                            "relation":"%s","value":"%s"}],"ifConditions":[],"thenConditions":[]}]}
                        """.formatted(targetType, key,
                        "variable".equals(targetType) ? "\"variableSource\":\"reported\"," : "", relation, value));
        return objectMapper.readTree(tool.execute("{}"));
    }

    @Test
    void execute_filtersAiConditionThatSuppliesConflictingDerivedSide() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "High temperature should immediately enter safe state",
                              "templateId": "4",
                              "templateLabel": "immediate",
                              "aConditions": [],
                              "ifConditions": [
                                {"side":"then","deviceId":"node-thermostat","deviceLabel":"Thermostat","targetType":"variable","variableSource":"reported","key":"temperature","relation":">","value":"30"}
                              ],
                              "thenConditions": [
                                {"side":"if","deviceId":"node-thermostat","deviceLabel":"Thermostat","targetType":"state","key":"state","relation":"=","value":"safe"}
                              ],
                              "confidence": 0.81
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals(0, json.path("count").asInt());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("conditionSideField",
                json.path("filteredItems").get(0).path("reasonCode").asText());
    }

    @Test
    void execute_filtersRecommendationWithHiddenTemplateOneImplicationShape() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "rationale": "Hidden same-step implication",
                              "templateId": "1",
                              "templateLabel": "always",
                              "aConditions": [],
                              "ifConditions": [
                                {"deviceId":"node-thermostat","deviceLabel":"Thermostat","targetType":"variable","variableSource":"reported","key":"temperature","relation":">","value":"30"}
                              ],
                              "thenConditions": [
                                {"deviceId":"node-thermostat","deviceLabel":"Thermostat","targetType":"state","key":"state","relation":"=","value":"safe"}
                              ],
                              "confidence": 0.7
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals(0, json.path("count").asInt());
        assertTrue(json.path("recommendations").isEmpty());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals(1, json.path("rawCandidateCount").asInt());
        assertEquals(1, json.path("inspectedCount").asInt());
        assertEquals(0, json.path("truncatedCount").asInt());
        assertEquals("specification", json.path("filteredItems").get(0).path("type").asText());
        assertEquals("invalidTemplateShape", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("Hidden same-step implication", json.path("filteredItems").get(0).path("label").asText());
    }

    @Test
    void execute_rejectsRemovedCategoryArgumentInsteadOfIgnoringIt() throws Exception {
        // The two tools keep separate hand-maintained argument allowlists, so the rule tool's
        // equivalent case does not cover this one.
        JsonNode json = objectMapper.readTree(tool.execute("{\"category\":\"safety\"}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        verify(deviceInfoHelper, never()).getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class));
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_rejectsOutOfRangeCountBeforePrompting() throws Exception {
        String result = tool.execute("{\"maxRecommendations\":999}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("maxRecommendations must be between 1 and 10.", json.path("error").asText());
        verify(deviceInfoHelper, never()).getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class));
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_rejectsUnsupportedLanguageInsteadOfSilentlyUsingEnglish() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute("{\"language\":\"fr\"}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("language must be en or zh-CN.", json.path("error").asText());
        verify(deviceInfoHelper, never()).getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class));
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_filtersSafetyApiThatHasNoModeledReliabilityTarget() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(serviceWithoutApiEndState()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [{
                            "rationale": "Untrusted notification must not be emitted",
                            "templateId": "7",
                            "templateLabel": "Untrusted-source safety",
                            "aConditions": [{
                              "deviceId":"node-service",
                              "targetType":"api",
                              "key":"notify",
                              "relation":"=",
                              "value":"TRUE"
                            }],
                            "ifConditions": [],
                            "thenConditions": []
                          }]
                        }
                        """);

        JsonNode result = objectMapper.readTree(tool.execute("{}"));

        assertEquals(0, result.path("count").asInt());
        assertEquals(1, result.path("filteredCount").asInt());
        assertEquals("invalidUntrustedSourceSafetyCondition",
                result.path("filteredItems").get(0).path("reasonCode").asText());
        assertTrue(result.path("filteredItems").get(0).path("reason").asText()
                .contains("untrusted source"));
    }

    @Test
    void execute_filtersCandidateWithUnknownSemanticField() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"recommendations":[{
                          "rationale":"Temperature stays safe",
                          "templateId":"1",
                          "aConditions":[{"deviceId":"node-thermostat","targetType":"state",
                            "key":"state","relation":"=","value":"safe"}],
                          "ifConditions":[],"thenConditions":[],
                          "unmodeledAssumption":"window is closed"
                        }]}
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals(0, json.path("count").asInt());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("unknownCandidateField",
                json.path("filteredItems").get(0).path("reasonCode").asText());
    }

    @Test
    void execute_filtersCandidateWithoutUserFacingRationale() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"recommendations":[{
                          "templateId":"1",
                          "aConditions":[{"deviceId":"node-thermostat","targetType":"state",
                            "key":"state","relation":"=","value":"safe"}],
                          "ifConditions":[],"thenConditions":[]
                        }]}
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals(0, json.path("count").asInt());
        assertEquals("missingSpecificationRationale",
                json.path("filteredItems").get(0).path("reasonCode").asText());
    }

    @Test
    void execute_filtersVariableCandidateThatDidNotSayWhichValueItMeans() throws Exception {
        /*
         * Filtered and counted like any other malformed field. Unvalidated, this candidate reached the
         * client and threw a whole-response contract error there, discarding every *other* recommendation
         * in the batch over one bad row — and the user could accept a recommendation that `addSpec` would
         * then refuse.
         */
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(thermostatDevice()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"recommendations":[{
                          "rationale":"Keep the room from overheating",
                          "templateId":"1",
                          "aConditions":[{"deviceId":"node-thermostat","targetType":"variable",
                            "key":"temperature","relation":">","value":"30"}],
                          "ifConditions":[],"thenConditions":[]
                        }]}
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals(0, json.path("count").asInt());
        assertEquals("conditionMissingVariableSource",
                json.path("filteredItems").get(0).path("reasonCode").asText());
    }

    private DeviceInfoHelper.DeviceInfo thermostatDevice() {
        return new DeviceInfoHelper.DeviceInfo(
                "node-thermostat",
                "Thermostat",
                "Thermostat",
                "normal",
                "trusted",
                null,
                List.of(),
                List.of(),
                List.of(new DeviceInfoHelper.VariableInfo("temperature", "Temperature", null,
                        0, 50, null, null)),
                List.of(),
                List.of(),
                List.of(),
                List.of("normal", "safe"),
                List.of()
        );
    }

    private DeviceInfoHelper.DeviceInfo homeModeDevice() {
        return new DeviceInfoHelper.DeviceInfo(
                "node-home-mode",
                "Home Mode",
                "Home Mode",
                "sleep;idle",
                "trusted",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new DeviceInfoHelper.ModeInfo("Mode", List.of("home", "away", "sleep")),
                        new DeviceInfoHelper.ModeInfo("State", List.of("idle", "sendingAlertMessage", "sendingPhoto"))),
                List.of("home;idle", "away;idle", "sleep;idle"),
                List.of()
        );
    }

    private DeviceInfoHelper.DeviceInfo cameraDevice() {
        return new DeviceInfoHelper.DeviceInfo(
                "node-camera",
                "Camera",
                "Camera",
                "idle",
                "trusted",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new DeviceInfoHelper.ApiInfo("take photo", "Take photo",
                        true, false, "_", "idle")),
                List.of(),
                List.of(new DeviceInfoHelper.ModeInfo("Mode", List.of("idle", "capturing"))),
                List.of("idle", "capturing"),
                List.of()
        );
    }

    private DeviceInfoHelper.DeviceInfo serviceWithoutApiEndState() {
        return new DeviceInfoHelper.DeviceInfo(
                "node-service",
                "Notification Service",
                "Notification Service",
                "idle",
                "trusted",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new DeviceInfoHelper.ApiInfo("notify", "Emit notification",
                        true, false, "idle", null)),
                List.of(),
                List.of(new DeviceInfoHelper.ModeInfo("Status", List.of("idle"))),
                List.of("idle"),
                List.of()
        );
    }
}
