package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.ModelTraceToolPresenter;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class GetFuzzRunTool extends AbstractAiTool {

    private final FuzzService fuzzService;

    public GetFuzzRunTool(FuzzService fuzzService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.fuzzService = fuzzService;
    }

    @Override
    public String getName() {
        return "get_fuzz_run";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                Map.of("runId", Map.of("type", "integer",
                        "description", "Completed counterexample-search run ID (from list_fuzz_runs or a completed task's runId).")),
                List.of("runId"));
        return LlmToolSpec.of(getName(),
                "Get one completed counterexample-search run: outcome, parameters, eligibility, limitations, and its finding summaries. Use get_fuzz_finding for a finding's full state sequence. Findings are heuristic candidate evidence, not formal counterexamples, and budget exhaustion never proves a specification holds.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("runId"));
            long runId = positiveLongArg(args, "runId");

            FuzzRunDto run = fuzzService.getRun(userId, runId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("runId", run.getId());
            body.put("outcome", run.getOutcome());
            body.put("explorationMode", run.getExplorationMode());
            body.put("effectiveSeed", run.getEffectiveSeed());
            body.put("iterations", run.getIterations());
            body.put("generatedPaths", run.getGeneratedPaths());
            body.put("elapsedMs", run.getElapsedMs());
            body.put("maxIterations", run.getMaxIterations());
            body.put("pathLength", run.getPathLength());
            body.put("populationSize", run.getPopulationSize());
            body.put("eligibility", run.getEligibility());
            body.put("limitations", run.getLimitations());
            body.put("targetSpecIds", run.getTargetSpecIds());
            body.put("findingCount", run.getFindingCount());
            body.put("findings", findingSummaries(run.getFindings()));
            body.put("createdAt", run.getCreatedAt());
            body.put("completedAt", run.getCompletedAt());
            body.put("message", "Counterexample-search run loaded. Findings are heuristic candidate evidence, not formal counterexamples.");
            return readOnlySuccessJson(body);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("get_fuzz_run busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("get_fuzz_run business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("get_fuzz_run failed", e);
            return errorJson("Failed to get counterexample-search run.", "INTERNAL_ERROR", 500);
        }
    }

    private List<Map<String, Object>> findingSummaries(List<FuzzFindingDto> findings) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (FuzzFindingDto finding : safeList(findings)) {
            if (finding == null) continue;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("findingId", finding.getId());
            summary.put("violatedSpec", ModelTraceToolPresenter.violatedSpecification(
                    finding.getViolatedSpec()));
            summary.put("firstViolationStep", finding.getFirstViolationStep());
            summary.put("stateCount", safeList(finding.getStates()).size());
            summary.put("seed", finding.getSeed());
            summary.put("createdAt", finding.getCreatedAt());
            summaries.add(summary);
        }
        return summaries;
    }
}
