package cn.edu.nju.Iot_Verify.component.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatToolProgressPresenterActionReceiptTest {

    private final ChatToolProgressPresenter presenter =
            new ChatToolProgressPresenter(new ObjectMapper());

    @Test
    void ordinaryUsableResultDoesNotRepeatTheSuccessStatus() {
        assertNull(presenter.toolProgressDetail("list_rules", "{\"count\":0}", true));
        assertNull(presenter.toolProgressDetail("list_rules", "{\"count\":0}", false));
    }

    @Test
    void operationOnlyResultKeepsItsStatusWithoutAddingAnActionSummary() {
        String result = "{\"operation\":\"deleted\"}";

        assertEquals("工具操作结果：deleted。", presenter.toolProgressDetail("delete_template", result, true));
        assertEquals("Tool operation result: deleted.", presenter.toolProgressDetail("delete_template", result, false));
        ChatToolProgressPresenter.ActionReceipt receipt = presenter
                .actionReceipt("delete_template", result, true)
                .orElseThrow();
        assertEquals("TEMPLATES_UPDATED", receipt.assistantAction());
        assertEquals(java.util.List.of("template_list"), receipt.refreshTargets());
        assertNull(receipt.summary());
    }

    @Test
    void previewsAndRuleReorderingAreNeverReportedAsDeletion() {
        String rulePreview = presenter.toolProgressDetail(
                "manage_rule", "{\"operation\":\"preview\"}", true);
        String ruleReorder = presenter.toolProgressDetail(
                "manage_rule", "{\"operation\":\"reordered\",\"ruleCount\":3}", true);
        String specPreview = presenter.toolProgressDetail(
                "manage_spec", "{\"operation\":\"preview\"}", true);

        assertTrue(rulePreview.contains("预览"));
        assertTrue(ruleReorder.contains("调整规则执行顺序"));
        assertTrue(specPreview.contains("预览"));
        assertFalse(rulePreview.contains("已删除"));
        assertFalse(ruleReorder.contains("已删除"));
        assertFalse(specPreview.contains("已删除"));
    }

    @Test
    void confirmedActionProducesTheSameExactSummaryUsedForProgress() {
        String result = "{\"operation\":\"cleared\",\"removedDeviceCount\":2,"
                + "\"removedRuleCount\":3,\"removedSpecificationCount\":1}";

        ChatToolProgressPresenter.ActionReceipt receipt = presenter
                .actionReceipt("clear_board", result, true)
                .orElseThrow();

        assertEquals("BOARD_CLEARED", receipt.assistantAction());
        assertEquals(presenter.toolProgressDetail("clear_board", result, true), receipt.summary());
        assertEquals(java.util.List.of("board_state"), receipt.refreshTargets());
    }

    @Test
    void clearingEditHistoryProducesAnExactSummaryOnlyReceipt() {
        String result = "{\"operation\":\"history_cleared\",\"clearedEntryCount\":4,"
                + "\"canUndo\":false,\"canRedo\":false}";

        ChatToolProgressPresenter.ActionReceipt receipt = presenter
                .actionReceipt("manage_board_history", result, true)
                .orElseThrow();

        assertEquals(null, receipt.assistantAction());
        assertTrue(receipt.summary().contains("已清除 4 条撤销/重做记录"));
        assertEquals(java.util.List.of("board_state"), receipt.refreshTargets());
    }

    @Test
    void unavailableMutationResultIsVisibleAsUnconfirmedInsteadOfGenericSuccess() {
        String result = "{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false,"
                + "\"mutationMayHaveCommitted\":true,\"errorCode\":\"TOOL_RESULT_MALFORMED\","
                + "\"message\":\"refresh\"}";

        String detail = presenter.toolProgressDetail("manage_rule", result, true);

        assertTrue(detail.contains("结果未能确认"));
        assertTrue(detail.contains("重新读取"));
        assertFalse(detail.contains("结构化结果"));
    }

    @Test
    void structuredErrorWithoutMessageIsStillPresentedAsFailure() {
        String detail = presenter.toolProgressDetail(
                "manage_rule", "{\"errorCode\":\"BUSINESS_ERROR\",\"status\":409}", false);

        assertTrue(detail.contains("BUSINESS_ERROR"));
        assertTrue(detail.contains("could not complete"));
        assertFalse(detail.contains("structured result"));
    }

    @Test
    void actionReceiptRejectsUnavailableOrErrorPayloadEvenWhenItContainsAnOperation() {
        String unavailable = "{\"operation\":\"cleared\",\"resultStatus\":\"RESULT_UNAVAILABLE\","
                + "\"resultAvailable\":false,\"mutationMayHaveCommitted\":true}";
        String error = "{\"operation\":\"cleared\",\"errorCode\":\"BUSINESS_ERROR\",\"status\":409}";

        assertTrue(presenter.actionReceipt("clear_board", unavailable, true).isEmpty());
        assertTrue(presenter.actionReceipt("clear_board", error, true).isEmpty());
    }

    @Test
    void verificationReceiptRequiresConfirmedRunHistoryPersistence() {
        String saved = "{\"outcome\":\"SATISFIED\",\"historyPersistence\":{"
                + "\"status\":\"SAVED\",\"runId\":41}}";
        String failed = "{\"outcome\":\"SATISFIED\",\"historyPersistence\":{"
                + "\"status\":\"FAILED\",\"message\":\"store unavailable\"}}";
        String unknown = "{\"outcome\":\"SATISFIED\",\"historyPersistence\":{"
                + "\"status\":\"OUTCOME_UNKNOWN\"}}";

        ChatToolProgressPresenter.ActionReceipt receipt = presenter
                .actionReceipt("verify_model", saved, true)
                .orElseThrow();
        assertEquals("FORMAL_VERIFICATION_RUN", receipt.assistantAction());
        assertNull(receipt.summary());
        assertTrue(presenter.actionReceipt("verify_model", failed, true).isEmpty());
        assertTrue(presenter.actionReceipt("verify_model", unknown, true).isEmpty());
    }
}
