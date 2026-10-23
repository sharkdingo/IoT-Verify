package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.component.aitool.BoardSemanticValidator;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationCapabilityView;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationAdjustmentItem;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationFilterItem;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationResponseDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.RuleRecommendationDto;
import cn.edu.nju.Iot_Verify.util.EnumLiteralResolver;
import cn.edu.nju.Iot_Verify.util.RecommendationInputs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

import static cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics.findConditionVariable;

/**
 * 智能规则推荐服务
 * 基于 LLM 实现智能规则推荐，根据当前面板上的设备信息生成自动化规则建议
 */
@Slf4j
@Service
public class RuleRecommendationService {

    private final DeviceInfoHelper deviceInfoHelper;
    private final BoardStorageService boardStorageService;
    private final PromptCompletionService promptCompletionService;

    private final ObjectMapper objectMapper;
    private final RecommendationResultParser resultParser;

    private static final double TEMPERATURE = 0.7;
    private static final int MAX_TOKENS = 4000;
    private static final Set<String> RECOMMENDATION_FIELDS =
            Set.of("name", "reason", "conditions", "command", "requiresUserInput");
    private static final Set<String> CONDITION_FIELDS =
            Set.of("deviceId", "deviceLabel", "deviceName", "attribute", "targetType", "relation", "value");
    private static final Set<String> COMMAND_FIELDS =
            Set.of("deviceId", "deviceLabel", "deviceName", "action", "contentDevice",
                    "contentDeviceLabel", "content", "contentPrivacy");

    private static final String SYSTEM_PROMPT = """
你是智能物联网(IoT)自动化规则推荐助手。你的任务是根据用户面板上的设备信息，
分析设备之间的联动可能性，并生成有价值的自动化规则建议。

## 输入信息
- 用户面板上的所有设备列表（包括设备名称、模板类型、当前运行值和完整 capabilities）
- 现有的自动化规则（用于避免重复推荐）

`capabilities` 是模板行为的权威视图：必须使用其中的变量域、FalsifiableWhenCompromised、
NaturalChangeRate、WorkingStates.Dynamics、Transitions、API Signal/AcceptsContent/StartState/EndState
和 Contents 描述来判断可达性、攻击面和内容动作；不得只凭名称猜测。输入还包含当前
Environment Pool，其中的 value/trust/privacy 是用户当前覆盖后的共享值。

## 输出要求
请分析设备之间的联动场景，生成符合以下JSON格式的规则推荐：

```json
{
  "recommendations": [
    {
      "name": "将作为画布规则名称保存的简洁自然语言名称",
      "reason": "为什么该规则适合用户目标和当前设备能力的简洁说明",
      "conditions": [
        {
          "deviceId": "设备 id（必须来自设备列表的 deviceId 字段）",
          "deviceName": "设备显示名称（来自设备列表的 label 字段，仅用于展示）",
          "attribute": "触发属性（必须是该设备实际存在的变量、模式名、signal API 名，或 state）",
          "targetType": "api|variable|mode|state",
          "relation": "所有值条件可用 =|!=|in|not in；数值变量额外可用 >|<|>=|<=；api 请省略（写 = TRUE 也接受）",
          "value": "触发值（仅 targetType 为 variable/mode/state 时填写）"
        }
      ],
      "command": {
        "deviceId": "目标设备 id（必须来自设备列表的 deviceId 字段）",
        "deviceName": "目标设备显示名称（仅用于展示）",
        "action": "动作名称（必须是该设备实际存在的API）",
        "contentDevice": null或"内容设备 id",
        "content": null或"内容值"
      }
    }
  ]
}
```

## 值得覆盖的场景（仅供发想，输出中没有分类字段）
优先考虑安全联动（燃气泄漏、烟雾、门窗异常）、节能（无人时关闭灯光与设备）、
舒适（按温湿度与光照调节环境）以及日常自动化（人体感应、门磁触发）。

## 重要约束
- conditions中的deviceId必须使用设备列表中的 deviceId；deviceName 只用于展示
- conditions中的targetType必须明确为 api、variable、mode 或 state
- conditions 中 targetType="api" 时，attribute 只能使用设备列表里的 apiSignals（Signal=true 的 API），不能使用普通动作 API。relation/value 请直接省略；写成语义等价的 relation="=" 且 value="TRUE" 也会被接受并规范化（记入 adjustedItems），但只写一半、写 FALSE 或用其他关系符会让整条推荐被丢弃
- conditions 中 targetType="variable"、"mode" 或 "state" 时，必须填写 relation 和 value
- conditions 中 targetType="mode" 或 "state" 只能使用 =、!=、in、not in；枚举变量也只能使用 =、!=、in、not in；数值变量可使用 =、!=、in、not in，并额外支持 >、<、>=、<=
- conditions中的attribute必须是该设备实际存在的 signal API 名、变量名、模式名，或固定的 state；模式值必须来自该模式 values，state 值必须来自工作状态
- variable 条件只能使用设备本地变量或 capabilities 中 reads=true 的共享变量；reads=false 的共享变量只受该设备影响而不被观测，不能作为触发条件
- command中的action必须是该设备实际存在的API名称
- 同一条规则的所有 conditions 必须能在模板声明的状态/变量定义域中同时成立；如果 command API 声明了非空 StartState，目标设备条件还必须与该前置状态兼容
- contentDevice 与 content 必须同时为 null，或同时填写；content 必须来自该内容设备的 contents 列表，且目标 API 必须声明 acceptsContent=true。只在动作确实携带该内容并需要分析隐私标签传播时填写
- 按与用户目标和设备能力的匹配程度从高到低排列；name 是应用后实际保存的规则名称，不要输出不存在于规则模型中的 priority
- reason 必须用用户要求的语言说明推荐依据，并引用实际设备能力或用户需求，不能只重复 name，且不超过 1000 个字符
- value 永远是字符串，不是数组；relation 为 in / not in 时把多个取值写成一个英文逗号分隔的字符串（例："cool,heat"）
- 数值变量的 value 必须是该变量 range 区间内的整数（不带单位、不带小数）；枚举变量的 value 必须是其 values 列表中的某一项（必须保留声明的大小写；仅无歧义的拼写差异可被规范化，不得在不同枚举值之间猜测）
- NaturalChangeRate 是对 v' - v 的约束，不是候选清单：区间内每个整数都可能发生，区间外的都不可能。0 表示没有独立自然变化；区间不含 0 时该变量每步都会变化（再与设备效应合并并裁剪到声明域）
- 条件组不仅要在定义域内相容，还必须能从设备当前状态与变量值出发，经 capabilities 的 Transitions 与已声明 API 到达。只有"设备本地（deviceLocal=true）、当前值已知、且 NaturalChangeRate 为 0"的变量才会被判不可达；会自然变化的变量与共享环境变量都不受此限制；共享环境变量的任何已声明取值在第一步都可达，不要因当前 Environment Pool 的读数而回避（例如烟雾当前读数为 clear 时，仍应推荐"检测到烟雾就报警"）。命令 API 的 StartState 同样必须可达
- 只输出上述 JSON 骨架中的字段。自行发明的字段（如 priority、severity）会导致整条推荐被丢弃；不要为了"补全"而添加骨架里没有的键。省略某个骨架字段本身不会被判错——按各字段的规则决定填或不填即可
- 不要推荐与现有规则重复的规则
- 如果设备信息不足，返回空的recommendations数组
""";

    public RuleRecommendationService(DeviceInfoHelper deviceInfoHelper,
                                     BoardStorageService boardStorageService,
                                     PromptCompletionService promptCompletionService,
                                     ObjectMapper objectMapper,
                                     RecommendationResultParser resultParser) {
        this.deviceInfoHelper = deviceInfoHelper;
        this.boardStorageService = boardStorageService;
        this.promptCompletionService = promptCompletionService;
        this.objectMapper = objectMapper;
        this.resultParser = resultParser;
    }

    public RecommendationResponseDto<RuleRecommendationDto> recommend(
            Long userId, Integer requestedMax, String requestedLanguage, String requestedRequirement) {
        int maxRecommendations = RecommendationInputs.maxRecommendations(requestedMax);
        String language = RecommendationInputs.language(requestedLanguage);
        String userRequirement = RecommendationInputs.userRequirement(requestedRequirement);
        BoardSemanticSnapshotDto board = boardStorageService.getSemanticSnapshot(userId);
        List<DeviceInfoHelper.DeviceInfo> devices = deviceInfoHelper.getDevicesWithTemplateInfo(board);
        List<DeviceTemplateDto> templates = board.deviceTemplates();
        List<DeviceNodeDto> nodes = board.nodes();
        List<BoardEnvironmentVariableDto> environmentVariables =
                board.environmentVariables();

        log.debug("Rule recommendation request: userId={}, devices={}, max={}",
                userId, devices.size(), maxRecommendations);

        if (devices.isEmpty()) {
            log.warn("No devices found on board for user {}", userId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", recommendationMessage(language, "noDevices", 0));
            result.put("count", 0);
            result.put("requestedCount", maxRecommendations);
            result.put("validatedCount", 0);
            result.put("filteredCount", 0);
            result.put("filteredItems", Collections.emptyList());
            result.put("adjustedCount", 0);
            result.put("adjustedItems", Collections.emptyList());
            result.put("rawCandidateCount", 0);
            result.put("inspectedCount", 0);
            result.put("truncatedCount", 0);
            result.put("recommendations", Collections.emptyList());
            return resultParser.parseRecommendationResponse(result, RuleRecommendationDto.class, true, "AI rule recommendation");
        }

        // 获取现有规则，避免推荐重复的
        List<cn.edu.nju.Iot_Verify.dto.rule.RuleDto> existingRules =
                board.rules();

        log.info("Generating AI-based rule recommendations for user {}: {} devices, max {} recommendations",
                userId, devices.size(), maxRecommendations);

        // 构建现有规则的简要信息
        String existingRulesInfo = buildExistingRulesInfo(existingRules);

        // 调用配置的 LLM 生成智能推荐
        String aiResponse = generateRecommendationsWithAI(
                devices,
                templates,
                environmentVariables,
                existingRulesInfo,
                maxRecommendations,
                language,
                userRequirement
        );

        log.info("Validating AI rule recommendations: userId={}, outputChars={}", userId, aiResponse.length());

        // 解析 AI 响应并进行验证
        RecommendationResponseDto<RuleRecommendationDto> result = parseAiResponse(
                aiResponse,
                devices,
                BoardSemanticValidator.recommendationContext(nodes, templates, environmentVariables),
                maxRecommendations,
                language);
        log.info("AI rule recommendations validated: userId={}, kept={}, filtered={}",
                userId, result.getCount(), result.getFilteredCount());
        return result;
    }

    /**
     * 调用配置的 LLM 生成智能推荐
     */
    private String generateRecommendationsWithAI(
            List<DeviceInfoHelper.DeviceInfo> devices,
            List<DeviceTemplateDto> templates,
            List<BoardEnvironmentVariableDto> environmentVariables,
            String existingRulesInfo,
            int maxRecommendations,
            String language,
            String userRequirement) {

        String deviceInfoJson = buildDeviceInfoJson(devices, templates, environmentVariables);
        String userPrompt = buildUserPrompt(deviceInfoJson, existingRulesInfo, maxRecommendations, language, userRequirement);

        log.info("Calling LLM for rule recommendations...");
        String content = promptCompletionService.completeRecommendation(
                SYSTEM_PROMPT, userPrompt, TEMPERATURE, MAX_TOKENS);

        if (content == null || content.isBlank()) {
            log.warn("AI returned empty content");
            return "";
        }

        log.debug("AI response content length: {}", content.length());
        return content;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String deviceInfoJson, String existingRulesInfo, int maxRecommendations, String language, String userRequirement) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下设备信息生成智能自动化规则推荐。\n\n");

        prompt.append("## 设备列表\n");
        prompt.append(deviceInfoJson);
        prompt.append("\n\n");

        if (existingRulesInfo != null && !existingRulesInfo.isEmpty()) {
            prompt.append("## 现有规则（避免重复推荐）\n");
            prompt.append(existingRulesInfo);
            prompt.append("\n\n");
        }

        prompt.append("## 推荐要求\n");
        prompt.append("- 最大推荐数量: ").append(maxRecommendations).append("\n");
        prompt.append(languageInstruction(language)).append("\n");

        if (userRequirement != null && !userRequirement.isBlank()) {
            prompt.append("- 用户需求场景: ").append(userRequirement).append("\n");
            prompt.append("- 优先推荐能服务该场景的规则；若设备能力不足，不要编造设备、变量或 API。\n");
        }

        prompt.append("\n请直接返回JSON格式的推荐结果，不要包含其他文字。");

        return prompt.toString();
    }

    private String languageInstruction(String language) {
        if ("zh-CN".equals(language)) {
            return "- 输出语言: 简体中文。name 与 reason 必须使用简体中文。";
        }
        return "- Output language: English. Use English for name and reason.";
    }

    private String recommendationMessage(String language, String key, int count) {
        boolean zh = "zh-CN".equals(language);
        return switch (key) {
            case "noDevices" -> zh
                    ? "画布中暂无设备。请先添加设备后再请求规则推荐。"
                    : "No devices found on the board. Please add devices first before requesting rule recommendations.";
            case "empty" -> zh
                    ? "暂无可用规则推荐。AI 建议未能匹配当前设备能力。"
                    : "No valid recommendations available. AI suggestions did not match actual device capabilities.";
            case "noCandidates" -> zh
                    ? "AI 本次没有返回任何规则候选；后端没有过滤项目。请调整需求或重试。"
                    : "AI returned no rule candidates in this run; the backend filtered nothing. Refine the request or retry.";
            case "found" -> zh
                    ? String.format("基于当前设备找到 %d 条规则推荐。", count)
                    : String.format("Found %d validated rule recommendations based on your current devices.", count);
            case "parseFailed" -> zh
                    ? "解析 AI 推荐失败，请重试。"
                    : "Failed to parse AI recommendations. Please try again.";
            default -> zh ? "规则推荐已完成。" : "Rule recommendation completed.";
        };
    }

    /**
     * 构建设备详细信息 JSON，用于 AI 分析
     */
    private String buildDeviceInfoJson(List<DeviceInfoHelper.DeviceInfo> devices,
                                       List<DeviceTemplateDto> templates,
                                       List<BoardEnvironmentVariableDto> environmentVariables) {
        try {
            List<Map<String, Object>> deviceList = new ArrayList<>();
            Map<String, DeviceTemplateDto> templatesByName =
                    RecommendationCapabilityView.indexTemplates(templates);

            for (DeviceInfoHelper.DeviceInfo device : devices) {
                Map<String, Object> deviceMap = new LinkedHashMap<>();
                deviceMap.put("deviceId", device.nodeId());
                deviceMap.put("label", device.label());
                deviceMap.put("templateName", device.templateName());
                deviceMap.put("currentState", device.currentState());
                deviceMap.put("currentStateTrust", device.currentStateTrust());
                deviceMap.put("currentStatePrivacy", device.currentStatePrivacy());
                deviceMap.put("initialVariables", device.instanceVariables() != null ? device.instanceVariables() : Collections.emptyList());
                deviceMap.put("initialPrivacies", device.instancePrivacies() != null ? device.instancePrivacies() : Collections.emptyList());
                DeviceTemplateDto template = RecommendationCapabilityView.resolveTemplate(
                        templatesByName, device.templateName());
                deviceMap.put("capabilities", RecommendationCapabilityView.fromManifest(
                        template == null ? null : template.getManifest()));

                // Only observable values can trigger rules; declared labels remain in capabilities.
                List<String> triggerableAttributes = new ArrayList<>();
                if (device.variables() != null) {
                    for (DeviceInfoHelper.VariableInfo var : device.variables()) {
                        if (findConditionVariable(template == null ? null : template.getManifest(), var.name()) != null) {
                            triggerableAttributes.add(var.name());
                        }
                    }
                }
                if (device.modes() != null) {
                    for (DeviceInfoHelper.ModeInfo mode : device.modes()) {
                        triggerableAttributes.add(mode.name());
                    }
                    if (!device.modes().isEmpty()) {
                        triggerableAttributes.add("state");
                    }
                }
                List<String> apiSignals = new ArrayList<>();
                if (device.apis() != null) {
                    for (DeviceInfoHelper.ApiInfo api : device.apis()) {
                        if (Boolean.TRUE.equals(api.signal()) && api.name() != null) {
                            apiSignals.add(api.name());
                            triggerableAttributes.add(api.name());
                        }
                    }
                }
                deviceMap.put("triggerableAttributes", triggerableAttributes);
                deviceMap.put("apiSignals", apiSignals);

                List<Map<String, Object>> modes = new ArrayList<>();
                if (device.modes() != null) {
                    for (DeviceInfoHelper.ModeInfo mode : device.modes()) {
                        Map<String, Object> modeMap = new LinkedHashMap<>();
                        modeMap.put("name", mode.name());
                        modeMap.put("values", mode.values() != null ? mode.values() : Collections.emptyList());
                        modes.add(modeMap);
                    }
                }
                deviceMap.put("modes", modes);

                // 提取可用的 API（可执行的动作为"控制"）
                List<Map<String, Object>> actionableApis = new ArrayList<>();
                if (device.apis() != null) {
                    for (DeviceInfoHelper.ApiInfo api : device.apis()) {
                        Map<String, Object> apiMap = new LinkedHashMap<>();
                        apiMap.put("name", api.name());
                        apiMap.put("description", api.description());
                        apiMap.put("signal", Boolean.TRUE.equals(api.signal()));
                        apiMap.put("acceptsContent", Boolean.TRUE.equals(api.acceptsContent()));
                        apiMap.put("startState", api.startState());
                        apiMap.put("endState", api.endState());
                        actionableApis.add(apiMap);
                    }
                }
                deviceMap.put("actionableApis", actionableApis);

                // 可用的工作状态
                deviceMap.put("workingStates", device.workingStates() != null ? device.workingStates() : Collections.emptyList());
                deviceMap.put("contents", device.contents() != null ? device.contents() : Collections.emptyList());

                deviceList.add(deviceMap);
            }

            Map<String, Object> context = new LinkedHashMap<>();
            context.put("devices", deviceList);
            context.put("environmentVariables", environmentVariables == null
                    ? Collections.emptyList() : environmentVariables);
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            log.error("Failed to build device info JSON", e);
            throw new IllegalStateException("Could not serialize current device capabilities for recommendation", e);
        }
    }

    /**
     * 构建现有规则的简要信息
     */
    private String buildExistingRulesInfo(List<cn.edu.nju.Iot_Verify.dto.rule.RuleDto> existingRules) {
        if (existingRules == null || existingRules.isEmpty()) {
            return "无现有规则";
        }

        try {
            List<Map<String, Object>> rulesInfo = new ArrayList<>();
            for (var rule : existingRules) {
                Map<String, Object> ruleInfo = new LinkedHashMap<>();
                ruleInfo.put("description", rule.getRuleString());
                ruleInfo.put("conditions", safeList(rule.getConditions()).stream().map(cond -> {
                    Map<String, Object> condition = new LinkedHashMap<>();
                    condition.put("deviceId", cond.getDeviceName());
                    condition.put("targetType", cond.getTargetType());
                    condition.put("attribute", cond.getAttribute());
                    condition.put("relation", cond.getRelation());
                    condition.put("value", cond.getValue());
                    return condition;
                }).toList());
                if (rule.getCommand() != null) {
                    Map<String, Object> command = new LinkedHashMap<>();
                    command.put("deviceId", rule.getCommand().getDeviceName());
                    command.put("action", rule.getCommand().getAction());
                    command.put("contentDevice", rule.getCommand().getContentDevice());
                    command.put("content", rule.getCommand().getContent());
                    ruleInfo.put("command", command);
                }
                rulesInfo.add(ruleInfo);
            }
            return objectMapper.writeValueAsString(rulesInfo);
        } catch (Exception e) {
            log.error("Failed to build existing rules info", e);
            throw new IllegalStateException("Could not serialize existing rules for recommendation", e);
        }
    }

    /**
     * 解析 AI 响应并验证设备变量和API
     */
    @SuppressWarnings("unchecked")
    private RecommendationResponseDto<RuleRecommendationDto> parseAiResponse(String aiResponse,
                                   List<DeviceInfoHelper.DeviceInfo> devices,
                                   BoardSemanticValidator.BoardContext semanticContext,
                                   int maxRecommendations,
                                   String language) {
        try {
            // 清理 AI 返回的内容，去除 Markdown 代码块标记
            String cleanedResponse = aiResponse.trim();
            if (cleanedResponse.startsWith("```")) {
                // 去除开头的 ```json 或 ```
                int firstNewline = cleanedResponse.indexOf('\n');
                if (firstNewline > 0) {
                    cleanedResponse = cleanedResponse.substring(firstNewline).trim();
                }
            }
            if (cleanedResponse.endsWith("```")) {
                cleanedResponse = cleanedResponse.substring(0, cleanedResponse.lastIndexOf("```")).trim();
            }

            // 构建设备能力映射，用于验证
            Map<String, DeviceInfoHelper.DeviceInfo> deviceMap = new HashMap<>();
            for (DeviceInfoHelper.DeviceInfo device : devices) {
                deviceMap.put(device.nodeId(), device);
            }

            // 尝试解析 AI 返回的 JSON
            JsonNode root = objectMapper.readTree(cleanedResponse);

            JsonNode recommendationsNode = root.path("recommendations");
            if (recommendationsNode.isMissingNode() && root.isArray()) {
                recommendationsNode = root;
            }
            if (!recommendationsNode.isArray()) {
                throw new IllegalArgumentException("AI response must contain a recommendations array");
            }

            List<Map<String, Object>> recommendations = new ArrayList<>();
            List<Map<String, Object>> filteredItems = new ArrayList<>();
            List<Map<String, Object>> adjustedItems = new ArrayList<>();
            int rawCandidateCount = recommendationsNode.size();
            int count = 0;
            int inspected = 0;

            for (JsonNode rec : recommendationsNode) {
                if (count >= maxRecommendations) break;
                inspected++;

                try {
                    Map<String, Object> recommendation = objectMapper.convertValue(rec, Map.class);
                    // Both keys are shapes the model volunteers unprompted and neither has a
                    // verification meaning, so they are stripped rather than allowed to fail the
                    // candidate: rejecting a `category` the prompt no longer even mentions would
                    // discard rules that passed every capability and semantic check.
                    recommendation.remove("confidence");
                    recommendation.remove("category");

                    // 验证并过滤推荐
                    RecommendationValidation validation = validateRecommendation(
                            recommendation, deviceMap, semanticContext,
                            language, inspected);
                    if (validation.valid()) {
                        recommendation.remove("requiresUserInput");
                        recommendations.add(recommendation);
                        adjustedItems.addAll(validation.adjustments());
                        count++;
                    } else {
                        log.debug("Filtered rule recommendation candidate {}: reasonCode={}",
                                inspected, validation.reasonCode());
                        filteredItems.add(filteredItem("rule", inspected, validation, recommendationLabel(recommendation)));
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse rule recommendation candidate {}: exception={}",
                            inspected, e.getClass().getName());
                    filteredItems.add(filteredItem("rule", inspected,
                            invalid("parseFailed", language), jsonText(rec.path("name"), "")));
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            if (recommendations.isEmpty()) {
                result.put("message", recommendationMessage(
                        language, rawCandidateCount == 0 ? "noCandidates" : "empty", 0));
            } else {
                result.put("message", recommendationMessage(language, "found", recommendations.size()));
            }
            result.put("count", recommendations.size());
            result.put("requestedCount", maxRecommendations);
            result.put("validatedCount", recommendations.size());
            result.put("filteredCount", filteredItems.size());
            result.put("filteredItems", filteredItems);
            result.put("adjustedCount", adjustedItems.size());
            result.put("adjustedItems", adjustedItems);
            result.put("rawCandidateCount", rawCandidateCount);
            result.put("inspectedCount", inspected);
            result.put("truncatedCount", Math.max(0, rawCandidateCount - inspected));
            result.put("recommendations", recommendations);

            return resultParser.parseRecommendationResponse(result, RuleRecommendationDto.class, true, "AI rule recommendation");

        } catch (Exception e) {
            log.error("Failed to parse AI response: exception={}", e.getClass().getName());
            throw new RecommendationFailure(
                    recommendationMessage(language, "parseFailed", 0),
                    "AI_RESPONSE_INVALID", Map.of("phase", "response_parse"), e);
        }
    }

    /**
     * 验证推荐是否使用设备实际存在的变量和API
     */
    @SuppressWarnings("unchecked")
    private RecommendationValidation validateRecommendation(Map<String, Object> recommendation,
            Map<String, DeviceInfoHelper.DeviceInfo> deviceMap,
            BoardSemanticValidator.BoardContext semanticContext,
            String language,
            int recommendationIndex) {

        List<Map<String, Object>> adjustments = new ArrayList<>();

        if (!hasOnlyFields(recommendation, RECOMMENDATION_FIELDS)) {
            return invalid("unknownCandidateField", language);
        }
        String name = asTrimmedString(recommendation.get("name"));
        if (name == null) {
            return invalid("missingRuleName", language);
        }
        recommendation.put("name", name);
        String reason = asTrimmedString(recommendation.get("reason"));
        if (reason != null && reason.length() > 1000) {
            return invalid("invalidRuleReason", language);
        }
        if (reason != null) recommendation.put("reason", reason);
        if (!recommendation.containsKey("conditions") || !recommendation.containsKey("command")) {
            return invalid("missingRuleFields", language);
        }

        List<Map<String, Object>> conditions = (List<Map<String, Object>>) recommendation.get("conditions");
        Map<String, Object> command = (Map<String, Object>) recommendation.get("command");

        if (conditions == null || conditions.isEmpty() || command == null) {
            return invalid("emptyConditionsOrCommand", language);
        }

        // 验证每个触发条件
        for (Map<String, Object> condition : conditions) {
            if (!hasOnlyFields(condition, CONDITION_FIELDS)) {
                return invalid("unknownCandidateField", language);
            }
            String deviceId = asTrimmedString(condition.get("deviceId"));
            String attribute = asTrimmedString(condition.get("attribute"));
            String targetType = normalizeTargetType(asTrimmedString(condition.get("targetType")));
            String relation = asTrimmedString(condition.get("relation"));
            String value = asTrimmedString(condition.get("value"));

            if (deviceId == null || attribute == null || targetType == null) {
                return invalid("conditionMissingFields", language);
            }

            // 检查设备是否存在
            DeviceInfoHelper.DeviceInfo device = resolveDevice(deviceId, deviceMap);
            if (device == null) {
                return invalid("unknownConditionDevice", language);
            }
            condition.put("deviceId", device.nodeId());
            condition.put("deviceName", device.label());
            condition.put("targetType", targetType);
            if ("api".equals(targetType)) {
                String canonicalApi = canonicalApiName(device, attribute, true);
                if (canonicalApi == null) {
                    return invalid("unknownApiSignal", language);
                }
                if (relation != null || value != null) {
                    String canonicalRelation = normalizeRelation(relation);
                    if (relation == null || value == null
                            || !"=".equals(canonicalRelation)
                            || !"TRUE".equalsIgnoreCase(value)) {
                        return invalid("invalidApiEventSyntax", language);
                    }
                    Map<String, Object> appliedValues = new LinkedHashMap<>();
                    appliedValues.put("sourceDevice", device.label());
                    appliedValues.put("sourceApi", canonicalApi);
                    appliedValues.put("removedRelation", canonicalRelation);
                    appliedValues.put("removedValue", "TRUE");
                    adjustments.add(new RecommendationAdjustmentItem(
                            "rule",
                            recommendationIndex,
                            "apiEventSyntaxNormalized",
                            adjustmentReason(language, "apiEventSyntaxNormalized"),
                            name,
                            appliedValues
                    ).toMap());
                }
                condition.put("attribute", canonicalApi);
                condition.remove("relation");
                condition.remove("value");
            } else {
                if (value == null) {
                    return invalid("conditionMissingValue", language);
                }
                String normalizedRelation = normalizeRelation(relation);
                if (relation != null && normalizedRelation == null) {
                    return invalid("invalidRelation", language);
                }
                String canonicalRelation = normalizedRelation != null ? normalizedRelation : "=";
                String canonicalAttribute = null;
                String canonicalValue = null;
                if ("variable".equals(targetType)) {
                    DeviceInfoHelper.VariableInfo variable = findVariable(device, attribute);
                    if (variable != null) {
                        canonicalAttribute = variable.name();
                        canonicalValue = canonicalVariableValue(variable, canonicalRelation, value);
                    }
                } else if ("mode".equals(targetType)) {
                    DeviceInfoHelper.ModeInfo mode = findMode(device, attribute);
                    if (mode != null && isEnumRelation(canonicalRelation)) {
                        canonicalAttribute = mode.name();
                        canonicalValue = canonicalModeValues(mode, canonicalRelation, value);
                    }
                } else if ("state".equals(targetType) && "state".equalsIgnoreCase(attribute)
                        && isEnumRelation(canonicalRelation)) {
                    canonicalAttribute = "state";
                    canonicalValue = canonicalStateValues(device, canonicalRelation, value);
                }
                if (canonicalAttribute == null || canonicalValue == null) {
                    return invalid("invalidConditionCapability", language);
                }
                condition.put("attribute", canonicalAttribute);
                condition.put("relation", canonicalRelation);
                condition.put("value", canonicalValue);
            }
        }

        // 验证执行动作
        if (!hasOnlyFields(command, COMMAND_FIELDS)) {
            return invalid("unknownCandidateField", language);
        }
        String actionDeviceId = asTrimmedString(command.get("deviceId"));
        String action = asTrimmedString(command.get("action"));

        if (actionDeviceId == null || action == null) {
            return invalid("commandMissingFields", language);
        }

        DeviceInfoHelper.DeviceInfo actionDevice = resolveDevice(actionDeviceId, deviceMap);
        if (actionDevice == null) {
            return invalid("unknownCommandDevice", language);
        }
        command.put("deviceId", actionDevice.nodeId());
        command.put("deviceName", actionDevice.label());
        DeviceInfoHelper.ApiInfo actionApi = canonicalApi(actionDevice, action, false);
        if (actionApi == null) {
            return invalid("unknownActionApi", language);
        }
        command.put("action", actionApi.name());

        String contentDevice = asTrimmedString(command.get("contentDevice"));
        String contentName = asTrimmedString(command.get("content"));
        if ((contentDevice == null) != (contentName == null)) {
            return invalid("incompleteContentPayload", language);
        }
        if (contentDevice != null) {
            if (!Boolean.TRUE.equals(actionApi.acceptsContent())) {
                return invalid("actionDoesNotAcceptContent", language);
            }
            DeviceInfoHelper.DeviceInfo resolvedContentDevice = resolveDevice(contentDevice, deviceMap);
            if (resolvedContentDevice == null) {
                return invalid("unknownContentDevice", language);
            }
            DeviceInfoHelper.ContentInfo resolvedContent = findContent(resolvedContentDevice, contentName);
            if (resolvedContent == null) {
                return invalid("unknownContent", language);
            }
            command.put("contentDevice", resolvedContentDevice.nodeId());
            command.put("contentDeviceLabel", resolvedContentDevice.label());
            command.put("content", resolvedContent.name());
            command.put("contentPrivacy", resolvedContent.privacy());
        }

        List<RuleDto.Condition> semanticConditions = new ArrayList<>();
        for (Map<String, Object> condition : conditions) {
            semanticConditions.add(RuleDto.Condition.builder()
                    .deviceName(asTrimmedString(condition.get("deviceId")))
                    .attribute(asTrimmedString(condition.get("attribute")))
                    .targetType(asTrimmedString(condition.get("targetType")))
                    .relation(asTrimmedString(condition.get("relation")))
                    .value(asTrimmedString(condition.get("value")))
                    .build());
        }
        RuleDto.Command semanticCommand = RuleDto.Command.builder()
                .deviceName(asTrimmedString(command.get("deviceId")))
                .action(asTrimmedString(command.get("action")))
                .contentDevice(asTrimmedString(command.get("contentDevice")))
                .content(asTrimmedString(command.get("content")))
                .build();
        for (int index = 0; index < semanticConditions.size(); index++) {
            if (BoardSemanticValidator.validateRuleCondition(
                    semanticContext, semanticConditions.get(index), index) != null) {
                return invalid("invalidConditionCapability", language);
            }
        }
        BoardSemanticValidator.GroupValidationIssue groupIssue =
                BoardSemanticValidator.validateRuleConditionGroup(
                        semanticContext, semanticConditions, semanticCommand);
        if (groupIssue != null) {
            String reasonCode = switch (groupIssue.reasonCode()) {
                case "COMMAND_PRESTATE_INCOMPATIBLE" -> "commandPrestateIncompatible";
                case "COMMAND_PRESTATE_UNREACHABLE" -> "commandPrestateUnreachable";
                case "UNREACHABLE_CONDITION_GROUP" -> "unreachableConditionGroup";
                default -> "contradictoryConditionGroup";
            };
            return invalid(reasonCode, language);
        }

        return RecommendationValidation.ok(adjustments);
    }

    private Map<String, Object> filteredItem(String type, int index,
                                             RecommendationValidation validation,
                                             String label) {
        return new RecommendationFilterItem(
                type,
                index,
                validation.reasonCode(),
                validation.reason(),
                label
        ).toMap();
    }

    private RecommendationValidation invalid(String reasonCode, String language) {
        return RecommendationValidation.reject(reasonCode, validationReason(language, reasonCode));
    }

    private String validationReason(String language, String reasonCode) {
        boolean zh = "zh-CN".equals(language);
        return switch (reasonCode) {
            case "missingRuleFields" -> zh
                    ? "缺少 conditions 或 command，无法形成完整自动化规则。"
                    : "Missing conditions or command, so the rule is incomplete.";
            case "missingRuleName" -> zh
                    ? "缺少将实际保存到画布的规则名称。"
                    : "The rule name that would be saved to the board is missing.";
            case "invalidRuleReason" -> zh
                    ? "面向用户的推荐理由超过 1000 个字符。"
                    : "The user-facing recommendation reason exceeds 1000 characters.";
            case "emptyConditionsOrCommand" -> zh
                    ? "触发条件为空或执行动作为空。"
                    : "The trigger conditions are empty or the command is missing.";
            case "conditionMissingFields" -> zh
                    ? "触发条件缺少设备、属性或目标类型。"
                    : "A trigger condition is missing its device, attribute, or target type.";
            case "unknownConditionDevice" -> zh
                    ? "触发条件引用了当前画布中不存在的设备实例。"
                    : "A trigger condition references a device instance that is not on the board.";
            case "unknownApiSignal" -> zh
                    ? "触发条件使用了该设备没有暴露的 signal API。"
                    : "A trigger condition uses a signal API that the device does not expose.";
            case "conditionMissingValue" -> zh
                    ? "变量、模式或状态条件缺少比较值。"
                    : "A variable, mode, or state condition is missing a comparison value.";
            case "invalidRelation" -> zh
                    ? "条件使用了不支持的关系运算符。"
                    : "A condition uses an unsupported relation operator.";
            case "invalidApiEventSyntax" -> zh
                    ? "API 事件只能省略比较字段，或使用语义等价的“= TRUE”；半缺字段、FALSE 或其他关系会改变事件语义。"
                    : "An API event must omit comparison fields or use the equivalent '= TRUE'; partial fields, FALSE, or another relation changes the event semantics.";
            case "invalidConditionCapability" -> zh
                    ? "条件引用的变量、模式、状态或取值不符合设备模板能力。"
                    : "A condition references a variable, mode, state, or value outside the device template capabilities.";
            case "commandMissingFields" -> zh
                    ? "执行动作缺少目标设备或 API。"
                    : "The command is missing its target device or API.";
            case "unknownCommandDevice" -> zh
                    ? "执行动作引用了当前画布中不存在的设备实例。"
                    : "The command references a device instance that is not on the board.";
            case "unknownActionApi" -> zh
                    ? "执行动作使用了该设备没有暴露的可执行 API。"
                    : "The command uses an action API that the device does not expose.";
            case "unknownContentDevice" -> zh
                    ? "执行动作的内容设备不在当前画布中。"
                    : "The command content device is not on the board.";
            case "incompleteContentPayload" -> zh
                    ? "内容来源设备和内容项必须同时填写；系统不会静默忽略半条内容流。"
                    : "The content source device and content item must be provided together; an incomplete content flow is not silently ignored.";
            case "unknownContent" -> zh
                    ? "内容项不在所选设备模板声明的内容列表中。"
                    : "The content item is not declared by the selected device template.";
            case "actionDoesNotAcceptContent" -> zh
                    ? "目标动作没有声明可接收内容敏感性标签；该内容流不会被附着到普通设备命令。"
                    : "The target action does not declare a content-sensitivity input, so the content flow cannot be attached to that ordinary device command.";
            case "contradictoryConditionGroup" -> zh
                    ? "这些触发条件在设备声明的合法状态或变量定义域中没有共同可满足的取值。"
                    : "The trigger conditions have no common satisfying value in the declared device state and variable domains.";
            case "commandPrestateIncompatible" -> zh
                    ? "触发条件与目标动作声明的 StartState 前置状态不兼容，因此动作无法在该条件下执行。"
                    : "The trigger conditions are incompatible with the action's declared StartState, so the command cannot execute under those conditions.";
            case "commandPrestateUnreachable" -> zh
                    ? "目标动作要求的 StartState 从设备当前状态无法通过已声明的 API 或转换到达，因此规则不会执行。"
                    : "The action's required StartState is unreachable from the device's current state through its declared APIs and transitions, so the rule cannot execute.";
            case "unreachableConditionGroup" -> zh
                    ? "这些触发条件虽然位于合法定义域内，但从当前设备状态和值无法通过已声明行为到达。"
                    : "The trigger conditions are legal but unreachable from the current device state and values under the declared behavior.";
            case "parseFailed" -> zh
                    ? "该候选项不是可解析的推荐对象。"
                    : "The candidate is not a parseable recommendation object.";
            case "unknownCandidateField" -> zh
                    ? "候选规则包含系统不认识的字段；为避免静默丢失其含义，整条候选已过滤。"
                    : "The rule candidate contains an unknown field; the whole candidate was filtered to avoid silently losing its meaning.";
            default -> zh
                    ? "该候选项未通过后端能力校验。"
                    : "The candidate did not pass backend capability validation.";
        };
    }

    private String adjustmentReason(String language, String reasonCode) {
        boolean zh = "zh-CN".equals(language);
        return switch (reasonCode) {
            case "apiEventSyntaxNormalized" -> zh
                    ? "AI 将 API 事件写成了布尔条件“= TRUE”；系统按相同用户语义规范化为事件触发，并移除了比较字段。"
                    : "The AI expressed the API event as '= TRUE'; it was normalized to the same event-trigger semantics by removing the comparison fields.";
            default -> zh
                    ? "系统对保留的规则候选进行了语义保持的规范化。"
                    : "The kept rule candidate was normalized without changing its user-facing semantics.";
        };
    }

    private String recommendationLabel(Map<String, Object> recommendation) {
        String name = asTrimmedString(recommendation.get("name"));
        if (name != null) return name;
        Object command = recommendation.get("command");
        return command == null ? null : String.valueOf(command);
    }

    private boolean hasOnlyFields(Map<String, Object> value, Set<String> allowedFields) {
        return value != null && allowedFields.containsAll(value.keySet());
    }

    private String jsonText(JsonNode node, String fallback) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.asText("").trim() : fallback;
    }

    private record RecommendationValidation(
            boolean valid,
            String reasonCode,
            String reason,
            List<Map<String, Object>> adjustments) {
        private static RecommendationValidation ok(List<Map<String, Object>> adjustments) {
            return new RecommendationValidation(true, "", "", List.copyOf(adjustments));
        }

        private static RecommendationValidation reject(String reasonCode, String reason) {
            return new RecommendationValidation(false, reasonCode, reason, List.of());
        }
    }

    private DeviceInfoHelper.DeviceInfo resolveDevice(String deviceRef,
            Map<String, DeviceInfoHelper.DeviceInfo> deviceMap) {
        if (deviceRef == null) {
            return null;
        }
        return deviceMap.get(deviceRef);
    }

    private String normalizeTargetType(String targetType) {
        if (targetType == null) {
            return null;
        }
        String normalized = targetType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "api", "variable", "mode", "state" -> normalized;
            default -> null;
        };
    }

    private String asTrimmedString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String normalizeRelation(String relation) {
        String normalized = SmvRelationUtils.normalizeRelation(relation);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return SmvRelationUtils.isSupportedRelation(normalized) ? normalized : null;
    }

    private boolean isEnumRelation(String relation) {
        return "=".equals(relation) || "!=".equals(relation)
                || "in".equals(relation) || "not in".equals(relation);
    }

    private String canonicalApiName(DeviceInfoHelper.DeviceInfo device, String rawName, boolean signalOnly) {
        DeviceInfoHelper.ApiInfo api = canonicalApi(device, rawName, signalOnly);
        return api != null ? api.name() : null;
    }

    private DeviceInfoHelper.ApiInfo canonicalApi(
            DeviceInfoHelper.DeviceInfo device, String rawName, boolean signalOnly) {
        if (device.apis() == null || rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        for (DeviceInfoHelper.ApiInfo api : device.apis()) {
            if (api.name() != null
                    && api.name().equalsIgnoreCase(trimmed)
                    && (!signalOnly || Boolean.TRUE.equals(api.signal()))) {
                return api;
            }
        }
        return null;
    }

    private DeviceInfoHelper.VariableInfo findVariable(DeviceInfoHelper.DeviceInfo device, String rawName) {
        if (device.variables() == null || rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        for (DeviceInfoHelper.VariableInfo variable : device.variables()) {
            if (variable.name() != null && variable.name().equalsIgnoreCase(trimmed)) {
                return variable;
            }
        }
        return null;
    }

    private DeviceInfoHelper.ContentInfo findContent(DeviceInfoHelper.DeviceInfo device, String rawName) {
        if (device.contents() == null || rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        for (DeviceInfoHelper.ContentInfo content : device.contents()) {
            if (content.name() != null && content.name().equalsIgnoreCase(trimmed)) {
                return content;
            }
        }
        return null;
    }

    private DeviceInfoHelper.ModeInfo findMode(DeviceInfoHelper.DeviceInfo device, String rawName) {
        if (device.modes() == null || rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        for (DeviceInfoHelper.ModeInfo mode : device.modes()) {
            if (mode.name() != null && mode.name().equalsIgnoreCase(trimmed)) {
                return mode;
            }
        }
        return null;
    }

    private String canonicalVariableValue(DeviceInfoHelper.VariableInfo variable, String relation, String rawValue) {
        if (variable == null || rawValue == null) {
            return null;
        }
        List<String> candidates = splitValueCandidates(rawValue, relation, false);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        if (variable.values() != null && !variable.values().isEmpty()) {
            if (!isEnumRelation(relation)) {
                return null;
            }
            for (String candidate : candidates) {
                String value = EnumLiteralResolver.resolveIgnoringSpaces(variable.values(), candidate);
                if (value == null) {
                    return null;
                }
                canonical.add(value);
            }
        } else if (variable.lowerBound() != null && variable.upperBound() != null) {
            for (String candidate : candidates) {
                try {
                    int parsed = Integer.parseInt(candidate.trim());
                    if (parsed < variable.lowerBound() || parsed > variable.upperBound()) {
                        return null;
                    }
                    canonical.add(String.valueOf(parsed));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        } else {
            canonical.addAll(candidates);
        }
        return joinCanonicalValues(canonical, relation);
    }

    private String canonicalModeValues(DeviceInfoHelper.ModeInfo mode, String relation, String rawValue) {
        if (mode == null || mode.values() == null || rawValue == null) {
            return null;
        }
        List<String> candidates = splitValueCandidates(rawValue, relation, false);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        for (String candidate : candidates) {
            String value = EnumLiteralResolver.resolveIgnoringSpaces(mode.values(), candidate);
            if (value == null) {
                return null;
            }
            canonical.add(value);
        }
        return joinCanonicalValues(canonical, relation);
    }

    private String canonicalStateValues(DeviceInfoHelper.DeviceInfo device, String relation, String rawValue) {
        if (device == null || rawValue == null) {
            return null;
        }
        boolean multiMode = device.modes() != null && device.modes().size() > 1;
        List<String> candidates = splitValueCandidates(rawValue, relation, multiMode);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        for (String candidate : candidates) {
            String value = canonicalStateValue(device, candidate);
            if (value == null) {
                return null;
            }
            canonical.add(value);
        }
        return joinCanonicalValues(canonical, relation);
    }

    private String canonicalStateValue(DeviceInfoHelper.DeviceInfo device, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String workingState = EnumLiteralResolver.resolveIgnoringSpaces(device.workingStates(), trimmed);
        if (workingState != null) {
            return workingState;
        }
        if (trimmed.contains(";")) {
            return canonicalStateTuple(device, trimmed);
        }
        return canonicalUniqueModeValue(device, trimmed);
    }

    private String canonicalStateTuple(DeviceInfoHelper.DeviceInfo device, String rawValue) {
        if (device.modes() == null || device.modes().isEmpty()) {
            return null;
        }
        String[] parts = rawValue.split(";", -1);
        if (parts.length != device.modes().size()) {
            return null;
        }
        List<String> canonicalParts = new ArrayList<>();
        boolean hasConcretePart = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty() || "_".equals(part)) {
                canonicalParts.add("");
                continue;
            }
            String value = EnumLiteralResolver.resolveIgnoringSpaces(device.modes().get(i).values(), part);
            if (value == null) {
                return null;
            }
            canonicalParts.add(value);
            hasConcretePart = true;
        }
        return hasConcretePart ? String.join(";", canonicalParts) : null;
    }

    private String canonicalUniqueModeValue(DeviceInfoHelper.DeviceInfo device, String rawValue) {
        if (device.modes() == null) {
            return null;
        }
        String match = null;
        int matches = 0;
        for (DeviceInfoHelper.ModeInfo mode : device.modes()) {
            String candidate = EnumLiteralResolver.resolveIgnoringSpaces(mode.values(), rawValue);
            if (candidate != null) {
                matches++;
                if (matches > 1) {
                    return null;
                }
                match = candidate;
            }
        }
        return match;
    }

    private List<String> splitValueCandidates(String rawValue, String relation, boolean preserveSemicolon) {
        if (rawValue == null) {
            return List.of();
        }
        String splitRegex = ("in".equals(relation) || "not in".equals(relation))
                ? (preserveSemicolon ? "[,|]" : "[,;|]")
                : null;
        if (splitRegex == null) {
            String trimmed = rawValue.trim();
            return trimmed.isEmpty() ? List.of() : List.of(trimmed);
        }
        List<String> result = new ArrayList<>();
        for (String part : rawValue.split(splitRegex)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private String joinCanonicalValues(List<String> values, String relation) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return ("in".equals(relation) || "not in".equals(relation))
                ? String.join(",", values)
                : values.get(0);
    }
    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

}
