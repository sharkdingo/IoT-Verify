package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsVersion;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskDto;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class VerifyTaskStatusTool extends AbstractAiTool {

    private final VerificationService verificationService;

    public VerifyTaskStatusTool(VerificationService verificationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "verify_task_status";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Query async verification task status and progress by taskId, waiting briefly for active work rather than repeatedly polling through the model. PENDING or RUNNING means keep waiting, not a verification conclusion. A completed task exposes its runId for get_verification_run. Raw NuSMV output and execution logs are intentionally omitted. "
                        + VerificationToolPresenter.MODEL_COMPARISON_GUIDANCE,
                AiTaskToolSupport.statusSchema("Verification task ID returned by verify_model_async."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.status(this, argsJson, "verification",
                "Verification status waiting stopped; the verification task was not cancelled.",
                taskId -> verificationService.getTask(userId, taskId),
                this::presentTask);
    }

    private void presentTask(Map<String, Object> body, long taskId, VerificationTaskDto task, int progress) {
        body.put("task", taskProjection(task, progress));
        if ("COMPLETED".equals(task.getStatus())) {
            body.put("runId", taskId);
            body.put("nextTool", "get_verification_run");
        }
    }

    private Map<String, Object> taskProjection(VerificationTaskDto task, int progress) {
        Map<String, Object> projected = new LinkedHashMap<>();
        projected.put("id", task.getId());
        projected.put("initiator", task.getInitiator());
        projected.put("status", task.getStatus());
        projected.put("progress", progress);
        projected.put("progressStage", task.getProgressStage());
        projected.put("createdAt", task.getCreatedAt());
        projected.put("startedAt", task.getStartedAt());
        projected.put("completedAt", task.getCompletedAt());
        projected.put("processingTimeMs", task.getProcessingTimeMs());
        projected.put("isAttack", task.getIsAttack());
        projected.put("attackBudget", task.getAttackBudget());
        projected.put("enablePrivacy", task.getEnablePrivacy());
        projected.put("modelSemantics", task.getModelSemantics());
        projected.put("modelSnapshot", task.getModelSnapshot());
        projected.put("modelSemanticsCurrent", ModelSemanticsVersion.isCurrent(task.getModelSnapshot()));
        projected.put("outcome", task.getOutcome());
        projected.put("modelComplete", task.getModelComplete());
        projected.put("violatedSpecCount", task.getViolatedSpecCount());
        projected.put("disabledRuleCount", task.getDisabledRuleCount());
        projected.put("skippedSpecCount", task.getSkippedSpecCount());
        projected.put("generationIssues", safeList(task.getGenerationIssues()));
        projected.put("specResults", VerificationToolPresenter.specResults(task.getSpecResults()));
        projected.put("checkLogCount", safeList(task.getCheckLogs()).size());
        projected.put("errorMessage", task.getErrorMessage());
        return projected;
    }
}
