package cn.edu.nju.Iot_Verify.service;

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
import cn.edu.nju.Iot_Verify.dto.model.RunDeletionImpactDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;

import java.util.List;

public interface FuzzService {

    FuzzPaperDomainPreviewDto previewPaperDomain(
            Long userId, FuzzPaperDomainPreviewRequestDto request);

    FuzzWorkloadPreviewDto previewWorkload(
            Long userId, FuzzWorkloadPreviewRequestDto request);

    Long submit(Long userId, FuzzRequestDto request);

    FuzzTaskDto getTask(Long userId, Long taskId);

    List<FuzzTaskSummaryDto> getTasks(
            Long userId, List<Long> excludedTaskIds, int page, int size);

    int getTaskProgress(Long userId, Long taskId);

    TaskCancellationResultDto cancelTask(Long userId, Long taskId);

    void deleteTask(Long userId, Long taskId);

    List<FuzzRunSummaryDto> getRuns(Long userId, int page, int size);

    FuzzRunDto getRun(Long userId, Long runId);

    /** Exact persisted-row impact for an AI deletion preview, without parsing finding payloads. */
    RunDeletionImpactDto getRunDeletionImpact(Long userId, Long runId);

    void deleteRun(Long userId, Long runId);

    /** Delete only when the exact persisted finding count still matches the confirmed preview. */
    long deleteRun(Long userId, Long runId, long expectedFindingCount);

    List<FuzzFindingDto> getFindings(Long userId, Long runId);

    FuzzFindingDto getFinding(Long userId, Long findingId);

    /**
     * Loads one owned candidate finding with the frozen run snapshot needed for read-only replay.
     */
    FuzzFindingReplayDto getFindingReplay(Long userId, Long findingId);
}
