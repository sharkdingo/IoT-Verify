package cn.edu.nju.Iot_Verify.component.aitool.scenario;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.ScenarioRecommendationRequestDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.ScenarioRecommendationResponseDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationResultParser;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationFailure;
import cn.edu.nju.Iot_Verify.service.recommendation.ScenarioRecommendationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RecommendScenarioToolTest {

    private static final String DEFAULT_ARGS = """
            {"minDevices":1,"minRules":1,"minSpecs":1,
             "maxDevices":6,"maxRules":5,"maxSpecs":5}
            """;

    @Mock
    private PromptCompletionService promptCompletionService;

    @Mock
    private BoardStorageService boardStorageService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiScenarioDraftStore draftStore;
    private ChatExecutionConfig chatExecutionConfig;
    private ScenarioRecommendationService service;
    private BoardSemanticSnapshotDto snapshot;
    private RecommendScenarioTool tool;

    @BeforeEach
    void setUp() {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of());
        lenient().when(boardStorageService.getSemanticSnapshot(1L)).thenAnswer(invocation -> snapshot);
        draftStore = new AiScenarioDraftStore();
        chatExecutionConfig = new ChatExecutionConfig();
        service = new ScenarioRecommendationService(
                promptCompletionService, boardStorageService, draftStore,
                chatExecutionConfig, objectMapper, new RecommendationResultParser(objectMapper));
        tool = new RecommendScenarioTool(service, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void restRecommendationDoesNotReadAmbientChatOrReplaceItsDraft() throws Exception {
        UserContextHolder.setChatSessionId("session-1");
        draftStore.saveDraft(1L, "session-1", "Previous", objectMapper.readTree(
                "{\"devices\":[{\"id\":\"device_1\"}],\"rules\":[],\"specs\":[]}"));
        DeviceTemplateDto template = sensorTemplate();
        template.getManifest().setIcon("data:image/svg+xml;base64," + "A".repeat(6_000));
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(template));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"scenarioName":"REST preview","scene":{
                          "devices":[{"id":"sensor","templateName":"Noise Sensor","label":"Sensor"}],
                          "environmentVariables":[],"rules":[],"specs":[]}}
                        """);
        chatExecutionConfig.setMaxToolResultBytes(4_096);

        ScenarioRecommendationResponseDto response = service.recommend(1L,
                objectMapper.readValue(DEFAULT_ARGS, ScenarioRecommendationRequestDto.class));

        assertEquals("REST preview", response.getScenarioName());
        assertEquals(1, response.getScene().getDevices().size());
        assertNull(response.getDraftStored());
        assertNull(response.getPreviousDraftRetained());
        JsonNode json = objectMapper.valueToTree(response);
        assertFalse(json.has("draftStored"));
        assertFalse(json.has("previousDraftRetained"));
        assertTrue(objectMapper.writeValueAsBytes(response).length > 4_096);
        assertEquals("Previous", draftStore.latestDraft(1L, "session-1").orElseThrow().scenarioName());
    }

    @Test
    void unusableProviderResultThrowsTypedFailureWithImmutableDetails() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("not-json");

        RecommendationFailure failure = assertThrows(RecommendationFailure.class, () -> service.recommend(
                1L, objectMapper.readValue(DEFAULT_ARGS, ScenarioRecommendationRequestDto.class)));

        assertEquals(502, failure.getCode());
        assertEquals("AI_RESPONSE_INVALID", failure.getErrorCode());
        assertEquals("response_parse", failure.getDetails().get("phase"));
        assertEquals(false, failure.getDetails().get("draftStored"));
        assertThrows(UnsupportedOperationException.class, () -> failure.getDetails().put("phase", "changed"));
    }

    @Test
    void promptAndValidationUseOneCapturedSceneEvenIfBoardChangesDuringCompletion() throws Exception {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("captured-node");
        node.setTemplateName("Noise Sensor");
        node.setLabel("Captured sensor");
        snapshot = new BoardSemanticSnapshotDto(List.of(node), List.of(), List.of(), List.of(),
                List.of(sensorTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    assertTrue(invocation.<String>getArgument(1).contains("captured-node"));
                    snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of());
                    return """
                            {"scenarioName":"Captured template","scene":{
                              "devices":[{"id":"sensor","templateName":"Noise Sensor","label":"Sensor"}],
                              "environmentVariables":[],"rules":[],"specs":[]}}
                            """;
                });

        ScenarioRecommendationResponseDto response = service.recommend(1L,
                objectMapper.readValue(DEFAULT_ARGS, ScenarioRecommendationRequestDto.class));

        assertEquals(1, response.getScene().getDevices().size());
        assertEquals("Noise Sensor", response.getScene().getDevices().get(0).getTemplateName());
        verify(boardStorageService).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);
    }

    @Test
    void definition_requiresExplicitObjectiveTargetsAndCaps() {
        assertEquals(
                List.of("minDevices", "minRules", "minSpecs", "maxDevices", "maxRules", "maxSpecs"),
                tool.getDefinition().parameters().required);
    }

    @Test
    void explicitTemplateScopeRetainsCompleteCapabilitiesAndValidatesAgainstThatScope() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(),
                List.of(sensorTemplate(), lightTemplate(), contentTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    String prompt = invocation.getArgument(1);
                    int start = prompt.indexOf("[{\"name\"");
                    int end = prompt.indexOf("\n\n## 场景要求", start);
                    JsonNode templates = objectMapper.readTree(prompt.substring(start, end));
                    assertEquals(2, templates.size(), "Only explicitly selected templates enter the model context");
                    assertEquals("Noise Sensor", templates.get(0).path("name").asText());
                    assertEquals(objectMapper.valueToTree(cn.edu.nju.Iot_Verify.component.aitool.RecommendationCapabilityView
                            .fromManifest(lightTemplate().getManifest())), templates.get(1).path("capabilities"));
                    return """
                            {"scenarioName":"Scoped","scene":{"devices":[
                             {"id":"light","templateName":"Light","label":"Light","state":"off"},
                             {"id":"phone","templateName":"Phone","label":"Phone"}],"environmentVariables":[],"rules":[],"specs":[]}}
                            """;
                });
        ScenarioRecommendationRequestDto request = objectMapper.readValue(DEFAULT_ARGS, ScenarioRecommendationRequestDto.class);
        request.setTemplateNames(List.of(" light ", "Noise Sensor"));
        ScenarioRecommendationResponseDto result = service.recommend(1L, request);
        assertEquals(1, result.getScene().getDevices().size());
        assertEquals("Light", result.getScene().getDevices().get(0).getTemplateName());
        assertEquals(1, result.getFilteredCount());
        assertEquals("unknownTemplate", result.getFilteredItems().get(0).getReasonCode());
        verify(boardStorageService).getSemanticSnapshot(1L);
    }

    @Test
    void toolAcceptsExplicitTemplateNamesWithoutReplacingThemWithTheWholeCatalog() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(),
                List.of(sensorTemplate(), lightTemplate(), contentTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"scenarioName":"Scoped","scene":{"devices":[{"id":"light","templateName":"Light","label":"Light","state":"off"}],
                         "environmentVariables":[],"rules":[],"specs":[]}}
                        """);
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(DEFAULT_ARGS);
        args.putArray("templateNames").add("Light");
        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));
        assertEquals("Scoped", result.path("scenarioName").asText());
        assertEquals(1, result.path("scene").path("devices").size());
        verify(promptCompletionService).completeRecommendation(anyString(), argThat(prompt ->
                prompt.contains("\"name\":\"Light\"") && !prompt.contains("\"name\":\"Noise Sensor\"")
                        && !prompt.contains("\"name\":\"Phone\"")), anyDouble(), anyInt());
    }

    @Test
    void unknownScopedTemplateStopsBeforeAnyModelCall() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate()));
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(DEFAULT_ARGS);
        args.putArray("templateNames").add("Light").add("Missing template");
        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));
        assertEquals(422, result.path("status").asInt());
        assertTrue(result.path("error").asText().contains("templateNames"));
        verify(promptCompletionService, never()).completeRecommendation(anyString(), anyString(), anyDouble(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[4]", "[\"\"]", "[\"Light\",\"Light\"]"})
    void malformedTemplateScopeIsRejectedBeforeReadingOrCallingTheModel(String names) throws Exception {
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(DEFAULT_ARGS);
        args.set("templateNames", objectMapper.readTree(names));
        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));
        assertEquals(400, result.path("status").asInt());
        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("error").asText().contains("templateNames"));
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
        verify(promptCompletionService, never()).completeRecommendation(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_rejectsUnknownRequestFieldBeforeReadingBoardOrCallingAi() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute("{\"maxSpecification\":3}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("$ contains unsupported field(s): maxSpecification.", json.path("error").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_withUnparseableAiResponse_returnsUpstreamErrorNotEmptyScene() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("not-json");

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        assertEquals("AI_RESPONSE_INVALID", json.path("errorCode").asText());
        assertEquals(502, json.path("status").asInt());
        assertEquals("response_parse", json.path("phase").asText());
    }

    @Test
    void execute_whenAiReturnsNoSceneItems_reportsNoFiltering() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName":"Empty draft",
                          "rationale":"No items proposed",
                          "scene":{"devices":[],"environmentVariables":[],"rules":[],"specs":[]}
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        assertEquals(0, json.path("rawCandidateCount").asInt());
        assertEquals(0, json.path("filteredCount").asInt());
        assertEquals("PARTIAL", json.path("objectiveStatus").asText());
        assertEquals(List.of("NO_DEVICES", "NO_AUTOMATION_RULES", "NO_SPECIFICATIONS"),
                json.path("objectiveIssues").findValuesAsText("code"));
        assertFalse(json.path("verificationReady").asBoolean());
        assertEquals("NO_DEVICES", json.path("readinessIssues").get(0).path("code").asText());
        assertEquals("NO_SPECIFICATIONS", json.path("readinessIssues").get(1).path("code").asText());
        assertTrue(json.path("message").asText().contains("returned no scene-item candidates"));
        assertFalse(json.has("draftStored"));
        assertFalse(json.has("previousDraftRetained"));
    }

    @Test
    void execute_whenEmptyRecommendationRetainsPreviousDraft_reportsItExplicitly() throws Exception {
        UserContextHolder.setChatSessionId("session-1");
        draftStore.saveDraft(1L, "session-1", "Previous", objectMapper.readTree(
                "{\"devices\":[{\"id\":\"device_1\"}],\"rules\":[],\"specs\":[]}"));
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of());

        JsonNode json = objectMapper.readTree(tool.execute("""
                {"minDevices":1,"minRules":1,"minSpecs":1,
                 "maxDevices":6,"maxRules":5,"maxSpecs":5,"language":"en"}
                """));

        assertFalse(json.path("draftStored").asBoolean());
        assertTrue(json.path("previousDraftRetained").asBoolean());
        assertTrue(json.path("message").asText().contains("previous valid draft"));
        assertEquals("Previous", draftStore.latestDraft(1L, "session-1").orElseThrow().scenarioName());
    }

    @Test
    void execute_whenResultExceedsLimit_doesNotReplacePreviousDraft() throws Exception {
        UserContextHolder.setChatSessionId("session-1");
        draftStore.saveDraft(1L, "session-1", "Previous", objectMapper.readTree(
                "{\"devices\":[{\"id\":\"device_1\"}],\"rules\":[],\"specs\":[]}"));
        DeviceTemplateDto oversizedTemplate = sensorTemplate();
        oversizedTemplate.getManifest().setIcon(
                "data:image/svg+xml;base64," + "A".repeat(6_000));
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(oversizedTemplate));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName":"Oversized draft",
                          "scene":{
                            "devices":[{"id":"sensor","templateName":"Noise Sensor","label":"Sensor"}],
                            "environmentVariables":[],
                            "rules":[],
                            "specs":[]
                          }
                        }
                        """);
        chatExecutionConfig.setMaxToolResultBytes(4_096);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        assertEquals("TOOL_RESULT_TOO_LARGE", json.path("errorCode").asText());
        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertFalse(json.path("mutationMayHaveCommitted").asBoolean());
        assertFalse(json.path("draftStored").asBoolean());
        assertTrue(json.path("previousDraftRetained").asBoolean());
        assertTrue(json.path("resultBytes").asInt() > json.path("maxResultBytes").asInt());
        assertEquals("Previous", draftStore.latestDraft(1L, "session-1").orElseThrow().scenarioName());
    }

    @Test
    void execute_generatesCoupledSceneAndKeepsPrefixLikeNamesLiteral() throws Exception {
        UserContextHolder.setChatSessionId("session-1");
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate(), lightTemplate()));
        when(promptCompletionService.completeRecommendation(
                anyString(),
                contains("全量替换 scene 草案"),
                anyDouble(),
                anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Prefix-safe safety scene",
                          "rationale": "The rule and spec both reference the same sensor and light.",
                          "scene": {
                            "devices": [
                              {"id":"sensor_1","templateName":"Noise Sensor","label":"Noise Sensor"},
                              {"id":"light_1","templateName":"Light","label":"Light","state":"off"}
                            ],
                            "environmentVariables": [
                              {"name":"a_noise","value":"high","trust":"trusted","privacy":"public"},
                              {"name":"a_noise","value":"low","trust":"untrusted","privacy":"private"}
                            ],
                            "rules": [
                              {
                                "name":"High noise turns on light",
                                "sources":[{"fromId":"sensor_1","fromApi":"a_noise","itemType":"variable","relation":"=","value":"high"}],
                                "toId":"light_1",
                                "toApi":"turnOn"
                              }
                            ],
                            "specs": [
                              {
                                "id":"spec_1",
                                "name": "Safety requirement", "templateId":"4",
                                "templateLabel":"Immediate response",
                                "ifConditions":[{"deviceId":"sensor_1","targetType":"variable","variableSource":"environment","key":"a_noise","relation":"=","value":"high"}],
                                "thenConditions":[{"deviceId":"light_1","targetType":"state","key":"state","relation":"=","value":"on"}]
                              }
                            ]
                          }
                        }
                        """);

        String result = tool.execute("""
                {
                  "minDevices": 2,
                  "minRules": 1,
                  "minSpecs": 1,
                  "maxDevices": 6,
                  "maxRules": 5,
                  "maxSpecs": 5,
                  "language": "en",
                  "userRequirement": "a_noise is a real environment variable name"
                }
                """);

        JsonNode json = objectMapper.readTree(result);
        JsonNode scene = json.path("scene");

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        // Named specifications require version 6; the frontend importer rejects older scene formats.
        assertEquals(6, scene.path("version").asInt());
        assertEquals(2, scene.path("devices").size());
        assertFalse(scene.path("devices").get(0).has("state"));
        assertEquals(1, scene.path("rules").size());
        assertEquals(1, scene.path("specs").size());
        assertEquals("COMPLETE", json.path("objectiveStatus").asText());
        assertTrue(json.path("objectiveIssues").isEmpty());
        assertEquals(2, json.path("objectiveTargets").path("minDevices").asInt());
        assertEquals(4, json.path("requestedCount").asInt());
        assertTrue(json.path("verificationReady").asBoolean());
        assertTrue(json.path("draftStored").asBoolean());
        assertFalse(json.path("previousDraftRetained").asBoolean());
        assertEquals(0, json.path("readinessIssues").size());
        assertEquals("FILTERED_CANDIDATES",
                json.path("semanticWarnings").get(0).path("code").asText());
        assertTrue(json.path("rationale").asText().contains("final canonical draft"));
        assertTrue(json.path("rationale").asText().contains("does not claim"));
        assertFalse(json.path("rationale").asText().contains("both reference the same sensor and light"));
        assertEquals("a_noise", scene.path("environmentVariables").get(0).path("name").asText());
        assertEquals("a_noise", scene.path("rules").get(0).path("sources").get(0).path("fromApi").asText());
        assertEquals("device_1", scene.path("specs").get(0).path("ifConditions").get(0).path("deviceId").asText());
        assertEquals("a_noise", scene.path("specs").get(0).path("ifConditions").get(0).path("key").asText());
        assertEquals("device_2", scene.path("specs").get(0).path("thenConditions").get(0).path("deviceId").asText());
        assertFalse(scene.path("templates").get(0).has("defaultTemplate"));
        assertFalse(scene.path("specs").get(0).has("id"));
        assertFalse(scene.path("specs").get(0).has("templateLabel"));
        assertFalse(scene.path("specs").get(0).has("devices"));
        assertFalse(scene.path("specs").get(0).path("ifConditions").get(0).has("id"));
        assertFalse(scene.path("specs").get(0).path("ifConditions").get(0).has("side"));
        assertFalse(scene.path("specs").get(0).path("ifConditions").get(0).has("deviceLabel"));
        assertTrue(json.path("filteredItems").findValuesAsText("reasonCode")
                .contains("duplicateEnvironmentVariable"));
        assertTrue(json.path("message").asText().contains("importable scene draft"));
        verify(promptCompletionService).completeRecommendation(
                argThat(system -> system.contains("不要声称它安全、完整、已验证或已应用")
                        && system.contains("itemType=api 时")
                        && system.contains("必须省略 relation 和 value")
                        // The prompt must not forbid the `= TRUE` spelling outright: normalizeRuleSources
                        // accepts it, strips it, and reports `apiEventSyntaxNormalized`. It states the
                        // preference and names the shapes that really are fatal.
                        && system.contains("会被规范化并记入 adjustedItems")
                        && system.contains("写 FALSE 或用其他关系符会让整条规则被丢弃")
                        && system.contains("\"itemType\": \"api\"")),
                argThat(prompt -> prompt.contains("尚未形式化验证")
                        && prompt.contains("不会自动应用")
                        && prompt.contains("设备数量: 至少 2 个，最多 6 个")),
                anyDouble(),
                anyInt());
        assertTrue(json.path("message").asText().contains("not been formally verified"));
        assertFalse(json.path("message").asText().contains("complete scenario"));
        AiScenarioDraftStore.DraftSnapshot stored = draftStore
                .latestDraft(1L, "session-1")
                .orElseThrow();
        assertEquals("Prefix-safe safety scene", stored.scenarioName());
        assertEquals(2, stored.scene().path("devices").size());
    }

    @Test
    void execute_normalizesEquivalentApiEventBooleanSyntaxButRejectsDifferentSemantics() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(eventSourceTemplate(), eventTargetTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scene": {
                            "devices": [
                              {"id":"source","templateName":"Event Source","label":"Event Source","state":"idle"},
                              {"id":"target","templateName":"Event Target","label":"Event Target","state":"off"}
                            ],
                            "rules": [
                              {
                                "name":"Equivalent API event syntax",
                                "sources":[{
                                  "fromId":"source","fromApi":"notify","itemType":"api",
                                  "relation":"=","value":"true"
                                }],
                                "toId":"target","toApi":"activate"
                              },
                              {
                                "name":"Different API boolean semantics",
                                "sources":[{
                                  "fromId":"source","fromApi":"notify","itemType":"api",
                                  "relation":"=","value":"FALSE"
                                }],
                                "toId":"target","toApi":"activate"
                              }
                            ],
                            "specs": []
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));
        JsonNode rules = json.path("scene").path("rules");

        assertEquals(1, rules.size());
        assertEquals("Equivalent API event syntax", rules.get(0).path("name").asText());
        JsonNode source = rules.get(0).path("sources").get(0);
        assertEquals("api", source.path("itemType").asText());
        assertFalse(source.has("relation"));
        assertFalse(source.has("value"));
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("invalidRuleSources",
                json.path("filteredItems").get(0).path("reasonCode").asText());
        assertTrue(json.path("adjustedItems").findValuesAsText("reasonCode")
                .contains("apiEventSyntaxNormalized"));
        JsonNode adjustment = json.path("adjustedItems").findParents("reasonCode").stream()
                .filter(item -> "apiEventSyntaxNormalized".equals(item.path("reasonCode").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals("notify", adjustment.path("appliedValues").path("sourceApi").asText());
        assertEquals("=", adjustment.path("appliedValues").path("removedRelation").asText());
        assertEquals("TRUE", adjustment.path("appliedValues").path("removedValue").asText());
    }

    @Test
    void execute_returnsFilteredItemsForInvalidSceneCandidates() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate(), lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Partially invalid scene",
                          "scene": {
                            "devices": [
                              {"id":"sensor_1","templateName":"Noise Sensor","label":"Noise Sensor"},
                              {"id":"ghost_1","templateName":"Ghost Device","label":"Ghost Device","state":"on"},
                              {"id":"light_1","templateName":"Light","label":"Light","state":"off"}
                            ],
                            "rules": [
                              {
                                "name":"Ghost triggers light",
                                "sources":[{"fromId":"ghost_1","fromApi":"motion","itemType":"variable","relation":"=","value":"yes"}],
                                "toId":"light_1",
                                "toApi":"turnOn"
                              },
                              {
                                "name":"Noise turns on light",
                                "sources":[{"fromId":"sensor_1","fromApi":"a_noise","itemType":"variable","relation":"=","value":"high"}],
                                "toId":"light_1",
                                "toApi":"turnOn"
                              }
                            ],
                            "specs": [
                              {"id":"spec_bad","name": "Safety requirement", "templateId":"99","templateLabel":"Unknown spec"}
                            ]
                          }
                        }
                        """);

        String result = tool.execute("""
                {"minDevices":1,"minRules":1,"minSpecs":1,
                 "maxDevices":5,"maxRules":5,"maxSpecs":5}
                """);
        JsonNode json = objectMapper.readTree(result);

        assertEquals(4, json.path("count").asInt(),
                "Final scene count includes the required shared environment item");
        assertEquals(3, json.path("validatedCount").asInt());
        assertEquals(3, json.path("filteredCount").asInt());
        assertEquals(6, json.path("rawCandidateCount").asInt());
        assertEquals(6, json.path("inspectedCount").asInt());
        assertEquals(0, json.path("truncatedCount").asInt());
        assertEquals("device", json.path("filteredItems").get(0).path("type").asText());
        assertEquals("unknownTemplate", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("rule", json.path("filteredItems").get(1).path("type").asText());
        assertEquals("invalidRuleSources", json.path("filteredItems").get(1).path("reasonCode").asText());
        assertEquals("specification", json.path("filteredItems").get(2).path("type").asText());
        assertEquals("invalidSpecTemplateId", json.path("filteredItems").get(2).path("reasonCode").asText());
    }

    @Test
    void execute_filtersWholeRuleOrSpecWhenAnyNestedCandidateWouldBeDropped() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate(), lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Partially dropped nested candidates",
                          "scene": {
                            "devices": [
                              {"id":"sensor_1","templateName":"Noise Sensor","label":"Noise Sensor"},
                              {"id":"light_1","templateName":"Light","label":"Light","state":"off"}
                            ],
                            "rules": [
                              {
                                "name":"Noise or missing variable turns on light",
                                "sources":[
                                  {"fromId":"sensor_1","fromApi":"a_noise","itemType":"variable","relation":"=","value":"high"},
                                  {"fromId":"sensor_1","fromApi":"missing_variable","itemType":"variable","relation":"=","value":"high"}
                                ],
                                "toId":"light_1",
                                "toApi":"turnOn"
                              }
                            ],
                            "specs": [
                              {
                                "id":"spec_partial",
                                "name": "Safety requirement", "templateId":"1",
                                "templateLabel":"Always",
                                "aConditions":[
                                  {"deviceId":"sensor_1","targetType":"variable","variableSource":"environment","key":"a_noise","relation":"=","value":"high"},
                                  {"deviceId":"sensor_1","targetType":"variable","variableSource":"environment","key":"missing_variable","relation":"=","value":"high"}
                                ]
                              }
                            ]
                          }
                        }
                        """);

        String result = tool.execute("""
                {"minDevices":1,"minRules":1,"minSpecs":1,
                 "maxDevices":5,"maxRules":5,"maxSpecs":5}
                """);
        JsonNode json = objectMapper.readTree(result);
        JsonNode scene = json.path("scene");

        assertEquals(3, json.path("count").asInt(),
                "Final scene count includes the required shared environment item");
        assertEquals(2, scene.path("devices").size());
        assertTrue(scene.path("rules").isEmpty());
        assertTrue(scene.path("specs").isEmpty());
        assertEquals(2, json.path("filteredCount").asInt());
        assertEquals("rule", json.path("filteredItems").get(0).path("type").asText());
        assertEquals("invalidRuleSources", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("specification", json.path("filteredItems").get(1).path("type").asText());
        assertEquals("invalidSpecConditions", json.path("filteredItems").get(1).path("reasonCode").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingSource", "unknownVariable", "invalidValue", "extraScope", "unknownDevice"})
    void rejectedSpecConditionsExplainTheirLocationAndCauseWithoutKeepingAPartialSpec(String invalid) throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate()));
        var response = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree("""
                {"scene":{"devices":[{"id":"sensor_1","templateName":"Noise Sensor","label":"声音传感器"}],
                  "rules":[],"specs":[{"name": "Safety requirement", "templateId":"1","aConditions":[{"deviceId":"sensor_1",
                    "targetType":"variable","key":"a_noise","variableSource":"reported","relation":"=","value":"high"}]}]}}
                """);
        var condition = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("scene").path("specs")
                .get(0).path("aConditions").get(0);
        String explanation = switch (invalid) {
            case "missingSource" -> { condition.remove("variableSource"); yield "请明确变量来源"; }
            case "unknownVariable" -> { condition.put("key", "missing_variable"); yield "请按设备模板声明选择"; }
            case "invalidValue" -> { condition.put("value", "outside_the_declared_domain"); yield "请按设备模板声明选择"; }
            case "extraScope" -> { condition.put("propertyScope", "variable"); yield "普通条件多写了标签范围"; }
            default -> { condition.put("deviceId", "not_in_this_draft"); yield "引用的设备不在草稿中"; }
        };
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(response.toString());
        var args = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(DEFAULT_ARGS);
        args.put("language", "zh-CN");
        JsonNode result = objectMapper.readTree(tool.execute(args.toString()));

        assertTrue(result.path("scene").path("specs").isEmpty());
        assertEquals("PARTIAL", result.path("objectiveStatus").asText());
        JsonNode rejected = result.path("filteredItems").get(0);
        assertEquals("invalidSpecConditions", rejected.path("reasonCode").asText());
        assertTrue(rejected.path("reason").asText().contains("检查条件第 1 项："));
        assertTrue(rejected.path("reason").asText().contains(explanation));
        verify(promptCompletionService).completeRecommendation(anyString(), anyString(), anyDouble(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void execute_preservesLabelsButFiltersWholeValueCandidatesForAffectOnlyDeclarations(boolean reads) throws Exception {
        DeviceTemplateDto sensor = sensorTemplate();
        sensor.getManifest().getInternalVariables().get(0).setReads(reads);
        sensor.getManifest().getInternalVariables().get(0).setFalsifiableWhenCompromised(false);
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(),
                List.of(sensor, lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"scenarioName":"Read capability","scene":{
                          "devices":[{"id":"sensor_1","templateName":"Noise Sensor","label":"Sensor"},
                                     {"id":"light_1","templateName":"Light","label":"Light","state":"off"}],
                          "environmentVariables":[{"name":"a_noise","value":"low","trust":"trusted","privacy":"public"}],
                          "rules":[{"name":"Noise turns on light","sources":[
                            {"fromId":"sensor_1","fromApi":"a_noise","itemType":"variable","relation":"=","value":"high"},
                            {"fromId":"light_1","fromApi":"SwitchState","itemType":"mode","relation":"=","value":"off"}],
                            "toId":"light_1","toApi":"turnOn"}],
                          "specs":[{"name": "Safety requirement", "templateId":"3","aConditions":[
                            {"deviceId":"sensor_1","targetType":"variable","key":"a_noise","variableSource":"reported","relation":"=","value":"high"},
                            {"deviceId":"sensor_1","targetType":"privacy","key":"a_noise","propertyScope":"variable","relation":"=","value":"public"}]},
                            {"name": "Safety requirement", "templateId":"3","aConditions":[
                            {"deviceId":"sensor_1","targetType":"trust","key":"a_noise","propertyScope":"variable","relation":"=","value":"trusted"},
                            {"deviceId":"sensor_1","targetType":"privacy","key":"a_noise","propertyScope":"variable","relation":"=","value":"public"}]}]}}
                        """);

        JsonNode result = objectMapper.readTree(tool.execute(DEFAULT_ARGS));
        JsonNode scene = result.path("scene");

        assertEquals(2, scene.path("devices").size());
        assertEquals(1, scene.path("environmentVariables").size());
        assertEquals(reads ? 1 : 0, scene.path("rules").size());
        assertEquals(reads ? 2 : 1, scene.path("specs").size());
        assertEquals(reads ? 0 : 2, result.path("filteredCount").asInt());
        JsonNode labelConditions = scene.path("specs").get(reads ? 1 : 0).path("aConditions");
        assertEquals(2, labelConditions.size());
        assertEquals("trust", labelConditions.get(0).path("targetType").asText());
        assertEquals("privacy", labelConditions.get(1).path("targetType").asText());
        if (reads) {
            assertEquals(2, scene.path("rules").get(0).path("sources").size());
            assertEquals(2, scene.path("specs").get(0).path("aConditions").size());
        } else {
            assertEquals("invalidRuleSources", result.path("filteredItems").get(0).path("reasonCode").asText());
            assertEquals("invalidSpecConditions", result.path("filteredItems").get(1).path("reasonCode").asText());
        }
        verify(boardStorageService).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);
    }

    @Test
    void execute_filtersUnknownEnvironmentVariablesInsteadOfReturningDriftingScene() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(sensorTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Unknown environment variable",
                          "scene": {
                            "devices": [
                              {"id":"sensor_1","templateName":"Noise Sensor","label":"Noise Sensor"}
                            ],
                            "environmentVariables": [
                              {"name":"weather","value":"rainy","trust":"trusted","privacy":"public"}
                            ],
                            "rules": [],
                            "specs": []
                          }
                        }
                        """);

        String result = tool.execute("""
                {"minDevices":1,"minRules":1,"minSpecs":1,
                 "maxDevices":5,"maxRules":5,"maxSpecs":5}
                """);
        JsonNode json = objectMapper.readTree(result);
        JsonNode scene = json.path("scene");

        assertEquals(2, json.path("count").asInt(),
                "Final scene count includes the required shared environment item");
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("environment", json.path("filteredItems").get(0).path("type").asText());
        assertEquals("unknownEnvironmentVariable", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("weather", json.path("filteredItems").get(0).path("label").asText());
        assertEquals(1, scene.path("environmentVariables").size());
        assertEquals("a_noise", scene.path("environmentVariables").get(0).path("name").asText());
    }

    @Test
    void execute_filtersDeviceWithInvalidInitialRuntimeInsteadOfDefaultingIt() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Invalid device runtime",
                          "scene": {
                            "devices": [
                              {"id":"light_bad","templateName":"Light","label":"Bad Light","state":"dimmed","currentStateTrust":"trusted"},
                              {"id":"light_trust_bad","templateName":"Light","label":"Bad Trust Light","state":"off","currentStateTrust":"maybe"},
                              {"id":"light_ok","templateName":"Light","label":"Ok Light","state":"on","currentStateTrust":"trusted"}
                            ],
                            "rules": [],
                            "specs": []
                          }
                        }
                        """);

        String result = tool.execute("""
                {"minDevices":1,"minRules":1,"minSpecs":1,
                 "maxDevices":5,"maxRules":5,"maxSpecs":5}
                """);
        JsonNode json = objectMapper.readTree(result);
        JsonNode scene = json.path("scene");

        assertEquals(1, scene.path("devices").size());
        assertEquals("device_1", scene.path("devices").get(0).path("id").asText());
        assertEquals("on", scene.path("devices").get(0).path("state").asText());
        assertEquals(2, json.path("filteredCount").asInt());
        assertEquals("invalidDeviceRuntime", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("invalidDeviceRuntime", json.path("filteredItems").get(1).path("reasonCode").asText());
    }

    @Test
    void execute_rejectsIncompleteRuleContentFlowInsteadOfSilentlyDroppingIt() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate(), contentTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Incomplete content flow",
                          "scene": {
                            "devices": [
                              {"id":"light_1","templateName":"Light","label":"Light","state":"off"},
                              {"id":"phone_1","templateName":"Phone","label":"Phone"}
                            ],
                            "rules": [
                              {
                                "name":"Missing content item",
                                "sources":[{"fromId":"light_1","fromApi":"brightness","itemType":"variable","relation":">","value":"50"}],
                                "toId":"light_1",
                                "toApi":"turnOn",
                                "contentDevice":"phone_1"
                              }
                            ],
                            "specs": []
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        assertEquals(2, json.path("scene").path("devices").size());
        assertTrue(json.path("scene").path("rules").isEmpty());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("invalidRuleContent",
                json.path("filteredItems").get(0).path("reasonCode").asText());
    }

    @Test
    void execute_preservesValidatedLocalRuntimeLabelsAndRejectsEnvironmentOverridesOnDevices() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate(), sensorTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Runtime labels",
                          "scene": {
                            "devices": [
                              {
                                "id":"light_1",
                                "templateName":"Light",
                                "label":"Desk Light",
                                "state":"on",
                                "currentStateTrust":"TRUSTED",
                                "currentStatePrivacy":"PRIVATE",
                                "variables":[{"name":"brightness","value":"80","trust":"UNTRUSTED"}],
                                "privacies":[{"name":"brightness","privacy":"PRIVATE"}]
                              },
                              {
                                "id":"sensor_bad",
                                "templateName":"Noise Sensor",
                                "label":"Bad Sensor",
                                "state":"Working"
                              }
                            ],
                            "rules": [],
                            "specs": [{
                              "name": "Safety requirement", "templateId":"1",
                              "templateLabel":"Current state sensitivity",
                              "aConditions":[{
                                "deviceId":"light_1",
                                "targetType":"privacy",
                                "propertyScope":"state",
                                "key":"SwitchState",
                                "relation":"=",
                                "value":"PRIVATE"
                              }],
                              "ifConditions":[],
                              "thenConditions":[]
                            }]
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));
        JsonNode devices = json.path("scene").path("devices");

        assertEquals(1, devices.size());
        assertEquals("trusted", devices.get(0).path("currentStateTrust").asText());
        assertEquals("private", devices.get(0).path("currentStatePrivacy").asText());
        assertEquals("brightness", devices.get(0).path("variables").get(0).path("name").asText());
        assertEquals("80", devices.get(0).path("variables").get(0).path("value").asText());
        assertEquals("untrusted", devices.get(0).path("variables").get(0).path("trust").asText());
        assertEquals("private", devices.get(0).path("privacies").get(0).path("privacy").asText());
        JsonNode propertyCondition = json.path("scene").path("specs").get(0).path("aConditions").get(0);
        assertEquals("state", propertyCondition.path("propertyScope").asText());
        assertEquals("SwitchState", propertyCondition.path("key").asText());
        assertEquals("private", propertyCondition.path("value").asText());
        assertFalse(propertyCondition.toString().contains("SwitchState_"));
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("invalidDeviceRuntime", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("Bad Sensor", json.path("filteredItems").get(0).path("label").asText());
    }

    @Test
    void execute_filtersDuplicateIdsAndInvalidProvidedLayoutInsteadOfSilentlyRewriting() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Invalid identities and layout",
                          "scene": {
                            "devices": [
                              {"id":"light_1","templateName":"Light","label":"First","state":"off"},
                              {"id":"light_1","templateName":"Light","label":"Duplicate","state":"on"},
                              {"id":"light_small","templateName":"Light","label":"Too small","state":"off","width":10},
                              {"id":"light_bad_pos","templateName":"Light","label":"Bad position","state":"off","position":{"x":"left","y":20}},
                              {"id":"light_far","templateName":"Light","label":"Outside portable canvas","state":"off","position":{"x":1000001,"y":20}}
                            ],
                            "rules": [],
                            "specs": []
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        assertEquals(1, json.path("scene").path("devices").size());
        assertEquals("device_1", json.path("scene").path("devices").get(0).path("id").asText());
        assertEquals(4, json.path("filteredCount").asInt());
        assertEquals("duplicateDeviceId", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("invalidDeviceLayout", json.path("filteredItems").get(1).path("reasonCode").asText());
        assertEquals("invalidDeviceLayout", json.path("filteredItems").get(2).path("reasonCode").asText());
        assertEquals("invalidDeviceLayout", json.path("filteredItems").get(3).path("reasonCode").asText());
    }

    @Test
    void execute_rewritesAiAliasAcrossDependenciesAndReportsEveryAppliedDefault() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scenarioName": "Literal identity",
                          "scene": {
                            "devices": [{
                              "id":"Hall Light #1",
                              "templateName":"Light",
                              "label":"Hall Light",
                              "variables":[{"name":"brightness","value":"80"}]
                            }],
                            "rules": [{
                              "name":"Bright hall light stays on",
                              "sources":[{"fromId":"Hall Light #1","fromApi":"brightness","itemType":"variable","relation":">","value":"50"}],
                              "toId":"Hall Light #1",
                              "toApi":"turnOn"
                            }],
                            "specs": [{
                              "name": "Safety requirement", "templateId":"1",
                              "aConditions":[{"deviceId":"Hall Light #1","targetType":"state","key":"state","relation":"=","value":"on"}],
                              "ifConditions":[],
                              "thenConditions":[]
                            }]
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));
        JsonNode scene = json.path("scene");

        assertEquals("device_1", scene.path("devices").get(0).path("id").asText());
        assertEquals("device_1", scene.path("rules").get(0).path("sources").get(0).path("fromId").asText());
        assertEquals("device_1", scene.path("rules").get(0).path("toId").asText());
        assertEquals("device_1", scene.path("specs").get(0).path("aConditions").get(0).path("deviceId").asText());
        assertEquals(3, json.path("validatedCount").asInt());
        assertEquals(1, json.path("adjustedCount").asInt());
        JsonNode defaults = json.path("adjustedItems").get(0).path("appliedValues");
        assertEquals("off", defaults.path("state").asText());
        assertEquals(176, defaults.path("width").asInt());
        assertEquals(128, defaults.path("height").asInt());
        assertFalse(defaults.has("currentStateTrust"));
        assertFalse(defaults.has("currentStatePrivacy"));
        assertFalse(defaults.has("variables.brightness.trust"));
        assertFalse(defaults.has("privacies.brightness.privacy"));
        assertFalse(scene.path("devices").get(0).has("currentStateTrust"));
        assertFalse(scene.path("devices").get(0).has("currentStatePrivacy"));
        assertFalse(scene.path("devices").get(0).has("privacies"));
    }

    @Test
    void execute_keepsRuleWhenOptionalDisplayNameIsMissing() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scene": {
                            "devices": [{"id":"light_1","templateName":"Light","label":"Light","state":"off"}],
                            "rules": [{
                              "sources":[{"fromId":"light_1","fromApi":"brightness","itemType":"variable","relation":">","value":"50"}],
                              "toId":"light_1",
                              "toApi":"turnOn"
                            }],
                            "specs": []
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        JsonNode rule = json.path("scene").path("rules").get(0);
        assertFalse(rule.has("name"));
        assertEquals("device_1", rule.path("toId").asText());
        assertEquals(0, json.path("filteredCount").asInt());
    }

    @Test
    void execute_rewritesAiAliasesSoModelNormalizationCannotMergeDevices() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(lightTemplate()));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scene": {
                            "devices": [
                              {"id":"Hall Light","templateName":"Light","label":"Hall Light A","state":"off"},
                              {"id":"hall-light","templateName":"Light","label":"Hall Light B","state":"on"}
                            ],
                            "rules": [],
                            "specs": []
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        assertEquals(2, json.path("scene").path("devices").size());
        assertEquals("device_1", json.path("scene").path("devices").get(0).path("id").asText());
        assertEquals("device_2", json.path("scene").path("devices").get(1).path("id").asText());
        assertEquals(0, json.path("filteredCount").asInt());
    }

    @Test
    void execute_revalidatesTemplate7ApiConditionByStableDeviceReference() throws Exception {
        DeviceTemplateDto notifier = new DeviceTemplateDto();
        notifier.setName("Notifier");
        notifier.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("Notifier")
                .modes(List.of("Status"))
                .initState("idle")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("idle").build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("notified").build()))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("notify").signal(true).endState("notified").build()))
                .build());
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(notifier));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "scene": {
                            "devices": [{
                              "id":"Notifier response alias",
                              "templateName":"Notifier",
                              "label":"Entry notifier",
                              "state":"idle"
                            }],
                            "rules": [],
                            "specs": [{
                              "name": "Safety requirement", "templateId":"7",
                              "aConditions":[{
                                "deviceId":"Notifier response alias",
                                "targetType":"api",
                                "key":"notify"
                              }],
                              "ifConditions":[],
                              "thenConditions":[]
                            }]
                          }
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute(DEFAULT_ARGS));

        assertEquals(1, json.path("scene").path("specs").size());
        assertEquals("device_1", json.path("scene").path("specs").get(0)
                .path("aConditions").get(0).path("deviceId").asText());
        assertEquals("=", json.path("scene").path("specs").get(0)
                .path("aConditions").get(0).path("relation").asText());
        assertEquals("TRUE", json.path("scene").path("specs").get(0)
                .path("aConditions").get(0).path("value").asText());
        assertEquals(0, json.path("filteredCount").asInt());
    }

    @Test
    void execute_rejectsMissingExplicitTargetBeforeLoadingBoard() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute("""
                {"minRules":1,"minSpecs":1,
                 "maxDevices":6,"maxRules":5,"maxSpecs":5}
                """));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("minDevices is required.", json.path("error").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
    }

    @Test
    void execute_rejectsMinimumAboveMaximumBeforeLoadingBoard() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute("""
                {"minDevices":4,"minRules":1,"minSpecs":1,
                 "maxDevices":3,"maxRules":5,"maxSpecs":5}
                """));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("Minimum devices must not exceed maximum devices.", json.path("error").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
    }

    @Test
    void execute_rejectsDecimalMaxDevicesBeforeLoadingBoard() throws Exception {
        String result = tool.execute("""
                {"minDevices":1,"minRules":1,"minSpecs":1,
                 "maxDevices":2.5,"maxRules":5,"maxSpecs":5}
                """);
        JsonNode json = objectMapper.readTree(result);

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        assertEquals("maxDevices must be an integer between 1 and 10.", json.path("error").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_rejectsNonStringRequirementInsteadOfCoercingIt() throws Exception {
        JsonNode json = objectMapper.readTree(tool.execute("""
                {"minDevices":1,"minRules":1,"minSpecs":1,
                 "maxDevices":6,"maxRules":5,"maxSpecs":5,"userRequirement":123}
                """));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("userRequirement must be a string.", json.path("error").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"device", "environment", "rule", "specification"})
    void execute_preservesDeclaredEnumCaseAcrossTheScene(String surface) throws Exception {
        JsonNode result = recommendCaseSensitiveScene(surface, "=", "On");
        JsonNode scene = result.path("scene");
        assertTrue(scene.isObject(), result.toString());
        JsonNode value = switch (surface) {
            case "device" -> scene.path("devices").get(0).path("variables").get(0).path("value");
            case "environment" -> scene.path("environmentVariables").get(0).path("value");
            case "rule" -> scene.path("rules").get(0).path("sources").get(0).path("value");
            default -> scene.path("specs").get(0).path("aConditions").get(0).path("value");
        };
        assertEquals("On", value.asText(), result.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"device", "environment", "rule", "specification"})
    void execute_reportsAmbiguousEnumValuesInsteadOfGuessingTheirMeaning(String surface) throws Exception {
        JsonNode result = recommendCaseSensitiveScene(surface, "=", "ON");
        String expectedReason = switch (surface) {
            case "device" -> "invalidDeviceRuntime";
            case "environment" -> "invalidEnvironmentValue";
            case "rule" -> "invalidRuleSources";
            default -> "invalidSpecConditions";
        };
        assertTrue(result.path("filteredItems").findValuesAsText("reasonCode").contains(expectedReason), result.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"rule", "specification"})
    void execute_keepsDistinctEnumValuesInSceneSetConditions(String surface) throws Exception {
        JsonNode result = recommendCaseSensitiveScene(surface, "in", "on,On");
        JsonNode scene = result.path("scene");
        assertTrue(scene.isObject(), result.toString());
        JsonNode value = "rule".equals(surface)
                ? scene.path("rules").get(0).path("sources").get(0).path("value")
                : scene.path("specs").get(0).path("aConditions").get(0).path("value");
        assertEquals("on,On", value.asText(), result.toString());
    }

    private JsonNode recommendCaseSensitiveScene(String surface, String relation, String value) throws Exception {
        var template = cn.edu.nju.Iot_Verify.testutil.CaseSensitiveEnumFixture.template();
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(template));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"scenarioName":"Preserve declared enum values","scene":{
                          "devices":[{"id":"case-sensor","templateName":"CaseSensor","label":"Case sensor",
                            "variables":[{"name":"level","value":"%s"}]}],
                          "environmentVariables":[{"name":"reading","value":"%s"}],
                          "rules":[{"name":"React to the reading","sources":[{"fromId":"case-sensor",
                            "fromApi":"reading","itemType":"variable","relation":"%s","value":"%s"}],
                            "toId":"case-sensor","toApi":"activate"}],
                          "specs":[{"id":"requirement","name": "Safety requirement", "templateId":"3","aConditions":[{"deviceId":"case-sensor",
                            "targetType":"variable","key":"reading","variableSource":"environment",
                            "relation":"%s","value":"%s"}]}]}}
                        """.formatted("device".equals(surface) ? value : "on",
                        "environment".equals(surface) ? value : "on",
                        "rule".equals(surface) ? relation : "=", "rule".equals(surface) ? value : "on",
                        "specification".equals(surface) ? relation : "=", "specification".equals(surface) ? value : "on"));
        return objectMapper.readTree(tool.execute(DEFAULT_ARGS));
    }

    private DeviceTemplateDto sensorTemplate() {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Noise Sensor");
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("Noise Sensor")
                .description("Noise sensor")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("a_noise")
                        .description("Noise level")
                        .isInside(false)
                        .values(List.of("high", "low"))
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .initState("Working")
                .workingStates(List.of(DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                        .name("Working")
                        .build()))
                .build());
        return dto;
    }

    private DeviceTemplateDto lightTemplate() {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Light");
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("Light")
                .description("Light")
                .modes(List.of("SwitchState"))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("brightness")
                        .description("Brightness")
                        .isInside(true)
                        .lowerBound(0)
                        .upperBound(100)
                        .naturalChangeRate("[-1, 1]")
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .initState("off")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("off").build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("on").build()))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("turnOn")
                        .description("Turn on")
                        .signal(false)
                        .acceptsContent(true)
                        .startState("off")
                        .endState("on")
                        .build()))
                .build());
        return dto;
    }

    private DeviceTemplateDto contentTemplate() {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Phone");
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("Phone")
                .description("Phone")
                .initState("on")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("on").build()))
                .contents(List.of(DeviceTemplateDto.DeviceManifest.Content.builder()
                        .name("photo")
                        .privacy("private")
                        .build()))
                .build());
        return dto;
    }

    private DeviceTemplateDto eventSourceTemplate() {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Event Source");
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("Event Source")
                .modes(List.of("Status"))
                .initState("idle")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("idle").build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("notified").build()))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("notify").signal(true).endState("notified").build()))
                .build());
        return dto;
    }

    private DeviceTemplateDto eventTargetTemplate() {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Event Target");
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name("Event Target")
                .modes(List.of("Status"))
                .initState("off")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("off").build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("on").build()))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("activate").signal(true).endState("on").build()))
                .build());
        return dto;
    }
}
