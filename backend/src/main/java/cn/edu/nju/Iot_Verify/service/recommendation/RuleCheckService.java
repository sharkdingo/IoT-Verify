package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.dto.rule.DuplicateRuleCheckResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleSimilarityResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.RuleSemanticSignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/** Deterministic duplicate checks and the separate, explicit AI similarity decision. */
@Slf4j
@Service
public class RuleCheckService {
    private final BoardStorageService boardStorageService;
    private final PromptCompletionService promptCompletionService;
    private final ObjectMapper objectMapper;
    private final RecommendationResultParser resultParser;

    private static final double TEMPERATURE = 0.3;
    private static final int MAX_TOKENS = 2000;

    private static final String SYSTEM_PROMPT = """
你是智能物联网(IoT)规则相似性分析助手。你的任务是检查用户新添加的规则是否与现有规则存在语义相似、重叠或重复风险。

## 输入信息
- 新添加的规则详情（触发条件、执行动作、targetType、relation、value 等）
- 用户面板上已有的所有规则列表

## 输出要求
请分析新规则与现有规则的关系，返回符合以下JSON格式的结果：

```json
{
  "isSimilar": false,
  "isDuplicate": false,
  "mostSimilarWith": "candidate-1",
  "duplicateWith": null,
  "similarity": 0.42,
  "reason": "判断理由"
}
```

字段取值：isSimilar 与 isDuplicate 必须是 JSON 布尔值，similarity 必须是 0 到 1 之间的 JSON 数字
（不要写成 "0.0-1.0" 这样的字符串或区间，否则整次调用失败）。mostSimilarWith 与 duplicateWith
必须逐字复制输入中某条现有规则的 ruleRef（形如 candidate-1）；没有对应规则时填 null。
similarity 达到 0.8 及以上会强制转人工复核。

## 相似性判定标准
1. **完全重复**: 触发条件和执行动作完全相同，isDuplicate=true
2. **高度相似**: 触发条件和执行动作几乎相同，仅参数略有不同，isSimilar=true
3. **包含关系**: 新规则是现有规则的子集或超集，isSimilar=true
4. **低相似**: 触发条件或执行动作有明显不同，isSimilar=false

## 重要约束
- 只能引用输入 JSON 中出现的设备名、属性名与动作名；这里没有设备模板，不要引入输入之外的名称
- 必须区分 targetType=api、variable、mode、state；api 条件没有 relation/value
- 如果发现重复，duplicateWith 必须是输入中的重复规则 ruleRef
- 如果 isDuplicate=true，isSimilar 也必须为 true
- 如果只是相似但不重复，mostSimilarWith 必须是输入中的最相似规则 ruleRef，duplicateWith 可以为 null
- 考虑规则的语义相似性，不仅是字面匹配
""";

    public RuleCheckService(BoardStorageService boardStorageService,
                            PromptCompletionService promptCompletionService,
                            ObjectMapper objectMapper,
                            RecommendationResultParser resultParser) {
        this.boardStorageService = boardStorageService;
        this.promptCompletionService = promptCompletionService;
        this.objectMapper = objectMapper;
        this.resultParser = resultParser;
    }

    public DuplicateRuleCheckResultDto checkDuplicate(Long userId, RuleDto newRule) {
        newRule = RuleCandidateNormalizer.normalize(newRule);
        List<RuleDto> existingRules = safeList(boardStorageService.getRules(userId));
        log.debug("Duplicate rule check request: userId={}, existingRules={}", userId, existingRules.size());
        if (existingRules.isEmpty()) {
            return DuplicateRuleCheckResultDto.builder()
                    .isDuplicate(false).requiresReview(false).matchedRule(null).similarity(0.0)
                    .matchType("none").reasonCode("NO_EXISTING_RULES").reason("no existing rules")
                    .message("No existing rules to compare. This is the first rule.").build();
        }
        return checkDuplicateDeterministically(newRule, existingRules);
    }

    public RuleSimilarityResultDto checkSimilarity(Long userId, RuleDto newRule) {
        newRule = RuleCandidateNormalizer.normalize(newRule);
        List<RuleDto> existingRules = safeList(boardStorageService.getRules(userId));
        log.debug("AI rule similarity request: userId={}, existingRules={}", userId, existingRules.size());
        if (existingRules.isEmpty()) return noExistingRules();
        Map<String, RuleDto> references = ruleReferences(existingRules);
        String aiResponse = analyzeSimilarityWithAI(
                buildRuleInfoJson(newRule, "new-rule"), buildRulesListJson(references));
        log.debug("AI rule similarity response length: {} chars", aiResponse.length());
        return parseAiResponse(aiResponse, references);
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private DuplicateRuleCheckResultDto checkDuplicateDeterministically(RuleDto newRule, List<RuleDto> existingRules) {
        RuleSemanticSignature.Signature newSignature = RuleSemanticSignature.from(newRule);
        DuplicateCandidate best = DuplicateCandidate.none();

        for (RuleDto existingRule : existingRules) {
            if (newRule.getId() != null && newRule.getId().equals(existingRule.getId())) {
                continue;
            }
            DuplicateCandidate candidate = compare(
                    newSignature, RuleSemanticSignature.from(existingRule), existingRule);
            if (candidate.similarity > best.similarity) {
                best = candidate;
            }
        }

        return DuplicateRuleCheckResultDto.builder()
                .isDuplicate(best.isDuplicate).requiresReview(best.requiresReview)
                .matchedRule(best.matchedRule).similarity(best.similarity).matchType(best.matchType)
                .reasonCode(best.reasonCode).reason(best.reason)
                .message(best.isDuplicate
                        ? "This rule is identical to: " + best.matchedRule
                        : best.requiresReview
                            ? "This rule may overlap with: " + best.matchedRule
                            : "No obvious duplicate rule found; this is not a conflict-free proof.")
                .build();
    }

    private DuplicateCandidate compare(RuleSemanticSignature.Signature candidate,
                                       RuleSemanticSignature.Signature existing,
                                       RuleDto existingRule) {
        if (!candidate.commandKey().equals(existing.commandKey())) {
            return DuplicateCandidate.none();
        }

        String matchedRule = displayRule(existingRule);
        if (candidate.conditionKeys().equals(existing.conditionKeys())) {
            return new DuplicateCandidate(true, true, matchedRule, 1.0, "exact",
                    "EXACT_MATCH",
                    "same trigger conditions and same action");
        }

        if (!candidate.conditionKeys().isEmpty() && !existing.conditionKeys().isEmpty()) {
            if (existing.conditionKeys().containsAll(candidate.conditionKeys())
                    || candidate.conditionKeys().containsAll(existing.conditionKeys())) {
                double ratio = Math.min(candidate.conditionKeys().size(), existing.conditionKeys().size())
                        / (double) Math.max(candidate.conditionKeys().size(), existing.conditionKeys().size());
                return new DuplicateCandidate(false, true, matchedRule, Math.max(0.85, ratio), "contains",
                        "TRIGGER_SET_CONTAINS_OTHER",
                        "same action and one trigger set contains the other");
            }
        }

        if (candidate.conditionShapeKeys().equals(existing.conditionShapeKeys())) {
            return new DuplicateCandidate(false, true, matchedRule, 0.82, "same-shape",
                    "SAME_TRIGGER_SHAPE_DIFFERENT_VALUES",
                    "same action and trigger structure with different parameter values");
        }

        Set<String> intersection = new HashSet<>(candidate.conditionShapeKeys());
        intersection.retainAll(existing.conditionShapeKeys());
        if (!intersection.isEmpty()) {
            double similarity = 0.45 + 0.3 * intersection.size()
                    / Math.max(candidate.conditionShapeKeys().size(), existing.conditionShapeKeys().size());
            return new DuplicateCandidate(false, false, null, similarity, "partial-shape",
                    "PARTIAL_TRIGGER_OVERLAP",
                    "same action with partially overlapping trigger structure");
        }

        return DuplicateCandidate.none();
    }

    private record DuplicateCandidate(
            boolean isDuplicate,
            boolean requiresReview,
            String matchedRule,
            double similarity,
            String matchType,
            String reasonCode,
            String reason
    ) {
        static DuplicateCandidate none() {
            return new DuplicateCandidate(false, false, null, 0.0, "none",
                    "NO_MATCHING_SIGNATURE", "no matching rule signature");
        }
    }

    private String displayRule(RuleDto rule) {
        if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
            return rule.getRuleString();
        }
        if (rule == null || rule.getCommand() == null) {
            return "?";
        }
        String conditions = safeList(rule.getConditions()).stream()
                .filter(java.util.Objects::nonNull)
                .map(condition -> {
                    String base = displayText(condition.getDeviceName()) + "." + displayText(condition.getAttribute());
                    if ("api".equalsIgnoreCase(displayText(condition.getTargetType()))) {
                        return base;
                    }
                    return base + " " + displayText(condition.getRelation()) + " " + displayText(condition.getValue());
                })
                .reduce((left, right) -> left + " & " + right)
                .orElse("?");
        return conditions + " -> " + displayText(rule.getCommand().getDeviceName())
                + "." + displayText(rule.getCommand().getAction());
    }

    private String displayText(String value) {
        return value == null ? "" : value.trim();
    }

    private RuleSimilarityResultDto noExistingRules() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isSimilar", false);
        body.put("isDuplicate", false);
        body.put("requiresReview", false);
        body.put("matchedRule", null);
        body.put("similarity", 0.0);
        body.put("reasonCode", "NO_EXISTING_RULES");
        body.put("reason", "no existing rules");
        body.put("message", "No existing rules to compare. This is the first rule.");
        return resultParser.parseRuleSimilarityResult(body);
    }

    private String buildRuleInfoJson(RuleDto rule, String prefix) {
        try {
            return objectMapper.writeValueAsString(buildRuleInfoMap(rule, prefix));
        } catch (Exception e) {
            log.error("Failed to build rule similarity JSON", e);
            throw new IllegalStateException("Could not serialize the candidate rule for similarity analysis", e);
        }
    }

    private String buildRulesListJson(Map<String, RuleDto> ruleReferences) {
        try {
            List<Map<String, Object>> rulesList = new ArrayList<>();
            for (Map.Entry<String, RuleDto> entry : ruleReferences.entrySet()) {
                rulesList.add(buildRuleInfoMap(entry.getValue(), entry.getKey()));
            }
            return objectMapper.writeValueAsString(rulesList);
        } catch (Exception e) {
            log.error("Failed to build existing rules similarity JSON", e);
            throw new IllegalStateException("Could not serialize existing rules for similarity analysis", e);
        }
    }

    private Map<String, Object> buildRuleInfoMap(RuleDto rule, String prefix) {
        Map<String, Object> ruleMap = new LinkedHashMap<>();
        ruleMap.put("ruleRef", prefix);

        if (rule.getConditions() != null && !rule.getConditions().isEmpty()) {
            List<Map<String, Object>> conditions = new ArrayList<>();
            for (RuleDto.Condition cond : rule.getConditions()) {
                Map<String, Object> condMap = new LinkedHashMap<>();
                condMap.put("deviceName", cond.getDeviceName());
                condMap.put("targetType", cond.getTargetType());
                condMap.put("attribute", cond.getAttribute());
                condMap.put("relation", cond.getRelation());
                condMap.put("value", cond.getValue());
                conditions.add(condMap);
            }
            ruleMap.put("conditions", conditions);
        } else {
            ruleMap.put("conditions", Collections.emptyList());
        }

        if (rule.getCommand() != null) {
            Map<String, Object> commandMap = new LinkedHashMap<>();
            commandMap.put("deviceName", rule.getCommand().getDeviceName());
            commandMap.put("action", rule.getCommand().getAction());
            commandMap.put("contentDevice", rule.getCommand().getContentDevice());
            commandMap.put("content", rule.getCommand().getContent());
            ruleMap.put("command", commandMap);
        }

        return ruleMap;
    }

    private String analyzeSimilarityWithAI(String newRuleInfo, String existingRulesInfo) {
        String userPrompt = """
请检查新添加的规则是否与现有规则存在语义相似、重叠或重复风险。

## 新添加的规则
%s

## 现有规则列表
%s

请直接返回JSON格式的检查结果，不要包含其他文字。
""".formatted(newRuleInfo, existingRulesInfo);

        String content = promptCompletionService.complete(SYSTEM_PROMPT, userPrompt, TEMPERATURE, MAX_TOKENS);
        if (content == null || content.isBlank()) {
            log.warn("AI returned empty content for rule similarity check");
            return "";
        }
        return content;
    }

    private RuleSimilarityResultDto parseAiResponse(String aiResponse, Map<String, RuleDto> ruleReferences) {
        try {
            JsonNode root = objectMapper.readTree(cleanFence(aiResponse));
            if (root == null || !root.isObject()
                    || !root.path("isSimilar").isBoolean()
                    || !root.path("isDuplicate").isBoolean()
                    || !root.path("similarity").isNumber()) {
                throw new IllegalArgumentException(
                        "AI similarity response must contain boolean isSimilar/isDuplicate and numeric similarity");
            }

            double similarity = root.path("similarity").asDouble();
            boolean isDuplicate = root.path("isDuplicate").asBoolean();
            boolean isSimilar = root.path("isSimilar").asBoolean();
            if (!Double.isFinite(similarity) || similarity < 0.0 || similarity > 1.0) {
                throw new IllegalArgumentException("AI similarity score must be between 0 and 1");
            }
            if (isDuplicate && !isSimilar) {
                throw new IllegalArgumentException("An AI duplicate result must also be marked similar");
            }
            String duplicateWith = textOrNull(root.path("duplicateWith"));
            String mostSimilarWith = textOrNull(root.path("mostSimilarWith"));
            if (mostSimilarWith == null) {
                mostSimilarWith = duplicateWith;
            }

            RuleDto duplicateRule = referencedRule(duplicateWith, ruleReferences);
            RuleDto mostSimilarRule = referencedRule(mostSimilarWith, ruleReferences);
            RuleDto matchedRule = duplicateRule != null ? duplicateRule : mostSimilarRule;

            Map<String, Object> result = new LinkedHashMap<>();
            boolean requiresReview = isDuplicate || isSimilar || similarity >= 0.8;
            String reasonCode = isDuplicate
                    ? "AI_DUPLICATE"
                    : isSimilar
                    ? "AI_SIMILAR"
                    : similarity >= 0.8
                    ? "AI_HIGH_SCORE_REVIEW"
                    : "AI_NO_SIGNIFICANT_SIMILARITY";
            result.put("isSimilar", isSimilar);
            result.put("isDuplicate", isDuplicate);
            result.put("requiresReview", requiresReview);
            result.put("matchedRule", matchedRule == null ? null : displaySimilarityRule(matchedRule));
            result.put("similarity", similarity);
            result.put("reasonCode", reasonCode);
            result.put("reason", root.path("reason").asText(""));
            result.put("message", isDuplicate
                    ? matchedRule == null
                    ? "AI found a possible duplicate but could not identify the referenced existing rule."
                    : "AI found a duplicate rule: " + displaySimilarityRule(matchedRule)
                    : isSimilar
                    ? matchedRule == null
                    ? "AI found a possible similar rule but could not identify the referenced existing rule."
                    : "AI found a similar rule: " + displaySimilarityRule(matchedRule)
                    : "AI found no obvious similar rule; this is not a conflict-free proof.");
            return resultParser.parseRuleSimilarityResult(result);
        } catch (Exception e) {
            log.error("Failed to parse AI rule similarity response: exception={}",
                    e.getClass().getName());
            throw new RecommendationFailure(
                    "Failed to parse AI similarity result. Please try again.",
                    "AI_RESPONSE_INVALID", Map.of("phase", "response_parse"), e);
        }
    }

    private String cleanFence(String aiResponse) {
        String cleaned = aiResponse == null ? "" : aiResponse.trim();
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

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Map<String, RuleDto> ruleReferences(List<RuleDto> existingRules) {
        Map<String, RuleDto> references = new LinkedHashMap<>();
        int index = 1;
        for (RuleDto rule : existingRules) {
            references.put("candidate-" + index++, rule);
        }
        return references;
    }

    private RuleDto referencedRule(String reference, Map<String, RuleDto> ruleReferences) {
        return reference == null || ruleReferences == null ? null : ruleReferences.get(reference);
    }

    private String displaySimilarityRule(RuleDto rule) {
        if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
            return rule.getRuleString();
        }
        if (rule == null || rule.getCommand() == null) {
            return "?";
        }
        return rule.getCommand().getDeviceName() + "." + rule.getCommand().getAction();
    }
}
