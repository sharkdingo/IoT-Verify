package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.component.aitool.BoardSemanticValidator;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationCapabilityView;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationFilterItem;
import cn.edu.nju.Iot_Verify.component.aitool.spec.SpecificationTemplateSemantics;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.RecommendationResponseDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.SpecificationRecommendationDto;
import cn.edu.nju.Iot_Verify.util.RecommendationInputs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 智能规约推荐服务
 * 根据用户画布中现有的设备、规则和规约，从系统可用规约模板中推荐最合适的规约来完善整个物联网系统
 */
@Slf4j
@Service
public class SpecificationRecommendationService {

    private final DeviceInfoHelper deviceInfoHelper;
    private final BoardStorageService boardStorageService;
    private final PromptCompletionService promptCompletionService;

    private final ObjectMapper objectMapper;
    private final RecommendationResultParser resultParser;

    private static final double TEMPERATURE = 0.7;
    private static final int MAX_TOKENS = 4000;
    private static final Set<String> ALLOWED_TEMPLATE_IDS = Set.of("1", "2", "3", "4", "5", "6", "7");
    private static final Set<String> RECOMMENDATION_FIELDS =
            Set.of("rationale", "templateId", "templateLabel",
                    "aConditions", "ifConditions", "thenConditions");
    private static final Set<String> CONDITION_FIELDS =
            Set.of("deviceId", "deviceLabel", "targetType", "key", "propertyScope", "variableSource",
                    "relation", "value");

    private static final String SYSTEM_PROMPT = """
你是智能物联网(IoT)规约推荐助手。你的任务是分析用户面板中现有的设备、规则和规约，
从系统可用规约模板中推荐最合适的规约来完善整个物联网系统的安全性和可靠性验证。

## 输入信息
- 用户画布中现有的设备列表（包含完整 capabilities、当前运行值和 trust/privacy 用户域目标）
- 用户画布中现有的自动化规则
- 用户画布中现有的规约列表

`capabilities` 是模板行为的权威视图：必须使用其中的变量域、FalsifiableWhenCompromised、
NaturalChangeRate、WorkingStates.Dynamics、Transitions、API Signal/AcceptsContent/StartState/EndState
和 Contents 描述来判断条件是否有行为意义。输入还包含当前 Environment Pool，其中的
value/trust/privacy 是用户当前覆盖后的共享值。

## 设备属性结构说明（非常重要！）
每个设备的信息结构如下：
- **deviceId**: 设备实例 id，也是推荐结果中 deviceId 必须使用的设备引用值
- **label**: 设备显示名称，只能写入 deviceLabel 用于展示
- **templateName**: 设备模板名称
- **currentState**: 设备当前状态值
- **variables**: 设备的内部变量列表，每个变量包含：
  - name: 变量名称（如 "temperature", "humidity", "contact"）
  - values: 可选值列表（如 ["open", "closed"]）
  - range: 数值范围（如 "0..100"）
- **modes**: 设备模式列表，每个模式包含：
  - name: 模式名称（如 "Mode", "MachineState", "HvacMode"）
  - values: 该模式可取值（如 ["sleep", "away", "home"]）
- **apiSignals**: 可用于规约条件的 signal API（Signal=true），targetType="api" 时只能使用这些 API
- **propertyTargets**: 可用于 targetType="trust" 或 "privacy" 的目标。每项给出 propertyScope 与 key；state 表示该模式当前活动状态的标签，variable 表示变量标签
  注意：variable 范围的标签是传播来源，不是传播目标。模型只在设备被入侵且该变量 FalsifiableWhenCompromised=true 时把 trust 改为 untrusted，privacy 变量标签从不改变。因此对 variable 标签写 Always/Never 规约往往恒真或恒假、不携带信息。要检查控制来源，请对 propertyScope=state 的标签写规约，或使用模板 7
- **states**: 设备的工作状态可能值列表（如 ["auto", "cool", "dry", "off", "heat", "fanOnly"]）

## 重要约束：如何正确引用设备属性
1. **引用变量**：使用 variables 中的变量名作为 key，并且必须给出 variableSource，如：
   - targetType: "variable", key: "temperature", variableSource: "environment", relation: ">=", value: "25"
   - targetType: "variable", key: "humidity", variableSource: "reported", relation: ">=", value: "70"
   - targetType: "variable", key: "contact", variableSource: "reported", relation: "=", value: "open"

2. **引用工作状态**：使用 targetType: "state"。key 固定使用 "state"，value 使用 states 列表中的状态值，如：
   - 如果要检查空调是否开启，应该用：
     targetType: "state", key: "state", relation: "!=", value: "off"
   - 如果设备模板暴露了更具体的状态变量，也可以用 targetType: "variable" 引用该变量（同样必须给出 variableSource）

3. **引用模式**：使用 targetType: "mode"。key 必须使用 modes 中的模式名称，value 必须来自该模式的 values，如：
   - targetType: "mode", key: "Mode", relation: "=", value: "sleep"
   - targetType: "mode", key: "MachineState", relation: "!=", value: "off"

4. **引用 API 信号**：使用 targetType: "api"。key 必须来自 apiSignals，value 可使用 "TRUE" 或 "FALSE"（模板 7 除外，见下文只能用 "= TRUE"）；如果省略 relation/value，系统按 "= TRUE" 处理

5. **引用 trust/privacy**：propertyScope 与 key 必须成对来自 propertyTargets；propertyScope="state" 时 key 是模式名并检查该模式当前活动状态的标签，propertyScope="variable" 时 key 是变量名。trust value 只能为 "trusted" 或 "untrusted"，privacy value 只能为 "public" 或 "private"

6. **关系运算符约束**：所有值条件都可使用 =, !=, in, not in；数值变量额外可使用 >, <, >=, <=；枚举变量、state、mode、api、trust、privacy 不能使用大小比较

7. **禁止使用 currentState 作为 key**，因为它不是设备模板中定义的属性名

8. variable 条件必须给出 variableSource=environment|reported。environment 只能用于 capabilities 中 deviceLocal=false 且 reads=true 的变量；deviceLocal=true 的变量只能用 reported；reads=false 的共享变量只影响、不观测，不能作 variable 条件，但仍可引用其 trust/privacy 标签。（不要依赖变量描述文字判断：模板作者填写了 Description 时会覆盖派生描述。）
9. 确保 targetType 和 key/value/propertyScope 的组合在设备的 variables、modes、apiSignals、propertyTargets 或 states 中确实存在；api 条件省略 value 时等价于 TRUE

## 输出要求
请分析现有设备和规则的功能，推荐可以验证系统正确性的规约，返回符合以下JSON格式的推荐：

```json
{
  "recommendations": [
    {
      "rationale": "解释为什么建议检查这条规约；仅用于推荐说明，不是规约字段",
      "templateId": "规约模板ID（必填，只能是 1|2|3|4|5|6|7）",
      "aConditions": [ <Condition>, ... ],
      "ifConditions": [ <Condition>, ... ],
      "thenConditions": [ <Condition>, ... ]
    }
  ]
}
```

按模板形状只填写对应的条件数组，其余数组完全省略。<Condition> 的字段定义（三个数组共用同一形状）：

```json
{
    "deviceId": "设备 id（必须来自设备列表的 deviceId 字段）",
    "deviceLabel": "设备 label（仅用于展示）",
    "targetType": "state|mode|variable|api|trust|privacy",
    "key": "状态/模式/变量/API名称或 propertyTargets 中的 key",
    "propertyScope": "仅 trust/privacy 必填且必须与 propertyTargets 一致（state|variable）；其他 targetType 必须完全省略该字段",
    "variableSource": "仅 targetType=variable 必填，其他 targetType 必须完全省略：environment 表示家中实际值（要求 capabilities 中该变量 deviceLocal=false 且 reads=true），reported 表示该设备上报值；设备被入侵时两者不同，没有默认值",
    "relation": "所有值条件可用 =|!=|in|not in；数值变量额外可用 >|<|>=|<=；api 可省略，默认 =",
    "value": "期望值（api 可省略，默认 TRUE）"
  }
```

## 规约模板类型
%s

## 重要约束
- 在模板语义允许的范围内覆盖不同关注点（安全、联动响应、状态一致性、隐私），不要对同一设备重复同一模板
- 只有模板 1、3、4 能被有界反例探索检查；模板 2、5、6 需要活性推理，模板 7 需要信任标签传播，探索接口会把它们报为不适用。它们仍可由 NuSMV 正式验证，不要因此回避，但也不要声称探索能覆盖它们
- 模板形状必须严格匹配：1/2/3/7 只使用 aConditions；4/5/6 只使用 ifConditions + thenConditions
- 模板 7 会为 A 条件自动关联 MEDIC 控制来源标签：不得在 A 中直接写 trust/privacy；state/mode 必须使用 =；api 必须使用 = TRUE。它检查受保护事件是否带有不可信控制来源标签，不是认证或通用完整性检查。模板 7 的 api 条件只能引用 capabilities 中 EndState 非空的 signal API，且该设备必须声明 Modes/WorkingStates，否则该规约无法解析控制来源标签而被跳过（不会进入 NuSMV）。隐私泄露应使用模板 3，把“公开动作/状态发生”和对应 privacy=private 一起放入 aConditions
- aConditions、ifConditions、thenConditions 中的 targetType、key/value 必须引用该设备实际存在的 states、modes、variables 或 APIs（key 与枚举值按大小写不敏感匹配并去除首尾空格，不必纠结大小写）
- 同一个 A、IF 或 THEN 数组内的全部条件是合取关系，必须在模板声明的合法状态和变量定义域中存在共同满足值
- state、mode、api、trust、privacy 以及枚举变量只能使用 =、!=、in、not in；数值变量可使用 =、!=、in、not in，并额外支持 >、<、>=、<=
- 禁止使用 "currentState" 作为 key，它不是有效的属性名
- 条件对象中禁止出现 side 字段；归属由所在的 aConditions/ifConditions/thenConditions 数组决定
- value 永远是字符串，不是数组；relation 为 in / not in 时把多个取值写成一个英文逗号分隔的字符串（例："cool,heat"）
- 数值变量的 value 必须是该变量 range 区间内的整数（不带单位、不带小数）；枚举变量的 value 必须是其 values 列表中的某一项（大小写与首尾空格会被规范化）
- NaturalChangeRate 是对 v' - v 的约束，不是候选清单：区间内每个整数都可能发生，区间外的都不可能。0 表示没有独立自然变化；区间不含 0 时该变量每步都会变化（再与设备效应合并并裁剪到声明域）
- 条件组不仅要在定义域内相容，还必须能从设备当前状态与变量值出发，经 capabilities 的 Transitions 与已声明 API 到达。只有"设备本地（deviceLocal=true）、当前值已知、且 NaturalChangeRate 为 0"的变量才会被判不可达；会自然变化的变量与共享环境变量都不受此限制；共享环境变量的任何已声明取值在第一步都可达，不要因当前 Environment Pool 的读数而回避（例如烟雾当前读数为 clear 时，仍应推荐"检测到烟雾就报警"）
- 只输出上述 JSON 骨架中的字段。自行发明的字段（如 priority、severity）会导致整条推荐被丢弃；不要为了"补全"而添加骨架里没有的键。按模板形状省略不适用的条件数组是正确做法，不是缺字段
- 每条推荐必须包含合法 templateId，且 templateId 必须严格枚举为 "1" 到 "7"
- 按与用户目标和设备能力的匹配程度从高到低排列，并在 rationale 中解释建议依据；不要输出不存在于验证模型中的 priority
- 不要推荐与现有规约重复的规约
- 如果没有找到合适的推荐，返回空的recommendations数组
- 最多返回10个推荐，具体数量遵循用户请求的 maxRecommendations
- 推荐中 deviceId 必须准确引用设备实例 id；deviceLabel 只用于展示
""".formatted(SpecificationTemplateSemantics.chinesePromptReference());

    public SpecificationRecommendationService(DeviceInfoHelper deviceInfoHelper,
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

    public RecommendationResponseDto<SpecificationRecommendationDto> recommend(
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

        log.debug("Specification recommendation request: userId={}, devices={}, max={}",
                userId, devices.size(), maxRecommendations);

        if (devices.isEmpty()) {
            log.warn("No devices found on board for user {}", userId);
            return resultParser.parseRecommendationResponse(Map.of(
                    "message", recommendationMessage(language, "noDevices", 0),
                    "count", 0,
                    "requestedCount", maxRecommendations,
                    "validatedCount", 0,
                    "filteredCount", 0,
                    "filteredItems", Collections.emptyList(),
                    "rawCandidateCount", 0,
                    "inspectedCount", 0,
                    "truncatedCount", 0,
                    "recommendations", Collections.emptyList()
            ), SpecificationRecommendationDto.class, false, "AI specification recommendation");
        }

        // 获取现有规则，避免推荐与规则冲突的规约
        List<cn.edu.nju.Iot_Verify.dto.rule.RuleDto> existingRules =
                board.rules();

        // 获取现有规约，避免推荐重复的
        List<SpecificationDto> existingSpecs =
                board.specifications();

        log.info("Generating AI-based specification recommendations for user {}: {} devices, max {} recommendations",
                userId, devices.size(), maxRecommendations);

        // 构建现有规则和规约的简要信息
        String existingRulesInfo = buildExistingRulesInfo(existingRules);
        String existingSpecsInfo = buildExistingSpecsInfo(existingSpecs);

        // 调用 LLM 生成智能推荐
        String aiResponse = generateRecommendationsWithAI(
                devices,
                templates,
                environmentVariables,
                existingRulesInfo,
                existingSpecsInfo,
                maxRecommendations,
                language,
                userRequirement
        );

        log.debug("Specification recommendation AI response length: {} chars", aiResponse.length());

        // 解析 AI 响应并进行验证
        return parseAiResponse(
                aiResponse,
                devices,
                BoardSemanticValidator.recommendationContext(nodes, templates, environmentVariables),
                maxRecommendations,
                language);
    }

    /**
     * 调用配置的 LLM 生成智能推荐
     */
    private String generateRecommendationsWithAI(
            List<DeviceInfoHelper.DeviceInfo> devices,
            List<DeviceTemplateDto> templates,
            List<BoardEnvironmentVariableDto> environmentVariables,
            String existingRulesInfo,
            String existingSpecsInfo,
            int maxRecommendations,
            String language,
            String userRequirement) {

        String deviceInfoJson = buildDeviceInfoJson(devices, templates, environmentVariables);
        String userPrompt = buildUserPrompt(deviceInfoJson, existingRulesInfo, existingSpecsInfo, maxRecommendations, language, userRequirement);

        log.info("Calling LLM for specification recommendations...");
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
    private String buildUserPrompt(String deviceInfoJson, String existingRulesInfo, String existingSpecsInfo,
                                   int maxRecommendations, String language, String userRequirement) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下设备信息生成智能规约推荐。\n\n");

        prompt.append("## 设备列表\n");
        prompt.append(deviceInfoJson);
        prompt.append("\n\n");

        if (existingRulesInfo != null && !existingRulesInfo.isEmpty()) {
            prompt.append("## 现有规则\n");
            prompt.append(existingRulesInfo);
            prompt.append("\n\n");
        }

        if (existingSpecsInfo != null && !existingSpecsInfo.isEmpty()) {
            prompt.append("## 现有规约（避免重复推荐）\n");
            prompt.append(existingSpecsInfo);
            prompt.append("\n\n");
        }

        prompt.append("## 推荐要求\n");
        prompt.append("- 最大推荐数量: ").append(maxRecommendations).append("\n");
        prompt.append(languageInstruction(language)).append("\n");

        if (userRequirement != null && !userRequirement.isBlank()) {
            prompt.append("- 用户需求场景: ").append(userRequirement).append("\n");
            prompt.append("- 优先推荐能验证该场景安全性、响应性、一致性或隐私性的规约；若当前设备/规则不足，不要编造条件。\n");
        }

        prompt.append("\n请直接返回JSON格式的推荐结果，不要包含其他文字。");

        return prompt.toString();
    }

    private String languageInstruction(String language) {
        if ("zh-CN".equals(language)) {
            return "- 输出语言: 简体中文。rationale 必须使用简体中文。";
        }
        return "- Output language: English. Use English for the rationale.";
    }

    private String recommendationMessage(String language, String key, int count) {
        boolean zh = "zh-CN".equals(language);
        return switch (key) {
            case "noDevices" -> zh
                    ? "画布中暂无设备。请先添加设备后再请求规约推荐。"
                    : "No devices found on the board. Please add devices first before requesting specification recommendations.";
            case "empty" -> zh
                    ? "暂无可用规约推荐。AI 建议未能匹配当前设备能力。"
                    : "No valid specifications found for your board.";
            case "noCandidates" -> zh
                    ? "AI 本次没有返回任何规约候选；后端没有过滤项目。请调整需求或重试。"
                    : "AI returned no specification candidates in this run; the backend filtered nothing. Refine the request or retry.";
            case "found" -> zh
                    ? String.format("基于当前设备找到 %d 条规约推荐。", count)
                    : String.format("Found %d validated specification recommendations based on your current devices.", count);
            case "parseFailed" -> zh
                    ? "解析 AI 推荐失败，请重试。"
                    : "Failed to parse AI recommendations. Please try again.";
            default -> zh ? "规约推荐已完成。" : "Specification recommendation completed.";
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

                // 提取可用的变量
                List<Map<String, Object>> variables = new ArrayList<>();
                if (device.variables() != null) {
                    for (DeviceInfoHelper.VariableInfo var : device.variables()) {
                        Map<String, Object> varMap = new LinkedHashMap<>();
                        varMap.put("name", var.name());
                        varMap.put("description", var.description());
                        if (var.values() != null && !var.values().isEmpty()) {
                            varMap.put("values", var.values());
                        }
                        if (var.lowerBound() != null && var.upperBound() != null) {
                            varMap.put("range", var.lowerBound() + ".." + var.upperBound());
                        }
                        variables.add(varMap);
                    }
                }
                deviceMap.put("variables", variables);

                List<Map<String, Object>> apiSignals = new ArrayList<>();
                if (device.apis() != null) {
                    for (DeviceInfoHelper.ApiInfo api : device.apis()) {
                        if (Boolean.TRUE.equals(api.signal()) && api.name() != null) {
                            Map<String, Object> apiMap = new LinkedHashMap<>();
                            apiMap.put("name", api.name());
                            apiMap.put("description", api.description());
                            apiSignals.add(apiMap);
                        }
                    }
                }
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

                deviceMap.put("propertyTargets", buildPropertyTargetInfo(device));

                // 可用的工作状态
                deviceMap.put("states", device.workingStates() != null ? device.workingStates() : Collections.emptyList());

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

    private List<Map<String, Object>> buildPropertyTargetInfo(DeviceInfoHelper.DeviceInfo device) {
        List<Map<String, Object>> targets = new ArrayList<>();
        if (device.modes() != null) {
            for (DeviceInfoHelper.ModeInfo mode : device.modes()) {
                if (mode.name() != null && !mode.name().isBlank()) {
                    targets.add(Map.of(
                            "propertyScope", "state",
                            "key", mode.name(),
                            "label", "current " + mode.name() + " state"
                    ));
                }
            }
        }
        if (device.variables() != null) {
            for (DeviceInfoHelper.VariableInfo variable : device.variables()) {
                if (variable.name() != null && !variable.name().isBlank()) {
                    targets.add(Map.of(
                            "propertyScope", "variable",
                            "key", variable.name(),
                            "label", variable.name()
                    ));
                }
            }
        }
        return targets;
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
     * 构建现有规约的简要信息
     */
    private String buildExistingSpecsInfo(List<SpecificationDto> existingSpecs) {
        if (existingSpecs == null || existingSpecs.isEmpty()) {
            return "无现有规约";
        }

        try {
            List<Map<String, Object>> specsInfo = new ArrayList<>();
            for (SpecificationDto spec : existingSpecs) {
                Map<String, Object> specInfo = new LinkedHashMap<>();
                specInfo.put("templateId", spec.getTemplateId());
                specInfo.put("templateLabel", spec.getTemplateLabel());
                specInfo.put("aConditions", specConditionContext(spec.getAConditions()));
                specInfo.put("ifConditions", specConditionContext(spec.getIfConditions()));
                specInfo.put("thenConditions", specConditionContext(spec.getThenConditions()));
                specsInfo.add(specInfo);
            }
            return objectMapper.writeValueAsString(specsInfo);
        } catch (Exception e) {
            log.error("Failed to build existing specs info", e);
            throw new IllegalStateException("Could not serialize existing specifications for recommendation", e);
        }
    }

    private List<Map<String, Object>> specConditionContext(List<SpecConditionDto> conditions) {
        return safeList(conditions).stream().map(condition -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", condition.getDeviceId());
            row.put("targetType", condition.getTargetType());
            row.put("key", condition.getKey());
            row.put("propertyScope", condition.getPropertyScope());
            // This is how the model sees the board's existing specifications. Withheld, it could not tell
            // that a spec already asks one of the two questions, so the prompt's requirement to supply the
            // field would be satisfied by recommending the *other* one as a new spec — a duplicate the user
            // has to reject. The prompt asks for it; the context has to show it.
            row.put("variableSource", condition.getVariableSource());
            row.put("relation", condition.getRelation());
            row.put("value", condition.getValue());
            return row;
        }).toList();
    }

    /**
     * 解析 AI 响应并验证设备属性
     */
    @SuppressWarnings("unchecked")
    private RecommendationResponseDto<SpecificationRecommendationDto> parseAiResponse(String aiResponse,
                                   List<DeviceInfoHelper.DeviceInfo> devices,
                                   BoardSemanticValidator.BoardContext semanticContext,
                                   int maxRecommendations,
                                   String language) {
        try {
            // 清理 AI 返回的内容，去除 Markdown 代码块标记
            String cleanedResponse = aiResponse.trim();
            if (cleanedResponse.startsWith("```")) {
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
                    // discard specifications that passed every capability and semantic check.
                    recommendation.remove("confidence");
                    recommendation.remove("category");

                    // 验证并过滤推荐
                    RecommendationValidation validation = validateRecommendation(
                            recommendation, deviceMap, semanticContext, language);
                    if (validation.valid()) {
                        recommendation.remove("templateLabel");
                        // Template-shape validation makes omitted inactive groups semantically empty.
                        recommendation.putIfAbsent("aConditions", List.of());
                        recommendation.putIfAbsent("ifConditions", List.of());
                        recommendation.putIfAbsent("thenConditions", List.of());
                        recommendations.add(recommendation);
                        count++;
                    } else {
                        log.debug("Filtered specification recommendation candidate {}: reasonCode={}",
                                inspected, validation.reasonCode());
                        filteredItems.add(filteredItem("specification", inspected, validation, recommendationLabel(recommendation)));
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse specification recommendation candidate {}: exception={}",
                            inspected, e.getClass().getName());
                    filteredItems.add(filteredItem("specification", inspected,
                            invalid("parseFailed", language), jsonText(rec.path("rationale"), "")));
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
            result.put("rawCandidateCount", rawCandidateCount);
            result.put("inspectedCount", inspected);
            result.put("truncatedCount", Math.max(0, rawCandidateCount - inspected));
            result.put("recommendations", recommendations);

            return resultParser.parseRecommendationResponse(result, SpecificationRecommendationDto.class, false, "AI specification recommendation");

        } catch (Exception e) {
            log.error("Failed to parse AI response: exception={}", e.getClass().getName());
            throw new RecommendationFailure(
                    recommendationMessage(language, "parseFailed", 0),
                    "AI_RESPONSE_INVALID", Map.of("phase", "response_parse"), e);
        }
    }

    /**
     * 验证推荐是否使用设备实际存在的属性
     */
    @SuppressWarnings("unchecked")
    private RecommendationValidation validateRecommendation(Map<String, Object> recommendation,
            Map<String, DeviceInfoHelper.DeviceInfo> deviceMap,
            BoardSemanticValidator.BoardContext semanticContext,
            String language) {

        if (!hasOnlyFields(recommendation, RECOMMENDATION_FIELDS)) {
            return invalid("unknownCandidateField", language);
        }
        String rationale = asTrimmedString(recommendation.get("rationale"));
        if (rationale == null) {
            return invalid("missingSpecificationRationale", language);
        }
        recommendation.put("rationale", rationale);
        String templateId = asTrimmedString(recommendation.get("templateId"));
        if (!ALLOWED_TEMPLATE_IDS.contains(templateId)) {
            return invalid("invalidTemplateId", language);
        }
        recommendation.put("templateId", templateId);

        if (!hasValidTemplateShape(templateId, recommendation)) {
            return invalid("invalidTemplateShape", language);
        }

        // 验证所有条件列表中的设备属性
        String[] conditionTypes = {"aConditions", "ifConditions", "thenConditions"};

        for (String conditionType : conditionTypes) {
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) recommendation.get(conditionType);
            if (conditions == null || conditions.isEmpty()) {
                continue;
            }

            for (Map<String, Object> condition : conditions) {
                if (condition.containsKey("side")) {
                    return invalid("conditionSideField", language);
                }
                if (!hasOnlyFields(condition, CONDITION_FIELDS)) {
                    return invalid("unknownCandidateField", language);
                }
                String deviceId = asTrimmedString(condition.get("deviceId"));
                String targetType = asTrimmedString(condition.get("targetType"));
                String key = asTrimmedString(condition.get("key"));
                String propertyScope = asTrimmedString(condition.get("propertyScope"));
                String variableSource = asTrimmedString(condition.get("variableSource"));
                String relation = asTrimmedString(condition.get("relation"));
                String value = asTrimmedString(condition.get("value"));

                if (deviceId == null || targetType == null || key == null) {
                    return invalid("conditionMissingFields", language);
                }
                String targetTypeLower = targetType.toLowerCase(Locale.ROOT);
                if (!"trust".equals(targetTypeLower) && !"privacy".equals(targetTypeLower)
                        && propertyScope != null) {
                    return invalid("invalidConditionCapability", language);
                }
                // Normalize the authored source here; manifest eligibility is checked below using the
                // captured semantic context, without relying on the advisory device projection.
                if ("variable".equals(targetTypeLower)) {
                    String normalizedSource = variableSource == null
                            ? null : variableSource.toLowerCase(Locale.ROOT);
                    if (normalizedSource == null
                            || (!"environment".equals(normalizedSource) && !"reported".equals(normalizedSource))) {
                        return invalid("conditionMissingVariableSource", language);
                    }
                    condition.put("variableSource", normalizedSource);
                } else if (variableSource != null) {
                    return invalid("invalidConditionCapability", language);
                }
                if (value == null && "api".equals(targetTypeLower)) {
                    value = "TRUE";
                }
                if (value == null) {
                    return invalid("conditionMissingValue", language);
                }
                String normalizedRelation = normalizeRelation(relation);
                if (relation != null && normalizedRelation == null) {
                    return invalid("invalidRelation", language);
                }

                DeviceInfoHelper.DeviceInfo device = resolveDevice(deviceId, deviceMap);
                if (device == null) {
                    return invalid("unknownDevice", language);
                }
                condition.put("deviceId", device.nodeId());
                condition.put("deviceLabel", device.label());

                if ("currentState".equalsIgnoreCase(key)) {
                    return invalid("currentStateKey", language);
                }

                String canonicalRelation = normalizedRelation != null ? normalizedRelation : "=";
                String actualKey = null;
                String canonicalValue = null;
                if ("state".equals(targetTypeLower) && isEnumRelation(canonicalRelation)) {
                    actualKey = "state";
                    canonicalValue = canonicalStateValues(device, canonicalRelation, value);
                } else if ("variable".equals(targetTypeLower)) {
                    DeviceInfoHelper.VariableInfo variable = findVariable(device, key);
                    if (variable != null) {
                        actualKey = variable.name();
                        canonicalValue = canonicalVariableValue(variable, canonicalRelation, value);
                    }
                } else if ("mode".equals(targetTypeLower) && isEnumRelation(canonicalRelation)) {
                    DeviceInfoHelper.ModeInfo mode = findMode(device, key);
                    if (mode != null) {
                        actualKey = mode.name();
                        canonicalValue = canonicalModeValues(mode, canonicalRelation, value);
                    }
                } else if ("api".equals(targetTypeLower) && isEnumRelation(canonicalRelation)) {
                    actualKey = canonicalApiName(device, key, true);
                    canonicalValue = canonicalBooleanValue(canonicalRelation, value);
                } else if ("trust".equals(targetTypeLower) || "privacy".equals(targetTypeLower)) {
                    propertyScope = canonicalPropertyScope(propertyScope);
                    actualKey = canonicalPropertyKey(device, propertyScope, key);
                    canonicalValue = canonicalTrustPrivacyValue(targetTypeLower, canonicalRelation, value);
                }

                if (actualKey == null || canonicalValue == null) {
                    return invalid("invalidConditionCapability", language);
                }
                condition.put("targetType", targetTypeLower);
                condition.put("key", actualKey);
                if ("trust".equals(targetTypeLower) || "privacy".equals(targetTypeLower)) {
                    condition.put("propertyScope", propertyScope);
                } else {
                    condition.remove("propertyScope");
                }
                condition.put("relation", canonicalRelation);
                condition.put("value", canonicalValue);
                if ("7".equals(templateId)
                        && !isValidUntrustedSourceSafetyCondition(
                        device, targetTypeLower, actualKey, canonicalRelation, canonicalValue)) {
                    return invalid("invalidUntrustedSourceSafetyCondition", language);
                }
            }
        }

        for (String conditionType : conditionTypes) {
            String side = conditionType.substring(0, conditionType.length() - "Conditions".length());
            List<SpecConditionDto> semanticConditions = semanticConditions(
                    (List<Map<String, Object>>) recommendation.get(conditionType), side);
            for (int index = 0; index < semanticConditions.size(); index++) {
                if (BoardSemanticValidator.validateSpecCondition(
                        semanticContext, semanticConditions.get(index), side, index) != null) {
                    return invalid("invalidConditionCapability", language);
                }
            }
            BoardSemanticValidator.GroupValidationIssue groupIssue =
                    BoardSemanticValidator.validateSpecConditionGroup(
                            semanticContext, semanticConditions, side);
            if (groupIssue != null) {
                return invalid("UNREACHABLE_CONDITION_GROUP".equals(groupIssue.reasonCode())
                        ? "unreachableConditionGroup" : "contradictoryConditionGroup", language);
            }
        }

        return RecommendationValidation.ok();
    }

    private List<SpecConditionDto> semanticConditions(List<Map<String, Object>> conditions, String side) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        List<SpecConditionDto> result = new ArrayList<>();
        for (Map<String, Object> condition : conditions) {
            SpecConditionDto dto = new SpecConditionDto();
            dto.setSide(side);
            dto.setDeviceId(asTrimmedString(condition.get("deviceId")));
            dto.setDeviceLabel(asTrimmedString(condition.get("deviceLabel")));
            dto.setTargetType(asTrimmedString(condition.get("targetType")));
            dto.setKey(asTrimmedString(condition.get("key")));
            dto.setPropertyScope(asTrimmedString(condition.get("propertyScope")));
            dto.setVariableSource(asTrimmedString(condition.get("variableSource")));
            dto.setRelation(asTrimmedString(condition.get("relation")));
            dto.setValue(asTrimmedString(condition.get("value")));
            result.add(dto);
        }
        return result;
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

    private boolean hasOnlyFields(Map<String, Object> value, Set<String> allowedFields) {
        return value != null && allowedFields.containsAll(value.keySet());
    }

    private String validationReason(String language, String reasonCode) {
        boolean zh = "zh-CN".equals(language);
        return switch (reasonCode) {
            case "invalidTemplateId" -> zh
                    ? "缺少合法 templateId；规约推荐必须使用 1 到 7 的模板编号。"
                    : "Missing a valid templateId; specification recommendations must use template 1 through 7.";
            case "missingSpecificationRationale" -> zh
                    ? "缺少面向用户的推荐依据，无法解释为什么建议检查这条规约。"
                    : "The user-facing rationale is missing, so the recommendation cannot explain why this property should be checked.";
            case "invalidTemplateShape" -> zh
                    ? "规约模板形状不匹配：该模板要求的条件区和推荐返回的条件区不一致。"
                    : "The specification template shape does not match the condition groups returned by the recommendation.";
            case "conditionMissingFields" -> zh
                    ? "规约条件缺少设备、目标类型或属性键。"
                    : "A specification condition is missing its device, target type, or key.";
            case "conditionMissingValue" -> zh
                    ? "规约条件缺少期望值。"
                    : "A specification condition is missing its expected value.";
            case "conditionMissingVariableSource" -> zh
                    ? "变量条件未说明检查的是家中实际值（environment）还是设备上报值（reported）。两者在设备被入侵时不同，没有默认值。"
                    : "A variable condition does not say whether it checks the actual value in the home "
                            + "(environment) or what the device reports (reported). The two differ when a "
                            + "device is compromised, so there is no default.";
            case "invalidRelation" -> zh
                    ? "规约条件使用了不支持的关系运算符。"
                    : "A specification condition uses an unsupported relation operator.";
            case "unknownDevice" -> zh
                    ? "规约条件引用了当前画布中不存在的设备实例。"
                    : "A specification condition references a device instance that is not on the board.";
            case "currentStateKey" -> zh
                    ? "规约条件把 currentState 当作属性键；应使用 targetType=state 且 key=state。"
                    : "A specification condition uses currentState as a key; use targetType=state with key=state instead.";
            case "invalidConditionCapability" -> zh
                    ? "规约条件引用的变量、模式、状态、API、信任/隐私键或取值不符合设备模板能力。"
                    : "A specification condition references a variable, mode, state, API, trust/privacy key, or value outside the device template capabilities.";
            case "invalidUntrustedSourceSafetyCondition" -> zh
                    ? "模板 7 只表示“不可信来源不得导致受保护事件”：不要直接写 trust/privacy；state/mode 必须为 =；API 必须为 = TRUE 且模板必须声明可建模的 EndState。普通“永不发生”或隐私泄露请使用模板 3。"
                    : "Template 7 means an untrusted source must not cause the protected event: do not use explicit trust/privacy predicates; state/mode require '='; and an API requires '= TRUE' plus a modeled EndState. Use template 3 for an unconditional prohibition or privacy leakage.";
            case "contradictoryConditionGroup" -> zh
                    ? "同一 A、IF 或 THEN 条件组在设备声明的合法状态或变量定义域中没有共同可满足的取值。"
                    : "One A, IF, or THEN condition group has no common satisfying value in the declared device state and variable domains.";
            case "unreachableConditionGroup" -> zh
                    ? "同一 A、IF 或 THEN 条件组虽然使用合法值，但从当前设备状态和值无法通过已声明行为到达。"
                    : "One A, IF, or THEN condition group uses legal values that are unreachable from the current device state and values under the declared behavior.";
            case "parseFailed" -> zh
                    ? "该候选项不是可解析的规约推荐对象。"
                    : "The candidate is not a parseable specification recommendation object.";
            case "unknownCandidateField" -> zh
                    ? "候选规约包含系统不认识的字段；为避免静默丢失其含义，整条候选已过滤。"
                    : "The specification candidate contains an unknown field; the whole candidate was filtered to avoid silently losing its meaning.";
            case "conditionSideField" -> zh
                    ? "候选条件填写了派生字段 side；条件所属的 A/IF/THEN 区域已由数组位置确定，后端不会静默覆盖冲突值。"
                    : "A candidate condition supplied the derived side field; its A/IF/THEN group is determined by the containing array, so the backend does not silently overwrite a conflicting value.";
            default -> zh
                    ? "该候选规约未通过后端能力校验。"
                    : "The candidate did not pass backend capability validation.";
        };
    }

    private String recommendationLabel(Map<String, Object> recommendation) {
        String rationale = asTrimmedString(recommendation.get("rationale"));
        if (rationale != null) return rationale;
        String templateLabel = asTrimmedString(recommendation.get("templateLabel"));
        return templateLabel != null ? templateLabel : asTrimmedString(recommendation.get("templateId"));
    }

    private String jsonText(JsonNode node, String fallback) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.asText("").trim() : fallback;
    }

    private record RecommendationValidation(boolean valid, String reasonCode, String reason) {
        private static RecommendationValidation ok() {
            return new RecommendationValidation(true, "", "");
        }

        private static RecommendationValidation reject(String reasonCode, String reason) {
            return new RecommendationValidation(false, reasonCode, reason);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasValidTemplateShape(String templateId, Map<String, Object> recommendation) {
        for (String group : List.of("aConditions", "ifConditions", "thenConditions")) {
            if (recommendation.containsKey(group) && !(recommendation.get(group) instanceof List<?>)) {
                return false;
            }
        }
        int aCount = conditionCount((List<Map<String, Object>>) recommendation.get("aConditions"));
        int ifCount = conditionCount((List<Map<String, Object>>) recommendation.get("ifConditions"));
        int thenCount = conditionCount((List<Map<String, Object>>) recommendation.get("thenConditions"));
        if ("1".equals(templateId) || "2".equals(templateId) || "3".equals(templateId) || "7".equals(templateId)) {
            return aCount > 0 && ifCount == 0 && thenCount == 0;
        }
        return aCount == 0 && ifCount > 0 && thenCount > 0;
    }

    private int conditionCount(List<Map<String, Object>> conditions) {
        return conditions == null ? 0 : conditions.size();
    }

    private boolean isValidUntrustedSourceSafetyCondition(DeviceInfoHelper.DeviceInfo device,
                                                           String targetType,
                                                           String key,
                                                           String relation,
                                                           String value) {
        if ("trust".equals(targetType) || "privacy".equals(targetType)) {
            return false;
        }
        if ("state".equals(targetType) || "mode".equals(targetType)) {
            return "=".equals(relation);
        }
        if (!"api".equals(targetType)) return true;
        if (!"=".equals(relation) || !"TRUE".equalsIgnoreCase(value) || device == null) return false;
        return safeList(device.apis()).stream()
                .anyMatch(api -> api != null && Boolean.TRUE.equals(api.signal())
                        && key != null && key.equals(api.name())
                        && api.endState() != null && !api.endState().isBlank());
    }

    private DeviceInfoHelper.DeviceInfo resolveDevice(String deviceId,
            Map<String, DeviceInfoHelper.DeviceInfo> deviceMap) {
        return deviceId == null ? null : deviceMap.get(deviceId);
    }

    private boolean isEnumRelation(String relation) {
        return "=".equals(relation) || "!=".equals(relation)
                || "in".equals(relation) || "not in".equals(relation);
    }

    private String canonicalApiName(DeviceInfoHelper.DeviceInfo device, String rawName, boolean signalOnly) {
        if (device.apis() == null || rawName == null) {
            return null;
        }
        String trimmed = rawName.trim();
        for (DeviceInfoHelper.ApiInfo api : device.apis()) {
            if (api.name() != null
                    && api.name().equalsIgnoreCase(trimmed)
                    && (!signalOnly || Boolean.TRUE.equals(api.signal()))) {
                return api.name();
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
                String value = canonicalEnumValue(variable.values(), candidate);
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
            String value = canonicalEnumValue(mode.values(), candidate);
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
        String workingState = canonicalEnumValue(device.workingStates(), trimmed);
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
            String value = canonicalEnumValue(device.modes().get(i).values(), part);
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
            String candidate = canonicalEnumValue(mode.values(), rawValue);
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

    private String canonicalPropertyScope(String rawScope) {
        if (rawScope == null) {
            return null;
        }
        String normalized = rawScope.trim().toLowerCase(Locale.ROOT);
        return "state".equals(normalized) || "variable".equals(normalized) ? normalized : null;
    }

    private String canonicalPropertyKey(DeviceInfoHelper.DeviceInfo device, String scope, String rawKey) {
        if (device == null || scope == null || rawKey == null) return null;
        if ("variable".equals(scope)) {
            DeviceInfoHelper.VariableInfo variable = findVariable(device, rawKey);
            return variable == null ? null : variable.name();
        }
        DeviceInfoHelper.ModeInfo mode = findMode(device, rawKey);
        return mode == null ? null : mode.name();
    }

    private String canonicalTrustPrivacyValue(String targetType, String relation, String rawValue) {
        if (!isEnumRelation(relation) || rawValue == null) {
            return null;
        }
        Set<String> allowed = "trust".equals(targetType)
                ? Set.of("trusted", "untrusted")
                : Set.of("public", "private");
        List<String> candidates = splitValueCandidates(rawValue, relation, false);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = candidate.trim().toLowerCase(Locale.ROOT);
            if (!allowed.contains(normalized)) {
                return null;
            }
            canonical.add(normalized);
        }
        return joinCanonicalValues(canonical, relation);
    }

    private String canonicalBooleanValue(String relation, String rawValue) {
        if (!isEnumRelation(relation) || rawValue == null) {
            return null;
        }
        List<String> candidates = splitValueCandidates(rawValue, relation, false);
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> canonical = new ArrayList<>();
        for (String candidate : candidates) {
            String normalized = candidate.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("TRUE", "FALSE").contains(normalized)) {
                return null;
            }
            canonical.add(normalized);
        }
        return joinCanonicalValues(canonical, relation);
    }

    private String canonicalEnumValue(List<String> allowedValues, String rawValue) {
        if (allowedValues == null || rawValue == null) {
            return null;
        }
        String normalized = cleanEnumLiteral(rawValue);
        for (String allowed : allowedValues) {
            if (allowed != null && cleanEnumLiteral(allowed).equalsIgnoreCase(normalized)) {
                return allowed;
            }
        }
        return null;
    }

    private String cleanEnumLiteral(String value) {
        return value == null ? "" : value.trim().replace(" ", "");
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
    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

}
