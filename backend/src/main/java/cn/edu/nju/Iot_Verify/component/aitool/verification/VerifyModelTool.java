package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.RunPersistenceDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRequestDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class VerifyModelTool extends AbstractAiTool {

    private final VerificationService verificationService;

    public VerifyModelTool(VerificationService verificationService,
                           ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
    }

    @Override
    public String getName() {
        return "verify_model";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();

        props.put("attackMode", Map.of(
                "type", "string", "enum", List.of("none", "exact", "exhaustive"),
                "description", "Per-run attack selection: none, exact user-selected points, or exhaustive verification of every combination up to attackBudget. Default none."));
        props.put("attackBudget", attackBudgetSchema());
        props.put("attackPoints", attackPointsSchema(true));
        props.put("enablePrivacy", Map.of(
                "type", "boolean",
                "description", "Track public/private sensitivity labels through automation chains. Privacy conditions force this on even when false. This models label propagation, not access control or encryption. Default false."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, Collections.emptyList()
        );

        return LlmToolSpec.of(getName(), "Run NuSMV formal verification on the current board. " +
                "Atomically snapshots all devices, environment values, rules, specifications, and referenced templates from the board. " +
                "Returns whether every emitted specification was satisfied, plus generation warnings " +
                "and property-violation details. " +
                "Requires at least one device and one specification on the board.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of(
                    "attackMode", "attackBudget", "attackPoints", "enablePrivacy"));
            AttackScenarioDto attackScenario = attackScenarioArg(args, true);
            boolean enablePrivacy = booleanArg(args, "enablePrivacy", false);

            VerificationRequestDto request = new VerificationRequestDto();
            request.setAttackScenario(attackScenario);
            request.setEnablePrivacy(enablePrivacy);

            VerificationResultDto result = verificationService.verify(userId, request);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("outcome", result.getOutcome());
            summary.put("modelComplete", result.isModelComplete());
            summary.put("disabledRuleCount", result.getDisabledRuleCount());
            summary.put("skippedSpecCount", result.getSkippedSpecCount());
            summary.put("requestedSpecCount", result.getModelSnapshot() != null
                    ? result.getModelSnapshot().getSpecificationCount() : null);
            summary.put("emittedSpecCount", result.getSpecResults() != null ? result.getSpecResults().size() : 0);
            summary.put("violationCount", countFailedSpecs(result));
            summary.put("specResults", VerificationToolPresenter.specResults(result.getSpecResults()));
            summary.put("generationIssues", result.getGenerationIssues());
            summary.put("isAttack", Boolean.TRUE.equals(result.getIsAttack()));
            summary.put("attackBudget", result.getAttackBudget());
            summary.put("attackScenario", attackScenario);
            summary.put("enablePrivacy", result.isEnablePrivacy());
            summary.put("modelSemantics", result.getModelSemantics());
            summary.put("modelSnapshot", result.getModelSnapshot());
            summary.put("historyPersistence", result.getHistoryPersistence());

            if (result.getOutcome() == VerificationOutcome.VIOLATED && result.getTraces() != null) {
                summary.put("traceCount", result.getTraces().size());
                List<Map<String, Object>> traceSummaries = new ArrayList<>();
                for (TraceDto trace : result.getTraces()) {
                    Map<String, Object> ts = new LinkedHashMap<>();
                    ts.put("traceId", trace.getId());
                    SpecificationDto violatedSpec = trace.getViolatedSpec();
                    if (violatedSpec != null) {
                        ts.put("violatedSpecificationLabel", violatedSpec.getTemplateLabel());
                        ts.put("formulaPreview", violatedSpec.getFormula());
                    }
                    ts.put("stateCount", trace.getStates() != null ? trace.getStates().size() : 0);
                    traceSummaries.add(ts);
                }
                summary.put("traces", traceSummaries);
            }

            summary.put("checkLogCount", result.getCheckLogs() != null
                    ? result.getCheckLogs().size() : 0);

            String message = switch (result.getOutcome() != null
                    ? result.getOutcome() : VerificationOutcome.INCONCLUSIVE) {
                case SATISFIED -> result.isModelComplete()
                        ? "All emitted specifications were satisfied on the complete generated model."
                        : "Emitted specifications were satisfied, but the generated model was incomplete.";
                case VIOLATED -> "Verification found one or more specification violations.";
                case INCONCLUSIVE -> "Verification was inconclusive; no safety conclusion was produced.";
            };
            message = appendHistoryPersistenceNotice(message, result.getHistoryPersistence());
            summary.put("message", message);
            return successJson(summary, message);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("verify_model busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (SmvGenerationException e) {
            log.warn("verify_model generation failed [{}]: {}", e.getErrorCategory(), e.getMessage());
            return errorJson(e.getMessage(),
                    "SMV_GENERATION_ERROR",
                    500,
                    Map.of("errorCategory", e.getErrorCategory()));
        } catch (BaseException e) {
            log.warn("verify_model business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("verify_model failed", e);
            return errorJson("Verification failed.", "INTERNAL_ERROR", 500);
        }
    }

    private int countFailedSpecs(VerificationResultDto result) {
        if (result == null || result.getSpecResults() == null) {
            return 0;
        }
        return (int) result.getSpecResults().stream()
                .filter(specResult -> specResult != null
                        && specResult.getOutcome() == VerificationOutcome.VIOLATED)
                .count();
    }

    private String appendHistoryPersistenceNotice(String message, RunPersistenceDto persistence) {
        if (persistence == null || persistence.getStatus() == null) {
            return message + " Run-history persistence status was unavailable.";
        }
        return switch (persistence.getStatus()) {
            case SAVED -> message + " The result was added to run history.";
            case FAILED -> message + " The result was produced but was not added to run history.";
            case OUTCOME_UNKNOWN -> message
                    + " The result was produced, but whether it entered run history could not be confirmed; refresh history before retrying.";
            case NOT_REQUESTED -> message + " No run-history save was requested.";
        };
    }

}
