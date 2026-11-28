package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskDto;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Removes a failed or cancelled verification task after an impact-bound confirmation. */
@Component
public class DismissVerifyTaskTool extends AbstractAiTool {

    private final VerificationService verificationService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DismissVerifyTaskTool(VerificationService verificationService,
                                 ObjectMapper objectMapper,
                                 AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.verificationService = verificationService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "dismiss_verify_task";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(),
                "Preview or, after explicit two-turn confirmation, dismiss a failed or cancelled verification task and its diagnostics. Active tasks must be cancelled first, and completed results are removed with delete_verification_run, not here.",
                AiTaskToolSupport.dismissSchema("Failed or cancelled verification task ID to remove from the task inbox."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.dismiss(this, argsJson, userId, "verification", destructiveActionGuard,
                taskId -> verificationService.getTask(userId, taskId),
                taskId -> verificationService.deleteTask(userId, taskId),
                this::appendDiagnostics);
    }

    private void appendDiagnostics(Map<String, Object> summary, VerificationTaskDto task) {
        summary.put("checkLogCount", safeList(task.getCheckLogs()).size());
        summary.put("generationIssueCount", safeList(task.getGenerationIssues()).size());
        summary.put("hasModelSnapshot", task.getModelSnapshot() != null);
        summary.put("hasNuSmvOutput", trimToNull(task.getNusmvOutput()) != null);
    }
}
