package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackPointDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.RecommendationInputs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI Tool 公共基类。
 *
 * <p>提取 Tool 共享的鉴权检查和辅助方法，消除样板代码。
 *
 * <h3>基类职责（有限）</h3>
 * <ul>
 *   <li>{@link #execute(String)} — 仅做 userId 鉴权，不做 JSON 解析，不做兜底 catch-all</li>
 *   <li>{@link #parseArgs(String)} — 可选 JSON 解析工具方法，子类按需调用</li>
 *   <li>errorJson / successJson / safeList / trimToNull — 共用辅助方法</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractAiTool implements AiTool {

    protected final ObjectMapper objectMapper;

    protected AbstractAiTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public final String execute(String argsJson) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            return errorJson("User not logged in", "UNAUTHORIZED", 401);
        }
        return doExecute(userId, argsJson);
    }

    /**
     * 子类实现业务逻辑。
     *
     * @implSpec 实现类必须：
     * <ol>
     *   <li>需要解析参数时调用 {@link #parseArgs(String)} 而非自行 readTree；</li>
     *   <li>包含完整的 try-catch 链并返回 {@link #errorJson}，以保留每个 Tool 特有的错误文案（契约不变）；</li>
     *   <li>catch-all 中使用 Tool 专属文案（如 "Failed to query verification task."），
     *       不得省略——否则异常会穿透到 {@link AiToolManager} 并返回通用 TOOL_EXECUTION_ERROR。</li>
     * </ol>
     *
     * @param userId  已通过鉴权的用户 ID（非 null）
     * @param argsJson 原始 JSON 参数字符串
     * @return JSON 格式的执行结果
     */
    protected abstract String doExecute(Long userId, String argsJson);

    // ── JSON 解析工具方法（子类按需调用，非强制）──────────────────────

    /**
     * 解析 argsJson 为 JsonNode。所有 Tool 都应在 doExecute() 开头调用；无参 Tool
     * 也必须解析并拒绝未知字段，避免调用者以为一个不支持的选项已经生效。
     *
     * @return 解析后的 JsonNode
     * @throws ArgParseException 包装了 errorJson 返回值，调用方应直接 catch 并 return
     */
    protected final JsonNode parseArgs(String argsJson) throws ArgParseException {
        try {
            return objectMapper.readTree(
                    argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            throw new ArgParseException(
                    errorJson("Invalid JSON arguments.", "VALIDATION_ERROR", 400));
        }
    }

    /**
     * 仅用于 {@link #parseArgs} 的控制流异常，不是业务异常。
     */
    protected static final class ArgParseException extends Exception {
        private final String errorResponse;

        ArgParseException(String errorResponse) {
            this.errorResponse = errorResponse;
        }

        public String getErrorResponse() {
            return errorResponse;
        }
    }

    /**
     * Used by helper methods that validate parsed tool arguments.
     */
    protected static final class ArgValidationException extends Exception {
        private final String errorResponse;

        public ArgValidationException(String errorResponse) {
            this.errorResponse = errorResponse;
        }

        public String getErrorResponse() {
            return errorResponse;
        }
    }

    // ── 共用 helper ─────────────────────────────────────────────────

    protected final int taskStatusWaitMsArg(JsonNode args) throws ArgValidationException {
        if (args.has("waitMs") && args.get("waitMs").isNull()) {
            throw new ArgValidationException(errorJson("waitMs must be an integer when provided.",
                    "VALIDATION_ERROR", 400));
        }
        return intArgInRange(args, "waitMs", AiTaskStatusWaiter.MAX_WAIT_MS, 0,
                AiTaskStatusWaiter.MAX_WAIT_MS);
    }

    protected final int intArgInRange(JsonNode args,
                                      String field,
                                      int defaultValue,
                                      int min,
                                      int max) throws ArgValidationException {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber()) {
            throw new ArgValidationException(errorJson(
                    field + " must be an integer between " + min + " and " + max + ".",
                    "VALIDATION_ERROR",
                    400));
        }
        BigInteger parsed = value.bigIntegerValue();
        if (parsed.compareTo(BigInteger.valueOf(min)) < 0
                || parsed.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new ArgValidationException(errorJson(
                    field + " must be between " + min + " and " + max + ".",
                    "VALIDATION_ERROR",
                    400));
        }
        return parsed.intValue();
    }

    protected final long positiveLongArg(JsonNode args, String field) throws ArgValidationException {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || value.isNull()) {
            throw new ArgValidationException(errorJson(
                    "'" + field + "' is required.",
                    "VALIDATION_ERROR",
                    400));
        }
        if (!value.isIntegralNumber()) {
            throw new ArgValidationException(errorJson(
                    "'" + field + "' must be a positive integer.",
                    "VALIDATION_ERROR",
                    400));
        }
        BigInteger parsed = value.bigIntegerValue();
        if (parsed.compareTo(BigInteger.ONE) < 0) {
            throw new ArgValidationException(errorJson(
                    "'" + field + "' must be positive.",
                    "VALIDATION_ERROR",
                    400));
        }
        if (parsed.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new ArgValidationException(errorJson(
                    "'" + field + "' is out of range.",
                    "VALIDATION_ERROR",
                    400));
        }
        return parsed.longValue();
    }

    /**
     * Reads an optional boolean, rejecting a non-boolean rather than coercing it.
     *
     * <p>Every confirmation-gated tool calls this for {@code confirmed} with {@code false} as the
     * default, even though all of them also list {@code confirmed} in their schema's {@code required}
     * array. That is deliberate and uniform: the schema states the intent so the model always sends
     * it, while the default keeps the *safe* branch reachable — an omitted flag yields a no-write
     * preview instead of spending a round on a VALIDATION_ERROR to reach the same outcome. A missing
     * flag must never be read as consent, which is why the default is false and not true.
     */
    protected final boolean booleanArg(JsonNode args,
                                       String field,
                                       boolean defaultValue) throws ArgValidationException {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isBoolean()) {
            throw new ArgValidationException(errorJson(
                    field + " must be a boolean.",
                    "VALIDATION_ERROR",
                    400));
        }
        return value.booleanValue();
    }

    /** Parses the shared per-run attack scenario used by verification and simulation tools. */
    /**
     * The JSON schema for the {@code attackPoints} argument, described once.
     *
     * <p>Four tools built this map privately and three described the contract to the LLM differently from the
     * fourth: "Required for attackMode exact" versus "Required <b>only</b> for attackMode exact". The model
     * chooses arguments from this text, so a divergence is a behavioural difference, not a wording preference —
     * and {@link #attackScenarioArg} rejects a non-empty {@code attackPoints} for every mode except
     * {@code exact}, so "only" was accurate and the other three understated it.
     *
     * <p>{@code allowExhaustive} must match what the caller passes to {@link #attackScenarioArg}. Simulation
     * passes {@code false} and offers only {@code none}/{@code exact}, so naming {@code exhaustive} in its
     * description would document a mode it rejects — which is the same class of mistake as the divergence this
     * consolidation fixed, just introduced from the other side.
     */
    protected static Map<String, Object> attackPointsSchema(boolean allowExhaustive) {
        String forbidden = allowExhaustive ? "none and exhaustive" : "none";
        return Map.of(
                "type", "array",
                "description", "Required only for attackMode exact; must be omitted or empty for " + forbidden
                        + ". Device ids use the canonical ids returned by board_overview and are normalized at "
                        + "the model boundary; automation links use persisted rule ids, never a rule's "
                        + "display order, ruleIndex, or priority.",
                "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "kind", Map.of("type", "string", "enum", List.of("device", "automation_link")),
                                "deviceId", Map.of("type", "string"),
                                "ruleId", Map.of("type", "integer", "description",
                                        "Exact persisted id of the selected rule from board_overview or list_rules, not its order/index.")),
                        "required", List.of("kind"),
                        "additionalProperties", false));
    }

    /** How much of a task's error message the AI tools echo back before truncating. */
    protected static final int ERROR_PREVIEW_LIMIT = 1_000;

    /**
     * Truncate a task error message for a tool response.
     *
     * <p>The three dismiss tools (fuzz, simulate, verify) each carried a byte-identical copy of this method and
     * of {@code ERROR_PREVIEW_LIMIT}. They agreed, but the model reads these strings: had one drifted, the same
     * failure would have been summarised at two different lengths depending on which run kind the user dismissed,
     * and nothing in the suite compares them.
     *
     * <p>Returns {@code null} unchanged — a task with no error message must not become the string "null".
     */
    protected static String errorPreview(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= ERROR_PREVIEW_LIMIT) {
            return errorMessage;
        }
        return errorMessage.substring(0, ERROR_PREVIEW_LIMIT - 3) + "...";
    }

    /**
     * The JSON schema for the {@code attackBudget} argument, described once.
     *
     * <p>Only the two verification tools accept it (simulation rejects {@code exhaustive}), and they described it
     * differently: one stated "Omit it for none or exact", the other omitted that sentence — while
     * {@link #attackScenarioArg} rejects a non-zero budget for both of those modes on either path. The tool that
     * left the rule out invited the model to send an argument that would be refused.
     */
    protected static Map<String, Object> attackBudgetSchema() {
        return Map.of(
                "type", "integer",
                "description", "Upper bound from 1 to 50 for attackMode exhaustive. Omit it, or send 0, for "
                        + "none and exact.");
    }

    protected final AttackScenarioDto attackScenarioArg(JsonNode args,
                                                        boolean allowExhaustive)
            throws ArgValidationException {
        String mode = optionalEnumArg(args, "attackMode", "none",
                allowExhaustive
                        ? Set.of("none", "exact", "exhaustive")
                        : Set.of("none", "exact"));
        JsonNode budgetNode = args == null ? null : args.get("attackBudget");
        JsonNode pointsNode = args == null ? null : args.get("attackPoints");

        if ("none".equals(mode)) {
            if (budgetNode != null && !budgetNode.isNull()
                    && intArgInRange(args, "attackBudget", 0, 0, 50) != 0) {
                throw new ArgValidationException(errorJson(
                        "attackBudget must be omitted or 0 when attackMode is none.",
                        "VALIDATION_ERROR", 400));
            }
            if (pointsNode != null && !pointsNode.isNull()
                    && (!pointsNode.isArray() || !pointsNode.isEmpty())) {
                throw new ArgValidationException(errorJson(
                        "attackPoints must be omitted or empty when attackMode is none.",
                        "VALIDATION_ERROR", 400));
            }
            return AttackScenarioDto.none();
        }

        if ("exhaustive".equals(mode)) {
            if (!allowExhaustive) {
                throw new ArgValidationException(errorJson(
                        "attackMode exhaustive is verification-only; simulation requires exact attack points.",
                        "VALIDATION_ERROR", 400));
            }
            if (pointsNode != null && !pointsNode.isNull()
                    && (!pointsNode.isArray() || !pointsNode.isEmpty())) {
                throw new ArgValidationException(errorJson(
                        "attackPoints must be omitted or empty when attackMode is exhaustive.",
                        "VALIDATION_ERROR", 400));
            }
            return AttackScenarioDto.anyUpToBudget(
                    intArgInRange(args, "attackBudget", 1, 1, 50));
        }

        if (budgetNode != null && !budgetNode.isNull()
                && intArgInRange(args, "attackBudget", 0, 0, 50) != 0) {
            throw new ArgValidationException(errorJson(
                    "attackBudget must be omitted or 0 when attackMode is exact.",
                    "VALIDATION_ERROR", 400));
        }
        if (pointsNode == null || pointsNode.isNull() || !pointsNode.isArray()
                || pointsNode.isEmpty() || pointsNode.size() > 50) {
            throw new ArgValidationException(errorJson(
                    "attackPoints must contain between 1 and 50 points when attackMode is exact.",
                    "VALIDATION_ERROR", 400));
        }

        List<AttackPointDto> points = new ArrayList<>();
        for (int index = 0; index < pointsNode.size(); index++) {
            JsonNode pointNode = pointsNode.get(index);
            String path = "attackPoints[" + index + "]";
            requireOnlyFields(pointNode, path, Set.of("kind", "deviceId", "ruleId"));
            String kind = optionalEnumArg(pointNode, "kind", "",
                    Set.of("device", "automation_link"));
            if ("device".equals(kind)) {
                String deviceId = requiredDeviceIdField(pointNode, "deviceId", path);
                if (pointNode.hasNonNull("ruleId")) {
                    throw new ArgValidationException(errorJson(
                            path + ".ruleId must be omitted for a device attack point.",
                            "VALIDATION_ERROR", 400));
                }
                points.add(AttackPointDto.device(DeviceNameNormalizer.normalize(deviceId)));
            } else {
                if (pointNode.hasNonNull("deviceId")) {
                    throw new ArgValidationException(errorJson(
                            path + ".deviceId must be omitted for an automation-link attack point.",
                            "VALIDATION_ERROR", 400));
                }
                points.add(AttackPointDto.automationLink(positiveLongArg(pointNode, "ruleId")));
            }
        }
        return AttackScenarioDto.exactPoints(points);
    }

    protected final String optionalTextArg(JsonNode args,
                                           String field,
                                           String defaultValue,
                                           int maxLength) throws ArgValidationException {
        JsonNode value = args == null ? null : args.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw new ArgValidationException(errorJson(
                    field + " must be a string.", "VALIDATION_ERROR", 400));
        }
        String normalized = value.textValue().trim();
        if (normalized.length() > maxLength) {
            throw new ArgValidationException(errorJson(
                    field + " must be at most " + maxLength + " characters.",
                    "VALIDATION_ERROR", 400));
        }
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    protected final String optionalEnumArg(JsonNode args,
                                           String field,
                                           String defaultValue,
                                           Set<String> allowed) throws ArgValidationException {
        String value = optionalTextArg(args, field, defaultValue, 100).toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new ArgValidationException(errorJson(
                    field + " must be one of: " + String.join(", ", allowed) + ".",
                    "VALIDATION_ERROR", 400));
        }
        return value;
    }

    protected final String languageArg(JsonNode args, String field) throws ArgValidationException {
        try {
            return RecommendationInputs.language(optionalTextArg(args, field, "en", 20));
        } catch (BadRequestException e) {
            throw new ArgValidationException(errorJson(
                    field + " must be en or zh-CN.", "VALIDATION_ERROR", 400));
        }
    }

    /**
     * Enforces the exact object shape advertised by a tool schema. Tree-model parsing does not
     * apply Jackson's DTO unknown-property checks, so tools must reject fields they would otherwise
     * ignore instead of returning a result for a request different from the caller's intent.
     */
    protected final void requireOnlyFields(JsonNode value,
                                           String path,
                                           Set<String> allowedFields) throws ArgValidationException {
        String displayPath = path == null || path.isBlank() ? "arguments" : path;
        if (value == null || !value.isObject()) {
            throw new ArgValidationException(errorJson(
                    displayPath + " must be a JSON object.", "VALIDATION_ERROR", 400));
        }
        List<String> unknownFields = new ArrayList<>();
        value.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) unknownFields.add(field);
        });
        if (!unknownFields.isEmpty()) {
            Collections.sort(unknownFields);
            throw new ArgValidationException(errorJson(
                    displayPath + " contains unsupported field(s): "
                            + String.join(", ", unknownFields) + ".",
                    "VALIDATION_ERROR", 400));
        }
    }

    protected final String requiredTextField(JsonNode object,
                                             String field,
                                             String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        String fieldPath = fieldPath(path, field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new ArgValidationException(errorJson(
                    fieldPath + " is required and must be a non-empty string.",
                    "VALIDATION_ERROR", 400));
        }
        String normalized = trimToNull(value.textValue());
        if (normalized == null) {
            throw new ArgValidationException(errorJson(
                    fieldPath + " is required and must be a non-empty string.",
                    "VALIDATION_ERROR", 400));
        }
        return normalized;
    }

    /** Device identities are validated before lookup or model-name conversion, never repaired by trimming. */
    protected final String requiredDeviceIdField(JsonNode object,
                                                 String field,
                                                 String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new ArgValidationException(errorJson(
                    fieldPath(path, field) + " is required and must be a non-empty string.",
                    "VALIDATION_ERROR", 400));
        }
        String id = value.textValue();
        if (!id.matches(DeviceNodeDto.ID_PATTERN)) {
            throw new ArgValidationException(errorJson(
                    fieldPath(path, field) + " must be a non-empty device ID without leading or trailing whitespace.",
                    "VALIDATION_ERROR", 400));
        }
        return id;
    }

    protected final String nullableDeviceIdField(JsonNode object,
                                                 String field,
                                                 String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull()) return null;
        return requiredDeviceIdField(object, field, path);
    }

    /** Returns null for an omitted, explicit-null, or blank optional string; rejects other JSON types. */
    protected final String nullableTextField(JsonNode object,
                                             String field,
                                             String path) throws ArgValidationException {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new ArgValidationException(errorJson(
                    fieldPath(path, field) + " must be a string or null.",
                    "VALIDATION_ERROR", 400));
        }
        return trimToNull(value.textValue());
    }

    private String fieldPath(String path, String field) {
        return path == null || path.isBlank() ? field : path + "." + field;
    }

    protected final String errorJson(String message, String errorCode, int status) {
        return AiToolResponseHelper.error(objectMapper, message, errorCode, status);
    }

    protected final String errorJson(String message, String errorCode, int status,
                                     Map<String, Object> extras) {
        return AiToolResponseHelper.error(objectMapper, message, errorCode, status, extras);
    }

    protected final String successJson(Map<String, Object> body, String fallbackMessage) {
        return AiToolResponseHelper.success(objectMapper, body, fallbackMessage);
    }

    protected final String readOnlySuccessJson(Map<String, Object> body, String fallbackMessage) {
        return AiToolResponseHelper.success(objectMapper, body, fallbackMessage, false);
    }

    protected final String acceptedAsyncTaskJson(Map<String, Object> body,
                                                 long taskId,
                                                 String statusTool) {
        return AiToolResponseHelper.acceptedAsyncTask(objectMapper, body, taskId, statusTool);
    }

    protected final String acceptedAsyncTaskResultUnavailable(long taskId, String statusTool) {
        return AiToolResponseHelper.acceptedAsyncTaskResultUnavailable(
                objectMapper, taskId, statusTool);
    }

    protected final String asyncTaskDispatchOutcomeUnknown(long taskId, String statusTool) {
        return AiToolResponseHelper.asyncTaskDispatchOutcomeUnknown(
                objectMapper, taskId, statusTool);
    }

    protected final String successJson(String fallbackMessage) {
        return AiToolResponseHelper.success(objectMapper, fallbackMessage);
    }

    protected final <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    protected final String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
