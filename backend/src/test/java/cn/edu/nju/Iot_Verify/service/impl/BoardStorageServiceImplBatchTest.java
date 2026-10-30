package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableChangeDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentMutationResultDto;
import cn.edu.nju.Iot_Verify.dto.board.EnvironmentVariableUpdateRequestDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeConfigDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceUpdateResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceJournalSnapshot;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.BoardReplacementStaleException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceLabelConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceLayoutConflictException;
import cn.edu.nju.Iot_Verify.exception.DeviceRuntimeConflictException;
import cn.edu.nju.Iot_Verify.exception.EnvironmentVariableConflictException;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.po.BoardEnvironmentVariablePo;
import cn.edu.nju.Iot_Verify.po.BoardEditEntityType;
import cn.edu.nju.Iot_Verify.po.BoardEditOperation;
import cn.edu.nju.Iot_Verify.po.DeviceNodeId;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.po.RulePo;
import cn.edu.nju.Iot_Verify.po.SpecificationPo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.BoardEnvironmentVariableRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceNodeRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.repository.RuleRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.service.board.BoardEditJournal;
import cn.edu.nju.Iot_Verify.service.board.BoardEditHistoryState;
import cn.edu.nju.Iot_Verify.service.board.BoardUndoAvailability;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies saveBoardBatch composes a complete board semantic replacement inside one transaction
 * and rejects stale destructive confirmations before any write.
 */
@ExtendWith(MockitoExtension.class)
class BoardStorageServiceImplBatchTest {

    @Mock private DeviceNodeRepository nodeRepo;
    @Mock private BoardEnvironmentVariableRepository environmentRepo;
    @Mock private RuleRepository ruleRepo;
    @Mock private SpecificationRepository specRepo;
    @Mock private DeviceTemplateRepository deviceTemplateRepo;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private DeviceNodeMapper deviceNodeMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private SpecificationMapper specificationMapper;
    @Mock private UserRepository userRepository;
    /**
     * The journal commits with the edit it describes, so these tests only need it to exist; the
     * ordering and invalidation rules are asserted in BoardEditJournalTest.
     */
    @Mock private BoardEditJournal editJournal;

    private BoardStorageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        // Execute the transaction callback inline.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        lenient().when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(new UserPo()));
        // The real journal always answers; reversible-mutation paths stamp this onto their result
        // without a null check, so an unstubbed mock would not model the real collaborator.
        lenient().when(editJournal.availability(anyLong()))
                .thenReturn(new BoardUndoAvailability(false, false));
        lenient().when(editJournal.historyState(anyLong()))
                .thenReturn(historyState(0, "0"));
    }

    @Test
    void saveBoardBatch_savesAllThreeCollections() {
        UserPo owner = UserPo.builder().id(1L).build();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        DeviceNodeDto node = boardNode("node-1", null, "Device");
        RuleDto rule = RuleDto.builder().conditions(List.of()).build();
        SpecificationDto spec = new SpecificationDto();
        spec.setName("Door remains closed");

        // node save path
        when(deviceNodeMapper.toEntity(any(), anyLong())).thenReturn(new DeviceNodePo());
        when(nodeRepo.saveAll(any())).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(new DeviceNodeDto());
        // rule save path (identity-preserving full-list save reads existing first)
        when(ruleRepo.findByUserId(1L)).thenReturn(List.of());
        when(ruleMapper.toEntity(any(), anyLong())).thenReturn(new RulePo());
        // spec save path
        when(specificationMapper.toEntity(any(), anyLong())).thenReturn(new SpecificationPo());
        when(specRepo.saveAll(any())).thenReturn(List.of());

        BoardBatchDto result = service.saveBoardBatch(1L,
                confirmedBatch(service, new BoardBatchDto(List.of(node), List.of(rule), List.of(spec))));

        assertTrue(result != null);
        // All three collections reconcile identities within the single transaction.
        verify(nodeRepo).saveAll(any());
        verify(specRepo).saveAll(any());
        verify(ruleMapper).toEntity(rule, 1L);
        verify(transactionTemplate, times(2)).execute(any());
        // Scene replacement rewrites every collection, so no recorded per-record inverse still
        // describes a reachable state. Leaving the journal would let undo "restore" a rule into a
        // scene the user replaced — an invariant that was previously untested at this layer.
        verify(editJournal).clear(1L);
        assertEquals(1L, owner.getSceneRevision());
    }

    @Test
    void saveBoardBatch_missingCollectionsRejectInsteadOfImplicitlyPreservingThem() {
        ValidationException error = assertThrows(ValidationException.class,
                () -> service.saveBoardBatch(1L, new BoardBatchDto(null, List.of(), null)));

        assertTrue(error.getErrors().containsKey("nodes"));
        assertTrue(error.getErrors().containsKey("specs"));
        verify(nodeRepo, never()).deleteAll(any());
        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void saveBoardBatch_rechecksCollectionLimitsInsideTheServiceBoundary() {
        BoardBatchDto oversized = new BoardBatchDto(
                java.util.Collections.nCopies(101, new DeviceNodeDto()), List.of(), List.of(), List.of());

        ValidationException error = assertThrows(
                ValidationException.class, () -> service.saveBoardBatch(1L, oversized));

        assertTrue(error.getErrors().containsKey("nodes"));
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void addNodes_rejectsWhenThePersistedBoardIsAlreadyAtCapacity() {
        when(nodeRepo.findByUserId(1L))
                .thenReturn(java.util.Collections.nCopies(100, new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(new DeviceNodeDto());

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.addNodes(1L, List.of(new DeviceNodeDto()), List.of()));

        assertTrue(error.getMessage().contains("at most 100 devices"));
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void renameNode_rejectsAStaleDialogBeforeWriting() {
        DeviceNodePo stored = DeviceNodePo.builder()
                .id("device-1")
                .userId(1L)
                .label("Renamed elsewhere")
                .build();
        DeviceNodeDto current = boardNode("device-1", "Sensor", "Renamed elsewhere");
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(stored));
        when(deviceNodeMapper.toDto(stored)).thenReturn(current);

        ConflictException error = assertThrows(ConflictException.class,
                () -> service.renameNode(1L, "device-1", "My new name", "Original name"));

        assertTrue(error.getMessage().contains("changed after the rename dialog was opened"));
        verify(nodeRepo, never()).deleteAll(any());
        verify(nodeRepo, never()).save(any());
    }

    @Test
    void renameNode_decodesSpecificationsOnceAndKeepsDistinctJournalDisplayCaches() {
        SpecificationMapper realSpecificationMapper = spy(new SpecificationMapper());
        var realService = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, realSpecificationMapper, ruleMapper, new DeviceNodeMapper(),
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceNodePo node = DeviceNodePo.builder().id("switch_1").userId(1L).templateName("Switch")
                .label("Original name").posX(0.0).posY(0.0).width(176).height(128).state("off").build();
        var manifest = DeviceTemplateDto.DeviceManifest.builder().name("Switch").modes(List.of("Power"))
                .initState("off").workingStates(List.of(workingState("off"), workingState("on")))
                .apis(List.of()).build();
        var template = DeviceTemplatePo.builder().userId(1L).name("Switch")
                .manifestJson(JsonUtils.toJson(manifest)).build();
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("switch_1");
        condition.setDeviceLabel("Original name");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("=");
        condition.setValue("on");
        SpecificationDto original = new SpecificationDto();
        original.setId("spec-1");
        original.setName("Safety requirement");
        original.setTemplateId("1");
        original.setAConditions(List.of(condition));
        original.setDevices(List.of(new SpecificationDto.DeviceRefDto("switch_1", "Original name", List.of())));
        SpecificationPo originalPo = new SpecificationMapper().toEntity(original, 1L);
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(node));
        when(nodeRepo.findById(new DeviceNodeId("switch_1", 1L))).thenReturn(Optional.of(node));
        when(nodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(specRepo.findByUserId(1L)).thenReturn(List.of(originalPo));

        var result = realService.renameNode(1L, "switch_1", "New name", "Original name");

        assertEquals(1, result.getUpdatedSpecificationCount());
        assertEquals("New name", result.getCurrentSpecifications().get(0).getAConditions().get(0).getDeviceLabel());
        assertTrue(result.getCurrentSpecifications().get(0).getFormula().contains("New name"));
        verify(realSpecificationMapper).toDto(same(originalPo));
        verify(specRepo, times(2)).findByUserId(1L);
        var before = ArgumentCaptor.forClass(DeviceJournalSnapshot.class);
        var after = ArgumentCaptor.forClass(DeviceJournalSnapshot.class);
        verify(editJournal).record(eq(1L), eq(BoardEditEntityType.DEVICE), eq(BoardEditOperation.UPDATE),
                eq("switch_1"), before.capture(), after.capture());
        assertEquals("Original name", before.getValue().getSpecs().get(0).getSpec()
                .getAConditions().get(0).getDeviceLabel());
        assertEquals("New name", after.getValue().getSpecs().get(0).getSpec()
                .getAConditions().get(0).getDeviceLabel());
    }

    @Test
    void removeRuleIfUnchanged_acceptsTheAuthoredSnapshotWithoutServerManagedFields() {
        RulePo stored = RulePo.builder().id(9L).userId(1L).build();
        RuleDto.Condition condition = RuleDto.Condition.builder()
                .deviceName("sensor-1")
                .attribute("motion")
                .targetType("api")
                .build();
        RuleDto.Command command = new RuleDto.Command("alarm-1", "on", null, null);
        RuleDto current = RuleDto.builder()
                .id(9L)
                .conditions(List.of(condition))
                .command(command)
                .ruleString("Motion starts alarm")
                .createdAt(LocalDateTime.now())
                .build();
        RuleDto expected = RuleDto.builder()
                .id(9L)
                .conditions(List.of(condition))
                .command(command)
                .ruleString("Motion starts alarm")
                .build();
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(stored), List.of());
        when(ruleMapper.toDto(stored)).thenReturn(current);

        service.removeRuleIfUnchanged(1L, 9L, expected);

        verify(ruleRepo).deleteById(9L);
    }

    @Test
    void removeRuleIfUnchanged_rejectsAWriteWithoutAConfirmedSnapshot() {
        assertThrows(BadRequestException.class,
                () -> service.removeRuleIfUnchanged(1L, 9L, null));

        verifyNoInteractions(ruleRepo);
    }

    @Test
    void removeSpecIfUnchanged_rejectsChangedAuthoredSemanticsBeforeWriting() {
        SpecificationPo stored = new SpecificationPo();
        stored.setId("spec-9");
        SpecificationDto current = new SpecificationDto();
        current.setId("spec-9");
        current.setName("Safety requirement");
        current.setTemplateId("1");
        SpecificationDto expected = new SpecificationDto();
        expected.setId("spec-9");
        expected.setName("Safety requirement");
        expected.setTemplateId("2");
        when(specRepo.findByUserId(1L)).thenReturn(List.of(stored));
        when(specificationMapper.toDto(stored)).thenReturn(current);

        assertThrows(ConflictException.class,
                () -> service.removeSpecIfUnchanged(1L, "spec-9", expected));

        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void removeSpecIfUnchanged_rejectsAWriteWithoutAConfirmedSnapshot() {
        assertThrows(BadRequestException.class,
                () -> service.removeSpecIfUnchanged(1L, "spec-9", null));

        verifyNoInteractions(specRepo);
    }

    @Test
    void renameNode_returnsConflictWhenAnotherTabClaimedTheRequestedLabel() {
        DeviceNodePo targetStored = DeviceNodePo.builder()
                .id("device-1")
                .userId(1L)
                .label("Original name")
                .build();
        DeviceNodePo competingStored = DeviceNodePo.builder()
                .id("device-2")
                .userId(1L)
                .label("Claimed name")
                .build();
        DeviceNodeDto target = boardNode("device-1", "Sensor", "Original name");
        DeviceNodeDto competing = boardNode("device-2", "Sensor", "Claimed name");
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(targetStored, competingStored));
        when(deviceNodeMapper.toDto(targetStored)).thenReturn(target);
        when(deviceNodeMapper.toDto(competingStored)).thenReturn(competing);

        DeviceLabelConflictException error = assertThrows(DeviceLabelConflictException.class,
                () -> service.renameNode(1L, "device-1", "claimed NAME", "Original name"));

        assertEquals("claimed NAME", error.getRequestedLabel());
        assertEquals("claimed NAME_1", error.getSuggestedLabel());
        verify(nodeRepo, never()).deleteAll(any());
        verify(nodeRepo, never()).save(any());
        verify(nodeRepo, never()).saveAll(any());
        verify(specRepo, never()).deleteAll(any());
        verify(specRepo, never()).saveAll(any());
    }

    @Test
    void addRule_rejectsWhenThePersistedBoardIsAlreadyAtCapacity() {
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L))
                .thenReturn(java.util.Collections.nCopies(100, new RulePo()));
        when(ruleMapper.toDto(any())).thenReturn(new RuleDto());

        BadRequestException error = assertThrows(
                BadRequestException.class, () -> service.addRule(1L, new RuleDto()));

        assertTrue(error.getMessage().contains("at most 100 rules"));
        verify(ruleRepo, never()).save(any());
    }

    @Test
    void addSpec_rejectsWhenThePersistedBoardIsAlreadyAtCapacity() {
        when(specRepo.findByUserId(1L))
                .thenReturn(java.util.Collections.nCopies(100, new SpecificationPo()));
        when(specificationMapper.toDto(any())).thenReturn(new SpecificationDto());

        BadRequestException error = assertThrows(
                BadRequestException.class, () -> service.addSpec(1L, new SpecificationDto()));

        assertTrue(error.getMessage().contains("at most 100 specifications"));
        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void saveBoardBatch_staleConfirmationReturnsFreshImpactAndWritesNothing() {
        DeviceNodePo stored = new DeviceNodePo();
        DeviceNodeDto first = boardNode("device-1", "Sensor", "Hall sensor");
        DeviceNodeDto second = boardNode("device-2", "Sensor", "Kitchen sensor");
        when(nodeRepo.findByUserId(1L))
                .thenReturn(List.of(stored), List.of(stored, new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(first, first, second);

        var preview = service.previewBoardReplacement(1L);
        assertEquals(1, preview.getDeviceCount());
        assertTrue(preview.getImpactToken() != null && !preview.getImpactToken().isBlank());

        BoardBatchDto replacement = new BoardBatchDto(List.of(), List.of(), List.of(), List.of());
        replacement.setImpactToken(preview.getImpactToken());
        BoardReplacementStaleException error = assertThrows(
                BoardReplacementStaleException.class,
                () -> service.saveBoardBatch(1L, replacement));

        assertEquals(2, error.getCurrentPreview().getDeviceCount());
        assertTrue(!preview.getImpactToken().equals(error.getCurrentPreview().getImpactToken()));
        verify(nodeRepo, never()).deleteAll(any());
        verify(environmentRepo, never()).deleteAll(any());
        verify(ruleRepo, never()).saveAll(any());
        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void replacementPreview_distinguishesStoredTemplatesFromSceneUsage() {
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(
                DeviceTemplatePo.builder().name("Unused").manifestJson("{\"Name\":\"Unused\"}").build(),
                DeviceTemplatePo.builder().name("Light").defaultTemplate(true)
                        .manifestJson("{\"Name\":\"Light\"}").build(),
                DeviceTemplatePo.builder().name("Sensor").manifestJson("{\"Name\":\"Sensor\"}").build()));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo(), new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(
                boardNode("one", "Sensor", "One"), boardNode("two", "sensor", "Two"));

        var preview = JsonUtils.fromJson(JsonUtils.toJson(service.previewBoardReplacement(1L)), java.util.Map.class);

        assertEquals(List.of(
                java.util.Map.of("name", "Light", "defaultTemplate", true, "deviceCount", 0),
                java.util.Map.of("name", "Sensor", "defaultTemplate", false, "deviceCount", 2),
                java.util.Map.of("name", "Unused", "defaultTemplate", false, "deviceCount", 0)), preview.get("templates"));
    }

    @Test
    void saveBoardBatch_rejectsPreviewWhenTheRetainedTemplateLibraryChanges() {
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of());
        var preview = service.previewBoardReplacement(1L);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(
                DeviceTemplatePo.builder().name("New Sensor").manifestJson("{\"Name\":\"New Sensor\"}").build()));
        BoardBatchDto replacement = new BoardBatchDto(List.of(), List.of(), List.of(), List.of());
        replacement.setImpactToken(preview.getImpactToken());

        assertThrows(BoardReplacementStaleException.class, () -> service.saveBoardBatch(1L, replacement));
        verify(nodeRepo, never()).deleteAll(any());
        verify(editJournal, never()).clear(anyLong());
    }

    @Test
    void saveBoardBatch_rejectsPreviewWhenOnlyUndoHistoryChanged() {
        when(editJournal.historyState(1L))
                .thenReturn(historyState(1, "a"), historyState(2, "b"));

        var preview = service.previewBoardReplacement(1L);
        BoardBatchDto replacement = new BoardBatchDto(List.of(), List.of(), List.of(), List.of());
        replacement.setImpactToken(preview.getImpactToken());

        BoardReplacementStaleException error = assertThrows(
                BoardReplacementStaleException.class,
                () -> service.saveBoardBatch(1L, replacement));

        assertEquals(1, preview.getEditHistoryEntryCount());
        assertEquals(2, error.getCurrentPreview().getEditHistoryEntryCount());
        assertTrue(!preview.getImpactToken().equals(error.getCurrentPreview().getImpactToken()));
        verify(editJournal, never()).clear(anyLong());
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveSpecsInternal_persistsAndReturnsTheSubmittedSpecificationOrder() {
        SpecificationDto first = new SpecificationDto();
        first.setId("spec-b");
        first.setName("Safety requirement");
        first.setTemplateId("1");
        first.setAConditions(List.of());
        first.setIfConditions(List.of());
        first.setThenConditions(List.of());
        SpecificationDto second = new SpecificationDto();
        second.setId("spec-a");
        second.setName("Safety requirement");
        second.setTemplateId("2");
        second.setAConditions(List.of());
        second.setIfConditions(List.of());
        second.setThenConditions(List.of());

        when(specificationMapper.toEntity(any(), anyLong())).thenAnswer(invocation -> {
            SpecificationDto dto = invocation.getArgument(0);
            return SpecificationPo.builder().name("Safety requirement").id(dto.getId()).userId(1L).build();
        });
        when(specRepo.saveAll(any())).thenReturn(List.of());
        when(specificationMapper.toDto(any())).thenAnswer(invocation -> {
            SpecificationPo po = invocation.getArgument(0);
            SpecificationDto dto = new SpecificationDto();
            dto.setId(po.getId());
            return dto;
        });

        @SuppressWarnings("unchecked")
        List<SpecificationDto> saved = ReflectionTestUtils.invokeMethod(
                service, "saveSpecsInternal", 1L, List.of(first, second), List.of());

        ArgumentCaptor<List<SpecificationPo>> captor = ArgumentCaptor.forClass(List.class);
        verify(specRepo).saveAll(captor.capture());
        assertEquals(List.of(0, 1), captor.getValue().stream()
                .map(SpecificationPo::getListOrder).toList());
        assertEquals(List.of("spec-b", "spec-a"), saved.stream().map(SpecificationDto::getId).toList());
    }

    @Test
    void saveBoardBatch_canonicalizesRuleAndSpecRelationsAndTargetTypesBeforePersistence() {
        DeviceNodeDto node = boardNode("sensor1", "Temperature Sensor", "Living Sensor");

        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Temperature Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Temperature Sensor")
                        .internalVariables(List.of(
                                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                        .name("temperature")
                                        .isInside(false)
                                        .lowerBound(0)
                                        .upperBound(100)
                                        .trust("untrusted")
                                        .privacy("public")
                                        .build()))
                        .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                                .name("cool")
                                .signal(false)
                                .build()))
                        .build()))
                .build();

        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor1")
                        .attribute("temperature")
                        .targetType("Variable")
                        .relation(" GTE ")
                        .value("28")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("sensor1")
                        .action("cool")
                        .build())
                .build();

        SpecConditionDto condition = new SpecConditionDto();
        condition.setId("c1");
        condition.setSide("a");
        condition.setDeviceId("sensor1");
        condition.setDeviceLabel("Stale internal label");
        condition.setTargetType("PRIVACY");
        condition.setKey("temperature");
        condition.setPropertyScope("VARIABLE");
        condition.setRelation(" NOT_IN ");
        condition.setValue("PUBLIC,PRIVATE");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec1");
        spec.setName("Safety requirement");
        spec.setTemplateId("3");
        spec.setTemplateLabel("Misleading caller label");
        spec.setAConditions(List.of(condition));
        spec.setFormula("LTLSPEC FALSE -- caller supplied cache");
        spec.setDevices(List.of(new SpecificationDto.DeviceRefDto(
                "other-device", "Wrong device", List.of("wrong API"))));

        when(deviceNodeMapper.toEntity(any(), anyLong())).thenReturn(new DeviceNodePo());
        when(nodeRepo.saveAll(any())).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserId(1L)).thenReturn(List.of());
        when(ruleMapper.toEntity(any(), anyLong())).thenReturn(new RulePo());
        when(specificationMapper.toEntity(any(), anyLong())).thenReturn(new SpecificationPo());
        when(specRepo.saveAll(any())).thenReturn(List.of());

        service.saveBoardBatch(1L,
                confirmedBatch(service, new BoardBatchDto(List.of(node), List.of(rule), List.of(spec))));

        verify(ruleMapper).toEntity(argThat(savedRule ->
                ">=".equals(savedRule.getConditions().get(0).getRelation())
                        && "variable".equals(savedRule.getConditions().get(0).getTargetType())), anyLong());
        verify(specificationMapper).toEntity(argThat(savedSpec ->
                "not in".equals(savedSpec.getAConditions().get(0).getRelation())
                        && "privacy".equals(savedSpec.getAConditions().get(0).getTargetType())
                        && "variable".equals(savedSpec.getAConditions().get(0).getPropertyScope())
                        && "public, private".equals(savedSpec.getAConditions().get(0).getValue())
                        && "Living Sensor".equals(savedSpec.getAConditions().get(0).getDeviceLabel())
                        && "Never".equals(savedSpec.getTemplateLabel())
                        && savedSpec.getFormula().contains("not in {public, private}")
                        && savedSpec.getDevices().size() == 1
                        && "sensor1".equals(savedSpec.getDevices().get(0).getDeviceId())
                        && savedSpec.getDevices().get(0).getSelectedApis().isEmpty()), anyLong());
    }

    @Test
    void addNodes_isNotBlockedByAStoredSpecificationThatNeverChoseItsReading() {
        /*
         * Device, rule and layout writes revalidate the WHOLE stored specification collection. When a
         * missing reading was rejected there, one specification written before the field existed made every
         * unrelated mutation fail — adding a device returned `specs[0].aConditions[0].variableSource is
         * required`, naming a specification the request did not contain, and the user could no longer add or
         * rename a device at all. The only operation left was deleting the specification.
         *
         * The absent case stays fail-closed where it belongs: the generator refuses to compile it, the
         * verification request validator rejects the run, and the board blocks the run with a reason. What
         * it must not do is block writes that have nothing to do with it.
         */
        DeviceNodeDto node = boardNode("sensor1", "Temperature Sensor", "Living Sensor");
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Temperature Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Temperature Sensor")
                        .internalVariables(List.of(
                                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                        .name("temperature")
                                        .isInside(false)
                                        .lowerBound(0)
                                        .upperBound(100)
                                        .trust("untrusted")
                                        .privacy("public")
                                        .build()))
                        .build()))
                .build();

        SpecConditionDto legacyCondition = new SpecConditionDto();
        legacyCondition.setId("legacy-c1");
        legacyCondition.setSide("a");
        legacyCondition.setDeviceId("sensor1");
        legacyCondition.setTargetType("variable");
        legacyCondition.setKey("temperature");
        legacyCondition.setRelation(">");
        legacyCondition.setValue("28");
        // No variableSource: exactly what a row written before this field deserializes to.

        SpecificationDto legacySpec = new SpecificationDto();
        legacySpec.setId("legacy-spec");
        legacySpec.setName("Safety requirement");
        legacySpec.setTemplateId("1");
        legacySpec.setAConditions(List.of(legacyCondition));

        SpecificationPo legacyPo = new SpecificationPo();
        // Lenient: the point of the test is that the write SUCCEEDS, so which of the persistence collabo-
        // rators it happens to touch is not the claim. Strict stubbing would make this test fail whenever
        // saveNodes' internals change, for a reason unrelated to what it asserts.
        lenient().when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        lenient().when(specRepo.findByUserId(1L)).thenReturn(List.of(legacyPo));
        lenient().when(specificationMapper.toDto(legacyPo)).thenReturn(legacySpec);
        lenient().when(ruleRepo.findByUserId(1L)).thenReturn(List.of());
        lenient().when(deviceNodeMapper.toEntity(any(), anyLong())).thenReturn(new DeviceNodePo());
        lenient().when(nodeRepo.saveAll(any())).thenReturn(List.of(new DeviceNodePo()));
        lenient().when(deviceNodeMapper.toDto(any())).thenReturn(node);

        assertDoesNotThrow(() -> service.saveNodes(1L, List.of(node)),
                "a stored specification with no recorded reading must not block an unrelated device write");
    }

    @Test
    void saveBoardBatch_normalizesVariableSourceForStorage() {
        /*
         * Asserted at the mapper boundary rather than at validation, because that is where the defect was:
         * `canonicalizeSpecConditionsForStorage` rebuilds each condition field by field, and the field was
         * missing from it. Validation accepted the request, the row persisted blank, and the specification
         * reloaded unresolved — for every variable condition a user authored. The accept-side validation
         * tests could not see it; they stop one layer above.
         */
        DeviceNodeDto node = boardNode("sensor1", "Temperature Sensor", "Living Sensor");
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Temperature Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Temperature Sensor")
                        .internalVariables(List.of(
                                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                        .name("temperature")
                                        .isInside(false)
                                        .lowerBound(0)
                                        .upperBound(100)
                                        .trust("untrusted")
                                        .privacy("public")
                                        .build()))
                        .build()))
                .build();

        SpecConditionDto condition = new SpecConditionDto();
        condition.setId("c1");
        condition.setSide("a");
        condition.setDeviceId("sensor1");
        condition.setTargetType("variable");
        condition.setKey("temperature");
        // Mixed case on purpose: canonicalization must normalize it, not reject or pass it through, or the
        // generator's equals() lookup and this stored value would disagree.
        condition.setVariableSource("Environment");
        condition.setRelation(">");
        condition.setValue("28");

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec1");
        spec.setName("Safety requirement");
        spec.setTemplateId("3");
        spec.setAConditions(List.of(condition));

        when(deviceNodeMapper.toEntity(any(), anyLong())).thenReturn(new DeviceNodePo());
        when(nodeRepo.saveAll(any())).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserId(1L)).thenReturn(List.of());
        when(specificationMapper.toEntity(any(), anyLong())).thenReturn(new SpecificationPo());
        when(specRepo.saveAll(any())).thenReturn(List.of());

        service.saveBoardBatch(1L,
                confirmedBatch(service, new BoardBatchDto(List.of(node), List.of(), List.of(spec))));

        verify(specificationMapper).toEntity(argThat(savedSpec ->
                "environment".equals(savedSpec.getAConditions().get(0).getVariableSource())), anyLong());
    }

    @Test
    void apiEventConditionWithoutRelationIsNotTreatedAsAnAdjustableParameter() {
        RuleDto.Condition apiEvent = RuleDto.Condition.builder()
                .deviceName("camera_1")
                .attribute("take photo")
                .targetType("api")
                .build();

        Boolean parameterizable = ReflectionTestUtils.invokeMethod(
                service,
                "isParameterizableBoardCondition",
                apiEvent,
                java.util.Map.of(),
                java.util.Map.of());

        assertEquals(Boolean.FALSE, parameterizable);
    }

    @Test
    void saveBoardBatch_savesEnvironmentVariablesWithImportedNodes() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Thermostat")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(50)
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Thermostat")
                .manifestJson(JsonUtils.toJson(manifest))
                .defaultTemplate(true)
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("thermostat_1");
        node.setTemplateName("Thermostat");
        node.setLabel("Living Thermostat");
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(10.0);
        position.setY(20.0);
        node.setPosition(position);
        node.setState("Working");
        node.setWidth(176);
        node.setHeight(128);

        BoardEnvironmentVariableDto importedEnvironment =
                new BoardEnvironmentVariableDto("temperature", "26", "untrusted", "private");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(deviceNodeMapper.toEntity(any(), anyLong())).thenReturn(new DeviceNodePo());
        when(nodeRepo.saveAll(any())).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of());
        when(environmentRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepo.findByUserId(1L)).thenReturn(List.of());
        when(specRepo.saveAll(any())).thenReturn(List.of());

        BoardBatchDto result = serviceWithEnvironment.saveBoardBatch(1L,
                confirmedBatch(serviceWithEnvironment,
                        new BoardBatchDto(List.of(node), List.of(importedEnvironment), List.of(), List.of())));

        assertEquals(1, result.getEnvironmentVariables().size());
        BoardEnvironmentVariableDto savedEnvironment = result.getEnvironmentVariables().get(0);
        assertEquals("temperature", savedEnvironment.getName());
        assertEquals("26", savedEnvironment.getValue());
        assertEquals("untrusted", savedEnvironment.getTrust());
        assertEquals("private", savedEnvironment.getPrivacy());
        verify(environmentRepo, never()).deleteAll(any());
        verify(environmentRepo, atLeastOnce()).saveAll(argThat(saved -> {
            for (BoardEnvironmentVariablePo po : saved) {
                if ("temperature".equals(po.getName())
                        && "26".equals(po.getValue())
                        && "untrusted".equals(po.getTrust())
                        && "private".equals(po.getPrivacy())) {
                    return true;
                }
            }
            return false;
        }));
    }

    @Test
    void saveBoardBatch_rejectsActiveDevicesWithConflictingEnvironmentSemantics() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest.InternalVariable slowTemperature =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false)
                        .lowerBound(0).upperBound(100).naturalChangeRate("[-1,1]")
                        .trust("untrusted").privacy("public").build();
        DeviceTemplateDto.DeviceManifest.InternalVariable fastTemperature =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false)
                        .lowerBound(0).upperBound(100).naturalChangeRate("[-5,5]")
                        .trust("untrusted").privacy("public").build();
        DeviceTemplatePo firstTemplate = DeviceTemplatePo.builder()
                .userId(1L).name("Indoor Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Indoor Sensor").internalVariables(List.of(slowTemperature)).build()))
                .build();
        DeviceTemplatePo secondTemplate = DeviceTemplatePo.builder()
                .userId(1L).name("Outdoor Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Outdoor Sensor").internalVariables(List.of(fastTemperature)).build()))
                .build();
        DeviceNodeDto indoor = boardNode("indoor_1", "Indoor Sensor", "Hallway sensor");
        DeviceNodeDto outdoor = boardNode("outdoor_1", "Outdoor Sensor", "Patio sensor");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(firstTemplate, secondTemplate));

        ValidationException exception = assertThrows(ValidationException.class, () ->
                serviceWithTemplates.saveBoardBatch(1L, confirmedBatch(serviceWithTemplates, new BoardBatchDto(
                        List.of(indoor, outdoor),
                        List.of(new BoardEnvironmentVariableDto("temperature", "20", "untrusted", "public")),
                        List.of(), List.of()))));

        String message = String.join(" ", exception.getErrors().values());
        assertTrue(message.contains("natural-change-rate mismatch"), message);
        assertTrue(message.contains("Hallway sensor"), message);
        assertTrue(message.contains("Patio sensor"), message);
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void getEnvironmentVariables_whenPersistedDevicesConflictOnDiscreteEffects_staysReadable() {
        // A pair-wise invariant must not run on a read path.
        //
        // The discrete-writer conflict check belongs to `validateBoardReferences`, which every write
        // route reaches — including scene replacement, via `saveBoardBatch`. It was briefly *also*
        // wired into `requireActiveEnvironmentDomainConsistency`, justified by a claim that scene
        // replacement reached only the latter. That claim was false, and it named
        // `applySceneReplacement`, a method that does not exist in this repo.
        //
        // The redundancy was not harmless: one of that method's five call sites sits inside
        // `projectEnvironmentVariablesForNodes`, which `getEnvironmentVariables` reaches through
        // `refreshEnvironmentVariablesInternal`. So `GET /api/board/environment` began rejecting any
        // board that already held a conflicting pair — locking the user out of reading their own
        // environment pool, for data saved before the check existed.
        //
        // A per-declaration consistency check is idempotent and safe to repeat on a read; a pair-wise
        // conflict check is not. This pins the read side; the write side is pinned by
        // `saveNodes_whenTwoDevicesDeclareConflictingDiscreteEffects_shouldRejectBeforePersisting`.
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest.InternalVariable airQuality =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("airQuality").isInside(false).reads(false)
                        .falsifiableWhenCompromised(false)
                        .values(List.of("good", "bad"))
                        .trust("trusted").privacy("public").build();
        DeviceTemplatePo goodWriter = DeviceTemplatePo.builder()
                .userId(1L).name("Writer Good")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Writer Good")
                        .modes(List.of("MachineState")).initState("on")
                        .workingStates(List.of(DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                                .name("on").trust("trusted").privacy("public")
                                .dynamics(List.of(DeviceTemplateDto.DeviceManifest.Dynamic.builder()
                                        .variableName("airQuality").value("good").build()))
                                .build()))
                        .internalVariables(List.of(airQuality))
                        .impactedVariables(List.of("airQuality"))
                        .build()))
                .build();
        DeviceTemplatePo badWriter = DeviceTemplatePo.builder()
                .userId(1L).name("Writer Bad")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Writer Bad")
                        .modes(List.of("MachineState")).initState("on")
                        .workingStates(List.of(DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                                .name("on").trust("trusted").privacy("public")
                                .dynamics(List.of(DeviceTemplateDto.DeviceManifest.Dynamic.builder()
                                        .variableName("airQuality").value("bad").build()))
                                .build()))
                        .internalVariables(List.of(airQuality))
                        .impactedVariables(List.of("airQuality"))
                        .build()))
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo(), new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(
                boardNode("writerGood1", "Writer Good", "Purifier"),
                boardNode("writerBad1", "Writer Bad", "Stove"));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(goodWriter, badWriter));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(new java.util.ArrayList<>());
        // `transactionTemplate` is already stubbed in setUp; re-stubbing it here overrode that with a
        // version that mishandled the callback argument.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> serviceWithTemplates.getEnvironmentVariables(1L),
                "reading the environment pool must not enforce a pair-wise write invariant");
    }

    @Test
    void saveEnvironmentVariables_rejectsActiveDevicesWithConflictingEnvironmentSemantics() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest.InternalVariable slowTemperature =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false)
                        .lowerBound(0).upperBound(100).naturalChangeRate("[-1,1]")
                        .trust("untrusted").privacy("public").build();
        DeviceTemplateDto.DeviceManifest.InternalVariable fastTemperature =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false)
                        .lowerBound(0).upperBound(100).naturalChangeRate("[-5,5]")
                        .trust("untrusted").privacy("public").build();
        DeviceTemplatePo firstTemplate = DeviceTemplatePo.builder()
                .userId(1L).name("Indoor Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Indoor Sensor").internalVariables(List.of(slowTemperature)).build()))
                .build();
        DeviceTemplatePo secondTemplate = DeviceTemplatePo.builder()
                .userId(1L).name("Outdoor Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Outdoor Sensor").internalVariables(List.of(fastTemperature)).build()))
                .build();
        DeviceNodeDto indoor = boardNode("indoor_1", "Indoor Sensor", "Hallway sensor");
        DeviceNodeDto outdoor = boardNode("outdoor_1", "Outdoor Sensor", "Patio sensor");
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo(), new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(indoor, outdoor);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(firstTemplate, secondTemplate));

        ValidationException exception = assertThrows(ValidationException.class, () ->
                serviceWithTemplates.saveEnvironmentVariables(1L, List.of(
                        new EnvironmentVariableUpdateRequestDto(
                                "temperature",
                                new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                                        "20", "untrusted", "public"),
                                new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                                        "21", null, null)))));

        String message = String.join(" ", exception.getErrors().values());
        assertTrue(message.contains("natural-change-rate mismatch"), message);
        assertTrue(message.contains("Hallway sensor"), message);
        assertTrue(message.contains("Patio sensor"), message);
        verify(environmentRepo, never()).saveAll(any());
    }

    @Test
    void saveBoardBatch_rejectsDeviceReferenceThatCollidesWithGeneratedEnvironmentName() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Sensor")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false)
                        .lowerBound(0).upperBound(50)
                        .trust("trusted").privacy("public")
                        .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L).name("Sensor").manifestJson(JsonUtils.toJson(manifest)).build();
        DeviceNodeDto node = boardNode("a_temperature", "Sensor", "Living-room sensor");
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException error = assertThrows(ValidationException.class, () ->
                serviceWithTemplates.saveBoardBatch(1L, confirmedBatch(serviceWithTemplates, new BoardBatchDto(
                        List.of(node),
                        List.of(new BoardEnvironmentVariableDto("temperature", "20", "trusted", "public")),
                        List.of(), List.of()))));

        String reason = error.getErrors().get("nodes[0].id");
        assertTrue(reason.contains("Living-room sensor"), reason);
        assertTrue(reason.contains("shared environment value 'temperature'"), reason);
        assertTrue(reason.contains("display name may stay unchanged"), reason);
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveBoardBatch_rejectsDeviceReferenceThatWouldDisableRulePlaybackAndAttackAnalysis() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Switch")
                .modes(List.of("Power"))
                .initState("off")
                .workingStates(List.of(workingState("off"), workingState("on")))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("turnOn").signal(false).build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L).name("Switch").manifestJson(JsonUtils.toJson(manifest)).build();
        String nodeId = SmvConstants.RULE_EXECUTION_PROBE_PREFIX + "0";
        DeviceNodeDto node = boardNode(nodeId, "Switch", "Hall switch");
        node.setState("off");
        RuleDto rule = RuleDto.builder()
                .ruleString("Turn on the hall switch when it is off")
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName(nodeId).attribute("state").targetType("state")
                        .relation("=").value("off").build()))
                .command(RuleDto.Command.builder().deviceName(nodeId).action("turnOn").build())
                .build();
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException error = assertThrows(ValidationException.class, () ->
                serviceWithTemplates.saveBoardBatch(1L, confirmedBatch(serviceWithTemplates, new BoardBatchDto(
                        List.of(node), List.of(), List.of(rule), List.of()))));

        String reason = error.getErrors().get("nodes[0].id");
        assertTrue(reason.contains("rule playback tracking"), reason);
        verify(nodeRepo, never()).deleteAll(any());
    }

    private static DeviceNodeDto boardNode(String id, String templateName, String label) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setTemplateName(templateName);
        node.setLabel(label);
        node.setWidth(176);
        node.setHeight(128);
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(0.0);
        position.setY(0.0);
        node.setPosition(position);
        return node;
    }

    private static BoardBatchDto confirmedBatch(BoardStorageServiceImpl target, BoardBatchDto batch) {
        batch.setImpactToken(target.previewBoardReplacement(1L).getImpactToken());
        return batch;
    }

    private static BoardEditHistoryState historyState(int entryCount, String tokenCharacter) {
        return new BoardEditHistoryState(
                entryCount,
                new BoardUndoAvailability(entryCount > 0, false),
                tokenCharacter.repeat(64));
    }

    @Test
    void saveBoardBatch_sceneImportRejectsMissingEnvironmentValuesBeforeBoardMutation() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Thermostat")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(50)
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Thermostat")
                .manifestJson(JsonUtils.toJson(manifest))
                .defaultTemplate(true)
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("thermostat_1");
        node.setTemplateName("Thermostat");
        node.setLabel("Living Thermostat");
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(10.0);
        position.setY(20.0);
        node.setPosition(position);
        node.setState("Working");
        node.setWidth(176);
        node.setHeight(128);

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        BoardBatchDto batch = new BoardBatchDto(List.of(node), List.of(), List.of(), List.of());
        DeviceTemplateDto snapshot = new DeviceTemplateDto();
        snapshot.setName("Thermostat");
        snapshot.setManifest(manifest);
        snapshot.setDefaultTemplate(true);
        batch.setTemplateSnapshots(List.of(snapshot));
        ValidationException error = assertThrows(
                ValidationException.class,
                () -> serviceWithEnvironment.saveBoardBatch(
                        1L, confirmedBatch(serviceWithEnvironment, batch)));

        assertTrue(error.getErrors().values().stream()
                .anyMatch(message -> message.contains("missing required environment variables")));
        verify(nodeRepo, never()).deleteAll(any());

        batch.setEnvironmentVariables(List.of(
                new BoardEnvironmentVariableDto("temperature", null, "trusted", "public")));
        ValidationException missingValue = assertThrows(
                ValidationException.class,
                () -> serviceWithEnvironment.saveBoardBatch(1L, batch));
        assertTrue(missingValue.getErrors().get("environmentVariables[0].value")
                .contains("explicit and non-blank"));
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void updateEnvironmentVariables_resetsOneVariableToTemplateDefaultsInsideAtomicMutation() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Thermostat")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(50)
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Thermostat")
                .manifestJson(JsonUtils.toJson(manifest))
                .defaultTemplate(true)
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("thermostat_1");
        node.setTemplateName("Thermostat");
        BoardEnvironmentVariablePo previous = BoardEnvironmentVariablePo.builder()
                .userId(1L)
                .name("temperature")
                .value("27")
                .trust("untrusted")
                .privacy("private")
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of(previous));
        when(environmentRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        List<BoardEnvironmentVariableDto> result = serviceWithEnvironment.updateEnvironmentVariables(
                1L,
                current -> List.of(new BoardEnvironmentVariableDto(
                        current.get(0).getName(), null, null, null)));

        assertEquals(1, result.size());
        assertEquals("temperature", result.get(0).getName());
        assertEquals("0", result.get(0).getValue());
        assertEquals("trusted", result.get(0).getTrust());
        assertEquals("public", result.get(0).getPrivacy());
        verify(transactionTemplate).execute(any());
    }

    @Test
    void saveEnvironmentVariables_preservesEveryFieldNotPresentInThePatch() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Thermostat")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(50)
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Thermostat")
                .manifestJson(JsonUtils.toJson(manifest))
                .defaultTemplate(true)
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("thermostat_1");
        node.setTemplateName("Thermostat");
        BoardEnvironmentVariablePo previous = BoardEnvironmentVariablePo.builder()
                .userId(1L)
                .name("temperature")
                .value("27")
                .trust("untrusted")
                .privacy("private")
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of(previous));
        when(environmentRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        EnvironmentMutationResultDto result = serviceWithEnvironment.saveEnvironmentVariables(
                1L,
                List.of(new EnvironmentVariableUpdateRequestDto(
                        "temperature",
                        new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                                " 27 ", " UNTRUSTED ", " PRIVATE "),
                        new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                                null, " TRUSTED ", null))));

        assertEquals("updated", result.getOperation());
        assertEquals(1, result.getCurrentCount());
        assertEquals(List.of(new BoardEnvironmentVariableDto(
                "temperature", "27", "trusted", "private")), result.getEnvironmentVariables());
        assertEquals(1, result.getPatchResults().size());
        assertEquals(List.of("trust"), result.getPatchResults().get(0).getSuppliedFields());
        assertEquals(List.of("trust"), result.getPatchResults().get(0).getChangedFields());
        assertEquals(List.of("value", "privacy"), result.getPatchResults().get(0).getPreservedFields());
        assertEquals("27", result.getPatchResults().get(0).getPreviousValue().getValue());
        assertEquals("private", result.getPatchResults().get(0).getCurrentValue().getPrivacy());
        assertEquals(1, result.getEnvironmentChanges().size());
        assertEquals(EnvironmentVariableChangeDto.ChangeType.UPDATED,
                result.getEnvironmentChanges().get(0).getChangeType());
        verify(environmentRepo).saveAll(argThat(saved -> {
            for (BoardEnvironmentVariablePo variable : saved) {
                if ("temperature".equals(variable.getName())
                        && "27".equals(variable.getValue())
                        && "trusted".equals(variable.getTrust())
                        && "private".equals(variable.getPrivacy())) {
                    return true;
                }
            }
            return false;
        }));

        assertThrows(ValidationException.class, () -> serviceWithEnvironment.saveEnvironmentVariables(
                1L,
                List.of(new EnvironmentVariableUpdateRequestDto(
                        "temperature",
                        new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                                "27", "untrusted", "private"),
                        new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                                " ", "trusted", null)))));
        verify(environmentRepo, times(1)).saveAll(any());
    }

    @Test
    void saveEnvironmentVariables_checksEveryBaselineBeforeWritingAnyItem() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Climate")
                .internalVariables(List.of(
                        DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                .name("temperature")
                                .isInside(false)
                                .lowerBound(0)
                                .upperBound(50)
                                .trust("trusted")
                                .privacy("public")
                                .build(),
                        DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                .name("humidity")
                                .isInside(false)
                                .lowerBound(0)
                                .upperBound(100)
                                .trust("trusted")
                                .privacy("public")
                                .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Climate")
                .manifestJson(JsonUtils.toJson(manifest))
                .defaultTemplate(true)
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("climate_1");
        node.setTemplateName("Climate");
        BoardEnvironmentVariablePo temperature = BoardEnvironmentVariablePo.builder()
                .userId(1L)
                .name("temperature")
                .value("27")
                .trust("untrusted")
                .privacy("private")
                .build();
        BoardEnvironmentVariablePo humidity = BoardEnvironmentVariablePo.builder()
                .userId(1L)
                .name("humidity")
                .value("40")
                .trust("trusted")
                .privacy("public")
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of(humidity, temperature));

        List<EnvironmentVariableUpdateRequestDto> updates = List.of(
                new EnvironmentVariableUpdateRequestDto(
                        "temperature",
                        new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                                "27", "untrusted", "private"),
                        new EnvironmentVariableUpdateRequestDto.DesiredPatch("28", null, null)),
                new EnvironmentVariableUpdateRequestDto(
                        "humidity",
                        new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                                "41", "trusted", "public"),
                        new EnvironmentVariableUpdateRequestDto.DesiredPatch("42", null, null)));

        EnvironmentVariableConflictException conflict = assertThrows(
                EnvironmentVariableConflictException.class,
                () -> serviceWithEnvironment.saveEnvironmentVariables(1L, updates));

        assertEquals("humidity", conflict.getVariableName());
        assertEquals("40", conflict.getCurrentVariable().getValue());
        verify(environmentRepo, never()).deleteAll(any());
        verify(environmentRepo, never()).saveAll(any());
    }

    @Test
    void saveEnvironmentVariables_reportsRemovedVariableAsStaleConflict() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("CurrentTemplate")
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(50)
                        .trust("trusted")
                        .privacy("public")
                        .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("CurrentTemplate")
                .manifestJson(JsonUtils.toJson(manifest))
                .defaultTemplate(true)
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("current_1");
        node.setTemplateName("CurrentTemplate");

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of());

        EnvironmentVariableConflictException conflict = assertThrows(
                EnvironmentVariableConflictException.class,
                () -> serviceWithEnvironment.saveEnvironmentVariables(1L, List.of(
                        new EnvironmentVariableUpdateRequestDto(
                                "removedVariable",
                                new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                                        "old", "trusted", "public"),
                                new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                                        "new", null, null)))));

        assertEquals("removedVariable", conflict.getVariableName());
        assertNull(conflict.getCurrentVariable());
        verify(environmentRepo, never()).deleteAll(any());
        verify(environmentRepo, never()).saveAll(any());
    }

    @Test
    void saveEnvironmentVariables_requiresANonBlankExpectedValue() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        ValidationException error = assertThrows(
                ValidationException.class,
                () -> serviceWithEnvironment.saveEnvironmentVariables(
                        1L,
                        List.of(new EnvironmentVariableUpdateRequestDto(
                                "temperature",
                                new EnvironmentVariableUpdateRequestDto.ExpectedValue(
                                        null, "trusted", "public"),
                                new EnvironmentVariableUpdateRequestDto.DesiredPatch(
                                        "28", null, null)))));

        assertEquals("Expected environment variable value is required",
                error.getErrors().get("environmentUpdates[0].expected.value"));
        verify(environmentRepo, never()).deleteAll(any());
        verify(environmentRepo, never()).saveAll(any());
    }

    @Test
    void updateNodeLayout_changesOnlyCanvasFields() {
        DeviceNodeMapper realMapper = new DeviceNodeMapper();
        BoardStorageServiceImpl serviceWithRealMapper = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, realMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceNodePo stored = DeviceNodePo.builder()
                .id("switch_1")
                .userId(1L)
                .templateName("Switch")
                .label("Hall switch")
                .posX(10.0)
                .posY(20.0)
                .state("on")
                .width(176)
                .height(128)
                .currentStateTrust("trusted")
                .currentStatePrivacy("private")
                .variablesJson("[{\"name\":\"level\",\"value\":\"2\",\"trust\":\"trusted\"}]")
                .privaciesJson("[{\"name\":\"level\",\"privacy\":\"private\"}]")
                .build();
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(35.0);
        position.setY(45.0);

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(stored));
        when(nodeRepo.findById(new cn.edu.nju.Iot_Verify.po.DeviceNodeId("switch_1", 1L)))
                .thenReturn(Optional.of(stored));
        when(nodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeviceUpdateResultDto result = serviceWithRealMapper.updateNodeLayout(
                1L, "switch_1", new DeviceLayoutDto(position, 190, 140));

        assertEquals("layout", result.getMutationType());
        assertEquals("updated", result.getOperation());
        assertEquals(List.of("position.x", "position.y", "width", "height"), result.getChangedFields());
        assertEquals("on", result.getCurrentDevice().getState());
        assertEquals("trusted", result.getCurrentDevice().getCurrentStateTrust());
        assertEquals("private", result.getCurrentDevice().getCurrentStatePrivacy());
        assertEquals("2", result.getCurrentDevice().getVariables().get(0).getValue());
        assertEquals("private", result.getCurrentDevice().getPrivacies().get(0).getPrivacy());
        assertEquals("Hall switch", result.getCurrentDevice().getLabel());
        assertEquals(35.0, result.getCurrentDevice().getPosition().getX());
        assertEquals(190, result.getCurrentDevice().getWidth());

        DeviceNodeDto.Position stalePosition = new DeviceNodeDto.Position();
        stalePosition.setX(10.0);
        stalePosition.setY(20.0);
        DeviceNodeDto.Position nextPosition = new DeviceNodeDto.Position();
        nextPosition.setX(50.0);
        nextPosition.setY(60.0);
        assertThrows(DeviceLayoutConflictException.class, () ->
                serviceWithRealMapper.updateNodeLayoutIfUnchanged(
                        1L,
                        "switch_1",
                        new DeviceLayoutDto(stalePosition, 176, 128),
                        new DeviceLayoutDto(nextPosition, 200, 150)));
    }

    @Test
    void saveNodes_acceptsPortableSceneLayoutBoundaries() {
        DeviceNodeDto node = boardNode("device-1", null, "Device");
        node.setWidth(DeviceLayoutDto.MAX_WIDTH);
        node.setHeight(DeviceLayoutDto.MIN_HEIGHT);

        when(deviceNodeMapper.toEntity(any(), anyLong())).thenReturn(new DeviceNodePo());
        when(nodeRepo.saveAll(any())).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);

        List<DeviceNodeDto> saved = service.saveNodes(1L, List.of(node));

        assertEquals(DeviceLayoutDto.MAX_WIDTH, saved.get(0).getWidth());
        assertEquals(DeviceLayoutDto.MIN_HEIGHT, saved.get(0).getHeight());
        verify(nodeRepo).saveAll(any());
    }

    /**
     * A value the device's state determines is corrected on write, not rejected.
     *
     * <p>The value is derived — the generator gives {@code next(<device>.<var>)} a branch per working state,
     * so a stored one survives only step 0 — which is how a Car came to be persisted in state {@code away}
     * reporting {@code location = garage}. Refusing it instead would be worse than the defect: this
     * validation runs over *every* stored node on ~19 write paths, so one such device would block adding a
     * device, creating a rule, renaming and even deleting, and the repair path revalidates the whole board,
     * so two of them could not be fixed at all. Canonicalising keeps every write working and leaves the
     * model self-consistent.</p>
     */
    @Test
    void addNodes_correctsALocalVariableTheStateDeterminesInsteadOfRejectingIt() {
        DeviceNodeMapper realMapper = new DeviceNodeMapper();
        BoardStorageServiceImpl serviceWithRealMapper = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, realMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Car")
                .modes(List.of("CarLocation"))
                .initState("away")
                .workingStates(List.of(
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                                .name("garage").trust("untrusted").privacy("private")
                                .dynamics(List.of(DeviceTemplateDto.DeviceManifest.Dynamic.builder()
                                        .variableName("location").value("garage").build()))
                                .build(),
                        DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                                .name("away").trust("untrusted").privacy("private")
                                .dynamics(List.of(DeviceTemplateDto.DeviceManifest.Dynamic.builder()
                                        .variableName("location").value("away").build()))
                                .build()))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("location").isInside(true).values(List.of("garage", "away"))
                        .trust("untrusted").privacy("private").falsifiableWhenCompromised(true).build()))
                .apis(List.of())
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L).name("Car").manifestJson(JsonUtils.toJson(manifest)).build();
        DeviceNodeDto draft = boardNode("car_1", "Car", "My car");
        draft.setState("away");
        // The reported contradiction, submitted verbatim.
        draft.setVariables(List.of(new VariableStateDto("location", "garage", "untrusted")));

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());
        when(nodeRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceNodeDto saved = serviceWithRealMapper.addNodes(1L, List.of(draft), List.of())
                .getAffectedDevices().get(0);

        assertEquals("away", saved.getState());
        assertEquals("away", saved.getVariables().get(0).getValue(),
                "the stored value must be the one the device's state declares");
        // The trust label is still the user's; only the value is derived.
        assertEquals("untrusted", saved.getVariables().get(0).getTrust());
    }

    @ParameterizedTest
    @ValueSource(strings = {"conflict", "same", "omitted", "transition", "undeclared"})
    void saveBoardBatch_requiresExplicitStateDerivedValuesToMatchBeforeWriting(String variant) throws Exception {
        var manifest = JsonUtils.fromJson(new String(getClass().getResourceAsStream("/deviceTemplate/Car.json")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), DeviceTemplateDto.DeviceManifest.class);
        if (variant.equals("transition")) {
            manifest.setTransitions(List.of(DeviceTemplateDto.DeviceManifest.Transition.builder()
                    .name("move").startState("away")
                    .assignments(List.of(DeviceTemplateDto.DeviceManifest.Assignment.builder()
                            .attribute("location").value("garage").build())).build()));
        }
        if (variant.equals("undeclared")) manifest.getWorkingStates().forEach(state -> state.setDynamics(List.of()));
        var stored = DeviceTemplatePo.builder().userId(1L).name("Car")
                .manifestJson(JsonUtils.toJson(manifest)).build();
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(stored));
        lenient().when(nodeRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(),
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        var node = boardNode("car_1", "Car", "My car");
        node.setState("away");
        if (!variant.equals("omitted")) node.setVariables(List.of(new VariableStateDto("location",
                variant.equals("same") ? "away" : "garage", "untrusted")));
        var batch = new BoardBatchDto(List.of(node), List.of(), List.of(), List.of());
        batch.setTemplateSnapshots(List.of(new DeviceTemplateMapper().toDto(stored)));
        confirmedBatch(serviceWithTemplates, batch);
        if (variant.equals("conflict")) {
            var error = assertThrows(ValidationException.class, () -> serviceWithTemplates.saveBoardBatch(1L, batch));
            assertTrue(error.getErrors().containsKey("nodes[0].variables[0].value"));
            assertEquals("garage", node.getVariables().get(0).getValue());
            verify(nodeRepo, never()).saveAll(any());
            verify(editJournal, never()).clear(anyLong());
        } else {
            var saved = assertDoesNotThrow(() -> serviceWithTemplates.saveBoardBatch(1L, batch));
            if (!variant.equals("omitted")) assertEquals(variant.equals("same") ? "away" : "garage",
                    saved.getNodes().get(0).getVariables().get(0).getValue());
        }
    }

    @Test
    void addNodes_canonicalizesRuntimeValuesBeforePersistence() {
        DeviceNodeMapper realMapper = new DeviceNodeMapper();
        BoardStorageServiceImpl serviceWithRealMapper = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, realMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Switch")
                .modes(List.of("Power"))
                .initState("off")
                .workingStates(List.of(workingState("off"), workingState("on")))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("mode").isInside(true).values(List.of("eco", "turbo"))
                        .trust("trusted").privacy("public").build()))
                .apis(List.of())
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L).name("Switch").manifestJson(JsonUtils.toJson(manifest)).build();
        DeviceNodeDto draft = boardNode("switch_1", " switch ", "Hall switch");
        draft.setState(" off ");
        draft.setCurrentStateTrust("TRUSTED");
        draft.setCurrentStatePrivacy(" PUBLIC ");
        draft.setVariables(List.of(new VariableStateDto(" mode ", " eco ", "TRUSTED")));

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());
        when(nodeRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceNodeDto saved = serviceWithRealMapper.addNodes(1L, List.of(draft), List.of())
                .getAffectedDevices().get(0);

        assertEquals("Switch", saved.getTemplateName());
        assertEquals("off", saved.getState());
        assertEquals("trusted", saved.getCurrentStateTrust());
        assertEquals("public", saved.getCurrentStatePrivacy());
        assertEquals("mode", saved.getVariables().get(0).getName());
        assertEquals("eco", saved.getVariables().get(0).getValue());
        assertEquals("trusted", saved.getVariables().get(0).getTrust());
    }

    @Test
    void saveBoardBatch_rejectsLayoutOutsidePortableSceneRangeBeforeMutation() {
        DeviceNodeDto node = boardNode("device-1", null, "Device");
        node.setWidth(DeviceLayoutDto.MAX_WIDTH + 1);

        ValidationException error = assertThrows(ValidationException.class, () ->
                service.saveBoardBatch(1L,
                        confirmedBatch(service, new BoardBatchDto(List.of(node), List.of(), List.of()))));

        assertEquals("Width must be within 80..2000", error.getErrors().get("nodes[0].width"));
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void updateNodeRuntime_changesOnlyDeviceLocalRuntimeFields() {
        DeviceNodeMapper realMapper = new DeviceNodeMapper();
        DeviceTemplateMapper templateMapper = spy(new DeviceTemplateMapper());
        BoardStorageServiceImpl serviceWithRealMapper = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, realMapper,
                null, templateMapper, null, userRepository, editJournal);
        DeviceNodePo stored = DeviceNodePo.builder()
                .id("switch_1")
                .userId(1L)
                .templateName("Switch")
                .label("Hall switch")
                .posX(10.0)
                .posY(20.0)
                .state("off")
                .width(176)
                .height(128)
                .currentStateTrust("trusted")
                .currentStatePrivacy("public")
                .build();
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Switch")
                .modes(List.of("Power"))
                .initState("off")
                .workingStates(List.of(workingState("off"), workingState("on")))
                .apis(List.of())
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Switch")
                .manifestJson(JsonUtils.toJson(manifest))
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(stored));
        when(nodeRepo.findById(new cn.edu.nju.Iot_Verify.po.DeviceNodeId("switch_1", 1L)))
                .thenReturn(Optional.of(stored));
        when(nodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        DeviceRuntimeConfigDto expected = new DeviceRuntimeConfigDto();
        expected.setState("off");
        expected.setCurrentStateTrust("trusted");
        expected.setCurrentStatePrivacy("public");
        DeviceRuntimeConfigDto desired = new DeviceRuntimeConfigDto();
        desired.setState("on");
        desired.setCurrentStateTrust("trusted");
        desired.setCurrentStatePrivacy("public");
        DeviceUpdateResultDto result = serviceWithRealMapper.updateNodeRuntime(
                1L, "switch_1", new DeviceRuntimeUpdateDto(expected, desired));

        assertEquals("runtime", result.getMutationType());
        assertEquals("updated", result.getOperation());
        assertEquals(List.of("state"), result.getChangedFields());
        assertEquals("on", result.getCurrentDevice().getState());
        assertEquals("Hall switch", result.getCurrentDevice().getLabel());
        assertEquals("Switch", result.getCurrentDevice().getTemplateName());
        assertEquals(10.0, result.getCurrentDevice().getPosition().getX());
        assertEquals(20.0, result.getCurrentDevice().getPosition().getY());
        assertEquals(176, result.getCurrentDevice().getWidth());
        assertEquals(128, result.getCurrentDevice().getHeight());
        verify(deviceTemplateRepo).findByUserId(1L);
        verify(templateMapper).toDto(template);

        // A later write is visible after the user-row lock; the old baseline must no longer apply.
        assertThrows(DeviceRuntimeConflictException.class, () ->
                serviceWithRealMapper.updateNodeRuntime(
                        1L, "switch_1", new DeviceRuntimeUpdateDto(expected, desired)));
    }

    @Test
    void updateNodeRuntimeCanonicalizesWhitespaceAroundDeclaredLocalVariableNames() {
        DeviceNodeMapper realMapper = new DeviceNodeMapper();
        BoardStorageServiceImpl serviceWithRealMapper = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, realMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceNodePo stored = DeviceNodePo.builder()
                .id("switch_1").userId(1L).templateName("Switch").label("Hall switch")
                .posX(10.0).posY(20.0).state("off").width(176).height(128)
                .variablesJson(JsonUtils.toJson(List.of(new VariableStateDto(
                        "mode", "eco", null))))
                .build();
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Switch").modes(List.of("Power")).initState("off")
                .workingStates(List.of(workingState("off"), workingState("on")))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("mode").isInside(true).values(List.of("eco", "turbo"))
                        .trust("trusted").privacy("public").build()))
                .apis(List.of()).build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L).name("Switch").manifestJson(JsonUtils.toJson(manifest)).build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(stored));
        when(nodeRepo.findById(new cn.edu.nju.Iot_Verify.po.DeviceNodeId("switch_1", 1L)))
                .thenReturn(Optional.of(stored));
        when(nodeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        DeviceRuntimeConfigDto expected = new DeviceRuntimeConfigDto();
        expected.setState("off");
        expected.setVariables(List.of(new VariableStateDto(
                " mode ", "eco", null)));
        DeviceRuntimeConfigDto desired = new DeviceRuntimeConfigDto();
        desired.setState("off");
        desired.setVariables(List.of(new VariableStateDto(
                " mode ", " turbo ", "TRUSTED")));

        DeviceUpdateResultDto result = serviceWithRealMapper.updateNodeRuntime(
                1L, "switch_1", new DeviceRuntimeUpdateDto(expected, desired));

        assertEquals("updated", result.getOperation());
        assertEquals("mode", result.getCurrentDevice().getVariables().get(0).getName());
        assertEquals("turbo", result.getCurrentDevice().getVariables().get(0).getValue());
        assertEquals("trusted", result.getCurrentDevice().getVariables().get(0).getTrust());
    }

    @Test
    void updateNodeRuntime_rewritesNonCanonicalStoredValuesEvenWhenDesiredRuntimeIsEquivalent() {
        DeviceNodeMapper realMapper = new DeviceNodeMapper();
        BoardStorageServiceImpl serviceWithRealMapper = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, realMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceNodePo stored = DeviceNodePo.builder()
                .id("switch_1").userId(1L).templateName("Switch").label("Hall switch")
                .posX(10.0).posY(20.0).state(" off ").width(176).height(128)
                .currentStateTrust("TRUSTED")
                .variablesJson(JsonUtils.toJson(List.of(new VariableStateDto(
                        " mode ", " eco ", "TRUSTED"))))
                .build();
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Switch").modes(List.of("Power")).initState("off")
                .workingStates(List.of(workingState("off"), workingState("on")))
                .internalVariables(List.of(DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("mode").isInside(true).values(List.of("eco", "turbo"))
                        .trust("trusted").privacy("public").build()))
                .apis(List.of()).build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L).name("Switch").manifestJson(JsonUtils.toJson(manifest)).build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(stored));
        when(nodeRepo.findById(new cn.edu.nju.Iot_Verify.po.DeviceNodeId("switch_1", 1L)))
                .thenReturn(Optional.of(stored));
        when(nodeRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        DeviceRuntimeConfigDto canonical = new DeviceRuntimeConfigDto();
        canonical.setState("off");
        canonical.setCurrentStateTrust("trusted");
        canonical.setVariables(List.of(new VariableStateDto("mode", "eco", "trusted")));

        DeviceUpdateResultDto result = serviceWithRealMapper.updateNodeRuntime(
                1L, "switch_1", new DeviceRuntimeUpdateDto(canonical, canonical));

        assertEquals("updated", result.getOperation());
        assertTrue(result.getChangedFields().contains("state"));
        assertTrue(result.getChangedFields().contains("currentStateTrust"));
        assertTrue(result.getChangedFields().contains("variables"));
        assertEquals("off", result.getCurrentDevice().getState());
        assertEquals("trusted", result.getCurrentDevice().getCurrentStateTrust());
        assertEquals("mode", result.getCurrentDevice().getVariables().get(0).getName());
        verify(nodeRepo).save(stored);
    }

    /**
     * A variable condition names one of two different values, so admission must make the author say which.
     *
     * <p>{@code environment} compiles to the shared pool value — "did this happen in the home" —
     * and {@code reported} to what this device said. They are equal until the device is compromised, and
     * then they are not: before this field existed the builder always chose the pool value and dropped the
     * device the author picked, so "temperature never exceeds 30" was reported SATISFIED while a falsified
     * reading of 40 drove the rule.
     *
     * <p>Four outcomes are pinned here because each fails differently: absent (no question chosen),
     * environment-on-a-device-local variable (names an identifier the model never declares), present on a
     * non-variable target (meaningless), and the two legitimate accepts.
     */
    private SpecificationDto specWithVariableSource(String key, String variableSource, String targetType) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId("condition-1");
        condition.setSide("a");
        condition.setDeviceId("sensor_1");
        condition.setTargetType(targetType);
        condition.setKey(key);
        condition.setVariableSource(variableSource);
        condition.setRelation("=");
        condition.setValue("present");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("safety-1");
        spec.setName("Safety requirement");
        spec.setTemplateId("7");
        spec.setAConditions(List.of(condition));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        return spec;
    }

    /** {@code shared} declares occupancy with IsInside=false, so it has a pool value; local does not. */
    private BoardStorageServiceImpl serviceWithVariableTemplate(boolean shared) {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceTemplateDto.DeviceManifest.InternalVariable variable =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("occupancy")
                        .isInside(!shared)
                        .values(List.of("present", "absent"))
                        .trust("trusted")
                        .privacy("public")
                        .build();
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Sensor")
                .internalVariables(List.of(variable))
                // Mode-less on purpose: a sensor with modes would need a node state as well, which is
                // scaffolding unrelated to the variableSource gate under test.
                .modes(List.of())
                .initState("")
                .workingStates(List.of())
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Sensor")
                .manifestJson(JsonUtils.toJson(manifest))
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Occupancy Sensor");
        node.setTemplateName("Sensor");
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(0.0);
        position.setY(0.0);
        node.setPosition(position);
        node.setWidth(176);
        node.setHeight(128);
        // Lenient because this helper serves tests that stop at different depths: the
        // missing-reading case is refused before any template is consulted (that check needs no
        // declaration), while the environment-on-device-local case must reach the manifest.
        lenient().when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        lenient().when(deviceNodeMapper.toDto(any())).thenReturn(node);
        lenient().when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        lenient().when(specRepo.findByUserId(1L)).thenReturn(List.of());
        return serviceWithTemplates;
    }

    @Test
    void addSpec_rejectsVariableConditionWithoutVariableSource() {
        /*
         * Refused before any template or manifest is consulted, because "did the author choose?" needs no
         * declaration to answer. That ordering is deliberate: the same semantic validation also runs over
         * *stored* specifications to guard device and rule writes, so requiring a choice there would have
         * made one legacy specification block every unrelated mutation. The requirement lives on the write
         * that authors the specification instead — hence `lenient()`, as the template stubs this fixture
         * sets up are no longer reached.
         */
        BoardStorageServiceImpl service = serviceWithVariableTemplate(true);

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.addSpec(1L, specWithVariableSource("occupancy", null, "variable")));

        assertTrue(error.getErrors().values().stream()
                        .anyMatch(message -> message.contains("variableSource is required")),
                () -> "Expected a variableSource requirement, got " + error.getErrors());
        verify(specRepo, never()).save(any());
    }

    @Test
    void addSpec_rejectsVariableSourceOnANonVariableTarget() {
        // The class comment claims this outcome is pinned; it was not. A reading on a state or mode
        // condition is meaningless — there is no second value to choose between — and silently ignoring it
        // would let a caller believe a distinction was recorded when none applies.
        BoardStorageServiceImpl service = serviceWithVariableTemplate(true);

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.addSpec(1L, specWithVariableSource("state", "environment", "state")));

        assertTrue(error.getErrors().values().stream()
                        .anyMatch(message -> message.contains("only valid for variable conditions")),
                () -> "Expected a non-variable rejection, got " + error.getErrors());
        verify(specRepo, never()).save(any());
    }

    @Test
    void addSpec_rejectsEnvironmentSourceOnDeviceLocalVariable() {
        BoardStorageServiceImpl service = serviceWithVariableTemplate(false);

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.addSpec(1L, specWithVariableSource("occupancy", "environment", "variable")));

        assertTrue(error.getErrors().values().stream()
                        .anyMatch(message -> message.contains("needs a shared variable")),
                () -> "Expected a shared-variable rejection, got " + error.getErrors());
        verify(specRepo, never()).save(any());
    }

    /**
     * The accepts are asserted as "not rejected by validation", not as a completed save: this class stubs
     * the specification mapper only where a test needs the persisted row, and reaching the mapper at all
     * proves the condition cleared every semantic gate. A {@link ValidationException} here would be the
     * regression; the NPE from the unstubbed mapper is this fixture's boundary, not a product failure.
     */
    private void assertPassesSpecValidation(BoardStorageServiceImpl service, SpecificationDto spec) {
        try {
            service.addSpec(1L, spec);
        } catch (ValidationException rejected) {
            throw new AssertionError("Expected the condition to clear validation, got " + rejected.getErrors(),
                    rejected);
        } catch (RuntimeException reachedPersistence) {
            // Past validation. Anything the stub-less mapper throws is out of scope for this assertion.
        }
    }

    @Test
    void addSpec_bothVariableSourcesClearValidationOnASharedDeclaration() {
        BoardStorageServiceImpl service = serviceWithVariableTemplate(true);

        assertPassesSpecValidation(service,
                specWithVariableSource("occupancy", "environment", "variable"));
        assertPassesSpecValidation(service,
                specWithVariableSource("occupancy", "reported", "variable"));
    }

    @Test
    void addSpec_acceptsReportedOnADeviceLocalVariable() {
        BoardStorageServiceImpl service = serviceWithVariableTemplate(false);

        assertPassesSpecValidation(service,
                specWithVariableSource("occupancy", "reported", "variable"));
    }

    @Test
    void addSpec_rejectsUntrustedSourceSafetyApiWithoutModeledEndState() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Notification Service")
                .modes(List.of("Status"))
                .initState("idle")
                .workingStates(List.of(workingState("idle")))
                .apis(List.of(DeviceTemplateDto.DeviceManifest.API.builder()
                        .name("notify")
                        .signal(true)
                        .build()))
                .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Notification Service")
                .manifestJson(JsonUtils.toJson(manifest))
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("service_1");
        node.setLabel("Notification Service");
        node.setTemplateName("Notification Service");
        SpecConditionDto condition = new SpecConditionDto();
        condition.setId("condition-1");
        condition.setSide("a");
        condition.setDeviceId("service_1");
        condition.setTargetType("api");
        condition.setKey("notify");
        condition.setRelation("=");
        condition.setValue("TRUE");
        SpecificationDto spec = new SpecificationDto();
        spec.setId("safety-1");
        spec.setName("Safety requirement");
        spec.setTemplateId("7");
        spec.setAConditions(List.of(condition));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        ValidationException error = assertThrows(
                ValidationException.class,
                () -> serviceWithTemplates.addSpec(1L, spec));

        assertTrue(error.getErrors().values().stream().anyMatch(message -> message.contains("has no EndState")));
        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void saveBoardBatch_sceneTemplateMismatchRejectsBeforeBoardMutation() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest existingManifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Sensor")
                .description("Existing sensor")
                .build();
        DeviceTemplatePo existing = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Sensor")
                .manifestJson(JsonUtils.toJson(existingManifest))
                .build();
        DeviceTemplateDto.DeviceManifest importedManifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Sensor")
                .description("Different sensor semantics")
                .build();
        DeviceTemplateDto importedSnapshot = new DeviceTemplateDto();
        importedSnapshot.setName("Sensor");
        importedSnapshot.setManifest(importedManifest);
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setTemplateName("Sensor");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(existing));
        BoardBatchDto batch = new BoardBatchDto(List.of(node), List.of(), List.of());
        batch.setEnvironmentVariables(List.of());
        batch.setTemplateSnapshots(List.of(importedSnapshot));

        assertThrows(ConflictException.class, () -> serviceWithTemplates.saveBoardBatch(
                1L, confirmedBatch(serviceWithTemplates, batch)));
        verify(nodeRepo, never()).deleteAll(any());
        verify(ruleRepo, never()).saveAll(any());
        verify(specRepo, never()).deleteAll(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "manifest"})
    void saveBoardBatch_reusesCanonicalTemplateAfterImportingNameWhitespace(String paddedField) {
        var serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(),
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        var manifest = DeviceTemplateDto.DeviceManifest.builder().name("Sensor").initState("")
                .modes(List.of()).workingStates(List.of()).internalVariables(List.of()).apis(List.of()).build();
        var stored = DeviceTemplatePo.builder().userId(1L).name("Sensor")
                .manifestJson(JsonUtils.toJson(manifest)).build();
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(stored));
        when(nodeRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        for (int attempt = 0; attempt < 2; attempt++) {
            var snapshot = new DeviceTemplateMapper().toDto(stored);
            if (!paddedField.equals("manifest")) snapshot.setName(" Sensor ");
            if (!paddedField.equals("name")) snapshot.getManifest().setName(" Sensor ");
            var batch = new BoardBatchDto(List.of(boardNode("sensor", "sensor", "Sensor device")),
                    List.of(), List.of(), List.of());
            batch.setTemplateSnapshots(List.of(snapshot));
            var result = assertDoesNotThrow(() -> serviceWithTemplates.saveBoardBatch(
                    1L, confirmedBatch(serviceWithTemplates, batch)));
            assertTrue(result.getCreatedTemplates().isEmpty());
            assertEquals("Sensor", result.getNodes().get(0).getTemplateName());
        }
        verify(deviceTemplateRepo, never()).saveAndFlush(any());
    }

    @Test
    void saveBoardBatch_sceneSnapshotNameMismatchRejectsBeforeBoardMutation() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Original Sensor")
                .description("Portable sensor semantics")
                .build();
        DeviceTemplateDto snapshot = new DeviceTemplateDto();
        snapshot.setName("Renamed Sensor");
        snapshot.setManifest(manifest);
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setTemplateName("Renamed Sensor");

        BoardBatchDto batch = new BoardBatchDto(List.of(node), List.of(), List.of());
        batch.setEnvironmentVariables(List.of());
        batch.setTemplateSnapshots(List.of(snapshot));

        BadRequestException error = assertThrows(
                BadRequestException.class,
                () -> serviceWithTemplates.saveBoardBatch(
                        1L, confirmedBatch(serviceWithTemplates, batch)));

        assertTrue(error.getMessage().contains("must exactly match manifest.Name"));
        assertTrue(error.getMessage().contains("cannot rename"));
        verify(deviceTemplateRepo, never()).save(any());
        verify(deviceTemplateRepo, never()).saveAndFlush(any());
        verify(nodeRepo, never()).deleteAll(any());
        verify(ruleRepo, never()).saveAll(any());
        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void saveBoardBatch_sceneReplacementRequiresEverySemanticCollection() {
        BoardBatchDto batch = new BoardBatchDto(List.of(), null, List.of(), List.of());
        batch.setTemplateSnapshots(List.of());

        ValidationException error = assertThrows(
                ValidationException.class,
                () -> service.saveBoardBatch(1L, batch));

        assertTrue(error.getErrors().containsKey("environmentVariables"));
        verify(nodeRepo, never()).deleteAll(any());
        verify(specRepo, never()).deleteAll(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"enum", "numeric"})
    void saveBoardBatch_usesSubmittedEnvironmentWhenTheReplacementChangesADomain(String domainType) {
        var serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(),
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);
        boolean discrete = "enum".equals(domainType);
        String variableName = discrete ? "motion" : "temperature";
        String previousValue = discrete ? "active" : "90";
        String nextValue = discrete ? "clear" : "20";
        var declaration = DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                .name(variableName).isInside(false).reads(true).trust("trusted").privacy("public");
        if (discrete) {
            declaration.values(List.of("detected", "clear"));
        } else {
            declaration.lowerBound(0).upperBound(30).naturalChangeRate("0");
        }
        var manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Replacement sensor").modes(List.of()).initState("")
                .internalVariables(List.of(declaration.build())).build();
        var template = DeviceTemplatePo.builder().userId(1L).name(manifest.getName())
                .manifestJson(JsonUtils.toJson(manifest)).build();
        var previousDeclaration = DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                .name(variableName).isInside(false).reads(true).trust("untrusted").privacy("private");
        if (discrete) {
            previousDeclaration.values(List.of("active", "inactive"));
        } else {
            previousDeclaration.lowerBound(0).upperBound(100).naturalChangeRate("0");
        }
        var previousManifest = DeviceTemplateDto.DeviceManifest.builder().name("Previous sensor")
                .modes(List.of()).initState("").internalVariables(List.of(previousDeclaration.build())).build();
        var previousTemplate = DeviceTemplatePo.builder().userId(1L).name(previousManifest.getName())
                .manifestJson(JsonUtils.toJson(previousManifest)).build();
        var previous = BoardEnvironmentVariablePo.builder().userId(1L).name(variableName)
                .value(previousValue).trust("untrusted").privacy("private").build();
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(previousTemplate, template));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodeMapper().toEntity(
                boardNode("previous_sensor", previousManifest.getName(), "Previous sensor"), 1L)));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of(previous));
        when(nodeRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var expected = new BoardEnvironmentVariableDto(variableName, nextValue, "trusted", "public");
        var batch = new BoardBatchDto(
                List.of(boardNode("sensor", manifest.getName(), "Replacement sensor")),
                List.of(expected), List.of(), List.of());
        batch.setTemplateSnapshots(List.of(new DeviceTemplateMapper().toDto(template)));

        var result = serviceWithEnvironment.saveBoardBatch(1L, confirmedBatch(serviceWithEnvironment, batch));

        assertEquals(List.of(expected), result.getEnvironmentVariables());
        verify(environmentRepo).saveAll(argThat(values -> {
            var rows = new ArrayList<BoardEnvironmentVariablePo>();
            values.forEach(rows::add);
            return rows.size() == 1 && nextValue.equals(rows.get(0).getValue())
                    && "trusted".equals(rows.get(0).getTrust()) && "public".equals(rows.get(0).getPrivacy());
        }));
        verify(editJournal).clear(1L);
    }

    @Test
    void saveBoardBatch_missingTemplateSnapshotRejectsEvenWhenTemplateAlreadyExists() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Sensor")
                .description("Portable sensor semantics")
                .build();
        DeviceTemplatePo existing = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Sensor")
                .manifestJson(JsonUtils.toJson(manifest))
                .build();
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setTemplateName("Sensor");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(existing));
        BoardBatchDto batch = new BoardBatchDto(List.of(node), List.of(), List.of());
        batch.setEnvironmentVariables(List.of());
        batch.setTemplateSnapshots(List.of());

        BadRequestException error = assertThrows(
                BadRequestException.class,
                () -> serviceWithTemplates.saveBoardBatch(
                        1L, confirmedBatch(serviceWithTemplates, batch)));

        assertTrue(error.getMessage().contains("self-contained"));
        verify(nodeRepo, never()).deleteAll(any());
        verify(ruleRepo, never()).saveAll(any());
        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void saveBoardBatch_unreferencedTemplateSnapshotRejectsBeforeBoardMutation() {
        BoardStorageServiceImpl serviceWithTemplates = new BoardStorageServiceImpl(
                nodeRepo, null, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Unused Sensor")
                .description("Not referenced by any imported device")
                .build();
        DeviceTemplateDto snapshot = new DeviceTemplateDto();
        snapshot.setName("Unused Sensor");
        snapshot.setManifest(manifest);

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of());
        BoardBatchDto batch = new BoardBatchDto(List.of(), List.of(), List.of());
        batch.setEnvironmentVariables(List.of());
        batch.setTemplateSnapshots(List.of(snapshot));

        BadRequestException error = assertThrows(
                BadRequestException.class,
                () -> serviceWithTemplates.saveBoardBatch(
                        1L, confirmedBatch(serviceWithTemplates, batch)));

        assertTrue(error.getMessage().contains("unreferenced template snapshot"));
        verify(nodeRepo, never()).deleteAll(any());
        verify(ruleRepo, never()).saveAll(any());
        verify(specRepo, never()).deleteAll(any());
    }

    @Test
    void addRule_canonicalizesRelationAndTargetTypeBeforePersistence() {
        DeviceNodeDto node = boardNode("sensor1", null, "Sensor");

        RuleDto rule = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor1")
                        .attribute("temperature")
                        .targetType("VARIABLE")
                        .relation(" LTE ")
                        .value("20")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("sensor1")
                        .action("heat")
                        .build())
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(), List.of());
        when(ruleMapper.toEntity(any(), anyLong())).thenReturn(new RulePo());
        RulePo savedEntity = new RulePo();
        savedEntity.setId(1L);
        when(ruleRepo.save(any())).thenReturn(savedEntity);

        service.addRule(1L, rule);

        verify(ruleMapper).toEntity(argThat(savedRule ->
                savedRule.getId() == null
                        && "<=".equals(savedRule.getConditions().get(0).getRelation())
                        && "variable".equals(savedRule.getConditions().get(0).getTargetType())), anyLong());
    }

    /**
     * The documented per-rule condition cap has to live in the service, not only on the DTO.
     *
     * <p>`RequestLimits.MAX_RULE_CONDITIONS` was referenced in exactly one place — `@Size` on
     * `RuleDto.conditions` — which Spring applies only on the `@Valid` REST path. The AI tools call this
     * service straight from a chat turn with no `Validator` in between (no service class carries
     * `@Validated`, and `AbstractAiTool`'s field helpers only trim), and `ManageRuleTool` loops the JSON
     * conditions array without a cap. So the assistant could store a rule the UI cannot submit, and the
     * two write paths disagreed on what a legal rule is.
     *
     * <p>Pinned one past the limit and exactly at it, so this fails on an off-by-one rather than only on
     * a comfortably-illegal size.
     */
    @Test
    void addRule_whenConditionsExceedTheDocumentedCap_shouldRejectOnTheSharedServicePath() {
        DeviceNodeDto node = boardNode("sensor1", null, "Sensor");
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(new DeviceNodePo()));
        when(deviceNodeMapper.toDto(any())).thenReturn(node);
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(), List.of());
        when(ruleMapper.toEntity(any(), anyLong())).thenReturn(new RulePo());
        RulePo savedEntity = new RulePo();
        savedEntity.setId(1L);
        when(ruleRepo.save(any())).thenReturn(savedEntity);

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.addRule(1L, ruleWithConditions(RequestLimits.MAX_RULE_CONDITIONS + 1)));
        assertTrue(error.getErrors().toString().contains(String.valueOf(RequestLimits.MAX_RULE_CONDITIONS)),
                () -> "the error should name the limit, got " + error.getErrors());
        verify(ruleRepo, never()).save(any());

        assertDoesNotThrow(() -> service.addRule(1L, ruleWithConditions(RequestLimits.MAX_RULE_CONDITIONS)));
    }

    @Test
    void updateRulesAgainstSnapshot_rejectsOversizedConditionsBeforeAnyWrite() {
        ValidationException error = assertThrows(ValidationException.class,
                () -> service.updateRulesAgainstSnapshot(1L,
                        snapshot -> List.of(ruleWithConditions(RequestLimits.MAX_RULE_CONDITIONS + 1))));

        assertTrue(error.getErrors().containsKey("conditions"));
        verify(ruleRepo, never()).save(any());
        verify(ruleRepo, never()).deleteAll(any());
        verifyNoInteractions(editJournal);
    }

    @Test
    void updateRulesAgainstSnapshot_rejectsOversizedPreviewBeforeAnyWrite() {
        RuleDto rule = ruleWithConditions(1);
        rule.setRuleString("x".repeat(RequestLimits.MAX_DESCRIPTION_LENGTH + 1));

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.updateRulesAgainstSnapshot(1L, snapshot -> List.of(rule)));

        assertTrue(error.getErrors().containsKey("ruleString"));
        verify(ruleRepo, never()).save(any());
        verify(ruleRepo, never()).deleteAll(any());
        verifyNoInteractions(editJournal);
    }

    /**
     * The preview bound must not sit behind the conditions null-guard.
     *
     * <p>`ruleString` does not depend on `conditions`, so a check placed after `if (conditions == null)
     * continue` is skipped entirely by a request that omits the array — and `rule_string` is TEXT, so an
     * unbounded preview then persists silently, which is the exact case the bound exists to stop.
     */
    @Test
    void saveBoardBatch_whenConditionsAreAbsent_stillBoundsTheRulePreview() {
        // Driven through the batch path on purpose: `addRule` cannot reach the null case, because
        // `canonicalizeRuleRelationsForStorage` rewrites absent conditions to an empty list before any
        // validation runs. A test written against `addRule` would pass for that reason instead of this one.
        RuleDto rule = RuleDto.builder()
                .command(RuleDto.Command.builder().deviceName("sensor1").action("heat").build())
                .ruleString("x".repeat(RequestLimits.MAX_DESCRIPTION_LENGTH + 1))
                .build();

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.saveBoardBatch(1L, confirmedBatch(service,
                        new BoardBatchDto(List.of(), List.of(rule), List.of()))));
        assertTrue(error.getErrors().containsKey("ruleString"),
                () -> "the preview bound should name ruleString, got " + error.getErrors());
        verify(ruleRepo, never()).deleteByUserId(anyLong());
    }

    /**
     * The spec-condition cap must report under its own error key.
     *
     * <p>The distinct suffix keeps the cap legible where `validateSpecTemplateShape` also reports: that
     * check owns `aConditions` / `ifConditions` for its shape errors, so sharing the key made a
     * template-4 specification with 60 A-conditions report only "uses IF/THEN conditions only" — the
     * shape complaint — while the real reason went unsaid. Uses template 4 so both are in play at once;
     * the request is rejected before the shape check now, and the key still says which list was too long.
     */
    @Test
    void addSpec_whenConditionsExceedTheCap_reportsUnderAKeyTheShapeCheckDoesNotOwn() {
        SpecificationDto spec = new SpecificationDto();
        spec.setName("Safety requirement");
        spec.setTemplateId("4");
        List<SpecConditionDto> conditions = new ArrayList<>();
        for (int i = 0; i < RequestLimits.MAX_SPEC_CONDITIONS + 1; i++) {
            SpecConditionDto condition = new SpecConditionDto();
            condition.setSide("a");
            condition.setDeviceId("sensor1");
            condition.setTargetType("state");
            condition.setKey("state");
            condition.setRelation("=");
            condition.setValue("on");
            conditions.add(condition);
        }
        spec.setAConditions(conditions);

        ValidationException error = assertThrows(ValidationException.class, () -> service.addSpec(1L, spec));
        assertTrue(error.getErrors().containsKey("aConditions.size"),
                () -> "the cap should have its own key, got " + error.getErrors());
        verify(specRepo, never()).saveAll(any());
    }

    @Test
    void saveNodes_isNotBlockedByAStoredRuleThatExceedsTheConditionCap() {
        /*
         * Same shape as the stored-specification-with-no-reading test above, and the reason the caps
         * live in the authoring paths rather than in `validateBoardReferences`.
         *
         * That re-validator sees the WHOLE stored collection on every device add, layout move, spec add
         * and undo. `rule_string` is TEXT and the condition array is JSON, so unlike the device label —
         * whose column is `length = 255` and therefore cannot already hold an over-long value — an
         * oversized rule CAN already be persisted, and the AI tools could write one before the cap
         * existed. Checking it there rejected unrelated writes over a rule the request never mentioned,
         * leaving the user unable to add a device to get unstuck.
         */
        DeviceNodeDto node = boardNode("sensor1", null, "Living Sensor");
        RulePo oversizedPo = new RulePo();
        // The ordered query, not findByUserId: getRulesInternal reads execution order because that is
        // model semantics, and stubbing the wrong one leaves the re-validator seeing an empty collection —
        // which made this test pass against the very placement it exists to reject.
        lenient().when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L))
                .thenReturn(List.of(oversizedPo));
        lenient().when(ruleMapper.toDto(oversizedPo))
                .thenReturn(ruleWithConditions(RequestLimits.MAX_RULE_CONDITIONS + 5));
        lenient().when(specRepo.findByUserId(1L)).thenReturn(List.of());
        lenient().when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of());
        lenient().when(deviceNodeMapper.toEntity(any(), anyLong())).thenReturn(new DeviceNodePo());
        lenient().when(nodeRepo.saveAll(any())).thenReturn(List.of(new DeviceNodePo()));
        lenient().when(deviceNodeMapper.toDto(any())).thenReturn(node);

        assertDoesNotThrow(() -> service.saveNodes(1L, List.of(node)),
                "a stored oversized rule must not block an unrelated device write");
    }

    private RuleDto ruleWithConditions(int count) {
        List<RuleDto.Condition> conditions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            conditions.add(RuleDto.Condition.builder()
                    .deviceName("sensor1")
                    .attribute("temperature")
                    .targetType("variable")
                    .relation("<=")
                    .value(String.valueOf(20 + i))
                    .build());
        }
        return RuleDto.builder()
                .conditions(conditions)
                .command(RuleDto.Command.builder().deviceName("sensor1").action("heat").build())
                .build();
    }

    @Test
    void reorderRules_persistsTheCompleteUserControlledExecutionOrder() {
        RulePo firstPo = RulePo.builder().id(1L).userId(1L).executionOrder(0).build();
        RulePo secondPo = RulePo.builder().id(2L).userId(1L).executionOrder(1).build();
        RuleDto first = RuleDto.builder().id(1L).ruleString("first")
                .conditions(List.of()).command(RuleDto.Command.builder().action("off").build()).build();
        RuleDto second = RuleDto.builder().id(2L).ruleString("second")
                .conditions(List.of()).command(RuleDto.Command.builder().action("on").build()).build();

        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L))
                .thenReturn(List.of(firstPo, secondPo));
        when(ruleMapper.toDto(firstPo)).thenReturn(first);
        when(ruleMapper.toDto(secondPo)).thenReturn(second);

        // Reorder is reversible, so it returns the same mutation envelope as the other
        // reversible edits rather than a bare list.
        List<RuleDto> saved = service.reorderRules(
                1L, List.of(1L, 2L), List.of(2L, 1L)).getCurrentItems();

        assertEquals(List.of(2L, 1L), saved.stream().map(RuleDto::getId).toList());
        ArgumentCaptor<RulePo> captor = ArgumentCaptor.forClass(RulePo.class);
        verify(ruleRepo, times(2)).save(captor.capture());
        assertEquals(List.of(0, 1), captor.getAllValues().stream()
                .map(RulePo::getExecutionOrder).toList());
        assertEquals(List.of(2L, 1L), captor.getAllValues().stream()
                .map(RulePo::getId).toList());
        verify(ruleRepo).findByUserIdOrderByExecutionOrderAscIdAsc(1L);
        verify(ruleMapper, never()).toEntity(any(), anyLong());
        verifyNoInteractions(nodeRepo, specRepo, deviceTemplateRepo);
    }

    @Test
    void reorderRules_preservesExistingPayloadsAndSkipsUnchangedPositions() {
        var createdAt = java.time.LocalDateTime.of(2026, 10, 8, 8, 0);
        RulePo first = RulePo.builder().id(1L).userId(1L).executionOrder(0)
                .conditionsJson("[]").commandJson("{\"action\":\"on\"}")
                .ruleString("first saved preview").createdAt(createdAt).build();
        RulePo middle = RulePo.builder().id(2L).userId(1L).executionOrder(1)
                .conditionsJson("[]").commandJson("{\"action\":\"off\"}")
                .ruleString("middle saved preview").createdAt(createdAt).build();
        RulePo last = RulePo.builder().id(3L).userId(1L).executionOrder(2)
                .conditionsJson("[]").commandJson("{\"action\":\"toggle\"}")
                .ruleString("last saved preview").createdAt(createdAt).build();
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(first, middle, last));
        when(ruleMapper.toDto(any())).thenAnswer(inv -> {
            RulePo row = inv.getArgument(0);
            return RuleDto.builder().id(row.getId()).ruleString(row.getRuleString()).build();
        });

        var result = service.reorderRules(1L, List.of(1L, 2L, 3L), List.of(3L, 2L, 1L));

        assertEquals(List.of(3L, 2L, 1L), result.getCurrentItems().stream().map(RuleDto::getId).toList());
        verify(ruleRepo).save(same(first));
        verify(ruleRepo).save(same(last));
        verify(ruleRepo, never()).save(same(middle));
        assertEquals("[]", first.getConditionsJson());
        assertEquals("{\"action\":\"on\"}", first.getCommandJson());
        assertEquals("first saved preview", first.getRuleString());
        assertEquals(createdAt, first.getCreatedAt());
        assertEquals(2, first.getExecutionOrder());
        assertEquals(0, last.getExecutionOrder());
        verify(ruleMapper, never()).toEntity(any(), anyLong());
        verifyNoInteractions(nodeRepo, specRepo, deviceTemplateRepo);
    }

    @Test
    void reorderRules_rejectsWhenAnotherWriterAlreadyChangedTheOrder() {
        RulePo firstPo = RulePo.builder().id(1L).userId(1L).executionOrder(1).build();
        RulePo secondPo = RulePo.builder().id(2L).userId(1L).executionOrder(0).build();
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L))
                .thenReturn(List.of(secondPo, firstPo));
        when(ruleMapper.toDto(firstPo)).thenReturn(RuleDto.builder().id(1L).build());
        when(ruleMapper.toDto(secondPo)).thenReturn(RuleDto.builder().id(2L).build());

        ConflictException failure = assertThrows(ConflictException.class, () ->
                service.reorderRules(1L, List.of(1L, 2L), List.of(2L, 1L)));

        assertTrue(failure.getMessage().contains("order changed"));
        verify(ruleRepo, never()).save(any());
    }

    @Test
    void addRule_whenIdenticalRuleAlreadyExists_rejectsInsideWriteLockBeforeSave() {
        RuleDto existing = RuleDto.builder()
                .id(9L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor1")
                        .attribute("temperature")
                        .targetType("variable")
                        .relation(">=")
                        .value("30")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("ac1")
                        .action("cool")
                        .build())
                .build();
        RuleDto duplicate = RuleDto.builder()
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor1")
                        .attribute("temperature")
                        .targetType("VARIABLE")
                        .relation("GTE")
                        .value("30")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("ac1")
                        .action("cool")
                        .build())
                .build();
        RulePo existingPo = new RulePo();

        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(existingPo));
        when(ruleMapper.toDto(existingPo)).thenReturn(existing);
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());

        ConflictException error = assertThrows(ConflictException.class,
                () -> service.addRule(1L, duplicate));

        assertTrue(error.getMessage().contains("identical automation rules"));
        assertTrue(error.getMessage().contains("temperature >= 30"));
        verify(ruleRepo, never()).save(any());
    }

    @Test
    void addRule_whenTheDuplicateCarriesContent_namesItSoTheUserCanTellTheRulesApart() {
        /*
         * The rejection message must render `content`, because the signature compares it.
         *
         * `RuleSemanticSignature` includes `contentDevice` and `content` in a rule's identity, but this message
         * used to describe only `device.action`. A board holding two rules that differ *only* in their content
         * therefore got a description matching both, with no way to tell which one the new rule collided with —
         * a correct rejection explained ambiguously.
         */
        RuleDto.Condition trigger = RuleDto.Condition.builder()
                .deviceName("sensor1")
                .attribute("motion")
                .targetType("variable")
                .relation("=")
                .value("active")
                .build();
        RuleDto.Command sendSnapshot = RuleDto.Command.builder()
                .deviceName("phone1")
                .action("send")
                .contentDevice("camera1")
                .content("snapshot")
                .build();

        RuleDto existing = RuleDto.builder().id(9L)
                .conditions(List.of(trigger)).command(sendSnapshot).build();
        RuleDto duplicate = RuleDto.builder()
                .conditions(List.of(trigger)).command(sendSnapshot).build();

        RulePo existingPo = new RulePo();
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(existingPo));
        when(ruleMapper.toDto(existingPo)).thenReturn(existing);
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());

        ConflictException error = assertThrows(ConflictException.class,
                () -> service.addRule(1L, duplicate));

        assertTrue(error.getMessage().contains("identical automation rules"));
        assertTrue(error.getMessage().contains("using camera1.snapshot"),
                "the message should name the content that is part of the rule's identity: " + error.getMessage());
        verify(ruleRepo, never()).save(any());
    }

    @Test
    void addSpec_whenSemanticInputsAreIdentical_rejectsBeforeReplacingCollection() {
        SpecConditionDto firstCondition = new SpecConditionDto();
        firstCondition.setId("old-1");
        firstCondition.setSide("a");
        firstCondition.setDeviceId("sensor1");
        firstCondition.setDeviceLabel("Old label");
        firstCondition.setTargetType("variable");
        firstCondition.setKey("temperature");
        // Must match the candidate's reading, differently spelled: two conditions are the same
        // specification only if they ask the same question, and canonicalization is what makes the
        // spellings comparable.
        firstCondition.setVariableSource("reported");
        firstCondition.setRelation(">=");
        firstCondition.setValue("30");

        SpecConditionDto secondCondition = new SpecConditionDto();
        secondCondition.setId("old-2");
        secondCondition.setSide("a");
        secondCondition.setDeviceId("sensor1");
        secondCondition.setDeviceLabel("Old label");
        secondCondition.setTargetType("mode");
        secondCondition.setKey("Mode");
        secondCondition.setRelation("in");
        secondCondition.setValue("away, home");

        SpecificationDto existing = new SpecificationDto();
        existing.setId("spec-existing");
        existing.setName("Safety requirement");
        existing.setTemplateId("1");
        existing.setTemplateLabel("Always");
        existing.setAConditions(List.of(firstCondition, secondCondition));
        existing.setFormula("display cache one");

        SpecConditionDto duplicateSecond = new SpecConditionDto();
        duplicateSecond.setId("new-2");
        duplicateSecond.setSide("a");
        duplicateSecond.setDeviceId("sensor1");
        duplicateSecond.setDeviceLabel("New label");
        duplicateSecond.setTargetType("mode");
        duplicateSecond.setKey("Mode");
        duplicateSecond.setRelation("IN");
        duplicateSecond.setValue("home|away");

        SpecConditionDto duplicateFirst = new SpecConditionDto();
        duplicateFirst.setId("new-1");
        duplicateFirst.setSide("a");
        duplicateFirst.setDeviceId("sensor1");
        duplicateFirst.setDeviceLabel("New label");
        duplicateFirst.setTargetType("VARIABLE");
        duplicateFirst.setKey("temperature");
        // Mixed case on both fields on purpose: this test is about duplicate detection, so the condition
        // must survive authoring validation to reach it, and the reading must normalize the same way the
        // target type does.
        duplicateFirst.setVariableSource("Reported");
        duplicateFirst.setRelation("GTE");
        duplicateFirst.setValue("30");

        SpecificationDto duplicate = new SpecificationDto();
        duplicate.setId("spec-new");
        duplicate.setName("Safety requirement");
        duplicate.setTemplateId("1");
        duplicate.setTemplateLabel("Different display label");
        duplicate.setAConditions(List.of(duplicateSecond, duplicateFirst));
        duplicate.setFormula("different preview cache");

        SpecificationPo existingPo = new SpecificationPo();
        DeviceNodePo nodePo = new DeviceNodePo();
        DeviceNodeDto node = boardNode("sensor1", null, "Living Sensor");

        when(specRepo.findByUserId(1L)).thenReturn(List.of(existingPo));
        when(specificationMapper.toDto(existingPo)).thenReturn(existing);
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(nodePo));
        when(deviceNodeMapper.toDto(nodePo)).thenReturn(node);

        ConflictException error = assertThrows(ConflictException.class,
                () -> service.addSpec(1L, duplicate));

        assertTrue(error.getMessage().contains("identical specifications"));
        assertTrue(error.getMessage().contains("Living Sensor.temperature >= 30"));
        verify(specRepo, never()).deleteAll(any());
        verify(specRepo, never()).saveAll(any());
    }

    @Test
    void deleteNodeCascade_whenConfirmedDependenciesDrift_rejectsBeforeAnyWrite() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor1");
        DeviceNodePo nodePo = new DeviceNodePo();

        RuleDto relatedRule = RuleDto.builder()
                .id(17L)
                .conditions(List.of(RuleDto.Condition.builder()
                        .deviceName("sensor1")
                        .attribute("temperature")
                        .relation(">")
                        .value("30")
                        .build()))
                .command(RuleDto.Command.builder()
                        .deviceName("sensor1")
                        .action("notify")
                        .build())
                .build();
        RulePo rulePo = new RulePo();

        SpecConditionDto specCondition = new SpecConditionDto();
        specCondition.setDeviceId("sensor1");
        SpecificationDto relatedSpec = new SpecificationDto();
        relatedSpec.setId("spec-1");
        relatedSpec.setAConditions(List.of(specCondition));
        SpecificationPo specPo = new SpecificationPo();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(nodePo));
        when(deviceNodeMapper.toDto(nodePo)).thenReturn(node);
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(rulePo));
        when(ruleMapper.toDto(rulePo)).thenReturn(relatedRule);
        when(specRepo.findByUserId(1L)).thenReturn(List.of(specPo));
        when(specificationMapper.toDto(specPo)).thenReturn(relatedSpec);

        assertThrows(ConflictException.class,
                () -> service.deleteNodeCascade(1L, "sensor1", "stale-token"));

        verify(nodeRepo, never()).deleteAll(any());
        verify(ruleRepo, never()).deleteById(anyLong());
        verify(ruleRepo, never()).save(any());
        verify(specRepo, never()).deleteAll(any());
        verify(specRepo, never()).saveAll(any());
    }

    @Test
    void deleteNodeCascade_acceptsTheExactOpaquePreviewToken() {
        DeviceNodeDto node = boardNode("sensor1", "Sensor", "Hall sensor");
        DeviceNodePo nodePo = new DeviceNodePo();
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(nodePo));
        when(deviceNodeMapper.toDto(nodePo)).thenReturn(node);
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(ruleRepo.findByUserId(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());
        when(nodeRepo.saveAll(any())).thenReturn(List.of());
        when(specRepo.saveAll(any())).thenReturn(List.of());

        var preview = service.previewNodeDeletion(1L, "sensor1");
        var result = service.deleteNodeCascade(1L, "sensor1", preview.getImpactToken());

        assertEquals("deleted", result.getOperation());
        assertEquals("sensor1", result.getDeletedDevice().getId());
        assertEquals(preview.getImpactToken(), result.getImpactToken());
        verify(nodeRepo).deleteAll(List.of(nodePo));
        verify(specRepo, never()).deleteAll(any());
        // The complete cascade is one compound journal entry; individual rule/spec entries remain
        // behind it and cannot be reached until the device deletion itself has been undone.
        verify(editJournal).record(eq(1L), eq(BoardEditEntityType.DEVICE),
                eq(BoardEditOperation.DELETE), eq("sensor1"), any(), any());
    }

    @Test
    void createNode_returnsEnvironmentPoolChangesFromTheSameTransaction() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceTemplateDto.DeviceManifest.InternalVariable temperature =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .falsifiableWhenCompromised(true)
                        .lowerBound(0)
                        .upperBound(100)
                        .trust("untrusted")
                        .privacy("public")
                        .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Temperature Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Temperature Sensor")
                        .internalVariables(List.of(temperature))
                        .build()))
                .build();
        DeviceNodeDto draft = boardNode("sensor1", "Temperature Sensor", "Hall sensor");
        DeviceNodePo savedNode = new DeviceNodePo();
        BoardEnvironmentVariablePo savedEnvironment = BoardEnvironmentVariablePo.builder()
                .userId(1L)
                .name("temperature")
                .value("0")
                .trust("untrusted")
                .privacy("public")
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());
        when(deviceNodeMapper.toEntity(draft, 1L)).thenReturn(savedNode);
        when(nodeRepo.saveAll(any())).thenReturn(List.of(savedNode));
        when(deviceNodeMapper.toDto(savedNode)).thenReturn(draft);
        when(environmentRepo.findByUserIdOrderByNameAsc(1L))
                .thenReturn(List.of(), List.of(), List.of(), List.of(savedEnvironment));
        when(environmentRepo.saveAll(any())).thenReturn(List.of(savedEnvironment));

        var result = serviceWithEnvironment.createNode(1L, current -> {
            assertTrue(current.isEmpty());
            return draft;
        });

        assertEquals(List.of(new BoardEnvironmentVariableDto(
                "temperature", "0", "untrusted", "public")), result.getEnvironmentVariables());
        assertEquals(1, result.getEnvironmentChanges().size());
        assertEquals(EnvironmentVariableChangeDto.ChangeType.ADDED,
                result.getEnvironmentChanges().get(0).getChangeType());
        assertEquals("temperature", result.getEnvironmentChanges().get(0).getName());
        assertEquals("0", result.getEnvironmentChanges().get(0).getCurrentValue().getValue());
        verify(transactionTemplate).execute(any());
    }

    @Test
    void deletionPreviewIncludesEnvironmentRemovalAndConfirmationRejectsEnvironmentDrift() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceNodeDto node = boardNode("sensor1", "Temperature Sensor", "Hall sensor");
        DeviceNodePo nodePo = new DeviceNodePo();
        DeviceTemplateDto.DeviceManifest.InternalVariable temperature =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .falsifiableWhenCompromised(true)
                        .lowerBound(0)
                        .upperBound(100)
                        .naturalChangeRate("[-1,1]")
                        .trust("untrusted")
                        .privacy("public")
                        .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Temperature Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Temperature Sensor")
                        .internalVariables(List.of(temperature))
                        .build()))
                .build();
        BoardEnvironmentVariablePo environment = BoardEnvironmentVariablePo.builder()
                .userId(1L)
                .name("temperature")
                .value("20")
                .trust("untrusted")
                .privacy("public")
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(nodePo));
        when(deviceNodeMapper.toDto(nodePo)).thenReturn(node);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of(environment));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        var preview = serviceWithEnvironment.previewNodeDeletion(1L, "sensor1");

        assertEquals(1, preview.getEnvironmentChanges().size());
        assertEquals(EnvironmentVariableChangeDto.ChangeType.REMOVED,
                preview.getEnvironmentChanges().get(0).getChangeType());
        assertEquals("temperature", preview.getEnvironmentChanges().get(0).getName());
        assertEquals("20", preview.getEnvironmentChanges().get(0).getPreviousValue().getValue());

        assertThrows(ConflictException.class, () -> serviceWithEnvironment.deleteNodeCascade(
                1L, "sensor1", "stale-token"));
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void deletionPreviewKeepsEnvironmentRequiredByAnotherDevice() {
        BoardStorageServiceImpl serviceWithEnvironment = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceNodeDto target = boardNode("sensor1", "Temperature Sensor", "Hall sensor");
        DeviceNodeDto remaining = boardNode("sensor2", "Temperature Sensor", "Bedroom sensor");
        DeviceNodePo targetPo = new DeviceNodePo();
        targetPo.setId("sensor1");
        targetPo.setUserId(1L);
        DeviceNodePo remainingPo = new DeviceNodePo();
        remainingPo.setId("sensor2");
        remainingPo.setUserId(1L);
        DeviceTemplateDto.DeviceManifest.InternalVariable temperature =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature")
                        .isInside(false)
                        .lowerBound(0)
                        .upperBound(100)
                        .naturalChangeRate("[-1,1]")
                        .trust("untrusted")
                        .privacy("public")
                        .build();
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .userId(1L)
                .name("Temperature Sensor")
                .manifestJson(JsonUtils.toJson(DeviceTemplateDto.DeviceManifest.builder()
                        .name("Temperature Sensor")
                        .internalVariables(List.of(temperature))
                        .build()))
                .build();
        BoardEnvironmentVariablePo environment = BoardEnvironmentVariablePo.builder()
                .userId(1L)
                .name("temperature")
                .value("20")
                .trust("untrusted")
                .privacy("public")
                .build();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(targetPo, remainingPo));
        when(deviceNodeMapper.toDto(targetPo)).thenReturn(target);
        when(deviceNodeMapper.toDto(remainingPo)).thenReturn(remaining);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of(environment));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        var preview = serviceWithEnvironment.previewNodeDeletion(1L, "sensor1");

        assertTrue(preview.getEnvironmentChanges().isEmpty());
        assertEquals(List.of(new BoardEnvironmentVariableDto(
                "temperature", "20", "untrusted", "public")), preview.getEnvironmentVariables());
    }

    private DeviceTemplateDto.DeviceManifest.WorkingState workingState(String name) {
        return DeviceTemplateDto.DeviceManifest.WorkingState.builder()
                .name(name)
                .trust("trusted")
                .privacy("public")
                .dynamics(List.of())
                .build();
    }

    @Test
    void persistTimeValidationRefusesAnAffectOnlySharedValueAsAConditionSource() {
        DeviceTemplateDto.DeviceManifest.InternalVariable affectOnly =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("illuminance").isInside(false).reads(false)
                        .falsifiableWhenCompromised(false)
                        .lowerBound(0).upperBound(100).naturalChangeRate("0")
                        .trust("trusted").privacy("public").build();
        DeviceTemplateDto.DeviceManifest.InternalVariable readable =
                DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false).reads(true)
                        .falsifiableWhenCompromised(true)
                        .lowerBound(15).upperBound(35).naturalChangeRate("0")
                        .trust("trusted").privacy("public").build();
        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .name("Light")
                .internalVariables(List.of(affectOnly, readable))
                .impactedVariables(List.of("illuminance"))
                .build();

        // The capability-blind existence lookup still finds it, and must: its other callers ask whether
        // a declaration exists at all (domain resolution, runtime overrides), where affect-only is a
        // legitimate answer.
        assertNotNull(ReflectionTestUtils.invokeMethod(
                service, "internalVariable", manifest, "illuminance"));

        // The condition-source resolver withholds it. The generator emits no read mirror for an
        // affect-only value, so a stored condition on it would name something the device never
        // observes. This is the persist-time gate every writer shares -- the REST board endpoints, the
        // assistant's rule and specification tools, and scene import alike.
        assertNull(ReflectionTestUtils.invokeMethod(
                service, "conditionSourceVariable", manifest, "illuminance"));
        assertNotNull(ReflectionTestUtils.invokeMethod(
                service, "conditionSourceVariable", manifest, "temperature"));
    }

    @Test
    void deletionPreviewIncludesSpecificationAnchoredToDeviceReadingEnvironmentValue() {
        /*
         * An `environment` spec whose anchor device is deleted is correctly removed — the device supplies
         * the declaration validating the value, making deletion load-bearing — but defect 13 found this
         * was undisclosed when no verdict row was selected. This test pins that the preview populates
         * `removedSpecifications` for both cases.
         */
        BoardStorageServiceImpl service = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, null, deviceTemplateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                null, new DeviceTemplateMapper(), null, userRepository, editJournal);

        DeviceNodeDto device = boardNode("sensor1", "Temperature Sensor", "Hall");
        DeviceNodePo devicePo = new DeviceNodePo();
        devicePo.setId("sensor1");

        DeviceTemplateDto.DeviceManifest manifest = DeviceTemplateDto.DeviceManifest.builder()
                .internalVariables(List.of(
                        DeviceTemplateDto.DeviceManifest.InternalVariable.builder()
                                .name("temperature")
                                .isInside(false)
                                .reads(true)
                                .trust("trusted")
                                .privacy("public")
                                .values(List.of("10", "20", "30"))
                                .build()))
                .build();
        DeviceTemplateDto template = new DeviceTemplateDto();
        template.setName("Temperature Sensor");
        template.setManifest(manifest);

        SpecificationDto spec = new SpecificationDto();
        spec.setId("spec1");
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setTemplateLabel("Always");
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("sensor1");
        condition.setTargetType("variable");
        condition.setKey("temperature");
        condition.setVariableSource("environment");
        condition.setRelation("=");
        condition.setValue("20");
        spec.setAConditions(List.of(condition));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());

        DeviceTemplatePo templatePo = new DeviceTemplatePo();
        SpecificationPo specPo = new SpecificationPo();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(devicePo));
        when(deviceNodeMapper.toDto(devicePo)).thenReturn(device);
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(templatePo));
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of());
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of(specPo));
        when(specificationMapper.toDto(specPo)).thenReturn(spec);

        var preview = service.previewNodeDeletion(1L, "sensor1");

        assertEquals(1, preview.getRemovedSpecifications().size(),
                "environment spec anchored to deleted device must appear in preview");
        assertEquals("spec1", preview.getRemovedSpecifications().get(0).getId());
    }
}
