package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolManager;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListTracesToolTest {

    @Mock
    private VerificationService verificationService;

    private ObjectMapper objectMapper;
    private ListTracesTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new ListTracesTool(verificationService, objectMapper, new ChatExecutionConfig());
        UserContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void execute_withNoTrace_shouldReturnEmptySummary() throws Exception {
        UserContextHolder.setUserId(1L);
        when(verificationService.getRuns(1L)).thenReturn(List.of());

        String result = tool.execute("{}");

        JsonNode json = objectMapper.readTree(result);
        assertEquals(0, json.path("count").asInt());
        assertEquals("No verification traces found. Traces are created when verification detects property violations.",
                json.path("message").asText());
    }

    @Test
    void execute_withUnknownField_shouldRejectBeforeListing() throws Exception {
        UserContextHolder.setUserId(1L);
        JsonNode json = objectMapper.readTree(tool.execute("{\"unknown\":10}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(verificationService, never()).getRuns(1L);
    }

    @Test
    void execute_whenServiceThrows_shouldReturnStableErrorJson() throws Exception {
        UserContextHolder.setUserId(1L);
        when(verificationService.getRuns(1L)).thenThrow(new RuntimeException("boom\"bad"));

        String result = tool.execute("{}");

        JsonNode json = objectMapper.readTree(result);
        assertEquals("Failed to list traces.", json.path("error").asText());
        assertEquals("INTERNAL_ERROR", json.path("errorCode").asText());
        assertEquals(500, json.path("status").asInt());
    }

    @Test
    void execute_withTrace_shouldExposeSpecContextAndModelCompleteness() throws Exception {
        UserContextHolder.setUserId(1L);
        SpecificationDto violatedSpec = new SpecificationDto();
        violatedSpec.setId("spec_1");
        violatedSpec.setName("Trust safety");
        violatedSpec.setTemplateId("7");
        violatedSpec.setTemplateLabel("Trust safety");
        TraceSummaryDto trace = TraceSummaryDto.builder()
                .id(5L)
                .violatedSpecId("spec_1")
                .violatedSpec(violatedSpec)
                .stateCount(4)
                .dataAvailable(true)
                .build();
        VerificationRunSummaryDto run = VerificationRunSummaryDto.builder()
                .id(10L)
                .modelComplete(false)
                .disabledRuleCount(2)
                .skippedSpecCount(1)
                .dataAvailable(true)
                .counterexamples(List.of(trace))
                .build();
        when(verificationService.getRuns(1L)).thenReturn(List.of(run));

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        JsonNode item = json.path("traces").get(0);
        assertEquals(5L, item.path("traceId").asLong());
        assertEquals("Trust safety", item.path("violatedSpecification").path("specificationLabel").asText());
        assertEquals(false, item.has("violatedSpecId"));
        assertEquals(false, item.path("violatedSpecification").has("id"));
        // `templateId` is emitted, unlike the persistence `id` beside it. It is not an internal identifier
        // — `spec_list`, `manage_spec` and both recommenders take and return it — and `get_trace` tells the
        // model to decide from it whether a trailing cycle is the violation (templates 2, 5, 6). Withholding
        // it left that instruction naming a field no trace response contained, so the only spec identity the
        // model could gate on was `specificationLabel`, which is display wording rather than a contract.
        assertEquals("7", item.path("violatedSpecification").path("templateId").asText());
        assertEquals(false, item.path("modelComplete").asBoolean());
        assertEquals(2, item.path("disabledRuleCount").asInt());
        assertEquals(1, item.path("skippedSpecCount").asInt());
        assertEquals(4, item.path("stateCount").asInt());
        assertEquals(1, json.path("availableCount").asInt());
        assertEquals(0, json.path("unavailableCount").asInt());
    }

    @Test
    void execute_withUnavailableTrace_shouldExplainItWithoutFabricatingDetails() throws Exception {
        UserContextHolder.setUserId(1L);
        TraceSummaryDto trace = TraceSummaryDto.builder()
                .id(6L)
                .dataAvailable(false)
                .unavailableReasonCode("PERSISTED_SEMANTIC_DATA_INVALID")
                .build();
        VerificationRunSummaryDto run = VerificationRunSummaryDto.builder()
                .id(11L)
                .dataAvailable(true)
                .counterexamples(List.of(trace))
                .build();
        when(verificationService.getRuns(1L)).thenReturn(List.of(run));

        JsonNode json = objectMapper.readTree(tool.execute("{}"));

        JsonNode item = json.path("traces").get(0);
        assertEquals(false, item.path("dataAvailable").asBoolean());
        assertEquals("PERSISTED_SEMANTIC_DATA_INVALID",
                item.path("unavailableReasonCode").asText());
        assertEquals(false, item.has("stateCount"));
        assertEquals(0, json.path("availableCount").asInt());
        assertEquals(1, json.path("unavailableCount").asInt());
    }

    @Test
    void execute_pagesOwnedTraceSummariesWithContinuation() throws Exception {
        UserContextHolder.setUserId(1L);
        List<TraceSummaryDto> traces = java.util.stream.LongStream.rangeClosed(1, 3)
                .mapToObj(id -> TraceSummaryDto.builder().id(id).dataAvailable(true).stateCount(2).build())
                .toList();
        when(verificationService.getRuns(1L)).thenReturn(List.of(
                VerificationRunSummaryDto.builder().id(8L).dataAvailable(true)
                        .counterexamples(traces).build()));

        JsonNode first = objectMapper.readTree(tool.execute("{\"limit\":2}"));
        JsonNode second = objectMapper.readTree(tool.execute("{\"offset\":2,\"limit\":2}"));

        assertEquals(3, first.path("totalCount").asInt());
        assertEquals(2, first.path("count").asInt());
        assertEquals(2, first.path("traces").size());
        assertEquals(1L, first.path("traces").get(0).path("traceId").asLong());
        assertEquals(true, first.path("hasMore").asBoolean());
        assertEquals(2, first.path("nextOffset").asInt());
        assertEquals(3L, second.path("traces").get(0).path("traceId").asLong());
        assertEquals(false, second.path("hasMore").asBoolean());
    }

    @Test
    void execute_reducesPageToFitByteBudgetWithoutLosingContinuation() throws Exception {
        UserContextHolder.setUserId(1L);
        ChatExecutionConfig config = new ChatExecutionConfig();
        config.setMaxToolResultBytes(4096);
        tool = new ListTracesTool(verificationService, objectMapper, config);
        List<TraceSummaryDto> traces = java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(id -> TraceSummaryDto.builder().id(id).dataAvailable(true).stateCount(2).build())
                .toList();
        when(verificationService.getRuns(1L)).thenReturn(List.of(
                VerificationRunSummaryDto.builder().id(8L).dataAvailable(true)
                        .generationIssues(List.of(ModelGenerationIssueDto.builder()
                                .issueType("rule").itemLabel("long issue")
                                .reason("cause ".repeat(90)).build()))
                        .counterexamples(traces).build()));

        AiToolManager manager = new AiToolManager(List.of(tool), objectMapper, config,
                mock(ChatConfirmationDetector.class));
        manager.init();
        String response = manager.execute("list_traces", "{\"limit\":8}");
        JsonNode page = objectMapper.readTree(response);

        assertEquals(8, page.path("totalCount").asInt());
        assertEquals(true, page.path("count").asInt() < 8);
        assertEquals(true, page.path("count").asInt() > 0);
        assertEquals(page.path("count").asInt(), page.path("nextOffset").asInt());
        assertEquals(true, page.path("hasMore").asBoolean());
        assertEquals("SUCCESS", page.path("resultStatus").asText());
        assertEquals(true, response.getBytes(StandardCharsets.UTF_8).length <= 4096);
    }

    @Test
    void execute_singleOversizedSummaryStillExposesTraceIdAndContinuation() throws Exception {
        UserContextHolder.setUserId(1L);
        ChatExecutionConfig config = new ChatExecutionConfig();
        config.setMaxToolResultBytes(4096);
        tool = new ListTracesTool(verificationService, objectMapper, config);
        when(verificationService.getRuns(1L)).thenReturn(List.of(
                VerificationRunSummaryDto.builder().id(8L).dataAvailable(true)
                        .generationIssues(List.of(ModelGenerationIssueDto.builder()
                                .issueType("rule").reason("cause ".repeat(900)).build()))
                        .counterexamples(List.of(TraceSummaryDto.builder().id(5L)
                                .dataAvailable(true).stateCount(2).build())).build()));

        JsonNode page = objectMapper.readTree(tool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", page.path("resultStatus").asText());
        assertEquals(5L, page.path("traceId").asLong());
        assertEquals(8L, page.path("runId").asLong());
        assertEquals(1, page.path("nextOffset").asInt());
    }
}
