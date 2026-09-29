package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.RuleFixer;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.configure.FixConfig;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.board.CollectionMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.ConditionAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.FaultLocalizationResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixApplyResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixResultDto;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterAdjustment;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRangeSelection;
import cn.edu.nju.Iot_Verify.dto.fix.TemplateSnapshotComparison;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.model.TemplateSnapshotBundleDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.po.TracePo;
import cn.edu.nju.Iot_Verify.repository.TraceRepository;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.service.FormalOperationAdmission;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import cn.edu.nju.Iot_Verify.util.mapper.TraceMapper;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FixServiceImplTest {

    @Mock private TraceRepository traceRepository;
    @Mock private TraceMapper traceMapper;
    @Mock private SmvGenerator smvGenerator;
    @Mock private RuleFixer ruleFixer;
    @Mock private BoardStorageService boardStorageService;
    @Mock private BoardDataConverter boardDataConverter;
    @Mock private cn.edu.nju.Iot_Verify.service.FixSuggestionTokenService fixSuggestionTokenService;
    @Mock private FormalOperationAdmission formalOperationAdmission;

    private FixServiceImpl fixService;
    private List<DeviceVerificationDto> currentDevices;
    private List<SpecificationDto> currentSpecs;
    private List<DeviceNodeDto> currentNodes;
    private List<BoardEnvironmentVariableDto> currentEnvironment;
    private Map<String, DeviceManifest> currentTemplateManifests;

    @BeforeEach
    void setUp() {
        FixConfig fixConfig = new FixConfig();
        fixConfig.setMaxAttempts(20);
        lenient().when(formalOperationAdmission.execute(anyLong(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get());
        lenient().when(fixSuggestionTokenService.verify(
                        anyLong(), anyLong(), anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        fixService = new FixServiceImpl(traceRepository, traceMapper, smvGenerator, ruleFixer, fixConfig,
                boardStorageService, boardDataConverter, fixSuggestionTokenService, formalOperationAdmission);
        DeviceManifest manifest = DeviceManifest.builder().name("t1").build();
        currentDevices = List.of();
        currentSpecs = List.of();
        currentNodes = List.of();
        currentEnvironment = List.of();
        currentTemplateManifests = Map.of("t1", manifest);
        lenient().when(smvGenerator.captureDeviceModel(anyLong(), anyList()))
                .thenReturn(new SmvGenerator.CapturedDeviceModel(Map.of("t1", manifest), Map.of()));
        lenient().when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap()))
                .thenReturn(Map.of());
        lenient().when(boardDataConverter.getModelInputSnapshot(anyLong()))
                .thenAnswer(invocation -> currentModelSnapshot());
        lenient().when(boardDataConverter.toModelInputSnapshot(any()))
                .thenAnswer(invocation -> currentModelSnapshot());
    }

    private void attachTemplateSnapshot(TracePo po, String... deviceIds) {
        Map<String, ModelTokenSource> tokenSources = new java.util.LinkedHashMap<>();
        for (String deviceId : deviceIds) {
            tokenSources.put(deviceId, ModelTokenSource.BUNDLED);
        }
        po.setTemplateSnapshotsJson(JsonUtils.toJson(TemplateSnapshotBundleDto.captured(
                Map.of("t1", DeviceManifest.builder().name("t1").build()), tokenSources)));
    }

    private SmvGenerator.CapturedDeviceModel changedTemplateSnapshot() {
        DeviceManifest changed = DeviceManifest.builder()
                .name("t1")
                .description("changed after the run")
                .build();
        return new SmvGenerator.CapturedDeviceModel(Map.of("t1", changed), Map.of());
    }

    /**
     * Stub the spec/device-drift guard's reads so the CURRENT board equals the trace snapshot (no
     * spec/device drift). The snapshot in the apply fixtures has one device {@code sensor}/{@code t1}
     * and no specs, so mirror that. lenient() because tests that reject BEFORE the guard (strategy
     * mismatch, recompute failure) never reach these reads.
     */
    private void stubNoSpecDeviceDrift() {
        DeviceVerificationDto d = new DeviceVerificationDto();
        d.setVarName("sensor");
        d.setTemplateName("t1");
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor");
        node.setLabel("Kitchen Sensor");
        currentDevices = List.of(d);
        currentSpecs = List.of();
        currentNodes = List.of(node);
        currentEnvironment = List.of();
    }

    /**
     * Stub updateRules to run the mutator against the given current rules inside the "transaction",
     * mirroring the real read→mutate→save. Returns the mutator's output (what would be persisted).
     */
    private void stubUpdateRules(List<RuleDto> currentRules) {
        when(boardStorageService.updateRulesAgainstSnapshot(anyLong(), any())).thenAnswer(inv -> {
            java.util.function.Function<BoardSemanticSnapshotDto, List<RuleDto>> mutator = inv.getArgument(1);
            List<RuleDto> saved = mutator.apply(storageSnapshot(currentRules));
            return CollectionMutationResultDto.of("updated", null, saved)
                    .withUndoAvailability(true, false);
        });
    }

    private BoardSemanticSnapshotDto storageSnapshot(List<RuleDto> rules) {
        return new BoardSemanticSnapshotDto(
                currentNodes, currentEnvironment, rules, currentSpecs, List.of());
    }

    private ModelInputSnapshot currentModelSnapshot() {
        return new ModelInputSnapshot(
                currentNodes,
                currentDevices,
                currentEnvironment,
                List.of(),
                currentSpecs,
                currentTemplateManifests);
    }

    private void setupTraceContext() {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "d1");
        po.setRequestJson("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));

        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setModelComplete(true);
        when(traceMapper.toDto(po)).thenReturn(traceDto);
        when(ruleFixer.fix(anyLong(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyMap(), anyLong(), any(AttackScenarioDto.class), anyBoolean(), any(), anyInt(), any()))
                .thenReturn(FixResultDto.builder().fixable(false).build());
    }

    @Test
    void localizeFault_restoresBundledTokenSourceFromFrozenSnapshot() {
        FaultRuleDto fault = localizeFaultWithSnapshot(Map.of("sensor", ModelTokenSource.BUNDLED), false);
        assertEquals(ModelTokenSource.BUNDLED, fault.getModelTokenSource());
        assertEquals("off", fault.getTargetActionLabel());
    }

    @Test
    void localizeFault_preservesCustomTokenSourceFromFrozenSnapshot() {
        FaultRuleDto fault = localizeFaultWithSnapshot(Map.of("sensor", ModelTokenSource.CUSTOM), false);
        assertEquals(ModelTokenSource.CUSTOM, fault.getModelTokenSource());
        assertEquals("Turn power off", fault.getTargetActionLabel());
    }

    @Test
    void localizeFault_rejectsMalformedPersistedTemplateSnapshot() {
        assertThrows(PersistedDataIntegrityException.class,
                () -> localizeFaultWithSnapshot(Map.of("sensor", ModelTokenSource.BUNDLED), true));
    }

    @Test
    void localizeFault_rejectsMissingOrUnknownFrozenDeviceTokenSource() {
        assertThrows(PersistedDataIntegrityException.class,
                () -> localizeFaultWithSnapshot(Map.of(), false));
        assertThrows(PersistedDataIntegrityException.class,
                () -> localizeFaultWithSnapshot(Map.of("sensor", ModelTokenSource.UNKNOWN), false));
    }

    @Test
    void localizeFault_rejectsFrozenSnapshotKeysThatDoNotMatchRequest() {
        assertThrows(PersistedDataIntegrityException.class,
                () -> localizeFaultWithSnapshot(Map.of("other", ModelTokenSource.BUNDLED), false));
    }

    @Test
    void localizeFault_rejectsPersistedRequestWithoutAttackScenario() {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        po.setRequestJson("{\"devices\":[{\"varName\":\"sensor\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[],\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));
        when(traceMapper.toDto(po)).thenReturn(new TraceDto());

        PersistedDataIntegrityException error = assertThrows(PersistedDataIntegrityException.class,
                () -> fixService.localizeFault(1L, 1L));

        assertEquals("requestJson", error.getField());
        assertTrue(error.getMessage().contains("Attack scenario is required"));
    }

    private FaultRuleDto localizeFaultWithSnapshot(
            Map<String, ModelTokenSource> tokenSources, boolean malformedSnapshot) {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        DeviceManifest manifest = DeviceManifest.builder().name("t1").build();
        po.setTemplateSnapshotsJson(malformedSnapshot
                ? JsonUtils.toJson(Map.of("t1", manifest))
                : tokenSources.containsValue(ModelTokenSource.UNKNOWN)
                ? "{\"schemaVersion\":1,\"manifests\":{\"t1\":{\"Name\":\"t1\"}},"
                        + "\"modelTokenSourcesByDeviceId\":{\"sensor\":\"UNKNOWN\"}}"
                : JsonUtils.toJson(TemplateSnapshotBundleDto.captured(
                        Map.of("t1", manifest), tokenSources)));
        po.setRequestJson("{\"devices\":[{\"varName\":\"sensor\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));

        TraceDto trace = new TraceDto();
        trace.setViolatedSpecId("s0");
        trace.setModelComplete(true);
        when(traceMapper.toDto(po)).thenReturn(trace);
        lenient().doAnswer(invocation -> {
                    List<DeviceVerificationDto> devices = invocation.getArgument(0);
                    DeviceVerificationDto input = devices.get(0);
                    DeviceSmvData smv = new DeviceSmvData();
                    smv.setVarName(input.getVarName());
                    smv.setModelTokenSource(input.getModelTokenSource());
                    return Map.of(smv.getVarName(), smv);
                }).when(smvGenerator).buildDeviceSmvMapFromTemplateSnapshots(anyList(), anyMap());
        lenient().when(ruleFixer.localizeFaults(any(), anyList(), any(), any(), anyMap()))
                .thenReturn(List.of(FaultRuleDto.builder()
                        .ruleString("Rule")
                        .transitionNumber(1)
                        .targetDeviceId("sensor")
                        .targetDeviceLabel("Sensor")
                        .targetActionId("off")
                        .targetActionLabel("Turn power off")
                        .reasonCode("TRIGGERED")
                        .reason("Triggered")
                        .build()));

        return fixService.localizeFault(1L, 1L).getFaultRules().get(0);
    }

    // ---- P0: strategies passthrough ----

    @Test
    void fix_nullStrategies_passesNullToRuleFixer() {
        setupTraceContext();
        fixService.fix(1L, 1L, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(ruleFixer).fix(anyLong(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyMap(), anyLong(), any(AttackScenarioDto.class), anyBoolean(), captor.capture(), anyInt(), any());
        assertNull(captor.getValue());
        verify(formalOperationAdmission).execute(eq(1L), any());
    }

    @Test
    void fix_explicitStrategies_passesToRuleFixer() {
        setupTraceContext();
        fixService.fix(1L, 1L, List.of("remove"), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(ruleFixer).fix(anyLong(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyMap(), anyLong(), any(AttackScenarioDto.class), anyBoolean(), captor.capture(), anyInt(), any());
        assertEquals(List.of("remove"), captor.getValue());
    }

    @Test
    void fix_emptyStrategies_isRejectedBeforeLoadingTrace() {
        BadRequestException error = assertThrows(BadRequestException.class,
                () -> fixService.fix(1L, 1L, List.of(), null));

        assertTrue(error.getMessage().contains("omit it to use the default order"));
        verifyNoInteractions(traceRepository);
    }

    @Test
    void fix_duplicateStrategies_isRejectedBeforeLoadingTrace() {
        assertThrows(BadRequestException.class,
                () -> fixService.fix(1L, 1L, List.of("parameter", "parameter"), null));

        verifyNoInteractions(traceRepository);
    }

    @Test
    void applyFix_unsupportedStrategy_isRejectedBeforeLoadingTrace() {
        assertThrows(BadRequestException.class,
                () -> fixService.applyFix(1L, 1L, "disable", null, "signed-token", null));

        verifyNoInteractions(traceRepository);
        verifyNoInteractions(fixSuggestionTokenService);
    }

    @Test
    void missingSourceCompleteness_isExplainedAndNeverProducesOrAppliesAFix() {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "d1");
        po.setRequestJson("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));

        ModelGenerationIssueDto issue = ModelGenerationIssueDto.builder()
                .issueType("SOURCE_COMPLETENESS_UNKNOWN")
                .itemLabel("Saved verification")
                .reason("The saved run predates explicit completeness metadata.")
                .build();
        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setStates(List.of());
        traceDto.setGenerationIssues(List.of(issue));
        when(traceMapper.toDto(po)).thenReturn(traceDto);
        when(ruleFixer.localizeFaults(anyList(), anyList(), any(), any(), anyMap())).thenReturn(List.of());

        FaultLocalizationResultDto localization = fixService.localizeFault(1L, 1L);
        FixResultDto result = fixService.fix(1L, 1L, null, null);

        assertFalse(localization.isSourceModelComplete());
        assertEquals(List.of(issue), localization.getSourceGenerationIssues());
        assertTrue(localization.getWarnings().get(0).contains("does not contain explicit"));
        assertFalse(result.isFixable());
        assertEquals(List.of(issue), result.getSourceGenerationIssues());
        assertTrue(result.getStrategyAttempts().stream()
                .allMatch(attempt -> "SKIPPED_INCOMPLETE_SOURCE_MODEL".equals(attempt.getStatus())));
        verify(ruleFixer, never()).fix(anyLong(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyMap(), anyLong(), any(AttackScenarioDto.class), anyBoolean(), any(), anyInt(), any());

        clearInvocations(smvGenerator);
        assertThrows(BadRequestException.class,
                () -> fixService.applyFix(
                        1L, 1L, "parameter", verifiedParameterSuggestion("40"), "signed-token", null));
        verify(smvGenerator, never()).buildDeviceSmvMap(anyLong(), anyList());
        verify(boardStorageService, never()).updateRulesAgainstSnapshot(anyLong(), any());
    }

    /**
     * An incomplete source model returns before any strategy runs, so a caller's pinned range is
     * honoured by nothing. Reporting an empty `unusedPreferredRangeSelections` there dropped the
     * user's explicit constraint silently — which is the failure this field exists to surface.
     */
    @Test
    void incompleteSourceModel_reportsEveryPreferredRangeAsUnused() {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "d1");
        po.setRequestJson("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));

        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setStates(List.of());
        traceDto.setModelComplete(false);
        when(traceMapper.toDto(po)).thenReturn(traceDto);
        when(ruleFixer.localizeFaults(anyList(), anyList(), any(), any(), anyMap())).thenReturn(List.of());

        String targetId = "param_abcdefghijklmnopqrstuvwx";
        // Lower == upper is the "lock this threshold exactly" case, the one worst served by
        // dropping the selection without saying so.
        Map<String, PreferredRange> pinned = Map.of(targetId, new PreferredRange(70, 70));

        FixResultDto result = fixService.fix(1L, 1L, null, pinned);

        assertFalse(result.isFixable());
        assertEquals(1, result.getUnusedPreferredRangeSelections().size());
        assertEquals(targetId, result.getUnusedPreferredRangeSelections().get(0).getTargetId());
    }

    @Test
    void fix_environmentPool_passesToRuleFixer() {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "d1");
        po.setRequestJson("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"environmentVariables\":[{\"name\":\"temperature\",\"value\":\"28\",\"trust\":\"trusted\",\"privacy\":\"public\"}],"
                + "\"rules\":[],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));
        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setModelComplete(true);
        when(traceMapper.toDto(po)).thenReturn(traceDto);
        when(ruleFixer.fix(anyLong(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyMap(), anyLong(), any(AttackScenarioDto.class), anyBoolean(), any(), anyInt(), any()))
                .thenReturn(FixResultDto.builder().fixable(false).build());

        fixService.fix(1L, 1L, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BoardEnvironmentVariableDto>> envCaptor = ArgumentCaptor.forClass(List.class);
        verify(ruleFixer).fix(anyLong(), any(), any(), anyList(), anyList(), envCaptor.capture(), anyList(),
                anyMap(), anyLong(), any(AttackScenarioDto.class), anyBoolean(), any(), anyInt(), any());
        assertEquals(1, envCaptor.getValue().size());
        assertEquals("temperature", envCaptor.getValue().get(0).getName());
        assertEquals("28", envCaptor.getValue().get(0).getValue());
    }

    // ---- P3: preferred range target validation ----

    @Test
    void fix_invalidPreferredRangeTargetId_throws400() {
        Map<String, PreferredRange> ranges = Map.of("invalid_key", new PreferredRange(10, 20));
        assertThrows(BadRequestException.class,
                () -> fixService.fix(1L, 1L, null, ranges));
    }

    @Test
    void fix_validPreferredRangeTargetId_passes() {
        setupTraceContext();
        Map<String, PreferredRange> ranges = Map.of(PreferredRangeSelection.targetIdFor(0, 1), new PreferredRange(10, 20));
        assertDoesNotThrow(() -> fixService.fix(1L, 1L, null, ranges));
    }

    @Test
    void fix_preferredRangeNullValue_throws400() {
        Map<String, PreferredRange> ranges = new java.util.HashMap<>();
        ranges.put(PreferredRangeSelection.targetIdFor(0, 0), null);
        assertThrows(BadRequestException.class,
                () -> fixService.fix(1L, 1L, null, ranges));
    }

    @Test
    void fix_preferredRangeLowerGreaterThanUpper_throws400() {
        Map<String, PreferredRange> ranges = Map.of(PreferredRangeSelection.targetIdFor(0, 0), new PreferredRange(40, 10));
        assertThrows(BadRequestException.class,
                () -> fixService.fix(1L, 1L, null, ranges));
    }

    @Test
    void fix_preferredRangeNullLower_throws400() {
        PreferredRange pr = new PreferredRange();
        pr.setUpper(20);
        Map<String, PreferredRange> ranges = Map.of(PreferredRangeSelection.targetIdFor(0, 0), pr);
        assertThrows(BadRequestException.class,
                () -> fixService.fix(1L, 1L, null, ranges));
    }

    // ---- Template drift detection ----

    private void setupTraceContextWithCreatedAt(LocalDateTime createdAt, FixResultDto fixResult) {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "d1");
        po.setRequestJson("{\"devices\":[{\"varName\":\"d1\",\"templateName\":\"t1\"}],"
                + "\"rules\":[],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));

        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setCreatedAt(createdAt);
        traceDto.setModelComplete(true);
        when(traceMapper.toDto(po)).thenReturn(traceDto);
        when(ruleFixer.fix(anyLong(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyMap(), anyLong(), any(AttackScenarioDto.class), anyBoolean(), any(), anyInt(), any()))
                .thenReturn(fixResult);
    }

    @Test
    void fix_templateDrift_appendsWarningToSummary() {
        setupTraceContextWithCreatedAt(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                FixResultDto.builder().fixable(false).summary("Original summary.").build());
        currentTemplateManifests = changedTemplateSnapshot().templateManifests();

        FixResultDto result = fixService.fix(1L, 1L, null, null);

        assertTrue(result.getSummary().contains("WARNING"));
        assertTrue(result.getSummary().startsWith("Original summary."));
        assertEquals(TemplateSnapshotComparison.CHANGED, result.getTemplateSnapshotComparison());
    }

    @Test
    void fix_createdAtNull_stillComparesExactManifestSnapshot() {
        setupTraceContextWithCreatedAt(null,
                FixResultDto.builder().fixable(false).build());

        FixResultDto result = fixService.fix(1L, 1L, null, null);

        assertNotNull(result);
        assertTrue(result.getWarnings() == null || result.getWarnings().isEmpty());
        assertEquals(TemplateSnapshotComparison.UNCHANGED, result.getTemplateSnapshotComparison());
        verify(boardDataConverter).getModelInputSnapshot(1L);
    }

    @Test
    void fix_summaryNull_driftWarningNotCorrupted() {
        setupTraceContextWithCreatedAt(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                FixResultDto.builder().fixable(false).build());
        currentTemplateManifests = changedTemplateSnapshot().templateManifests();

        FixResultDto result = fixService.fix(1L, 1L, null, null);

        assertTrue(result.getSummary().startsWith("WARNING"));
        assertFalse(result.getSummary().contains("null"));
    }

    @Test
    void fix_templateComparisonUnavailable_returnsExplicitWarning() {
        setupTraceContextWithCreatedAt(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                FixResultDto.builder().fixable(false).summary("Original summary.").build());
        when(boardDataConverter.getModelInputSnapshot(1L))
                .thenThrow(new RuntimeException("db down"));

        FixResultDto result = fixService.fix(1L, 1L, null, null);

        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("could not be compared")));
        assertTrue(result.getSummary().contains("apply will remain blocked"));
        assertEquals(TemplateSnapshotComparison.UNAVAILABLE, result.getTemplateSnapshotComparison());
    }

    @Test
    void fix_currentTemplateSetEmpty_reportsConfirmedDrift() {
        setupTraceContextWithCreatedAt(
                LocalDateTime.of(2025, 1, 1, 0, 0),
                FixResultDto.builder().fixable(false).summary("Original summary.").build());
        currentTemplateManifests = Map.of();

        FixResultDto result = fixService.fix(1L, 1L, null, null);

        assertEquals(TemplateSnapshotComparison.CHANGED, result.getTemplateSnapshotComparison());
        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("differ")));
        assertTrue(result.getSummary().contains("verification is run again"));
    }

    @Test
    void templateComparison_emptyLegacyApiAssignmentsDoesNotCreateFalseDrift() throws Exception {
        DeviceManifest.API frozenApi = DeviceManifest.API.builder()
                .name("on")
                .signal(true)
                .startState("")
                .endState("On")
                .build();
        DeviceManifest.API currentApi = DeviceManifest.API.builder()
                .name("on")
                .signal(true)
                .startState("")
                .endState("On")
                .build();
        DeviceManifest frozen = DeviceManifest.builder()
                .name("t1")
                .apis(List.of(frozenApi))
                .build();
        DeviceManifest current = DeviceManifest.builder()
                .name("t1")
                .apis(List.of(currentApi))
                .build();

        java.lang.reflect.Method method = FixServiceImpl.class.getDeclaredMethod(
                "compareTemplateSnapshots", Map.class, Map.class);
        method.setAccessible(true);

        Object comparison = method.invoke(fixService, Map.of("t1", frozen), Map.of("t1", current));

        assertEquals("UNCHANGED", comparison.toString());
    }

    // ---- applyFix ----

    /** Trace snapshot with a single rule: [temperature > 30] -> ac.on. */
    private void setupApplyContextSingleRule() {
        setupApplyContext(null);
    }

    /** Same single-rule fixture with an explicit trace timestamp for persisted-history coverage. */
    private void setupApplyContextWithTraceCreatedAt() {
        setupApplyContext(LocalDateTime.now());
    }

    private void setupApplyContext(LocalDateTime createdAt) {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "sensor");
        po.setRequestJson("{\"devices\":[{\"varName\":\"sensor\",\"templateName\":\"t1\"}],"
                + "\"rules\":[{\"conditions\":[{\"deviceName\":\"sensor\",\"attribute\":\"temperature\","
                + "\"relation\":\">\",\"value\":\"30\"}],\"command\":{\"deviceName\":\"sensor\",\"action\":\"on\"},"
                + "\"ruleString\":\"r0\"}],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));

        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setCreatedAt(createdAt);
        traceDto.setModelComplete(true);
        when(traceMapper.toDto(po)).thenReturn(traceDto);

        // By default the current board matches the snapshot (no spec/device drift). Tests that exercise
        // drift override these stubs.
        stubNoSpecDeviceDrift();
    }

    private RuleDto boardRuleMatchingSnapshot() {
        return RuleDto.builder()
                .id(42L)
                .conditions(new java.util.ArrayList<>(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor").attribute("temperature").relation(">").value("30").build())))
                .command(RuleDto.Command.builder().deviceName("sensor").action("on").build())
                .ruleString("r0")
                .build();
    }

    private FixSuggestionDto verifiedParameterSuggestion(String newValue) {
        return FixSuggestionDto.builder()
                .strategy("parameter")
                .parameterAdjustments(List.of(ParameterAdjustment.builder()
                        .ruleIndex(0).conditionIndex(0).attribute("temperature")
                        .relation(">").originalValue("30").newValue(newValue).build()))
                .build();
    }

    private FixApplyResultDto applySigned(String strategy, FixSuggestionDto suggestion) {
        return fixService.applyFix(1L, 1L, strategy, suggestion, "signed-token", null);
    }

    @Test
    void applyFix_parameter_persistsAdjustedValue() {
        setupApplyContextSingleRule();
        // Atomic read→mutate→save: mutator runs against the current 1-rule board.
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        FixSuggestionDto suggestion = verifiedParameterSuggestion("40");
        FixApplyResultDto result = applySigned("parameter", suggestion);

        assertTrue(result.isApplied());
        assertTrue(result.isVerificationEvidenceReused());
        verify(fixSuggestionTokenService).verify(
                1L, 1L, "parameter", suggestion, "signed-token", null);
        verify(formalOperationAdmission).execute(eq(1L), any());
        verify(formalOperationAdmission).registerCurrentLeaseCommitFence();
        // applyFix returns exactly what was persisted (the mutator's output).
        assertEquals("40", result.getRules().get(0).getConditions().get(0).getValue());
        assertEquals("IF Kitchen Sensor.temperature > 40 THEN Kitchen Sensor.on",
                result.getRules().get(0).getRuleString());
        // Original DB id must be preserved so it updates in place.
        assertEquals(42L, result.getRules().get(0).getId());
    }

    @Test
    void applyFix_specDeviceDriftGuardRunsInsideUpdateRulesMutator() {
        setupApplyContextSingleRule();

        AtomicBoolean insideUpdateRules = new AtomicBoolean(false);
        when(boardDataConverter.toModelInputSnapshot(any())).thenAnswer(inv -> {
            assertTrue(insideUpdateRules.get(), "semantic drift snapshot must be converted inside the rule-write boundary");
            return currentModelSnapshot();
        });
        when(boardStorageService.updateRulesAgainstSnapshot(anyLong(), any())).thenAnswer(inv -> {
            java.util.function.Function<BoardSemanticSnapshotDto, List<RuleDto>> mutator = inv.getArgument(1);
            insideUpdateRules.set(true);
            try {
                List<RuleDto> saved = mutator.apply(storageSnapshot(
                        new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot()))));
                return CollectionMutationResultDto.of("updated", null, saved)
                        .withUndoAvailability(true, false);
            } finally {
                insideUpdateRules.set(false);
            }
        });

        FixApplyResultDto result = applySigned("parameter", verifiedParameterSuggestion("40"));

        assertTrue(result.isApplied());
        assertEquals("40", result.getRules().get(0).getConditions().get(0).getValue());
    }

    @Test
    void applyFix_strategyMismatch_rejected() {
        // strategy != suggestion.strategy is rejected before token verification.
        assertThrows(BadRequestException.class,
                () -> applySigned("remove", verifiedParameterSuggestion("40")));
        verify(boardStorageService, never()).updateRulesAgainstSnapshot(anyLong(), any());
        verifyNoInteractions(fixSuggestionTokenService);
    }

    @Test
    void applyFix_boardDrifted_rejectedAndNotSaved() {
        setupApplyContextSingleRule();
        // Board now has an extra rule -> count mismatch with the 1-rule snapshot. The drift check runs
        // INSIDE updateRules' mutator, so it must throw there and nothing is persisted.
        stubUpdateRules(new java.util.ArrayList<>(
                List.of(boardRuleMatchingSnapshot(), boardRuleMatchingSnapshot())));

        assertThrows(BadRequestException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));
    }

    @Test
    void applyFix_commandContentChanged_treatedAsDrift() {
        setupApplyContextSingleRule();
        // Board rule matches the snapshot on conditions + command device/action, but its command now
        // carries privacy content the snapshot lacked. contentDevice/content drive SMV privacy content
        // migration, so this is a real rule change that the fingerprint must catch as drift.
        RuleDto withContent = boardRuleMatchingSnapshot();
        withContent.setRuleString("IF Kitchen Sensor.temperature > 30 THEN Kitchen Sensor.on");
        withContent.getCommand().setContent("secret");
        withContent.getCommand().setContentDevice("phone");
        stubUpdateRules(new java.util.ArrayList<>(List.of(withContent)));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));
        assertTrue(error.getMessage().contains("IF Kitchen Sensor.temperature > 30 THEN Kitchen Sensor.on"));
        assertTrue(error.getMessage().contains("position 1"));
        assertFalse(error.getMessage().contains("#0"));
    }

    @Test
    void applyFix_templateDrifted_rejectedAndNotApplied() {
        // Current templates differ from the exact manifests that produced the counterexample.
        setupApplyContextWithTraceCreatedAt();
        currentTemplateManifests = changedTemplateSnapshot().templateManifests();
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        assertThrows(BadRequestException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));
        verify(boardStorageService).updateRulesAgainstSnapshot(anyLong(), any());
        verify(smvGenerator, never()).captureDeviceModel(anyLong(), anyList());
    }

    @Test
    void applyFix_noTemplateDrift_applies() {
        // Same setup with a trace timestamp, but no template changed -> apply proceeds normally.
        setupApplyContextWithTraceCreatedAt();
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        FixApplyResultDto result = applySigned("parameter", verifiedParameterSuggestion("40"));

        assertTrue(result.isApplied());
        assertEquals("40", result.getRules().get(0).getConditions().get(0).getValue());
    }

    @Test
    void applyFix_remove_permanentlyRemovesFlaggedRule() {
        setupApplyContextSingleRule();
        FixSuggestionDto remove = FixSuggestionDto.builder()
                .strategy("remove")
                .removedRuleIndices(List.of(0))
                .build();
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        FixApplyResultDto result = applySigned("remove", remove);

        assertTrue(result.isApplied());
        assertTrue(result.getRules().isEmpty());
    }

    // ---- spec/device-only drift guard (the recompute replays the stored context, so it cannot see
    //      spec/device edits on its own; the semantic fingerprint must catch them) ----

    @Test
    void applyFix_specConditionAddedAfterVerify_rejectedAndNotSaved() {
        setupApplyContextSingleRule();
        // The user added a specification to the board after verifying. Rules/templates are unchanged,
        // so the signed suggestion alone cannot detect the drift; the frozen-model check must catch it.
        cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto spec = new cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto();
        spec.setId("s1");
        spec.setTemplateId("1");
        currentSpecs = List.of(spec);
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        assertThrows(BadRequestException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));
        verify(boardStorageService).updateRulesAgainstSnapshot(anyLong(), any());
    }

    @Test
    void applyFix_deviceVariableChangedAfterVerify_rejectedAndNotSaved() {
        setupApplyContextSingleRule();
        // Same device identity/template, but the user edited a device variable value after verifying.
        // The snapshot stored no explicit variables; the current board now carries one → fingerprints differ.
        DeviceVerificationDto edited = new DeviceVerificationDto();
        edited.setVarName("sensor");
        edited.setTemplateName("t1");
        edited.setVariables(List.of(new cn.edu.nju.Iot_Verify.dto.device.VariableStateDto(
                "threshold", "99", "trusted")));
        currentDevices = List.of(edited);
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        assertThrows(BadRequestException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));
        verify(boardStorageService).updateRulesAgainstSnapshot(anyLong(), any());
    }

    @Test
    void applyFix_currentBoardDeviceModelFailsToBuild_failsClosed() {
        setupApplyContextSingleRule();
        // The current board no longer builds a valid device model (e.g. a device's template was deleted).
        // We must fail closed rather than skip the spec/device check. The guard builds the CURRENT map
        // separately from the snapshot map; the current board differs from the snapshot device (sensor),
        // so only the current-board build throws.
        DeviceVerificationDto current = new DeviceVerificationDto();
        current.setVarName("brokenDevice");
        current.setTemplateName("goneTemplate");
        currentDevices = List.of(current);
        when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(eq(List.of(current)), anyMap()))
                .thenThrow(new cn.edu.nju.Iot_Verify.exception.BadRequestException("template gone"));
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        assertThrows(BadRequestException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));
        verify(boardStorageService).updateRulesAgainstSnapshot(anyLong(), any());
    }

    @Test
    void applyFix_currentBoardDeviceModelTemporarilyUnavailable_failsClosedAs503() {
        setupApplyContextSingleRule();
        DeviceVerificationDto current = new DeviceVerificationDto();
        current.setVarName("currentSensor");
        current.setTemplateName("t1");
        currentDevices = List.of(current);
        when(smvGenerator.buildDeviceSmvMapFromTemplateSnapshots(eq(List.of(current)), anyMap()))
                .thenThrow(SmvGenerationException.templateLoadError(
                        "t1", new RuntimeException("db down")));
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));

        assertTrue(ex.getMessage().contains("retry"));
        verify(boardStorageService).updateRulesAgainstSnapshot(anyLong(), any());
    }

    // ---- template-drift fail-closed on apply ----

    @Test
    void applyFix_currentTemplateSetEmpty_rejectsAsConfirmedDrift() {
        setupApplyContextWithTraceCreatedAt();
        // A full-scene clear legitimately produces no referenced manifests. Compared with the
        // non-empty verification snapshot, that is confirmed drift rather than an unavailable read.
        currentTemplateManifests = Map.of();
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRuleMatchingSnapshot())));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> applySigned("parameter", verifiedParameterSuggestion("40")));
        assertTrue(ex.getMessage().contains("re-run verification"));
        verify(boardStorageService).updateRulesAgainstSnapshot(anyLong(), any());
    }

    @Test
    void applyFix_digitLeadingLabel_notFalselyRejected() {
        // The snapshot names the device with the SMV-safe transform (1Lamp -> _1lamp); the persisted
        // board rule carries the raw node id (1Lamp). The apply-time rule fingerprint must canonicalize
        // both sides so an untouched board is NOT rejected as drifted.
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "_1lamp");
        po.setRequestJson("{\"devices\":[{\"varName\":\"_1lamp\",\"templateName\":\"t1\"}],"
                + "\"rules\":[{\"conditions\":[{\"deviceName\":\"_1lamp\",\"attribute\":\"state\","
                + "\"targetType\":\"state\",\"relation\":\"=\",\"value\":\"on\"}],\"command\":{\"deviceName\":\"_1lamp\",\"action\":\"off\"},"
                + "\"ruleString\":\"r0\"}],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));
        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setModelComplete(true);
        when(traceMapper.toDto(po)).thenReturn(traceDto);
        // No spec/device drift: current board mirrors the snapshot device (raw node id 1Lamp).
        DeviceVerificationDto dev = new DeviceVerificationDto();
        dev.setVarName("1Lamp");
        dev.setTemplateName("t1");
        currentDevices = List.of(dev);
        currentSpecs = List.of();

        FixSuggestionDto remove = FixSuggestionDto.builder()
                .strategy("remove").removedRuleIndices(List.of(0)).build();
        // Persisted board rule uses the raw digit-leading node id.
        RuleDto boardRule = RuleDto.builder()
                .id(7L)
                .conditions(new java.util.ArrayList<>(List.of(RuleDto.Condition.builder()
                        .deviceName("1Lamp").targetType("state").attribute("state").relation("=").value("on").build())))
                .command(RuleDto.Command.builder().deviceName("1Lamp").action("off").build())
                .ruleString("r0")
                .build();
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRule)));

        FixApplyResultDto result = applySigned("remove", remove);

        assertTrue(result.isApplied(), "untouched board with a digit-leading node id must not be rejected");
    }

    @Test
    void applyFix_conditionAdd_persistsCurrentBoardNodeId() {
        TracePo po = new TracePo();
        po.setId(1L);
        po.setUserId(1L);
        attachTemplateSnapshot(po, "_1lamp");
        po.setRequestJson("{\"devices\":[{\"varName\":\"_1lamp\",\"templateName\":\"t1\"}],"
                + "\"rules\":[{\"conditions\":[{\"deviceName\":\"_1lamp\",\"attribute\":\"state\","
                + "\"targetType\":\"state\",\"relation\":\"=\",\"value\":\"on\"}],\"command\":{\"deviceName\":\"_1lamp\",\"action\":\"off\"},"
                + "\"ruleString\":\"r0\"}],\"specs\":[],\"attackScenario\":{\"mode\":\"NONE\"},\"enablePrivacy\":false}");
        when(traceRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(po));
        TraceDto traceDto = new TraceDto();
        traceDto.setViolatedSpecId("s0");
        traceDto.setModelComplete(true);
        when(traceMapper.toDto(po)).thenReturn(traceDto);

        DeviceVerificationDto dev = new DeviceVerificationDto();
        // Model-boundary devices carry the NuSMV-safe varName; the persisted canvas node below
        // deliberately keeps the raw id so apply must translate back before saving the rule.
        dev.setVarName("_1lamp");
        dev.setTemplateName("t1");
        currentDevices = List.of(dev);
        currentSpecs = List.of();
        DeviceNodeDto displayNode = new DeviceNodeDto();
        displayNode.setId("1Lamp");
        displayNode.setLabel("Hall Lamp");
        currentNodes = List.of(displayNode);

        FixSuggestionDto addCondition = FixSuggestionDto.builder()
                .strategy("condition")
                .conditionAdjustments(List.of(ConditionAdjustment.builder()
                        .ruleIndex(0)
                        .conditionIndex(1)
                        .action("add")
                        .deviceName("_1lamp")
                        .targetType("state")
                        .attribute("state")
                        .relation("!=")
                        .value("off")
                        .build()))
                .build();

        RuleDto boardRule = RuleDto.builder()
                .id(7L)
                .conditions(new java.util.ArrayList<>(List.of(RuleDto.Condition.builder()
                        .deviceName("1Lamp").targetType("state").attribute("state").relation("=").value("on").build())))
                .command(RuleDto.Command.builder().deviceName("1Lamp").action("off").build())
                .ruleString("r0")
                .build();
        stubUpdateRules(new java.util.ArrayList<>(List.of(boardRule)));

        FixApplyResultDto result = applySigned("condition", addCondition);

        assertTrue(result.isApplied());
        List<RuleDto.Condition> savedConditions = result.getRules().get(0).getConditions();
        assertEquals(2, savedConditions.size());
        assertEquals("1Lamp", savedConditions.get(1).getDeviceName());
        assertEquals("IF Hall Lamp.state = on AND Hall Lamp.state != off THEN Hall Lamp.off",
                result.getRules().get(0).getRuleString());
    }
}
