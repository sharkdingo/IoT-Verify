package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class CancelVerifyTaskTool extends AbstractAiTool {

    private final VerificationService verificationService;

    public CancelVerifyTaskTool(VerificationService verificationService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "cancel_verify_task";
    }

    @Override
    public LlmToolSpec getDefinition() {
        return LlmToolSpec.of(getName(), "Cancel an async verification task by taskId.",
                AiTaskToolSupport.cancelSchema("Verification task ID."));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        return AiTaskToolSupport.cancel(this, argsJson, "verification", "checker", "result",
                taskId -> verificationService.cancelTask(userId, taskId));
    }
}
