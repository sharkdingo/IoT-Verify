package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingReplayDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewRequestDto;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FuzzControllerTest {

    @Mock private FuzzService fuzzService;

    @Test
    void paperDomainPreviewUsesCanonicalRouteAndResultEnvelope() throws Exception {
        FuzzController controller = new FuzzController(fuzzService);
        FuzzPaperDomainPreviewRequestDto request =
                FuzzPaperDomainPreviewRequestDto.builder().pathLength(12).build();
        FuzzPaperDomainPreviewDto preview = FuzzPaperDomainPreviewDto.builder()
                .pathLength(12)
                .initializationPolicy("RANDOM_LEGAL_PER_SEED")
                .build();
        when(fuzzService.previewPaperDomain(7L, request)).thenReturn(preview);

        Result<FuzzPaperDomainPreviewDto> result = controller.previewPaperDomain(7L, request);
        Method method = FuzzController.class.getMethod(
                "previewPaperDomain", Long.class, FuzzPaperDomainPreviewRequestDto.class);

        assertEquals(List.of("/paper-domain/preview"),
                List.of(method.getAnnotation(PostMapping.class).value()));
        assertEquals(200, result.getCode());
        assertSame(preview, result.getData());
        verify(fuzzService).previewPaperDomain(7L, request);
    }

    @Test
    void workloadPreviewUsesCanonicalRouteAndResultEnvelope() throws Exception {
        FuzzController controller = new FuzzController(fuzzService);
        FuzzWorkloadPreviewRequestDto request = FuzzWorkloadPreviewRequestDto.builder()
                .maxIterations(100)
                .pathLength(8)
                .populationSize(4)
                .build();
        FuzzWorkloadPreviewDto preview = FuzzWorkloadPreviewDto.builder()
                .estimatedWorkload(39_200L)
                .workloadLimit(12_500_000L)
                .accepted(true)
                .build();
        when(fuzzService.previewWorkload(7L, request)).thenReturn(preview);

        Result<FuzzWorkloadPreviewDto> result = controller.previewWorkload(7L, request);
        Method method = FuzzController.class.getMethod(
                "previewWorkload", Long.class, FuzzWorkloadPreviewRequestDto.class);

        assertEquals(List.of("/workload/preview"),
                List.of(method.getAnnotation(PostMapping.class).value()));
        assertEquals(200, result.getCode());
        assertSame(preview, result.getData());
        verify(fuzzService).previewWorkload(7L, request);
    }

    @Test
    void submitPreservesDefaultAndExplicitExplorationModesInAcceptedTaskResponses() {
        FuzzController controller = new FuzzController(fuzzService);
        FuzzRequestDto defaultRequest = new FuzzRequestDto();
        FuzzRequestDto paperRequest = new FuzzRequestDto();
        paperRequest.setExplorationMode(FuzzExplorationMode.PAPER_COMPATIBLE);
        paperRequest.setPaperDomainFingerprint("a".repeat(64));
        FuzzTaskDto boardTask = FuzzTaskDto.builder()
                .id(11L)
                .explorationMode(FuzzExplorationMode.BOARD_SNAPSHOT)
                .build();
        FuzzTaskDto paperTask = FuzzTaskDto.builder()
                .id(12L)
                .explorationMode(FuzzExplorationMode.PAPER_COMPATIBLE)
                .build();
        when(fuzzService.submit(7L, defaultRequest)).thenReturn(11L);
        when(fuzzService.getTask(7L, 11L)).thenReturn(boardTask);
        when(fuzzService.submit(7L, paperRequest)).thenReturn(12L);
        when(fuzzService.getTask(7L, 12L)).thenReturn(paperTask);

        Result<FuzzTaskDto> defaultResult = controller.submit(7L, defaultRequest);
        Result<FuzzTaskDto> paperResult = controller.submit(7L, paperRequest);

        assertEquals(FuzzExplorationMode.BOARD_SNAPSHOT,
                defaultResult.getData().getExplorationMode());
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE,
                paperResult.getData().getExplorationMode());
        verify(fuzzService).submit(7L, defaultRequest);
        verify(fuzzService).submit(7L, paperRequest);
    }

    @Test
    void listEndpointsPreserveResultEnvelopeAndPaginationArguments() {
        FuzzController controller = new FuzzController(fuzzService);
        List<FuzzTaskSummaryDto> tasks = List.of(FuzzTaskSummaryDto.builder()
                .id(1L)
                .explorationMode(FuzzExplorationMode.PAPER_COMPATIBLE)
                .build());
        List<FuzzRunSummaryDto> runs = List.of(FuzzRunSummaryDto.builder()
                .id(2L)
                .explorationMode(FuzzExplorationMode.BOARD_SNAPSHOT)
                .build());
        when(fuzzService.getTasks(7L, List.of(9L), 2, 50)).thenReturn(tasks);
        when(fuzzService.getRuns(7L, 3, 25)).thenReturn(runs);

        Result<List<FuzzTaskSummaryDto>> taskResult = controller.getTasks(
                7L, List.of(9L), 2, 50);
        Result<List<FuzzRunSummaryDto>> runResult = controller.getRuns(7L, 3, 25);

        assertEquals(200, taskResult.getCode());
        assertEquals("success", taskResult.getMessage());
        assertSame(tasks, taskResult.getData());
        assertEquals(200, runResult.getCode());
        assertSame(runs, runResult.getData());
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE,
                taskResult.getData().get(0).getExplorationMode());
        assertEquals(FuzzExplorationMode.BOARD_SNAPSHOT,
                runResult.getData().get(0).getExplorationMode());
        verify(fuzzService).getTasks(7L, List.of(9L), 2, 50);
        verify(fuzzService).getRuns(7L, 3, 25);
    }

    @Test
    void findingDetailUsesTheCanonicalTopLevelRoute() throws Exception {
        RequestMapping root = FuzzController.class.getAnnotation(RequestMapping.class);
        Method method = FuzzController.class.getMethod("getFinding", Long.class, Long.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        FuzzController controller = new FuzzController(fuzzService);
        FuzzFindingReplayDto replay = FuzzFindingReplayDto.builder()
                .finding(FuzzFindingDto.builder().id(8L).fuzzTaskId(3L).build())
                .build();
        when(fuzzService.getFindingReplay(7L, 8L)).thenReturn(replay);

        Result<FuzzFindingReplayDto> result = controller.getFinding(7L, 8L);

        assertEquals(List.of("/api/fuzz"), List.of(root.value()));
        assertEquals(List.of("/findings/{id}"), List.of(mapping.value()));
        assertEquals(200, result.getCode());
        assertSame(replay, result.getData());
        verify(fuzzService).getFindingReplay(7L, 8L);
    }

    @Test
    void controllerConstraintsBoundPaginationExclusionsAndPathIds() throws Exception {
        FuzzController controller = new FuzzController(fuzzService);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Method getTasks = FuzzController.class.getMethod(
                "getTasks", Long.class, List.class, int.class, int.class);
        Method getRun = FuzzController.class.getMethod("getRun", Long.class, Long.class);
        List<Long> tooManyExclusions = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();

        Set<ConstraintViolation<FuzzController>> listViolations = validator.forExecutables()
                .validateParameters(controller, getTasks,
                        new Object[]{7L, tooManyExclusions, -1, 201});
        Set<ConstraintViolation<FuzzController>> idViolations = validator.forExecutables()
                .validateParameters(controller, getRun, new Object[]{7L, 0L});

        assertEquals(3, listViolations.size());
        assertEquals(1, idViolations.size());
    }
}
