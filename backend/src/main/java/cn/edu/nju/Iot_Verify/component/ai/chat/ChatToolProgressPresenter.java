package cn.edu.nju.Iot_Verify.component.ai.chat;

import cn.edu.nju.Iot_Verify.component.aitool.AiToolResultContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a raw AI tool result into the short, user-visible progress line shown while a chat turn
 * runs.
 *
 * <p>Purely presentational: it reads the tool's JSON payload and renders a bilingual summary,
 * deliberately reporting an absent or unconfirmed result as such rather than implying success.
 * It holds no chat-session state, which is why it lives outside {@code ChatServiceImpl}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatToolProgressPresenter {

    /**
     * Budget for one round's user-visible reasoning. Larger than a tool status line because this is
     * the channel that has to carry a decomposition, the alternatives considered, and the check —
     * the provider caps its own summary at 2000, so this is the binding limit on explanation.
     */
    private static final int REASONING_MAX_CHARS = 1600;

    private final ObjectMapper objectMapper;

    /**
     * A known tool whose payload is structurally incomplete must not be summarised as a result.
     * Exposed because the chat service applies the same check when classifying an execution.
     */
    public boolean hasValidKnownToolPayload(String functionName, JsonNode root) {
        if (AiToolResultContract.isMutationCapable(functionName)) {
            return AiToolResultContract.hasValidKnownToolPayload(functionName, root);
        }
        if ("prepare_tools".equals(functionName)) {
            if (root == null || !root.isObject() || !"toolSchemasPrepared".equals(root.path("operation").asText())
                    || !hasArray(root, "loadedTools") || root.path("loadedTools").isEmpty()
                    || nonNegativeInt(root.get("count")) == null
                    || root.path("count").intValue() != root.path("loadedTools").size()) return false;
            Set<String> names = new java.util.HashSet<>();
            for (JsonNode name : root.path("loadedTools")) {
                if (!name.isTextual() || name.textValue().isBlank() || !names.add(name.textValue())) return false;
            }
            return true;
        }
        if (!"board_overview".equals(functionName)) return true;
        return root != null && root.isObject()
                && hasArray(root, "devices")
                && hasArray(root, "rules")
                && hasArray(root, "specs")
                && hasArray(root, "edges")
                && hasArray(root, "environmentVariables");
    }

    private boolean hasArray(JsonNode root, String field) {
        return root != null && root.has(field) && root.get(field).isArray();
    }

    public String compactProgressDetail(String value) {
        return compactProgressDetail(value, 240);
    }

    public String compactProgressDetail(String value, int maxChars) {
        if (value == null) return null;
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) return compact;
        return compact.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    public String toolProgressDetail(String functionName, String toolResult, boolean preferChinese) {
        if (toolResult == null || toolResult.isBlank()) {
            return preferChinese ? "工具没有返回可用结果。" : "The tool returned no usable result.";
        }
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            if (!root.isObject() || root.path("skipped").asBoolean(false)) {
                return null;
            }
            if (AiToolResultContract.isUnavailable(root)) {
                boolean mayHaveCommitted = root.path("mutationMayHaveCommitted").asBoolean(false);
                return preferChinese
                        ? (mayHaveCommitted
                        ? "工具已经开始执行，但结果未能确认；当前状态需要重新读取，请核对后再重试。"
                        : "工具结果不可用，平台状态尚未得到确认，请重试。")
                        : (mayHaveCommitted
                        ? "The tool started, but its result could not be confirmed; reread current state before retrying."
                        : "The tool result is unavailable; current platform state was not confirmed. Retry the request.");
            }
            if (AiToolResultContract.isStructuredError(root)) {
                String errorCode = root.path("errorCode").asText("").trim();
                String code = errorCode.matches("[A-Z0-9_-]{1,64}") ? " (" + errorCode + ")" : "";
                return preferChinese
                        ? "工具未能完成该操作" + code + "，具体原因见助手回复。"
                        : "The tool could not complete the operation" + code
                        + "; see the assistant response for the specific reason.";
            }

            if ("board_overview".equals(functionName)) {
                if (!hasValidKnownToolPayload(functionName, root)) return null;
                return preferChinese
                        ? String.format("已读取画布：%d 个设备、%d 条规则、%d 条规约、%d 个共享环境变量。",
                        arraySize(root, "devices"), arraySize(root, "rules"),
                        arraySize(root, "specs"), arraySize(root, "environmentVariables"))
                        : String.format("Read the board: %d devices, %d rules, %d specifications, and %d shared environment variables.",
                        arraySize(root, "devices"), arraySize(root, "rules"),
                        arraySize(root, "specs"), arraySize(root, "environmentVariables"));
            }
            if ("prepare_tools".equals(functionName)) {
                if (!hasValidKnownToolPayload(functionName, root)) return null;
                return preferChinese
                        ? String.format("已准备 %d 项相关操作。", root.path("count").intValue())
                        : String.format("Prepared %d requested actions.", root.path("count").intValue());
            }
            if ("list_verification_runs".equals(functionName)) {
                Integer count = nonNegativeInt(root.get("count"));
                if (count == null || !hasArray(root, "runs") || arraySize(root, "runs") != count) return null;
                int unavailable = 0;
                for (JsonNode run : root.path("runs")) {
                    if (!run.path("dataAvailable").isBoolean()) return null;
                    if (!run.path("dataAvailable").booleanValue()) unavailable++;
                }
                return preferChinese
                        ? String.format("已读取 %d 条保存的验证记录，其中 %d 条无法查看；这些记录对应保存时的场景。", count, unavailable)
                        : String.format("Read %d saved verification records; %d unavailable. They describe their saved scenes.", count, unavailable);
            }
            if ("get_verification_run".equals(functionName)) {
                if (!hasArray(root, "specResults") || !root.path("modelComplete").isBoolean()) return null;
                int satisfied = 0, violated = 0, inconclusive = 0;
                for (JsonNode spec : root.path("specResults")) {
                    switch (spec.path("outcome").asText()) {
                        case "SATISFIED" -> satisfied++;
                        case "VIOLATED" -> violated++;
                        case "INCONCLUSIVE" -> inconclusive++;
                        default -> { return null; }
                    }
                }
                return preferChinese
                        ? String.format("已读取保存的验证结果：%d 项满足、%d 项违反、%d 项尚无结论；%s。", satisfied, violated, inconclusive,
                        root.path("modelComplete").booleanValue() ? "模型完整，结论仅适用于保存时的场景" : "模型不完整，不能据此判断整个场景通过")
                        : String.format("Read the saved verification result: %d satisfied, %d violated, %d inconclusive; %s.", satisfied, violated, inconclusive,
                        root.path("modelComplete").booleanValue() ? "complete model, conclusions apply only to the saved scene" : "incomplete model, no whole-scene pass can be inferred");
            }
            if ("read_run_model".equals(functionName)) {
                Integer total = nonNegativeInt(root.get("totalCharacters"));
                Integer offset = nonNegativeInt(root.get("offset"));
                Integer returned = nonNegativeInt(root.get("returnedCharacters"));
                if (total == null || offset == null || returned == null || !root.path("modelText").isTextual()
                        || !root.path("hasMore").isBoolean() || !"SAVED_RUN".equals(root.path("modelSource").asText())
                        || (long) offset + returned > total || offset > total
                        || returned != root.path("modelText").textValue().codePointCount(0, root.path("modelText").textValue().length())
                        || root.path("hasMore").booleanValue() != ((long) offset + returned < total)
                        || (root.path("hasMore").booleanValue()
                            && !Integer.valueOf(offset + returned).equals(nonNegativeInt(root.get("nextOffset"))))) return null;
                if (returned == 0) return preferChinese
                        ? "本页位于保存模型的末尾，没有可显示的内容。"
                        : "This page is at the end of the saved model; there is no text to display.";
                return preferChinese
                        ? String.format("已读取保存模型第 %d–%d 个字符（共 %d）%s。", offset + 1, offset + returned, total,
                        root.path("hasMore").booleanValue() ? "，还有后续内容" : "，已到模型末尾")
                        : String.format("Read characters %d–%d of %d in the saved model%s.", offset + 1, offset + returned, total,
                        root.path("hasMore").booleanValue() ? "; more text remains" : "; reached the model's end");
            }
            if ("list_traces".equals(functionName)) {
                Integer count = nonNegativeInt(root.get("count"));
                if (count == null || (root.has("traces")
                        && (!hasArray(root, "traces") || arraySize(root, "traces") != count))) return null;
                if (count == 0) {
                    return preferChinese ? "没有已保存的反例记录；这不代表已经通过验证。"
                            : "No saved counterexample records; this does not mean verification passed.";
                }
                Integer available = nonNegativeInt(root.get("availableCount"));
                Integer unavailable = nonNegativeInt(root.get("unavailableCount"));
                if (!hasArray(root, "traces") || available == null || unavailable == null
                        || (long) available + unavailable != count) return null;
                return preferChinese
                        ? String.format("已读取 %d 条反例记录，其中 %d 条无法查看；记录对应保存时的画布。", count, unavailable)
                        : String.format("Read %d saved counterexample records; %d unavailable. They describe the saved Board snapshot.",
                        count, unavailable);
            }
            if ("read_conversation_activity".equals(functionName)) {
                Integer count = nonNegativeInt(root.get("count"));
                if (count == null || !hasArray(root, "activities") || arraySize(root, "activities") != count
                        || !root.path("hasMore").isBoolean()) return null;
                boolean hasMore = root.path("hasMore").booleanValue();
                int drafts = 0, previews = 0, changes = 0;
                for (JsonNode activity : root.path("activities")) {
                    if (!"AVAILABLE".equals(activity.path("evidenceStatus").asText())) continue;
                    boolean changed = activity.path("appliedAction").isTextual()
                            && !activity.path("appliedAction").textValue().isBlank();
                    if (activity.path("draftStored").isBoolean() && activity.path("draftStored").booleanValue()) drafts++;
                    if (!changed && activity.path("requiresUserConfirmation").isBoolean()
                            && activity.path("requiresUserConfirmation").booleanValue()) previews++;
                    if (changed) changes++;
                }
                List<String> stages = new ArrayList<>();
                if (drafts > 0) stages.add(preferChinese ? "保存草稿 " + drafts + " 次" : drafts + " saved drafts");
                if (previews > 0) stages.add(preferChinese ? "确认预览 " + previews + " 次" : previews + " confirmation previews");
                if (changes > 0) stages.add(preferChinese ? "历史改动 " + changes + " 次" : changes + " historical changes");
                String coverage = preferChinese
                        ? String.format("已核对 %d 条已保存的会话操作记录%s。", count,
                        hasMore ? "，还有更早的记录需要读取" : "，已读到最早记录")
                        : String.format("Read %d saved conversation operation records%s.", count,
                        hasMore ? "; older records remain" : "; reached the earliest record");
                return stages.isEmpty() ? coverage : coverage
                        + (preferChinese ? "本页明确记录：" : " This page records: ")
                        + String.join(preferChinese ? "；" : "; ", stages) + (preferChinese ? "。" : ".");
            }
            if ("fix_violation".equals(functionName)) {
                Integer count = nonNegativeInt(root.get("suggestionCount"));
                if (count == null || !root.path("fixable").isBoolean()
                        || root.path("fixable").booleanValue() != (count > 0)
                        || (count > 0 && !hasArray(root, "suggestions"))
                        || (root.has("suggestions")
                            && (!hasArray(root, "suggestions") || arraySize(root, "suggestions") != count))) return null;
                String summary = preferChinese
                        ? (count == 0 ? "本次检查未提供可直接应用的方案；画布未改变。"
                        : String.format("已读取修复检查结果：%d 个候选方案，尚未应用。", count))
                        : (count == 0 ? "This analysis returned no directly applicable suggestion; the Board was not changed."
                        : String.format("Read the repair analysis: %d candidate suggestion(s); nothing was applied.", count));
                // Keep concrete thresholds visible even when an unsupported model claim is replaced
                // by this authoritative read summary. A proposal is still not a mutation receipt.
                for (int i = 0; i < count; i++) {
                    JsonNode parameters = root.path("suggestions").get(i).path("parameterAdjustments");
                    if (!parameters.isArray()) continue;
                    List<String> values = new ArrayList<>();
                    for (JsonNode parameter : parameters) {
                        if (!List.of("attribute", "relation", "originalValue", "newValue").stream()
                                .allMatch(field -> parameter.path(field).isTextual())) continue;
                        values.add(parameter.path("attribute").textValue() + " "
                                + parameter.path("relation").textValue() + " "
                                + parameter.path("originalValue").textValue() + " → "
                                + parameter.path("newValue").textValue());
                    }
                    if (!values.isEmpty()) summary += (preferChinese ? " 方案 " : " Option ")
                            + (i + 1) + ": " + String.join("; ", values) + ".";
                }
                return compactToolProgressDetail(summary);
            }
            if ("add_device".equals(functionName) && "created".equals(root.path("operation").asText())) {
                JsonNode device = root.path("device");
                String label = device.path("label").asText("").trim();
                String state = device.path("state").asText("").trim();
                int environmentChanges = arraySize(root, "environmentChanges");
                return compactToolProgressDetail(preferChinese
                        ? String.format("已创建设备%s%s；环境池变化 %d 项。",
                        quotedName(label), state.isBlank() ? "" : "，初始状态为 " + state,
                        environmentChanges)
                        : String.format("Created device%s%s with %d Environment Pool change(s).",
                        quotedName(label), state.isBlank() ? "" : " in initial state " + state,
                        environmentChanges));
            }
            if ("delete_device".equals(functionName)) {
                boolean preview = "preview".equals(root.path("operation").asText());
                JsonNode device = preview ? root.path("device") : root.path("deletedDevice");
                String label = device.path("label").asText("").trim();
                if (preview) {
                    return compactToolProgressDetail(preferChinese
                            ? String.format("已预览删除设备%s：将移除 %d 条规则、%d 条规约并改变 %d 个环境变量；尚未写入。",
                            quotedName(label), root.path("wouldRemoveRuleCount").asInt(0),
                            root.path("wouldRemoveSpecificationCount").asInt(0),
                            root.path("wouldChangeEnvironmentVariableCount").asInt(0))
                            : String.format("Previewed deletion of device%s: %d rule(s), %d specification(s), and %d environment variable(s) would be affected; nothing was written.",
                            quotedName(label), root.path("wouldRemoveRuleCount").asInt(0),
                            root.path("wouldRemoveSpecificationCount").asInt(0),
                            root.path("wouldChangeEnvironmentVariableCount").asInt(0)));
                }
                if ("deleted".equals(root.path("operation").asText())) {
                    return compactToolProgressDetail(preferChinese
                            ? String.format("已删除设备%s，并移除 %d 条规则和 %d 条规约。",
                            quotedName(label), root.path("removedRuleCount").asInt(0),
                            root.path("removedSpecificationCount").asInt(0))
                            : String.format("Deleted device%s and removed %d rule(s) and %d specification(s).",
                            quotedName(label), root.path("removedRuleCount").asInt(0),
                            root.path("removedSpecificationCount").asInt(0)));
                }
            }
            if ("manage_rule".equals(functionName)) {
                String operation = root.path("operation").asText("").trim();
                JsonNode rule = "deleted".equals(operation) ? root.path("deletedRule") : root.path("rule");
                String description = rule.path("description").asText("").trim();
                if ("preview".equals(operation)) {
                    return compactToolProgressDetail(preferChinese
                            ? "已预览规则删除影响；尚未写入，等待明确确认。"
                            : "Previewed the rule deletion impact; nothing was written and explicit confirmation is pending.");
                }
                if ("reordered".equals(operation)) {
                    return compactToolProgressDetail(preferChinese
                            ? "已调整规则执行顺序，共 " + root.path("ruleCount").asInt(0) + " 条规则。"
                            : "Reordered " + root.path("ruleCount").asInt(0) + " rule(s).");
                }
                if (Set.of("created", "updated", "deleted").contains(operation)) {
                    String action = "created".equals(operation)
                            ? (preferChinese ? "已创建规则" : "Created rule")
                            : "updated".equals(operation)
                                ? (preferChinese ? "已修改规则" : "Updated rule")
                                : (preferChinese ? "已删除规则" : "Deleted rule");
                    return compactToolProgressDetail(description.isBlank()
                            ? action + (preferChinese ? "。" : ".")
                            : action + (preferChinese ? "：" : ": ") + description);
                }
            }
            if ("apply_fix".equals(functionName)) {
                String operation = root.path("operation").asText("").trim();
                if ("preview".equals(operation)) {
                    return compactToolProgressDetail(preferChinese
                            ? "已预览形式化修复建议；尚未修改规则，等待明确确认。"
                            : "Previewed the formal fix; no rules changed and explicit confirmation is pending.");
                }
                if ("applied".equals(operation)) {
                    return compactToolProgressDetail(preferChinese
                            ? String.format("已应用形式化修复：规则数由 %d 变为 %d。",
                            root.path("previousRuleCount").asInt(0), root.path("currentRuleCount").asInt(0))
                            : String.format("Applied the formal fix; rule count changed from %d to %d.",
                            root.path("previousRuleCount").asInt(0), root.path("currentRuleCount").asInt(0)));
                }
            }
            if ("manage_spec".equals(functionName)) {
                String operation = root.path("operation").asText("").trim();
                JsonNode spec = "deleted".equals(operation)
                        ? root.path("deletedSpecification") : root.path("specification");
                String formula = spec.path("formulaPreview").asText("").trim();
                if ("preview".equals(operation)) {
                    return compactToolProgressDetail(preferChinese
                            ? "已预览规约删除影响；尚未写入，等待明确确认。"
                            : "Previewed the specification deletion impact; nothing was written and explicit confirmation is pending.");
                }
                if (Set.of("created", "deleted").contains(operation)) {
                    String action = "created".equals(operation)
                            ? (preferChinese ? "已创建规约" : "Created specification")
                            : (preferChinese ? "已删除规约" : "Deleted specification");
                    return compactToolProgressDetail(formula.isBlank()
                            ? action + (preferChinese ? "。" : ".")
                            : action + (preferChinese ? "：" : ": ") + formula);
                }
            }
            if ("add_template".equals(functionName) && "created".equals(root.path("operation").asText())) {
                String name = root.path("template").path("name").asText("").trim();
                return compactToolProgressDetail(preferChinese
                        ? "已创建设备模板" + quotedName(name) + "。"
                        : "Created device template" + quotedName(name) + ".");
            }
            if (Set.of("recommend_rules", "recommend_specifications", "recommend_related_devices")
                    .contains(functionName)) {
                int kept = root.path("validatedCount").asInt(root.path("count").asInt(0));
                int filtered = root.path("filteredCount").asInt(0);
                String firstReason = root.path("filteredItems").path(0).path("reason").asText("").trim();
                String summary = preferChinese
                        ? "AI 候选经后端校验后保留 " + kept + " 项，过滤 " + filtered + " 项。"
                        : "Backend validation kept " + kept + " AI candidate(s) and filtered " + filtered + ".";
                if (!firstReason.isBlank()) {
                    summary += (preferChinese ? " 首个过滤原因：" : " First filter reason: ") + firstReason;
                }
                return compactToolProgressDetail(summary);
            }
            if ("recommend_scenario".equals(functionName)) {
                JsonNode scene = root.path("scene");
                String name = root.path("scenarioName").asText("").trim();
                String summary = preferChinese
                        ? String.format("已生成场景%s：%d 个设备、%d 条规则、%d 条规约；过滤 %d 个无效候选。",
                        quotedName(name), arraySize(scene, "devices"), arraySize(scene, "rules"),
                        arraySize(scene, "specs"), root.path("filteredCount").asInt(0))
                        : String.format("Generated scenario%s with %d devices, %d rules, and %d specifications; filtered %d invalid candidate(s).",
                        quotedName(name), arraySize(scene, "devices"), arraySize(scene, "rules"),
                        arraySize(scene, "specs"), root.path("filteredCount").asInt(0));
                if (!root.path("verificationReady").asBoolean(false)) {
                    summary += preferChinese ? " 该草案目前尚不能启动验证。" : " The draft is not verification-ready yet.";
                }
                return compactToolProgressDetail(summary);
            }
            if ("apply_scenario".equals(functionName)) {
                String name = root.path("scenarioName").asText("").trim();
                boolean preview = "preview".equals(root.path("operation").asText());
                if (preview) {
                    if (List.of("currentDeviceCount", "currentEnvironmentVariableCount", "currentRuleCount",
                            "currentSpecificationCount", "replacementDeviceCount", "replacementEnvironmentVariableCount",
                            "replacementRuleCount", "replacementSpecificationCount", "editHistoryEntryCount")
                            .stream().allMatch(field -> nonNegativeInt(root.get(field)) != null)) {
                        return compactToolProgressDetail(preferChinese
                                ? String.format("场景%s替换预览：设备 %d→%d，共享环境变量 %d→%d，规则 %d→%d，规约 %d→%d；将清除 %d 条撤销/重做记录。尚未写入，等待确认。",
                                quotedName(name), root.path("currentDeviceCount").intValue(), root.path("replacementDeviceCount").intValue(),
                                root.path("currentEnvironmentVariableCount").intValue(), root.path("replacementEnvironmentVariableCount").intValue(),
                                root.path("currentRuleCount").intValue(), root.path("replacementRuleCount").intValue(),
                                root.path("currentSpecificationCount").intValue(), root.path("replacementSpecificationCount").intValue(),
                                root.path("editHistoryEntryCount").intValue())
                                : String.format("Scenario%s replacement preview: devices %d→%d, shared variables %d→%d, rules %d→%d, specifications %d→%d; %d undo/redo entries would be cleared. Nothing was written; confirmation is pending.",
                                quotedName(name), root.path("currentDeviceCount").intValue(), root.path("replacementDeviceCount").intValue(),
                                root.path("currentEnvironmentVariableCount").intValue(), root.path("replacementEnvironmentVariableCount").intValue(),
                                root.path("currentRuleCount").intValue(), root.path("replacementRuleCount").intValue(),
                                root.path("currentSpecificationCount").intValue(), root.path("replacementSpecificationCount").intValue(),
                                root.path("editHistoryEntryCount").intValue()));
                    }
                    return compactToolProgressDetail(preferChinese
                            ? String.format("已生成场景%s的全量替换预览，尚未写入，正在等待确认。", quotedName(name))
                            : String.format("Prepared a full-board replacement preview for scenario%s; nothing was written and confirmation is required.", quotedName(name)));
                }
                return compactToolProgressDetail(preferChinese
                        ? String.format("已应用场景%s：%d 个设备、%d 条规则、%d 条规约。",
                        quotedName(name), root.path("deviceCount").asInt(0),
                        root.path("ruleCount").asInt(0), root.path("specificationCount").asInt(0))
                        : String.format("Applied scenario%s with %d devices, %d rules, and %d specifications.",
                        quotedName(name), root.path("deviceCount").asInt(0),
                        root.path("ruleCount").asInt(0), root.path("specificationCount").asInt(0)));
            }
            if ("reset_default_templates".equals(functionName)) {
                if (root.path("requiresUserConfirmation").asBoolean(false)) {
                    JsonNode preview = root.path("preview");
                    return preferChinese
                            ? String.format("已预览默认模板刷新：%d 个模板变化、%d 个受影响设备，尚未写入，正在等待确认。",
                            arraySize(preview, "templateChanges"), arraySize(preview, "affectedDevices"))
                            : String.format("Previewed the default-template refresh: %d template change(s) and %d affected device(s); nothing was written and confirmation is required.",
                            arraySize(preview, "templateChanges"), arraySize(preview, "affectedDevices"));
                }
                if ("reset".equals(root.path("operation").asText())) {
                    return preferChinese
                            ? String.format("已刷新默认模板：%d 个模板变化、%d 个受影响设备、%d 个环境变量变化。",
                            root.path("templateChangeCount").asInt(0),
                            root.path("affectedDeviceCount").asInt(0),
                            root.path("environmentChangeCount").asInt(0))
                            : String.format("Refreshed default templates: %d template change(s), %d affected device(s), and %d environment-variable change(s).",
                            root.path("templateChangeCount").asInt(0),
                            root.path("affectedDeviceCount").asInt(0),
                            root.path("environmentChangeCount").asInt(0));
                }
            }
            if ("manage_environment".equals(functionName)) {
                JsonNode variable = root.path("currentVariable");
                String name = variable.path("name").asText("").trim();
                if (!name.isBlank()) {
                    return compactToolProgressDetail(preferChinese
                            ? "环境变量“" + name + "”当前值为 " + variable.path("value").asText("")
                            + "，trust=" + variable.path("trust").asText("")
                            + "，privacy=" + variable.path("privacy").asText("") + "。"
                            : "Environment variable '" + name + "' is now " + variable.path("value").asText("")
                            + " with trust=" + variable.path("trust").asText("")
                            + " and privacy=" + variable.path("privacy").asText("") + ".");
                }
            }
            if ("manage_board_history".equals(functionName)) {
                String operation = root.path("operation").asText("");
                if ("clear_preview".equals(operation)) {
                    return preferChinese
                            ? "已预览清除 " + root.path("entryCount").asInt(0)
                            + " 条撤销/重做记录；当前画布不会改变，正在等待确认。"
                            : "Previewed clearing " + root.path("entryCount").asInt(0)
                            + " undo/redo entries; current Board data will not change and confirmation is pending.";
                }
                if ("history_empty".equals(operation)) {
                    return preferChinese
                            ? "撤销/重做历史已经为空，画布未改变。"
                            : "Undo/redo history was already empty; the Board did not change.";
                }
                if ("history_cleared".equals(operation)) {
                    return preferChinese
                            ? "已清除 " + root.path("clearedEntryCount").asInt(0)
                            + " 条撤销/重做记录，当前画布未改变。"
                            : "Cleared " + root.path("clearedEntryCount").asInt(0)
                            + " undo/redo entries; current Board data did not change.";
                }
                if ("availability".equals(operation)) {
                    return preferChinese
                            ? "已读取编辑历史：可撤销=" + root.path("canUndo").asBoolean(false)
                            + "，可重做=" + root.path("canRedo").asBoolean(false) + "。"
                            : "Read edit history: canUndo=" + root.path("canUndo").asBoolean(false)
                            + ", canRedo=" + root.path("canRedo").asBoolean(false) + ".";
                }
                if (Set.of("undone", "redone").contains(operation)) {
                    boolean applied = root.path("applied").asBoolean(false);
                    if (!applied) {
                        return preferChinese
                                ? ("undone".equals(operation) ? "没有可撤销的编辑。" : "没有可重做的编辑。")
                                : ("undone".equals(operation) ? "There was no edit to undo." : "There was no edit to redo.");
                    }
                    String entityType = root.path("entityType").asText("").trim();
                    return compactToolProgressDetail(preferChinese
                            ? ("undone".equals(operation) ? "已撤销最近一次编辑" : "已重做上一次撤销的编辑")
                            + (entityType.isBlank() ? "。" : "（" + entityType + "）。")
                            : ("undone".equals(operation) ? "Undid the latest edit" : "Redid the previously undone edit")
                            + (entityType.isBlank() ? "." : " (" + entityType + ")."));
                }
            }
            if ("clear_board".equals(functionName)) {
                if ("preview".equals(root.path("operation").asText())) {
                    return compactToolProgressDetail(preferChinese
                            ? String.format("已预览清空场景：将移除 %d 个设备、%d 条规则、%d 条规约；尚未写入，等待确认。",
                            root.path("wouldRemoveDeviceCount").asInt(0),
                            root.path("wouldRemoveRuleCount").asInt(0),
                            root.path("wouldRemoveSpecificationCount").asInt(0))
                            : String.format("Previewed Board clearing: %d device(s), %d rule(s), and %d specification(s) would be removed; nothing was written and confirmation is pending.",
                            root.path("wouldRemoveDeviceCount").asInt(0),
                            root.path("wouldRemoveRuleCount").asInt(0),
                            root.path("wouldRemoveSpecificationCount").asInt(0)));
                }
                if ("cleared".equals(root.path("operation").asText())) {
                    return compactToolProgressDetail(preferChinese
                            ? String.format("已清空场景：移除 %d 个设备、%d 条规则、%d 条规约。",
                            root.path("removedDeviceCount").asInt(0),
                            root.path("removedRuleCount").asInt(0),
                            root.path("removedSpecificationCount").asInt(0))
                            : String.format("Cleared the Board: removed %d device(s), %d rule(s), and %d specification(s).",
                            root.path("removedDeviceCount").asInt(0),
                            root.path("removedRuleCount").asInt(0),
                            root.path("removedSpecificationCount").asInt(0)));
                }
            }

            String operation = root.path("operation").asText("").trim();
            if (!operation.isBlank()) {
                return compactToolProgressDetail(preferChinese
                        ? "工具操作结果：" + operation + "。"
                        : "Tool operation result: " + operation + ".");
            }
            return null;
        } catch (Exception e) {
            return preferChinese ? "工具结果无法生成摘要。" : "The tool result could not be summarized.";
        }
    }

    private Integer nonNegativeInt(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
                ? value.intValue() : null;
    }

    /** One authoritative user-visible receipt and the client data it invalidates. */
    public Optional<ActionReceipt> actionReceipt(
            String functionName, String toolResult, boolean preferChinese) {
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            if (root == null || !root.isObject()) return Optional.empty();
            if (!AiToolResultContract.hasValidControlFields(root)
                    || AiToolResultContract.isUnavailable(root)
                    || AiToolResultContract.isStructuredError(root)
                    || !hasValidKnownToolPayload(functionName, root)) {
                return Optional.empty();
            }
            boolean boardHistoryCleared = "manage_board_history".equals(functionName)
                    && "history_cleared".equals(root.path("operation").asText());
            String action = confirmedAction(functionName, root);
            if (action == null && !boardHistoryCleared) return Optional.empty();
            String summary = toolProgressDetail(functionName, toolResult, preferChinese);
            if (isGenericSummary(summary, preferChinese)) summary = null;
            return Optional.of(new ActionReceipt(action, summary, potentialRefreshTargets(functionName)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean isGenericSummary(String summary, boolean preferChinese) {
        if (summary == null || summary.isBlank()) return true;
        return summary.startsWith(preferChinese ? "工具操作结果：" : "Tool operation result: ");
    }

    public List<String> potentialRefreshTargets(String functionName) {
        return switch (functionName) {
            case "add_device" -> List.of("device_list", "environment_list");
            case "delete_device" -> List.of("device_list", "environment_list", "rule_list", "spec_list");
            case "edit_device" -> List.of("device_list", "spec_list");
            case "manage_rule", "apply_fix" -> List.of("rule_list");
            case "manage_spec" -> List.of("spec_list");
            case "manage_environment" -> List.of("environment_list");
            case "apply_scenario", "reset_default_templates", "manage_board_history", "clear_board" ->
                    List.of("board_state");
            case "add_template", "delete_template" -> List.of("template_list");
            case "verify_model", "verify_model_async", "simulate_model_async", "fuzz_model_async",
                    "cancel_verify_task", "cancel_simulate_task", "cancel_fuzz_task", "delete_trace",
                    "delete_simulation_trace", "delete_verification_run", "delete_fuzz_run",
                    "dismiss_verify_task", "dismiss_simulate_task", "dismiss_fuzz_task" ->
                    List.of("run_history");
            default -> List.of();
        };
    }

    private String confirmedAction(String functionName, JsonNode root) {
        String operation = root.path("operation").asText("");
        return switch (functionName) {
            case "add_device" -> "created".equals(operation) ? "DEVICE_ADDED" : null;
            case "delete_device" -> "deleted".equals(operation) ? "DEVICE_DELETED" : null;
            case "edit_device" -> Set.of("updated", "renamed").contains(operation) ? "DEVICE_UPDATED" : null;
            case "manage_rule" -> Set.of("created", "updated", "deleted", "reordered").contains(operation)
                    ? "RULES_UPDATED" : null;
            case "apply_fix" -> "applied".equals(operation) ? "REPAIR_APPLIED" : null;
            case "manage_spec" -> Set.of("created", "deleted").contains(operation)
                    ? "SPECIFICATIONS_UPDATED" : null;
            case "manage_environment" -> root.path("changesApplied").asBoolean(false)
                    ? "ENVIRONMENT_UPDATED" : null;
            case "apply_scenario" -> "replaced".equals(operation) ? "SCENE_APPLIED" : null;
            case "reset_default_templates" -> "reset".equals(operation) ? "DEFAULT_TEMPLATES_RESET" : null;
            case "add_template" -> "created".equals(operation) ? "TEMPLATES_UPDATED" : null;
            case "delete_template" -> "deleted".equals(operation) ? "TEMPLATES_UPDATED" : null;
            case "verify_model" -> root.hasNonNull("outcome")
                    && "SAVED".equals(root.path("historyPersistence").path("status").asText())
                    && root.path("historyPersistence").path("runId").canConvertToLong()
                    && root.path("historyPersistence").path("runId").asLong() > 0
                    ? "FORMAL_VERIFICATION_RUN" : null;
            case "verify_model_async" -> root.path("taskAccepted").asBoolean(false)
                    ? "VERIFICATION_TASK_STARTED" : null;
            case "simulate_model_async" -> root.path("taskAccepted").asBoolean(false)
                    ? "SIMULATION_TASK_STARTED" : null;
            case "fuzz_model_async" -> root.path("taskAccepted").asBoolean(false)
                    ? "EXPLORATION_TASK_STARTED" : null;
            case "cancel_verify_task", "cancel_simulate_task", "cancel_fuzz_task" ->
                    root.path("cancellationAccepted").asBoolean(false) ? "RUN_HISTORY_UPDATED" : null;
            case "delete_trace", "delete_simulation_trace", "delete_verification_run", "delete_fuzz_run" ->
                    root.path("deleted").asBoolean(false) ? "RUN_HISTORY_UPDATED" : null;
            case "dismiss_verify_task", "dismiss_simulate_task", "dismiss_fuzz_task" ->
                    root.path("dismissed").asBoolean(false) ? "RUN_HISTORY_UPDATED" : null;
            case "manage_board_history" -> {
                if (!root.path("applied").asBoolean(false)) yield null;
                yield "undone".equals(operation) ? "BOARD_UNDONE"
                        : "redone".equals(operation) ? "BOARD_REDONE" : null;
            }
            case "clear_board" -> "cleared".equals(operation) ? "BOARD_CLEARED" : null;
            default -> null;
        };
    }

    /** Confirmation must expose canonical modeling choices even when the model omits them. */
    public String scenarioEnvironmentAssumptions(String previewJson, boolean preferChinese) {
        if (previewJson == null || previewJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(previewJson);
            JsonNode variables = root.path("replacementScene").path("environmentVariables");
            Integer count = nonNegativeInt(root.get("replacementEnvironmentVariableCount"));
            if (!"preview".equals(root.path("operation").asText())
                    || !root.path("requiresUserConfirmation").isBoolean()
                    || !root.path("requiresUserConfirmation").booleanValue()
                    || count == null || count == 0 || !variables.isArray() || variables.size() != count) return null;
            int trusted = 0, publicInputs = 0;
            for (JsonNode variable : variables) {
                String trust = variable.path("trust").asText();
                String privacy = variable.path("privacy").asText();
                if (!List.of("trusted", "untrusted").contains(trust)
                        || !List.of("public", "private").contains(privacy)) return null;
                if ("trusted".equals(trust)) trusted++;
                if ("public".equals(privacy)) publicInputs++;
            }
            return preferChinese
                    ? String.format("本草稿的环境输入设置：%d 项按可信来源建模，%d 项按不可信来源建模；%d 项公开，%d 项私密。这些是建模假设，尚未验证。",
                    trusted, count - trusted, publicInputs, count - publicInputs)
                    : String.format("Draft environment inputs: %d modeled as trusted sources, %d untrusted; %d public, %d private. These are modeling assumptions, not verification conclusions.",
                    trusted, count - trusted, publicInputs, count - publicInputs);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    public record ActionReceipt(String assistantAction, String summary, List<String> refreshTargets) {
    }

    private int arraySize(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.path(field);
        return value != null && value.isArray() ? value.size() : 0;
    }

    private String quotedName(String name) {
        return name == null || name.isBlank() ? "" : " '" + name + "'";
    }

    private String compactToolProgressDetail(String value) {
        return sanitizeProgressDetail(value, 240);
    }

    /**
     * Sanitizes the model's user-visible reasoning while keeping it readable <em>as</em> reasoning.
     *
     * <p>Deliberately different from {@link #compactToolProgressDetail}, which formats a one-line
     * status. Reasoning is the only channel carrying actual analysis, so three things that are
     * right for a status line are wrong here:
     *
     * <ul>
     *   <li><b>Line structure is preserved.</b> Collapsing all whitespace turned a decomposition
     *       ("Goal … / Observed … / Next …") into one run-on line.</li>
     *   <li><b>Prose is left alone.</b> Identifier redaction is anchored to real identifier shapes,
     *       so ordinary hyphenated English — "rule-based", "device-level", "trace-driven" — is no
     *       longer replaced by a redaction marker mid-sentence.</li>
     *   <li><b>The budget is larger and the cut lands on a boundary</b>, not mid-clause.</li>
     * </ul>
     */
    public String compactReasoningProgressDetail(String value) {
        if (value == null) return null;
        // Collapse horizontal runs and excess blank lines, but keep single newlines.
        String structured = redactIdentifiers(value)
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return truncateAtBoundary(structured, REASONING_MAX_CHARS);
    }

    /**
     * Cuts at the last sentence end or line break inside the budget, so the user never reads a
     * sentence that stops mid-clause. Falls back to a hard cut only when no usable boundary exists.
     */
    private String truncateAtBoundary(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        String head = value.substring(0, maxChars);
        int boundary = -1;
        for (String terminator : new String[] {"\n", ". ", "。", "; ", "；"}) {
            boundary = Math.max(boundary, head.lastIndexOf(terminator));
        }
        // Only honour a boundary in the last third; earlier would discard usable reasoning. A short
        // decomposition followed by one long unbroken paragraph therefore takes the hard cut — that is
        // the intended trade: losing two thirds of the explanation to end on a period reads worse than
        // an ellipsis mid-clause.
        if (boundary > maxChars / 3 * 2) {
            return head.substring(0, boundary).trim() + " …";
        }
        return head.substring(0, Math.max(0, maxChars - 2)).trim() + " …";
    }

    private String sanitizeProgressDetail(String value, int maxChars) {
        if (value == null) return null;
        return compactProgressDetail(redactIdentifiers(value), maxChars);
    }

    /**
     * Removes confirmation tokens and internal identifiers.
     *
     * <p>The hyphenated form requires a digit in the tail, which is what distinguishes a generated id
     * ({@code rule-4a}) from an English compound adjective ({@code rule-based}, {@code device-level});
     * without it, the redaction corrupted the explanation it was protecting. The underscore form needs
     * no digit: English prose does not join words with underscores, so {@code device_a} is an id too —
     * requiring a digit there leaked every non-numeric node id past the only mechanical guard.
     */
    private String redactIdentifiers(String value) {
        return value
                .replaceAll("(?i)(impactToken|confirmationToken|domainImpactToken|suggestionToken)\\s*[:=]\\s*[^,;\\s]+", "$1=[hidden]")
                .replaceAll("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "[internal reference]")
                .replaceAll("(?i)\\b(?:device|node|rule|spec|task|trace|simulation)_[a-z0-9_-]+\\b", "[internal reference]")
                .replaceAll("(?i)\\b(?:device|node|rule|spec|task|trace|simulation)-[a-z0-9_-]*[0-9][a-z0-9_-]*\\b", "[internal reference]")
                .replaceAll("(?i)\\b(?:device|node|rule|spec(?:ification)?|task|trace|simulation|session|user)\\s+id\\s*[:=#]?\\s*[a-z0-9_-]+", "[internal reference]");
    }
}
