package cn.edu.nju.Iot_Verify.component.aitool.task;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationTaskSummaryDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.FuzzService;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Lets the assistant discover task IDs created in any conversation or from the Run History UI. */
@Slf4j
@Component
public class ListAsyncTasksTool extends AbstractAiTool {

    private final VerificationService verificationService;
    private final SimulationService simulationService;
    private final FuzzService fuzzService;

    public ListAsyncTasksTool(VerificationService verificationService,
                              SimulationService simulationService,
                              FuzzService fuzzService,
                              ObjectMapper objectMapper) {
        super(objectMapper);
        this.verificationService = verificationService;
        this.simulationService = simulationService;
        this.fuzzService = fuzzService;
    }

    @Override
    public String getName() {
        return "list_async_tasks";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", Map.of(
                "type", "string",
                "enum", List.of("all", "verification", "simulation", "counterexample_search"),
                "description", "Task kind to include (default all)."));
        properties.put("status", Map.of(
                "type", "string",
                "enum", List.of("all", "PENDING", "RUNNING", "FAILED", "CANCELLED"),
                "description", "Optional exact lifecycle status such as PENDING, RUNNING, FAILED, or CANCELLED; use all to disable this filter."));
        properties.put("initiator", Map.of(
                "type", "string",
                "enum", List.of("all", "USER", "AI_ASSISTANT"),
                "description", "Who started the task (default all)."));
        properties.put("limit", Map.of(
                "type", "integer", "minimum", 1, "maximum", 100,
                "description", "Maximum combined results, newest first (default 25)."));
        return LlmToolSpec.of(getName(),
                "List background verification, simulation, and bounded counterexample-search tasks across all conversations. Use this to discover a taskId before polling, cancelling, or dismissing a task.",
                new FunctionParameterSchema("object", properties, List.of()));
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("kind", "status", "initiator", "limit"));
            String kind = optionalEnumArg(args, "kind", "all",
                    Set.of("all", "verification", "simulation", "counterexample_search"));
            String status = optionalTextArg(args, "status", "all", 40).toUpperCase(Locale.ROOT);
            if (!Set.of("ALL", "PENDING", "RUNNING", "FAILED", "CANCELLED")
                    .contains(status)) {
                throw new ArgValidationException(errorJson(
                        "status must be one of: all, PENDING, RUNNING, FAILED, CANCELLED. Completed results belong to run history.",
                        "VALIDATION_ERROR", 400));
            }
            String initiator = optionalTextArg(args, "initiator", "all", 40).toUpperCase(Locale.ROOT);
            if (!Set.of("ALL", "USER", "AI_ASSISTANT").contains(initiator)) {
                throw new ArgValidationException(errorJson(
                        "initiator must be one of: all, USER, AI_ASSISTANT.",
                        "VALIDATION_ERROR", 400));
            }
            int limit = intArgInRange(args, "limit", 25, 1, 100);

            List<Map<String, Object>> tasks = new ArrayList<>();
            if (Set.of("all", "verification").contains(kind)) {
                for (VerificationTaskSummaryDto task : safeList(
                        verificationService.getTasks(userId, List.of()))) {
                    if (task != null) tasks.add(verificationSummary(task));
                }
            }
            if (Set.of("all", "simulation").contains(kind)) {
                for (SimulationTaskSummaryDto task : safeList(
                        simulationService.getTasks(userId, List.of()))) {
                    if (task != null) tasks.add(simulationSummary(task));
                }
            }
            if (Set.of("all", "counterexample_search").contains(kind)) {
                int matchingFuzzTasks = 0;
                for (int page = 0; ; page++) {
                    List<FuzzTaskSummaryDto> batch = safeList(fuzzService.getTasks(userId, List.of(), page, 100));
                    for (FuzzTaskSummaryDto task : batch) {
                        if (task == null) continue;
                        Map<String, Object> summary = fuzzSummary(task);
                        tasks.add(summary);
                        if (matchesFilter(summary, status, initiator)) matchingFuzzTasks++;
                    }
                    if (batch.size() < 100 || matchingFuzzTasks >= limit) break;
                }
            }

            tasks.removeIf(task -> !"ALL".equals(status)
                    && !status.equals(String.valueOf(task.get("status")).toUpperCase(Locale.ROOT)));
            tasks.removeIf(task -> !"ALL".equals(initiator)
                    && !initiator.equals(String.valueOf(task.get("initiator")).toUpperCase(Locale.ROOT)));
            tasks.sort(Comparator.comparing(
                    task -> (LocalDateTime) task.get("createdAt"),
                    Comparator.nullsLast(Comparator.reverseOrder())));
            List<Map<String, Object>> visible = List.copyOf(tasks.subList(0, Math.min(limit, tasks.size())));
            return readOnlySuccessJson(Map.of(
                    "message", visible.isEmpty()
                            ? "No matching background tasks found."
                            : "Found " + visible.size() + " matching background task(s), newest first.",
                    "count", visible.size(),
                    "tasks", visible),
                    "Background tasks loaded.");
        } catch (ArgParseException e) {
            return e.getErrorResponse();
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_async_tasks failed", e);
            return errorJson("Failed to list background tasks.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> verificationSummary(VerificationTaskSummaryDto task) {
        Map<String, Object> result = commonSummary(
                "verification", task.getId(), task.getInitiator(), task.getStatus(),
                task.getProgress(), task.getProgressStage(), task.getCreatedAt(), task.getErrorMessage());
        putIfNotNull(result, "outcome", task.getOutcome());
        putIfNotNull(result, "violatedSpecCount", task.getViolatedSpecCount());
        return result;
    }

    private Map<String, Object> simulationSummary(SimulationTaskSummaryDto task) {
        Map<String, Object> result = commonSummary(
                "simulation", task.getId(), task.getInitiator(), task.getStatus(),
                task.getProgress(), task.getProgressStage(), task.getCreatedAt(), task.getErrorMessage());
        putIfNotNull(result, "simulationTraceId", task.getSimulationTraceId());
        putIfNotNull(result, "completedSteps", task.getSteps());
        return result;
    }

    private Map<String, Object> fuzzSummary(FuzzTaskSummaryDto task) {
        Map<String, Object> result = commonSummary(
                "counterexample_search", task.getId(), task.getInitiator(), task.getStatus(),
                task.getProgress(), task.getProgressStage(), task.getCreatedAt(), task.getErrorMessage());
        putIfNotNull(result, "runId", task.getRunId());
        putIfNotNull(result, "outcome", task.getOutcome());
        return result;
    }

    private Map<String, Object> commonSummary(String kind,
                                               Long taskId,
                                               Object initiator,
                                               String status,
                                               Integer progress,
                                               Object progressStage,
                                               LocalDateTime createdAt,
                                               String errorMessage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("taskId", taskId);
        putIfNotNull(result, "initiator", initiator);
        putIfNotNull(result, "status", status);
        putIfNotNull(result, "progress", progress);
        putIfNotNull(result, "progressStage", progressStage);
        putIfNotNull(result, "createdAt", createdAt);
        putIfNotNull(result, "errorMessage", trimToNull(errorMessage));
        return result;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private boolean matchesFilter(Map<String, Object> task, String status, String initiator) {
        return ("ALL".equals(status) || status.equals(String.valueOf(task.get("status")).toUpperCase(Locale.ROOT)))
                && ("ALL".equals(initiator)
                || initiator.equals(String.valueOf(task.get("initiator")).toUpperCase(Locale.ROOT)));
    }
}
