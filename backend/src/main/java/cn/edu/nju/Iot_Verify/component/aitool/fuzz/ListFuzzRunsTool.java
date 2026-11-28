package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
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
public class ListFuzzRunsTool extends AbstractAiTool {

    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 100;

    private final FuzzService fuzzService;

    public ListFuzzRunsTool(FuzzService fuzzService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.fuzzService = fuzzService;
    }

    @Override
    public String getName() {
        return "list_fuzz_runs";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("page", Map.of("type", "integer",
                "description", "Zero-based page index (default 0)."));
        props.put("size", Map.of("type", "integer",
                "description", "Page size, 1-100 (default 25)."));
        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, List.of());
        return LlmToolSpec.of(getName(),
                "List completed counterexample-search runs (history), newest first. Each run summary reports its outcome, effective seed, and finding count. Use get_fuzz_run for eligibility and limitations. Findings are heuristic candidate evidence, not formal counterexamples.",
                schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("page", "size"));
            int page = intArgInRange(args, "page", 0, 0, 10_000);
            int size = intArgInRange(args, "size", DEFAULT_SIZE, 1, MAX_SIZE);

            List<FuzzRunSummaryDto> runs = safeList(fuzzService.getRuns(userId, page, size));
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (FuzzRunSummaryDto run : runs) {
                if (run == null) continue;
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("runId", run.getId());
                summary.put("dataAvailable", run.isDataAvailable());
                summary.put("createdAt", run.getCreatedAt());
                if (!run.isDataAvailable()) {
                    summary.put("unavailableReasonCode", run.getUnavailableReasonCode());
                } else {
                    summary.put("outcome", run.getOutcome());
                    summary.put("explorationMode", run.getExplorationMode());
                    summary.put("effectiveSeed", run.getEffectiveSeed());
                    summary.put("iterations", run.getIterations());
                    summary.put("findingCount", run.getFindingCount());
                    summary.put("completedAt", run.getCompletedAt());
                }
                summaries.add(summary);
            }

            List<Map<String, Object>> list = List.copyOf(summaries);
            if (list.isEmpty()) {
                return readOnlySuccessJson(Map.of(
                        "message", "No counterexample-search runs found. Start one with fuzz_model_async.",
                        "count", 0,
                        "runs", list
                ));
            }
            long unavailable = list.stream()
                    .filter(run -> !Boolean.TRUE.equals(run.get("dataAvailable")))
                    .count();
            return readOnlySuccessJson(Map.of(
                    "message", "Found " + list.size() + " counterexample-search run(s); "
                            + unavailable + " unavailable due to stored-data errors.",
                    "count", list.size(),
                    "availableCount", list.size() - unavailable,
                    "unavailableCount", unavailable,
                    "runs", list
            ));
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("list_fuzz_runs busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("list_fuzz_runs business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_fuzz_runs failed", e);
            return errorJson("Failed to list counterexample-search runs.", "INTERNAL_ERROR", 500);
        }
    }
}
