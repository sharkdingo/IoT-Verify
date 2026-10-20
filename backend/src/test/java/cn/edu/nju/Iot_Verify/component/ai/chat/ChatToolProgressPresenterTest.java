package cn.edu.nju.Iot_Verify.component.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatToolProgressPresenterTest {
    @Test
    void ruleUpdateShowsTheConcreteChangeAndRefreshesRulesWithoutClaimingVerification() {
        String result = """
                {"operation":"updated","verificationStatus":"NOT_VERIFIED","executionOrder":1,"totalRules":2,
                 "rule":{"ruleId":11,"conditions":[{}],"command":{},"description":"IF temperature > 30 THEN cool"}}
                """;
        assertTrue(presenter.toolProgressDetail("manage_rule", result, true).contains("已修改规则"));
        assertTrue(presenter.toolProgressDetail("manage_rule", result, true).contains("temperature > 30"));
        assertFalse(presenter.actionReceipt("manage_rule", result, true).isEmpty());
    }
    @Test
    void schemaPreparationHasFactualFeedbackWithoutClaimingADomainAction() throws Exception {
        ChatToolProgressPresenter presenter = new ChatToolProgressPresenter(new ObjectMapper());
        String result = "{\"operation\":\"toolSchemasPrepared\",\"loadedTools\":[\"verify_model\"],\"count\":1}";
        assertEquals("已准备 1 项相关操作。", presenter.toolProgressDetail("prepare_tools", result, true));
        assertEquals("Prepared 1 requested actions.", presenter.toolProgressDetail("prepare_tools", result, false));
        assertTrue(presenter.actionReceipt("prepare_tools", result, true).isEmpty());
        assertFalse(presenter.hasValidKnownToolPayload("prepare_tools",
                new ObjectMapper().readTree(result.replace("\"count\":1", "\"count\":2"))));
        assertNull(presenter.toolProgressDetail("prepare_tools", result.replace("\"verify_model\"", "null"), true));
    }


    private final ChatToolProgressPresenter presenter = new ChatToolProgressPresenter(new ObjectMapper());

    @Test
    void savedRunAndModelReadsProvideConcreteEvidenceWithoutACompletionReceipt() {
        String history = "{\"count\":2,\"runs\":[{\"dataAvailable\":true},{\"dataAvailable\":false}]}";
        assertEquals("已读取 2 条保存的验证记录，其中 1 条无法查看；这些记录对应保存时的场景。",
                presenter.toolProgressDetail("list_verification_runs", history, true));
        assertNull(presenter.toolProgressDetail("list_verification_runs", "{\"count\":2,\"runs\":[]}", true));
        String page = """
                {"modelSource":"SAVED_RUN","offset":3,"returnedCharacters":2,"totalCharacters":7,
                 "hasMore":true,"nextOffset":5,"modelText":"🏠中"}
                """;
        assertEquals("已读取保存模型第 4–5 个字符（共 7），还有后续内容。",
                presenter.toolProgressDetail("read_run_model", page, true));
        assertNull(presenter.toolProgressDetail("read_run_model", page.replace("\"returnedCharacters\":2", "\"returnedCharacters\":3"), true));
        assertTrue(presenter.actionReceipt("read_run_model", page, true).isEmpty());
        assertTrue(presenter.actionReceipt("list_verification_runs", history, true).isEmpty());
        String run = """
                {"modelComplete":false,"specResults":[{"outcome":"SATISFIED"},
                 {"outcome":"VIOLATED"},{"outcome":"INCONCLUSIVE"}]}
                """;
        assertEquals("已读取保存的验证结果：1 项满足、1 项违反、1 项尚无结论；模型不完整，不能据此判断整个场景通过。",
                presenter.toolProgressDetail("get_verification_run", run, true));
    }

    @Test
    void savedTraceDetailsDescribeHistoricalRecordsAndTheirUnavailableCount() {
        String result = """
                {"count":2,"availableCount":1,"unavailableCount":1,
                 "traces":[{"dataAvailable":true},{"dataAvailable":false}]}
                """;

        assertEquals("已读取 2 条反例记录，其中 1 条无法查看；记录对应保存时的画布。",
                presenter.toolProgressDetail("list_traces", result, true));
        assertEquals("Read 2 saved counterexample records; 1 unavailable. They describe the saved Board snapshot.",
                presenter.toolProgressDetail("list_traces", result, false));
        assertNull(presenter.actionReceipt("list_traces", result, true).orElse(null));
    }

    @Test
    void emptyModelPageDescribesThePageWithoutClaimingTheWholeModelWasRead() {
        String page = """
                {"modelSource":"SAVED_RUN","offset":7,"returnedCharacters":0,"totalCharacters":7,
                 "hasMore":false,"modelText":""}
                """;
        assertEquals("本页位于保存模型的末尾，没有可显示的内容。",
                presenter.toolProgressDetail("read_run_model", page, true));
        assertEquals("This page is at the end of the saved model; there is no text to display.",
                presenter.toolProgressDetail("read_run_model", page, false));
        assertTrue(presenter.actionReceipt("read_run_model", page, true).isEmpty());
    }

    @Test
    void emptyHistoryDoesNotBecomeAClaimThatVerificationPassed() {
        assertEquals("没有已保存的反例记录；这不代表已经通过验证。",
                presenter.toolProgressDetail("list_traces", "{\"count\":0}", true));
    }

    @Test
    void historicalReadShowsRemainingCoverageWithoutReportingANewWrite() {
        assertEquals("已核对 1 条已保存的会话操作记录，还有更早的记录需要读取。",
                presenter.toolProgressDetail("read_conversation_activity",
                        "{\"count\":1,\"activities\":[{}],\"hasMore\":true}", true));
        assertEquals("Read 0 saved conversation operation records; reached the earliest record.",
                presenter.toolProgressDetail("read_conversation_activity",
                        "{\"count\":0,\"activities\":[],\"hasMore\":false}", false));
        assertNull(presenter.toolProgressDetail("read_conversation_activity",
                "{\"count\":1,\"activities\":[],\"hasMore\":false}", true));
        assertTrue(presenter.actionReceipt("read_conversation_activity",
                "{\"count\":1,\"activities\":[{\"appliedAction\":\"SCENE_APPLIED\"}],\"hasMore\":false}", true).isEmpty());
    }

    @Test
    void scenarioAssumptionsRequireCompleteKnownLabelsFromAConfirmationPreview() {
        String preview = """
                {"operation":"preview","requiresUserConfirmation":true,"replacementEnvironmentVariableCount":2,
                 "replacementScene":{"environmentVariables":[{"trust":"trusted","privacy":"private"},
                  {"trust":"untrusted","privacy":"public"}]}}
                """;
        assertTrue(presenter.scenarioEnvironmentAssumptions(preview, true)
                .contains("1 项按可信来源建模，1 项按不可信来源建模；1 项公开，1 项私密"));
        assertNull(presenter.scenarioEnvironmentAssumptions(preview.replace("VariableCount\":2", "VariableCount\":3"), true));
        assertNull(presenter.scenarioEnvironmentAssumptions(preview.replace("untrusted", "unknown"), true));
        assertNull(presenter.scenarioEnvironmentAssumptions(preview.replace("preview", "replaced"), true));
        assertNull(presenter.scenarioEnvironmentAssumptions("not-json", true));
        assertNull(presenter.scenarioEnvironmentAssumptions(null, true));
        assertTrue(presenter.actionReceipt("apply_scenario", preview, true).isEmpty());
    }

    @Test
    void historySummariesDistinguishSavedDraftsPreviewsAndPastChangesWithoutNewReceipts() {
        String history = """
                {"count":6,"hasMore":false,"activities":[
                 {"evidenceStatus":"AVAILABLE","draftStored":true},
                 {"evidenceStatus":"AVAILABLE","requiresUserConfirmation":true},
                 {"evidenceStatus":"AVAILABLE","appliedAction":"SCENE_APPLIED"},
                 {"evidenceStatus":"AVAILABLE","appliedAction":"ENVIRONMENT_UPDATED"},
                 {"evidenceStatus":"FAILED","draftStored":true,"appliedAction":"SCENE_APPLIED"},
                 {"evidenceStatus":"UNAVAILABLE","requiresUserConfirmation":true}]}
                """;
        assertEquals("已核对 6 条已保存的会话操作记录，已读到最早记录。本页明确记录：保存草稿 1 次；确认预览 1 次；历史改动 2 次。",
                presenter.toolProgressDetail("read_conversation_activity", history, true));
        assertEquals("Read 6 saved conversation operation records; reached the earliest record. This page records: 1 saved drafts; 1 confirmation previews; 2 historical changes.",
                presenter.toolProgressDetail("read_conversation_activity", history, false));
        assertTrue(presenter.actionReceipt("read_conversation_activity", history, true).isEmpty());
        assertFalse(presenter.toolProgressDetail("read_conversation_activity", history, true).contains("已应用"));
    }

    @Test
    void repairDetailsKeepSuggestionsSeparateFromAppliedChanges() {
        String result = """
                {"fixable":true,"suggestionCount":1,
                 "suggestions":[{"description":"Reduce the threshold","suggestionToken":"private-token"}]}
                """;

        assertEquals("已读取修复检查结果：1 个候选方案，尚未应用。",
                presenter.toolProgressDetail("fix_violation", result, true));
        assertEquals("Read the repair analysis: 1 candidate suggestion(s); nothing was applied.",
                presenter.toolProgressDetail("fix_violation", result, false));
        assertNull(presenter.actionReceipt("fix_violation", result, true).orElse(null));
    }

    @Test
    void noSuggestionReportsOnlyWhatThisAnalysisReturned() {
        assertEquals("本次检查未提供可直接应用的方案；画布未改变。",
                presenter.toolProgressDetail("fix_violation",
                        "{\"fixable\":false,\"suggestionCount\":0}", true));
    }

    @Test
    void repairSummaryRetainsConcreteThresholdsWithoutClaimingTheyWereApplied() {
        String result = """
                {"fixable":true,"suggestionCount":1,"suggestions":[{
                 "suggestionToken":"private-token","parameterAdjustments":[
                 {"attribute":"CO2","relation":">","originalValue":"1000","newValue":"998"}]}]}
                """;
        for (boolean chinese : List.of(true, false)) {
            String detail = presenter.toolProgressDetail("fix_violation", result, chinese);
            assertTrue(detail.contains("CO2 > 1000 → 998"));
            assertTrue(detail.contains(chinese ? "尚未应用" : "nothing was applied"));
            assertFalse(detail.contains("private-token"));
            assertTrue(presenter.actionReceipt("fix_violation", result, chinese).isEmpty());
        }
    }

    @Test
    void scenePreviewShowsReplacementAndHistoryImpactWithoutAMutationReceipt() {
        String result = """
                {"operation":"preview","requiresUserConfirmation":true,"scenarioName":"温控",
                 "currentDeviceCount":0,"currentEnvironmentVariableCount":0,"currentRuleCount":0,
                 "currentSpecificationCount":0,"editHistoryEntryCount":3,"replacementDeviceCount":2,
                 "replacementEnvironmentVariableCount":2,"replacementRuleCount":2,"replacementSpecificationCount":2}
                """;
        String detail = presenter.toolProgressDetail("apply_scenario", result, true);
        assertTrue(detail.contains("设备 0→2"));
        assertTrue(detail.contains("共享环境变量 0→2"));
        assertTrue(detail.contains("规则 0→2"));
        assertTrue(detail.contains("规约 0→2"));
        assertTrue(detail.contains("将清除 3 条撤销/重做记录"));
        assertTrue(detail.contains("尚未写入"));
        assertTrue(presenter.actionReceipt("apply_scenario", result, true).isEmpty());
    }

    @Test
    void malformedCountsCannotCreateAuthoritativeProgressDetails() {
        for (String result : List.of("{}", "{\"count\":\"2\"}", "{\"count\":-1}",
                "{\"count\":1,\"availableCount\":1,\"unavailableCount\":0,\"traces\":[]}",
                "{\"count\":1,\"availableCount\":1,\"unavailableCount\":1,\"traces\":[{}]}")) {
            assertNull(presenter.toolProgressDetail("list_traces", result, true), result);
        }
        for (String result : List.of("{}", "{\"fixable\":true,\"suggestionCount\":0}",
                "{\"fixable\":false,\"suggestionCount\":1,\"suggestions\":[{}]}",
                "{\"fixable\":true,\"suggestionCount\":\"1\",\"suggestions\":[{}]}",
                "{\"fixable\":true,\"suggestionCount\":1,\"suggestions\":[]}")) {
            assertNull(presenter.toolProgressDetail("fix_violation", result, true), result);
        }
    }

    @Test
    void failedAndUnavailableAnalysisNeverProducesACompletedSearchSummary() {
        assertTrue(presenter.toolProgressDetail("fix_violation", "{\"errorCode\":\"SERVICE_UNAVAILABLE\"}",
                true).contains("未能完成"));
        assertTrue(presenter.toolProgressDetail("fix_violation",
                "{\"resultStatus\":\"RESULT_UNAVAILABLE\",\"resultAvailable\":false}", true)
                .contains("不可用"));
    }
}
