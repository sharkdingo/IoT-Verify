package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class VerifyModelAsyncTool extends AbstractAiTool {

    private final VerificationService verificationService;

    public VerifyModelAsyncTool(VerificationService verificationService,
                                ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "verify_model_async";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("attackMode", Map.of("type", "string", "enum", List.of("none", "exact", "exhaustive"), "description", "Per-run attack selection. Default none."));
        props.put("attackBudget", attackBudgetSchema());
        props.put("attackPoints", attackPointsSchema(true));
        props.put("enablePrivacy", Map.of("type", "boolean", "description", "Track public/private sensitivity labels through automation chains. Privacy conditions force this on even when false. This is not access control or encryption. Default false."));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, Collections.emptyList());

        return LlmToolSpec.of(getName(), "Submit an async NuSMV verification task. Returns its authoritative current task status, progress, frozen model snapshot, and taskId for later polling; acceptance does not mean verification completed.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of(
                    "attackMode", "attackBudget", "attackPoints", "enablePrivacy"));
            VerificationRequestDto request = verificationRequest(args);
            AttackScenarioDto attackScenario = request.getAttackScenario();

            Long taskId = verificationService.submitVerification(userId, request);
            return acceptedAsyncTaskJson(taskId, "Verification", "verify_task_status",
                    "Verification task accepted. Its current status is authoritative; completion is not implied.",
                    () -> verificationService.getTask(userId, taskId), (response, task) -> {
                        response.put("isAttack", task.getIsAttack());
                        response.put("attackBudget", task.getAttackBudget());
                        response.put("attackScenario", attackScenario);
                        response.put("enablePrivacy", task.getEnablePrivacy());
                        response.put("modelSnapshot", task.getModelSnapshot());
                        response.put("modelSemantics", task.getModelSemantics());
                    });
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (AsyncTaskDispatchOutcomeUnknownException e) {
            log.error("Verification task {} dispatch outcome is unknown", e.getTaskId(), e);
            return asyncTaskDispatchOutcomeUnknown(e.getTaskId(), "verify_task_status");
        } catch (ServiceUnavailableException e) {
            log.warn("verify_model_async busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (SmvGenerationException e) {
            log.warn("verify_model_async generation failed [{}]: {}", e.getErrorCategory(), e.getMessage());
            return errorJson(e.getMessage(),
                    "SMV_GENERATION_ERROR",
                    500,
                    Map.of("errorCategory", e.getErrorCategory()));
        } catch (BaseException e) {
            log.warn("verify_model_async business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("verify_model_async failed", e);
            return errorJson("Failed to start verification task.",
                    "INTERNAL_ERROR", 500);
        }
    }

}
