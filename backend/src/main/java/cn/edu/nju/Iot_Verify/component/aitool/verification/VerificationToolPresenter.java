package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.dto.verification.SpecResultDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceSummaryDto;
import cn.edu.nju.Iot_Verify.component.aitool.ModelTraceToolPresenter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared user-semantic projections for verification tools. */
final class VerificationToolPresenter {

    private VerificationToolPresenter() {
    }

    static Map<String, Object> traceSummary(TraceSummaryDto trace, Long runId, boolean runAvailable) {
        boolean available = runAvailable && Boolean.TRUE.equals(trace.getDataAvailable());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceId", trace.getId());
        summary.put("runId", runId);
        summary.put("dataAvailable", available);
        summary.put("createdAt", trace.getCreatedAt());
        if (available) {
            summary.put("violatedSpecification",
                    ModelTraceToolPresenter.violatedSpecification(trace.getViolatedSpec()));
            summary.put("stateCount", trace.getStateCount());
        } else {
            summary.put("unavailableReasonCode", "PERSISTED_SEMANTIC_DATA_INVALID");
        }
        return summary;
    }

    /** Persistence ids are unnecessary for interpretation and follow-up tools use run/trace ids. */
    static List<Map<String, Object>> specResults(List<SpecResultDto> specResults) {
        if (specResults == null || specResults.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> presented = new ArrayList<>(specResults.size());
        for (SpecResultDto specResult : specResults) {
            if (specResult == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("specificationLabel", specResult.getSpecificationLabel());
            row.put("formulaPreview", specResult.getFormulaPreview());
            row.put("formulaKind", specResult.getFormulaKind());
            row.put("outcome", specResult.getOutcome());
            row.put("antecedentReachability", specResult.getAntecedentReachability());
            row.put("checkedExpression", specResult.getExpression());
            presented.add(row);
        }
        return List.copyOf(presented);
    }
}
