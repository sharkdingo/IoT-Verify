package cn.edu.nju.Iot_Verify.util.mapper;

import cn.edu.nju.Iot_Verify.component.fuzz.FuzzLimitationContract;
import cn.edu.nju.Iot_Verify.util.ModelFingerprint;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.component.fuzz.FuzzModelInputSnapshotCodec;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzEligibilityDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzFindingReplayDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzInputEventDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzIneligibleSpecDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzRunSummaryDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskDto;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzTaskSummaryDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelRunSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.model.RunInitiator;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTrustPrivacyDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.exception.PersistedDataIntegrityException;
import cn.edu.nju.Iot_Verify.po.FuzzFindingPo;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzFindingSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzSpecificationPresentationProjection;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FuzzMapperTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    @AfterAll
    static void closeValidators() { VALIDATORS.close(); }

    private final ModelFingerprint modelFingerprint =
            new ModelFingerprint(new ObjectMapper().findAndRegisterModules());
    private final FuzzMapper mapper = new FuzzMapper(modelFingerprint, VALIDATORS.getValidator());

    @Test
    void mapsCompletedRunWrittenWithVersionedSnapshotEnvelope() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);
        ModelInputSnapshot snapshot = inputSnapshot(List.of(specification));
        run.setModelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(snapshot));

        assertDoesNotThrow(() -> mapper.toRunDto(run, List.of()));
        assertDoesNotThrow(() -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void rejectsFuzzSnapshotsWithoutASemanticFingerprint() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);
        run.setModelSnapshotJson(JsonUtils.toJson(ModelRunSnapshotDto.captured(
                run.getCreatedAt(), 0, 0, 1, 0, 0)));

        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toTaskDto(run));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void rejectsFrozenInputWhoseContentNoLongerMatchesItsFingerprint() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification), List.of("spec-1"), FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(), FuzzOutcome.BUDGET_EXHAUSTED, 0);
        SpecificationDto altered = supportedSpecification("spec-1");
        altered.getAConditions().get(0).setValue("off");
        run.setModelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(
                inputSnapshot(List.of(altered))));

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));

        assertEquals("modelSnapshotJson", error.getField());
    }

    @Test
    void rejectsContradictoryTaskLifecycleMetadata() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification), List.of("spec-1"), FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(), FuzzOutcome.BUDGET_EXHAUSTED, 0);

        run.setStartedAt(null);
        run.setProcessingTimeMs(null);
        assertEquals("startedAt", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toTaskDto(run)).getField());

        run.setStartedAt(run.getCreatedAt());
        run.setProcessingTimeMs(2_000L);
        run.setCompletedAt(run.getCreatedAt().minusSeconds(1));
        assertEquals("completedAt", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toTaskSummaryDto(run)).getField());

        run.setCompletedAt(run.getCreatedAt().plusSeconds(2));
        run.setStatus(FuzzTaskPo.TaskStatus.FAILED);
        run.setOutcome(null);
        run.setErrorMessage(" ");
        assertEquals("errorMessage", assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toTaskDto(run)).getField());
    }

    @Test
    void mapsSnapshotWhoseDeviceUsesANonCanonicalTemplateAlias() {
        SpecificationDto specification = supportedSpecification("spec-1");
        ModelInputSnapshot snapshot = provenanceSnapshot(specification);
        snapshot.devices().get(0).setTemplateName("  sWiTcH  ");
        snapshot.nodes().get(0).setTemplateName("  sWiTcH  ");
        FuzzTaskPo run = completedRun(
                List.of(specification), List.of("spec-1"), FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(), FuzzOutcome.BUDGET_EXHAUSTED, 0);
        run.setModelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(snapshot));
        run.setModelSnapshotJson(JsonUtils.toJson(fuzzSnapshot(run.getCreatedAt(), snapshot)));

        assertDoesNotThrow(() -> mapper.toRunDto(run, List.of()));
    }

    @Test
    void runDetailRejectsMalformedNestedStateAndForgedProvenance() {
        SpecificationDto specification = supportedSpecification("spec-1");
        ModelInputSnapshot snapshot = provenanceSnapshot(specification);
        FuzzTaskPo run = completedRun(
                List.of(specification), List.of("spec-1"), FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(), FuzzOutcome.FOUND_VIOLATION, 1);
        run.setModelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(snapshot));
        run.setModelSnapshotJson(JsonUtils.toJson(fuzzSnapshot(run.getCreatedAt(), snapshot)));

        TraceVariableDto local = traceVariable("battery", ModelTokenSource.BUNDLED);
        TraceVariableDto environment = traceVariable("temperature", ModelTokenSource.BUNDLED);
        TraceVariableDto global = traceVariable("compromisedPointCount", ModelTokenSource.UNKNOWN);
        TraceDeviceDto device = new TraceDeviceDto();
        device.setDeviceId("switch_1");
        device.setDeviceLabel("Switch one");
        device.setTemplateName("Switch");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        device.setVariables(List.of(local));
        TraceTrustPrivacyDto trust = trustPrivacy(
                "battery", "variable", null, true, null);
        TraceTrustPrivacyDto privacy = trustPrivacy(
                "battery", "variable", null, null, "public");
        device.setTrustPrivacy(List.of(trust));
        device.setPrivacies(List.of(privacy));
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of(device))
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .envVariables(List.of(environment))
                .globalVariables(List.of(global))
                .build();
        FuzzFindingPo finding = finding(run, specification);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));

        assertDoesNotThrow(() -> mapper.toFindingDto(run, finding, 1L));

        device.setDeviceLabel(" ");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        device.setDeviceLabel("Switch one");
        local.setValue(null);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        local.setValue("0");
        trust.setMode(" ");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        trust.setMode(null);
        trust.setPrivacy("private");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        trust.setPrivacy(null);
        privacy.setPrivacy("secret");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        privacy.setPrivacy("public");
        global.setModelTokenSource(ModelTokenSource.BUNDLED);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        global.setModelTokenSource(ModelTokenSource.UNKNOWN);
        device.setModelTokenSource(null);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        local.setModelTokenSource(null);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        local.setModelTokenSource(ModelTokenSource.BUNDLED);
        environment.setModelTokenSource(null);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        environment.setModelTokenSource(ModelTokenSource.BUNDLED);
        device.setModelTokenSource(ModelTokenSource.CUSTOM);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        local.setModelTokenSource(ModelTokenSource.CUSTOM);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));

        local.setModelTokenSource(ModelTokenSource.BUNDLED);
        environment.setModelTokenSource(ModelTokenSource.CUSTOM);
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(run, finding, 1L));
    }

    @Test
    void mapsCompletedRunAndEmbedsFindingSummaries() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 14, 9, 30);
        SpecificationDto specification = supportedSpecification("spec-1");
        specification.setTemplateLabel("Never unlock while away");
        ModelInputSnapshot frozenInput = inputSnapshot(List.of(specification));
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        FuzzFindingPo finding = FuzzFindingPo.builder()
                .id(9L)
                .userId(3L)
                .fuzzTaskId(7L)
                .violatedSpecId("spec-1")
                .violatedSpecJson(JsonUtils.toJson(specification))
                .firstViolationStep(0)
                .statesJson(JsonUtils.toJson(List.of(state)))
                .inputEventsJson(JsonUtils.toJson(List.of(FuzzInputEventDto.builder()
                        .step(0)
                        .kind("DEVICE_VARIABLE")
                        .targetId("DoorLock")
                        .property("battery")
                        .value("80")
                        .source("MODEL_CHOICE")
                        .build())))
                .seed(42L)
                .stateCount(1)
                .createdAt(createdAt.plusSeconds(1))
                .build();
        FuzzTaskPo task = FuzzTaskPo.builder()
                .id(7L)
                .userId(3L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .createdAt(createdAt)
                .startedAt(createdAt)
                .completedAt(createdAt.plusSeconds(2))
                .processingTimeMs(2_000L)
                .progress(100)
                .targetSpecIdsJson(JsonUtils.toJson(List.of("spec-1")))
                .maxIterations(1000)
                .pathLength(20)
                .populationSize(10)
                .explorationMode(FuzzExplorationMode.PAPER_COMPATIBLE)
                .modelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(frozenInput))
                .modelSnapshotJson(JsonUtils.toJson(fuzzSnapshot(createdAt, frozenInput)))
                .outcome(FuzzOutcome.FOUND_VIOLATION)
                .effectiveSeed(42L)
                .iterations(12)
                .generatedPaths(120L)
                .elapsedMs(250L)
                .eligibilityJson(JsonUtils.toJson(FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .eligibleSpecLabels(Map.of("spec-1", "Never unlock while away"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build()))
                .limitationsJson(JsonUtils.toJson(
                        FuzzLimitationContract.requiredCodes(FuzzExplorationMode.PAPER_COMPATIBLE)))
                .findingCount(1)
                .build();

        FuzzRunSummaryDto dto = mapper.toRunSummaryDto(task, List.of(finding));
        FuzzRunDto runDetail = mapper.toRunDto(task, List.of(finding));
        FuzzTaskDto taskDetail = mapper.toTaskDto(task);
        FuzzTaskSummaryDto taskSummary = mapper.toTaskSummaryDto(task);

        assertEquals(FuzzOutcome.FOUND_VIOLATION, dto.getOutcome());
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE, dto.getExplorationMode());
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE, runDetail.getExplorationMode());
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE, taskDetail.getExplorationMode());
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE, taskSummary.getExplorationMode());
        assertEquals(42L, dto.getEffectiveSeed());
        assertTrue(dto.isDataAvailable());
        assertEquals(1, dto.getFindingCount());
        assertEquals(1, dto.getFindings().size());
        assertEquals("spec-1", dto.getFindings().get(0).getViolatedSpecId());
        assertEquals(42L, dto.getFindings().get(0).getSeed());
        assertEquals("MODEL_CHOICE", runDetail.getFindings().get(0)
                .getInputEvents().get(0).getSource());
        assertEquals(0, dto.getModelSnapshot().getDeviceCount());
        assertEquals(0, taskSummary.getModelSnapshot().getDeviceCount());
        assertEquals(List.of("spec-1"), taskSummary.getTargetSpecIds());
        assertEquals(Map.of("spec-1", "Never unlock while away"),
                runDetail.getEligibility().getEligibleSpecLabels());
        assertTrue(runDetail.getLimitations().contains(
                "PAPER_PREDECESSOR_STATE_OUTPUTS_THREE_LEVELS_ONLY"));
        assertTrue(runDetail.getLimitations().contains(
                "PAPER_MULTI_TARGET_PRODUCT_EXTENSION"));
        assertTrue(runDetail.getLimitations().contains(
                "ATTACK_TRUST_PRIVACY_CONTENT_UNSUPPORTED"));
    }

    @Test
    void findingSummaryRejectsInconsistentPersistedTraceMetadata() {
        SpecificationDto specification = supportedSpecification("spec-1");
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        FuzzFindingPo finding = FuzzFindingPo.builder()
                .id(10L)
                .userId(3L)
                .fuzzTaskId(7L)
                .violatedSpecId("spec-1")
                .violatedSpecJson(JsonUtils.toJson(specification))
                .firstViolationStep(0)
                .statesJson(JsonUtils.toJson(List.of(state)))
                .inputEventsJson("[]")
                .seed(42L)
                .stateCount(2)
                .createdAt(LocalDateTime.now())
                .build();

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingSummaryDto(finding));

        finding.setStateCount(1);
        finding.setInputEventsJson("[{\"step\":0,\"kind\":\"DEVICE_VARIABLE\","
                + "\"targetId\":\"device-1\",\"property\":\"temperature\",\"value\":\"21\"}]");
        assertThrows(PersistedDataIntegrityException.class, () -> mapper.toFindingDto(finding));

        finding.setInputEventsJson("[{\"step\":0,\"kind\":\"DEVICE_VARIABLE\","
                + "\"targetId\":\"device-1\",\"property\":\"temperature\",\"value\":\"21\","
                + "\"source\":\"MODEL_CHOICE\"}]");
        assertEquals("MODEL_CHOICE", mapper.toFindingDto(finding).getInputEvents().get(0).getSource());

        TraceStateDto trailingState = TraceStateDto.builder()
                .stateIndex(1)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        finding.setStatesJson(JsonUtils.toJson(List.of(state, trailingState)));
        finding.setStateCount(2);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(finding));
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        finding.setStateCount(1);

        finding.setInputEventsJson(JsonUtils.toJson(List.of(FuzzInputEventDto.builder()
                .step(0)
                .kind("DEVICE_STATE")
                .targetId("device-1")
                .property("state")
                .value("on")
                .source("SEED_EVENT")
                .build())));
        assertEquals("SEED_EVENT",
                mapper.toFindingDto(finding).getInputEvents().get(0).getSource());

        finding.setInputEventsJson(JsonUtils.toJson(List.of(FuzzInputEventDto.builder()
                .step(0)
                .kind("ENVIRONMENT_RATE")
                .targetId("environment")
                .property("temperature")
                .value("rate:1")
                .source("UNKNOWN_SOURCE")
                .build())));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(finding));

        finding.setFirstViolationStep(1);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingSummaryDto(finding));

        finding.setFirstViolationStep(0);
        finding.setInputEventsJson(JsonUtils.toJson(List.of(FuzzInputEventDto.builder()
                .step(1)
                .kind("DEVICE_VARIABLE")
                .build())));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingSummaryDto(finding));

        finding.setInputEventsJson(JsonUtils.toJson(List.of(FuzzInputEventDto.builder()
                .step(0)
                .kind("UNSUPPORTED_KIND")
                .targetId("device-1")
                .property("temperature")
                .value("21")
                .build())));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingSummaryDto(finding));
    }

    @Test
    void findingRejectsInputEventsOutsideCausalOrder() {
        SpecificationDto specification = supportedSpecification("spec-1");
        TraceStateDto first = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        TraceStateDto second = TraceStateDto.builder()
                .stateIndex(1)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        FuzzFindingPo finding = FuzzFindingPo.builder()
                .id(11L)
                .violatedSpecId("spec-1")
                .violatedSpecJson(JsonUtils.toJson(specification))
                .firstViolationStep(1)
                .statesJson(JsonUtils.toJson(List.of(first, second)))
                .seed(42L)
                .stateCount(2)
                .createdAt(LocalDateTime.now())
                .build();

        finding.setInputEventsJson(JsonUtils.toJson(List.of(
                inputEvent(0, "RANDOM_INITIAL_STATE"),
                inputEvent(0, "SEED_EVENT"),
                inputEvent(0, "MODEL_CHOICE"),
                inputEvent(1, "SEED_EVENT"),
                inputEvent(1, "MODEL_CHOICE"))));
        assertDoesNotThrow(() -> mapper.toFindingDto(finding));

        finding.setInputEventsJson(JsonUtils.toJson(List.of(
                inputEvent(1, "MODEL_CHOICE"), inputEvent(0, "MODEL_CHOICE"))));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(finding));

        finding.setInputEventsJson(JsonUtils.toJson(List.of(
                inputEvent(0, "MODEL_CHOICE"), inputEvent(0, "SEED_EVENT"))));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(finding));

        finding.setInputEventsJson(JsonUtils.toJson(List.of(
                inputEvent(1, "RANDOM_INITIAL_STATE"))));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(finding));
    }

    @Test
    void persistedRunRejectsInconsistentExecutionStatistics() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);

        run.setIterations(0);
        run.setGeneratedPaths(1L);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
        run.setIterations(1);
        run.setGeneratedPaths(0L);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
        run.setIterations(2);
        run.setGeneratedPaths(1L);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
        run.setGeneratedPaths(3L);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
        run.setPopulationSize(2);
        run.setGeneratedPaths(4L);
        assertDoesNotThrow(() -> mapper.toRunSummaryDto(run, List.of()));
        run.setMaxIterations(1);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void projectedRunRejectsInconsistentExecutionStatistics() {
        assertEquals("statistics", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(
                        projectedRun(0, 1L, 1, 10), List.of(), null)).getField());
        assertEquals("statistics", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(
                        projectedRun(1, 0L, 1, 10), List.of(), null)).getField());
        assertEquals("statistics", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(
                        projectedRun(2, 1L, 1, 10), List.of(), null)).getField());
        assertEquals("statistics", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(
                        projectedRun(2, 3L, 1, 10), List.of(), null)).getField());
        assertEquals("statistics", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(
                        projectedRun(2, 2L, 2, 1), List.of(), null)).getField());
        assertDoesNotThrow(() -> mapper.toRunSummaryDtoFromTaskProjection(
                projectedRun(2, 4L, 2, 10), List.of(), projectedPresentation()));
    }

    @Test
    void runSummaryRejectsOutcomeAndFindingCountContradictions() {
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(7L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .outcome(FuzzOutcome.BUDGET_EXHAUSTED)
                .findingCount(1)
                .build();
        FuzzFindingPo finding = FuzzFindingPo.builder().id(9L).build();

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of(finding)));

        run.setFindingCount(0);
        run.setOutcome(FuzzOutcome.FOUND_VIOLATION);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));

        run.setUserId(3L);
        run.setFindingCount(1);
        FuzzFindingPo crossUserFinding = FuzzFindingPo.builder()
                .id(10L)
                .userId(4L)
                .fuzzTaskId(7L)
                .violatedSpecId("spec-1")
                .build();
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of(crossUserFinding)));
    }

    @Test
    void runSummaryRejectsMissingRequiredScalarsAndEligibilityCountDrift() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 14, 12, 0);
        ModelInputSnapshot frozenInput = inputSnapshot(List.of(supportedSpecification("spec-1")));
        FuzzTaskPo run = FuzzTaskPo.builder()
                .id(12L)
                .userId(3L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .createdAt(createdAt)
                .startedAt(createdAt)
                .completedAt(createdAt.plusSeconds(1))
                .processingTimeMs(1_000L)
                .progress(100)
                .outcome(FuzzOutcome.BUDGET_EXHAUSTED)
                .iterations(1)
                .generatedPaths(1L)
                .elapsedMs(1L)
                .maxIterations(10)
                .pathLength(2)
                .populationSize(1)
                .findingCount(0)
                .targetSpecIdsJson("[]")
                .modelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(frozenInput))
                .modelSnapshotJson(JsonUtils.toJson(fuzzSnapshot(createdAt, frozenInput)))
                .eligibilityJson(JsonUtils.toJson(FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build()))
                .limitationsJson("[]")
                .build();

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));

        run.setEffectiveSeed(42L);
        run.setEligibilityJson(JsonUtils.toJson(FuzzEligibilityDto.builder()
                .eligibleSpecIds(List.of("spec-1"))
                .requestedSpecCount(2)
                .eligibleSpecCount(1)
                .build()));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void runSummaryRejectsUnsupportedEligibleTemplate() {
        SpecificationDto unsupported = supportedSpecification("spec-1");
        unsupported.setTemplateId("6");
        FuzzTaskPo run = completedRun(
                List.of(unsupported),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void runSummaryRejectsTargetsAndFindingsOutsideFrozenSpecifications() {
        SpecificationDto frozen = supportedSpecification("spec-1");
        FuzzTaskPo unknownTargetRun = completedRun(
                List.of(frozen),
                List.of("spec-2"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-2"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(unknownTargetRun, List.of()));

        FuzzTaskPo findingRun = completedRun(
                List.of(frozen),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.FOUND_VIOLATION,
                1);
        SpecificationDto alteredSnapshot = supportedSpecification("spec-1");
        alteredSnapshot.setTemplateLabel("altered persisted specification");
        FuzzFindingPo finding = finding(findingRun, alteredSnapshot);

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(findingRun, List.of(finding)));
    }

    @Test
    void singleFindingDetailStillValidatesItsFrozenRunContext() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.FOUND_VIOLATION,
                1);
        FuzzFindingPo finding = finding(run, specification);

        assertEquals(finding.getId(), mapper.toFindingDto(run, finding, 1L).getId());
        FuzzFindingReplayDto replay = mapper.toFindingReplayDto(run, finding, 1L);
        assertEquals(finding.getId(), replay.getFinding().getId());
        assertNotNull(replay.getModelSnapshot());
        assertNotNull(replay.getPlaybackScene());

        finding.setSeed(43L);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 1L));
    }

    @Test
    void singleFindingDetailRejectsAStoredCountThatDoesNotMatchActualRows() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.FOUND_VIOLATION,
                1);
        FuzzFindingPo finding = finding(run, specification);

        assertDoesNotThrow(() -> mapper.toFindingDto(run, finding, 1L));
        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 2L));

        assertEquals("findingCount", error.getField());
    }

    @Test
    void singleFindingDetailRejectsInvalidRunLifecycle() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.FOUND_VIOLATION,
                1);
        FuzzFindingPo finding = finding(run, specification);
        run.setProgress(99);

        PersistedDataIntegrityException error = assertThrows(
                PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 1L));

        assertEquals("progress", error.getField());
    }

    @Test
    void findingDetailRejectsMissingAndDuplicateRuleEvidenceIndexes() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification), List.of("spec-1"), FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(), FuzzOutcome.FOUND_VIOLATION, 1);
        FuzzFindingPo finding = finding(run, specification);
        TraceTriggeredRuleDto first = TraceTriggeredRuleDto.builder()
                .ruleIndex(0)
                .ruleId("7")
                .ruleLabel("Rule 7")
                .build();
        TraceTriggeredRuleDto duplicate = TraceTriggeredRuleDto.builder()
                .ruleIndex(0)
                .ruleId("8")
                .ruleLabel("Rule 8")
                .build();
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of(first, duplicate))
                .compromisedAutomationLinks(List.of())
                .build();
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(finding));

        first.setRuleIndex(null);
        state.setTriggeredRules(List.of(first));
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(finding));
    }

    @Test
    void findingDetailRejectsRuleEvidenceOutsideOrDifferentFromFrozenRules() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification), List.of("spec-1"), FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(), FuzzOutcome.FOUND_VIOLATION, 1);
        RuleDto frozenRule = RuleDto.builder()
                .id(7L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("switch_1")
                        .attribute("state")
                        .targetType("state")
                        .relation("=")
                        .value("off")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("switch_1")
                        .action("on")
                        .build())
                .ruleString("Frozen rule")
                .build();
        ModelInputSnapshot provenance = provenanceSnapshot(specification);
        ModelInputSnapshot frozenInput = new ModelInputSnapshot(
                provenance.nodes(), provenance.devices(), provenance.environmentVariables(), List.of(frozenRule),
                provenance.specifications(), provenance.templateManifests());
        run.setModelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(frozenInput));
        run.setModelSnapshotJson(JsonUtils.toJson(fuzzSnapshot(run.getCreatedAt(), frozenInput)));
        FuzzFindingPo finding = finding(run, specification);
        TraceTriggeredRuleDto evidence = TraceTriggeredRuleDto.builder()
                .ruleIndex(1)
                .ruleId("7")
                .ruleLabel("Frozen rule")
                .build();
        TraceDeviceDto device = new TraceDeviceDto();
        device.setDeviceId("switch_1");
        device.setDeviceLabel("switch_1");
        device.setTemplateName("Switch");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        device.setVariables(List.of(traceVariable("battery", ModelTokenSource.BUNDLED)));
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of(device))
                .triggeredRules(List.of(evidence))
                .compromisedAutomationLinks(List.of())
                .envVariables(List.of(traceVariable("temperature", ModelTokenSource.BUNDLED)))
                .build();

        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 1L));

        evidence.setRuleIndex(0);
        evidence.setRuleId("8");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 1L));

        evidence.setRuleId("7");
        evidence.setRuleLabel("Altered rule");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 1L));

        evidence.setRuleLabel("Frozen rule");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertDoesNotThrow(() -> mapper.toFindingDto(run, finding, 1L));

        state.setTriggeredRules(List.of());
        state.setCompromisedAutomationLinks(List.of(evidence));
        evidence.setRuleLabel("Altered compromised link");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 1L));

        evidence.setRuleLabel("Frozen rule");
        finding.setStatesJson(JsonUtils.toJson(List.of(state)));
        assertDoesNotThrow(() -> mapper.toFindingDto(run, finding, 1L));
    }

    @Test
    void singleFindingDetailRejectsStateCountBeyondRunPathLength() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.FOUND_VIOLATION,
                1);
        run.setPathLength(1);
        FuzzFindingPo finding = finding(run, specification);
        TraceStateDto first = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        TraceStateDto second = TraceStateDto.builder()
                .stateIndex(1)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        finding.setStatesJson(JsonUtils.toJson(List.of(first, second)));
        finding.setStateCount(2);
        finding.setFirstViolationStep(1);

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toFindingDto(run, finding, 1L));
    }

    @Test
    void runSummaryRejectsEligibilityThatDoesNotPartitionFrozenTargets() {
        SpecificationDto first = supportedSpecification("spec-1");
        SpecificationDto second = supportedSpecification("spec-2");
        FuzzTaskPo run = completedRun(
                List.of(first, second),
                List.of(),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void runSummaryRejectsMissingSemanticLimitationDisclosure() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);
        run.setLimitationsJson(JsonUtils.toJson(List.of(
                "FINITE_TRACE_TEMPLATES_1_3_4_ONLY",
                "ATTACK_TRUST_PRIVACY_CONTENT_UNSUPPORTED")));

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void runSummaryRejectsUnboundedDiagnosticsAndLimitationCount() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzEligibilityDto eligibility = FuzzEligibilityDto.builder()
                .eligibleSpecIds(List.of())
                .eligibleSpecLabels(Map.of())
                .ineligibleSpecs(List.of(FuzzIneligibleSpecDto.builder()
                        .specId("spec-1")
                        .specificationLabel("spec-1")
                        .reasonCode("MODEL_INVALID")
                        .reason("x".repeat(501))
                        .build()))
                .requestedSpecCount(1)
                .eligibleSpecCount(0)
                .build();
        FuzzTaskPo run = completedRun(
                List.of(specification),
                List.of("spec-1"),
                eligibility,
                FuzzOutcome.INCONCLUSIVE,
                0);

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));

        eligibility.getIneligibleSpecs().get(0).setReason("Model is invalid");
        run.setEligibilityJson(JsonUtils.toJson(eligibility));
        List<String> limitations = new java.util.ArrayList<>(FuzzLimitationContract.baseCodes());
        for (int index = limitations.size(); index <= 32; index++) {
            limitations.add("FUTURE_LIMITATION_" + index);
        }
        run.setLimitationsJson(JsonUtils.toJson(limitations));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(run, List.of()));
    }

    @Test
    void projectedImplicitAllEligibilityMustMatchFrozenSpecificationCount() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 15, 10, 0);
        FuzzTaskSummaryProjection run = mock(FuzzTaskSummaryProjection.class);
        when(run.getId()).thenReturn(88L);
        when(run.getUserId()).thenReturn(3L);
        when(run.getInitiator()).thenReturn(RunInitiator.USER);
        when(run.getStatus()).thenReturn(FuzzTaskPo.TaskStatus.COMPLETED);
        when(run.getCreatedAt()).thenReturn(createdAt);
        when(run.getStartedAt()).thenReturn(createdAt);
        when(run.getCompletedAt()).thenReturn(createdAt.plusSeconds(1));
        when(run.getProcessingTimeMs()).thenReturn(1_000L);
        when(run.getProgress()).thenReturn(100);
        when(run.getOutcome()).thenReturn(FuzzOutcome.BUDGET_EXHAUSTED);
        when(run.getEffectiveSeed()).thenReturn(42L);
        when(run.getIterations()).thenReturn(1);
        when(run.getGeneratedPaths()).thenReturn(1L);
        when(run.getElapsedMs()).thenReturn(1L);
        when(run.getMaxIterations()).thenReturn(10);
        when(run.getPathLength()).thenReturn(2);
        when(run.getPopulationSize()).thenReturn(1);
        when(run.getFindingCount()).thenReturn(0);
        when(run.getExplorationMode()).thenReturn(FuzzExplorationMode.BOARD_SNAPSHOT);
        when(run.getTargetSpecIdsJson()).thenReturn("[]");
        when(run.getModelSnapshotJson()).thenReturn(JsonUtils.toJson(
                fuzzSnapshot(createdAt, 0, 0, 2, 0, 0)));
        when(run.getEligibilityJson()).thenReturn(JsonUtils.toJson(FuzzEligibilityDto.builder()
                .eligibleSpecIds(List.of("spec-1"))
                .eligibleSpecLabels(Map.of("spec-1", "Safety property"))
                .ineligibleSpecs(List.of())
                .requestedSpecCount(1)
                .eligibleSpecCount(1)
                .build()));
        when(run.getLimitationsJson()).thenReturn(
                JsonUtils.toJson(FuzzLimitationContract.baseCodes()));

        assertEquals("targetSpecIdsJson", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(), null)).getField());

        when(run.getModelSnapshotJson()).thenReturn(JsonUtils.toJson(
                fuzzSnapshot(createdAt, 0, 0, 1, 0, 0)));
        when(run.getEligibilityJson()).thenReturn(JsonUtils.toJson(FuzzEligibilityDto.builder()
                .eligibleSpecIds(List.of("spec-1"))
                .eligibleSpecLabels(Map.of("spec-1", "Safety\nproperty"))
                .ineligibleSpecs(List.of())
                .requestedSpecCount(1)
                .eligibleSpecCount(1)
                .build()));
        assertEquals("eligibilityJson", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(), null)).getField());
    }

    @Test
    void projectedRunRejectsFindingStateCountBeyondRunPathLength() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 15, 11, 0);
        FuzzTaskSummaryProjection run = mock(FuzzTaskSummaryProjection.class);
        when(run.getId()).thenReturn(89L);
        when(run.getUserId()).thenReturn(3L);
        when(run.getInitiator()).thenReturn(RunInitiator.USER);
        when(run.getStatus()).thenReturn(FuzzTaskPo.TaskStatus.COMPLETED);
        when(run.getCreatedAt()).thenReturn(createdAt);
        when(run.getStartedAt()).thenReturn(createdAt);
        when(run.getCompletedAt()).thenReturn(createdAt.plusSeconds(1));
        when(run.getProcessingTimeMs()).thenReturn(1_000L);
        when(run.getProgress()).thenReturn(100);
        when(run.getOutcome()).thenReturn(FuzzOutcome.FOUND_VIOLATION);
        when(run.getEffectiveSeed()).thenReturn(42L);
        when(run.getIterations()).thenReturn(1);
        when(run.getGeneratedPaths()).thenReturn(1L);
        when(run.getElapsedMs()).thenReturn(1L);
        when(run.getMaxIterations()).thenReturn(10);
        when(run.getPathLength()).thenReturn(1);
        when(run.getPopulationSize()).thenReturn(1);
        when(run.getFindingCount()).thenReturn(1);
        when(run.getExplorationMode()).thenReturn(FuzzExplorationMode.BOARD_SNAPSHOT);
        when(run.getTargetSpecIdsJson()).thenReturn(JsonUtils.toJson(List.of("spec-1")));
        when(run.getModelSnapshotJson()).thenReturn(JsonUtils.toJson(
                fuzzSnapshot(createdAt, 0, 0, 1, 0, 0)));
        when(run.getEligibilityJson()).thenReturn(JsonUtils.toJson(FuzzEligibilityDto.builder()
                .eligibleSpecIds(List.of("spec-1"))
                .eligibleSpecLabels(Map.of("spec-1", "Safety property"))
                .ineligibleSpecs(List.of())
                .requestedSpecCount(1)
                .eligibleSpecCount(1)
                .build()));
        when(run.getLimitationsJson()).thenReturn(
                JsonUtils.toJson(FuzzLimitationContract.baseCodes()));

        FuzzFindingSummaryProjection finding = mock(FuzzFindingSummaryProjection.class);
        when(finding.getId()).thenReturn(31L);
        when(finding.getUserId()).thenReturn(3L);
        when(finding.getFuzzTaskId()).thenReturn(89L);
        when(finding.getViolatedSpecId()).thenReturn("spec-1");
        when(finding.getFirstViolationStep()).thenReturn(1);
        when(finding.getSeed()).thenReturn(42L);
        when(finding.getStateCount()).thenReturn(2);
        when(finding.getCreatedAt()).thenReturn(createdAt.plusNanos(1));

        assertEquals("findings", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(finding), null)).getField());
    }

    @Test
    void missingModeIsRejectedInsteadOfBeingRelabeledAsBoardSnapshot() {
        SpecificationDto specification = supportedSpecification("spec-1");
        FuzzTaskPo legacyRun = completedRun(
                List.of(specification),
                List.of("spec-1"),
                FuzzEligibilityDto.builder()
                        .eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1)
                        .eligibleSpecCount(1)
                        .build(),
                FuzzOutcome.BUDGET_EXHAUSTED,
                0);
        legacyRun.setExplorationMode(null);

        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toTaskDto(legacyRun));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toTaskSummaryDto(legacyRun));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunDto(legacyRun, List.of()));
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDto(legacyRun, List.of()));
    }

    private FuzzSpecificationPresentationProjection projectedPresentation() {
        FuzzSpecificationPresentationProjection presentation = mock(FuzzSpecificationPresentationProjection.class);
        when(presentation.getId()).thenReturn(90L);
        when(presentation.getSpecificationsJson()).thenReturn(JsonUtils.toJson(List.of(supportedSpecification("spec-1"))));
        when(presentation.getModelTokenSourcesJson()).thenReturn("{\"device-1\":\"UNKNOWN\"}");
        return presentation;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void projectedSummaryAcceptsNormalizedMaximumLengthBoardDeviceId(boolean includeDeviceRefs) {
        String rawId = "1".repeat(100);
        assertTrue(VALIDATORS.getValidator().validateValue(DeviceNodeDto.class, "id", rawId).isEmpty());
        String modelId = DeviceNameNormalizer.normalize(rawId);
        assertEquals("_" + rawId, modelId);
        assertTrue(VALIDATORS.getValidator()
                .validateValue(DeviceVerificationDto.class, "varName", modelId).isEmpty());

        FuzzTaskSummaryProjection run = projectedRun(1, 1L, 1, 10);
        FuzzSpecificationPresentationProjection presentation = projectedPresentation();
        ModelInputSnapshot frozen = frozenSpecification(rawId, "Device", 1);
        SpecificationDto specification = frozen.specifications().get(0);
        assertEquals(modelId, frozen.devices().get(0).getVarName());
        assertEquals(modelId, specification.getDevices().get(0).getDeviceId());
        if (!includeDeviceRefs) specification.setDevices(List.of());
        when(presentation.getSpecificationsJson()).thenReturn(JsonUtils.toJson(List.of(specification)));
        when(presentation.getModelTokenSourcesJson()).thenReturn(
                JsonUtils.toJson(Map.of(modelId, ModelTokenSource.BUNDLED)));

        FuzzRunSummaryDto summary = mapper.toRunSummaryDtoFromTaskProjection(run, List.of(), presentation);

        assertEquals(Map.of("_" + rawId, ModelTokenSource.BUNDLED), summary.getModelTokenSourcesByDeviceId());
        assertEquals("_" + rawId,
                summary.getEligibleSpecifications().get("spec-1").getAConditions().get(0).getDeviceId());
    }

    @ParameterizedTest
    @CsvSource({"101, 1", "201, 1", "255, 1", "100, 50"})
    void projectedSummaryAcceptsGeneratedSpecificationPresentation(int labelLength, int conditionCount) {
        String label = "L".repeat(labelLength);
        assertTrue(VALIDATORS.getValidator().validateValue(DeviceNodeDto.class, "label", label).isEmpty());
        ModelInputSnapshot frozen = frozenSpecification("device-1", label, conditionCount);
        SpecificationDto specification = frozen.specifications().get(0);
        if (conditionCount > 1) assertTrue(specification.getFormula().length() > 4000);
        FuzzTaskSummaryProjection run = projectedRun(1, 1L, 1, 10);
        FuzzSpecificationPresentationProjection presentation = projectedPresentation();
        when(presentation.getSpecificationsJson()).thenReturn(JsonUtils.toJson(frozen.specifications()));
        when(presentation.getModelTokenSourcesJson()).thenReturn(
                JsonUtils.toJson(Map.of("device_1", ModelTokenSource.BUNDLED)));

        FuzzRunSummaryDto summary = mapper.toRunSummaryDtoFromTaskProjection(run, List.of(), presentation);

        assertEquals(specification, summary.getEligibleSpecifications().get("spec-1"));
        assertEquals(label, summary.getEligibleSpecifications().get("spec-1").getDevices().get(0).getDeviceLabel());

        FuzzTaskPo fullRun = completedRun(frozen.specifications(), List.of("spec-1"),
                FuzzEligibilityDto.builder().eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1).eligibleSpecCount(1).build(), FuzzOutcome.BUDGET_EXHAUSTED, 0);
        fullRun.setModelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(frozen));
        fullRun.setModelSnapshotJson(JsonUtils.toJson(fuzzSnapshot(fullRun.getCreatedAt(), frozen)));
        assertDoesNotThrow(() -> mapper.toRunDto(fullRun, List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"relation", "conditionReference", "conditionReferenceLength",
            "deviceReference", "deviceReferenceLength", "conditionSide"})
    void frozenSpecificationRejectsMalformedSemanticsInBothReadPaths(String defect) {
        SpecificationDto specification = supportedSpecification("spec-1");
        specification.setDevices(List.of(new SpecificationDto.DeviceRefDto("device-1", "Device", List.of())));
        SpecConditionDto condition = specification.getAConditions().get(0);
        switch (defect) {
            case "relation" -> condition.setRelation("approximately");
            case "conditionReference" -> condition.setDeviceId("");
            case "conditionReferenceLength" -> condition.setDeviceId("x".repeat(RequestLimits.MAX_IDENTIFIER_LENGTH + 1));
            case "deviceReference" -> specification.getDevices().get(0).setDeviceId("");
            case "deviceReferenceLength" -> specification.getDevices().get(0)
                    .setDeviceId("x".repeat(RequestLimits.MAX_IDENTIFIER_LENGTH + 1));
            case "conditionSide" -> condition.setSide("then");
            default -> throw new AssertionError(defect);
        }
        FuzzTaskSummaryProjection run = projectedRun(1, 1L, 1, 10);
        FuzzSpecificationPresentationProjection presentation = projectedPresentation();
        when(presentation.getSpecificationsJson()).thenReturn(JsonUtils.toJson(List.of(specification)));

        assertEquals("specificationPresentation", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(), presentation)).getField());

        FuzzTaskPo fullRun = completedRun(List.of(specification), List.of("spec-1"),
                FuzzEligibilityDto.builder().eligibleSpecIds(List.of("spec-1"))
                        .requestedSpecCount(1).eligibleSpecCount(1).build(), FuzzOutcome.BUDGET_EXHAUSTED, 0);
        assertEquals("modelInputSnapshotJson", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunDto(fullRun, List.of())).getField());
    }

    @Test
    void projectedSummaryRejectsTokenSourceIdBeyondModelLimit() {
        String modelId = "x".repeat(RequestLimits.MAX_IDENTIFIER_LENGTH + 1);
        FuzzTaskSummaryProjection run = projectedRun(1, 1L, 1, 10);
        FuzzSpecificationPresentationProjection presentation = projectedPresentation();
        when(presentation.getModelTokenSourcesJson()).thenReturn(
                JsonUtils.toJson(Map.of(modelId, ModelTokenSource.BUNDLED)));

        assertEquals("modelTokenSourcesByDeviceId", assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(), presentation)).getField());
    }

    private ModelInputSnapshot frozenSpecification(String rawId, String label, int conditionCount) {
        DeviceNodeDto node = playbackNode(rawId, "Device");
        node.setLabel(label);
        node.setState("on");
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Device");
        template.setDefaultTemplate(true);
        template.setManifest(DeviceManifest.builder().name("Device").modes(List.of("MachineState"))
                .workingStates(List.of(DeviceManifest.WorkingState.builder().name("on").build())).build());
        SpecificationDto stored = supportedSpecification("spec-1");
        stored.setTemplateLabel("Always");
        stored.setAConditions(IntStream.range(0, conditionCount).mapToObj(index -> {
            SpecConditionDto condition = supportedSpecification("condition-" + index).getAConditions().get(0);
            condition.setDeviceId(rawId);
            condition.setDeviceLabel(label);
            return condition;
        }).toList());
        stored.setDevices(List.of(new SpecificationDto.DeviceRefDto(rawId, label, List.of())));
        stored.setFormula(SpecificationFormulaPreview.format(stored,
                SpecificationFormulaPreview.context(List.of(node), List.of(template))));
        return new BoardDataConverter(mock(BoardStorageService.class)).toModelInputSnapshot(
                new BoardSemanticSnapshotDto(List.of(node), List.of(), List.of(), List.of(stored), List.of(template)));
    }

    @Test
    void projectedSummaryCarriesFrozenConditionsAndTokenSourcesWithoutLoadingEvidence() {
        FuzzTaskSummaryProjection run = projectedRun(1, 1L, 1, 10);
        FuzzSpecificationPresentationProjection presentation = projectedPresentation();
        SpecificationDto specification = supportedSpecification("spec-1");
        specification.setTemplateId("3");
        specification.setTemplateLabel("Never");
        specification.getAConditions().get(0).setValue("heat");
        when(presentation.getSpecificationsJson()).thenReturn(JsonUtils.toJson(List.of(specification)));
        when(presentation.getModelTokenSourcesJson()).thenReturn("{\"device-1\":\"BUNDLED\"}");
        LocalDateTime capturedAt = run.getCreatedAt();
        when(run.getModelSnapshotJson()).thenReturn(JsonUtils.toJson(
                fuzzSnapshot(capturedAt, 1, 0, 1, 0, 1)));
        when(run.getOutcome()).thenReturn(FuzzOutcome.FOUND_VIOLATION);
        when(run.getFindingCount()).thenReturn(1);
        FuzzFindingSummaryProjection finding = mock(FuzzFindingSummaryProjection.class);
        when(finding.getId()).thenReturn(31L);
        when(finding.getUserId()).thenReturn(3L);
        when(finding.getFuzzTaskId()).thenReturn(90L);
        when(finding.getViolatedSpecId()).thenReturn("spec-1");
        when(finding.getFirstViolationStep()).thenReturn(1);
        when(finding.getSeed()).thenReturn(42L);
        when(finding.getStateCount()).thenReturn(2);
        LocalDateTime completedAt = run.getCompletedAt();
        when(finding.getCreatedAt()).thenReturn(completedAt);
        FuzzRunSummaryDto summary = mapper.toRunSummaryDtoFromTaskProjection(run, List.of(finding), presentation);
        assertEquals(specification, summary.getFindings().get(0).getViolatedSpec());
        assertEquals(specification, summary.getEligibleSpecifications().get("spec-1"));
        assertEquals(Map.of("device-1", ModelTokenSource.BUNDLED), summary.getModelTokenSourcesByDeviceId());

        when(presentation.getModelTokenSourcesJson()).thenReturn("{\"missing-device\":\"BUNDLED\"}");
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(finding), presentation));
        when(presentation.getModelTokenSourcesJson()).thenReturn("{\"device-1\":\"INVENTED\"}");
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(finding), presentation));
        when(presentation.getModelTokenSourcesJson()).thenReturn("{\"device-1\":\"BUNDLED\"}");

        when(presentation.getId()).thenReturn(91L);
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(finding), presentation));
        when(presentation.getId()).thenReturn(90L);
        when(presentation.getSpecificationsJson()).thenReturn("[]");
        assertThrows(PersistedDataIntegrityException.class,
                () -> mapper.toRunSummaryDtoFromTaskProjection(run, List.of(finding), presentation));
    }

    private FuzzTaskSummaryProjection projectedRun(
            int iterations, long generatedPaths, int populationSize, int maxIterations) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 15, 12, 0);
        FuzzTaskSummaryProjection run = mock(FuzzTaskSummaryProjection.class);
        when(run.getId()).thenReturn(90L);
        when(run.getUserId()).thenReturn(3L);
        when(run.getInitiator()).thenReturn(RunInitiator.AI_ASSISTANT);
        when(run.getStatus()).thenReturn(FuzzTaskPo.TaskStatus.COMPLETED);
        when(run.getCreatedAt()).thenReturn(createdAt);
        when(run.getStartedAt()).thenReturn(createdAt);
        when(run.getCompletedAt()).thenReturn(createdAt.plusSeconds(1));
        when(run.getProcessingTimeMs()).thenReturn(1_000L);
        when(run.getProgress()).thenReturn(100);
        when(run.getOutcome()).thenReturn(FuzzOutcome.BUDGET_EXHAUSTED);
        when(run.getExplorationMode()).thenReturn(FuzzExplorationMode.BOARD_SNAPSHOT);
        when(run.getEffectiveSeed()).thenReturn(42L);
        when(run.getIterations()).thenReturn(iterations);
        when(run.getGeneratedPaths()).thenReturn(generatedPaths);
        when(run.getElapsedMs()).thenReturn(1L);
        when(run.getMaxIterations()).thenReturn(maxIterations);
        when(run.getPathLength()).thenReturn(2);
        when(run.getPopulationSize()).thenReturn(populationSize);
        when(run.getFindingCount()).thenReturn(0);
        when(run.getTargetSpecIdsJson()).thenReturn("[]");
        when(run.getModelSnapshotJson()).thenReturn(JsonUtils.toJson(
                fuzzSnapshot(createdAt, 1, 0, 1, 0, 1)));
        when(run.getEligibilityJson()).thenReturn(JsonUtils.toJson(FuzzEligibilityDto.builder()
                .eligibleSpecIds(List.of("spec-1"))
                .eligibleSpecLabels(Map.of("spec-1", "Safety property"))
                .ineligibleSpecs(List.of())
                .requestedSpecCount(1)
                .eligibleSpecCount(1)
                .build()));
        when(run.getLimitationsJson()).thenReturn(
                JsonUtils.toJson(FuzzLimitationContract.baseCodes()));
        return run;
    }

    private FuzzInputEventDto inputEvent(int step, String source) {
        return FuzzInputEventDto.builder()
                .step(step)
                .kind("DEVICE_VARIABLE")
                .targetId("device-1")
                .property("temperature")
                .value("21")
                .source(source)
                .build();
    }

    private FuzzTaskPo completedRun(
            List<SpecificationDto> specifications,
            List<String> targetSpecIds,
            FuzzEligibilityDto eligibility,
            FuzzOutcome outcome,
            int findingCount) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 14, 15, 0);
        ModelInputSnapshot frozenInput = inputSnapshot(specifications);
        if (eligibility.getEligibleSpecLabels() == null
                || eligibility.getEligibleSpecLabels().isEmpty()) {
            Map<String, SpecificationDto> byId = specifications.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            SpecificationDto::getId, specification -> specification));
            Map<String, String> labels = new java.util.LinkedHashMap<>();
            for (String id : eligibility.getEligibleSpecIds()) {
                SpecificationDto specification = byId.get(id);
                labels.put(id, specification != null && specification.getTemplateLabel() != null
                        ? specification.getTemplateLabel() : id);
            }
            eligibility.setEligibleSpecLabels(labels);
        }
        return FuzzTaskPo.builder()
                .id(20L)
                .userId(3L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .createdAt(createdAt)
                .startedAt(createdAt)
                .completedAt(createdAt.plusSeconds(2))
                .processingTimeMs(2_000L)
                .progress(100)
                .targetSpecIdsJson(JsonUtils.toJson(targetSpecIds))
                .maxIterations(10)
                .pathLength(2)
                .populationSize(1)
                .explorationMode(FuzzExplorationMode.BOARD_SNAPSHOT)
                .modelInputSnapshotJson(FuzzModelInputSnapshotCodec.encode(frozenInput))
                .modelSnapshotJson(JsonUtils.toJson(fuzzSnapshot(createdAt, frozenInput)))
                .outcome(outcome)
                .effectiveSeed(42L)
                .iterations(1)
                .generatedPaths(1L)
                .elapsedMs(1L)
                .eligibilityJson(JsonUtils.toJson(eligibility))
                .limitationsJson(JsonUtils.toJson(FuzzLimitationContract.baseCodes()))
                .findingCount(findingCount)
                .build();
    }

    private FuzzFindingPo finding(FuzzTaskPo run, SpecificationDto specification) {
        TraceStateDto state = TraceStateDto.builder()
                .stateIndex(0)
                .devices(List.of())
                .triggeredRules(List.of())
                .compromisedAutomationLinks(List.of())
                .build();
        return FuzzFindingPo.builder()
                .id(30L)
                .userId(run.getUserId())
                .fuzzTaskId(run.getId())
                .violatedSpecId(specification.getId())
                .violatedSpecJson(JsonUtils.toJson(specification))
                .firstViolationStep(0)
                .statesJson(JsonUtils.toJson(List.of(state)))
                .inputEventsJson("[]")
                .seed(run.getEffectiveSeed())
                .stateCount(1)
                .createdAt(run.getCreatedAt().plusSeconds(1))
                .build();
    }

    private ModelInputSnapshot provenanceSnapshot(SpecificationDto specification) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("switch_1");
        device.setTemplateName("Switch");
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Switch")
                .internalVariables(List.of(
                        DeviceManifest.InternalVariable.builder()
                                .name("battery")
                                .isInside(true)
                                .build(),
                        DeviceManifest.InternalVariable.builder()
                                .name("temperature")
                                .isInside(false)
                                .build()))
                .impactedVariables(List.of())
                .build();
        return new ModelInputSnapshot(
                List.of(playbackNode("switch_1", "Switch")), List.of(device), List.of(), List.of(), List.of(specification),
                Map.of("Switch", manifest));
    }

    private DeviceNodeDto playbackNode(String id, String templateName) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setTemplateName(templateName);
        node.setLabel(id);
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(0.0);
        position.setY(0.0);
        node.setPosition(position);
        node.setState("Working");
        node.setWidth(160);
        node.setHeight(120);
        return node;
    }

    private TraceVariableDto traceVariable(String name, ModelTokenSource source) {
        TraceVariableDto variable = new TraceVariableDto();
        variable.setName(name);
        variable.setValue("0");
        variable.setModelTokenSource(source);
        return variable;
    }

    private TraceTrustPrivacyDto trustPrivacy(
            String name,
            String propertyScope,
            String mode,
            Boolean trust,
            String privacy) {
        TraceTrustPrivacyDto value = new TraceTrustPrivacyDto();
        value.setName(name);
        value.setPropertyScope(propertyScope);
        value.setMode(mode);
        value.setTrust(trust);
        value.setPrivacy(privacy);
        return value;
    }

    private ModelInputSnapshot inputSnapshot(List<SpecificationDto> specifications) {
        return new ModelInputSnapshot(
                List.of(), List.of(), List.of(), List.of(), specifications, Map.of());
    }

    private ModelRunSnapshotDto fuzzSnapshot(LocalDateTime capturedAt,
                                             int deviceCount,
                                             int ruleCount,
                                             int specificationCount,
                                             int environmentVariableCount,
                                             int deviceTemplateCount) {
        ModelRunSnapshotDto snapshot = ModelRunSnapshotDto.captured(
                capturedAt, deviceCount, ruleCount, specificationCount,
                environmentVariableCount, deviceTemplateCount);
        snapshot.setModelFingerprint("a".repeat(64));
        return snapshot;
    }

    private ModelRunSnapshotDto fuzzSnapshot(
            LocalDateTime capturedAt, ModelInputSnapshot frozenInput) {
        ModelRunSnapshotDto snapshot = ModelRunSnapshotDto.captured(
                capturedAt,
                frozenInput.devices().size(),
                frozenInput.rules().size(),
                frozenInput.specifications().size(),
                frozenInput.environmentVariables().size(),
                frozenInput.templateManifests().size());
        snapshot.setModelFingerprint(modelFingerprint.modelFingerprint(frozenInput));
        return snapshot;
    }

    private SpecificationDto supportedSpecification(String id) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId(id + "-condition");
        condition.setSide("a");
        condition.setDeviceId("device-1");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("on");
        SpecificationDto specification = new SpecificationDto();
        specification.setId(id);
        specification.setTemplateId("1");
        specification.setAConditions(List.of(condition));
        return specification;
    }
}
