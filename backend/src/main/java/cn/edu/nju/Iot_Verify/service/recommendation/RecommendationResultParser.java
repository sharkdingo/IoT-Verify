package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.aitool.scenario.ScenarioObjectiveTargets;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.ScenarioVerificationReadiness;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneFormat;
import cn.edu.nju.Iot_Verify.dto.recommendation.*;
import cn.edu.nju.Iot_Verify.dto.rule.RuleSimilarityResultDto;
import cn.edu.nju.Iot_Verify.exception.BadGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates provider-derived candidates and accounting before either transport receives a result. */
@Component
public class RecommendationResultParser {

    private static final Set<String> SIMILARITY_REASON_CODES = Set.of(
            "NO_EXISTING_RULES",
            "AI_DUPLICATE",
            "AI_SIMILAR",
            "AI_HIGH_SCORE_REVIEW",
            "AI_NO_SIGNIFICANT_SIMILARITY");

    private final ObjectMapper objectMapper;

    public RecommendationResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> RecommendationResponseDto<T> parseRecommendationResponse(
            Map<String, Object> result,
            Class<T> candidateType,
            boolean requireAdjustments,
            String context) {
        Set<String> allowedFields = requireAdjustments
                ? Set.of("message", "count", "requestedCount", "validatedCount",
                        "filteredCount", "filteredItems", "adjustedCount", "adjustedItems",
                        "rawCandidateCount", "inspectedCount", "truncatedCount", "recommendations")
                : Set.of("message", "count", "requestedCount", "validatedCount",
                        "filteredCount", "filteredItems", "rawCandidateCount",
                        "inspectedCount", "truncatedCount", "recommendations");
        requireOnlyRecommendationFields(result, allowedFields, context);
        RecommendationAudit audit = parseRecommendationAudit(result, requireAdjustments, context);
        List<?> rawRecommendations = requireRecommendationList(result, "recommendations", context);
        List<T> recommendations = convertRecommendationItems(rawRecommendations, candidateType, context);
        validateKeptRecommendationCandidates(recommendations, candidateType, context);
        if (audit.count() != recommendations.size()
                || audit.validatedCount() != recommendations.size()) {
            throw invalidRecommendationResult(
                    context, "count and validatedCount must match kept recommendations");
        }

        RecommendationResponseDto<T> response = new RecommendationResponseDto<>();
        response.setMessage(audit.message());
        response.setCount(audit.count());
        response.setRequestedCount(audit.requestedCount());
        response.setValidatedCount(audit.validatedCount());
        response.setFilteredCount(audit.filteredCount());
        response.setFilteredItems(audit.filteredItems());
        response.setAdjustedCount(audit.adjustedCount());
        response.setAdjustedItems(audit.adjustedItems());
        response.setRawCandidateCount(audit.rawCandidateCount());
        response.setInspectedCount(audit.inspectedCount());
        response.setTruncatedCount(audit.truncatedCount());
        response.setRecommendations(recommendations);
        return response;
    }

    private <T> void validateKeptRecommendationCandidates(
            List<T> recommendations,
            Class<T> candidateType,
            String context) {
        for (int index = 0; index < recommendations.size(); index++) {
            Object candidate = recommendations.get(index);
            if (candidate instanceof RuleRecommendationDto rule) {
                if (isBlank(rule.getName()) || rule.getConditions() == null
                        || rule.getConditions().isEmpty() || rule.getCommand() == null) {
                    throw invalidRecommendationResult(
                            context, "recommendations[" + index
                                    + "] lacks saved rule name, conditions, or command");
                }
            } else if (candidate instanceof SpecificationRecommendationDto specification) {
                if (isBlank(specification.getRationale())
                        || specification.getTemplateId() == null
                        || !specification.getTemplateId().matches("^[1-7]$")
                        || specification.getAConditions() == null
                        || specification.getIfConditions() == null
                        || specification.getThenConditions() == null) {
                    throw invalidRecommendationResult(
                            context, "recommendations[" + index
                                    + "] lacks rationale, legal templateId, or condition collections");
                }
            } else if (candidate instanceof DeviceRecommendationDto device) {
                if (isBlank(device.getTemplateName()) || isBlank(device.getSuggestedLabel())) {
                    throw invalidRecommendationResult(
                            context, "recommendations[" + index
                                    + "] lacks device type or effective instance name");
                }
            } else if (candidate == null) {
                throw invalidRecommendationResult(
                        context, "recommendations[" + index + "] is null for " + candidateType.getSimpleName());
            }
        }
    }

    public ScenarioRecommendationResponseDto parseScenarioRecommendationResponse(
            Map<String, Object> result,
            ScenarioRecommendationRequestDto request) {
        String context = "AI scenario recommendation";
        requireOnlyRecommendationFields(result, Set.of(
                "message", "count", "requestedCount", "validatedCount",
                "filteredCount", "filteredItems", "adjustedCount", "adjustedItems",
                "rawCandidateCount", "inspectedCount", "truncatedCount",
                "scenarioName", "rationale", "objectiveTargets", "objectiveStatus", "objectiveIssues",
                "verificationReady", "readinessIssues",
                "semanticWarnings", "scene"), context);
        RecommendationAudit audit = parseRecommendationAudit(result, true, context);
        PortableSceneDto scene = convertRecommendationItem(
                result.get("scene"), PortableSceneDto.class, context, "scene");
        // Reads the shared constants rather than its own literals. Hardcoded at 4 while the tool emitted
        // 5, this rejected every generated scenario as "unsupported" — the producer and the importer were
        // both bumped and this validator was not.
        if (!PortableSceneFormat.isSupported(scene.getSchema(), scene.getVersion())) {
            throw invalidRecommendationResult(context, "scene schema/version is unsupported");
        }
        if (scene.getTemplates() == null || scene.getDevices() == null
                || scene.getEnvironmentVariables() == null || scene.getRules() == null
                || scene.getSpecs() == null) {
            throw invalidRecommendationResult(context, "scene collections must all be arrays");
        }
        int finalCount = scene.getDevices().size()
                + scene.getEnvironmentVariables().size()
                + scene.getRules().size()
                + scene.getSpecs().size();
        if (audit.count() != finalCount) {
            throw invalidRecommendationResult(context, "count must match final scene items");
        }
        ScenarioObjectiveTargetsDto objectiveTargetsDto = convertRecommendationItem(
                result.get("objectiveTargets"),
                ScenarioObjectiveTargetsDto.class,
                context,
                "objectiveTargets");
        ScenarioObjectiveTargets objectiveTargets = parseScenarioObjectiveTargets(
                objectiveTargetsDto, request, context);
        if (audit.requestedCount() != objectiveTargets.requestedCount()) {
            throw invalidRecommendationResult(
                    context, "requestedCount must equal the sum of objectiveTargets");
        }

        ScenarioRecommendationResponseDto response = new ScenarioRecommendationResponseDto();
        response.setMessage(audit.message());
        response.setCount(audit.count());
        response.setRequestedCount(audit.requestedCount());
        response.setValidatedCount(audit.validatedCount());
        response.setFilteredCount(audit.filteredCount());
        response.setFilteredItems(audit.filteredItems());
        response.setAdjustedCount(audit.adjustedCount());
        response.setAdjustedItems(audit.adjustedItems());
        response.setRawCandidateCount(audit.rawCandidateCount());
        response.setInspectedCount(audit.inspectedCount());
        response.setTruncatedCount(audit.truncatedCount());
        response.setScenarioName(requireRecommendationString(result, "scenarioName", context));
        response.setRationale(requireRecommendationString(result, "rationale", context));
        response.setObjectiveTargets(objectiveTargetsDto);
        String objectiveStatus = requireRecommendationString(result, "objectiveStatus", context);
        List<ScenarioObjectiveIssueDto> objectiveIssues = convertRecommendationItems(
                requireRecommendationList(result, "objectiveIssues", context),
                ScenarioObjectiveIssueDto.class, context);
        boolean verificationReady = requireRecommendationBoolean(result, "verificationReady", context);
        List<ScenarioReadinessIssueDto> readinessIssues = convertRecommendationItems(
                requireRecommendationList(result, "readinessIssues", context),
                ScenarioReadinessIssueDto.class, context);
        List<ScenarioSemanticWarningDto> semanticWarnings = convertRecommendationItems(
                requireRecommendationList(result, "semanticWarnings", context),
                ScenarioSemanticWarningDto.class, context);
        validateScenarioAnalysis(
                scene,
                audit.filteredCount(),
                objectiveTargets,
                objectiveStatus,
                objectiveIssues,
                verificationReady,
                readinessIssues,
                semanticWarnings,
                context);
        response.setObjectiveStatus(objectiveStatus);
        response.setObjectiveIssues(objectiveIssues);
        response.setVerificationReady(verificationReady);
        response.setReadinessIssues(readinessIssues);
        response.setSemanticWarnings(semanticWarnings);
        response.setScene(scene);
        return response;
    }

    private void validateScenarioAnalysis(PortableSceneDto scene,
                                          int filteredCount,
                                          ScenarioObjectiveTargets objectiveTargets,
                                          String objectiveStatus,
                                          List<ScenarioObjectiveIssueDto> objectiveIssues,
                                          boolean verificationReady,
                                          List<ScenarioReadinessIssueDto> readinessIssues,
                                          List<ScenarioSemanticWarningDto> semanticWarnings,
                                          String context) {
        ScenarioVerificationReadiness.Status expected = ScenarioVerificationReadiness.assess(
                objectMapper.valueToTree(scene), filteredCount, "en", objectiveTargets);
        List<String> actualObjectiveCodes = new ArrayList<>();
        for (int index = 0; index < objectiveIssues.size(); index++) {
            ScenarioObjectiveIssueDto issue = objectiveIssues.get(index);
            if (issue == null || isBlank(issue.getCode()) || isBlank(issue.getMessage())) {
                throw invalidRecommendationResult(
                        context, "objectiveIssues[" + index + "] requires non-blank code and message");
            }
            actualObjectiveCodes.add(issue.getCode());
        }
        List<String> expectedObjectiveCodes = expected.objectiveIssues().stream()
                .map(ScenarioVerificationReadiness.Issue::code)
                .toList();
        if (!expected.objectiveStatus().equals(objectiveStatus)
                || !actualObjectiveCodes.equals(expectedObjectiveCodes)) {
            throw invalidRecommendationResult(
                    context, "objectiveStatus/objectiveIssues must match the returned scene");
        }

        List<String> actualCodes = new ArrayList<>();
        for (int index = 0; index < readinessIssues.size(); index++) {
            ScenarioReadinessIssueDto issue = readinessIssues.get(index);
            if (issue == null || isBlank(issue.getCode()) || isBlank(issue.getMessage())) {
                throw invalidRecommendationResult(
                        context, "readinessIssues[" + index + "] requires non-blank code and message");
            }
            actualCodes.add(issue.getCode());
        }
        List<String> expectedCodes = expected.readinessIssues().stream()
                .map(ScenarioVerificationReadiness.Issue::code)
                .toList();
        if (verificationReady != expected.verificationReady() || !actualCodes.equals(expectedCodes)) {
            throw invalidRecommendationResult(
                    context, "verificationReady/readinessIssues must match the returned scene");
        }

        List<String> actualWarningCodes = new ArrayList<>();
        for (int index = 0; index < semanticWarnings.size(); index++) {
            ScenarioSemanticWarningDto warning = semanticWarnings.get(index);
            if (warning == null || isBlank(warning.getCode()) || isBlank(warning.getMessage())) {
                throw invalidRecommendationResult(
                        context, "semanticWarnings[" + index + "] requires non-blank code and message");
            }
            actualWarningCodes.add(warning.getCode());
        }
        List<String> expectedWarningCodes = expected.semanticWarnings().stream()
                .map(ScenarioVerificationReadiness.Issue::code)
                .toList();
        if (!actualWarningCodes.equals(expectedWarningCodes)) {
            throw invalidRecommendationResult(
                    context, "semanticWarnings must match the returned scene and filtered candidates");
        }
    }

    private ScenarioObjectiveTargets parseScenarioObjectiveTargets(
            ScenarioObjectiveTargetsDto targets,
            ScenarioRecommendationRequestDto request,
            String context) {
        if (targets.getMinDevices() == null
                || targets.getMinRules() == null
                || targets.getMinSpecs() == null) {
            throw invalidRecommendationResult(
                    context, "objectiveTargets must contain all minimum counts");
        }
        ScenarioObjectiveTargets parsed;
        try {
            parsed = new ScenarioObjectiveTargets(
                    targets.getMinDevices(), targets.getMinRules(), targets.getMinSpecs());
        } catch (IllegalArgumentException e) {
            throw invalidRecommendationResult(context, e.getMessage());
        }
        if (request.getMinDevices() == null
                || request.getMinRules() == null
                || request.getMinSpecs() == null
                || parsed.minDevices() != request.getMinDevices()
                || parsed.minRules() != request.getMinRules()
                || parsed.minSpecs() != request.getMinSpecs()) {
            throw invalidRecommendationResult(
                    context, "objectiveTargets must match the submitted minimum counts");
        }
        return parsed;
    }

    private RecommendationAudit parseRecommendationAudit(
            Map<String, Object> result,
            boolean requireAdjustments,
            String context) {
        String message = requireRecommendationText(result, "message", context);
        int count = requireRecommendationInteger(result, "count", context);
        int requestedCount = requireRecommendationInteger(result, "requestedCount", context);
        int validatedCount = requireRecommendationInteger(result, "validatedCount", context);
        int filteredCount = requireRecommendationInteger(result, "filteredCount", context);
        int rawCandidateCount = requireRecommendationInteger(result, "rawCandidateCount", context);
        int inspectedCount = requireRecommendationInteger(result, "inspectedCount", context);
        int truncatedCount = requireRecommendationInteger(result, "truncatedCount", context);
        List<RecommendationFilterItemDto> filteredItems = convertRecommendationItems(
                requireRecommendationList(result, "filteredItems", context),
                RecommendationFilterItemDto.class, context);
        validateRecommendationFilterItems(filteredItems, context);
        if (filteredCount != filteredItems.size()) {
            throw invalidRecommendationResult(context, "filteredCount must match filteredItems");
        }
        if (inspectedCount != validatedCount + filteredCount) {
            throw invalidRecommendationResult(
                    context, "inspectedCount must equal validatedCount plus filteredCount");
        }
        if (rawCandidateCount != inspectedCount + truncatedCount) {
            throw invalidRecommendationResult(
                    context, "rawCandidateCount must equal inspectedCount plus truncatedCount");
        }

        boolean hasAdjustedCount = result.containsKey("adjustedCount");
        boolean hasAdjustedItems = result.containsKey("adjustedItems");
        if (hasAdjustedCount != hasAdjustedItems || (requireAdjustments && !hasAdjustedCount)) {
            throw invalidRecommendationResult(
                    context, "adjustedCount and adjustedItems must be returned together");
        }
        if (!requireAdjustments && hasAdjustedCount) {
            throw invalidRecommendationResult(context, "unexpected adjustment fields");
        }

        Integer adjustedCount = null;
        List<RecommendationAdjustmentItemDto> adjustedItems = null;
        if (hasAdjustedCount) {
            adjustedCount = requireRecommendationInteger(result, "adjustedCount", context);
            adjustedItems = convertRecommendationItems(
                    requireRecommendationList(result, "adjustedItems", context),
                    RecommendationAdjustmentItemDto.class, context);
            validateRecommendationAdjustmentItems(adjustedItems, context);
            if (adjustedCount != adjustedItems.size()) {
                throw invalidRecommendationResult(context, "adjustedCount must match adjustedItems");
            }
        }

        return new RecommendationAudit(
                message, count, requestedCount, validatedCount, filteredCount, filteredItems,
                adjustedCount, adjustedItems, rawCandidateCount, inspectedCount, truncatedCount);
    }

    private void validateRecommendationFilterItems(
            List<RecommendationFilterItemDto> items, String context) {
        for (int index = 0; index < items.size(); index++) {
            RecommendationFilterItemDto item = items.get(index);
            if (item == null || isBlank(item.getType()) || item.getIndex() == null
                    || item.getIndex() < 1 || isBlank(item.getReasonCode())
                    || isBlank(item.getReason())) {
                throw invalidRecommendationResult(
                        context, "filteredItems[" + index + "] lacks type/index/reasonCode/reason");
            }
        }
    }

    private void validateRecommendationAdjustmentItems(
            List<RecommendationAdjustmentItemDto> items, String context) {
        for (int index = 0; index < items.size(); index++) {
            RecommendationAdjustmentItemDto item = items.get(index);
            if (item == null || isBlank(item.getType())
                    || (item.getIndex() != null && item.getIndex() < 1)
                    || isBlank(item.getReasonCode()) || isBlank(item.getReason())
                    || item.getAppliedValues() == null) {
                throw invalidRecommendationResult(
                        context, "adjustedItems[" + index + "] lacks required explanation data");
            }
        }
    }

    private List<?> requireRecommendationList(
            Map<String, Object> result, String field, String context) {
        Object value = result.get(field);
        if (value instanceof List<?> list) {
            return list;
        }
        throw invalidRecommendationResult(context, field + " must be an array");
    }

    private void requireOnlyRecommendationFields(
            Map<String, Object> result, Set<String> allowedFields, String context) {
        for (String field : result.keySet()) {
            if (!allowedFields.contains(field)) {
                throw invalidRecommendationResult(context, "unknown field: " + field);
            }
        }
    }

    private int requireRecommendationInteger(
            Map<String, Object> result, String field, String context) {
        Object value = result.get(field);
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric) && numeric >= 0.0 && numeric == Math.rint(numeric)
                    && numeric <= Integer.MAX_VALUE) {
                return (int) numeric;
            }
        }
        throw invalidRecommendationResult(context, field + " must be a non-negative integer");
    }

    private boolean requireRecommendationBoolean(
            Map<String, Object> result, String field, String context) {
        Object value = result.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalidRecommendationResult(context, field + " must be boolean");
    }

    private String requireRecommendationText(
            Map<String, Object> result, String field, String context) {
        String value = requireRecommendationString(result, field, context);
        if (value.isBlank()) {
            throw invalidRecommendationResult(context, field + " must be non-blank text");
        }
        return value;
    }

    private String requireRecommendationString(
            Map<String, Object> result, String field, String context) {
        Object value = result.get(field);
        if (value instanceof String text) {
            return text;
        }
        throw invalidRecommendationResult(context, field + " must be text");
    }

    private <T> List<T> convertRecommendationItems(
            List<?> values, Class<T> type, String context) {
        return values.stream()
                .map(value -> convertRecommendationItem(value, type, context, type.getSimpleName()))
                .toList();
    }

    private <T> T convertRecommendationItem(
            Object value, Class<T> type, String context, String field) {
        if (value == null) {
            throw invalidRecommendationResult(context, field + " cannot be null");
        }
        try {
            return objectMapper.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            throw new BadGatewayException(
                    context + " returned an invalid " + field + " object", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private BadGatewayException invalidRecommendationResult(String context, String detail) {
        return new BadGatewayException(context + " returned an incomplete result: " + detail);
    }

    private record RecommendationAudit(
            String message,
            int count,
            int requestedCount,
            int validatedCount,
            int filteredCount,
            List<RecommendationFilterItemDto> filteredItems,
            Integer adjustedCount,
            List<RecommendationAdjustmentItemDto> adjustedItems,
            int rawCandidateCount,
            int inspectedCount,
            int truncatedCount) {
    }

    public RuleSimilarityResultDto parseRuleSimilarityResult(Map<String, Object> result) {
        boolean isSimilar = requireBoolean(result, "isSimilar", "AI similarity check");
        boolean isDuplicate = requireBoolean(result, "isDuplicate", "AI similarity check");
        boolean requiresReview = requireBoolean(result, "requiresReview", "AI similarity check");
        double similarity = requireSimilarity(result, "AI similarity check");
        if (isDuplicate && !isSimilar) {
            throw new BadGatewayException(
                    "AI similarity check returned an inconsistent duplicate result");
        }
        if (requiresReview != (isDuplicate || isSimilar || similarity >= 0.8)) {
            throw new BadGatewayException(
                    "AI similarity check returned an inconsistent review decision");
        }
        String reasonCode = requireText(result, "reasonCode", "AI similarity check");
        if (!SIMILARITY_REASON_CODES.contains(reasonCode)) {
            throw new BadGatewayException("AI similarity check returned an unknown reason code");
        }
        String expectedReasonCode = isDuplicate
                ? "AI_DUPLICATE"
                : isSimilar
                ? "AI_SIMILAR"
                : similarity >= 0.8
                ? "AI_HIGH_SCORE_REVIEW"
                : "AI_NO_SIGNIFICANT_SIMILARITY";
        if (!expectedReasonCode.equals(reasonCode)
                && !("NO_EXISTING_RULES".equals(reasonCode)
                && !isSimilar && !isDuplicate && similarity == 0.0)) {
            throw new BadGatewayException(
                    "AI similarity check returned a reason code inconsistent with its result");
        }
        return RuleSimilarityResultDto.builder()
                .isSimilar(isSimilar)
                .isDuplicate(isDuplicate)
                .requiresReview(requiresReview)
                .matchedRule(optionalString(result, "matchedRule", "AI similarity check"))
                .similarity(similarity)
                .reasonCode(reasonCode)
                .reason(requireText(result, "reason", "AI similarity check"))
                .message(requireText(result, "message", "AI similarity check"))
                .build();
    }

    private boolean requireBoolean(Map<String, Object> result, String field, String context) {
        Object value = result.get(field);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw invalidCheckResult(context, field + " must be boolean");
    }

    private double requireSimilarity(Map<String, Object> result, String context) {
        Object value = result.get("similarity");
        if (value instanceof Number number) {
            double similarity = number.doubleValue();
            if (Double.isFinite(similarity) && similarity >= 0.0 && similarity <= 1.0) {
                return similarity;
            }
        }
        throw invalidCheckResult(context, "similarity must be a number from 0 to 1");
    }

    private String requireText(Map<String, Object> result, String field, String context) {
        String value = optionalString(result, field, context);
        if (value != null && !value.isBlank()) {
            return value;
        }
        throw invalidCheckResult(context, field + " must be non-blank text");
    }

    private String optionalString(Map<String, Object> result, String field, String context) {
        Object value = result.get(field);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw invalidCheckResult(context, field + " must be text or null");
    }

    private BadGatewayException invalidCheckResult(String context, String detail) {
        String message = context + " returned an incomplete result: " + detail;
        return new BadGatewayException(message);
    }
}
