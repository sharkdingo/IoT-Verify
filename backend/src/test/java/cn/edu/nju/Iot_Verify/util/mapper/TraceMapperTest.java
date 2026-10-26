package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.projection.VerificationTraceContextProjection;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import cn.edu.nju.Iot_Verify.repository.projection.TraceSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.TraceTokenSourceProjection;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class TraceMapperTest {

    private TraceTokenSourceProjection tokenSources(Long id) {
        TraceTokenSourceProjection sources = mock(TraceTokenSourceProjection.class);
        when(sources.getId()).thenReturn(id);
        when(sources.getDeviceIdsJson()).thenReturn("[\"d1\"]");
        when(sources.getTokenSourcesJson()).thenReturn("[\"BUNDLED\"]");
        return sources;
    }

    private final TraceMapper mapper = new TraceMapper();
    private VerificationTaskPo run;

    private VerificationTraceContextProjection runContext() {
        return new SpelAwareProxyProjectionFactory().createProjection(VerificationTraceContextProjection.class, run);
    }

    @Test
    void persistedContextRetainsTheDecodedRequestWithoutChangingMappedEvidence() {
        TracePo po = baseTrace(VALID_REQUEST_JSON);

        TraceMapper.PersistedContext context = mapper.readContext(po, runContext());

        assertEquals("d1", context.request().getDevices().get(0).getVarName());
        assertEquals(42L, context.request().getRules().get(0).getId());
        assertEquals(VALID_REQUEST_JSON, run.getRequestJson());
        assertEquals(1, context.trace().getStates().size());
        assertEquals("d1", context.trace().getPlaybackScene().nodes().get(0).getId());
        context.request().getDevices().get(0).setVarName("changed-by-fix");
        assertEquals("d1", context.trace().getPlaybackScene().nodes().get(0).getId());
        assertEquals(VALID_REQUEST_JSON, run.getRequestJson());
    }

    @Test
    void summaryRejectsMissingMismatchedOrPartialFrozenTokenProvenance() {
        TraceSummaryProjection trace = validSummaryProjection(9L);
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toSummaryDto(trace, null));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toSummaryDto(trace, tokenSources(10L)));
        TraceTokenSourceProjection sources = tokenSources(9L);
        when(sources.getTokenSourcesJson()).thenReturn("[]");
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toSummaryDto(trace, sources));
        when(sources.getTokenSourcesJson()).thenReturn("[null]");
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toSummaryDto(trace, sources));
        when(sources.getTokenSourcesJson()).thenReturn("[\"CUSTOM\"]");
        assertEquals(ModelTokenSource.CUSTOM, mapper.toSummaryDto(trace, sources).getModelTokenSourcesByDeviceId().get("d1"));
    }
    private static final String VALID_STATE_JSON = "{\"stateIndex\":1,\"devices\":[],"
            + "\"triggeredRules\":[],\"compromisedAutomationLinks\":[]}";
    private static final String MODEL_SNAPSHOT_JSON = "{\"capturedAt\":\"2026-07-12T09:30:00\","
            + "\"deviceCount\":1,\"ruleCount\":1,\"specificationCount\":1,"
            + "\"environmentVariableCount\":0,\"deviceTemplateCount\":1,\"templatesFrozen\":true}";
    private static final String MODEL_SEMANTICS_JSON = JsonUtils.toJson(
            ModelSemanticsDto.forRun(AttackScenarioDto.none(), false, 1, 1, 0));
    private static final String VALID_REQUEST_JSON =
            "{\"devices\":[{\"varName\":\"d1\",\"deviceLabel\":\"d1\",\"templateName\":\"t1\"}],"
            + "\"playbackNodes\":[{\"id\":\"d1\",\"templateName\":\"t1\",\"label\":\"d1\","
            + "\"position\":{\"x\":0,\"y\":0},\"state\":\"Working\",\"width\":160,\"height\":120}],"
            + "\"rules\":[{\"id\":42,\"conditions\":[{\"deviceName\":\"d1\","
            + "\"attribute\":\"state\",\"targetType\":\"state\",\"relation\":\"=\",\"value\":\"Working\"}],"
            + "\"command\":{\"deviceName\":\"d1\",\"action\":\"stay\"},"
            + "\"ruleString\":\"Rule A\"}]}";

    private static String stateJson(int stateIndex) {
        return "{\"stateIndex\":" + stateIndex + ",\"devices\":[],"
                + "\"triggeredRules\":[],\"compromisedAutomationLinks\":[]}";
    }

    private TracePo baseTrace(String requestJson) {
        run = VerificationTaskPo.builder().id(9L).userId(1L)
                .status(VerificationTaskPo.TaskStatus.COMPLETED)
                .disabledRuleCount(0).skippedSpecCount(0).generationIssuesJson("[]").build();
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        po.setViolatedSpecId("s0");
        po.setViolatedSpecJson("{\"id\":\"s0\",\"name\": \"Safety requirement\", \"templateId\":\"3\",\"templateLabel\":\"Never\","
                + "\"aConditions\":[],\"ifConditions\":[],\"thenConditions\":[],\"devices\":[]}");
        po.setStatesJson("[" + VALID_STATE_JSON + "]");
        po.setStateCount(1);
        po.setVerificationTaskId(9L);
        run.setRequestJson(requestJson != null ? requestJson : VALID_REQUEST_JSON);
        run.setTemplateSnapshotsJson("{\"light\":{\"Name\":\"Light\"}}");
        run.setModelSnapshotJson(MODEL_SNAPSHOT_JSON);
        run.setModelSemanticsJson(MODEL_SEMANTICS_JSON);
        run.setIsAttack(false);
        run.setAttackBudget(0);
        run.setEnablePrivacy(false);
        run.setModeledDeviceAttackPointCount(1);
        run.setModeledFalsifiableReadingDeviceCount(0);
        run.setModeledAutomationLinkAttackPointCount(1);
        return po;
    }

    @Test
    void usesPersistedAttackContextInsteadOfRecountingRequestDevices() {
        TracePo po = baseTrace("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"playbackNodes\":[{\"id\":\"d1\",\"templateName\":\"t1\",\"label\":\"d1\","
                + "\"position\":{\"x\":0,\"y\":0},\"state\":\"Working\",\"width\":160,\"height\":120}],"
                + "\"rules\":[{\"conditions\":[{\"deviceName\":\"d1\",\"attribute\":\"state\","
                + "\"targetType\":\"state\",\"relation\":\"=\",\"value\":\"Working\"}],"
                + "\"command\":{\"deviceName\":\"d1\",\"action\":\"stay\"}}],\"specs\":[],"
                + "\"attackScenario\":{\"mode\":\"ANY_UP_TO_BUDGET\",\"budget\":1},"
                + "\"enablePrivacy\":true}");
        run.setIsAttack(true);
        run.setAttackBudget(1);
        run.setEnablePrivacy(true);
        run.setModelSemanticsJson(JsonUtils.toJson(
                ModelSemanticsDto.forRun(AttackScenarioDto.anyUpToBudget(1), true, 1, 1, 0)));
        run.setModeledDeviceAttackPointCount(1);
        run.setModeledFalsifiableReadingDeviceCount(0);
        run.setModeledAutomationLinkAttackPointCount(1);

        TraceDto dto = mapper.toDto(po, runContext());

        assertEquals(Boolean.TRUE, dto.getAttack());
        assertEquals(1, dto.getAttackBudget());
        assertEquals(Boolean.TRUE, dto.getEnablePrivacy());
        assertEquals(1, dto.getModelSemantics().getModeledDeviceAttackPointCount());
        assertEquals(1, dto.getModelSemantics().getModeledAutomationLinkAttackPointCount());
        assertEquals(2, dto.getModelSemantics().getModeledAttackPointCount());
        assertEquals(1, dto.getModelSnapshot().getDeviceCount());
        assertTrue(dto.getModelSnapshot().isTemplatesFrozen());
        assertEquals(run.getTemplateSnapshotsJson(), mapper.readContext(po, runContext()).templateSnapshotsJson());
        assertEquals("d1", dto.getPlaybackScene().nodes().get(0).getId());
        assertEquals("d1", dto.getPlaybackScene().rules().get(0).getCommand().getDeviceName());
    }

    @Test
    void rejectsInconsistentPersistedDisabledAttackBudget() {
        TracePo po = baseTrace("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[]}");
        run.setIsAttack(false);
        run.setAttackBudget(7);
        run.setEnablePrivacy(false);
        run.setModeledDeviceAttackPointCount(0);
        run.setModeledFalsifiableReadingDeviceCount(0);
        run.setModeledAutomationLinkAttackPointCount(0);

        assertEquals("attackBudget", assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
    }

    @Test
    void traceWithoutPersistedModelContext_isRejected() {
        TracePo po = baseTrace("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[],\"isAttack\":true,\"attackBudget\":1}");
        run.setIsAttack(null);

        assertEquals("isAttack", assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
    }

    @Test
    void unparseableInternalRequestSnapshot_failsClosed() {
        TracePo po = baseTrace("{ not valid json ");

        assertEquals("requestJson", assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
    }

    @Test
    void structurallyInvalidStatesFailClosedInDetail() {
        for (String statesJson : java.util.List.of("[null]", "[{}]")) {
            TracePo po = baseTrace(null);
            po.setStatesJson(statesJson);
            assertEquals("statesJson", assertThrows(
                    PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
        }
    }

    @Test
    void toDto_rejectsStatesWithoutRequiredRuleEventLists() {
        TracePo po = baseTrace(null);
        po.setStatesJson("[{\"stateIndex\":1,\"devices\":[]}]");

        assertEquals("statesJson", assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
    }

    @Test
    void toDto_rejectsRuleEvidenceWithoutUniqueFrozenIndexes() {
        for (String triggeredRules : java.util.List.of(
                "[{\"ruleId\":\"1\"}]",
                "[{\"ruleIndex\":0},{\"ruleIndex\":0}]")) {
            TracePo po = baseTrace(null);
            po.setStatesJson("[{\"stateIndex\":1,\"devices\":[],"
                    + "\"triggeredRules\":" + triggeredRules + ","
                    + "\"compromisedAutomationLinks\":[]}]");

            assertEquals("statesJson", assertThrows(
                    PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
        }
    }

    @Test
    void toDto_bindsRuleEvidenceToTheFrozenRequestSnapshot() {
        TracePo valid = baseTrace(null);
        valid.setStatesJson("[{\"stateIndex\":1,\"devices\":[],"
                + "\"triggeredRules\":[{\"ruleIndex\":0,\"ruleId\":\"42\","
                + "\"ruleLabel\":\"Rule A\"}],\"compromisedAutomationLinks\":[]}]");
        assertDoesNotThrow(() -> mapper.toDto(valid, runContext()));

        for (String evidence : java.util.List.of(
                "{\"ruleIndex\":1}",
                "{\"ruleIndex\":0,\"ruleId\":\"99\",\"ruleLabel\":\"Rule A\"}",
                "{\"ruleIndex\":0,\"ruleId\":\"42\",\"ruleLabel\":\"Forged\"}")) {
            TracePo corrupt = baseTrace(null);
            corrupt.setStatesJson("[{\"stateIndex\":1,\"devices\":[],"
                    + "\"triggeredRules\":[" + evidence + "],"
                    + "\"compromisedAutomationLinks\":[]}]");

            assertEquals("statesJson", assertThrows(
                    PersistedDataIntegrityException.class, () -> mapper.toDto(corrupt, runContext())).getField());
        }
    }

    @Test
    void toDto_rejectsMissingTokenProvenance() {
        TracePo po = baseTrace(null);
        po.setStatesJson("[{\"stateIndex\":1,\"devices\":[{"
                + "\"deviceId\":\"light_1\",\"deviceLabel\":\"Hall light\","
                + "\"templateName\":\"Light\",\"variables\":[{"
                + "\"name\":\"workingState\",\"value\":\"off\"}]}],"
                + "\"triggeredRules\":[],\"compromisedAutomationLinks\":[],"
                + "\"envVariables\":[{\"name\":\"weather\",\"value\":\"sunny\"}]}]");

        assertEquals("statesJson", assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
    }

    @Test
    void nonSequentialStateIndexesFailClosedInDetail() {
        for (String statesJson : java.util.List.of(
                "[" + stateJson(2) + "]",
                "[" + stateJson(1) + "," + stateJson(1) + "]",
                "[" + stateJson(1) + "," + stateJson(3) + "]")) {
            TracePo po = baseTrace(null);
            po.setStatesJson(statesJson);

            assertEquals("statesJson", assertThrows(
                    PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
        }
    }

    @Test
    void incompleteTraceDeviceIdentityFailsClosedInDetail() {
        for (String deviceJson : java.util.List.of(
                "{\"deviceLabel\":\"Hall light\",\"templateName\":\"Light\",\"variables\":[]}",
                "{\"deviceId\":\"light_1\",\"templateName\":\"Light\",\"variables\":[]}",
                "{\"deviceId\":\"light_1\",\"deviceLabel\":\"Hall light\",\"variables\":[]}")) {
            TracePo po = baseTrace(null);
            po.setStatesJson("[{\"stateIndex\":1,\"devices\":[" + deviceJson + "],"
                    + "\"triggeredRules\":[],\"compromisedAutomationLinks\":[]}]");

            assertEquals("statesJson", assertThrows(
                    PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
        }
    }

    @Test
    void mapsPersistedSpecJsonToStructuredApiSnapshot() {
        TracePo po = baseTrace(null);
        po.setViolatedSpecJson("{\"id\":\"s0\",\"name\": \"Safety requirement\", \"templateId\":\"3\",\"templateLabel\":\"Never\","
                + "\"aConditions\":[],\"ifConditions\":[],\"thenConditions\":[],\"devices\":[]}");

        TraceDto dto = mapper.toDto(po, runContext());

        assertNotNull(dto.getViolatedSpec());
        assertEquals("s0", dto.getViolatedSpec().getId());
        assertEquals("Never", dto.getViolatedSpec().getTemplateLabel());
    }

    @Test
    void preservesActualCheckedExpressionForHistoricalPlayback() {
        TracePo po = baseTrace(null);
        po.setCheckedExpression("CTLSPEC AG(!(camera_1.CameraMode=taking_photo))");

        TraceDto dto = mapper.toDto(po, runContext());
        TracePo roundTrip = mapper.toEntity(dto);

        assertEquals(po.getCheckedExpression(), dto.getCheckedExpression());
        assertEquals(po.getCheckedExpression(), roundTrip.getCheckedExpression());
    }

    @Test
    void toEntityPersistsOnlyTraceEvidence() {
        TraceDto dto = new TraceDto();
        dto.setUserId(1L);
        dto.setVerificationTaskId(9L);
        dto.setViolatedSpecId("s0");
        cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto violatedSpec =
                new cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto();
        violatedSpec.setId("s0");
        violatedSpec.setName("Safety requirement");
        violatedSpec.setTemplateId("3");
        dto.setViolatedSpec(violatedSpec);
        dto.setStates(java.util.List.of(new cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto()));
        dto.setAttack(true);
        dto.setAttackBudget(2);
        dto.setEnablePrivacy(false);
        dto.setModelSemantics(cn.edu.nju.Iot_Verify.dto.model.ModelSemanticsDto.forRun(
                AttackScenarioDto.anyUpToBudget(2), false, 1, 2, 1));
        dto.setModelSnapshot(cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto.captured(
                java.time.LocalDateTime.of(2026, 7, 12, 9, 30), 1, 2, 1, 0, 1));

        TracePo po = mapper.toEntity(dto);

        assertEquals("s0", po.getViolatedSpecId());
        assertEquals(1, po.getStateCount());
    }

    @Test
    void summaryProjectionValidatesMetadataAndMapsPersistedStateCount() {
        TraceSummaryProjection projection = validSummaryProjection(8L);

        var summary = mapper.toSummaryDto(projection, tokenSources(8L));

        assertEquals(2, summary.getStateCount());
        assertEquals(4L, summary.getVerificationTaskId());
        assertEquals(ModelTokenSource.BUNDLED, summary.getModelTokenSourcesByDeviceId().get("d1"));
    }

    /**
     * A counterexample summary must not carry the run's model, in any form.
     *
     * <p>Two fields lived here and were removed: the model text itself (a byte-identical duplicate of
     * the owning run's, N+1 copies for N violations) and a {@code hasSmvModel} flag that gated a
     * per-counterexample download button which no longer exists. This asserts the summary stays
     * model-free rather than re-acquiring either — the pull is real, because "this trace's model"
     * reads like a reasonable thing for a trace DTO to expose.
     */
    @Test
    void summaryCarriesNoModelOfItsOwn() {
        var summary = mapper.toSummaryDto(validSummaryProjection(10L), tokenSources(10L));

        for (var method : summary.getClass().getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("smvmodel"),
                    "a counterexample summary must not expose the run's model: " + method.getName());
        }
    }

    @Test
    void summaryProjectionRejectsMissingStateCount() {
        TraceSummaryProjection projection = validSummaryProjection(9L);
        when(projection.getStateCount()).thenReturn(0);

        assertEquals("stateCount", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toSummaryDto(projection, tokenSources(9L))).getField());
    }

    @Test
    void emptyStructuredModelContextFailsClosed() {
        TracePo emptySemantics = baseTrace(null);
        run.setModelSemanticsJson("{}");
        assertEquals("modelSemanticsJson", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(emptySemantics, runContext())).getField());

        TracePo emptySnapshot = baseTrace(null);
        run.setModelSnapshotJson("{}");
        assertEquals("modelSnapshotJson", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toDto(emptySnapshot, runContext())).getField());
    }

    @Test
    void persistedSemanticCountsMustMatchOwnerColumns() {
        TracePo po = baseTrace(null);
        run.setModeledAutomationLinkAttackPointCount(0);

        assertEquals("modelSemanticsJson", assertThrows(
                PersistedDataIntegrityException.class, () -> mapper.toDto(po, runContext())).getField());
    }

    /**
     * A trace carries no model in either direction.
     *
     * <p>Two tests used to pin the opposite — that {@code toDto} read `smv_model_content` back and
     * {@code toEntity} persisted it — because the trace-keyed download endpoint needed it. Both the
     * endpoint and the column are gone: one model is generated per run, the run row owns it, and
     * copying it per counterexample stored the same bytes N+1 times for N violations while the stated
     * justification (evidence surviving its run's deletion) was contradicted by
     * {@code deleteRunInternal}, which deletes traces and run together.
     *
     * <p>Asserted reflectively over both directions, so re-adding the field to the PO or the DTO fails
     * here rather than silently restoring the duplication.
     */
    @Test
    void neitherTraceDirectionCarriesTheRunsModel() {
        for (Class<?> type : java.util.List.of(TraceDto.class, TracePo.class)) {
            for (String field : java.util.List.of("requestJson", "templateSnapshotsJson")) {
                assertThrows(NoSuchFieldException.class, () -> type.getDeclaredField(field));
            }
        }
        for (var method : TraceDto.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("smvmodel"),
                    "TraceDto must not carry the run's model: " + method.getName());
        }
        for (var method : TracePo.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("smvmodel"),
                    "TracePo must not store a per-trace copy of the run's model: " + method.getName());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "OWNER_ID", "USER", "PENDING", "RUNNING", "CANCELLED", "FAILED"})
    void traceContextRequiresTheSameUsersCompletedOwner(String invalidOwner) {
        TracePo po = baseTrace(null);
        switch (invalidOwner) {
            case "MISSING" -> { }
            case "OWNER_ID" -> run.setId(10L);
            case "USER" -> run.setUserId(2L);
            default -> run.setStatus(VerificationTaskPo.TaskStatus.valueOf(invalidOwner));
        }
        assertEquals("verificationTaskId", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toDto(po, invalidOwner.equals("MISSING") ? null : runContext())).getField());
    }

    @Test
    void traceCollectionRejectsAMissingOwnerIdentityExplicitly() {
        TracePo po = baseTrace(null);
        po.setVerificationTaskId(null);
        assertEquals("verificationTaskId", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toDtoList(java.util.List.of(po), java.util.Map.of())).getField());
    }

    @Test
    void allTracesInheritTheOwnersCompletenessInsteadOfRequestDefaults() {
        TracePo po = baseTrace(null);
        run.setDisabledRuleCount(2);
        run.setSkippedSpecCount(1);
        run.setGenerationIssuesJson("[{\"issueType\":\"RULE_DISABLED\",\"itemLabel\":\"Old rule\"}]");
        TraceDto trace = mapper.toDto(po, runContext());
        assertFalse(trace.getModelComplete());
        assertEquals(2, trace.getDisabledRuleCount());
        assertEquals(1, trace.getSkippedSpecCount());
        assertEquals(1, trace.getGenerationIssues().size());
        run.setDisabledRuleCount(null);
        assertEquals("disabledRuleCount", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toDto(po, runContext())).getField());
    }

    @Test
    void traceCollectionDecodesItsSharedOwnerOnce() {
        TracePo first = baseTrace(null);
        TracePo second = baseTrace(null);
        second.setId(2L);
        second.setViolatedSpecId("s2");
        second.setViolatedSpecJson(second.getViolatedSpecJson().replace("s0", "s2"));
        VerificationTraceContextProjection owner = spy(runContext());

        var traces = mapper.toDtoList(java.util.List.of(first, second), java.util.Map.of(9L, owner));

        assertEquals(java.util.List.of(1L, 2L), traces.stream().map(TraceDto::getId).toList());
        assertEquals(java.util.List.of("s0", "s2"), traces.stream().map(TraceDto::getViolatedSpecId).toList());
        verify(owner, times(1)).getModelSnapshotJson();
        verify(owner, times(1)).getModelSemanticsJson();
        verify(owner, times(1)).getGenerationIssuesJson();
        verify(owner, times(1)).getRequestJson();
        verify(owner, times(1)).getTemplateSnapshotsJson();
        assertSame(traces.get(0).getPlaybackScene(), traces.get(1).getPlaybackScene());
    }

    private TraceSummaryProjection validSummaryProjection(Long id) {
        TraceSummaryProjection projection = mock(TraceSummaryProjection.class);
        when(projection.getId()).thenReturn(id);
        when(projection.getVerificationTaskId()).thenReturn(4L);
        when(projection.getViolatedSpecId()).thenReturn("s0");
        when(projection.getViolatedSpecJson()).thenReturn(
                "{\"id\":\"s0\",\"name\": \"Safety requirement\", \"templateId\":\"3\",\"aConditions\":[],"
                        + "\"ifConditions\":[],\"thenConditions\":[],\"devices\":[]}");
        when(projection.getStateCount()).thenReturn(2);
        return projection;
    }
}
