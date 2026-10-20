package cn.edu.nju.Iot_Verify.component.aitool.fuzz;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRequestDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starts an async bounded counterexample search (fuzz) over the current board snapshot.
 *
 * <p>Only the reproducible {@link FuzzExplorationMode#BOARD_SNAPSHOT} strategy is exposed here.
 * The paper-compatible random-state strategy needs a preview-issued input-range fingerprint whose
 * multi-step handshake does not fit a single chat tool call, so it stays UI-only.
 */
@Slf4j
@Component
public class FuzzModelAsyncTool extends AbstractAiTool {

    private final FuzzService fuzzService;

    public FuzzModelAsyncTool(FuzzService fuzzService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.fuzzService = fuzzService;
    }

    @Override
    public String getName() {
        return "fuzz_model_async";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("targetSpecIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Optional. Exact saved specification id values from board_overview or list_specs, "
                        + "never templateId or display order. Omit to search every eligible specification. "
                        + "Ineligible specs are reported back, not silently dropped."));
        props.put("maxIterations", Map.of(
                "type", "integer",
                "description", "Search budget in iterations, 1-5000 (default 200). A larger budget can be refused: the admission guard multiplies this by path length, population size, and the Board's model complexity. Budget exhaustion never proves a specification holds."));
        props.put("pathLength", Map.of(
                "type", "integer",
                "description", "Bounded exploration depth per candidate path, 1-50 (default 20)."));
        props.put("populationSize", Map.of(
                "type", "integer",
                "description", "Candidate population size per iteration, 1-50 (default 10)."));
        props.put("seed", Map.of(
                "type", "integer",
                "description", "Optional deterministic seed (0 to 9007199254740991). Omit for a fresh random seed."));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, List.of());
        return LlmToolSpec.of(getName(),
                "Submit an async bounded counterexample search over the current board snapshot. Returns the authoritative task status, frozen model snapshot, and taskId for polling; acceptance does not mean the search completed, and a bounded search never proves a specification is satisfied. Findings are heuristic candidate evidence, not formal counterexamples, and must never be routed into fix_violation or apply_fix.",
                schema);
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
                    "targetSpecIds", "maxIterations", "pathLength", "populationSize", "seed"));

            FuzzRequestDto request = FuzzRequestDto.builder()
                    .explorationMode(FuzzExplorationMode.BOARD_SNAPSHOT)
                    .targetSpecIds(parseTargetSpecIds(args))
                    // 200, matching FuzzRequestDto. At 1000 this tool's default product was 200,000, which
                    // every scene in docs/examples refused — so "run bounded exploration" failed with a
                    // VALIDATION_ERROR on every shipped scene, and the rejection carried the ceiling without
                    // the Board's complexity, leaving the model nothing to correct with.
                    .maxIterations(intArgInRange(args, "maxIterations", 200, 1, 5000))
                    .pathLength(intArgInRange(args, "pathLength", 20, 1, 50))
                    .populationSize(intArgInRange(args, "populationSize", 10, 1, 50))
                    .seed(parseSeed(args))
                    .build();

            Long taskId = fuzzService.submit(userId, request);
            if (taskId == null || taskId <= 0) {
                throw new IllegalStateException("Counterexample-search submission returned no usable task id");
            }
            try {
                FuzzTaskDto task = fuzzService.getTask(userId, taskId);
                if (task == null) {
                    throw new IllegalStateException("Accepted counterexample-search task was not readable");
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("message", "Counterexample search accepted. Its current status is authoritative; completion is not implied, and budget exhaustion never proves a specification holds.");
                response.put("taskAccepted", true);
                response.put("taskId", taskId);
                response.put("taskStatus", task.getStatus());
                response.put("progress", task.getProgress());
                response.put("explorationMode", task.getExplorationMode());
                response.put("maxIterations", task.getMaxIterations());
                response.put("pathLength", task.getPathLength());
                response.put("populationSize", task.getPopulationSize());
                response.put("seed", task.getSeed());
                response.put("targetSpecIds", task.getTargetSpecIds());
                response.put("modelSnapshot", task.getModelSnapshot());
                return acceptedAsyncTaskJson(
                        response, taskId, "fuzz_task_status");
            } catch (Exception e) {
                log.error("Counterexample-search task {} was accepted, but its status response is unavailable",
                        taskId, e);
                return acceptedAsyncTaskResultUnavailable(taskId, "fuzz_task_status");
            }
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (AsyncTaskDispatchOutcomeUnknownException e) {
            log.error("Counterexample-search task {} dispatch outcome is unknown", e.getTaskId(), e);
            return asyncTaskDispatchOutcomeUnknown(e.getTaskId(), "fuzz_task_status");
        } catch (ServiceUnavailableException e) {
            log.warn("fuzz_model_async busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("fuzz_model_async business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("fuzz_model_async failed", e);
            return errorJson("Failed to start counterexample search.", "INTERNAL_ERROR", 500);
        }
    }

    private List<String> parseTargetSpecIds(JsonNode args) throws ArgValidationException {
        JsonNode node = args.path("targetSpecIds");
        if (node.isMissingNode() || node.isNull()) {
            return new ArrayList<>();
        }
        if (!node.isArray()) {
            throw new ArgValidationException(errorJson(
                    "targetSpecIds must be a JSON array of specification ids.", "VALIDATION_ERROR", 400));
        }
        if (node.size() > 100) {
            throw new ArgValidationException(errorJson(
                    "At most 100 target specification ids can be selected.",
                    "VALIDATION_ERROR", 400));
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode item : node) {
            if (item == null || !item.isTextual() || trimToNull(item.textValue()) == null) {
                throw new ArgValidationException(errorJson(
                        "targetSpecIds[" + index + "] must be a non-blank specification id.",
                        "VALIDATION_ERROR", 400));
            }
            String id = item.textValue().trim();
            if (id.length() > 100) {
                throw new ArgValidationException(errorJson(
                        "targetSpecIds[" + index + "] must be at most 100 characters.",
                        "VALIDATION_ERROR", 400));
            }
            if (!seen.add(id)) {
                throw new ArgValidationException(errorJson(
                        "targetSpecIds must not contain duplicate specification ids.",
                        "VALIDATION_ERROR", 400));
            }
            ids.add(id);
            index++;
        }
        return ids;
    }

    private Long parseSeed(JsonNode args) throws ArgValidationException {
        JsonNode node = args.path("seed");
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new ArgValidationException(errorJson(
                    "seed must be an integer.", "VALIDATION_ERROR", 400));
        }
        long seed = node.longValue();
        if (seed < 0 || seed > FuzzRequestDto.JS_SAFE_SEED_MAX) {
            throw new ArgValidationException(errorJson(
                    "seed must be between 0 and " + FuzzRequestDto.JS_SAFE_SEED_MAX + ".",
                    "VALIDATION_ERROR", 400));
        }
        return seed;
    }
}
