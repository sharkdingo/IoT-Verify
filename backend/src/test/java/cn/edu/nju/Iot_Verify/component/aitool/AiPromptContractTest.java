package cn.edu.nju.Iot_Verify.component.aitool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recommendation prompt is the model's only view of what the backend accepts, and nothing compiles it.
 *
 * <p>The incidents, all found by hand rather than by the suite: the scene skeleton advertised
 * {@code "version": 4} while the codec constant was 5; the similarity skeleton carried
 * {@code "similarity": 0.0-1.0}, which is not JSON, where the parser demands a number; and several
 * validator rejection paths had no line in the prompt at all, so the model could not have avoided them.
 *
 * <p>Source-level on purpose. The defect is prose inside a string literal that compiles, and the per-tool
 * tests stub {@code PromptCompletionService}, so they never read the prompt they would have sent.
 */
class AiPromptContractTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "cn", "edu", "nju", "Iot_Verify");

    private static final Map<String, Path> PROMPT_SOURCES = new LinkedHashMap<>();

    static {
        PROMPT_SOURCES.put("RecommendRulesTool",
                MAIN.resolve("service/recommendation/RuleRecommendationService.java"));
        PROMPT_SOURCES.put("RecommendSpecificationsTool",
                MAIN.resolve("service/recommendation/SpecificationRecommendationService.java"));
        PROMPT_SOURCES.put("RecommendRelatedDevicesTool",
                MAIN.resolve("service/recommendation/DeviceRecommendationService.java"));
        PROMPT_SOURCES.put("RecommendScenarioTool",
                MAIN.resolve("service/recommendation/ScenarioRecommendationService.java"));
        PROMPT_SOURCES.put("CheckRuleSimilarityTool",
                MAIN.resolve("service/recommendation/RuleCheckService.java"));
    }

    private static String source(String tool) throws IOException {
        Path path = PROMPT_SOURCES.get(tool);
        assertTrue(path != null && Files.exists(path),
                tool + ": prompt source moved; update PROMPT_SOURCES rather than dropping the tool");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * The {@code SYSTEM_PROMPT} text block only — never a fixed-size window. The first version of the
     * sibling {@code languageInstruction} guard used a 700-character window, ran past the method it meant
     * to read into the next one, and reported every tool as an offender. A guard whose failures are all
     * false positives gets deleted, not fixed.
     */
    private static String systemPrompt(String tool) throws IOException {
        String text = source(tool);
        int at = text.indexOf("SYSTEM_PROMPT = ");
        assertTrue(at > 0, tool + ": no SYSTEM_PROMPT declaration; the prompt idiom changed");
        int open = text.indexOf("\"\"\"", at);
        int close = text.indexOf("\"\"\"", open + 3);
        assertTrue(open > 0 && close > open, tool + ": SYSTEM_PROMPT is no longer a text block");
        String prompt = text.substring(open + 3, close);
        assertTrue(prompt.length() > 300,
                tool + ": extracted only " + prompt.length() + " prompt characters — the scan is broken, "
                        + "so a pass proves nothing");
        return prompt;
    }

    @Test
    void deviceRecommendationsDoNotInventIndependentSharedInputs() throws IOException {
        String prompt = systemPrompt("RecommendRelatedDevicesTool");

        assertTrue(prompt.contains("deviceLocal=false 的同名变量")
                        && prompt.contains("共享同一个 Environment Pool 值"),
                "the model must know that shared variable names identify one scene value");
        assertTrue(prompt.contains("标签、位置和建议部署区域不能产生独立的分区读数"),
                "moving or renaming an instance must not be presented as a new sensor input");
        assertTrue(prompt.contains("可以推荐同模板的多个实例")
                        && prompt.contains("独立状态、本地变量或 API"),
                "same-template instances remain valid when their modeled capabilities add value");
        assertFalse(prompt.contains("例如 Bedroom Motion Sensor 和 Hallway Motion Sensor"),
                "the prompt must not encourage room independence for the shared motion input");
    }

    /* ------------------------------------------------------------------ (e) skeletons parse as JSON */

    /**
     * Every brace-balanced skeleton in a prompt must parse as JSON once its placeholder idioms are
     * substituted.
     *
     * <p>{@code CheckRuleSimilarityTool} shipped {@code "similarity": 0.0-1.0} — a range written where the
     * parser reads {@code asDouble()}. The prompt was demonstrating a shape the model could not copy without
     * producing invalid JSON, and the failure surfaced only as a whole call being discarded at runtime.
     *
     * <p>Two placeholder idioms are legitimate and substituted rather than reported: {@code null或"..."}
     * (an optional field) and {@code [ &lt;Condition&gt;, ... ]} (a shape defined in a second skeleton).
     * Anything else must be valid JSON as written, because that is what the model copies.
     */
    @Test
    @DisplayName("every JSON skeleton in a prompt parses as JSON")
    void promptSkeletonsAreValidJson() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<String> offenders = new ArrayList<>();
        int skeletons = 0;

        for (String tool : PROMPT_SOURCES.keySet()) {
            for (String skeleton : jsonSkeletons(systemPrompt(tool))) {
                skeletons++;
                String substituted = skeleton
                        .replaceAll("null或\"[^\"]*\"", "null")
                        .replaceAll("\\[\\s*<[^>]*>\\s*,\\s*\\.\\.\\.\\s*]", "[]");
                try {
                    mapper.readTree(substituted);
                } catch (Exception e) {
                    offenders.add(tool + ": skeleton starting \"" + firstLine(skeleton)
                            + "\" is not JSON — " + e.getMessage());
                }
            }
        }

        /*
         * Coverage floor: every prompt must contribute at least one skeleton, because a scan that finds none
         * asserts nothing and a moved prompt would report a clean catalog. Bounded by the number of prompts
         * rather than the current skeleton count — collapsing three duplicated condition blocks into one
         * shared definition is a legitimate edit that must not read as "the scan is broken".
         */
        assertTrue(skeletons >= PROMPT_SOURCES.size(),
                "expected at least one JSON skeleton per prompt (" + PROMPT_SOURCES.size() + "), found "
                        + skeletons + " — the skeleton scan is broken, so an empty offender list proves nothing");
        assertEquals(List.of(), offenders,
                "a prompt shows the model a skeleton it cannot copy as valid JSON: " + offenders);
    }

    /** Brace-balanced blocks that start on their own line, which is how every prompt writes a skeleton. */
    private static List<String> jsonSkeletons(String prompt) {
        List<String> blocks = new ArrayList<>();
        String[] lines = prompt.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("{")) continue;
            int depth = 0;
            StringBuilder block = new StringBuilder();
            int j = i;
            for (; j < lines.length; j++) {
                block.append(lines[j]).append('\n');
                depth += count(lines[j], '{') - count(lines[j], '}');
                if (depth == 0) break;
            }
            if (depth == 0) {
                blocks.add(block.toString());
                i = j;
            }
        }
        return blocks;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) n++;
        }
        return n;
    }

    private static String firstLine(String block) {
        String[] lines = block.split("\n");
        return lines.length > 1 ? lines[1].trim() : lines[0].trim();
    }

    /* ------------------------------------------------------- (d) literals match their Java constant */

    /**
     * A literal the prompt states about the payload the backend writes must equal the Java constant.
     *
     * <p>{@code RecommendScenarioTool}'s skeleton said {@code "version": 4} while {@code SCENE_VERSION} was
     * 5. The prompt is the only place the model learns the envelope, so it dutifully returned version-4
     * drafts that the codec then refused; the tool's own tests build their fixtures from the constant and
     * never saw it. The pairs are discovered from {@code scene.put("key", CONSTANT)} rather than listed, so
     * a renamed constant cannot quietly leave this check behind.
     */
    @Test
    @DisplayName("a constant named in a prompt skeleton equals the Java constant the backend writes")
    void promptConstantsMatchTheirJavaConstants() throws IOException {
        String tool = "RecommendScenarioTool";
        String text = source(tool);
        String prompt = systemPrompt(tool);

        Map<String, String> constants = new LinkedHashMap<>();
        Matcher declaration = Pattern.compile(
                "static final (?:int|String)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*([^;]+);").matcher(text);
        while (declaration.find()) {
            constants.put(declaration.group(1), resolveConstant(declaration.group(2).trim()));
        }

        List<String> offenders = new ArrayList<>();
        int pairs = 0;
        Matcher put = Pattern.compile("\\.put\\(\\s*\"([A-Za-z]+)\"\\s*,\\s*([A-Z][A-Z0-9_]+)\\s*\\)")
                .matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (put.find()) {
            String field = put.group(1);
            String constant = put.group(2);
            String value = constants.get(constant);
            if (value == null || !seen.add(field + "=" + constant)) continue;

            Matcher stated = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?([^\",\\n]+)\"?")
                    .matcher(prompt);
            boolean statedAtAll = false;
            while (stated.find()) {
                statedAtAll = true;
                String claim = stated.group(1).trim();
                if (!claim.equals(value)) {
                    offenders.add(tool + ": prompt says " + field + "=" + claim
                            + " but " + constant + " is " + value);
                }
            }
            if (statedAtAll) pairs++;
        }

        assertTrue(pairs >= 2,
                "expected the scene envelope's schema and version to be stated in the prompt, matched "
                        + pairs + " — the skeleton or the put() idiom changed, so a pass proves nothing");
        assertEquals(List.of(), offenders,
                "a prompt advertises an envelope value the backend does not write: " + offenders);
    }

    /**
     * The literal a constant's initializer ultimately denotes, following one hop through another class.
     *
     * <p>Reading only the initializer text was enough while every constant was its own literal, but that
     * made the check hostile to deduplication: pointing {@code SCENE_SCHEMA} at the format's single
     * owner ({@code PortableSceneFormat.SCHEMA}) made this read the *expression* as the expected value
     * and report the prompt as wrong when nothing about it had changed. Loosening the comparison would
     * have been the wrong repair — the guard's whole job is to compare against what the backend writes —
     * so it resolves the reference instead. A reference it cannot resolve is returned unchanged, which
     * still fails loudly rather than passing vacuously.</p>
     */
    private static String resolveConstant(String initializer) {
        Matcher reference = Pattern.compile("^([A-Z][A-Za-z0-9_]*)\\.([A-Z][A-Z0-9_]*)$")
                .matcher(initializer.trim());
        if (!reference.find()) {
            return unquote(initializer);
        }
        try {
            String owner = findSource(reference.group(1));
            Matcher declared = Pattern.compile(
                            "static final (?:int|String)\\s+" + Pattern.quote(reference.group(2))
                                    + "\\s*=\\s*([^;]+);")
                    .matcher(owner);
            return declared.find() ? unquote(declared.group(1).trim()) : initializer.trim();
        } catch (IOException | IllegalArgumentException exception) {
            return initializer.trim();
        }
    }

    /** Locates a class's source anywhere under {@code src/main/java} by simple name. */
    private static String findSource(String simpleName) throws IOException {
        try (var paths = Files.walk(Path.of("src", "main", "java"))) {
            Path source = paths
                    .filter(path -> path.getFileName().toString().equals(simpleName + ".java"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no source for " + simpleName));
            return Files.readString(source);
        }
    }

    private static String unquote(String literal) {
        String value = literal.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value.replace("_", "");
    }

    /* --------------------------------------------- (c) every rejection reason is warned about up front */

    /**
     * Every reason a candidate can be rejected must be something the prompt told the model to avoid.
     *
     * <p>This is the class of drift that costs the user a wasted round: the backend discards a candidate
     * for a constraint the model was never given, the user sees a filtered item, and no amount of retrying
     * helps. Several such paths existed — including {@code conditionSideField} and the untrusted-source
     * template-7 shape, whose rules the prompt did not state.
     *
     * <p>The table below is the only hand-written part, and it cannot rot silently in either direction. Each
     * value must still occur in the prompt (so deleting a warning line fails here), and the key set is
     * compared against the reason codes discovered from the tool's own {@code validationReason} /
     * {@code adjustmentReason} switch (so adding a rejection without a warning fails here too). Mapping
     * code→prompt cannot be derived mechanically — the warning is Chinese prose and the code is an
     * identifier — but requiring the mapping to be total in both directions is what keeps it honest.
     */
    @Test
    @DisplayName("no candidate rejection reason exists that the prompt never warned about")
    void everyRejectionReasonIsWarnedAboutInThePrompt() throws IOException {
        Map<String, Map<String, String>> warnings = new LinkedHashMap<>();
        warnings.put("RecommendRulesTool", RULE_WARNINGS);
        warnings.put("RecommendSpecificationsTool", SPEC_WARNINGS);
        warnings.put("RecommendRelatedDevicesTool", DEVICE_WARNINGS);
        warnings.put("RecommendScenarioTool", SCENARIO_WARNINGS);

        List<String> offenders = new ArrayList<>();
        int codes = 0;

        for (Map.Entry<String, Map<String, String>> entry : warnings.entrySet()) {
            String tool = entry.getKey();
            Map<String, String> table = entry.getValue();
            String prompt = systemPrompt(tool);
            Set<String> declared = reasonCodes(source(tool));

            /*
             * A floor of 1, not the current count. The purpose is to catch a *broken scan* — a renamed reason
             * switch would otherwise report an empty offender list as success. Pinning it at the present number
             * instead makes a legitimate product change (removing one rejection path) fail here, reporting
             * "the scan is broken" for a tool whose scan works fine. The aggregate floor below carries the
             * real coverage assertion.
             */
            assertTrue(!declared.isEmpty(),
                    tool + ": no reason codes found — the reason-switch scan is broken, so a pass proves nothing");
            codes += declared.size();

            for (String code : declared) {
                if (!table.containsKey(code)) {
                    offenders.add(tool + ": reason code \"" + code + "\" has no prompt warning; add the "
                            + "constraint to SYSTEM_PROMPT and map it here");
                }
            }
            for (Map.Entry<String, String> row : table.entrySet()) {
                if (!declared.contains(row.getKey())) {
                    offenders.add(tool + ": table maps \"" + row.getKey()
                            + "\", which the tool no longer reports; delete the row");
                } else if (row.getValue() != null && !prompt.contains(row.getValue())) {
                    offenders.add(tool + ": the prompt line warning about \"" + row.getKey()
                            + "\" is gone (expected to contain \"" + row.getValue() + "\")");
                }
            }
        }

        assertTrue(codes >= 50,
                "expected the four recommendation tools to declare at least 50 reason codes, found " + codes
                        + " — the scan is probably broken, so an empty offender list proves nothing");
        assertEquals(List.of(), offenders,
                "a candidate can be rejected for a constraint the model was never given: " + offenders);
    }

    /**
     * Reason codes are the {@code case} labels of the tool's own {@code validationReason} /
     * {@code adjustmentReason} switch — the one place every code must appear to get user-facing copy.
     * Reading the emit sites instead would mean re-implementing four different call idioms and would
     * confuse a JSON field name for a reason code, which an early draft of this check did.
     */
    private static Set<String> reasonCodes(String text) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher method = Pattern.compile(
                "private String (?:validationReason|adjustmentReason)\\(.*?\\n    }", Pattern.DOTALL)
                .matcher(text);
        while (method.find()) {
            Matcher label = Pattern.compile("case \"([A-Za-z]+)\"").matcher(method.group());
            while (label.find()) {
                codes.add(label.group(1));
            }
        }
        return codes;
    }

    /**
     * A {@code null} value means the code cannot be warned about because it is not a constraint the model
     * can satisfy: {@code parseFailed} fires when the candidate is not an object at all, and the
     * {@code *Applied}/{@code *Normalized} codes report a deterministic backend adjustment to a candidate
     * that was kept. Every other code needs a prompt line.
     */
    private static final Map<String, String> RULE_WARNINGS = new LinkedHashMap<>();
    private static final Map<String, String> SPEC_WARNINGS = new LinkedHashMap<>();
    private static final Map<String, String> DEVICE_WARNINGS = new LinkedHashMap<>();
    private static final Map<String, String> SCENARIO_WARNINGS = new LinkedHashMap<>();

    static {
        RULE_WARNINGS.put("parseFailed", null);
        RULE_WARNINGS.put("apiEventSyntaxNormalized", "写成语义等价的 relation=\"=\" 且 value=\"TRUE\" 也会被接受");
        RULE_WARNINGS.put("missingRuleFields", "\"conditions\": [");
        RULE_WARNINGS.put("missingRuleName", "name 是应用后实际保存的规则名称");
        RULE_WARNINGS.put("invalidRuleReason", "不超过 1000 个字符");
        RULE_WARNINGS.put("emptyConditionsOrCommand", "\"command\": {");
        RULE_WARNINGS.put("conditionMissingFields", "conditions中的targetType必须明确为");
        RULE_WARNINGS.put("unknownConditionDevice", "conditions中的deviceId必须使用设备列表中的 deviceId");
        RULE_WARNINGS.put("unknownApiSignal", "只能使用设备列表里的 apiSignals");
        RULE_WARNINGS.put("conditionMissingValue", "必须填写 relation 和 value");
        RULE_WARNINGS.put("invalidRelation", "只能使用 =、!=、in、not in");
        RULE_WARNINGS.put("invalidApiEventSyntax", "只写一半、写 FALSE 或用其他关系符");
        RULE_WARNINGS.put("invalidConditionCapability", "attribute必须是该设备实际存在的");
        RULE_WARNINGS.put("commandMissingFields", "command中的action必须是该设备实际存在的API名称");
        RULE_WARNINGS.put("unknownCommandDevice", "必须来自设备列表的 deviceId 字段");
        RULE_WARNINGS.put("unknownActionApi", "command中的action必须是该设备实际存在的API名称");
        RULE_WARNINGS.put("unknownContentDevice", "contentDevice 与 content 必须同时为 null");
        RULE_WARNINGS.put("incompleteContentPayload", "contentDevice 与 content 必须同时为 null");
        RULE_WARNINGS.put("unknownContent", "content 必须来自该内容设备的 contents 列表");
        RULE_WARNINGS.put("actionDoesNotAcceptContent", "acceptsContent=true");
        RULE_WARNINGS.put("contradictoryConditionGroup", "必须能在模板声明的状态/变量定义域中同时成立");
        RULE_WARNINGS.put("commandPrestateIncompatible", "如果 command API 声明了非空 StartState");
        RULE_WARNINGS.put("commandPrestateUnreachable", "命令 API 的 StartState 同样必须可达");
        RULE_WARNINGS.put("unreachableConditionGroup", "经 capabilities 的 Transitions 与已声明 API 到达");
        RULE_WARNINGS.put("unknownCandidateField", "自行发明的字段");

        SPEC_WARNINGS.put("invalidSpecificationName", "简短、具体的规约名称（必填，1到100字符）");
        SPEC_WARNINGS.put("parseFailed", null);
        SPEC_WARNINGS.put("invalidTemplateId", "templateId 必须严格枚举为 \"1\" 到 \"7\"");
        SPEC_WARNINGS.put("missingSpecificationRationale", "在 rationale 中解释建议依据");
        SPEC_WARNINGS.put("invalidTemplateShape", "模板形状必须严格匹配");
        SPEC_WARNINGS.put("conditionMissingFields", "必须引用该设备实际存在的 states、modes、variables 或 APIs");
        SPEC_WARNINGS.put("conditionMissingValue", "api 可省略，默认 =");
        SPEC_WARNINGS.put("conditionMissingVariableSource", "variable 条件必须给出 variableSource=environment|reported");
        SPEC_WARNINGS.put("invalidRelation", "以及枚举变量只能使用 =、!=、in、not in");
        SPEC_WARNINGS.put("unknownDevice", "推荐中 deviceId 必须准确引用设备实例 id");
        SPEC_WARNINGS.put("currentStateKey", "禁止使用 \"currentState\" 作为 key");
        SPEC_WARNINGS.put("invalidConditionCapability", "必须引用该设备实际存在的 states、modes、variables 或 APIs");
        SPEC_WARNINGS.put("invalidUntrustedSourceSafetyCondition", "不得在 A 中直接写 trust/privacy");
        SPEC_WARNINGS.put("contradictoryConditionGroup", "必须在模板声明的合法状态和变量定义域中存在共同满足值");
        SPEC_WARNINGS.put("unreachableConditionGroup", "经 capabilities 的 Transitions 与已声明 API 到达");
        SPEC_WARNINGS.put("unknownCandidateField", "自行发明的字段");
        SPEC_WARNINGS.put("conditionSideField", "条件对象中禁止出现 side 字段");

        DEVICE_WARNINGS.put("parseFailed", null);
        DEVICE_WARNINGS.put("missingTemplateName", "推荐模板必须是系统中已加载的真实模板名称");
        DEVICE_WARNINGS.put("unknownTemplate", "推荐模板必须是系统中已加载的真实模板名称");
        DEVICE_WARNINGS.put("duplicateDeviceInstance", "不要推荐与现有设备完全相同建议部署区域/用途的重复实例");
        DEVICE_WARNINGS.put("invalidInitialRuntime",
                "initialVariables/initialPrivacies 只能使用模板中 IsInside=true 的本地变量");

        SCENARIO_WARNINGS.put("invalidSpecName", "简短、具体的规约名称（必填，1到100字符）");
        SCENARIO_WARNINGS.put("deviceDefaultsApplied", null);
        SCENARIO_WARNINGS.put("environmentDefaultsApplied", null);
        SCENARIO_WARNINGS.put("missingEnvironmentAdded", null);
        SCENARIO_WARNINGS.put("apiEventSyntaxNormalized", "会被规范化并记入 adjustedItems");
        SCENARIO_WARNINGS.put("unknownTemplate", "设备实例必须来自可用模板");
        SCENARIO_WARNINGS.put("invalidDeviceId", "devices[].id 只是本次回答内供规则/规约关联设备的临时别名");
        SCENARIO_WARNINGS.put("invalidDeviceRuntime",
                "只有同时声明 Modes 和 WorkingStates 的模板才填写 state/currentStateTrust/currentStatePrivacy");
        SCENARIO_WARNINGS.put("duplicateDeviceId", "devices[].id 只是本次回答内供规则/规约关联设备的临时别名");
        SCENARIO_WARNINGS.put("invalidDeviceLabel", "\"label\": \"用户可读名称\"");
        SCENARIO_WARNINGS.put("environmentDomainConflict",
                "environmentVariables 必须列出保留设备模板声明或影响的每个共享变量");
        SCENARIO_WARNINGS.put("invalidDeviceLayout", "\"position\": {\"x\": 0, \"y\": 0}");
        SCENARIO_WARNINGS.put("missingEnvironmentName", "\"name\": \"真实环境变量名\"");
        SCENARIO_WARNINGS.put("unknownEnvironmentVariable",
                "environmentVariables 必须列出保留设备模板声明或影响的每个共享变量");
        SCENARIO_WARNINGS.put("duplicateEnvironmentVariable",
                "environmentVariables 必须列出保留设备模板声明或影响的每个共享变量");
        SCENARIO_WARNINGS.put("invalidEnvironmentValue", "不得猜测模板范围外的值");
        SCENARIO_WARNINGS.put("invalidEnvironmentTrust", "\"真实环境变量名\", \"value\": \"初始值\", \"trust\"");
        SCENARIO_WARNINGS.put("invalidEnvironmentPrivacy", "\"trust\": \"trusted|untrusted\", \"privacy\"");
        SCENARIO_WARNINGS.put("invalidRuleSources", "itemType=variable|mode|state 时必须给出 relation 和 value");
        SCENARIO_WARNINGS.put("unknownRuleTarget", "规则和规约必须引用同一个 devices 列表里的设备实例 id");
        SCENARIO_WARNINGS.put("unknownRuleAction", "规则只能调用模板里真实存在的 API");
        SCENARIO_WARNINGS.put("invalidRuleContent", "content 必须来自对应设备模板的 Contents");
        SCENARIO_WARNINGS.put("ruleActionDoesNotAcceptContent", "目标 API 必须声明 AcceptsContent=true");
        SCENARIO_WARNINGS.put("contradictoryRuleConditions", "每条规则的触发条件必须可同时满足");
        SCENARIO_WARNINGS.put("ruleCommandPrestateIncompatible", "与目标 API 的非空 StartState 兼容");
        SCENARIO_WARNINGS.put("ruleCommandPrestateUnreachable", "命令 API 的 StartState 同样必须可达");
        SCENARIO_WARNINGS.put("unreachableRuleConditions", "经 capabilities 的 Transitions 与已声明 API 到达");
        SCENARIO_WARNINGS.put("invalidSpecTemplateId", "\"templateId\": \"1|2|3|4|5|6|7\"");
        SCENARIO_WARNINGS.put("invalidSpecConditions", "规约条件只能使用 state、mode、variable、api、trust、privacy");
        SCENARIO_WARNINGS.put("conditionDeviceMissing", "规则和规约必须引用同一个 devices 列表里的设备实例 id");
        SCENARIO_WARNINGS.put("conditionTargetUnsupported", "规约条件只能使用 state、mode、variable、api、trust、privacy");
        SCENARIO_WARNINGS.put("conditionPropertyScopeMissing", "只有 trust/privacy 条件填写 propertyScope=state|variable");
        SCENARIO_WARNINGS.put("conditionUnexpectedPropertyScope", "其他条件必须省略不适用的 propertyScope/variableSource");
        SCENARIO_WARNINGS.put("conditionVariableSourceMissing", "只有 variable 条件填写 variableSource=environment|reported");
        SCENARIO_WARNINGS.put("conditionEnvironmentSourceInvalid", "deviceLocal=false 且 reads=true");
        SCENARIO_WARNINGS.put("conditionUnexpectedVariableSource", "其他条件必须省略不适用的 propertyScope/variableSource");
        SCENARIO_WARNINGS.put("conditionComparisonMissing", "非 API 条件必须给出 relation/value");
        SCENARIO_WARNINGS.put("conditionRelationUnsupported", "relation 只能使用 =、!=、<、<=、>、>=、in、not in");
        SCENARIO_WARNINGS.put("conditionCapabilityMismatch", "字段名与取值必须符合该设备 capabilities");
        SCENARIO_WARNINGS.put("invalidSpecShape",
                "每个 condition: {deviceId, targetType, key, propertyScope?, variableSource?, relation?, value?}");
        SCENARIO_WARNINGS.put("contradictorySpecConditionGroup", "每个规约的 A、IF、THEN 条件数组也必须各自可同时满足");
        SCENARIO_WARNINGS.put("unreachableSpecConditionGroup", "只有\"设备本地（deviceLocal=true）、当前值已知、且 NaturalChangeRate 为 0\"的变量才会被判不可达");
        SCENARIO_WARNINGS.put("invalidUntrustedSourceSafetyCondition",
                "templateId 7 的 aConditions 不得直接使用 trust/privacy");
    }

    /* ------------------------------- the one enum comparison that is reliable: a real Java enum */

    /**
     * The confirmation detector's prompt must offer exactly the kinds and decisions the enums declare.
     *
     * <p>The generic "every alternation in a prompt equals a Java Set" check was prototyped and rejected:
     * `relation 为 in / not in` yields the junk alternation `in|not`, the scenario prompt legitimately omits
     * `api` from one alternation, and two prompts spell their enums as Chinese prose the regex cannot see.
     * A guard whose failures are mostly false positives gets deleted rather than fixed.
     *
     * <p>This case is the exception, and the only one in the seven prompts: both sides are real Java enums in
     * UPPER_SNAKE with no prose spelling. The `ALL` and `null` tokens in the same alternation are sentinels
     * rather than enum constants, so they are excluded by name — if a third sentinel appears, this test fails
     * and someone has to say so out loud, which is the intended behaviour.
     */
    @Test
    @DisplayName("the confirmation prompt offers exactly the ConfirmationKind and DecisionType constants")
    void confirmationPromptMatchesItsEnums() throws IOException {
        String source = Files.readString(
                Paths.get("src", "main", "java", "cn", "edu", "nju", "Iot_Verify", "component", "ai",
                        "ChatConfirmationDetector.java"),
                StandardCharsets.UTF_8);

        Set<String> sentinels = Set.of("ALL", "null");
        for (String enumName : List.of("ConfirmationKind", "DecisionType")) {
            Matcher declaration = Pattern
                    .compile("enum " + enumName + " \\{([^}]*)\\}")
                    .matcher(source);
            assertTrue(declaration.find(), enumName + ": declaration not found, so a pass proves nothing");
            Set<String> declared = new TreeSet<>();
            for (String token : declaration.group(1).split(",")) {
                String name = token.trim();
                if (!name.isEmpty()) declared.add(name);
            }
            assertTrue(declared.size() >= 4,
                    enumName + ": found only " + declared + " — the enum scan is broken");

            /*
             * Scan the PROMPT TEXT, not the whole file. A first version matched UPPER_SNAKE tokens across the
             * entire source, so the enum declaration itself satisfied every expectation and the test could not
             * fail: deleting a kind from the prompt still passed. Verified by replaying that mutation.
             */
            int promptStart = source.indexOf("SYSTEM_PROMPT = \"\"\"");
            assertTrue(promptStart > 0, "ChatConfirmationDetector: SYSTEM_PROMPT not found");
            int promptEnd = source.indexOf("\"\"\";", promptStart + 20);
            assertTrue(promptEnd > promptStart, "ChatConfirmationDetector: prompt end not found");
            String promptText = source.substring(promptStart, promptEnd);
            assertTrue(promptText.length() > 300,
                    "ChatConfirmationDetector: prompt slice is only " + promptText.length()
                            + " chars — the extraction is broken, so a pass proves nothing");

            Set<String> offered = new TreeSet<>();
            Matcher spelled = Pattern.compile("\\b[A-Z][A-Z_]{3,}\\b").matcher(promptText);
            while (spelled.find()) {
                String token = spelled.group();
                if (declared.contains(token)) offered.add(token);
            }
            assertEquals(declared, offered,
                    enumName + ": the prompt and the enum disagree about which constants exist");
        }
    }

    /**
     * The template tool's description may not name a manifest field the schema forbids, and must name every
     * field the schema requires.
     *
     * <p>Both halves failed, and both were measured against the running backend rather than reasoned about:
     *
     * <ul>
     *   <li>The description instructed the model that "every EnvironmentDomain must define both labels".
     *       The schema *removed* that array — its own {@code $comment} says so — and runs with
     *       {@code additionalProperties: false}, so emitting it answered
     *       {@code 400 "property EnvironmentDomains not defined in the schema"}.</li>
     *   <li>{@code NaturalChangeRate} appeared nowhere in the description, while the schema requires it for
     *       an {@code IsInside=false} variable with integer bounds — which is every shared numeric reading,
     *       i.e. every temperature, humidity and illuminance sensor. An isolated A/B pair differing in that
     *       field alone answered {@code 400 "required property NaturalChangeRate not found"} and
     *       {@code 200}.</li>
     * </ul>
     *
     * <p>A schema description is part of the tool contract — {@code requireOnlyFields} rejects unknown keys,
     * so a description promising a removed field costs the model a guaranteed round trip, and one omitting a
     * required field costs it every attempt. The existing guards check the argument *allowlist* in one
     * direction only (every advertised argument is accepted); neither can see a description that misdescribes
     * the manifest body, which is where this whole class of defect lives.
     */
    @Test
    void templateToolDescriptionMatchesTheManifestSchema() throws IOException {
        String tool = Files.readString(
                MAIN.resolve("component/aitool/template/AddTemplateTool.java"), StandardCharsets.UTF_8);
        String schema = Files.readString(
                Paths.get("device-template-schema.json"), StandardCharsets.UTF_8);

        /*
         * Every string literal in the file, minus comment lines — not a window starting at
         * `LlmToolSpec.of(`. This tool has *two* description surfaces: the parameter-schema description
         * (where the manifest body is documented) and the tool description that follows it. The first
         * version of this check windowed from `LlmToolSpec.of(` and therefore skipped the manifest
         * documentation entirely, reporting fields as undescribed while they were described twenty lines
         * above — a guard that failed on correct code, which is as useless as one that passes on broken code.
         *
         * Comment lines are stripped because the comments here necessarily quote the fields they explain.
         */
        String described = tool.replaceAll("(?m)^\\s*//.*$", "");

        // 1. A field the schema does not define cannot be demanded. `additionalProperties: false` is in force,
        //    so naming one guarantees a 400.
        assertTrue(schema.contains("\"additionalProperties\": false"),
                "this check only holds while the schema closes its objects");
        for (String removed : List.of("EnvironmentDomains", "EnvironmentDomain")) {
            assertFalse(described.contains(removed),
                    "the description names '" + removed + "', which the schema does not define; "
                            + "emitting it is a guaranteed 400");
        }

        // 2. Every conditionally-required manifest field must be described. Collected from the schema's own
        //    `then: {required: [...]}` clauses rather than a hand-kept list, so a new requirement is caught
        //    by the same assertion.
        Set<String> conditionallyRequired = new TreeSet<>();
        Matcher required = Pattern
                .compile("\"then\"\\s*:\\s*\\{\\s*\"required\"\\s*:\\s*\\[([^]]*)]")
                .matcher(schema);
        while (required.find()) {
            for (String raw : required.group(1).split(",")) {
                String field = raw.replaceAll("[\"\\s]", "");
                if (!field.isEmpty()) conditionallyRequired.add(field);
            }
        }
        assertFalse(conditionallyRequired.isEmpty(),
                "the schema should declare conditional requirements; an empty set proves nothing");

        Set<String> undescribed = new TreeSet<>();
        for (String field : conditionallyRequired) {
            if (!described.contains(field)) undescribed.add(field);
        }
        assertEquals(Set.of(), undescribed,
                "the schema requires these manifest fields and the tool description never mentions them, "
                        + "so the model cannot author a valid manifest: " + undescribed);
    }
}
