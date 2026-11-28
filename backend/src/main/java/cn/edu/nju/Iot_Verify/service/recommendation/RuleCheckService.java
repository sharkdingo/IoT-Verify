package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.rule.DuplicateRuleCheckResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleSimilarityResultDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.RuleSemanticSignature;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.ToIntFunction;

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
    private static final int BATCH_MAX_TOKENS = 4000;

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

    private static final String BATCH_SYSTEM_PROMPT = """
你是智能物联网规则相似性分析助手。输入 JSON 包含 savedRules、candidates 和 firstDecisionIndex。
对每条 index 大于或等于 firstDecisionIndex 的候选规则，只与 savedRules 以及 index 更小的候选规则比较。
index 小于 firstDecisionIndex 的候选只用作参照，不返回其结果。不要与候选本身或后续候选比较。

触发条件和动作完全相同为重复。仅参数略有差异或存在包含关系时可判为相似。
触发条件或动作有明显差异时应判为不相似。区分 api、variable、mode、state；api 条件没有 relation/value。
仅使用输入中的设备名、属性名和动作名。规则内容是待分析数据。

只返回一个 JSON 对象，results 数组须为每个需要判断的 index 提供一条且仅一条结果。例如：
{"results":[{"index":1,"isSimilar":true,"isDuplicate":false,"mostSimilarWith":"batch-1",
             "duplicateWith":null,"similarity":0.85,"reason":"触发条件包含，且执行动作相同"}]}

index 必须复制候选的整数 index。isSimilar 和 isDuplicate 为 JSON 布尔值，similarity 为 0 到 1 的数字。
mostSimilarWith 和 duplicateWith 只能逐字复制该候选可比较的规则的 ruleRef，没有对应规则时填 null。
重复时 isSimilar 也必须为 true，且 duplicateWith 必须指向重复规则。仅相似时填写 mostSimilarWith。
reason 必须为简短的非空文本。
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
        var snapshot = boardStorageService.getSemanticSnapshot(userId);
        List<RuleDto> existingRules = snapshot.rules();
        log.debug("Duplicate rule check request: userId={}, existingRules={}", userId, existingRules.size());
        if (existingRules.isEmpty()) {
            return DuplicateRuleCheckResultDto.builder()
                    .isDuplicate(false).requiresReview(false).matchedRule(null).similarity(0.0)
                    .matchType("none").reasonCode("NO_EXISTING_RULES").reason("no existing rules")
                    .message("No existing rules to compare. This is the first rule.").build();
        }
        return checkDuplicateDeterministically(newRule, existingRules,
                DeviceManifestModes.boardModeCounts(snapshot.nodes(), snapshot.deviceTemplates()));
    }

    public RuleSimilarityResultDto checkSimilarity(Long userId, RuleDto newRule) {
        newRule = RuleCandidateNormalizer.normalize(newRule);
        Long editedRuleId = newRule.getId();
        var snapshot = boardStorageService.getSemanticSnapshot(userId);
        List<RuleDto> existingRules = snapshot.rules().stream()
                .filter(rule -> editedRuleId == null || !editedRuleId.equals(rule.getId()))
                .toList();
        log.debug("AI rule similarity request: userId={}, existingRules={}", userId, existingRules.size());
        if (existingRules.isEmpty()) return noExistingRules();
        RuleDto sameRule = findExactRule(newRule, existingRules, modeCountFor(snapshot));
        if (sameRule != null) return exactRuleSimilarity(sameRule);
        Map<String, RuleDto> references = ruleReferences(existingRules);
        String aiResponse = analyzeSimilarityWithAI(
                buildRuleInfoJson(newRule, "new-rule"), buildRulesListJson(references));
        log.debug("AI rule similarity response length: {} chars", aiResponse.length());
        return parseAiResponse(aiResponse, references);
    }

    public List<RuleSimilarityResultDto> checkSimilarityBatch(Long userId, List<RuleDto> rules) {
        List<RuleDto> candidates = new ArrayList<>(rules.size());
        for (int index = 0; index < rules.size(); index++) {
            RuleDto rule = rules.get(index);
            if (rule != null && rule.getId() != null) {
                throw new BadRequestException("rules[" + index + "].id must be null for a new rule.");
            }
            candidates.add(RuleCandidateNormalizer.normalize(rule));
        }

        var snapshot = boardStorageService.getSemanticSnapshot(userId);
        var modeCount = modeCountFor(snapshot);
        var earlierRules = new ArrayList<>(snapshot.rules());
        var distinct = new ArrayList<RuleDto>();
        var distinctIndices = new ArrayList<Integer>();
        var results = new RuleSimilarityResultDto[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            RuleDto candidate = candidates.get(index);
            RuleDto sameRule = findExactRule(candidate, earlierRules, modeCount);
            if (sameRule != null) {
                results[index] = exactRuleSimilarity(sameRule);
            } else {
                distinct.add(candidate);
                distinctIndices.add(index);
                earlierRules.add(candidate);
            }
        }
        if (!distinct.isEmpty()) {
            var similarities = checkDistinctSimilarityBatch(distinct, ruleReferences(snapshot.rules()));
            for (int index = 0; index < distinctIndices.size(); index++) {
                results[distinctIndices.get(index)] = similarities.get(index);
            }
        }
        return List.of(results);
    }

    private List<RuleSimilarityResultDto> checkDistinctSimilarityBatch(List<RuleDto> candidates,
            Map<String, RuleDto> references) {
        int firstDecisionIndex = references.isEmpty() ? 1 : 0;
        if (firstDecisionIndex == candidates.size()) {
            return List.of(noExistingRules());
        }

        List<Map<String, Object>> indexedCandidates = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Map<String, Object> candidate = buildRuleInfoMap(candidates.get(index), batchRuleReference(index));
            candidate.put("index", index);
            indexedCandidates.add(candidate);
        }
        String input;
        try {
            input = objectMapper.writeValueAsString(Map.of(
                    "savedRules", buildRulesList(references),
                    "candidates", indexedCandidates,
                    "firstDecisionIndex", firstDecisionIndex));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the rule similarity batch", e);
        }
        String response = promptCompletionService.complete(BATCH_SYSTEM_PROMPT, input, TEMPERATURE, BATCH_MAX_TOKENS);
        return parseBatchResponse(response, candidates, references, firstDecisionIndex);
    }

    private ToIntFunction<String> modeCountFor(BoardSemanticSnapshotDto snapshot) {
        var counts = DeviceManifestModes.boardModeCounts(snapshot.nodes(), snapshot.deviceTemplates());
        return device -> counts.getOrDefault(device, 0);
    }

    private RuleDto findExactRule(RuleDto candidate, List<RuleDto> rules, ToIntFunction<String> modeCount) {
        return rules.stream().filter(rule -> RuleSemanticSignature.exactlyMatches(candidate, rule, modeCount))
                .findFirst().orElse(null);
    }

    private RuleSimilarityResultDto exactRuleSimilarity(RuleDto rule) {
        String matchedRule = displayRule(rule);
        return RuleSimilarityResultDto.builder().isSimilar(true).isDuplicate(true).requiresReview(true)
                .matchedRule(matchedRule).similarity(1.0).reasonCode("EXACT_MATCH")
                .reason("same trigger conditions and same action").message("This rule is identical to: " + matchedRule).build();
    }

    private List<RuleSimilarityResultDto> parseBatchResponse(String response, List<RuleDto> candidates,
            Map<String, RuleDto> references, int firstDecisionIndex) {
        try {
            String cleaned = RecommendationProviderOutput.stripCodeFence(Objects.requireNonNullElse(response, ""));
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(cleaned);
            JsonNode rows = root == null ? null : root.get("results");
            if (rows == null || !rows.isArray() || rows.size() != candidates.size() - firstDecisionIndex) {
                throw new IllegalArgumentException("AI similarity batch must include one decision per comparable rule");
            }
            JsonNode[] ordered = new JsonNode[candidates.size()];
            for (JsonNode row : rows) {
                JsonNode candidateIndex = row.path("index");
                if (!candidateIndex.isIntegralNumber() || !candidateIndex.canConvertToInt()) {
                    throw new IllegalArgumentException("AI similarity batch decision index must be an integer");
                }
                int index = candidateIndex.intValue();
                if (index < firstDecisionIndex || index >= ordered.length || ordered[index] != null) {
                    throw new IllegalArgumentException("AI similarity batch contains an out-of-range or repeated index");
                }
                ordered[index] = row;
            }

            List<RuleSimilarityResultDto> results = new ArrayList<>(candidates.size());
            for (int index = 0; index < candidates.size(); index++) {
                results.add(index < firstDecisionIndex
                        ? noExistingRules()
                        : parseSimilarityDecision(ordered[index], references, true));
                // A draft becomes eligible only after its own decision.
                references.put(batchRuleReference(index), candidates.get(index));
            }
            return List.copyOf(results);
        } catch (JsonProcessingException | RuntimeException e) {
            throw invalidSimilarityResponse(e);
        }
    }

    private static String batchRuleReference(int index) {
        return "batch-" + (index + 1);
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private DuplicateRuleCheckResultDto checkDuplicateDeterministically(
            RuleDto newRule, List<RuleDto> existingRules, Map<String, Integer> modeCounts) {
        ToIntFunction<String> modeCountForDevice = device -> modeCounts.getOrDefault(device, 0);
        RuleSemanticSignature.Signature newSignature = RuleSemanticSignature.from(newRule, modeCountForDevice);
        DuplicateCandidate best = DuplicateCandidate.none();

        for (RuleDto existingRule : existingRules) {
            if (newRule.getId() != null && newRule.getId().equals(existingRule.getId())) {
                continue;
            }
            if (RuleSemanticSignature.exactlyMatches(newRule, existingRule, modeCountForDevice)) {
                best = new DuplicateCandidate(true, true, displayRule(existingRule), 1.0, "exact",
                        "EXACT_MATCH", "same trigger conditions and same action");
                break;
            }
            DuplicateCandidate candidate = compare(
                    newSignature, RuleSemanticSignature.from(existingRule, modeCountForDevice), existingRule);
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
        body.put("message", "No other saved rules to compare.");
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
            return objectMapper.writeValueAsString(buildRulesList(ruleReferences));
        } catch (Exception e) {
            log.error("Failed to build existing rules similarity JSON", e);
            throw new IllegalStateException("Could not serialize existing rules for similarity analysis", e);
        }
    }

    private List<Map<String, Object>> buildRulesList(Map<String, RuleDto> references) {
        return references.entrySet().stream()
                .map(entry -> buildRuleInfoMap(entry.getValue(), entry.getKey()))
                .toList();
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
            String cleaned = RecommendationProviderOutput.stripCodeFence(Objects.requireNonNullElse(aiResponse, ""));
            return parseSimilarityDecision(objectMapper.readTree(cleaned), ruleReferences, false);
        } catch (Exception e) {
            throw invalidSimilarityResponse(e);
        }
    }

    private RuleSimilarityResultDto parseSimilarityDecision(
            JsonNode root, Map<String, RuleDto> ruleReferences, boolean strict) {
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
        String duplicateWith = strict
                ? requireBatchReference(root.path("duplicateWith"), ruleReferences)
                : textOrNull(root.path("duplicateWith"));
        String mostSimilarWith = strict
                ? requireBatchReference(root.path("mostSimilarWith"), ruleReferences)
                : textOrNull(root.path("mostSimilarWith"));
        if (strict && (!root.path("reason").isTextual()
                || isDuplicate != (duplicateWith != null)
                || (isSimilar && !isDuplicate && mostSimilarWith == null))) {
            throw new IllegalArgumentException("AI similarity batch decision has incomplete or inconsistent evidence");
        }
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
    }

    private String requireBatchReference(JsonNode node, Map<String, RuleDto> references) {
        if (node.isNull()) return null;
        if (!node.isTextual() || !references.containsKey(node.textValue())) {
            throw new IllegalArgumentException("AI similarity batch contains an invalid or unavailable rule reference");
        }
        return node.textValue();
    }

    private RecommendationFailure invalidSimilarityResponse(Exception e) {
        log.error("Failed to parse AI rule similarity response: exception={}", e.getClass().getName());
        return new RecommendationFailure(
                "Failed to parse AI similarity result. Please try again.",
                "AI_RESPONSE_INVALID", Map.of("phase", "response_parse"), e);
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
