package cn.edu.nju.Iot_Verify.component.aitool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolResultContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ruleUpdateNeedsACompleteSavedRuleAndExplicitUnverifiedStatus() throws Exception {
        String valid = """
                {"operation":"updated","verificationStatus":"NOT_VERIFIED","executionOrder":1,"totalRules":2,
                 "rule":{"ruleId":11,"conditions":[{}],"command":{"action":"cool"}}}
                """;
        assertTrue(AiToolResultContract.hasValidKnownToolPayload("manage_rule", objectMapper.readTree(valid)));
        assertFalse(AiToolResultContract.hasValidKnownToolPayload("manage_rule",
                objectMapper.readTree("{\"operation\":\"updated\"}")));
        for (String field : java.util.List.of("rule", "verificationStatus", "executionOrder", "totalRules")) {
            var incomplete = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(valid);
            incomplete.remove(field);
            assertFalse(AiToolResultContract.hasValidKnownToolPayload("manage_rule", incomplete), field);
        }
    }

    @Test
    void everyMutationCapableToolHasARecognizedAuthoritativeResultMarker() throws Exception {
        Map<String, String> examples = Map.ofEntries(
                Map.entry("add_device", "{\"operation\":\"created\"}"),
                Map.entry("edit_device", "{\"operation\":\"unchanged\"}"),
                Map.entry("delete_device", "{\"operation\":\"deleted\"}"),
                Map.entry("manage_environment", "{\"operation\":\"defaults_restored\"}"),
                Map.entry("apply_scenario", "{\"operation\":\"replaced\"}"),
                Map.entry("reset_default_templates", "{\"operation\":\"reset\"}"),
                Map.entry("manage_spec", "{\"operation\":\"created\"}"),
                Map.entry("add_template", "{\"operation\":\"created\"}"),
                Map.entry("delete_template", "{\"operation\":\"deleted\"}"),
                Map.entry("delete_trace", "{\"deleted\":true}"),
                Map.entry("cancel_verify_task", "{\"taskId\":1,\"cancellationAccepted\":false}"),
                Map.entry("apply_fix", "{\"operation\":\"applied\"}"),
                Map.entry("manage_rule", "{\"operation\":\"reordered\"}"),
                Map.entry("delete_simulation_trace", "{\"deleted\":true}"),
                Map.entry("cancel_simulate_task", "{\"taskId\":1,\"cancellationAccepted\":true}"),
                Map.entry("verify_model", "{\"outcome\":\"INCONCLUSIVE\"}"),
                Map.entry("verify_model_async", "{\"taskId\":1,\"taskAccepted\":true}"),
                Map.entry("simulate_model_async", "{\"taskId\":1,\"taskAccepted\":true}"),
                Map.entry("delete_verification_run", "{\"deleted\":true}"),
                Map.entry("dismiss_verify_task", "{\"dismissed\":true}"),
                Map.entry("dismiss_simulate_task", "{\"operation\":\"preview\"}"),
                Map.entry("fuzz_model_async", "{\"taskId\":1,\"taskAccepted\":true}"),
                Map.entry("cancel_fuzz_task", "{\"taskId\":1,\"cancellationAccepted\":true}"),
                Map.entry("delete_fuzz_run", "{\"deleted\":true}"),
                Map.entry("dismiss_fuzz_task", "{\"dismissed\":true}"),
                Map.entry("manage_board_history", "{\"operation\":\"history_empty\"}"),
                Map.entry("clear_board", "{\"operation\":\"unchanged\"}"));

        assertEquals(AiToolResultContract.mutationCapableTools(), examples.keySet());
        for (Map.Entry<String, String> example : examples.entrySet()) {
            assertTrue(AiToolResultContract.hasValidKnownToolPayload(
                    example.getKey(), objectMapper.readTree(example.getValue())), example.getKey());
        }
    }

    @Test
    void freeFormMessagesAndMalformedMarkersAreNotCompletionEvidence() throws Exception {
        for (String toolName : AiToolResultContract.mutationCapableTools()) {
            assertFalse(AiToolResultContract.hasValidKnownToolPayload(
                    toolName, objectMapper.readTree("{\"message\":\"done\"}")), toolName);
        }
        assertFalse(AiToolResultContract.hasValidKnownToolPayload(
                "manage_rule", objectMapper.readTree("{\"operation\":\"done\"}")));
        assertFalse(AiToolResultContract.hasValidKnownToolPayload(
                "verify_model_async", objectMapper.readTree("{\"taskId\":0,\"taskAccepted\":true}")));
        assertFalse(AiToolResultContract.hasValidKnownToolPayload(
                "verify_model", objectMapper.readTree("{\"outcome\":\"SUCCESS\"}")));
    }

    @Test
    void malformedExecutionControlFieldsAreRejectedCentrally() throws Exception {
        assertTrue(AiToolResultContract.hasValidControlFields(objectMapper.readTree(
                "{\"resultStatus\":\"SUCCESS\",\"resultAvailable\":true}")));
        assertFalse(AiToolResultContract.hasValidControlFields(objectMapper.readTree(
                "{\"resultStatus\":\"SUCCESS\"}")));
        assertFalse(AiToolResultContract.hasValidControlFields(objectMapper.readTree(
                "{\"errorCode\":123}")));
        assertFalse(AiToolResultContract.hasValidControlFields(objectMapper.readTree(
                "{\"mutationMayHaveCommitted\":\"yes\"}")));
    }
}
