package cn.edu.nju.Iot_Verify.controller;

import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingReplayDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzPaperDomainPreviewRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzWorkloadPreviewRequestDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.security.CurrentUser;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;

@Validated
@RestController
@RequestMapping("/api/fuzz")
@RequiredArgsConstructor
public class FuzzController {

    private final FuzzService fuzzService;

    @PostMapping("/paper-domain/preview")
    public Result<FuzzPaperDomainPreviewDto> previewPaperDomain(
            @CurrentUser Long userId,
            @Valid @RequestBody FuzzPaperDomainPreviewRequestDto request) {
        return Result.success(fuzzService.previewPaperDomain(userId, request));
    }

    @PostMapping("/workload/preview")
    public Result<FuzzWorkloadPreviewDto> previewWorkload(
            @CurrentUser Long userId,
            @Valid @RequestBody FuzzWorkloadPreviewRequestDto request) {
        return Result.success(fuzzService.previewWorkload(userId, request));
    }

    @PostMapping("/async")
    public Result<FuzzTaskDto> submit(
            @CurrentUser Long userId,
            @Valid @RequestBody FuzzRequestDto request) {
        Long taskId = fuzzService.submit(userId, request);
        return Result.success(fuzzService.getTask(userId, taskId));
    }

    @GetMapping("/tasks")
    public Result<List<FuzzTaskSummaryDto>> getTasks(
            @CurrentUser Long userId,
            @RequestParam(name = "excludeTaskIds", required = false)
            @Size(max = RequestLimits.MAX_TASK_EXCLUSIONS, message = "At most 100 task IDs can be excluded")
            List<@Positive(message = "Excluded task IDs must be positive") Long> excludeTaskIds,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "Page must not be negative")
            @Max(value = 10_000, message = "Page must be at most 10000") int page,
            @RequestParam(name = "size", defaultValue = "100")
            @Min(value = 1, message = "Page size must be at least 1")
            @Max(value = 200, message = "Page size must be at most 200") int size) {
        return Result.success(fuzzService.getTasks(userId, excludeTaskIds, page, size));
    }

    @GetMapping("/tasks/{id}")
    public Result<FuzzTaskDto> getTask(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Task ID must be positive") Long id) {
        return Result.success(fuzzService.getTask(userId, id));
    }

    @GetMapping("/tasks/{id}/progress")
    public Result<Integer> getTaskProgress(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Task ID must be positive") Long id) {
        return Result.success(fuzzService.getTaskProgress(userId, id));
    }

    @PostMapping("/tasks/{id}/cancel")
    public Result<TaskCancellationResultDto> cancelTask(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Task ID must be positive") Long id) {
        return Result.success(fuzzService.cancelTask(userId, id));
    }

    @DeleteMapping("/tasks/{id}")
    public Result<Void> deleteTask(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Task ID must be positive") Long id) {
        fuzzService.deleteTask(userId, id);
        return Result.success();
    }

    @GetMapping("/runs")
    public Result<List<FuzzRunSummaryDto>> getRuns(
            @CurrentUser Long userId,
            @RequestParam(name = "page", defaultValue = "0")
            @Min(value = 0, message = "Page must not be negative")
            @Max(value = 10_000, message = "Page must be at most 10000") int page,
            @RequestParam(name = "size", defaultValue = "25")
            @Min(value = 1, message = "Page size must be at least 1")
            @Max(value = 100, message = "Page size must be at most 100") int size) {
        return Result.success(fuzzService.getRuns(userId, page, size));
    }

    @GetMapping("/runs/{id}")
    public Result<FuzzRunDto> getRun(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Run ID must be positive") Long id) {
        return Result.success(fuzzService.getRun(userId, id));
    }

    @DeleteMapping("/runs/{id}")
    public Result<Void> deleteRun(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Run ID must be positive") Long id) {
        fuzzService.deleteRun(userId, id);
        return Result.success();
    }

    @GetMapping("/runs/{id}/findings")
    public Result<List<FuzzFindingDto>> getFindings(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Run ID must be positive") Long id) {
        return Result.success(fuzzService.getFindings(userId, id));
    }

    @GetMapping("/findings/{id}")
    public Result<FuzzFindingReplayDto> getFinding(
            @CurrentUser Long userId,
            @PathVariable @Positive(message = "Finding ID must be positive") Long id) {
        return Result.success(fuzzService.getFindingReplay(userId, id));
    }
}
