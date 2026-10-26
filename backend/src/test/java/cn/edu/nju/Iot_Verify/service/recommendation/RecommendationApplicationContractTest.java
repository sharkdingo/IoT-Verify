package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.rule.RecommendRelatedDevicesTool;
import cn.edu.nju.Iot_Verify.component.aitool.rule.RecommendRulesTool;
import cn.edu.nju.Iot_Verify.component.aitool.spec.RecommendSpecificationsTool;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationResponseDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationApplicationContractTest {
    @Mock private BoardStorageService board;
    @Mock private PromptCompletionService provider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeviceInfoHelper devices = new DeviceInfoHelper();
    private final RecommendationResultParser parser = new RecommendationResultParser(mapper);

    @BeforeEach
    void clearAmbientUser() {
        UserContextHolder.clear();
    }

    @AfterEach
    void clearUser() {
        UserContextHolder.clear();
    }

    @Test
    void typedRulesAndToolShareDefaultsAndTheExplicitOwner() throws Exception {
        emptyBoard();
        var service = new RuleRecommendationService(devices, board, provider, mapper, parser);
        var result = service.recommend(37L, null, "zh_CN", " ");
        assertEnvelopeParity(result, new RecommendRulesTool(service, mapper), true);
    }

    @Test
    void typedDevicesAndToolShareDefaultsAndTheExplicitOwner() throws Exception {
        emptyBoard();
        var service = new DeviceRecommendationService(devices, board, provider, mapper, parser);
        var result = service.recommend(37L, null, "zh_CN", " ");
        assertEnvelopeParity(result, new RecommendRelatedDevicesTool(service, mapper), true);
    }

    @Test
    void typedSpecificationsAndToolOmitTheSameAbsentAdjustmentFields() throws Exception {
        emptyBoard();
        var service = new SpecificationRecommendationService(devices, board, provider, mapper, parser);
        var result = service.recommend(37L, null, "zh_CN", " ");
        assertEnvelopeParity(result, new RecommendSpecificationsTool(service, mapper), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "3", "4", "5", "6", "7"})
    void specificationProducerMaterializesOnlyOmittedInactiveGroupsForBothEntrypoints(String templateId)
            throws Exception {
        alarmBoard();
        ObjectNode raw = mapper.createObjectNode();
        raw.putArray("recommendations").add(specificationCandidate(templateId));
        when(provider.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(mapper.writeValueAsString(raw));
        var service = new SpecificationRecommendationService(devices, board, provider, mapper, parser);

        var result = service.recommend(37L, 2, "en", "");

        assertEquals(1, result.getCount());
        assertEquals(1, result.getValidatedCount());
        assertEquals(0, result.getFilteredCount());
        assertEquals(1, result.getRawCandidateCount());
        assertEquals(1, result.getInspectedCount());
        assertEquals(0, result.getTruncatedCount());
        var kept = result.getRecommendations().get(0);
        assertNotNull(kept.getAConditions());
        assertNotNull(kept.getIfConditions());
        assertNotNull(kept.getThenConditions());
        boolean aOnly = List.of("1", "2", "3", "7").contains(templateId);
        assertEquals(aOnly ? 1 : 0, kept.getAConditions().size());
        assertEquals(aOnly ? 0 : 1, kept.getIfConditions().size());
        assertEquals(aOnly ? 0 : 1, kept.getThenConditions().size());
        UserContextHolder.setUserId(37L);
        JsonNode toolResult = mapper.readTree(new RecommendSpecificationsTool(service, mapper)
                .execute("{\"maxRecommendations\":2}"));
        assertEquals(mapper.valueToTree(result), toolResult);
        verify(board, times(2)).getSemanticSnapshot(37L);
        verifyNoMoreInteractions(board);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingActive", "emptyActive", "nullActive", "nullInactive", "scalarInactive",
            "objectInactive", "nonemptyInactive"})
    void malformedSpecificationGroupsAreFilteredWithoutDiscardingTheValidCandidate(String malformed)
            throws Exception {
        alarmBoard();
        ObjectNode invalid = specificationCandidate("4");
        switch (malformed) {
            case "missingActive" -> invalid.remove("thenConditions");
            case "emptyActive" -> invalid.putArray("thenConditions");
            case "nullActive" -> invalid.putNull("thenConditions");
            case "nullInactive" -> invalid.putNull("aConditions");
            case "scalarInactive" -> invalid.put("aConditions", "off");
            case "objectInactive" -> invalid.putObject("aConditions");
            case "nonemptyInactive" -> invalid.putArray("aConditions")
                    .add(invalid.path("ifConditions").get(0).deepCopy());
            default -> throw new AssertionError(malformed);
        }
        ObjectNode raw = mapper.createObjectNode();
        raw.putArray("recommendations").add(invalid).add(specificationCandidate("4"));
        when(provider.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(mapper.writeValueAsString(raw));
        var service = new SpecificationRecommendationService(devices, board, provider, mapper, parser);

        var result = service.recommend(37L, 2, "en", "");

        assertEquals(1, result.getCount());
        assertEquals(1, result.getValidatedCount());
        assertEquals(1, result.getFilteredCount());
        assertEquals(2, result.getRawCandidateCount());
        assertEquals(2, result.getInspectedCount());
        assertEquals(0, result.getTruncatedCount());
        assertEquals(1, result.getFilteredItems().get(0).getIndex());
        assertEquals("invalidTemplateShape", result.getFilteredItems().get(0).getReasonCode());
        assertEquals(List.of(), result.getRecommendations().get(0).getAConditions());
        assertEquals(1, result.getRecommendations().get(0).getIfConditions().size());
        assertEquals(1, result.getRecommendations().get(0).getThenConditions().size());
        verify(board).getSemanticSnapshot(37L);
        verifyNoMoreInteractions(board);
    }

    @Test
    void malformedProviderResponseIsATypedFailureAndTheSameToolError() throws Exception {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor");
        node.setTemplateName("Sensor");
        node.setLabel("Hall sensor");
        when(board.getSemanticSnapshot(37L)).thenReturn(
                new BoardSemanticSnapshotDto(List.of(node), List.of(), List.of(), List.of(), List.of()));
        when(provider.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("not-json");
        var service = new RuleRecommendationService(devices, board, provider, mapper, parser);

        RecommendationFailure failure = assertThrows(RecommendationFailure.class,
                () -> service.recommend(37L, 5, "en", ""));

        assertEquals(502, failure.getCode());
        assertEquals("AI_RESPONSE_INVALID", failure.getErrorCode());
        assertEquals("response_parse", failure.getDetails().get("phase"));
        assertThrows(UnsupportedOperationException.class, () -> failure.getDetails().put("phase", "changed"));
        UserContextHolder.setUserId(37L);
        JsonNode toolError = mapper.readTree(new RecommendRulesTool(service, mapper).execute("{}"));
        assertEquals(502, toolError.path("status").intValue());
        assertEquals(failure.getErrorCode(), toolError.path("errorCode").textValue());
        assertEquals("response_parse", toolError.path("phase").textValue());
    }

    private void emptyBoard() {
        when(board.getSemanticSnapshot(37L)).thenReturn(
                new BoardSemanticSnapshotDto(List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    private void alarmBoard() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("alarm");
        node.setTemplateName("Alarm");
        node.setLabel("Hall alarm");
        node.setState("off");
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Alarm");
        template.setManifest(DeviceTemplateDto.DeviceManifest.builder().name("Alarm")
                .modes(List.of("AlertState")).initState("off")
                .workingStates(List.of(DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                        .name("off").trust("trusted").privacy("public").build())).build());
        when(board.getSemanticSnapshot(37L)).thenReturn(
                new BoardSemanticSnapshotDto(List.of(node), List.of(), List.of(), List.of(), List.of(template)));
    }

    private ObjectNode specificationCandidate(String templateId) {
        ObjectNode candidate = mapper.createObjectNode();
        candidate.put("name", "Safety requirement");
        candidate.put("templateId", templateId).put("rationale", "Check the alarm's declared off state");
        ObjectNode condition = mapper.createObjectNode();
        condition.put("deviceId", "alarm").put("targetType", "state").put("key", "state")
                .put("relation", "=").put("value", "off");
        if (List.of("1", "2", "3", "7").contains(templateId)) {
            candidate.putArray("aConditions").add(condition);
        } else {
            candidate.putArray("ifConditions").add(condition);
            candidate.putArray("thenConditions").add(condition.deepCopy());
        }
        return candidate;
    }

    private void assertEnvelopeParity(RecommendationResponseDto<?> result, AbstractAiTool tool,
                                      boolean hasAdjustments) throws Exception {
        assertNull(UserContextHolder.getUserId());
        assertEquals(5, result.getRequestedCount());
        assertEquals(0, result.getCount());
        JsonNode typedJson = mapper.valueToTree(result);
        assertEquals(hasAdjustments, typedJson.has("adjustedCount"));
        assertEquals(hasAdjustments, typedJson.has("adjustedItems"));
        UserContextHolder.setUserId(37L);
        assertEquals(typedJson, mapper.readTree(tool.execute("{\"language\":\"zh_CN\"}")));
        verify(board, times(2)).getSemanticSnapshot(37L);
        verifyNoMoreInteractions(board);
        verifyNoInteractions(provider);
    }
}
