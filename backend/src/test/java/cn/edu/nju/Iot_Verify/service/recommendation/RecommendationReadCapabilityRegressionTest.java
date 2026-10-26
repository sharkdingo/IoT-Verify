package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.component.aitool.BoardSemanticValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationReadCapabilityRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeviceInfoHelper devices = new DeviceInfoHelper();
    private final RecommendationResultParser parser = new RecommendationResultParser(mapper);

    @Test
    void ruleRecommendationDoesNotValidateAnAffectOnlyVariableAsAnObservedTrigger() throws Exception {
        BoardStorageService board = mock(BoardStorageService.class);
        PromptCompletionService provider = mock(PromptCompletionService.class);
        when(provider.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"recommendations":[{"name":"Dry when humidity is high","reason":"Humidity response",
                        "conditions":[{"deviceId":"ac_1","attribute":"humidity","targetType":"variable",
                        "relation":">=","value":"60"}],"command":{"deviceId":"ac_1","action":"dry"}}]}
                        """);
        var service = new RuleRecommendationService(devices, board, provider, mapper, parser);
        var readable = snapshot(false, true);
        when(board.getSemanticSnapshot(37L)).thenReturn(readable);
        assertEquals(1, service.recommend(37L, 2, "en", "Humidity response").getValidatedCount());

        var affectOnly = snapshot(false, false);
        var condition = RuleDto.Condition.builder().deviceName("ac_1").attribute("humidity")
                .targetType("variable").relation(">=").value("60").build();
        assertNotNull(BoardSemanticValidator.validateRuleCondition(
                BoardSemanticValidator.context(affectOnly.nodes(), affectOnly.deviceTemplates(),
                        affectOnly.environmentVariables()), condition, 0));
        when(board.getSemanticSnapshot(37L)).thenReturn(affectOnly);

        var result = service.recommend(37L, 2, "en", "Humidity response");

        assertEquals(0, result.getValidatedCount());
        assertEquals(1, result.getFilteredCount());
        assertTrue(result.getRecommendations().isEmpty());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(provider, times(2)).completeRecommendation(anyString(), prompt.capture(), anyDouble(), anyInt());
        JsonNode readableDevice = deviceContext(prompt.getAllValues().get(0)).path("devices").get(0);
        JsonNode affectOnlyDevice = deviceContext(prompt.getValue()).path("devices").get(0);
        assertTrue(mapper.convertValue(readableDevice.path("triggerableAttributes"), List.class).contains("humidity"));
        assertFalse(mapper.convertValue(affectOnlyDevice.path("triggerableAttributes"), List.class).contains("humidity"));
        assertEquals("humidity", affectOnlyDevice.path("capabilities").path("variables").get(0).path("name").asText());
        assertFalse(affectOnlyDevice.path("capabilities").path("variables").get(0).path("reads").asBoolean());
    }

    @Test
    void specificationRecommendationDoesNotValidateEnvironmentSourceForADeviceLocalVariable() {
        BoardStorageService board = mock(BoardStorageService.class);
        PromptCompletionService provider = mock(PromptCompletionService.class);
        when(provider.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("""
                        {"recommendations":[{"name": "Safety requirement", "templateId":"3","rationale":"Avoid excessive humidity",
                        "aConditions":[{"deviceId":"ac_1","targetType":"variable","key":"humidity",
                        "variableSource":"environment","relation":">=","value":"60"}]}]}
                        """);
        var service = new SpecificationRecommendationService(devices, board, provider, mapper, parser);
        when(board.getSemanticSnapshot(37L)).thenReturn(snapshot(false, true));
        assertEquals(1, service.recommend(37L, 2, "en", "Humidity safety").getValidatedCount());
        var deviceLocal = snapshot(true, true);
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("ac_1");
        condition.setTargetType("variable");
        condition.setKey("humidity");
        condition.setVariableSource("environment");
        condition.setRelation(">=");
        condition.setValue("60");
        assertNotNull(BoardSemanticValidator.validateSpecCondition(
                BoardSemanticValidator.context(deviceLocal.nodes(), deviceLocal.deviceTemplates(),
                        deviceLocal.environmentVariables()), condition, "a", 0));
        when(board.getSemanticSnapshot(37L)).thenReturn(deviceLocal);

        var result = service.recommend(37L, 2, "en", "Humidity safety");

        assertEquals(0, result.getValidatedCount());
        assertEquals(1, result.getFilteredCount());
        assertTrue(result.getRecommendations().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, variable, reported, 60, 1",
            "false, true, variable, reported, 60, 1",
            "false, true, variable, environment, 60, 1",
            "false, false, variable, reported, 60, 0",
            "false, false, variable, environment, 60, 0",
            "false, false, trust, , trusted, 1",
            "false, false, privacy, , public, 1"
    })
    void specificationConditionsRespectReadingCapabilityWithoutDiscardingDeclaredLabels(
            boolean deviceLocal, boolean reads, String targetType, String variableSource,
            String value, int validatedCount) throws Exception {
        BoardStorageService board = mock(BoardStorageService.class);
        PromptCompletionService provider = mock(PromptCompletionService.class);
        Map<String, Object> condition = new LinkedHashMap<>(Map.of(
                "deviceId", "ac_1", "targetType", targetType, "key", "humidity",
                "relation", "variable".equals(targetType) ? ">=" : "=", "value", value));
        if (variableSource != null) condition.put("variableSource", variableSource);
        else condition.put("propertyScope", "variable");
        when(provider.completeRecommendation(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn(mapper.writeValueAsString(Map.of("recommendations", List.of(Map.of(
                        "name", "Humidity stays safe", "templateId", "3", "rationale", "Humidity safety", "aConditions", List.of(condition))))));
        when(board.getSemanticSnapshot(37L)).thenReturn(snapshot(deviceLocal, reads));

        var result = new SpecificationRecommendationService(devices, board, provider, mapper, parser)
                .recommend(37L, 2, "en", "Humidity safety");

        assertEquals(validatedCount, result.getValidatedCount());
        assertEquals(1 - validatedCount, result.getFilteredCount());
        if (validatedCount == 0) {
            assertTrue(result.getRecommendations().isEmpty());
            assertEquals("invalidConditionCapability", result.getFilteredItems().get(0).getReasonCode());
        }
    }

    private JsonNode deviceContext(String prompt) throws Exception {
        String header = "## 设备列表\n";
        int start = prompt.indexOf(header) + header.length();
        assertTrue(start >= header.length());
        return mapper.readTree(prompt.substring(start, prompt.indexOf("\n\n", start)));
    }

    private BoardSemanticSnapshotDto snapshot(boolean deviceLocal, boolean reads) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("ac_1");
        node.setTemplateName("Air Conditioner");
        node.setLabel("Air Conditioner");
        node.setState("off");
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Air Conditioner");
        template.setManifest(DeviceTemplateDto.DeviceManifest.builder().name("Air Conditioner")
                .modes(List.of("ClimateState")).initState("off")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("humidity").isInside(deviceLocal).reads(deviceLocal ? null : reads)
                        .falsifiableWhenCompromised(false).naturalChangeRate("[-1, 1]")
                        .lowerBound(0).upperBound(100).trust("trusted").privacy("public").build()))
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("off").build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder().name("dry").build()))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder().name("dry")
                        .signal(false).startState("off").endState("dry").build())).build());
        return new BoardSemanticSnapshotDto(List.of(node),
                deviceLocal ? List.of()
                        : List.of(new BoardEnvironmentVariableDto("humidity", "50", "trusted", "public")),
                List.of(), List.of(), List.of(template));
    }
}
