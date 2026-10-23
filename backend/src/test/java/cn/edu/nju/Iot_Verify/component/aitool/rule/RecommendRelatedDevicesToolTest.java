package cn.edu.nju.Iot_Verify.component.aitool.rule;

import cn.edu.nju.Iot_Verify.service.recommendation.DeviceInfoHelper;
import cn.edu.nju.Iot_Verify.service.recommendation.DeviceRecommendationService;
import cn.edu.nju.Iot_Verify.service.recommendation.RecommendationResultParser;
import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RecommendRelatedDevicesToolTest {

    @Mock
    private PromptCompletionService promptCompletionService;
    @Mock
    private DeviceInfoHelper deviceInfoHelper;
    @Mock
    private BoardStorageService boardStorageService;

    private BoardSemanticSnapshotDto snapshot;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RecommendRelatedDevicesTool tool;

    @BeforeEach
    void setUp() {
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of());
        lenient().when(boardStorageService.getSemanticSnapshot(1L)).thenAnswer(invocation -> snapshot);
        tool = new RecommendRelatedDevicesTool(new DeviceRecommendationService(deviceInfoHelper, boardStorageService,
                promptCompletionService, objectMapper, new RecommendationResultParser(objectMapper)), objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void promptAndValidationUseOneCapturedTemplateSetEvenIfBoardChangesDuringCompletion() throws Exception {
        snapshot = new BoardSemanticSnapshotDto(
                List.of(), List.of(), List.of(), List.of(), List.of(template("Camera")));
        tool = new RecommendRelatedDevicesTool(new DeviceRecommendationService(new DeviceInfoHelper(), boardStorageService,
                promptCompletionService, objectMapper, new RecommendationResultParser(objectMapper)), objectMapper);
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    assertTrue(invocation.<String>getArgument(1).contains("Camera"));
                    snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of());
                    return """
                            {"recommendations":[{"templateName":"Camera","suggestedLabel":"Door camera"}]}
                            """;
                });

        JsonNode result = objectMapper.readTree(tool.execute("{}"));

        assertEquals(1, result.path("validatedCount").asInt(), result.toString());
        verify(boardStorageService).getSemanticSnapshot(1L);
        verifyNoMoreInteractions(boardStorageService);
    }

    @Test
    void execute_rejectsUnknownRequestFieldBeforeReadingBoardOrCallingAi() throws Exception {
        JsonNode json = objectMapper.readTree(
                tool.execute("{\"userRequrement\":\"night safety\"}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("$ contains unsupported field(s): userRequrement.", json.path("error").asText());
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void execute_withUnparseableAiResponse_returnsUpstreamError() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(template("Camera")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("not-json");

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals("AI_RESPONSE_INVALID", json.path("errorCode").asText());
        assertEquals(502, json.path("status").asInt());
        assertEquals("response_parse", json.path("phase").asText());
    }

    @Test
    void execute_doesNotLogRejectedModelCandidateContent() throws Exception {
        String sensitive = "private-template-value-never-log";
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(template("Camera")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {
                              "templateName": "private-template-value-never-log",
                              "suggestedLabel": "private-template-value-never-log"
                            }
                          ]
                        }
                        """);

        try (LogCapture logs = LogCapture.forClass(DeviceRecommendationService.class)) {
            JsonNode json = objectMapper.readTree(tool.execute("{}"));

            assertEquals(1, json.path("filteredCount").asInt());
            assertFalse(logs.messages().stream().anyMatch(message -> message.contains(sensitive)));
        }
    }

    @Test
    void execute_whenAiReturnsNoCandidates_reportsNoFiltering() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(template("Camera")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"recommendations\":[]}");

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals(0, json.path("rawCandidateCount").asInt());
        assertEquals(0, json.path("filteredCount").asInt());
        assertTrue(json.path("message").asText().contains("returned no device candidates"));
    }

    @Test
    void execute_honorsMaxRecommendations() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(
                template("Camera"), template("Alarm"), template("Plug")));
        when(promptCompletionService.completeRecommendation(
                anyString(),
                contains("最多返回 2 条推荐"),
                anyDouble(),
                anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {"templateName":"Camera","description":"Add camera","reason":"Visibility","confidence":0.9},
                            {"templateName":"Alarm","description":"Add alarm","reason":"Security","confidence":0.8},
                            {"templateName":"Plug","description":"Add plug","reason":"Automation","confidence":0.7}
                          ]
                        }
                        """);

        String result = tool.execute("""
                {
                  "maxRecommendations": 2
                }
                """);

        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertEquals(2, json.path("count").asInt());
        assertEquals(2, json.path("recommendations").size());
        assertEquals(3, json.path("rawCandidateCount").asInt());
        assertEquals(2, json.path("inspectedCount").asInt());
        assertEquals(1, json.path("truncatedCount").asInt());
        assertEquals("Camera", json.path("recommendations").get(0).path("templateName").asText());
        assertEquals("Alarm", json.path("recommendations").get(1).path("templateName").asText());
    }

    @Test
    void execute_withChineseLanguage_returnsLocalizedBackendMessage() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of(
                new DeviceInfoHelper.DeviceInfo(
                        "door-1",
                        "Door",
                        "Door",
                        "closed",
                        "trusted",
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("closed", "open"),
                        List.of())
        ));
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(template("Camera")));
        when(promptCompletionService.completeRecommendation(
                anyString(),
                contains("输出语言: 简体中文"),
                anyDouble(),
                anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {"templateName":"Camera","description":"补充摄像头","reason":"增强可视化监控","confidence":0.9}
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
        assertEquals("为当前画布找到 1 个推荐设备。", json.path("message").asText());
        assertEquals(1, json.path("count").asInt());
        assertEquals("Camera", json.path("recommendations").get(0).path("templateName").asText());
    }

    @Test
    void execute_canonicalizesTemplateNameFromAvailableTemplate() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(template("Window Shade")));
        when(promptCompletionService.completeRecommendation(
                anyString(),
                anyString(),
                anyDouble(),
                anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {"templateName":"window shade","description":"Add shade","reason":"Privacy","confidence":0.9}
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");

        JsonNode json = objectMapper.readTree(result);

        assertTrue(json.path("error").isMissingNode(), "unexpected error envelope: " + result);
        assertEquals(1, json.path("count").asInt());
        assertEquals("Window Shade", json.path("recommendations").get(0).path("templateName").asText());
    }

    @Test
    void execute_returnsFilteredItemsForUnknownTemplates() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(template("Camera")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {"templateName":"Imaginary Sensor","description":"Invalid","reason":"Not real","confidence":0.7},
                            {"templateName":"Camera","suggestedLabel":"Entry Camera","description":"Add camera","reason":"Visibility","confidence":0.9}
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":3}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals(1, json.path("count").asInt());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("device", json.path("filteredItems").get(0).path("type").asText());
        assertEquals(1, json.path("filteredItems").get(0).path("index").asInt());
        assertEquals("unknownTemplate", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("Imaginary Sensor", json.path("filteredItems").get(0).path("label").asText());
    }

    @Test
    void execute_filtersCandidatesWithDroppedInitialRuntime() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(runtimeTemplate("Sensor")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {"templateName":"Sensor","suggestedLabel":"Bad State Sensor","initialState":"missing","description":"Bad state"},
                            {"templateName":"Sensor","suggestedLabel":"Bad Variable Sensor","initialVariables":[{"name":"missing","value":"high"}],"description":"Bad variable"},
                            {
                              "templateName":"Sensor",
                              "suggestedLabel":"Good Sensor",
                              "initialState":"active",
                              "currentStatePrivacy":"private",
                              "initialVariables":[{"name":"battery","value":"high","trust":"trusted"}],
                              "initialPrivacies":[{"name":"battery","privacy":"private"}],
                              "description":"Valid runtime"
                            }
                          ]
                        }
                        """);

        String result = tool.execute("{\"maxRecommendations\":5}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals(1, json.path("count").asInt());
        assertEquals("Good Sensor", json.path("recommendations").get(0).path("suggestedLabel").asText());
        assertEquals("active", json.path("recommendations").get(0).path("initialState").asText());
        assertEquals("private", json.path("recommendations").get(0).path("currentStatePrivacy").asText());
        assertEquals(1, json.path("recommendations").get(0).path("initialVariables").size());
        assertEquals(1, json.path("recommendations").get(0).path("initialPrivacies").size());
        assertEquals(2, json.path("filteredCount").asInt());
        assertEquals("invalidInitialRuntime", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals("Bad State Sensor", json.path("filteredItems").get(0).path("label").asText());
        assertEquals("invalidInitialRuntime", json.path("filteredItems").get(1).path("reasonCode").asText());
        assertEquals("Bad Variable Sensor", json.path("filteredItems").get(1).path("label").asText());
    }

    @Test
    void execute_filtersDisplayNameDuplicatesAcrossDifferentTemplates() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(
                template("Camera"), template("Alarm")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [
                            {"templateName":"Camera","suggestedLabel":"Entry Monitor","description":"Camera"},
                            {"templateName":"Alarm","suggestedLabel":"entry monitor","description":"Alarm"}
                          ]
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{\"maxRecommendations\":5}"));

        assertEquals(1, json.path("count").asInt());
        assertEquals("Entry Monitor", json.path("recommendations").get(0).path("suggestedLabel").asText());
        assertEquals("duplicateDeviceInstance", json.path("filteredItems").get(0).path("reasonCode").asText());
    }

    @Test
    void execute_materializesValuesButLeavesTemplateLabelsAsFallback() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(runtimeTemplate("Sensor")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [{
                            "templateName":"Sensor",
                            "suggestedPlacement":"Kitchen",
                            "intendedUse":"Kitchen monitoring",
                            "description":"Monitor the kitchen"
                          }]
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{\"maxRecommendations\":5}"));
        JsonNode recommendation = json.path("recommendations").get(0);

        assertEquals(1, json.path("count").asInt());
        assertEquals("Kitchen Sensor", recommendation.path("suggestedLabel").asText());
        assertEquals("Kitchen", recommendation.path("suggestedPlacement").asText());
        assertEquals("Kitchen monitoring", recommendation.path("intendedUse").asText());
        assertEquals("idle", recommendation.path("initialState").asText());
        assertFalse(recommendation.has("currentStateTrust"));
        assertFalse(recommendation.has("currentStatePrivacy"));
        assertEquals("battery", recommendation.path("initialVariables").get(0).path("name").asText());
        assertEquals("low", recommendation.path("initialVariables").get(0).path("value").asText());
        assertFalse(recommendation.path("initialVariables").get(0).has("trust"));
        assertFalse(recommendation.has("initialPrivacies"));
        assertEquals(1, json.path("adjustedCount").asInt());
        JsonNode defaults = json.path("adjustedItems").get(0).path("appliedValues");
        assertEquals("Kitchen Sensor", defaults.path("suggestedLabel").asText());
        assertEquals("idle", defaults.path("state").asText());
        assertEquals("low", defaults.path("variables.battery.value").asText());
        assertFalse(defaults.has("variables.battery.trust"));
        assertFalse(defaults.has("privacies.battery.privacy"));
    }

    /**
     * A suggested value must be an integer inside the declared domain, because that is the only thing the
     * board will store. `Double.parseDouble` accepted "25.5" for an integer domain and let a recommendation
     * through that `validateVariableValues` then rejected — the model's suggestion looked applicable and was
     * not. The sibling tools already parse with `Integer.parseInt`.
     *
     * <p>The whole candidate is filtered rather than the value silently replaced, matching every other
     * unusable-runtime rejection in this method (unknown name, duplicate name, bad trust): a recommendation
     * whose runtime cannot be applied is reported as filtered, not repaired into a different suggestion.
     */
    @Test
    void execute_dropsCandidateWithNonIntegerValueForAnIntegerDomain() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(numericTemplate("Thermometer")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [{
                            "templateName":"Thermometer",
                            "suggestedPlacement":"Hall",
                            "intendedUse":"Hall monitoring",
                            "description":"Monitor the hall",
                            "initialVariables":[{"name":"temperature","value":"25.5"}]
                          }]
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{\"maxRecommendations\":5}"));

        assertEquals(0, json.path("count").asInt());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("invalidInitialRuntime", json.path("filteredItems").get(0).path("reasonCode").asText());
    }

    /** An in-domain integer is kept verbatim — the guard above must not reject legal values. */
    @Test
    void execute_keepsAnInDomainIntegerValue() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(numericTemplate("Thermometer")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [{
                            "templateName":"Thermometer",
                            "suggestedPlacement":"Hall",
                            "intendedUse":"Hall monitoring",
                            "description":"Monitor the hall",
                            "initialVariables":[{"name":"temperature","value":"22"}]
                          }]
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{\"maxRecommendations\":5}"));

        assertEquals("22", json.path("recommendations").get(0)
                .path("initialVariables").get(0).path("value").asText());
    }

    @Test
    void execute_rejectsExplicitBlankRuntimeInsteadOfDefaultingIt() throws Exception {
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        snapshot = new BoardSemanticSnapshotDto(snapshot.nodes(), snapshot.environmentVariables(), snapshot.rules(), snapshot.specifications(), List.of(runtimeTemplate("Sensor")));
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {
                          "recommendations": [{
                            "templateName":"Sensor",
                            "suggestedLabel":"Kitchen Sensor",
                            "initialState":""
                          }]
                        }
                        """);

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        assertEquals(0, json.path("count").asInt());
        assertEquals(1, json.path("filteredCount").asInt());
        assertEquals("invalidInitialRuntime", json.path("filteredItems").get(0).path("reasonCode").asText());
        assertEquals(0, json.path("adjustedCount").asInt());
    }

    @Test
    void execute_rejectsOverlongRequirementInsteadOfTruncatingIt() throws Exception {
        String args = "{\"userRequirement\":\"" + "x".repeat(2001) + "\"}";

        JsonNode json = objectMapper.readTree(tool.execute(args));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals("userRequirement must be at most 2000 characters.", json.path("error").asText());
        verify(deviceInfoHelper, never()).getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class));
        verify(boardStorageService, never()).getSemanticSnapshot(1L);
        verify(promptCompletionService, never()).complete(anyString(), anyString(), anyDouble(), anyInt());
    }

    private DeviceTemplateDto template(String name) {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name(name)
                .description(name)
                .build());
        return dto;
    }

    /** A template whose only variable carries a complete integer domain, for value-canonicalization tests. */
    private DeviceTemplateDto numericTemplate(String name) {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name(name)
                .description(name)
                .modes(List.of("SensorState"))
                .initState("idle")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("idle").build()))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(true)
                        .lowerBound(15)
                        .upperBound(35)
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .build());
        return dto;
    }

    @ParameterizedTest
    @ValueSource(strings = {"on", "On"})
    void execute_preservesExactCaseOfADeclaredInitialVariable(String value) throws Exception {
        JsonNode result = recommendCaseSensitiveInitialValue(value);
        assertEquals(1, result.path("validatedCount").asInt(), result.toString());
        assertEquals(value, result.path("recommendations").get(0).path("initialVariables").get(0).path("value").asText());
    }

    @Test
    void execute_filtersAnAmbiguousInitialVariableInsteadOfChangingItsMeaning() throws Exception {
        JsonNode result = recommendCaseSensitiveInitialValue("ON");
        assertEquals(0, result.path("validatedCount").asInt(), result.toString());
        assertEquals(1, result.path("filteredCount").asInt());
    }

    private JsonNode recommendCaseSensitiveInitialValue(String value) throws Exception {
        var template = cn.edu.nju.Iot_Verify.testutil.CaseSensitiveEnumFixture.template();
        snapshot = new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of(template));
        when(deviceInfoHelper.getDevicesWithTemplateInfo(any(BoardSemanticSnapshotDto.class))).thenReturn(List.of());
        when(promptCompletionService.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"recommendations":[{"templateName":"CaseSensor","suggestedLabel":"Case sensor",
                          "initialVariables":[{"name":"level","value":"%s"}]}]}
                        """.formatted(value));
        return objectMapper.readTree(tool.execute("{}"));
    }

    private DeviceTemplateDto runtimeTemplate(String name) {
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(DeviceTemplateDto.DeviceManifest.builder()
                .name(name)
                .description(name)
                .modes(List.of("SensorState"))
                .initState("idle")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("idle").build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("active").build()))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("battery")
                        .isInside(true)
                        .values(List.of("low", "high"))
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .build());
        return dto;
    }
}
