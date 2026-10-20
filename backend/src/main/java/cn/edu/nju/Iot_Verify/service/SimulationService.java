package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationRequestDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationResultDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceSummaryDto;

import java.util.List;

/**
 * 模拟服务接口
 */
public interface SimulationService {

    /**
     * 执行模拟（不落库）
     */
    SimulationResultDto simulate(Long userId, SimulationRequestDto request);

    /**
     * 校验请求、创建任务并提交异步模拟。请求非法时不会创建任务；队列拒绝时会
     * 将已创建任务标记为失败并抛出 ServiceUnavailableException。
     */
    Long submitSimulation(Long userId, SimulationRequestDto request);

    SimulationTaskDto getTask(Long userId, Long taskId);

    /**
     * 获取用户的仿真任务收件箱（轻量列表，不含日志和原始输出）。
     */
    List<SimulationTaskSummaryDto> getTasks(Long userId, List<Long> excludedTaskIds);

    /** Remove a failed or cancelled task that produced no history result. */
    void deleteTask(Long userId, Long taskId);

    int getTaskProgress(Long userId, Long taskId);

    TaskCancellationResultDto cancelTask(Long userId, Long taskId);

    /**
     * 执行模拟并持久化
     */
    SimulationTraceDto simulateAndSave(Long userId, SimulationRequestDto request);

    /**
     * 获取用户的所有模拟记录
     */
    List<SimulationTraceSummaryDto> getUserSimulations(Long userId);

    /**
     * 获取单条模拟记录
     */
    SimulationTraceDto getSimulation(Long userId, Long id);

    /**
     * 删除单条模拟记录
     */
    void deleteSimulation(Long userId, Long id);
}
