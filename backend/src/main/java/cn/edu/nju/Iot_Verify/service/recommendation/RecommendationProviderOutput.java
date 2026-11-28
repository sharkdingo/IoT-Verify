package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationFilterItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

final class RecommendationProviderOutput {
    private RecommendationProviderOutput() {}

    static String completeRecommendation(
            PromptCompletionService completion, String systemPrompt, String userPrompt,
            double temperature, int maxTokens, Logger log) {
        String content = completion.completeRecommendation(systemPrompt, userPrompt, temperature, maxTokens);
        if (content == null || content.isBlank()) {
            log.warn("AI returned empty content");
            return "";
        }
        log.debug("AI response content length: {}", content.length());
        return content;
    }

    // Keep fence handling separate so each caller retains its existing JSON reader.
    static String stripCodeFence(String value) {
        String cleaned = value.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline).trim();
            }
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```")).trim();
        }
        return cleaned;
    }

    static JsonNode requireRecommendationArray(ObjectMapper mapper, String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        JsonNode candidates = root.path("recommendations");
        if (candidates.isMissingNode() && root.isArray()) {
            candidates = root;
        }
        if (!candidates.isArray()) {
            throw new IllegalArgumentException("AI response must contain a recommendations array");
        }
        return candidates;
    }

    static Map<String, DeviceInfoHelper.DeviceInfo> indexDevices(List<DeviceInfoHelper.DeviceInfo> devices) {
        Map<String, DeviceInfoHelper.DeviceInfo> index = new HashMap<>();
        for (DeviceInfoHelper.DeviceInfo device : devices) {
            index.put(device.nodeId(), device);
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readCandidate(ObjectMapper mapper, JsonNode candidate) {
        Map<String, Object> result = mapper.convertValue(candidate, Map.class);
        // Provider-volunteered metadata has no verification meaning.
        result.remove("confidence");
        result.remove("category");
        return result;
    }

    static CandidateReview reviewCandidates(ObjectMapper mapper, JsonNode candidates, int limit,
            CandidateDiagnostics diagnostics,
            BiFunction<Map<String, Object>, Integer, RecommendationValidation> validate,
            Function<Map<String, Object>, String> rejectionLabel) {
        List<Map<String, Object>> recommendations = new ArrayList<>();
        List<Map<String, Object>> filteredItems = new ArrayList<>();
        List<Map<String, Object>> adjustedItems = new ArrayList<>();
        int rawCandidateCount = candidates.size();
        int inspected = 0;
        String filterLog = "Filtered " + diagnostics.kind() + " recommendation candidate {}: reasonCode={}";
        String parseLog = "Failed to parse " + diagnostics.kind() + " recommendation candidate {}: exception={}";
        for (JsonNode candidate : candidates) {
            if (recommendations.size() >= limit) break;
            inspected++;
            try {
                Map<String, Object> recommendation = readCandidate(mapper, candidate);
                RecommendationValidation validation = validate.apply(recommendation, inspected);
                if (validation.valid()) {
                    recommendations.add(recommendation);
                    adjustedItems.addAll(validation.adjustments());
                } else {
                    diagnostics.log().debug(filterLog, inspected, validation.reasonCode());
                    filteredItems.add(validation.filteredItem(diagnostics.kind(), inspected,
                            rejectionLabel.apply(recommendation)));
                }
            } catch (Exception e) {
                diagnostics.log().warn(parseLog, inspected, e.getClass().getName());
                filteredItems.add(diagnostics.parseFailure().filteredItem(diagnostics.kind(), inspected,
                        candidateText(candidate.path(diagnostics.rawLabelField()))));
            }
        }
        return new CandidateReview(recommendations, filteredItems, adjustedItems, rawCandidateCount, inspected);
    }

    record CandidateDiagnostics(Logger log, String kind, String rawLabelField, RecommendationValidation parseFailure) {}

    record CandidateReview(List<Map<String, Object>> recommendations, List<Map<String, Object>> filteredItems,
                           List<Map<String, Object>> adjustedItems, int rawCandidateCount, int inspectedCount) {
        String messageKey() {
            return recommendations.isEmpty() ? (rawCandidateCount == 0 ? "noCandidates" : "empty") : "found";
        }
    }

    static boolean hasOnlyFields(Map<String, Object> value, Set<String> allowedFields) {
        return value != null && allowedFields.containsAll(value.keySet());
    }

    static String candidateText(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.asText("").trim() : "";
    }

    record RecommendationValidation(boolean valid, String reasonCode, String reason,
                                    List<Map<String, Object>> adjustments) {
        static RecommendationValidation ok(List<Map<String, Object>> adjustments) {
            return new RecommendationValidation(true, "", "", List.copyOf(adjustments));
        }

        static RecommendationValidation reject(String reasonCode, String reason) {
            return new RecommendationValidation(false, reasonCode, reason, List.of());
        }

        Map<String, Object> filteredItem(String type, int index, String label) {
            return new RecommendationFilterItem(type, index, reasonCode, reason, label).toMap();
        }
    }
}
