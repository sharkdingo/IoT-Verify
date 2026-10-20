package cn.edu.nju.Iot_Verify.component.aitool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** Shared boundary contract for results whose execution may change persisted state. */
public final class AiToolResultContract {

    private static final Set<String> MUTATION_CAPABLE_TOOLS = Set.of(
            "add_device", "edit_device", "delete_device", "manage_environment", "apply_scenario",
            "reset_default_templates", "manage_spec", "add_template", "delete_template",
            "delete_trace", "cancel_verify_task", "apply_fix", "manage_rule",
            "delete_simulation_trace", "cancel_simulate_task", "verify_model",
            "verify_model_async", "simulate_model_async", "delete_verification_run",
            "dismiss_verify_task", "dismiss_simulate_task", "fuzz_model_async", "cancel_fuzz_task",
            "delete_fuzz_run", "dismiss_fuzz_task", "manage_board_history", "clear_board");

    private AiToolResultContract() {
    }

    public static Set<String> mutationCapableTools() {
        return MUTATION_CAPABLE_TOOLS;
    }

    public static boolean isMutationCapable(String functionName) {
        return functionName != null && MUTATION_CAPABLE_TOOLS.contains(functionName);
    }

    public static boolean hasValidControlFields(JsonNode root) {
        if (root == null || !root.isObject()
                || !optionalText(root, "error")
                || !optionalText(root, "errorCode")
                || !optionalBoolean(root, "requiresUserConfirmation")
                || !optionalBoolean(root, "resultAvailable")
                || !optionalBoolean(root, "mutationMayHaveCommitted")) {
            return false;
        }
        if (root.has("status") && (!root.get("status").isIntegralNumber()
                || !root.get("status").canConvertToInt()
                || root.get("status").intValue() < 100
                || root.get("status").intValue() > 599)) {
            return false;
        }
        if (root.has("resultStatus") != root.has("resultAvailable")) return false;
        if (root.has("resultStatus")) {
            if (!root.get("resultStatus").isTextual()
                    || !Set.of("SUCCESS", "PREVIEW", "RESULT_UNAVAILABLE")
                    .contains(root.get("resultStatus").textValue())) {
                return false;
            }
            boolean available = root.get("resultAvailable").booleanValue();
            if (("RESULT_UNAVAILABLE".equals(root.get("resultStatus").textValue())) == available) {
                return false;
            }
        }
        return !root.has("objectiveStatus")
                || (root.get("objectiveStatus").isTextual()
                && Set.of("COMPLETE", "PARTIAL").contains(root.get("objectiveStatus").textValue()));
    }

    public static boolean isStructuredError(JsonNode root) {
        return hasNonBlankText(root, "error")
                || hasNonBlankText(root, "errorCode")
                || (root != null && root.has("status") && root.path("status").isIntegralNumber()
                && root.path("status").intValue() >= 400);
    }

    public static boolean isUnavailable(JsonNode root) {
        return root != null && ("RESULT_UNAVAILABLE".equals(root.path("resultStatus").asText())
                || (root.has("resultAvailable") && root.path("resultAvailable").isBoolean()
                && !root.path("resultAvailable").booleanValue()));
    }

    /**
     * Checks the smallest authoritative marker that proves a known mutation-capable tool reached
     * its result boundary. A free-form message is deliberately insufficient evidence.
     */
    public static boolean hasValidKnownToolPayload(String functionName, JsonNode root) {
        if (root == null || !root.isObject()) return false;
        if (!isMutationCapable(functionName)) return true;

        return switch (functionName) {
            case "add_device" -> operation(root, "created");
            case "edit_device" -> operation(root, "renamed", "updated", "unchanged");
            case "delete_device" -> operation(root, "preview", "deleted");
            case "manage_environment" -> operation(root, "listed", "updated", "unchanged", "defaults_restored");
            case "apply_scenario" -> operation(root, "preview", "replaced");
            case "reset_default_templates" -> operation(root, "reset")
                    || nestedPreviewDecision(root, "canApply");
            case "manage_spec" -> operation(root, "created", "preview", "deleted");
            case "add_template" -> operation(root, "created");
            case "delete_template" -> operation(root, "deleted")
                    || nestedPreviewDecision(root, "canDelete");
            case "delete_trace", "delete_simulation_trace", "delete_verification_run", "delete_fuzz_run" ->
                    operation(root, "preview") || booleanTrue(root, "deleted");
            case "cancel_verify_task", "cancel_simulate_task", "cancel_fuzz_task" ->
                    booleanField(root, "cancellationAccepted") && positiveId(root, "taskId");
            case "apply_fix" -> operation(root, "preview", "applied");
            case "manage_rule" -> operation(root, "created", "preview", "deleted", "reordered")
                    || (operation(root, "updated") && positiveId(root.path("rule"), "ruleId")
                        && root.path("rule").path("conditions").isArray()
                        && !root.path("rule").path("conditions").isEmpty()
                        && root.path("rule").path("command").isObject()
                        && "NOT_VERIFIED".equals(root.path("verificationStatus").asText())
                        && positiveId(root, "executionOrder") && positiveId(root, "totalRules")
                        && root.path("executionOrder").longValue() <= root.path("totalRules").longValue());
            case "verify_model" -> enumText(root, "outcome", Set.of("SATISFIED", "VIOLATED", "INCONCLUSIVE"));
            case "verify_model_async", "simulate_model_async", "fuzz_model_async" ->
                    booleanTrue(root, "taskAccepted") && positiveId(root, "taskId");
            case "dismiss_verify_task", "dismiss_simulate_task", "dismiss_fuzz_task" ->
                    operation(root, "preview") || booleanTrue(root, "dismissed");
            case "manage_board_history" -> operation(root,
                    "availability", "undone", "redone", "clear_preview", "history_empty", "history_cleared");
            case "clear_board" -> operation(root, "unchanged", "preview", "cleared");
            default -> false;
        };
    }

    private static boolean operation(JsonNode root, String... allowed) {
        String value = root.path("operation").asText("").trim();
        return Set.of(allowed).contains(value);
    }

    private static boolean nestedPreviewDecision(JsonNode root, String field) {
        JsonNode preview = root.path("preview");
        return preview.isObject() && preview.has(field) && preview.get(field).isBoolean();
    }

    private static boolean booleanField(JsonNode root, String field) {
        return root.has(field) && root.get(field).isBoolean();
    }

    private static boolean booleanTrue(JsonNode root, String field) {
        return root.path(field).isBoolean() && root.path(field).booleanValue();
    }

    private static boolean positiveId(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private static boolean enumText(JsonNode root, String field, Set<String> allowed) {
        String value = root.path(field).asText("").trim();
        return !value.isBlank() && allowed.contains(value);
    }

    private static boolean optionalText(JsonNode root, String field) {
        return !root.has(field) || root.get(field).isTextual();
    }

    private static boolean optionalBoolean(JsonNode root, String field) {
        return !root.has(field) || root.get(field).isBoolean();
    }

    private static boolean hasNonBlankText(JsonNode root, String field) {
        if (root == null) return false;
        JsonNode value = root.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }
}
