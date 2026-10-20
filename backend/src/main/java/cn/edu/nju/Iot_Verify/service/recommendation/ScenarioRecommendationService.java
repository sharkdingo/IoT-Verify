package cn.edu.nju.Iot_Verify.service.recommendation;

import cn.edu.nju.Iot_Verify.component.ai.PromptCompletionService;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.AiScenarioDraftStore;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.ScenarioObjectiveTargets;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.ScenarioVerificationReadiness;
import cn.edu.nju.Iot_Verify.dto.recommendation.ScenarioRecommendationRequestDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.ScenarioRecommendationResponseDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.component.aitool.BoardSemanticValidator;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationAdjustmentItem;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationCapabilityView;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationFilterItem;
import cn.edu.nju.Iot_Verify.component.aitool.spec.SpecificationTemplateSemantics;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneFormat;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.component.template.ManifestConditionSemantics;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.EnvironmentDomainUtils;
import cn.edu.nju.Iot_Verify.util.RecommendationInputs;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scenario-level recommendation and chat-draft lifecycle.
 *
 * <p>Unlike standalone device/rule/spec recommendations, scenario generation asks the LLM for one
 * coupled board-scene draft. The backend then validates every cross-reference inside the same scene:
 * rules and specifications may only refer to device instances that exist in the returned devices list,
 * and every capability must come from the referenced template. This keeps scenario recommendation from
 * becoming three unrelated suggestions glued together in the UI.</p>
 */
@Slf4j
@Service
public class ScenarioRecommendationService {

    private static final String SCENE_SCHEMA = PortableSceneFormat.SCHEMA;
    private static final int SCENE_VERSION = PortableSceneFormat.VERSION;
    private static final int DEFAULT_NODE_WIDTH = 176;
    private static final int DEFAULT_NODE_HEIGHT = 128;
    private static final double TEMPERATURE = 0.7;
    private static final int MAX_TOKENS = 6000;
    private static final Set<String> SPEC_TEMPLATE_IDS = Set.of("1", "2", "3", "4", "5", "6", "7");
    private static final Set<String> SPEC_TARGET_TYPES = Set.of("state", "mode", "variable", "api", "trust", "privacy");
    private static final Set<String> RULE_TARGET_TYPES = Set.of("api", "variable", "mode", "state");

    private static final String SYSTEM_PROMPT = ("""
你是智能家居 IoT 形式化验证场景设计助手。你的任务不是分别推荐设备、规则、规约，而是一次设计一个自包含的全量场景草案。
草案只需满足结构、引用和设备能力约束；不要声称它安全、完整、已验证或已应用。

场景必须把用户痛点、设备实例、共享环境变量、自动化规则和形式化规约串成闭环：
- 设备实例必须来自可用模板。
- 环境变量必须使用用户可理解的真实变量名，不能把 a_、trust_、privacy_、variable_ 当作保留前缀。
- environmentVariables 必须列出保留设备模板声明或影响的每个共享变量，并显式给出非空 value、trust、privacy。
- trust 是 MEDIC 控制来源标签：多条件规则只要至少一个实际触发来源可信，目标仍可信；只有全部来源不可信才失去可信控制。不要把它解释成认证或通用数据完整性。
- 规则和规约必须引用同一个 devices 列表里的设备实例 id。
- 每条规则的触发条件必须可同时满足，并与目标 API 的非空 StartState 兼容；每个规约的 A、IF、THEN 条件数组也必须各自可同时满足。
- 条件组除了可同时满足，还必须能从设备当前状态与变量值出发，经 capabilities 的 Transitions 与已声明 API 到达；命令 API 的 StartState 同样必须可达。只有"设备本地（deviceLocal=true）、当前值已知、且 NaturalChangeRate 为 0"的变量才会被判不可达；会自然变化的变量与共享环境变量都不受此限制，不要因当前 Environment Pool 的读数而回避（例如烟雾当前读数为 clear 时，仍应写出"检测到烟雾就报警"）。
- 规则只能调用模板里真实存在的 API；规则触发条件只能使用 signal API、变量、模式或 state。
- 规则 sources 有两种互斥结构：itemType=api 时 fromApi 必须是 Signal=true 的可观察 API，并且必须省略 relation 和 value；itemType=variable|mode|state 时必须给出 relation 和 value。规则 API 事件源请直接省略 relation/value；写成等价的 "=" + "TRUE" 会被规范化并记入 adjustedItems，但只写一半、写 FALSE 或用其他关系符会让整条规则被丢弃。
- 规约条件只能使用 state、mode、variable、api、trust、privacy。state 条件的 key 用 state，取值用完整 WorkingStates；mode 条件的 key 用模板 Modes 中的真实模式名，取值用 capabilities.modeValues 中该模式的允许值。只有 trust/privacy 条件填写 propertyScope=state|variable，其 state 范围的 key 是模式名，variable 范围的 key 是变量名。只有 variable 条件填写 variableSource=environment|reported，二者在设备被攻陷时含义不同，不得省略。其他条件必须省略不适用的 propertyScope/variableSource。
- 条件的 relation 只能使用 =、!=、<、<=、>、>=、in、not in；state/mode/api/trust/privacy 和枚举变量只能用等值或选值比较，数值变量可用数值比较。非 API 条件必须给出 relation/value，字段名与取值必须符合该设备 capabilities。
- devices[].variables/privacies 只允许模板中 IsInside=true 的本地变量；共享环境量必须放在 environmentVariables。无法确定初始值或标签时应省略，不得猜测模板范围外的值。
- devices[].id 只是本次回答内供规则/规约关联设备的临时别名，后端会统一改写；不要把它当作用户名称或永久技术 ID。
- 只有同时声明 Modes 和 WorkingStates 的模板才填写 state/currentStateTrust/currentStatePrivacy。无状态机模板必须省略这三个字段，用变量表达读数。
- currentStateTrust/currentStatePrivacy 以及变量 trust/privacy 都是实例级高级覆盖；需要模板默认值时必须省略，不能复制模板默认标签。
- 推荐目标是构造一个可验证的场景，最好包含能暴露安全、响应、一致性或隐私问题的规则/规约组合。

请只返回 JSON，不要返回 Markdown。

返回格式：
{
  "scenarioName": "短名称",
  "rationale": "为什么这个场景能支撑验证",
  "scene": {
    "schema": "iot-verify.board-scene",
    "version": 5,
    "devices": [
      {
        "id": "response_local_alias",
        "templateName": "必须来自模板列表",
        "label": "用户可读名称",
        "position": {"x": 0, "y": 0},
        "state": "仅有状态机模板填写；必须是该模板 WorkingStates 中的某个状态（大小写与首尾空格会被规范化）",
        "currentStateTrust": "可选，trusted|untrusted；表示初始状态触发自动化时的控制来源标签，不是认证、通用完整性或概率",
        "currentStatePrivacy": "可选，public|private；表示初始状态的数据敏感性标签，不是访问控制",
        "variables": [
          {"name": "IsInside=true 的本地变量名", "value": "模板声明域内的初始值", "trust": "trusted|untrusted"}
        ],
        "privacies": [
          {"name": "IsInside=true 的本地变量名", "privacy": "public|private"}
        ],
        "width": 176,
        "height": 128
      }
    ],
    "environmentVariables": [
      {"name": "真实环境变量名", "value": "初始值", "trust": "trusted|untrusted", "privacy": "public|private"}
    ],
    "rules": [
      {
        "name": "规则说明",
        "sources": [
          {"fromId": "devices[].id", "fromApi": "Signal=true 的真实 API 名", "itemType": "api"},
          {"fromId": "devices[].id", "fromApi": "变量/模式名/state", "itemType": "variable|mode|state", "relation": "=", "value": "值"}
        ],
        "toId": "devices[].id",
        "toApi": "目标设备真实 API 名",
        "contentDevice": null,
        "content": null
      }
    ],
    "specs": [
      {
        "templateId": "1|2|3|4|5|6|7",
        "aConditions": [],
        "ifConditions": [],
        "thenConditions": []
      }
    ]
  }
}

规约模板语义（必须按公式选择，不能只匹配 JSON 形状）：
%s
附加约束：
- contentDevice 与 content 必须同时为 null，或同时填写。content 必须来自对应设备模板的 Contents，且目标 API 必须声明 AcceptsContent=true；仅在动作携带该内容且需要分析敏感性标签传播时使用。该标签不表示系统复制了真实数据或实施了访问控制。
- templateId 3 也用于隐私泄露：把公开动作/状态与对应 privacy=private 一起放进 aConditions。
- templateId 7 的 aConditions 不得直接使用 trust/privacy；state/mode 必须使用 =；api 必须使用 = TRUE。
- 每个 condition: {deviceId, targetType, key, propertyScope?, variableSource?, relation?, value?}。propertyScope 仅 trust/privacy 必填；variableSource 仅 targetType=variable 必填，取 environment（家中实际值，要求该变量是共享声明且该设备读取它，即 capabilities 中 deviceLocal=false 且 reads=true）或 reported（该设备上报值）；reads=false 的共享变量只影响、不观测，不能作 variable 条件，但仍可引用其 trust/privacy 标签。非 API 条件必须给出 relation/value，API 条件可省略二者并按 = TRUE 处理；不要输出内部 id、side、deviceLabel、templateLabel、formula、Mode_state 生成键或 devices 缓存。
""").formatted(SpecificationTemplateSemantics.chinesePromptReference());

    private final PromptCompletionService promptCompletionService;
    private final BoardStorageService boardStorageService;
    private final AiScenarioDraftStore draftStore;
    private final ChatExecutionConfig chatExecutionConfig;

    private final ObjectMapper objectMapper;
    private final RecommendationResultParser resultParser;

    public ScenarioRecommendationService(PromptCompletionService promptCompletionService,
                                         BoardStorageService boardStorageService,
                                         AiScenarioDraftStore draftStore,
                                         ChatExecutionConfig chatExecutionConfig,
                                         ObjectMapper objectMapper,
                                         RecommendationResultParser resultParser) {
        this.promptCompletionService = promptCompletionService;
        this.boardStorageService = boardStorageService;
        this.draftStore = draftStore;
        this.chatExecutionConfig = chatExecutionConfig;
        this.objectMapper = objectMapper;
        this.resultParser = resultParser;
    }

    public ScenarioRecommendationResponseDto recommend(Long userId, ScenarioRecommendationRequestDto request) {
        return recommend(userId, request, null);
    }

    public ScenarioRecommendationResponseDto recommend(Long userId, ScenarioRecommendationRequestDto request,
                                                        String chatSessionId) {
        ScenarioObjectiveTargets objectiveTargets = new ScenarioObjectiveTargets(
                requireCount(request.getMinDevices(), "minDevices"),
                requireCount(request.getMinRules(), "minRules"),
                requireCount(request.getMinSpecs(), "minSpecs"));
        int maxDevices = requireCount(request.getMaxDevices(), "maxDevices");
        int maxRules = requireCount(request.getMaxRules(), "maxRules");
        int maxSpecs = requireCount(request.getMaxSpecs(), "maxSpecs");
        requireOrderedRange("devices", objectiveTargets.minDevices(), maxDevices);
        requireOrderedRange("rules", objectiveTargets.minRules(), maxRules);
        requireOrderedRange("specs", objectiveTargets.minSpecs(), maxSpecs);
        String language = RecommendationInputs.language(request.getLanguage());
        String userRequirement = RecommendationInputs.userRequirement(request.getUserRequirement());
        BoardSemanticSnapshotDto board = boardStorageService.getSemanticSnapshot(userId);
        List<DeviceTemplateDto> templates = board.deviceTemplates();
        if (request.getTemplateNames() != null) {
            List<String> names = request.getTemplateNames();
            if (names.isEmpty() || names.size() > 100) {
                throw new BadRequestException("templateNames must contain 1 to 100 template names.");
            }
            Set<String> distinct = new HashSet<>();
            Map<String, DeviceTemplateDto> available = RecommendationCapabilityView.indexTemplates(templates);
            Set<String> selected = new HashSet<>();
            for (String name : names) {
                if (name == null || name.isBlank() || name.length() > 100 || !distinct.add(name)) {
                    throw new BadRequestException("templateNames must contain distinct, non-empty strings of at most 100 characters.");
                }
                DeviceTemplateDto template = RecommendationCapabilityView.resolveTemplate(available, name);
                if (template == null) {
                    throw new ValidationException("templateNames", "Some selected device templates are unavailable. Choose names from the current template catalog.");
                }
                selected.add(template.getName());
            }
            // The same complete capability set owns both model input and candidate admission.
            templates = templates.stream().filter(template -> selected.contains(template.getName())).toList();
        }
        Map<String, Object> result;
        if (templates.isEmpty()) {
            result = new LinkedHashMap<>();
            result.put("message", message(language, "noTemplates", 0, 0, 0));
            result.put("count", 0);
            result.put("requestedCount", objectiveTargets.requestedCount());
            result.put("validatedCount", 0);
            result.put("filteredCount", 0);
            result.put("filteredItems", Collections.emptyList());
            result.put("adjustedCount", 0);
            result.put("adjustedItems", Collections.emptyList());
            result.put("rawCandidateCount", 0);
            result.put("inspectedCount", 0);
            result.put("truncatedCount", 0);
            result.put("scenarioName", "");
            Map<String, Object> scene = emptyScene();
            result.put("scene", scene);
            ScenarioVerificationReadiness.Status analysis = putScenarioAnalysis(
                    result, objectMapper.valueToTree(scene), 0, language, objectiveTargets);
            result.put("rationale", validatedRationale(language, 0, 0, 0, analysis.semanticWarnings().size()));
        } else {
            String prompt;
            try {
                prompt = buildPrompt(board, templates, objectiveTargets,
                        maxDevices, maxRules, maxSpecs, language, userRequirement);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not serialize the scenario recommendation context", e);
            }
            String aiResponse = promptCompletionService.completeRecommendation(
                    SYSTEM_PROMPT, prompt, TEMPERATURE, MAX_TOKENS);
            try {
                result = parseAndValidate(aiResponse, templates, language, objectiveTargets,
                        maxDevices, maxRules, maxSpecs);
            } catch (Exception e) {
                log.warn("recommend_scenario received an unusable AI response: exception={}", e.getClass().getName());
                String message = "zh-CN".equals(language)
                        ? "AI 返回的内容不是可解析的场景草案 JSON，请重试。"
                        : "The AI response was not a parseable scene-draft JSON object. Please try again.";
                boolean previousDraftRetained = chatSessionId != null && !chatSessionId.isBlank()
                        && draftStore.latestDraft(userId, chatSessionId).isPresent();
                throw new RecommendationFailure(message, "AI_RESPONSE_INVALID", Map.of(
                        "phase", "response_parse", "draftStored", false,
                        "previousDraftRetained", previousDraftRetained), e);
            }
        }
        ScenarioRecommendationResponseDto response = resultParser.parseScenarioRecommendationResponse(result, request);
        return prepareDeliverableDraftResult(userId, chatSessionId, language, response);
    }

    private int requireCount(Integer count, String field) {
        if (count == null) throw new BadRequestException(field + " is required.");
        if (count < ScenarioObjectiveTargets.MIN_COUNT || count > ScenarioObjectiveTargets.MAX_COUNT) {
            throw new BadRequestException(field + " must be between 1 and 10.");
        }
        return count;
    }

    private void requireOrderedRange(String itemType, int minimum, int maximum) {
        if (minimum > maximum) {
            throw new BadRequestException("Minimum " + itemType + " must not exceed maximum " + itemType + ".");
        }
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private String buildPrompt(BoardSemanticSnapshotDto board,
                               List<DeviceTemplateDto> templates,
                               ScenarioObjectiveTargets objectiveTargets,
                               int maxDevices,
                               int maxRules,
                               int maxSpecs,
                               String language,
                               String userRequirement) throws JsonProcessingException {
        Map<String, Object> currentBoard = new LinkedHashMap<>();
        currentBoard.put("devices", board.nodes());
        currentBoard.put("environmentVariables", board.environmentVariables());
        currentBoard.put("rules", board.rules());
        currentBoard.put("specs", board.specifications());

        List<Map<String, Object>> templateContext = new ArrayList<>();
        for (DeviceTemplateDto template : templates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", templateName(template));
            row.put("description", template.getManifest() == null ? "" : template.getManifest().getDescription());
            row.put("capabilities", compactManifest(template.getManifest()));
            templateContext.add(row);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("请生成一个自包含的 IoT-Verify 全量场景草案 JSON。它尚未形式化验证，也不会自动应用。\n\n");
        prompt.append("## 当前画布（可保留并扩展；如果用户需求明显要求新场景，也可以提出全量替代草案）\n");
        prompt.append(objectMapper.writeValueAsString(currentBoard));
        prompt.append("\n\n## 可用设备模板（只能使用这些模板）\n");
        prompt.append(objectMapper.writeValueAsString(templateContext));
        prompt.append("\n\n## 场景要求\n");
        prompt.append("- 设备数量: 至少 ").append(objectiveTargets.minDevices())
                .append(" 个，最多 ").append(maxDevices).append(" 个\n");
        prompt.append("- 规则数量: 至少 ").append(objectiveTargets.minRules())
                .append(" 条，最多 ").append(maxRules).append(" 条\n");
        prompt.append("- 规约数量: 至少 ").append(objectiveTargets.minSpecs())
                .append(" 条，最多 ").append(maxSpecs).append(" 条\n");
        prompt.append("- 上述最小数量是明确完成目标；若最终校验后低于任一最小值，结果会标记为 PARTIAL。\n");
        prompt.append(languageInstruction(language)).append("\n");
        if (!userRequirement.isBlank()) {
            prompt.append("- 用户需求/痛点: ").append(userRequirement).append("\n");
        } else {
            prompt.append("- 用户需求/痛点: 构造一个包含多设备联动、共享环境变量、自动化冲突和可验证规约的复杂智能家居场景。\n");
        }
        prompt.append("- 返回的是供用户审阅的全量替换 scene 草案，不是局部追加推荐。rules/specs 必须引用 scene.devices 中存在的 id。\n");
        prompt.append("- 不要把 a_、trust_、privacy_、variable_ 当前缀约定；它们如果出现在变量名里就是普通业务名字。\n");
        prompt.append("\n请只返回 JSON。");
        return prompt.toString();
    }

    private Map<String, Object> compactManifest(DeviceTemplateDto.DeviceManifest manifest) {
        return RecommendationCapabilityView.fromManifest(manifest);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAndValidate(String aiResponse,
                                                 List<DeviceTemplateDto> templates,
                                                 String language,
                                                 ScenarioObjectiveTargets objectiveTargets,
                                                 int maxDevices,
                                                 int maxRules,
                                                 int maxSpecs) throws Exception {
        String cleaned = stripCodeFence(aiResponse);
        JsonNode root = objectMapper.readTree(cleaned.isBlank() ? "{}" : cleaned);
        JsonNode sceneNode = root.has("scene") ? root.path("scene") : root;
        if (!sceneNode.isObject()
                || !sceneNode.path("devices").isArray()
                || !sceneNode.path("rules").isArray()
                || !(sceneNode.path("specs").isArray() || sceneNode.path("specifications").isArray())) {
            throw new IllegalArgumentException(
                    "AI scene response must contain devices, rules, and specs arrays");
        }

        TemplateCatalog catalog = new TemplateCatalog(templates);
        JsonNode specsNode = sceneNode.path("specs").isMissingNode()
                ? sceneNode.path("specifications")
                : sceneNode.path("specs");
        List<Map<String, Object>> filteredItems = new ArrayList<>();
        List<Map<String, Object>> adjustedItems = new ArrayList<>();
        CandidateStats stats = new CandidateStats();
        Map<String, String> deviceReferenceMap = new LinkedHashMap<>();
        List<Map<String, Object>> devices = normalizeDevices(
                sceneNode.path("devices"), catalog, maxDevices,
                filteredItems, adjustedItems, language, stats, deviceReferenceMap);
        Map<String, DeviceContext> deviceById = buildDeviceContext(devices, catalog, deviceReferenceMap);
        List<Map<String, Object>> environmentVariables = normalizeEnvironmentVariables(
                sceneNode.path("environmentVariables"), devices, catalog,
                filteredItems, adjustedItems, language, stats);
        BoardSemanticValidator.BoardContext semanticContext = scenarioSemanticContext(
                deviceById, devices, environmentVariables);
        List<Map<String, Object>> rules = normalizeRules(
                sceneNode.path("rules"), deviceById, maxRules,
                filteredItems, adjustedItems, semanticContext, language, stats);
        List<Map<String, Object>> specs = normalizeSpecs(
                specsNode, deviceById, maxSpecs, filteredItems, semanticContext, language, stats);

        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("schema", SCENE_SCHEMA);
        scene.put("version", SCENE_VERSION);
        scene.put("templates", templateSnapshots(devices, catalog));
        scene.put("devices", devices);
        scene.put("environmentVariables", environmentVariables);
        scene.put("rules", rules);
        scene.put("specs", specs);

        int count = devices.size() + environmentVariables.size() + rules.size() + specs.size();
        Map<String, Object> result = new LinkedHashMap<>();
        String messageKey = devices.isEmpty()
                ? (stats.rawCandidateCount == 0 ? "noCandidates" : "empty")
                : "found";
        result.put("message", message(language, messageKey, devices.size(), rules.size(), specs.size()));
        result.put("count", count);
        result.put("requestedCount", objectiveTargets.requestedCount());
        result.put("validatedCount", stats.validatedCount);
        result.put("filteredCount", filteredItems.size());
        result.put("filteredItems", filteredItems);
        result.put("adjustedCount", adjustedItems.size());
        result.put("adjustedItems", adjustedItems);
        result.put("rawCandidateCount", stats.rawCandidateCount);
        result.put("inspectedCount", stats.inspectedCount);
        result.put("truncatedCount", Math.max(0, stats.rawCandidateCount - stats.inspectedCount));
        result.put("scenarioName", text(root.path("scenarioName"), language.equals("zh-CN") ? "AI 推荐场景" : "AI Scenario"));
        result.put("scene", scene);
        ScenarioVerificationReadiness.Status analysis = putScenarioAnalysis(
                result, objectMapper.valueToTree(scene), filteredItems.size(), language, objectiveTargets);
        result.put("rationale", validatedRationale(
                language, devices.size(), rules.size(), specs.size(), analysis.semanticWarnings().size()));
        return result;
    }

    private ScenarioVerificationReadiness.Status putScenarioAnalysis(
            Map<String, Object> result,
            JsonNode scene,
            int filteredCount,
            String language,
            ScenarioObjectiveTargets objectiveTargets) {
        ScenarioVerificationReadiness.Status analysis =
                ScenarioVerificationReadiness.assess(scene, filteredCount, language, objectiveTargets);
        result.put("objectiveTargets", objectiveTargets);
        result.put("objectiveStatus", analysis.objectiveStatus());
        result.put("objectiveIssues", analysis.objectiveIssues());
        result.put("verificationReady", analysis.verificationReady());
        result.put("readinessIssues", analysis.readinessIssues());
        result.put("semanticWarnings", analysis.semanticWarnings());
        return analysis;
    }

    private List<Map<String, Object>> normalizeDevices(JsonNode devicesNode,
                                                       TemplateCatalog catalog,
                                                       int maxDevices,
                                                       List<Map<String, Object>> filteredItems,
                                                       List<Map<String, Object>> adjustedItems,
                                                       String language,
                                                       CandidateStats stats,
                                                       Map<String, String> deviceReferenceMap) {
        List<Map<String, Object>> devices = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();
        Set<String> usedLabels = new HashSet<>();
        Map<String, EnvironmentDomainSource> activeEnvironmentDomains = new LinkedHashMap<>();
        if (!devicesNode.isArray()) {
            return devices;
        }
        stats.rawCandidateCount += devicesNode.size();
        int index = 0;
        for (JsonNode row : devicesNode) {
            if (devices.size() >= maxDevices) break;
            stats.inspectedCount++;
            index++;
            String rawTemplateName = text(row.path("templateName"), text(row.path("template"), text(row.path("type"), "")));
            DeviceTemplateDto template = catalog.resolve(rawTemplateName);
            if (template == null) {
                filteredItems.add(filteredItem("device", index, "unknownTemplate", language,
                        text(row.path("label"), rawTemplateName)));
                continue;
            }
            String templateName = templateName(template);
            String candidateRef = text(row.path("id"), "");
            if (candidateRef.isBlank() || candidateRef.length() > 100) {
                filteredItems.add(filteredItem("device", index, "invalidDeviceId", language,
                        text(row.path("label"), templateName)));
                continue;
            }
            if (deviceReferenceMap.containsKey(candidateRef)) {
                filteredItems.add(filteredItem("device", index, "duplicateDeviceId", language,
                        text(row.path("label"), candidateRef)));
                continue;
            }
            String id = nextPortableDeviceId(usedIds);
            String label = text(row.path("label"), "");
            String labelKey = label.toLowerCase(Locale.ROOT);
            if (label.isBlank() || label.length() > 255 || !usedLabels.add(labelKey)) {
                usedIds.remove(id);
                filteredItems.add(filteredItem("device", index, "invalidDeviceLabel", language,
                        label.isBlank() ? candidateRef : label));
                continue;
            }
            if (!hasValidOptionalLayout(row)) {
                usedIds.remove(id);
                usedLabels.remove(labelKey);
                filteredItems.add(filteredItem("device", index, "invalidDeviceLayout", language,
                        label));
                continue;
            }
            JsonNode stateNode = row.path("state");
            String state = canonicalState(template, text(stateNode, ""));
            if (isPresentNode(stateNode) && state == null) {
                usedIds.remove(id);
                usedLabels.remove(labelKey);
                filteredItems.add(filteredItem("device", index, "invalidDeviceRuntime", language,
                        label));
                continue;
            }
            JsonNode trustNode = row.path("currentStateTrust");
            String trust = normalizeTrust(text(trustNode, ""));
            if (isPresentNode(trustNode) && trust == null) {
                usedIds.remove(id);
                usedLabels.remove(labelKey);
                filteredItems.add(filteredItem("device", index, "invalidDeviceRuntime", language,
                        label));
                continue;
            }
            JsonNode statePrivacyNode = row.path("currentStatePrivacy");
            String statePrivacy = normalizePrivacy(text(statePrivacyNode, ""));
            if (isPresentNode(statePrivacyNode) && statePrivacy == null) {
                usedIds.remove(id);
                usedLabels.remove(labelKey);
                filteredItems.add(filteredItem("device", index, "invalidDeviceRuntime", language,
                        label));
                continue;
            }
            if (!hasStateMachine(template)
                    && (isPresentNode(stateNode)
                    || isPresentNode(trustNode)
                    || isPresentNode(statePrivacyNode))) {
                usedIds.remove(id);
                usedLabels.remove(labelKey);
                filteredItems.add(filteredItem("device", index, "invalidDeviceRuntime", language,
                        label));
                continue;
            }
            Map<String, Object> appliedDefaults = new LinkedHashMap<>();
            List<Map<String, Object>> variables = normalizeDeviceVariables(
                    template, row.path("variables"), appliedDefaults);
            List<Map<String, Object>> privacies = normalizeDevicePrivacies(
                    template, row.path("privacies"), appliedDefaults);
            if (variables == null || privacies == null) {
                usedIds.remove(id);
                usedLabels.remove(labelKey);
                filteredItems.add(filteredItem("device", index, "invalidDeviceRuntime", language,
                        label));
                continue;
            }
            String environmentMismatch = environmentDomainMismatch(template, activeEnvironmentDomains);
            if (environmentMismatch != null) {
                usedIds.remove(id);
                usedLabels.remove(labelKey);
                filteredItems.add(filteredItem("device", index, "environmentDomainConflict", language,
                        label + ": " + environmentMismatch));
                continue;
            }
            registerEnvironmentDomains(template, label, activeEnvironmentDomains);
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("id", id);
            device.put("templateName", templateName);
            device.put("label", label);
            Map<String, Object> position = normalizePosition(row.path("position"), devices.size());
            device.put("position", position);
            boolean stateful = hasStateMachine(template);
            String effectiveState = stateful ? (state == null ? defaultState(template) : state) : null;
            if (stateful) {
                device.put("state", effectiveState);
            }
            int width = intOrDefault(row.path("width"), DEFAULT_NODE_WIDTH,
                    DeviceLayoutDto.MIN_WIDTH, DeviceLayoutDto.MAX_WIDTH);
            int height = intOrDefault(row.path("height"), DEFAULT_NODE_HEIGHT,
                    DeviceLayoutDto.MIN_HEIGHT, DeviceLayoutDto.MAX_HEIGHT);
            device.put("width", width);
            device.put("height", height);
            if (stateful && !isPresentNode(stateNode)) appliedDefaults.put("state", effectiveState);
            if (!isPresentNode(row.path("position"))) {
                appliedDefaults.put("position", position);
            } else {
                if (!isPresentNode(row.path("position").path("x"))) {
                    appliedDefaults.put("position.x", position.get("x"));
                }
                if (!isPresentNode(row.path("position").path("y"))) {
                    appliedDefaults.put("position.y", position.get("y"));
                }
            }
            if (!isPresentNode(row.path("width"))) appliedDefaults.put("width", width);
            if (!isPresentNode(row.path("height"))) appliedDefaults.put("height", height);
            if (stateful) {
                if (trust != null) device.put("currentStateTrust", trust);
                if (statePrivacy != null) device.put("currentStatePrivacy", statePrivacy);
            }
            if (!variables.isEmpty()) {
                device.put("variables", variables);
            }
            if (!privacies.isEmpty()) {
                device.put("privacies", privacies);
            }
            devices.add(device);
            deviceReferenceMap.put(candidateRef, id);
            stats.validatedCount++;
            if (!appliedDefaults.isEmpty()) {
                adjustedItems.add(adjustedItem("device", index, "deviceDefaultsApplied",
                        language, label, appliedDefaults));
            }
        }
        return devices;
    }

    private String nextPortableDeviceId(Set<String> usedIds) {
        int suffix = usedIds.size() + 1;
        String id;
        do {
            id = "device_" + suffix++;
        } while (!usedIds.add(id));
        return id;
    }

    private List<Map<String, Object>> normalizeDeviceVariables(DeviceTemplateDto template,
                                                               JsonNode node,
                                                               Map<String, Object> appliedDefaults) {
        if (isPresentNode(node) && !node.isArray()) return null;
        Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> localVariables = localVariableMap(template);
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (node.isArray()) {
            for (JsonNode row : node) {
                if (!row.isObject()) return null;
                String requestedName = text(row.path("name"), "");
                DeviceTemplateDto.DeviceManifest.InternalVariable variable =
                        localVariables.get(requestedName.toLowerCase(Locale.ROOT));
                if (variable == null || !seen.add(variable.getName().toLowerCase(Locale.ROOT))) return null;
                String value = canonicalVariableValue(variable, "=", text(row.path("value"), ""));
                if (value == null) return null;
                String trust = normalizeTrust(text(row.path("trust"), ""));
                if (isPresentNode(row.path("trust")) && trust == null) return null;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", variable.getName());
                item.put("value", value);
                if (trust != null) item.put("trust", trust);
                normalized.add(item);
            }
        }
        for (DeviceTemplateDto.DeviceManifest.InternalVariable variable : localVariables.values()) {
            String key = variable.getName().toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            // A recommended device starts in its template's InitState, so a local variable must start at the
            // value that state declares; the pool rule below is for shared values only.
            DeviceTemplateDto.DeviceManifest localManifest = template.getManifest();
            String derived = DeviceManifestModes.localInitialValue(
                    localManifest, variable, localManifest != null ? localManifest.getInitState() : null);
            String value = derived != null ? derived : "";
            if (value.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", variable.getName());
            item.put("value", value);
            normalized.add(item);
            appliedDefaults.put("variables." + variable.getName() + ".value", value);
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeDevicePrivacies(DeviceTemplateDto template,
                                                               JsonNode node,
                                                               Map<String, Object> appliedDefaults) {
        if (isPresentNode(node) && !node.isArray()) return null;
        Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> localVariables = localVariableMap(template);
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (node.isArray()) {
            for (JsonNode row : node) {
                if (!row.isObject()) return null;
                String requestedName = text(row.path("name"), "");
                DeviceTemplateDto.DeviceManifest.InternalVariable variable =
                        localVariables.get(requestedName.toLowerCase(Locale.ROOT));
                if (variable == null || !seen.add(variable.getName().toLowerCase(Locale.ROOT))) return null;
                JsonNode privacyNode = row.path("privacy");
                String privacy = normalizePrivacy(text(privacyNode, ""));
                if (isPresentNode(privacyNode) && privacy == null) return null;
                if (privacy != null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", variable.getName());
                    item.put("privacy", privacy);
                    normalized.add(item);
                }
            }
        }
        return normalized;
    }

    private Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> localVariableMap(DeviceTemplateDto template) {
        Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> variables = new LinkedHashMap<>();
        if (template == null || template.getManifest() == null) return variables;
        for (DeviceTemplateDto.DeviceManifest.InternalVariable variable :
                safeList(template.getManifest().getInternalVariables())) {
            if (variable.getName() != null && Boolean.TRUE.equals(variable.getIsInside())) {
                variables.put(variable.getName().toLowerCase(Locale.ROOT), variable);
            }
        }
        return variables;
    }

    private boolean hasValidOptionalLayout(JsonNode row) {
        JsonNode position = row.path("position");
        if (isPresentNode(position)) {
            if (!position.isObject()) return false;
            if (!isOptionalCoordinate(position.path("x")) || !isOptionalCoordinate(position.path("y"))) {
                return false;
            }
        }
        return isOptionalIntegerInRange(row.path("width"),
                DeviceLayoutDto.MIN_WIDTH, DeviceLayoutDto.MAX_WIDTH)
                && isOptionalIntegerInRange(row.path("height"),
                DeviceLayoutDto.MIN_HEIGHT, DeviceLayoutDto.MAX_HEIGHT);
    }

    private boolean isOptionalCoordinate(JsonNode node) {
        return !isPresentNode(node)
                || (node.isNumber()
                && Double.isFinite(node.asDouble())
                && Math.abs(node.asDouble()) <= DeviceLayoutDto.MAX_ABS_POSITION);
    }

    private boolean isOptionalIntegerInRange(JsonNode node, int min, int max) {
        if (!isPresentNode(node)) return true;
        if (!node.isIntegralNumber() || !node.canConvertToInt()) return false;
        int value = node.intValue();
        return value >= min && value <= max;
    }

    private Map<String, Object> normalizePosition(JsonNode node, int index) {
        Map<String, Object> position = new LinkedHashMap<>();
        double fallbackX = 80 + (index % 3) * 240;
        double fallbackY = 80 + (index / 3) * 180;
        position.put("x", doubleOrDefault(node.path("x"), fallbackX));
        position.put("y", doubleOrDefault(node.path("y"), fallbackY));
        return position;
    }

    private Map<String, DeviceContext> buildDeviceContext(List<Map<String, Object>> devices,
                                                           TemplateCatalog catalog,
                                                           Map<String, String> deviceReferenceMap) {
        Map<String, DeviceContext> contexts = new LinkedHashMap<>();
        Map<String, DeviceContext> byStableId = new LinkedHashMap<>();
        for (Map<String, Object> device : devices) {
            String id = String.valueOf(device.get("id"));
            DeviceTemplateDto template = catalog.resolve(String.valueOf(device.get("templateName")));
            if (template != null) {
                byStableId.put(id, new DeviceContext(id, String.valueOf(device.get("label")), template));
            }
        }
        for (Map.Entry<String, String> entry : deviceReferenceMap.entrySet()) {
            DeviceContext context = byStableId.get(entry.getValue());
            if (context != null) contexts.put(entry.getKey(), context);
        }
        return contexts;
    }

    @SuppressWarnings("unchecked")
    private BoardSemanticValidator.BoardContext scenarioSemanticContext(
            Map<String, DeviceContext> deviceById,
            List<Map<String, Object>> devices,
            List<Map<String, Object>> environmentVariables) {
        Map<String, DeviceContext> uniqueDevices = new LinkedHashMap<>();
        for (DeviceContext context : deviceById.values()) {
            uniqueDevices.putIfAbsent(context.id(), context);
        }
        List<DeviceNodeDto> nodes = new ArrayList<>();
        List<DeviceTemplateDto> templates = new ArrayList<>();
        Map<String, Map<String, Object>> deviceRowsById = new LinkedHashMap<>();
        for (Map<String, Object> device : devices) {
            if (device != null && device.get("id") != null) {
                deviceRowsById.put(String.valueOf(device.get("id")), device);
            }
        }
        Set<String> templateNames = new HashSet<>();
        for (DeviceContext context : uniqueDevices.values()) {
            DeviceNodeDto node = new DeviceNodeDto();
            node.setId(context.id());
            node.setLabel(context.label());
            node.setTemplateName(templateName(context.template()));
            Map<String, Object> deviceRow = deviceRowsById.get(context.id());
            if (deviceRow != null) {
                Object state = deviceRow.get("state");
                if (state instanceof String text && !text.isBlank()) {
                    node.setState(text);
                }
                Object variables = deviceRow.get("variables");
                if (variables instanceof List<?> list) {
                    List<VariableStateDto> values = new ArrayList<>();
                    for (Object item : list) {
                        if (!(item instanceof Map<?, ?> row)) continue;
                        Object name = row.get("name");
                        Object value = row.get("value");
                        if (name instanceof String variableName && value instanceof String variableValue) {
                            VariableStateDto dto = new VariableStateDto();
                            dto.setName(variableName);
                            dto.setValue(variableValue);
                            Object trust = row.get("trust");
                            if (trust instanceof String trustValue) dto.setTrust(trustValue);
                            values.add(dto);
                        }
                    }
                    node.setVariables(values);
                }
            }
            nodes.add(node);
            if (templateNames.add(templateName(context.template()))) {
                templates.add(context.template());
            }
        }
        List<BoardEnvironmentVariableDto> environment = new ArrayList<>();
        for (Map<String, Object> row : environmentVariables) {
            if (row == null || !(row.get("name") instanceof String name)
                    || !(row.get("value") instanceof String value)) {
                continue;
            }
            environment.add(new BoardEnvironmentVariableDto(
                    name,
                    value,
                    row.get("trust") instanceof String trust ? trust : null,
                    row.get("privacy") instanceof String privacy ? privacy : null));
        }
        return BoardSemanticValidator.recommendationContext(nodes, templates, environment);
    }

    private String environmentDomainMismatch(DeviceTemplateDto template,
                                             Map<String, EnvironmentDomainSource> activeDomains) {
        for (Map.Entry<String, DeviceTemplateDto.DeviceManifest.InternalVariable> entry :
                templateEnvironmentDefinitions(template).entrySet()) {
            String literalName = entry.getKey();
            EnvironmentDomainSource previous = activeDomains.get(literalName.toLowerCase(Locale.ROOT));
            if (previous == null) continue;
            String mismatch = !previous.literalName.equals(literalName)
                    ? "name/case mismatch ('" + previous.literalName + "' versus '" + literalName + "')"
                    : EnvironmentDomainUtils.incompatibility(previous.definition, entry.getValue());
            if (mismatch != null) {
                return "shared variable '" + previous.literalName + "' has a " + mismatch
                        + " versus device '" + previous.deviceLabel + "'";
            }
        }
        return null;
    }

    private void registerEnvironmentDomains(DeviceTemplateDto template,
                                            String deviceLabel,
                                            Map<String, EnvironmentDomainSource> activeDomains) {
        for (Map.Entry<String, DeviceTemplateDto.DeviceManifest.InternalVariable> entry :
                templateEnvironmentDefinitions(template).entrySet()) {
            activeDomains.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT),
                    new EnvironmentDomainSource(entry.getKey(), deviceLabel, entry.getValue()));
        }
    }

    private Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> templateEnvironmentDefinitions(
            DeviceTemplateDto template) {
        Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> definitions = new LinkedHashMap<>();
        if (template == null || template.getManifest() == null) return definitions;
        DeviceTemplateDto.DeviceManifest manifest = template.getManifest();
        Set<String> readableNames = new HashSet<>();
        for (DeviceTemplateDto.DeviceManifest.InternalVariable variable :
                safeList(manifest.getInternalVariables())) {
            if (variable == null || variable.getName() == null || variable.getName().isBlank()
                    || Boolean.TRUE.equals(variable.getIsInside())) continue;
            readableNames.add(variable.getName());
            definitions.putIfAbsent(variable.getName(), variable);
        }
        for (String impacted : safeList(manifest.getImpactedVariables())) {
            if (impacted == null || impacted.isBlank() || readableNames.contains(impacted)) continue;
            DeviceTemplateDto.DeviceManifest.InternalVariable definition =
                    EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted);
            if (definition != null) definitions.putIfAbsent(impacted, definition);
        }
        return definitions;
    }

    private List<Map<String, Object>> normalizeEnvironmentVariables(JsonNode envNode,
                                                                    List<Map<String, Object>> devices,
                                                                    TemplateCatalog catalog,
                                                                    List<Map<String, Object>> filteredItems,
                                                                    List<Map<String, Object>> adjustedItems,
                                                                    String language,
                                                                    CandidateStats stats) {
        Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> definitions =
                environmentDefinitions(devices, catalog);
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        if (envNode.isArray()) {
            stats.rawCandidateCount += envNode.size();
            int index = 0;
            for (JsonNode row : envNode) {
                stats.inspectedCount++;
                index++;
                String name = text(row.path("name"), "");
                if (name.isBlank()) {
                    filteredItems.add(filteredItem("environment", index, "missingEnvironmentName", language, ""));
                    continue;
                }
                if (!definitions.containsKey(name)) {
                    filteredItems.add(filteredItem("environment", index, "unknownEnvironmentVariable", language, name));
                    continue;
                }
                if (byName.containsKey(name)) {
                    filteredItems.add(filteredItem("environment", index,
                            "duplicateEnvironmentVariable", language, name));
                    continue;
                }
                DeviceTemplateDto.DeviceManifest.InternalVariable definition = definitions.get(name);
                Map<String, Object> appliedDefaults = new LinkedHashMap<>();
                String value = row.has("value") && !row.path("value").isNull()
                        ? row.path("value").asText("")
                        : defaultSharedVariableValue(definition);
                if (!row.has("value") || row.path("value").isNull()) {
                    appliedDefaults.put("value", value);
                }
                if (definition != null && row.has("value") && !row.path("value").isNull()) {
                    value = canonicalVariableValue(definition, "=", value);
                    if (value == null) {
                        filteredItems.add(filteredItem("environment", index, "invalidEnvironmentValue", language, name));
                        continue;
                    }
                }
                String trust = normalizeTrust(text(row.path("trust"), ""));
                if (isProvided(row.path("trust")) && trust == null) {
                    filteredItems.add(filteredItem("environment", index, "invalidEnvironmentTrust", language, name));
                    continue;
                }
                String privacy = normalizePrivacy(text(row.path("privacy"), ""));
                if (isProvided(row.path("privacy")) && privacy == null) {
                    filteredItems.add(filteredItem("environment", index, "invalidEnvironmentPrivacy", language, name));
                    continue;
                }
                Map<String, Object> env = new LinkedHashMap<>();
                env.put("name", name);
                env.put("value", value);
                String effectiveTrust = trust != null
                        ? trust : defaultTrust(normalizeTrust(definition == null ? null : definition.getTrust()));
                String effectivePrivacy = privacy != null
                        ? privacy : defaultPrivacy(normalizePrivacy(definition == null ? null : definition.getPrivacy()));
                env.put("trust", effectiveTrust);
                env.put("privacy", effectivePrivacy);
                if (!isProvided(row.path("trust"))) appliedDefaults.put("trust", effectiveTrust);
                if (!isProvided(row.path("privacy"))) appliedDefaults.put("privacy", effectivePrivacy);
                byName.put(name, env);
                stats.validatedCount++;
                if (!appliedDefaults.isEmpty()) {
                    adjustedItems.add(adjustedItem("environment", index,
                            "environmentDefaultsApplied", language, name, appliedDefaults));
                }
            }
        }

        for (Map.Entry<String, DeviceTemplateDto.DeviceManifest.InternalVariable> entry : definitions.entrySet()) {
            if (!byName.containsKey(entry.getKey())) {
                Map<String, Object> defaulted = defaultEnvironmentVariable(entry.getValue(), entry.getKey());
                byName.put(entry.getKey(), defaulted);
                adjustedItems.add(adjustedItem("environment", null,
                        "missingEnvironmentAdded", language, entry.getKey(), defaulted));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> environmentDefinitions(
            List<Map<String, Object>> devices,
            TemplateCatalog catalog) {
        Map<String, DeviceTemplateDto.DeviceManifest.InternalVariable> definitions = new LinkedHashMap<>();
        for (Map<String, Object> device : devices) {
            DeviceTemplateDto template = catalog.resolve(String.valueOf(device.get("templateName")));
            if (template == null || template.getManifest() == null) continue;
            List<DeviceTemplateDto.DeviceManifest.InternalVariable> vars =
                    safeList(template.getManifest().getInternalVariables());
            for (DeviceTemplateDto.DeviceManifest.InternalVariable variable : vars) {
                if (variable.getName() != null && !Boolean.TRUE.equals(variable.getIsInside())) {
                    definitions.putIfAbsent(variable.getName(), variable);
                }
            }
            for (String impacted : safeList(template.getManifest().getImpactedVariables())) {
                if (impacted == null || impacted.isBlank()) continue;
                DeviceTemplateDto.DeviceManifest.InternalVariable definition =
                        EnvironmentDomainUtils.resolveImpactDomain(template.getManifest(), impacted);
                if (definition != null) {
                    definitions.putIfAbsent(impacted, definition);
                }
            }
        }
        return definitions;
    }

    private Map<String, Object> defaultEnvironmentVariable(DeviceTemplateDto.DeviceManifest.InternalVariable variable) {
        return defaultEnvironmentVariable(variable, variable == null ? "" : variable.getName());
    }

    private Map<String, Object> defaultEnvironmentVariable(DeviceTemplateDto.DeviceManifest.InternalVariable variable, String name) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("name", name);
        env.put("value", defaultSharedVariableValue(variable));
        env.put("trust", defaultTrust(variable == null ? null : normalizeTrust(variable.getTrust())));
        env.put("privacy", defaultPrivacy(variable == null ? null : normalizePrivacy(variable.getPrivacy())));
        return env;
    }

    private List<Map<String, Object>> normalizeRules(JsonNode rulesNode,
                                                     Map<String, DeviceContext> deviceById,
                                                     int maxRules,
                                                     List<Map<String, Object>> filteredItems,
                                                     List<Map<String, Object>> adjustedItems,
                                                     BoardSemanticValidator.BoardContext semanticContext,
                                                     String language,
                                                     CandidateStats stats) {
        List<Map<String, Object>> rules = new ArrayList<>();
        if (!rulesNode.isArray()) return rules;
        stats.rawCandidateCount += rulesNode.size();
        int index = 0;
        for (JsonNode row : rulesNode) {
            if (rules.size() >= maxRules) break;
            stats.inspectedCount++;
            index++;
            JsonNode sourcesNode = row.path("sources");
            String name = text(row.path("name"), "");
            int rawSourceCount = arraySize(sourcesNode);
            List<Map<String, Object>> sourceAdjustments = new ArrayList<>();
            List<Map<String, Object>> sources = normalizeRuleSources(
                    sourcesNode, deviceById, sourceAdjustments, language, index, name);
            if (rawSourceCount == 0 || sources.size() != rawSourceCount) {
                filteredItems.add(filteredItem("rule", index, "invalidRuleSources", language,
                        text(row.path("name"), "")));
                continue;
            }
            String toId = text(row.path("toId"), "");
            DeviceContext target = deviceById.get(toId);
            if (target == null) {
                filteredItems.add(filteredItem("rule", index, "unknownRuleTarget", language,
                        text(row.path("name"), toId)));
                continue;
            }
            DeviceTemplateDto.DeviceManifest.API actionApi = canonicalApi(
                    target.template, text(row.path("toApi"), ""), false);
            if (actionApi == null) {
                filteredItems.add(filteredItem("rule", index, "unknownRuleAction", language,
                        text(row.path("name"), text(row.path("toApi"), ""))));
                continue;
            }
            Map<String, Object> rule = new LinkedHashMap<>();
            if (!name.isBlank()) {
                rule.put("name", name);
            }
            rule.put("sources", sources);
            rule.put("toId", target.id);
            rule.put("toApi", actionApi.getName());
            String contentDevice = text(row.path("contentDevice"), "");
            String contentName = text(row.path("content"), "");
            if (contentDevice.isBlank() != contentName.isBlank()) {
                filteredItems.add(filteredItem("rule", index, "invalidRuleContent", language,
                        text(row.path("name"), !contentDevice.isBlank() ? contentDevice : contentName)));
                continue;
            }
            if (!contentDevice.isBlank()) {
                if (!Boolean.TRUE.equals(actionApi.getAcceptsContent())) {
                    filteredItems.add(filteredItem("rule", index, "ruleActionDoesNotAcceptContent", language,
                            text(row.path("name"), actionApi.getName())));
                    continue;
                }
                DeviceContext contentContext = deviceById.get(contentDevice);
                if (contentContext == null) {
                    filteredItems.add(filteredItem("rule", index, "invalidRuleContent", language,
                            text(row.path("name"), contentDevice)));
                    continue;
                }
                String content = canonicalContentName(contentContext.template, contentName);
                if (content == null) {
                    filteredItems.add(filteredItem("rule", index, "invalidRuleContent", language,
                            text(row.path("name"), contentName)));
                    continue;
                }
                rule.put("contentDevice", contentContext.id);
                rule.put("content", content);
            }
            BoardSemanticValidator.GroupValidationIssue groupIssue =
                    BoardSemanticValidator.validateRuleConditionGroup(
                            semanticContext, ruleConditions(sources), ruleCommand(rule));
            if (groupIssue != null) {
                String reasonCode = switch (groupIssue.reasonCode()) {
                    case "COMMAND_PRESTATE_INCOMPATIBLE" -> "ruleCommandPrestateIncompatible";
                    case "COMMAND_PRESTATE_UNREACHABLE" -> "ruleCommandPrestateUnreachable";
                    case "UNREACHABLE_CONDITION_GROUP" -> "unreachableRuleConditions";
                    default -> "contradictoryRuleConditions";
                };
                filteredItems.add(filteredItem("rule", index, reasonCode, language,
                        text(row.path("name"), actionApi.getName())));
                continue;
            }
            rules.add(rule);
            adjustedItems.addAll(sourceAdjustments);
            stats.validatedCount++;
        }
        return rules;
    }

    private List<RuleDto.Condition> ruleConditions(List<Map<String, Object>> sources) {
        List<RuleDto.Condition> result = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            result.add(RuleDto.Condition.builder()
                    .deviceName(String.valueOf(source.get("fromId")))
                    .attribute(String.valueOf(source.get("fromApi")))
                    .targetType(String.valueOf(source.get("itemType")))
                    .relation(source.get("relation") == null ? null : String.valueOf(source.get("relation")))
                    .value(source.get("value") == null ? null : String.valueOf(source.get("value")))
                    .build());
        }
        return result;
    }

    private RuleDto.Command ruleCommand(Map<String, Object> rule) {
        return RuleDto.Command.builder()
                .deviceName(String.valueOf(rule.get("toId")))
                .action(String.valueOf(rule.get("toApi")))
                .contentDevice(rule.get("contentDevice") == null
                        ? null : String.valueOf(rule.get("contentDevice")))
                .content(rule.get("content") == null ? null : String.valueOf(rule.get("content")))
                .build();
    }

    private List<Map<String, Object>> normalizeRuleSources(
            JsonNode sourcesNode,
            Map<String, DeviceContext> deviceById,
            List<Map<String, Object>> sourceAdjustments,
            String language,
            int ruleIndex,
            String ruleName) {
        List<Map<String, Object>> sources = new ArrayList<>();
        if (!sourcesNode.isArray()) return sources;
        for (JsonNode source : sourcesNode) {
            String fromId = text(source.path("fromId"), "");
            DeviceContext device = deviceById.get(fromId);
            if (device == null) continue;
            String itemType = text(source.path("itemType"), "").toLowerCase(Locale.ROOT);
            if (!RULE_TARGET_TYPES.contains(itemType)) continue;
            String key = text(source.path("fromApi"), text(source.path("attribute"), ""));
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("fromId", device.id);
            normalized.put("itemType", itemType);
            if ("api".equals(itemType)) {
                String api = canonicalApiName(device.template, key, true);
                if (api == null) continue;
                boolean relationProvided = isProvided(source.path("relation"));
                boolean valueProvided = isProvided(source.path("value"));
                if (relationProvided || valueProvided) {
                    String relation = normalizeRelation(text(source.path("relation"), ""));
                    String value = text(source.path("value"), "");
                    if (!relationProvided || !valueProvided
                            || !"=".equals(relation)
                            || !"TRUE".equalsIgnoreCase(value)) {
                        continue;
                    }
                    Map<String, Object> appliedValues = new LinkedHashMap<>();
                    appliedValues.put("sourceDevice", device.label);
                    appliedValues.put("sourceApi", api);
                    appliedValues.put("removedRelation", relation);
                    appliedValues.put("removedValue", "TRUE");
                    String label = ruleName.isBlank() ? device.label + "." + api : ruleName;
                    sourceAdjustments.add(adjustedItem(
                            "rule", ruleIndex, "apiEventSyntaxNormalized",
                            language, label, appliedValues));
                }
                normalized.put("fromApi", api);
            } else {
                if (!isProvided(source.path("relation")) || !isProvided(source.path("value"))) continue;
                String relation = normalizeRelation(text(source.path("relation"), ""));
                if (relation == null) continue;
                String value = text(source.path("value"), "");
                CapabilityValue cap = canonicalCapability(device.template, itemType, key, relation, value);
                if (cap == null) continue;
                normalized.put("fromApi", cap.key);
                normalized.put("relation", cap.relation);
                normalized.put("value", cap.value);
            }
            sources.add(normalized);
        }
        return sources;
    }

    private List<Map<String, Object>> normalizeSpecs(JsonNode specsNode,
                                                     Map<String, DeviceContext> deviceById,
                                                     int maxSpecs,
                                                     List<Map<String, Object>> filteredItems,
                                                     BoardSemanticValidator.BoardContext semanticContext,
                                                     String language,
                                                     CandidateStats stats) {
        List<Map<String, Object>> specs = new ArrayList<>();
        if (!specsNode.isArray()) return specs;
        stats.rawCandidateCount += specsNode.size();
        int specIndex = 1;
        int candidateIndex = 0;
        for (JsonNode row : specsNode) {
            if (specs.size() >= maxSpecs) break;
            stats.inspectedCount++;
            candidateIndex++;
            String templateId = text(row.path("templateId"), "");
            if (!SPEC_TEMPLATE_IDS.contains(templateId)) {
                filteredItems.add(filteredItem("specification", candidateIndex, "invalidSpecTemplateId", language,
                        text(row.path("templateLabel"), templateId)));
                continue;
            }
            JsonNode aConditionsNode = row.path("aConditions");
            JsonNode ifConditionsNode = row.path("ifConditions");
            JsonNode thenConditionsNode = row.path("thenConditions");
            int rawAConditionCount = arraySize(aConditionsNode);
            int rawIfConditionCount = arraySize(ifConditionsNode);
            int rawThenConditionCount = arraySize(thenConditionsNode);
            List<String> conditionIssues = new ArrayList<>();
            List<Map<String, Object>> aConditions = normalizeSpecConditions(aConditionsNode, deviceById, "a", language, conditionIssues);
            List<Map<String, Object>> ifConditions = normalizeSpecConditions(ifConditionsNode, deviceById, "if", language, conditionIssues);
            List<Map<String, Object>> thenConditions = normalizeSpecConditions(thenConditionsNode, deviceById, "then", language, conditionIssues);
            if (aConditions.size() != rawAConditionCount
                    || ifConditions.size() != rawIfConditionCount
                    || thenConditions.size() != rawThenConditionCount) {
                Map<String, Object> rejected = filteredItem("specification", candidateIndex, "invalidSpecConditions", language,
                        text(row.path("templateLabel"), templateId));
                rejected.put("reason", rejected.get("reason") + " " + String.join(" ", conditionIssues));
                filteredItems.add(rejected);
                continue;
            }
            if (!validSpecShape(templateId, aConditions, ifConditions, thenConditions)) {
                filteredItems.add(filteredItem("specification", candidateIndex, "invalidSpecShape", language,
                        text(row.path("templateLabel"), templateId)));
                continue;
            }
            if ("7".equals(templateId)
                    && aConditions.stream().anyMatch(condition ->
                    !isValidUntrustedSourceSafetyCondition(condition, deviceById))) {
                filteredItems.add(filteredItem("specification", candidateIndex,
                        "invalidUntrustedSourceSafetyCondition", language,
                        text(row.path("templateLabel"), templateId)));
                continue;
            }
            String rejectedGroupReason = null;
            for (SpecConditionGroup group : List.of(
                    new SpecConditionGroup("a", aConditions),
                    new SpecConditionGroup("if", ifConditions),
                    new SpecConditionGroup("then", thenConditions))) {
                BoardSemanticValidator.GroupValidationIssue groupIssue =
                        BoardSemanticValidator.validateSpecConditionGroup(
                                semanticContext, specConditions(group.conditions(), group.side()), group.side());
                if (groupIssue != null) {
                    rejectedGroupReason = "UNREACHABLE_CONDITION_GROUP".equals(groupIssue.reasonCode())
                            ? "unreachableSpecConditionGroup" : "contradictorySpecConditionGroup";
                    break;
                }
            }
            if (rejectedGroupReason != null) {
                filteredItems.add(filteredItem("specification", candidateIndex,
                        rejectedGroupReason, language,
                        text(row.path("templateLabel"), templateId)));
                continue;
            }
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("templateId", templateId);
            spec.put("aConditions", aConditions);
            spec.put("ifConditions", ifConditions);
            spec.put("thenConditions", thenConditions);
            specs.add(spec);
            stats.validatedCount++;
            specIndex++;
        }
        return specs;
    }

    private List<SpecConditionDto> specConditions(List<Map<String, Object>> conditions, String side) {
        List<SpecConditionDto> result = new ArrayList<>();
        for (Map<String, Object> condition : conditions) {
            SpecConditionDto dto = new SpecConditionDto();
            dto.setSide(side);
            dto.setDeviceId(String.valueOf(condition.get("deviceId")));
            dto.setTargetType(String.valueOf(condition.get("targetType")));
            dto.setKey(String.valueOf(condition.get("key")));
            dto.setPropertyScope(condition.get("propertyScope") == null
                    ? null : String.valueOf(condition.get("propertyScope")));
            dto.setVariableSource(condition.get("variableSource") == null
                    ? null : String.valueOf(condition.get("variableSource")));
            dto.setRelation(String.valueOf(condition.get("relation")));
            dto.setValue(String.valueOf(condition.get("value")));
            result.add(dto);
        }
        return result;
    }

    private List<Map<String, Object>> normalizeSpecConditions(JsonNode conditionsNode,
                                                              Map<String, DeviceContext> deviceById,
                                                              String side, String language, List<String> issues) {
        List<Map<String, Object>> conditions = new ArrayList<>();
        if (!conditionsNode.isArray()) return conditions;
        int index = 0;
        for (JsonNode row : conditionsNode) {
            index++;
            String deviceId = text(row.path("deviceId"), "");
            DeviceContext device = deviceById.get(deviceId);
            if (device == null) {
                issues.add(specConditionIssue(language, side, index, "conditionDeviceMissing"));
                continue;
            }
            String targetType = text(row.path("targetType"), "").toLowerCase(Locale.ROOT);
            if (!SPEC_TARGET_TYPES.contains(targetType)) {
                issues.add(specConditionIssue(language, side, index, "conditionTargetUnsupported"));
                continue;
            }
            String key = text(row.path("key"), "");
            String propertyScope = text(row.path("propertyScope"), "").toLowerCase(Locale.ROOT);
            boolean propertyCondition = "trust".equals(targetType) || "privacy".equals(targetType);
            if (propertyCondition && !Set.of("state", "variable").contains(propertyScope)) {
                issues.add(specConditionIssue(language, side, index, "conditionPropertyScopeMissing"));
                continue;
            }
            if (!propertyCondition && !propertyScope.isBlank()) {
                issues.add(specConditionIssue(language, side, index, "conditionUnexpectedPropertyScope"));
                continue;
            }
            // Which question a variable condition asks. Filtered, never defaulted: this generator drops a
            // candidate it cannot validate, and choosing a source the model did not state would put an
            // assertion in the scene that nobody authored. `environment` additionally needs a shared
            // declaration, so a device-local variable may only be asked as `reported`.
            String variableSource = text(row.path("variableSource"), "").toLowerCase(Locale.ROOT);
            if ("variable".equals(targetType)) {
                if (!Set.of("environment", "reported").contains(variableSource)) {
                    issues.add(specConditionIssue(language, side, index, "conditionVariableSourceMissing"));
                    continue;
                }
                if ("environment".equals(variableSource)) {
                    DeviceTemplateDto.DeviceManifest.InternalVariable declared =
                            findVariable(device.template, key);
                    if (declared == null || Boolean.TRUE.equals(declared.getIsInside())) {
                        issues.add(specConditionIssue(language, side, index, "conditionEnvironmentSourceInvalid"));
                        continue;
                    }
                }
            } else if (!variableSource.isBlank()) {
                issues.add(specConditionIssue(language, side, index, "conditionUnexpectedVariableSource"));
                continue;
            }
            boolean relationProvided = isProvided(row.path("relation"));
            boolean valueProvided = isProvided(row.path("value"));
            if (!"api".equals(targetType) && (!relationProvided || !valueProvided)) {
                issues.add(specConditionIssue(language, side, index, "conditionComparisonMissing"));
                continue;
            }
            String relation = relationProvided
                    ? normalizeRelation(text(row.path("relation"), ""))
                    : "=";
            if (relation == null) {
                issues.add(specConditionIssue(language, side, index, "conditionRelationUnsupported"));
                continue;
            }
            String value = valueProvided ? row.path("value").asText() : "TRUE";
            CapabilityValue cap = canonicalCapability(device.template, targetType, key, relation, value, propertyScope);
            if (cap == null) {
                issues.add(specConditionIssue(language, side, index, "conditionCapabilityMismatch"));
                continue;
            }
            Map<String, Object> condition = new LinkedHashMap<>();
            condition.put("deviceId", device.id);
            condition.put("targetType", targetType);
            condition.put("key", cap.key);
            if (propertyCondition) condition.put("propertyScope", propertyScope);
            if ("variable".equals(targetType)) condition.put("variableSource", variableSource);
            condition.put("relation", cap.relation);
            condition.put("value", cap.value);
            conditions.add(condition);
        }
        return conditions;
    }

    private String specConditionIssue(String language, String side, int index, String reasonCode) {
        boolean zh = "zh-CN".equals(language);
        String label = switch (side) {
            case "if" -> zh ? "前提条件" : "IF condition";
            case "then" -> zh ? "目标条件" : "THEN condition";
            default -> zh ? "检查条件" : "A condition";
        };
        return (zh ? label + "第 " + index + " 项：" : label + " " + index + ": ")
                + validationReason(language, reasonCode);
    }

    private Map<String, Object> filteredItem(String type,
                                             int index,
                                             String reasonCode,
                                             String language,
                                             String label) {
        return new RecommendationFilterItem(
                type,
                index,
                reasonCode,
                validationReason(language, reasonCode),
                label
        ).toMap();
    }

    private Map<String, Object> adjustedItem(String type,
                                             Integer index,
                                             String reasonCode,
                                             String language,
                                             String label,
                                             Map<String, Object> appliedValues) {
        return new RecommendationAdjustmentItem(
                type,
                index,
                reasonCode,
                adjustmentReason(language, reasonCode),
                label,
                appliedValues
        ).toMap();
    }

    private String adjustmentReason(String language, String reasonCode) {
        boolean zh = "zh-CN".equals(language);
        return switch (reasonCode) {
            case "deviceDefaultsApplied" -> zh
                    ? "AI 省略了部分初始运行或布局字段；系统按该设备模板与画布默认布局补全。"
                    : "The AI omitted initial runtime or layout fields; template and canvas defaults were applied.";
            case "environmentDefaultsApplied" -> zh
                    ? "AI 省略了该共享环境变量的部分初始值或标签；系统按设备模板声明补全。"
                    : "The AI omitted part of this shared environment variable's initial value or labels; template defaults were applied.";
            case "missingEnvironmentAdded" -> zh
                    ? "AI 未返回设备模板要求的共享环境变量；系统按模板声明加入了显式初始项。"
                    : "The AI omitted a shared environment variable required by a kept device template; an explicit template-default item was added.";
            case "apiEventSyntaxNormalized" -> zh
                    ? "AI 将规则 API 事件写成了布尔条件“= TRUE”；系统按相同用户语义规范化为事件触发，并移除了 relation/value。"
                    : "The AI expressed a rule API event as the boolean condition '= TRUE'; it was normalized to the same event-trigger semantics by removing relation/value.";
            default -> zh
                    ? "系统对保留的场景候选应用了确定性模板或布局默认值。"
                    : "Deterministic template or layout defaults were applied to a kept scenario candidate.";
        };
    }

    private String validationReason(String language, String reasonCode) {
        boolean zh = "zh-CN".equals(language);
        return switch (reasonCode) {
            case "unknownTemplate" -> zh
                    ? "场景设备引用了当前模板库中不存在的设备模板。"
                    : "A scenario device references a template that is not available.";
            case "invalidDeviceId" -> zh
                    ? "场景设备缺少本次 AI 回答内的引用别名，或别名超过 100 个字符；系统不会根据标签猜测引用。"
                    : "A scenario device is missing its response-local reference alias or the alias exceeds 100 characters; the backend does not infer references from the label.";
            case "invalidDeviceRuntime" -> zh
                    ? "场景设备显式给出的初始状态、来源标签、敏感性标签或本地变量不符合设备模板声明；后端不会静默改成默认值。"
                    : "A scenario device explicitly supplied an initial state, source label, sensitivity label, or local variable that does not match the template; the backend does not silently replace it with a default.";
            case "duplicateDeviceId" -> zh
                    ? "两个场景设备使用了同一本次回答内的引用别名，规则和规约无法无歧义地绑定到实例。"
                    : "Two scenario devices use the same response-local alias, so rules and specifications cannot bind to an instance unambiguously.";
            case "invalidDeviceLabel" -> zh
                    ? "场景设备缺少可读名称、名称重复，或名称超过 255 个字符。"
                    : "A scenario device has a missing, duplicate, or overlong display name.";
            case "environmentDomainConflict" -> zh
                    ? "该设备模板与已保留设备对同名共享环境变量给出了不一致的类型、范围、变化率或默认标签；场景不能形成单一模型。"
                    : "This device template conflicts with a kept device over the type, range, change rate, or default labels of a shared environment variable, so the scene cannot form one model.";
            case "invalidDeviceLayout" -> zh
                    ? "场景设备提供了非法坐标或尺寸；后端只会为缺省布局字段补默认值，不会覆盖非法值。"
                    : "A scenario device supplied invalid coordinates or dimensions; defaults are used only for omitted layout fields, not to overwrite invalid values.";
            case "missingEnvironmentName" -> zh
                    ? "场景环境变量缺少名称。"
                    : "A scenario environment variable is missing its name.";
            case "unknownEnvironmentVariable" -> zh
                    ? "场景环境变量未被任何保留设备模板声明或影响，应用后无法进入画布语义模型。"
                    : "A scenario environment variable is not declared or impacted by any kept device template, so it cannot enter the board semantic model.";
            case "duplicateEnvironmentVariable" -> zh
                    ? "场景重复给出了同一个环境变量；后端保留第一项并明确过滤重复候选，不会静默覆盖。"
                    : "The scene supplied the same environment variable more than once; the first item is kept and the duplicate is reported instead of silently overwriting it.";
            case "invalidEnvironmentValue" -> zh
                    ? "场景环境变量的初始值不符合设备模板声明的枚举或数值范围。"
                    : "A scenario environment variable value is outside the enum or numeric range declared by the device template.";
            case "invalidEnvironmentTrust" -> zh
                    ? "场景环境变量的 trust 必须是 trusted 或 untrusted。"
                    : "A scenario environment variable trust value must be trusted or untrusted.";
            case "invalidEnvironmentPrivacy" -> zh
                    ? "场景环境变量的 privacy 必须是 public 或 private。"
                    : "A scenario environment variable privacy value must be public or private.";
            case "invalidRuleSources" -> zh
                    ? "场景规则没有可保留的触发源；触发设备、能力、关系或取值未通过模板校验。"
                    : "A scenario rule has no valid trigger source after device capability validation.";
            case "unknownRuleTarget" -> zh
                    ? "场景规则的目标设备不在已保留的设备列表中。"
                    : "A scenario rule target is not present in the kept device list.";
            case "unknownRuleAction" -> zh
                    ? "场景规则调用了目标设备没有暴露的可执行 API。"
                    : "A scenario rule calls an action API that the target device does not expose.";
            case "invalidRuleContent" -> zh
                    ? "场景规则的内容复制来源或内容字段未通过设备模板校验。"
                    : "A scenario rule has an invalid content source or content field for the referenced template.";
            case "ruleActionDoesNotAcceptContent" -> zh
                    ? "场景规则把内容敏感性标签附着到了未声明 AcceptsContent 的普通动作；整条规则已过滤。"
                    : "A scenario rule attaches content sensitivity to an ordinary action that does not declare AcceptsContent; the whole rule was filtered.";
            case "contradictoryRuleConditions" -> zh
                    ? "场景规则的触发条件在设备声明的合法状态或变量定义域中无法同时成立。"
                    : "The scenario rule trigger conditions cannot hold together in the declared device state and variable domains.";
            case "ruleCommandPrestateIncompatible" -> zh
                    ? "场景规则的触发条件与目标动作声明的 StartState 前置状态不兼容。"
                    : "The scenario rule trigger conditions are incompatible with the target action's declared StartState.";
            case "ruleCommandPrestateUnreachable" -> zh
                    ? "场景规则的目标动作要求了从当前状态无法到达的 StartState。"
                    : "The scenario rule target action requires a StartState that is unreachable from the current state.";
            case "unreachableRuleConditions" -> zh
                    ? "场景规则使用了合法但从当前设备状态和值无法到达的触发条件。"
                    : "The scenario rule uses legal trigger conditions that are unreachable from the current device state and values.";
            case "invalidSpecTemplateId" -> zh
                    ? "场景规约缺少合法 templateId；必须使用 1 到 7 的规约模板。"
                    : "A scenario specification is missing a valid templateId; it must use specification template 1 through 7.";
            case "invalidSpecConditions" -> zh
                    ? "场景规约包含未通过设备能力校验的条件；后端不会静默删除部分条件后继续保留该规约。"
                    : "A scenario specification contains conditions that failed device-capability validation; the backend does not keep a partially dropped specification.";
            case "conditionDeviceMissing" -> zh
                    ? "引用的设备不在草稿中，请使用草稿中已有的设备。"
                    : "The referenced device is absent from the draft; use a device in this draft.";
            case "conditionTargetUnsupported" -> zh
                    ? "条件类型无法识别，请使用设备支持的状态、模式、变量、事件或标签条件。"
                    : "The condition type is unsupported; use a supported state, mode, variable, event, or label condition.";
            case "conditionPropertyScopeMissing" -> zh
                    ? "未说明来源或敏感性标签属于设备状态还是变量，请明确标签范围。"
                    : "The source or sensitivity label must specify whether it belongs to state or a variable.";
            case "conditionUnexpectedPropertyScope" -> zh
                    ? "普通条件多写了标签范围，请仅在来源或敏感性标签条件中填写。"
                    : "An ordinary condition has an inapplicable label scope; omit it outside trust/privacy conditions.";
            case "conditionVariableSourceMissing" -> zh
                    ? "未说明比较的是设备读数还是家中实际数值，请明确变量来源。"
                    : "Specify whether the comparison uses the device-reported reading or the actual shared environment value.";
            case "conditionEnvironmentSourceInvalid" -> zh
                    ? "所选变量不是该设备声明的共享环境变量，请使用模板中的变量并选择正确的读数来源。"
                    : "This is not a shared variable declared by this device; choose a template variable and its correct reading source.";
            case "conditionUnexpectedVariableSource" -> zh
                    ? "非变量条件多写了读数来源，请仅在变量条件中填写。"
                    : "A non-variable condition has an inapplicable reading source; omit it.";
            case "conditionComparisonMissing" -> zh
                    ? "缺少比较方式或比较值，请补全该条件。"
                    : "The comparison operator or value is missing; complete this condition.";
            case "conditionRelationUnsupported" -> zh
                    ? "比较方式无法识别，请使用等于、不等于、数值比较或选值比较。"
                    : "The comparison operator is unsupported; use equality, inequality, numeric comparison, or set membership.";
            case "conditionCapabilityMismatch" -> zh
                    ? "所选状态、变量或取值不符合设备能力，请按设备模板声明选择。"
                    : "The selected state, variable, or value does not match the device capability; choose from its template.";
            case "invalidSpecShape" -> zh
                    ? "场景规约的模板形状或条件能力未通过校验。"
                    : "A scenario specification has an invalid template shape or invalid condition capabilities.";
            case "contradictorySpecConditionGroup" -> zh
                    ? "场景规约的某个 A、IF 或 THEN 条件组在设备声明的合法定义域中无法同时成立。"
                    : "One A, IF, or THEN group in the scenario specification cannot hold together in the declared device domains.";
            case "unreachableSpecConditionGroup" -> zh
                    ? "场景规约的某个 A、IF 或 THEN 条件组使用了合法但从当前状态和值无法到达的条件。"
                    : "One A, IF, or THEN group in the scenario specification uses legal conditions that are unreachable from the current state and values.";
            case "invalidUntrustedSourceSafetyCondition" -> zh
                    ? "模板 7 只表示“不可信来源不得导致受保护事件”：不得直接写 trust/privacy，state/mode 必须为 =，API 必须为 = TRUE 且模板必须声明可建模的 EndState；普通禁令或隐私泄露请使用模板 3。"
                    : "Template 7 means an untrusted source must not cause the protected event: no explicit trust/privacy predicates, state/mode require '=', and an API requires '= TRUE' plus a modeled EndState. Use template 3 for an unconditional prohibition or privacy leakage.";
            default -> zh
                    ? "该场景候选项未通过后端能力校验。"
                    : "The scenario candidate did not pass backend capability validation.";
        };
    }

    @SafeVarargs
    private List<Map<String, Object>> specDeviceRefs(List<Map<String, Object>>... conditionLists) {
        Map<String, Map<String, Object>> refs = new LinkedHashMap<>();
        for (List<Map<String, Object>> conditions : conditionLists) {
            for (Map<String, Object> condition : conditions) {
                String deviceId = String.valueOf(condition.get("deviceId"));
                refs.putIfAbsent(deviceId, new LinkedHashMap<>(Map.of(
                        "deviceId", deviceId,
                        "deviceLabel", String.valueOf(condition.get("deviceLabel")),
                        "selectedApis", List.of()
                )));
            }
        }
        return new ArrayList<>(refs.values());
    }

    private boolean validSpecShape(String templateId,
                                   List<Map<String, Object>> aConditions,
                                   List<Map<String, Object>> ifConditions,
                                   List<Map<String, Object>> thenConditions) {
        if ("1".equals(templateId) || "2".equals(templateId) || "3".equals(templateId) || "7".equals(templateId)) {
            return !aConditions.isEmpty() && ifConditions.isEmpty() && thenConditions.isEmpty();
        }
        return aConditions.isEmpty() && !ifConditions.isEmpty() && !thenConditions.isEmpty();
    }

    private boolean isValidUntrustedSourceSafetyCondition(Map<String, Object> condition,
                                                          Map<String, DeviceContext> deviceById) {
        String targetType = String.valueOf(condition.getOrDefault("targetType", ""));
        String relation = String.valueOf(condition.getOrDefault("relation", ""));
        String value = String.valueOf(condition.getOrDefault("value", ""));
        if ("trust".equals(targetType) || "privacy".equals(targetType)) {
            return false;
        }
        if ("state".equals(targetType) || "mode".equals(targetType)) {
            return "=".equals(relation);
        }
        if (!"api".equals(targetType)) return true;
        if (!"=".equals(relation) || !"TRUE".equalsIgnoreCase(value)) return false;
        String stableId = String.valueOf(condition.getOrDefault("deviceId", ""));
        DeviceContext device = deviceById.values().stream()
                .filter(candidate -> stableId.equals(candidate.id()))
                .findFirst()
                .orElse(null);
        DeviceTemplateDto.DeviceManifest manifest = device == null || device.template() == null
                ? null : device.template().getManifest();
        if (manifest == null || manifest.getApis() == null) return false;
        String key = String.valueOf(condition.getOrDefault("key", ""));
        return manifest.getApis().stream()
                .anyMatch(api -> api != null && Boolean.TRUE.equals(api.getSignal())
                        && key.equals(api.getName())
                        && api.getEndState() != null && !api.getEndState().isBlank());
    }

    private CapabilityValue canonicalCapability(DeviceTemplateDto template,
                                                String targetType,
                                                String rawKey,
                                                String relation,
                                                String rawValue) {
        return canonicalCapability(template, targetType, rawKey, relation, rawValue, null);
    }

    private CapabilityValue canonicalCapability(DeviceTemplateDto template,
                                                String targetType,
                                                String rawKey,
                                                String relation,
                                                String rawValue,
                                                String propertyScope) {
        if (relation == null) return null;
        String canonicalRelation = relation;
        if ("api".equals(targetType)) {
            if (!isEnumRelation(canonicalRelation)) return null;
            String api = canonicalApiName(template, rawKey, true);
            String value = canonicalBoolean(rawValue, canonicalRelation);
            return api == null || value == null ? null : new CapabilityValue(api, canonicalRelation, value);
        }
        if ("state".equals(targetType)) {
            if (!isEnumRelation(canonicalRelation)) return null;
            String value = canonicalEnumSet(workingStates(template), rawValue, canonicalRelation, true);
            return value == null ? null : new CapabilityValue("state", canonicalRelation, value);
        }
        if ("mode".equals(targetType)) {
            if (!isEnumRelation(canonicalRelation)) return null;
            String mode = canonicalModeName(template, rawKey);
            if (mode == null) return null;
            String value = canonicalEnumSet(modeValues(template, mode), rawValue, canonicalRelation, false);
            return value == null ? null : new CapabilityValue(mode, canonicalRelation, value);
        }
        if ("variable".equals(targetType)) {
            DeviceTemplateDto.DeviceManifest.InternalVariable variable = findVariable(template, rawKey);
            if (variable == null || ManifestConditionSemantics.findConditionVariable(
                    template.getManifest(), variable.getName()) == null) return null;
            String value = canonicalVariableValue(variable, canonicalRelation, rawValue);
            return value == null ? null : new CapabilityValue(variable.getName(), canonicalRelation, value);
        }
        if ("trust".equals(targetType) || "privacy".equals(targetType)) {
            if (!isEnumRelation(canonicalRelation)) return null;
            String key = canonicalPropertyKey(template, propertyScope, rawKey);
            if (key == null) return null;
            String value = canonicalEnumSet("trust".equals(targetType)
                    ? List.of("trusted", "untrusted")
                    : List.of("public", "private"), rawValue, canonicalRelation, false);
            return value == null ? null : new CapabilityValue(key, canonicalRelation, value);
        }
        return null;
    }

    private String canonicalVariableValue(DeviceTemplateDto.DeviceManifest.InternalVariable variable,
                                          String relation,
                                          String rawValue) {
        if (variable == null || rawValue == null || rawValue.isBlank()) return null;
        List<String> candidates = splitValues(rawValue, relation, false);
        if (candidates.isEmpty()) return null;
        if (variable.getValues() != null && !variable.getValues().isEmpty()) {
            if (!isEnumRelation(relation)) return null;
            return canonicalEnumSet(variable.getValues(), rawValue, relation, false);
        }
        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            List<String> normalized = new ArrayList<>();
            for (String candidate : candidates) {
                try {
                    int parsed = Integer.parseInt(candidate.trim());
                    if (parsed < variable.getLowerBound() || parsed > variable.getUpperBound()) return null;
                    normalized.add(String.valueOf(parsed));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return joinValues(normalized, relation);
        }
        return joinValues(candidates, relation);
    }

    private String canonicalPropertyKey(DeviceTemplateDto template, String scope, String rawKey) {
        if ("variable".equals(scope)) {
            DeviceTemplateDto.DeviceManifest.InternalVariable variable = findVariable(template, rawKey);
            return variable == null ? null : variable.getName();
        }
        if ("state".equals(scope)) {
            return canonicalModeName(template, rawKey);
        }
        return null;
    }

    private String canonicalBoolean(String rawValue, String relation) {
        return canonicalEnumSet(List.of("TRUE", "FALSE"), rawValue == null || rawValue.isBlank() ? "TRUE" : rawValue, relation, false);
    }

    private String canonicalEnumSet(List<String> allowed, String rawValue, String relation, boolean preserveSemicolon) {
        if (allowed == null || allowed.isEmpty() || rawValue == null || rawValue.isBlank()) return null;
        List<String> candidates = splitValues(rawValue, relation, preserveSemicolon);
        if (candidates.isEmpty()) return null;
        List<String> canonical = new ArrayList<>();
        for (String candidate : candidates) {
            String matched = null;
            for (String option : allowed) {
                if (option != null && clean(option).equalsIgnoreCase(clean(candidate))) {
                    matched = option;
                    break;
                }
            }
            if (matched == null) return null;
            canonical.add(matched);
        }
        return joinValues(canonical, relation);
    }

    private List<String> splitValues(String rawValue, String relation, boolean preserveSemicolon) {
        if ("in".equals(relation) || "not in".equals(relation)) {
            String regex = preserveSemicolon ? "[,|]" : "[,;|]";
            List<String> values = new ArrayList<>();
            for (String part : rawValue.split(regex)) {
                if (!part.trim().isEmpty()) values.add(part.trim());
            }
            return values;
        }
        return rawValue.trim().isEmpty() ? List.of() : List.of(rawValue.trim());
    }

    private String joinValues(List<String> values, String relation) {
        if (values.isEmpty()) return null;
        return ("in".equals(relation) || "not in".equals(relation)) ? String.join(",", values) : values.get(0);
    }

    private String canonicalApiName(DeviceTemplateDto template, String rawName, boolean signalOnly) {
        DeviceTemplateDto.DeviceManifest.API api = canonicalApi(template, rawName, signalOnly);
        return api != null ? api.getName() : null;
    }

    private DeviceTemplateDto.DeviceManifest.API canonicalApi(
            DeviceTemplateDto template, String rawName, boolean signalOnly) {
        if (template.getManifest() == null || rawName == null) return null;
        for (DeviceTemplateDto.DeviceManifest.API api : safeList(template.getManifest().getApis())) {
            if (api.getName() != null
                    && api.getName().equalsIgnoreCase(rawName.trim())
                    && (!signalOnly || Boolean.TRUE.equals(api.getSignal()))) {
                return api;
            }
        }
        return null;
    }

    private String canonicalContentName(DeviceTemplateDto template, String rawName) {
        if (template.getManifest() == null || rawName == null || rawName.isBlank()) return null;
        for (DeviceTemplateDto.DeviceManifest.Content content : safeList(template.getManifest().getContents())) {
            if (content.getName() != null && content.getName().equalsIgnoreCase(rawName.trim())) {
                return content.getName();
            }
        }
        return null;
    }

    private DeviceTemplateDto.DeviceManifest.InternalVariable findVariable(DeviceTemplateDto template, String rawName) {
        if (template.getManifest() == null || rawName == null) return null;
        for (DeviceTemplateDto.DeviceManifest.InternalVariable variable : safeList(template.getManifest().getInternalVariables())) {
            if (variable.getName() != null && variable.getName().equalsIgnoreCase(rawName.trim())) {
                return variable;
            }
        }
        return null;
    }

    private List<String> modes(DeviceTemplateDto template) {
        if (template.getManifest() == null) return List.of();
        return safeList(template.getManifest().getModes()).stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private String canonicalModeName(DeviceTemplateDto template, String rawName) {
        for (String mode : modes(template)) {
            if (mode.equalsIgnoreCase(rawName == null ? "" : rawName.trim())) return mode;
        }
        return null;
    }

    private List<String> modeValues(DeviceTemplateDto template, String modeName) {
        List<String> modeNames = modes(template);
        int modeIndex = -1;
        for (int i = 0; i < modeNames.size(); i++) {
            if (modeNames.get(i).equalsIgnoreCase(modeName)) {
                modeIndex = i;
                break;
            }
        }
        if (modeIndex < 0) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String state : workingStates(template)) {
            String[] parts = state.split(";", -1);
            if (modeNames.size() == 1 && parts.length == 1) {
                values.add(state);
            } else if (parts.length == modeNames.size() && modeIndex < parts.length && !parts[modeIndex].isBlank()) {
                values.add(parts[modeIndex]);
            }
        }
        return new ArrayList<>(values);
    }

    private List<String> workingStates(DeviceTemplateDto template) {
        if (template.getManifest() == null) return List.of();
        List<String> states = new ArrayList<>();
        for (DeviceTemplateDto.DeviceManifest.WorkingState state : safeList(template.getManifest().getWorkingStates())) {
            if (state.getName() != null && !state.getName().isBlank()) {
                states.add(state.getName());
            }
        }
        return states;
    }

    private String canonicalState(DeviceTemplateDto template, String rawState) {
        for (String state : workingStates(template)) {
            if (state.equalsIgnoreCase(rawState == null ? "" : rawState.trim())) {
                return state;
            }
        }
        return null;
    }

    private String defaultState(DeviceTemplateDto template) {
        if (template.getManifest() != null && template.getManifest().getInitState() != null
                && !template.getManifest().getInitState().isBlank()) {
            return template.getManifest().getInitState();
        }
        List<String> states = workingStates(template);
        return states.isEmpty() ? "Working" : states.get(0);
    }

    private boolean hasStateMachine(DeviceTemplateDto template) {
        return template != null && template.getManifest() != null
                && template.getManifest().getModes() != null
                && !template.getManifest().getModes().isEmpty()
                && !workingStates(template).isEmpty();
    }

    private List<Map<String, Object>> templateSnapshots(List<Map<String, Object>> devices, TemplateCatalog catalog) {
        Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
        for (Map<String, Object> device : devices) {
            DeviceTemplateDto template = catalog.resolve(String.valueOf(device.get("templateName")));
            if (template == null) continue;
            String name = templateName(template);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("manifest", template.getManifest());
            snapshots.put(name, row);
        }
        return new ArrayList<>(snapshots.values());
    }

    private Map<String, Object> emptyScene() {
        Map<String, Object> scene = new LinkedHashMap<>();
        scene.put("schema", SCENE_SCHEMA);
        scene.put("version", SCENE_VERSION);
        scene.put("templates", List.of());
        scene.put("devices", List.of());
        scene.put("environmentVariables", List.of());
        scene.put("rules", List.of());
        scene.put("specs", List.of());
        return scene;
    }

    private String stripCodeFence(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) cleaned = cleaned.substring(firstNewline).trim();
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```")).trim();
        }
        return cleaned;
    }

    private int arraySize(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    private boolean isProvided(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() && !node.asText("").trim().isEmpty();
    }

    private boolean isPresentNode(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull();
    }

    private String normalizeRelation(String relation) {
        String normalized = SmvRelationUtils.normalizeRelation(relation);
        return normalized != null && SmvRelationUtils.isSupportedRelation(normalized) ? normalized : null;
    }

    private boolean isEnumRelation(String relation) {
        return "=".equals(relation) || "!=".equals(relation) || "in".equals(relation) || "not in".equals(relation);
    }

    private String languageInstruction(String language) {
        if ("zh-CN".equals(language)) {
            return "- 输出语言: 简体中文，scenarioName/rationale/rules.name 使用简体中文。";
        }
        return "- Output language: English for scenarioName, rationale, and rule names.";
    }

    private String message(String language, String key, int devices, int rules, int specs) {
        if ("noTemplates".equals(key)) {
            return "zh-CN".equals(language)
                    ? "暂无可用设备模板，无法生成可导入场景草案。"
                    : "No device templates are available, so an importable scene draft cannot be generated.";
        }
        if ("empty".equals(key)) {
            return "zh-CN".equals(language)
                    ? "AI 输出经过校验后没有可导入的设备，因此未生成场景草案。"
                    : "No importable devices remained after validation, so no scene draft was generated.";
        }
        if ("noCandidates".equals(key)) {
            return "zh-CN".equals(language)
                    ? "AI 本次没有返回任何场景项目候选；后端没有过滤项目。请调整需求或重试。"
                    : "AI returned no scene-item candidates in this run; the backend filtered nothing. Refine the request or retry.";
        }
        return "zh-CN".equals(language)
                ? String.format("已生成通过结构与设备能力校验的可导入场景草案：%d 个设备、%d 条规则、%d 条规约。尚未进行形式化验证，也未修改画布。", devices, rules, specs)
                : String.format("Generated an importable scene draft that passed structure and capability checks: %d devices, %d rules, and %d specs. It has not been formally verified and has not changed the board.", devices, rules, specs);
    }

    private String validatedRationale(
            String language,
            int devices,
            int rules,
            int specs,
            int semanticWarningCount) {
        if ("zh-CN".equals(language)) {
            String summary = String.format(
                    "最终规范化草案包含 %d 个设备、%d 条自动化规则和 %d 条规约。此摘要只描述校验后保留的内容，不表示草案已满足用户需求或已通过形式化验证。",
                    devices, rules, specs);
            return semanticWarningCount == 0
                    ? summary
                    : summary + String.format(" 应用前请审阅 %d 条语义覆盖提示。", semanticWarningCount);
        }
        String summary = String.format(
                "The final canonical draft contains %d device(s), %d automation rule(s), and %d specification(s). This summary describes only retained content; it does not claim that the draft satisfies the user requirement or has passed formal verification.",
                devices, rules, specs);
        return semanticWarningCount == 0
                ? summary
                : summary + String.format(" Review %d semantic coverage warning(s) before applying it.", semanticWarningCount);
    }

    private void attachDraftStatus(ScenarioRecommendationResponseDto result,
                                   String language,
                                   AiScenarioDraftStore.DraftSaveResult saveResult) {
        result.setDraftStored(saveResult.draftStored());
        result.setPreviousDraftRetained(saveResult.previousDraftRetained());
        if (!saveResult.previousDraftRetained()) return;
        String currentMessage = result.getMessage().trim();
        String warning = "zh-CN".equals(language)
                ? "本次结果没有替换草稿；会话中上一次有效草稿仍被保留，“应用最新草稿”将应用上一次草稿。"
                : "This result did not replace the draft. The previous valid draft is still retained, so applying the latest draft would apply that earlier draft.";
        result.setMessage(currentMessage.isEmpty() ? warning : currentMessage + " " + warning);
    }

    private ScenarioRecommendationResponseDto prepareDeliverableDraftResult(Long userId,
                                                                           String chatSessionId,
                                                                           String language,
                                                                           ScenarioRecommendationResponseDto result) {
        if (chatSessionId == null || chatSessionId.isBlank()) return result;
        JsonNode scene = objectMapper.valueToTree(result.getScene());
        boolean canStoreDraft = scene.isObject()
                && scene.path("devices").isArray()
                && !scene.path("devices").isEmpty();
        boolean previousDraftExists = draftStore.latestDraft(userId, chatSessionId).isPresent();
        AiScenarioDraftStore.DraftSaveResult prospectiveSave = canStoreDraft
                ? new AiScenarioDraftStore.DraftSaveResult(true, false)
                : new AiScenarioDraftStore.DraftSaveResult(false, previousDraftExists);
        attachDraftStatus(result, language, prospectiveSave);

        int resultBytes;
        try {
            resultBytes = objectMapper.writeValueAsString(result).getBytes(StandardCharsets.UTF_8).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the validated scenario result", e);
        }
        int maxResultBytes = chatExecutionConfig.getMaxToolResultBytes();
        if (resultBytes > maxResultBytes) {
            String message = previousDraftExists
                    ? "The validated scenario result exceeded the safe tool-result limit and was not stored. "
                            + "The previous visible draft remains available; request a smaller scene before replacing it."
                    : "The validated scenario result exceeded the safe tool-result limit and was not stored. "
                            + "Request a smaller scene before applying anything.";
            throw new ResultTooLargeException(message, Map.of(
                    "resultStatus", "RESULT_UNAVAILABLE", "resultAvailable", false,
                    "mutationMayHaveCommitted", false, "draftStored", false,
                    "previousDraftRetained", previousDraftExists,
                    "resultBytes", resultBytes, "maxResultBytes", maxResultBytes));
        }

        if (canStoreDraft) {
            AiScenarioDraftStore.DraftSaveResult actualSave = draftStore.saveDraft(
                    userId, chatSessionId, result.getScenarioName(), scene);
            if (!actualSave.equals(prospectiveSave)) {
                throw new IllegalStateException("Scenario draft storage did not match the validated scene.");
            }
        }
        return result;
    }

    public static final class ResultTooLargeException extends BaseException {
        private final Map<String, Object> details;

        private ResultTooLargeException(String message, Map<String, Object> details) {
            super(413, message);
            this.details = Map.copyOf(details);
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }

    private int intOrDefault(JsonNode node, int fallback, int min, int max) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            return fallback;
        }
        int value = node.intValue();
        return value >= min && value <= max ? value : fallback;
    }

    private double doubleOrDefault(JsonNode node, double fallback) {
        return node.isNumber() && Double.isFinite(node.asDouble()) ? node.asDouble() : fallback;
    }

    private String text(JsonNode node, String fallback) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.asText("").trim() : fallback;
    }

    private String templateName(DeviceTemplateDto template) {
        if (template.getManifest() != null && template.getManifest().getName() != null
                && !template.getManifest().getName().isBlank()) {
            return template.getManifest().getName();
        }
        return template.getName();
    }

    /**
     * The Environment Pool's default for a SHARED value: first enum literal, else the lower bound.
     *
     * <p>Deliberately not the local rule. A shared value's initial value is scenario authority owned by the
     * pool, and several devices may write it, so no single device's starting state may dictate it. This
     * method used to serve both scopes, which is how one label came to describe two rules.</p>
     */
    private String defaultSharedVariableValue(DeviceTemplateDto.DeviceManifest.InternalVariable variable) {
        if (variable == null) return "";
        if (variable.getValues() != null && !variable.getValues().isEmpty()) return String.valueOf(variable.getValues().get(0));
        // Both bounds, like every other defaulter (BoardStorageServiceImpl.defaultEnvironmentValue,
        // NusmvEnvironmentPool:143, NodeServiceImpl:334): a lone LowerBound is not a domain the schema admits,
        // so defaulting from it invented a value for a variable that has none.
        if (variable.getLowerBound() != null && variable.getUpperBound() != null) {
            return String.valueOf(variable.getLowerBound());
        }
        return "";
    }

    private String normalizeTrust(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("trusted".equals(normalized) || "untrusted".equals(normalized)) return normalized;
        return null;
    }

    private String normalizePrivacy(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("public".equals(normalized) || "private".equals(normalized)) return normalized;
        return null;
    }

    private String defaultTrust(String value) {
        return value == null ? "untrusted" : value;
    }

    private String defaultPrivacy(String value) {
        return value == null ? "public" : value;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replace(" ", "");
    }

    private static final class CandidateStats {
        private int rawCandidateCount;
        private int inspectedCount;
        private int validatedCount;
    }

    private record EnvironmentDomainSource(
            String literalName,
            String deviceLabel,
            DeviceTemplateDto.DeviceManifest.InternalVariable definition
    ) {}

    private record DeviceContext(String id, String label, DeviceTemplateDto template) {}

    private record CapabilityValue(String key, String relation, String value) {}

    private record SpecConditionGroup(String side, List<Map<String, Object>> conditions) {}

    private final class TemplateCatalog {
        private final Map<String, DeviceTemplateDto> byName = new HashMap<>();

        private TemplateCatalog(List<DeviceTemplateDto> templates) {
            for (DeviceTemplateDto template : templates) {
                if (template.getName() != null) byName.put(template.getName().toLowerCase(Locale.ROOT), template);
                String manifestName = templateName(template);
                if (manifestName != null) byName.put(manifestName.toLowerCase(Locale.ROOT), template);
            }
        }

        private DeviceTemplateDto resolve(String name) {
            if (name == null) return null;
            return byName.get(name.trim().toLowerCase(Locale.ROOT));
        }
    }
}
