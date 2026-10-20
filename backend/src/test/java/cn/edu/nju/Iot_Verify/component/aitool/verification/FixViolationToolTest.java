package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControlHolder;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterTarget;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.FixService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FixViolationToolTest {

    @Mock private FixService fixService;
    private ObjectMapper objectMapper;
    private FixViolationTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new FixViolationTool(fixService, objectMapper);
        UserContextHolder.setUserId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        LlmRequestControlHolder.clear();
        Thread.interrupted();
    }

    private void assertValidationError(String argsJson) throws Exception {
        String result = tool.execute(argsJson);
        JsonNode node = objectMapper.readTree(result);
        assertEquals("VALIDATION_ERROR", node.get("errorCode").asText());
        assertEquals(400, node.get("status").asInt());
        verifyNoInteractions(fixService);
    }

    @Test
    void definitionDescribesBothConditionAdjustmentDirections() {
        String description = tool.getDefinition().description();

        assertTrue(description.contains("add or remove triggering conditions"));
    }

    @Test
    void execute_traceId_outOfLongRange_returns400() throws Exception {
        assertValidationError("{\"traceId\":99999999999999999999}");
    }

    @Test
    void execute_legacyPreferredRanges_returns400() throws Exception {
        assertValidationError("{\"traceId\":1,\"preferredRanges\":{\"r0_c0\":{\"lower\":1,\"upper\":10}}}");
    }

    @Test
    void execute_preferredRangeSelections_nonIntegerValue_returns400() throws Exception {
        String targetId = PreferredRangeSelection.targetIdFor(0, 0);
        assertValidationError("{\"traceId\":1,\"preferredRangeSelections\":[{\"targetId\":\""
                + targetId + "\",\"lower\":\"abc\",\"upper\":10}]}");
    }

    @Test
    void execute_preferredRangeSelections_decimalValue_returns400() throws Exception {
        String targetId = PreferredRangeSelection.targetIdFor(0, 0);
        assertValidationError("{\"traceId\":1,\"preferredRangeSelections\":[{\"targetId\":\""
                + targetId + "\",\"lower\":1.5,\"upper\":10}]}");
    }

    @Test
    void execute_preferredRangeSelections_outOfIntRange_returns400() throws Exception {
        String targetId = PreferredRangeSelection.targetIdFor(0, 0);
        assertValidationError("{\"traceId\":1,\"preferredRangeSelections\":[{\"targetId\":\""
                + targetId + "\",\"lower\":4294967299,\"upper\":10}]}");
    }

    @Test
    void execute_preferredRangeSelections_invalidTargetId_returns400() throws Exception {
        assertValidationError("{\"traceId\":1,\"preferredRangeSelections\":[{\"targetId\":\"not-a-target\",\"lower\":1,\"upper\":10}]}");
    }

    @Test
    void execute_preferredRangeSelections_missingField_returns400() throws Exception {
        assertValidationError("{\"traceId\":1,\"preferredRangeSelections\":[{\"lower\":10,\"upper\":20}]}");
    }

    @Test
    void execute_preferredRangeSelections_wrongType_returns400() throws Exception {
        assertValidationError("{\"traceId\":1,\"preferredRangeSelections\":\"abc\"}");
    }

    @Test
    void execute_strategiesWrongType_doesNotFallBackToDefaultOrder() throws Exception {
        assertValidationError("{\"traceId\":1,\"strategies\":\"remove\"}");
    }

    @Test
    void execute_emptyStrategies_doesNotFallBackToDefaultOrder() throws Exception {
        assertValidationError("{\"traceId\":1,\"strategies\":[]}");
    }

    @Test
    void execute_unsupportedStrategy_returns400() throws Exception {
        assertValidationError("{\"traceId\":1,\"strategies\":[\"unknown\"]}");
    }

    @Test
    void execute_successOmitsInternalSpecIdAndExplainsThatNothingWasApplied() throws Exception {
        when(fixService.fix(anyLong(), anyLong(), any(), any())).thenReturn(FixResultDto.builder()
                .traceId(1L)
                .violatedSpecId("internal-spec-id")
                .fixable(false)
                .faultRules(java.util.List.of())
                .suggestions(java.util.List.of())
                .strategyAttempts(java.util.List.of())
                .warnings(java.util.List.of())
                .parameterTargets(java.util.List.of(ParameterTarget.builder()
                        .targetId(PreferredRangeSelection.targetIdFor(1L, 0, 0))
                        .attribute("temperature")
                        .relation(">")
                        .originalValue("30")
                        .lowerBound(0)
                        .upperBound(50)
                        .description("Cooling rule temperature")
                        .build()))
                .summary("No forward-verified suggestion was found; no Board change was applied.")
                .build());

        JsonNode node = objectMapper.readTree(tool.execute("{\"traceId\":1}"));

        assertFalse(node.has("violatedSpecId"));
        assertTrue(node.path("message").asText().contains("no Board change was applied"));
        assertEquals(1, node.path("parameterTargets").size());
        assertFalse(node.path("parameterTargets").get(0).has("ruleIndex"));
        assertTrue(node.path("preferredRangeSelections").isArray());
        assertTrue(node.path("preferredRangeSelections").isEmpty());
    }

    @Test
    void executeRetainsTheExactPreferredRangesWithItsCanonicalResult() throws Exception {
        String targetId = PreferredRangeSelection.targetIdFor(1L, 0, 0);
        when(fixService.fix(anyLong(), anyLong(), any(), any())).thenReturn(FixResultDto.builder()
                .traceId(1L).fixable(false).suggestions(java.util.List.of()).build());

        JsonNode result = objectMapper.readTree(tool.execute("""
                {"traceId":1,"preferredRangeSelections":[{"targetId":"%s","lower":10,"upper":20}]}
                """.formatted(targetId)));

        assertEquals(targetId, result.path("preferredRangeSelections").get(0).path("targetId").asText());
        assertEquals(10, result.path("preferredRangeSelections").get(0).path("lower").asInt());
        assertEquals(20, result.path("preferredRangeSelections").get(0).path("upper").asInt());
    }

    @Test
    void stoppingTheAssistantInterruptsItsActiveReadOnlyFixSearch() throws Exception {
        LlmRequestControl control = new LlmRequestControl();
        CountDownLatch enteredSearch = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean searchInterrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        when(fixService.fix(anyLong(), anyLong(), any(), any())).thenAnswer(invocation -> {
            enteredSearch.countDown();
            try {
                new CountDownLatch(1).await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                searchInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return FixResultDto.builder().traceId(1L).fixable(false).suggestions(List.of()).build();
        });
        Thread worker = new Thread(() -> {
            UserContextHolder.setUserId(1L);
            LlmRequestControlHolder.set(control);
            try {
                result.set(tool.execute("{\"traceId\":1}"));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                UserContextHolder.clear();
                LlmRequestControlHolder.clear();
                finished.countDown();
            }
        });
        worker.start();
        try {
            assertTrue(enteredSearch.await(3, TimeUnit.SECONDS));
            control.cancel();
            assertTrue(finished.await(2, TimeUnit.SECONDS), "Stop must reach the active fix search");
            assertTrue(searchInterrupted.get());
            assertNull(failure.get());
            assertFalse(Thread.currentThread().isInterrupted());
            JsonNode stopped = objectMapper.readTree(result.get());
            assertEquals("FIX_ANALYSIS_CANCELLED", stopped.path("errorCode").asText());
            assertEquals(499, stopped.path("status").asInt());
            assertFalse(stopped.has("suggestions"));
        } finally {
            worker.interrupt();
            worker.join(3000);
        }
    }

    @Test
    void anAlreadyStoppedAssistantDoesNotStartFixAnalysisOrReportATimeout() throws Exception {
        LlmRequestControl control = new LlmRequestControl();
        control.cancel();
        LlmRequestControlHolder.set(control);

        JsonNode result = objectMapper.readTree(tool.execute("{\"traceId\":1}"));

        assertEquals("FIX_ANALYSIS_CANCELLED", result.path("errorCode").asText());
        assertEquals(499, result.path("status").asInt());
        assertTrue(Thread.currentThread().isInterrupted());
        verifyNoInteractions(fixService);
    }

    @Test
    void execute_serviceThrowsResourceNotFound_returns404() throws Exception {
        when(fixService.fix(anyLong(), anyLong(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Trace 99 not found"));

        String result = tool.execute("{\"traceId\":99}");
        JsonNode node = objectMapper.readTree(result);

        assertEquals("NOT_FOUND", node.get("errorCode").asText());
        assertEquals(404, node.get("status").asInt());
    }

    @Test
    void execute_serviceThrowsServiceUnavailable_returns503() throws Exception {
        when(fixService.fix(anyLong(), anyLong(), any(), any()))
                .thenThrow(new ServiceUnavailableException("NuSMV busy"));

        String result = tool.execute("{\"traceId\":1}");
        JsonNode node = objectMapper.readTree(result);

        assertEquals("SERVICE_UNAVAILABLE", node.get("errorCode").asText());
        assertEquals(503, node.get("status").asInt());
    }

    @Test
    void execute_serviceThrowsSmvGenerationException_returns500WithCategory() throws Exception {
        when(fixService.fix(anyLong(), anyLong(), any(), any()))
                .thenThrow(SmvGenerationException.deviceNotFound("sensor1"));

        String result = tool.execute("{\"traceId\":1}");
        JsonNode node = objectMapper.readTree(result);

        assertEquals("SMV_GENERATION_ERROR", node.get("errorCode").asText());
        assertEquals(500, node.get("status").asInt());
        assertEquals("DEVICE_NOT_FOUND", node.get("errorCategory").asText());
    }

    @Test
    void execute_serviceThrowsGenericException_returns500FixedMessage() throws Exception {
        when(fixService.fix(anyLong(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("internal details leaked"));

        String result = tool.execute("{\"traceId\":1}");
        JsonNode node = objectMapper.readTree(result);

        assertEquals("INTERNAL_ERROR", node.get("errorCode").asText());
        assertEquals(500, node.get("status").asInt());
        assertEquals("Fix analysis failed.", node.get("error").asText(),
                "Internal details must not leak to AI response");
    }
}
