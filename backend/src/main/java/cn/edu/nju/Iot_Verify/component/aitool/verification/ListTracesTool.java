package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResponseHelper;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResponseHelper.TracePagePresentation;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationRunSummaryDto;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class ListTracesTool extends AbstractAiTool {

    private static final TracePagePresentation PRESENTATION = new TracePagePresentation(
            "No verification traces found. Traces are created when verification detects property violations.",
            "counterexample trace(s)", " Use get_trace for state sequences.", List.of("traceId", "runId"),
            "A trace summary exceeds the chat result limit; read the trace by traceId, then continue at nextOffset.");

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
        FunctionParameterSchema schema = AiToolResponseHelper.traceSummaryPageSchema(
                "Zero-based trace summary offset (default 0).");

        return LlmToolSpec.of(getName(), "Page saved verification counterexample summaries, newest run first. " +
                "Traces are saved when verification finds property violations. Use each traceId with get_trace " +
                "to read its state sequence; continue from nextOffset while hasMore is true.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            return AiToolResponseHelper.traceSummaryPage(this, argsJson, chatExecutionConfig,
                    () -> traceRows(userId), this::traceSummary, PRESENTATION);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (Exception e) {
            return readOnlyToolFailure(log, "list_traces", e,
                    "Failed to list traces.");
        }
    }

    private List<TraceRow> traceRows(Long userId) {
        List<VerificationRunSummaryDto> runs = verificationService.getRuns(userId);
        List<TraceRow> rows = new ArrayList<>();
        for (VerificationRunSummaryDto run : runs != null ? runs : List.<VerificationRunSummaryDto>of()) {
            if (run == null || run.getCounterexamples() == null) continue;
            for (TraceSummaryDto trace : run.getCounterexamples()) {
                if (trace == null) continue;
                rows.add(new TraceRow(run, trace));
            }
        }
        return rows;
    }

    private Map<String, Object> traceSummary(TraceRow row) {
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
        return summary;
    }

    private record TraceRow(VerificationRunSummaryDto run, TraceSummaryDto trace) {
    }
}
