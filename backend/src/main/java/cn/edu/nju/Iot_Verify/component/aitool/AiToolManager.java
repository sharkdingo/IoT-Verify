package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.security.UserContextHolder.BoardWritePermission;
import cn.edu.nju.Iot_Verify.util.InterruptPreservation;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiToolManager {

    // Spring 会自动注入所有实现了 AiTool 接口的 Bean (例如 AddNodeTool)
    private final List<AiTool> allTools;
    private final ObjectMapper objectMapper;
    private final ChatExecutionConfig chatExecutionConfig;
    private final ChatConfirmationDetector confirmationDetector;
    private static final Set<String> BOARD_WRITING_TOOLS = Set.of(
            "add_device", "edit_device", "delete_device", "manage_rule", "manage_spec",
            "manage_environment", "apply_fix", "apply_scenario", "add_template", "delete_template",
            "reset_default_templates", "manage_board_history", "clear_board");

    // 缓存 Map：ToolName -> AiTool 实例
    private Map<String, AiTool> toolMap;

    @PostConstruct
    public void init() {
        toolMap = allTools.stream()
                .collect(Collectors.toMap(AiTool::getName, Function.identity()));
    }

    /**
     * 获取所有工具的定义列表 (提供给 LlmProvider 使用)
     */
    public List<LlmToolSpec> getAllToolDefinitions() {
        return allTools.stream()
                .map(AiTool::getDefinition)
                .collect(Collectors.toList());
    }

    /** Registry names do not resolve definitions, avoiding recursive schema discovery. */
    public List<String> getAllToolNames() {
        return allTools.stream().map(AiTool::getName).toList();
    }

    /**
     * 根据工具名和 JSON 参数执行具体的工具
     */
    public String execute(String functionName, String argsJson) {
        AiTool tool = toolMap.get(functionName);
        if (tool == null) {
            log.warn("AI 尝试调用不存在的工具: {}", functionName);
            return errorJson("Unknown function: " + functionName, "TOOL_NOT_FOUND", 404);
        }

        log.info("开始执行 AI 工具: {}", functionName);
        try {
            if (UserContextHolder.getChatExecutionId() != null && requiresBoardWriteAuthorization(functionName, argsJson)) {
                BoardWritePermission permission = UserContextHolder.getBoardWritePermission();
                if (permission == null) {
                    permission = confirmationDetector.detectBoardWritePermission(UserContextHolder.getChatUserMessage());
                    UserContextHolder.setBoardWritePermission(permission);
                }
                if (permission != BoardWritePermission.ALLOWED) {
                    return errorJson(permission == BoardWritePermission.DENIED
                                    ? "This user turn does not authorize saved Board changes. Nothing was changed. For repair analysis use fix_violation, then present verified candidates without applying them."
                                    : "Write permission could not be established. Nothing was changed; continue read-only work or ask the user to specify the intended edit.",
                            permission == BoardWritePermission.DENIED ? "BOARD_WRITE_NOT_AUTHORIZED"
                                    : "BOARD_WRITE_PERMISSION_UNAVAILABLE", permission == BoardWritePermission.DENIED ? 403 : 503);
                }
            }
            return boundResult(functionName, attachExecutionEvidence(tool.execute(argsJson)));
        } catch (Exception e) {
            // Broad enough to capture an InterruptedException, which clears the flag as it is thrown.
            // A tool that delegates to synchronous verification or simulation runs work that *is*
            // interruptible, and swallowing the flag here would leave a later interruptible call on
            // this same thread unable to see it. Re-arming is safe against leaking into the next
            // request because ThreadConfig's task decorator clears the flag at the task boundary —
            // note ChatServiceImpl.synchronizeExecutionStop reads isInterrupted() and ends the turn as
            // DISCONNECTED, so an escaped flag would abort the *current* turn as well.
            InterruptPreservation.preserveInterrupt(e);
            log.error("AI tool '{}' threw unexpected exception", functionName, e);
            if (AiToolResultContract.isMutationCapable(functionName)) {
                return mutationOutcomeUnavailable(
                        "TOOL_EXECUTION_OUTCOME_UNKNOWN",
                        "The tool failed unexpectedly after execution started. State may already have changed; "
                                + "refresh current state before retrying.");
            }
            return errorJson("Tool execution failed due to an internal error", "TOOL_EXECUTION_ERROR", 500);
        }
    }

    private boolean requiresBoardWriteAuthorization(String functionName, String argsJson) {
        if (!BOARD_WRITING_TOOLS.contains(functionName)) return false;
        JsonNode args = parseObject(argsJson);
        if (args == null) return true;
        String action = args.path("action").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        if (Set.of("delete_device", "delete_template", "apply_fix", "apply_scenario",
                "reset_default_templates", "clear_board").contains(functionName)
                || (Set.of("manage_rule", "manage_spec").contains(functionName) && "delete".equals(action))
                || ("manage_board_history".equals(functionName) && "clear".equals(action))) {
            if (!args.path("confirmed").asBoolean(false)) return false;
            // An exact pending-action confirmation already grants authority; the tool still checks its
            // session-bound token and target. It cannot grant authority to an ordinary edit tool.
            if ("apply_scenario".equals(functionName)) return !UserContextHolder.isSceneReplacementConfirmed();
            if ("reset_default_templates".equals(functionName)) return !UserContextHolder.isDefaultTemplateResetConfirmed();
            return !UserContextHolder.isDestructiveActionConfirmed();
        }
        if ("manage_environment".equals(functionName)) return !"list".equals(action);
        if ("manage_board_history".equals(functionName)) return !"availability".equals(action);
        return true;
    }

    /**
     * Gives every successful tool result the same small execution envelope. Domain payloads stay
     * flexible; mutation tools are checked separately for their authoritative outcome marker.
     */
    private String attachExecutionEvidence(String result) {
        if (result == null || result.isBlank()) return result;
        try {
            var root = objectMapper.readTree(result);
            if (root == null || !root.isObject() || root.isEmpty()
                    || AiToolResultContract.isStructuredError(root)
                    || AiToolResultContract.isUnavailable(root)) {
                return result;
            }
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) root;
            if (!object.has("resultAvailable")) object.put("resultAvailable", true);
            if (!object.has("resultStatus")) {
                object.put("resultStatus",
                        object.path("requiresUserConfirmation").asBoolean(false)
                                ? "PREVIEW" : "SUCCESS");
            }
            return objectMapper.writeValueAsString(object);
        } catch (Exception ignored) {
            return result;
        }
    }

    private String boundResult(String functionName, String result) {
        String safeResult = result == null ? "" : result;
        int resultBytes = safeResult.getBytes(StandardCharsets.UTF_8).length;
        if (resultBytes > chatExecutionConfig.getMaxToolResultBytes()
                && Set.of("get_trace", "get_simulation_trace", "get_fuzz_finding").contains(functionName)) {
            JsonNode parsed = parseObject(safeResult);
            if (parsed instanceof ObjectNode window && window.path("states") instanceof ArrayNode states
                    && window.path("stateOffset").isInt() && window.path("stateOffset").intValue() >= 0
                    && window.path("returnedStateCount").asInt(-1) == states.size()
                    && (long) window.path("stateOffset").intValue() + states.size()
                            <= window.path("stateCount").asLong(-1)
                    && !AiToolResultContract.isStructuredError(window)
                    && !AiToolResultContract.isUnavailable(window)) {
                // State-count paging alone can exceed the byte budget on an ordinary multi-device board.
                // Keep complete states and their metadata; the next offset makes every omitted state reachable.
                while (resultBytes > chatExecutionConfig.getMaxToolResultBytes() && states.size() > 1) {
                    states.remove(states.size() - 1);
                    int nextOffset = window.path("stateOffset").intValue() + states.size();
                    window.put("returnedStateCount", states.size());
                    window.put("hasMoreStates", true);
                    window.put("nextStateOffset", nextOffset);
                    if (window.path("inputEvents") instanceof ArrayNode events) {
                        for (int index = events.size() - 1; index >= 0; index--) {
                            if (events.get(index).path("step").asInt() >= nextOffset) events.remove(index);
                        }
                        window.put("returnedInputEventCount", events.size());
                    }
                    safeResult = window.toString();
                    resultBytes = safeResult.getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        if (resultBytes > chatExecutionConfig.getMaxToolResultBytes()) {
            boolean mutationMayHaveCommitted = AiToolResultContract.isMutationCapable(functionName);
            log.warn("AI tool '{}' result exceeded persistence/model limit: bytes={}, limit={}",
                    functionName, resultBytes, chatExecutionConfig.getMaxToolResultBytes());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resultStatus", "RESULT_UNAVAILABLE");
            body.put("resultAvailable", false);
            body.put("mutationMayHaveCommitted", mutationMayHaveCommitted);
            body.put("errorCode", "TOOL_RESULT_TOO_LARGE");
            body.put("message", mutationMayHaveCommitted
                    ? "Tool result details exceeded the safe size limit. The operation may already have changed state; refresh current state before retrying with a narrower request."
                    : "Tool result details exceeded the safe size limit. Retry with a narrower filter or request fewer details.");
            body.put("resultBytes", resultBytes);
            body.put("maxResultBytes", chatExecutionConfig.getMaxToolResultBytes());
            return AiToolResponseHelper.success(objectMapper, body, "Tool result exceeded the safe size limit.",
                    mutationMayHaveCommitted);
        }
        if (AiToolResultContract.isMutationCapable(functionName) && !isNonEmptyJsonObject(safeResult)) {
            log.warn("Mutation-capable AI tool '{}' returned an unusable result", functionName);
            return mutationOutcomeUnavailable(
                    "TOOL_RESULT_MALFORMED",
                    "The tool returned no trustworthy result after execution started. State may already have "
                            + "changed; refresh current state before retrying.");
        }
        JsonNode parsed = parseObject(safeResult);
        if (AiToolResultContract.isMutationCapable(functionName)
                && !AiToolResultContract.hasValidControlFields(parsed)) {
            log.warn("AI mutation tool '{}' returned malformed result control fields", functionName);
            return mutationOutcomeUnavailable(
                    "TOOL_RESULT_MALFORMED",
                    "The tool returned malformed execution evidence after execution started. "
                            + "State may already have changed; refresh current state before retrying.");
        }
        if (AiToolResultContract.isMutationCapable(functionName)
                && isNonErrorResult(safeResult)
                && !AiToolResultContract.hasValidKnownToolPayload(functionName, parsed)) {
            log.warn("AI mutation tool '{}' returned no authoritative completion marker", functionName);
            return mutationOutcomeUnavailable(
                    "TOOL_RESULT_MALFORMED",
                    "The tool returned no authoritative completion marker after execution started. "
                            + "State may already have changed; refresh current state before retrying.");
        }
        return safeResult;
    }

    private boolean isNonErrorResult(String result) {
        JsonNode parsed = parseObject(result);
        if (parsed == null) return false;
        return !AiToolResultContract.isStructuredError(parsed)
                && !AiToolResultContract.isUnavailable(parsed);
    }

    private JsonNode parseObject(String result) {
        try {
            JsonNode parsed = objectMapper.readTree(result);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isNonEmptyJsonObject(String result) {
        if (result == null || result.isBlank()) return false;
        try {
            var parsed = objectMapper.readTree(result);
            return parsed != null && parsed.isObject() && !parsed.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String mutationOutcomeUnavailable(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resultStatus", "RESULT_UNAVAILABLE");
        body.put("resultAvailable", false);
        body.put("mutationMayHaveCommitted", true);
        body.put("errorCode", errorCode);
        body.put("message", message);
        return AiToolResponseHelper.success(objectMapper, body,
                "Tool execution outcome is unavailable.", true);
    }

    private String errorJson(String message, String errorCode, int status) {
        return AiToolResponseHelper.error(objectMapper, message, errorCode, status);
    }
}
