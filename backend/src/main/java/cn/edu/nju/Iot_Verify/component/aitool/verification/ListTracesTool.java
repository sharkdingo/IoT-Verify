package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
public class ListTracesTool extends AbstractAiTool {

    private final VerificationService verificationService;
    private final ChatExecutionConfig chatExecutionConfig;

    public ListTracesTool(VerificationService verificationService, ObjectMapper objectMapper,
                          ChatExecutionConfig chatExecutionConfig) {
        super(objectMapper);
        this.verificationService = verificationService;
        this.chatExecutionConfig = chatExecutionConfig;
    }

    @Override
    public String getName() {
        return "list_traces";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema("object", Map.of(
                "offset", Map.of("type", "integer", "minimum", 0,
                        "description", "Zero-based trace summary offset (default 0)."),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100,
                        "description", "Maximum summaries to return (default 20). Continue from nextOffset while hasMore is true.")),
                Collections.emptyList());

        return LlmToolSpec.of(getName(), "Page saved verification counterexample summaries, newest run first. " +
                "Traces are saved when verification finds property violations. Use each traceId with get_trace " +
                "to read its state sequence; continue from nextOffset while hasMore is true.", schema);
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
            requireOnlyFields(args, "arguments", Set.of("offset", "limit"));
            int offset = intArgInRange(args, "offset", 0, 0, Integer.MAX_VALUE);
            int limit = intArgInRange(args, "limit", 20, 1, 100);

            List<VerificationRunSummaryDto> runs = verificationService.getRuns(userId);
            List<TraceRow> rows = new ArrayList<>();
            for (VerificationRunSummaryDto run : runs != null ? runs : List.<VerificationRunSummaryDto>of()) {
                if (run == null || run.getCounterexamples() == null) continue;
                for (TraceSummaryDto trace : run.getCounterexamples()) {
                    if (trace == null) continue;
                    rows.add(new TraceRow(run, trace));
                }
            }
            int totalCount = rows.size();
            int start = Math.min(offset, totalCount);
            int end = (int) Math.min(totalCount, (long) start + limit);
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (TraceRow row : rows.subList(start, end)) {
                VerificationRunSummaryDto run = row.run();
                TraceSummaryDto trace = row.trace();
                boolean available = Boolean.TRUE.equals(run.getDataAvailable())
                        && Boolean.TRUE.equals(trace.getDataAvailable());
                Map<String, Object> summary = VerificationToolPresenter.traceSummary(
                        trace, run.getId(), Boolean.TRUE.equals(run.getDataAvailable()));
                if (available) {
                    summary.put("modelComplete", run.getModelComplete());
                    summary.put("disabledRuleCount", run.getDisabledRuleCount());
                    summary.put("skippedSpecCount", run.getSkippedSpecCount());
                    summary.put("generationIssues", run.getGenerationIssues());
                }
                summaries.add(summary);
            }
            while (true) {
                int returned = summaries.size();
                long unavailable = summaries.stream()
                        .filter(trace -> !Boolean.TRUE.equals(trace.get("dataAvailable"))).count();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", totalCount == 0
                        ? "No verification traces found. Traces are created when verification detects property violations."
                        : "Found " + totalCount + " saved counterexample trace(s); showing " + returned
                                + " summary row(s). Use get_trace for state sequences.");
                body.put("count", returned);
                body.put("totalCount", totalCount);
                body.put("availableCount", returned - unavailable);
                body.put("unavailableCount", unavailable);
                body.put("offset", offset);
                body.put("hasMore", (long) start + returned < totalCount);
                if ((long) start + returned < totalCount) body.put("nextOffset", start + returned);
                body.put("traces", List.copyOf(summaries));
                body.put("resultAvailable", true);
                body.put("resultStatus", "SUCCESS");
                String response = readOnlySuccessJson(body, "Saved counterexample summaries loaded.");
                if (response.getBytes(StandardCharsets.UTF_8).length <= chatExecutionConfig.getMaxToolResultBytes()) {
                    return response;
                }
                if (summaries.size() <= 1) {
                    Map<String, Object> unavailableResult = new LinkedHashMap<>();
                    unavailableResult.put("resultStatus", "RESULT_UNAVAILABLE");
                    unavailableResult.put("resultAvailable", false);
                    unavailableResult.put("mutationMayHaveCommitted", false);
                    unavailableResult.put("errorCode", "TOOL_RESULT_TOO_LARGE");
                    unavailableResult.put("message", "A trace summary exceeds the chat result limit; read the trace by traceId, then continue at nextOffset.");
                    unavailableResult.put("traceId", summaries.get(0).get("traceId"));
                    unavailableResult.put("runId", summaries.get(0).get("runId"));
                    unavailableResult.put("totalCount", totalCount);
                    unavailableResult.put("offset", offset);
                    unavailableResult.put("nextOffset", start + 1);
                    unavailableResult.put("hasMore", (long) start + 1 < totalCount);
                    return readOnlySuccessJson(unavailableResult, "Trace summary too large.");
                }
                summaries.remove(summaries.size() - 1);
            }
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("list_traces busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("list_traces business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_traces failed", e);
            return errorJson("Failed to list traces.", "INTERNAL_ERROR", 500);
        }
    }

    private record TraceRow(VerificationRunSummaryDto run, TraceSummaryDto trace) {
    }
}
