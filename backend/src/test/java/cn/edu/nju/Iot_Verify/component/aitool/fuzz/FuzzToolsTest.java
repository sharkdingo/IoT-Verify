package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzInputEventDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationOutcome;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FuzzToolsTest {

    @Mock
    private FuzzService fuzzService;

    private ObjectMapper objectMapper;
    private FuzzModelAsyncTool fuzzModelAsyncTool;
    private FuzzTaskStatusTool fuzzTaskStatusTool;
    private CancelFuzzTaskTool cancelFuzzTaskTool;
    private ListFuzzRunsTool listFuzzRunsTool;
    private GetFuzzRunTool getFuzzRunTool;
    private GetFuzzFindingTool getFuzzFindingTool;
    private DeleteFuzzRunTool deleteFuzzRunTool;
    private DismissFuzzTaskTool dismissFuzzTaskTool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        fuzzModelAsyncTool = new FuzzModelAsyncTool(fuzzService, objectMapper);
        fuzzTaskStatusTool = new FuzzTaskStatusTool(fuzzService, objectMapper);
        cancelFuzzTaskTool = new CancelFuzzTaskTool(fuzzService, objectMapper);
        listFuzzRunsTool = new ListFuzzRunsTool(fuzzService, objectMapper);
        getFuzzRunTool = new GetFuzzRunTool(fuzzService, objectMapper);
        getFuzzFindingTool = new GetFuzzFindingTool(fuzzService, objectMapper);
        deleteFuzzRunTool = new DeleteFuzzRunTool(
                fuzzService, objectMapper, new AiDestructiveActionGuard(objectMapper));
        dismissFuzzTaskTool = new DismissFuzzTaskTool(
                fuzzService, objectMapper, new AiDestructiveActionGuard(objectMapper));
        UserContextHolder.setUserId(1L);
        UserContextHolder.setChatSessionId("fuzz-test-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void start_usesBoardSnapshotAndSubmitsDefaults() throws Exception {
        when(fuzzService.submit(eq(1L), any())).thenReturn(7L);
        when(fuzzService.getTask(1L, 7L)).thenReturn(FuzzTaskDto.builder()
                .id(7L).status("PENDING").progress(0)
                .explorationMode(FuzzExplorationMode.BOARD_SNAPSHOT)
                .maxIterations(1000).pathLength(20).populationSize(10)
                .build());

        JsonNode json = objectMapper.readTree(fuzzModelAsyncTool.execute("{}"));

        assertEquals(7L, json.path("taskId").asLong());
        assertEquals("PENDING", json.path("taskStatus").asText());
        assertTrue(json.path("taskAccepted").asBoolean());
        ArgumentCaptor<FuzzRequestDto> captor = ArgumentCaptor.forClass(FuzzRequestDto.class);
        verify(fuzzService).submit(eq(1L), captor.capture());
        assertEquals(FuzzExplorationMode.BOARD_SNAPSHOT, captor.getValue().getExplorationMode());
        // Must equal FuzzRequestDto's own default, not a second number: at 1000 this tool's default budget
        // product was 200,000, which every scene in docs/examples refused, so "run bounded exploration"
        // through the assistant failed with a VALIDATION_ERROR on every shipped scene.
        assertEquals(200, captor.getValue().getMaxIterations());
        assertEquals(FuzzRequestDto.builder().build().getMaxIterations(),
                captor.getValue().getMaxIterations());
        assertEquals(20, captor.getValue().getPathLength());
        assertEquals(10, captor.getValue().getPopulationSize());
    }

    @Test
    void start_whenAcceptedTaskStatusCannotBeRead_shouldPreserveTaskId() throws Exception {
        when(fuzzService.submit(eq(1L), any())).thenReturn(8L);
        when(fuzzService.getTask(1L, 8L))
                .thenThrow(new ServiceUnavailableException("status store unavailable"));

        JsonNode json = objectMapper.readTree(fuzzModelAsyncTool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals("ACCEPTED_TASK_STATUS_UNAVAILABLE", json.path("errorCode").asText());
        assertTrue(json.path("taskAccepted").asBoolean());
        assertEquals(8L, json.path("taskId").asLong());
        assertEquals("fuzz_task_status", json.path("statusTool").asText());
        assertTrue(json.path("message").asText().contains("do not submit a duplicate"));
        verify(fuzzService).submit(eq(1L), any());
        verify(fuzzService).getTask(1L, 8L);
    }

    @Test
    void start_whenDispatchCleanupIsUnknown_shouldPreserveTaskId() throws Exception {
        when(fuzzService.submit(eq(1L), any()))
                .thenThrow(new AsyncTaskDispatchOutcomeUnknownException(
                        "fuzz", 9L, new IllegalStateException("cleanup failed")));

        JsonNode json = objectMapper.readTree(fuzzModelAsyncTool.execute("{}"));

        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals(AsyncTaskDispatchOutcomeUnknownException.REASON_CODE,
                json.path("errorCode").asText());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
        assertEquals(9L, json.path("taskId").asLong());
        assertEquals("fuzz_task_status", json.path("statusTool").asText());
        assertTrue(json.path("message").asText().contains("before retrying"));
        verify(fuzzService, never()).getTask(anyLong(), anyLong());
    }

    @Test
    void start_rejectsOutOfRangeIterations() throws Exception {
        JsonNode json = objectMapper.readTree(
                fuzzModelAsyncTool.execute("{\"maxIterations\":99999}"));
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(fuzzService, never()).submit(any(), any());
    }

    @Test
    void start_rejectsOutOfRangeSeed() throws Exception {
        JsonNode json = objectMapper.readTree(
                fuzzModelAsyncTool.execute("{\"seed\":-1}"));
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(fuzzService, never()).submit(any(), any());
    }

    @Test
    void start_rejectsSeedOutsideLongRangeWithoutTruncating() throws Exception {
        JsonNode json = objectMapper.readTree(
                fuzzModelAsyncTool.execute("{\"seed\":18446744073709551616}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(fuzzService, never()).submit(any(), any());
    }

    @Test
    void start_rejectsInvalidTargetSpecificationCollectionsBeforeSubmit() throws Exception {
        JsonNode duplicate = objectMapper.readTree(fuzzModelAsyncTool.execute(
                "{\"targetSpecIds\":[\"spec-1\",\"spec-1\"]}"));
        JsonNode overlong = objectMapper.readTree(fuzzModelAsyncTool.execute(
                "{\"targetSpecIds\":[\"" + "s".repeat(101) + "\"]}"));
        JsonNode tooMany = objectMapper.readTree(fuzzModelAsyncTool.execute(
                "{\"targetSpecIds\":[" + java.util.stream.IntStream.range(0, 101)
                        .mapToObj(index -> "\"spec-" + index + "\"")
                        .collect(java.util.stream.Collectors.joining(",")) + "]}"));

        assertEquals("VALIDATION_ERROR", duplicate.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", overlong.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", tooMany.path("errorCode").asText());
        verify(fuzzService, never()).submit(any(), any());
    }

    @Test
    void status_returnsTaskAndProgress() throws Exception {
        when(fuzzService.getTask(1L, 7L)).thenReturn(FuzzTaskDto.builder()
                .id(7L).status("RUNNING").progress(42).build());

        JsonNode json = objectMapper.readTree(fuzzTaskStatusTool.execute("{\"taskId\":7,\"waitMs\":0}"));

        assertEquals(42, json.path("progress").asInt());
        assertEquals("RUNNING", json.path("task").path("status").asText());
        assertEquals(42, json.path("task").path("progress").asInt());
        verify(fuzzService).getTask(1L, 7L);
        verifyNoMoreInteractions(fuzzService);
    }

    @Test
    void statusWaitsBrieflyUsingTheSameContractAsVerificationAndSimulation() throws Exception {
        when(fuzzService.getTask(1L, 7L)).thenReturn(
                FuzzTaskDto.builder().id(7L).status("RUNNING").progress(42).build(),
                FuzzTaskDto.builder().id(7L).status("COMPLETED").progress(100).build());

        JsonNode result = objectMapper.readTree(fuzzTaskStatusTool.execute(
                "{\"taskId\":7,\"waitMs\":10000}"));

        assertEquals("COMPLETED", result.path("task").path("status").asText());
        assertEquals(100, result.path("progress").asInt());
        verify(fuzzService, org.mockito.Mockito.times(2)).getTask(1L, 7L);
    }

    @Test
    void statusRejectsInvalidWaitsBeforeReadingATask() throws Exception {
        for (String wait : List.of("-1", "10001", "1.5", "\"soon\"", "null")) {
            JsonNode result = objectMapper.readTree(fuzzTaskStatusTool.execute(
                    "{\"taskId\":7,\"waitMs\":" + wait + "}"));
            assertEquals("VALIDATION_ERROR", result.path("errorCode").asText(), wait);
        }
        verifyNoMoreInteractions(fuzzService);
    }

    @Test
    void cancel_reportsOutcome() throws Exception {
        when(fuzzService.cancelTask(1L, 7L)).thenReturn(TaskCancellationResultDto.builder()
                .taskId(7L).cancellationAccepted(true)
                .cancellationOutcome(TaskCancellationOutcome.ACCEPTED)
                .taskStatus("CANCELLED").executionMayStillBeStopping(false).build());

        JsonNode json = objectMapper.readTree(cancelFuzzTaskTool.execute("{\"taskId\":7}"));

        assertEquals(true, json.path("cancellationAccepted").asBoolean());
        assertEquals("CANCELLED", json.path("taskStatus").asText());
    }

    @Test
    void listRuns_summarizesAndPaginates() throws Exception {
        when(fuzzService.getRuns(1L, 0, 25)).thenReturn(List.of(
                FuzzRunSummaryDto.builder().id(3L).outcome(FuzzOutcome.FOUND_VIOLATION)
                        .findingCount(2).dataAvailable(true).build()));

        JsonNode json = objectMapper.readTree(listFuzzRunsTool.execute("{}"));

        assertEquals(1, json.path("count").asInt());
        assertEquals(3L, json.path("runs").get(0).path("runId").asLong());
        verify(fuzzService).getRuns(1L, 0, 25);
    }

    @Test
    void getRun_exposesFindingSummaries() throws Exception {
        when(fuzzService.getRun(1L, 3L)).thenReturn(FuzzRunDto.builder()
                .id(3L).outcome(FuzzOutcome.FOUND_VIOLATION).findingCount(1)
                .findings(List.of(FuzzFindingDto.builder().id(11L).violatedSpecId("s1")
                        .violatedSpec(violatedSpecification())
                        .firstViolationStep(4).states(List.of()).build()))
                .build());

        JsonNode json = objectMapper.readTree(getFuzzRunTool.execute("{\"runId\":3}"));

        assertEquals(3L, json.path("runId").asLong());
        assertEquals(11L, json.path("findings").get(0).path("findingId").asLong());
        assertFalse(json.path("findings").get(0).has("violatedSpecId"));
        assertEquals("Safety invariant", json.path("findings").get(0)
                .path("violatedSpec").path("specificationLabel").asText());
        assertTrue(json.path("message").asText().toLowerCase().contains("not formal"));
    }

    @Test
    void getFinding_returnsStatesAndDisclaimer() throws Exception {
        when(fuzzService.getFinding(1L, 11L)).thenReturn(FuzzFindingDto.builder()
                .id(11L).fuzzTaskId(3L).violatedSpecId("s1").firstViolationStep(4)
                .violatedSpec(violatedSpecification())
                .states(List.of()).inputEvents(List.of()).build());

        JsonNode json = objectMapper.readTree(getFuzzFindingTool.execute("{\"findingId\":11}"));

        assertEquals(11L, json.path("findingId").asLong());
        assertEquals(3L, json.path("runId").asLong());
        assertFalse(json.has("violatedSpecId"));
        assertEquals("Safety invariant",
                json.path("violatedSpec").path("specificationLabel").asText());
        assertEquals(0, json.path("returnedStateCount").asInt());
        assertEquals(10, json.path("stateLimit").asInt());
        assertTrue(json.path("message").asText().toLowerCase().contains("not a formal"));
    }

    @Test
    void getFinding_pagesStatesAndMatchingInputEvents() throws Exception {
        when(fuzzService.getFinding(1L, 11L)).thenReturn(FuzzFindingDto.builder()
                .id(11L).fuzzTaskId(3L).violatedSpecId("s1").firstViolationStep(2)
                .states(List.of(state(0), state(1), state(2)))
                .inputEvents(List.of(event(0), event(1), event(2)))
                .build());

        JsonNode json = objectMapper.readTree(getFuzzFindingTool.execute(
                "{\"findingId\":11,\"stateOffset\":1,\"stateLimit\":1}"));

        assertEquals(3, json.path("stateCount").asInt());
        assertEquals(1, json.path("stateOffset").asInt());
        assertEquals(1, json.path("stateLimit").asInt());
        assertEquals(1, json.path("returnedStateCount").asInt());
        assertTrue(json.path("hasMoreStates").asBoolean());
        assertEquals(2, json.path("nextStateOffset").asInt());
        assertEquals(1, json.path("states").get(0).path("stateIndex").asInt());
        assertEquals(3, json.path("inputEventCount").asInt());
        assertEquals(1, json.path("returnedInputEventCount").asInt());
        assertEquals(1, json.path("inputEvents").get(0).path("step").asInt());
    }

    @Test
    void getFinding_rejectsInvalidStateWindowBeforeLoadingFinding() throws Exception {
        JsonNode zeroLimit = objectMapper.readTree(getFuzzFindingTool.execute(
                "{\"findingId\":11,\"stateLimit\":0}"));
        JsonNode oversizedLimit = objectMapper.readTree(getFuzzFindingTool.execute(
                "{\"findingId\":11,\"stateLimit\":11}"));
        JsonNode negativeOffset = objectMapper.readTree(getFuzzFindingTool.execute(
                "{\"findingId\":11,\"stateOffset\":-1}"));

        assertEquals("VALIDATION_ERROR", zeroLimit.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", oversizedLimit.path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", negativeOffset.path("errorCode").asText());
        verify(fuzzService, never()).getFinding(anyLong(), anyLong());
    }

    @Test
    void deleteRun_previewThenConfirmCascades() throws Exception {
        when(fuzzService.getRunDeletionImpact(1L, 3L)).thenReturn(
                RunDeletionImpactDto.builder().runId(3L).evidenceCount(2).build());
        when(fuzzService.deleteRun(1L, 3L, 2L)).thenReturn(2L);

        JsonNode preview = objectMapper.readTree(
                deleteFuzzRunTool.execute("{\"runId\":3,\"confirmed\":false}"));
        assertTrue(preview.path("requiresUserConfirmation").asBoolean());
        assertEquals(2, preview.path("wouldRemoveStoredFindingCount").asInt());
        assertTrue(preview.path("countIncludesUnavailableEvidence").asBoolean());
        verify(fuzzService, never()).deleteRun(1L, 3L, 2L);

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode result = objectMapper.readTree(deleteFuzzRunTool.execute(
                "{\"runId\":3,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals(true, result.path("deleted").asBoolean());
        assertEquals(2, result.path("deletedFindingCount").asInt());
        verify(fuzzService).deleteRun(1L, 3L, 2L);
    }

    @Test
    void deleteRun_confirmWithoutTokenIsRejected() throws Exception {
        when(fuzzService.getRunDeletionImpact(1L, 3L)).thenReturn(
                RunDeletionImpactDto.builder().runId(3L).evidenceCount(0).build());
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", "missing-token");

        JsonNode json = objectMapper.readTree(
                deleteFuzzRunTool.execute("{\"runId\":3,\"confirmed\":true}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(fuzzService, never()).deleteRun(1L, 3L, 0L);
    }

    @Test
    void deleteRun_changedFindingCountInvalidatesConfirmation() throws Exception {
        when(fuzzService.getRunDeletionImpact(1L, 3L))
                .thenReturn(RunDeletionImpactDto.builder().runId(3L).evidenceCount(2).build())
                .thenReturn(RunDeletionImpactDto.builder().runId(3L).evidenceCount(4).build());

        JsonNode preview = objectMapper.readTree(
                deleteFuzzRunTool.execute("{\"runId\":3,\"confirmed\":false}"));
        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode result = objectMapper.readTree(deleteFuzzRunTool.execute(
                "{\"runId\":3,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals(409, result.path("status").asInt());
        assertEquals(4, result.path("currentPreview").path("wouldRemoveStoredFindingCount").asInt());
        verify(fuzzService, never()).deleteRun(1L, 3L, 4L);
    }

    @Test
    void dismiss_previewThenConfirmRemovesDeadTask() throws Exception {
        FuzzTaskDto task = FuzzTaskDto.builder()
                .id(9L)
                .status("FAILED")
                .progress(72)
                .errorMessage("Exploration worker failed")
                .explorationMode(FuzzExplorationMode.BOARD_SNAPSHOT)
                .maxIterations(1000)
                .targetSpecIds(List.of("spec-1"))
                .build();
        when(fuzzService.getTask(1L, 9L)).thenReturn(task);

        JsonNode preview = objectMapper.readTree(
                dismissFuzzTaskTool.execute("{\"taskId\":9,\"confirmed\":false}"));

        assertTrue(preview.path("requiresUserConfirmation").asBoolean());
        assertEquals("FAILED", preview.path("status").asText());
        assertEquals("spec-1", preview.path("targetSpecIds").get(0).asText());
        verify(fuzzService, never()).deleteTask(1L, 9L);

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", preview.path("impactToken").asText());
        JsonNode json = objectMapper.readTree(dismissFuzzTaskTool.execute(
                "{\"taskId\":9,\"confirmed\":true,\"impactToken\":\""
                        + preview.path("impactToken").asText() + "\"}"));

        assertEquals(true, json.path("dismissed").asBoolean());
        verify(fuzzService).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_activeTaskBusinessErrorSurfaces() throws Exception {
        when(fuzzService.getTask(1L, 9L)).thenReturn(
                FuzzTaskDto.builder().id(9L).status("PENDING").build());

        JsonNode json = objectMapper.readTree(
                dismissFuzzTaskTool.execute("{\"taskId\":9,\"confirmed\":false}"));

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
        verify(fuzzService, never()).deleteTask(1L, 9L);
    }

    @Test
    void dismiss_confirmWithoutUserConfirmationReturnsFreshPreview() throws Exception {
        when(fuzzService.getTask(1L, 9L)).thenReturn(
                FuzzTaskDto.builder().id(9L).status("CANCELLED").build());

        JsonNode json = objectMapper.readTree(dismissFuzzTaskTool.execute(
                "{\"taskId\":9,\"confirmed\":true,\"impactToken\":\"untrusted\"}"));

        assertTrue(json.path("requiresUserConfirmation").asBoolean());
        assertEquals("preview", json.path("operation").asText());
        verify(fuzzService, never()).deleteTask(1L, 9L);
    }

    @Test
    void getFinding_notFoundSurfacesBusinessError() throws Exception {
        when(fuzzService.getFinding(1L, 99L))
                .thenThrow(new ResourceNotFoundException("Counterexample search finding", 99L));

        JsonNode json = objectMapper.readTree(getFuzzFindingTool.execute("{\"findingId\":99}"));

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(404, json.path("status").asInt());
    }

    private TraceStateDto state(int index) {
        return TraceStateDto.builder()
                .stateIndex(index)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .trustPrivacies(List.of())
                .envVariables(List.of())
                .globalVariables(List.of())
                .build();
    }

    private FuzzInputEventDto event(int step) {
        return FuzzInputEventDto.builder()
                .step(step)
                .kind("environment")
                .targetId("temperature")
                .property("value")
                .value(Integer.toString(step))
                .source("test")
                .build();
    }

    private SpecificationDto violatedSpecification() {
        SpecificationDto specification = new SpecificationDto();
        specification.setId("s1");
        specification.setName("Safety invariant");
        specification.setTemplateId("1");
        specification.setTemplateLabel("Safety invariant");
        specification.setFormula("AG safe");
        return specification;
    }
}
