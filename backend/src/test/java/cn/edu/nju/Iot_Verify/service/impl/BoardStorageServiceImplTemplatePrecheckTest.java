package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetChangeDto;
import cn.edu.nju.Iot_Verify.dto.device.DefaultTemplateResetResultDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.PrivacyStateDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.exception.TemplateDeletionConflictException;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.BoardLayoutRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceNodeRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.repository.RuleRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateNuSmvValidator;
import cn.edu.nju.Iot_Verify.service.DeviceTemplateService;
import cn.edu.nju.Iot_Verify.service.board.BoardEditHistoryState;
import cn.edu.nju.Iot_Verify.service.board.BoardEditJournal;
import cn.edu.nju.Iot_Verify.service.board.BoardUndoAvailability;
import cn.edu.nju.Iot_Verify.util.mapper.BoardLayoutMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.lang.NonNull;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardStorageServiceImplTemplatePrecheckTest {

    @Mock
    private DeviceNodeRepository nodeRepo;
    @Mock
    private SpecificationRepository specRepo;
    @Mock
    private RuleRepository ruleRepo;
    @Mock
    private BoardLayoutRepository layoutRepo;
    @Mock
    private DeviceTemplateRepository deviceTemplateRepo;
    @Mock
    private DeviceTemplateService deviceTemplateService;
    @Mock
    private SmvGenerator smvGenerator;
    @Mock
    private SpecificationMapper specificationMapper;
    @Mock
    private RuleMapper ruleMapper;
    @Mock
    private DeviceNodeMapper deviceNodeMapper;
    @Mock
    private BoardLayoutMapper boardLayoutMapper;
    @Mock
    private DeviceTemplateMapper deviceTemplateMapper;
    @Mock
    private DeviceTemplateSchemaValidator deviceTemplateSchemaValidator;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BoardEditJournal editJournal;

    private BoardStorageServiceImpl service;

    @BeforeEach
    void setUp() {
        // Use a real DeviceTemplateMapper so toDto() works in addDeviceTemplate tests
        deviceTemplateMapper = new DeviceTemplateMapper();
        deviceNodeMapper = new DeviceNodeMapper();
        // The NuSMV template validation moved to its own component; use a real one over the
        // mocked generator so these tests still exercise validation through the service API.
        DeviceTemplateNuSmvValidator templateNuSmvValidator =
                new DeviceTemplateNuSmvValidator(smvGenerator);
        service = new BoardStorageServiceImpl(
                nodeRepo,
                null,
                specRepo,
                ruleRepo,
                layoutRepo,
                deviceTemplateRepo,
                deviceTemplateService,
                transactionTemplate,
                templateNuSmvValidator,
                specificationMapper,
                ruleMapper,
                deviceNodeMapper,
                boardLayoutMapper,
                deviceTemplateMapper,
                deviceTemplateSchemaValidator,
                userRepository,
                editJournal
        );
        lenient().when(userRepository.findByIdForUpdate(anyLong())).thenReturn(java.util.Optional.of(new UserPo()));
        lenient().when(userRepository.findById(anyLong())).thenReturn(java.util.Optional.of(new UserPo()));
        lenient().when(deviceTemplateSchemaValidator.toCanonicalJson(any(DeviceManifest.class)))
                .thenAnswer(inv -> JsonUtils.toJson(inv.getArgument(0)));
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        lenient().when(editJournal.historyState(anyLong()))
                .thenReturn(historyState(0, "0"));
    }

    @Test
    void getDeviceTemplates_whenEmpty_isSideEffectFree() {
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of());

        List<DeviceTemplateDto> result = service.getDeviceTemplates(1L);

        assertEquals(List.of(), result);
        verify(deviceTemplateService, never()).initDefaultTemplates(1L);
    }

    @Test
    void previewDefaultTemplateReset_returnsExactImpactWithoutWriting() {
        DeviceTemplatePo bundled = templatePo("Sensor", """
                {"Name":"Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[],"ImpactedVariables":[],
                 "Transitions":[],"APIs":[],"Contents":[]}
                """);
        bundled.setId(null);
        bundled.setDefaultTemplate(true);
        DeviceTemplatePo current = templatePo("Sensor", bundled.getManifestJson());
        current.setDefaultTemplate(true);

        when(deviceTemplateService.getDefaultTemplateDefinitions(1L)).thenReturn(List.of(bundled));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(current));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        DefaultTemplateResetResultDto preview = service.previewDefaultTemplateReset(1L);

        assertEquals("preview", preview.getOperation());
        assertFalse(preview.getImpactToken().isBlank());
        assertEquals(true, preview.isCanApply());
        assertEquals(DefaultTemplateResetChangeDto.ChangeType.REFRESH_DEFAULT,
                preview.getTemplateChanges().get(0).getChangeType());
        assertFalse(preview.getTemplateChanges().get(0).isSemanticsChanged());
        assertEquals(0, preview.getEditHistoryEntryCount());
        verify(deviceTemplateRepo, never()).deleteDefaultsForReset(anyLong(), anyList());
        verify(deviceTemplateRepo, never()).saveAllAndFlush(anyList());
    }

    @Test
    void resetDefaultTemplates_rejectsWhenOnlyUndoHistoryChanged() {
        DeviceTemplatePo bundled = templatePo("Sensor", """
                {"Name":"Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[],"ImpactedVariables":[],
                 "Transitions":[],"APIs":[],"Contents":[]}
                """);
        bundled.setId(null);
        bundled.setDefaultTemplate(true);
        DeviceTemplatePo current = templatePo("Sensor", bundled.getManifestJson());
        current.setDefaultTemplate(true);
        when(deviceTemplateService.getDefaultTemplateDefinitions(1L)).thenReturn(List.of(bundled));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(current));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());
        when(editJournal.historyState(1L))
                .thenReturn(historyState(1, "a"), historyState(2, "b"));

        DefaultTemplateResetResultDto preview = service.previewDefaultTemplateReset(1L);
        ConflictException error = assertThrows(ConflictException.class,
                () -> service.resetDefaultTemplates(1L, preview.getImpactToken()));

        assertEquals(1, preview.getEditHistoryEntryCount());
        org.assertj.core.api.Assertions.assertThat(error.getMessage()).contains("undo history changed");
        verify(deviceTemplateRepo, never()).deleteDefaultsForReset(anyLong(), anyList());
        verify(editJournal, never()).clear(anyLong());
    }

    @Test
    void resetDefaultTemplates_clearsThePreviewedUndoHistoryAfterCommit() {
        DeviceTemplatePo bundled = templatePo("Sensor", """
                {"Name":"Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[],"ImpactedVariables":[],
                 "Transitions":[],"APIs":[],"Contents":[]}
                """);
        bundled.setId(null);
        bundled.setDefaultTemplate(true);
        DeviceTemplatePo current = templatePo("Sensor", bundled.getManifestJson());
        current.setDefaultTemplate(true);
        when(deviceTemplateService.getDefaultTemplateDefinitions(1L)).thenReturn(List.of(bundled));
        when(deviceTemplateRepo.findByUserId(1L))
                .thenReturn(List.of(current), List.of(current), List.of(bundled));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());
        when(deviceTemplateRepo.deleteDefaultsForReset(anyLong(), anyList())).thenReturn(1);
        when(deviceTemplateRepo.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(editJournal.historyState(1L)).thenReturn(historyState(3, "c"));

        DefaultTemplateResetResultDto preview = service.previewDefaultTemplateReset(1L);
        DefaultTemplateResetResultDto result = service.resetDefaultTemplates(1L, preview.getImpactToken());

        assertEquals("reset", result.getOperation());
        assertEquals(3, result.getEditHistoryEntryCount());
        verify(editJournal).clear(1L);
    }

    @Test
    void previewDefaultTemplateReset_marksBundledEnvironmentAdditionsForSafeLocalization() {
        String manifestJson = """
                {"Name":"Weather Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[{"Name":"weather","IsInside":false,"Reads":true,
                 "Values":["sunny","rainy"],"Trust":"trusted","Privacy":"public"}],
                 "ImpactedVariables":[],"Transitions":[],"APIs":[],"Contents":[]}
                """;
        DeviceTemplatePo bundled = templatePo("Weather Sensor", manifestJson);
        bundled.setId(null);
        bundled.setDefaultTemplate(true);
        DeviceTemplatePo current = templatePo("Weather Sensor", manifestJson);
        current.setDefaultTemplate(true);
        DeviceNodeDto node = buildNode("weather_1", "Weather Sensor");
        node.setState("Working");

        when(deviceTemplateService.getDefaultTemplateDefinitions(1L)).thenReturn(List.of(bundled));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(current));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(deviceNodeMapper.toEntity(node, 1L)));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        DefaultTemplateResetResultDto preview = service.previewDefaultTemplateReset(1L);

        assertEquals(1, preview.getEnvironmentChanges().size());
        assertEquals("weather", preview.getEnvironmentChanges().get(0).getName());
        assertEquals(ModelTokenSource.UNKNOWN,
                preview.getEnvironmentChanges().get(0).getPreviousModelTokenSource());
        assertEquals(ModelTokenSource.BUNDLED,
                preview.getEnvironmentChanges().get(0).getCurrentModelTokenSource());
    }

    @Test
    void environmentModelTokenSources_requiresEveryProviderToBeBundled() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setInternalVariables(List.of(DeviceManifest.InternalVariable.builder()
                .name("weather")
                .isInside(false).reads(true)
                .values(List.of("sunny", "rainy"))
                .build()));

        DeviceTemplateDto bundled = new DeviceTemplateDto();
        bundled.setName("Bundled Weather");
        bundled.setManifest(manifest);
        bundled.setDefaultTemplate(true);
        DeviceTemplateDto custom = new DeviceTemplateDto();
        custom.setName("Custom Weather");
        custom.setManifest(manifest);
        custom.setDefaultTemplate(false);

        DeviceNodeDto bundledNode = buildNode("bundled_1", bundled.getName());
        DeviceNodeDto customNode = buildNode("custom_1", custom.getName());

        assertEquals(ModelTokenSource.BUNDLED,
                service.environmentModelTokenSources(
                        List.of(bundledNode), List.of(bundled, custom)).get("weather"));
        assertEquals(ModelTokenSource.CUSTOM,
                service.environmentModelTokenSources(
                        List.of(bundledNode, customNode), List.of(bundled, custom)).get("weather"));
    }

    @Test
    void previewDefaultTemplateReset_reportsObsoleteTypeAsBlockingUsedDevice() {
        DeviceTemplatePo bundled = templatePo("Sensor", """
                {"Name":"Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[],"ImpactedVariables":[],
                 "Transitions":[],"APIs":[],"Contents":[]}
                """);
        bundled.setId(null);
        bundled.setDefaultTemplate(true);
        DeviceTemplatePo obsolete = templatePo("Legacy Sensor", """
                {"Name":"Legacy Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[],"ImpactedVariables":[],
                 "Transitions":[],"APIs":[],"Contents":[]}
                """);
        obsolete.setDefaultTemplate(true);
        DeviceNodeDto node = buildNode("legacy_1", "Legacy Sensor");
        node.setState(null);

        when(deviceTemplateService.getDefaultTemplateDefinitions(1L)).thenReturn(List.of(bundled));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(obsolete));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(deviceNodeMapper.toEntity(node, 1L)));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of());
        when(specRepo.findByUserId(1L)).thenReturn(List.of());

        DefaultTemplateResetResultDto preview = service.previewDefaultTemplateReset(1L);

        assertFalse(preview.isCanApply());
        assertEquals("legacy_1", preview.getAffectedDevices().get(0).getDeviceLabel());
        org.assertj.core.api.Assertions.assertThat(preview.getBlockers())
                .anySatisfy(blocker -> {
                    org.assertj.core.api.Assertions.assertThat(blocker.getItemLabel()).contains("legacy_1");
                    org.assertj.core.api.Assertions.assertThat(blocker.getReasonCode())
                            .isEqualTo("DEVICE_INSTANCE_INCOMPATIBLE");
                    org.assertj.core.api.Assertions.assertThat(blocker.getReason()).contains("Unknown device template");
                });
    }

    @Test
    void saveNodes_whenTemplateMissing_shouldRejectBeforeReplacingNodes() {
        DeviceNodeDto node = buildNode("lamp1", "Missing Template");

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        assertEquals(422, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].templateName", "Unknown device template: Missing Template");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenTemplateNameStartsWithVariablePrefix_shouldTreatAsNormalDevice() {
        DeviceTemplatePo template = templatePo("variable_power", """
                {"Name":"variable_power","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[{"Name":"reading","LowerBound":0,"UpperBound":100,"IsInside":true}]}
                """);
        DeviceNodeDto node = buildNode("lamp1_power", "variable_power");
        node.setState(null);

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(nodeRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<DeviceNodeDto> saved = service.saveNodes(1L, List.of(node));

        assertEquals(1, saved.size());
        assertEquals("variable_power", saved.get(0).getTemplateName());
    }

    @Test
    void saveNodes_whenTemplateNameCaseDiffers_shouldPersistCanonicalTemplateName() {
        DeviceTemplatePo template = templatePo("Window Shade", """
                {"Name":"Window Shade","Modes":["ShadeMode"],"InitState":"open",
                 "WorkingStates":[{"Name":"open"},{"Name":"closed"}],"InternalVariables":[]}
                """);
        DeviceNodeDto node = buildNode("shade1", "window shade");
        node.setState("open");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(nodeRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<DeviceNodeDto> saved = service.saveNodes(1L, List.of(node));

        assertEquals(1, saved.size());
        assertEquals("Window Shade", saved.get(0).getTemplateName());
    }

    @Test
    void saveNodes_whenRuntimeStateIsIllegal_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Light", "{\"Name\":\"Light\",\"Modes\":[\"SwitchState\"],\"InitState\":\"Off\",\"WorkingStates\":[{\"Name\":\"Off\"},{\"Name\":\"On\"}],\"InternalVariables\":[]}");
        DeviceNodeDto node = buildNode("lamp1", "Light");
        node.setState("broken");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].state", "Illegal state value for device template: broken");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenModeDeviceStateIsBlank_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Light", "{\"Name\":\"Light\",\"Modes\":[\"SwitchState\"],\"InitState\":\"Off\",\"WorkingStates\":[{\"Name\":\"Off\"},{\"Name\":\"On\"}],\"InternalVariables\":[]}");
        DeviceNodeDto node = buildNode("lamp1", "Light");
        node.setState("");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].state", "State is required for device templates with modes");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenRuntimeVariableIsIllegal_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Thermostat", """
                {"Name":"Thermostat","Modes":["ThermostatMode"],"InitState":"auto",
                 "WorkingStates":[{"Name":"auto"},{"Name":"cool"}],
                 "InternalVariables":[{"Name":"temperature","IsInside":true,"LowerBound":0,"UpperBound":50}]}
                """);
        DeviceNodeDto node = buildNode("thermostat1", "Thermostat");
        node.setState("auto");
        node.setVariables(List.of(new VariableStateDto("temperature", "80", "trusted")));

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].variables[0].value",
                        "Variable value out of range for 'temperature': 80 (allowed 0..50)");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenRuntimeVariableNamesMode_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Light", """
                {"Name":"Light","Modes":["SwitchState"],"InitState":"Off",
                 "WorkingStates":[{"Name":"Off"},{"Name":"On"}],
                 "InternalVariables":[]}
                """);
        DeviceNodeDto node = buildNode("lamp1", "Light");
        node.setState("Off");
        node.setVariables(List.of(new VariableStateDto("SwitchState", "On", "trusted")));

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].variables[0].name",
                        "Unknown runtime variable for device template: SwitchState");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenRuntimePrivacyIsIllegal_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Sensor", """
                {"Name":"Sensor","Modes":[],"InitState":"","WorkingStates":[],
                "InternalVariables":[{"Name":"motion","Values":["active","inactive"],"Privacy":"public"}]}
                """);
        DeviceNodeDto node = buildNode("sensor1", "Sensor");
        node.setState("Working");
        node.setPrivacies(List.of(new PrivacyStateDto("missing", "secret")));

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].privacies[0].privacy", "Value must be public or private: secret")
                .containsEntry("nodes[0].privacies[0].name", "Unknown device-local variable privacy target: missing");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenNoModeDeviceHasStateTrust_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Sensor", """
                {"Name":"Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[{"Name":"motion","Values":["active","inactive"],"Privacy":"public"}]}
                """);
        DeviceNodeDto node = buildNode("sensor1", "Sensor");
        node.setState("Working");
        node.setCurrentStateTrust("trusted");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].currentStateTrust",
                        "currentStateTrust is only valid for device templates with modes");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenNoModeDeviceHasNonPlaceholderState_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Sensor", """
                {"Name":"Sensor","Modes":[],"InitState":"","WorkingStates":[],
                 "InternalVariables":[{"Name":"motion","Values":["active","inactive"],"Privacy":"public"}]}
                """);
        DeviceNodeDto node = buildNode("sensor1", "Sensor");
        node.setState("off");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(node)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[0].state",
                        "No-mode device state must be omitted or the UI placeholder 'Working'");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void saveNodes_whenIdsCollideAfterNuSmvNormalization_shouldRejectBeforeReplacingNodes() {
        DeviceTemplatePo template = templatePo("Light", "{\"Name\":\"Light\",\"Modes\":[\"SwitchState\"],\"InitState\":\"Off\",\"WorkingStates\":[{\"Name\":\"Off\"},{\"Name\":\"On\"}],\"InternalVariables\":[]}");
        DeviceNodeDto first = buildNode("AC 1", "Light");
        first.setState("Off");
        DeviceNodeDto second = buildNode("ac_1", "Light");
        second.setState("Off");

        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(first, second)));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors())
                .containsEntry("nodes[1].id", "Device ID collides after NuSMV normalization: ac_1 -> ac_1");
        verify(nodeRepo, never()).deleteAll(any());
    }

    @Test
    void deleteDeviceTemplate_whenTemplateIsUsedByCanvasDevice_shouldReject() {
        DeviceTemplatePo template = DeviceTemplatePo.builder()
                .id(10L)
                .userId(1L)
                .name("Light")
                .manifestJson("{\"Name\":\"Light\"}")
                .defaultTemplate(false)
                .build();
        DeviceNodePo node = DeviceNodePo.builder()
                .id("living_light")
                .userId(1L)
                .templateName("Light")
                .label("living_light")
                .posX(0.0)
                .posY(0.0)
                .state("off")
                .width(110)
                .height(90)
                .build();

        when(deviceTemplateRepo.findByIdAndUserId(10L, 1L)).thenReturn(java.util.Optional.of(template));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(node));

        var preview = service.previewDeviceTemplateDeletion(1L, 10L);
        TemplateDeletionConflictException ex = assertThrows(TemplateDeletionConflictException.class, () ->
                service.deleteDeviceTemplate(1L, 10L, preview.getImpactToken()));

        assertEquals(409, ex.getCode());
        assertEquals("TEMPLATE_DELETION_BLOCKED", ex.getReasonCode());
        assertFalse(ex.getCurrentPreview().isCanDelete());
        assertEquals("living_light", ex.getCurrentPreview().getBlockers().get(0).getItemId());
        verify(deviceTemplateRepo, never()).delete(any());
    }

    @Test
    void deleteDeviceTemplate_clearsThePreviewedUndoHistoryAfterCommit() {
        DeviceTemplatePo template = templatePo("Light", "{\"Name\":\"Light\"}");
        template.setId(10L);
        template.setDefaultTemplate(false);
        when(deviceTemplateRepo.findByIdAndUserId(10L, 1L)).thenReturn(java.util.Optional.of(template));
        when(deviceTemplateRepo.findByUserId(1L))
                .thenReturn(List.of(template), List.of(template), List.of());
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(editJournal.historyState(1L)).thenReturn(historyState(4, "d"));

        var preview = service.previewDeviceTemplateDeletion(1L, 10L);
        var result = service.deleteDeviceTemplate(1L, 10L, preview.getImpactToken());

        assertEquals("deleted", result.getOperation());
        assertEquals(4, result.getEditHistoryEntryCount());
        verify(deviceTemplateRepo).delete(template);
        verify(editJournal).clear(1L);
    }

    @Test
    void deleteDeviceTemplate_rejectsWhenOnlyUndoHistoryChanged() {
        DeviceTemplatePo template = templatePo("Light", "{\"Name\":\"Light\"}");
        template.setId(10L);
        template.setDefaultTemplate(false);
        when(deviceTemplateRepo.findByIdAndUserId(10L, 1L)).thenReturn(java.util.Optional.of(template));
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of());
        when(editJournal.historyState(1L))
                .thenReturn(historyState(1, "a"), historyState(2, "b"));

        var preview = service.previewDeviceTemplateDeletion(1L, 10L);
        TemplateDeletionConflictException error = assertThrows(
                TemplateDeletionConflictException.class,
                () -> service.deleteDeviceTemplate(1L, 10L, preview.getImpactToken()));

        assertEquals("TEMPLATE_DELETION_PREVIEW_STALE", error.getReasonCode());
        assertEquals(2, error.getCurrentPreview().getEditHistoryEntryCount());
        verify(deviceTemplateRepo, never()).delete(any());
        verify(editJournal, never()).clear(anyLong());
    }

    @Test
    void templateDeletionDoesNotRevealWhetherAnotherUsersTemplateExists() {
        when(deviceTemplateRepo.findByIdAndUserId(10L, 1L)).thenReturn(java.util.Optional.empty());

        ResourceNotFoundException previewError = assertThrows(ResourceNotFoundException.class,
                () -> service.previewDeviceTemplateDeletion(1L, 10L));
        ResourceNotFoundException deleteError = assertThrows(ResourceNotFoundException.class,
                () -> service.deleteDeviceTemplate(1L, 10L, "confirmed-impact-token"));

        assertEquals(404, previewError.getCode());
        assertEquals(previewError.getMessage(), deleteError.getMessage());
        verify(deviceTemplateRepo, times(2)).findByIdAndUserId(10L, 1L);
        verify(deviceTemplateRepo, never()).findById(anyLong());
        verify(deviceTemplateRepo, never()).delete(any());
    }

    @Test
    void addDeviceTemplate_whenMissingWorkingStates_shouldFailFastBeforePrecheck() throws Exception {
        DeviceTemplateDto dto = buildTemplate("Demo", false);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        verify(smvGenerator, never()).generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class));
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());
    }

    @Test
    void addDeviceTemplate_whenNuSmvPrecheckFails_shouldReturnBadRequest() throws Exception {
        DeviceTemplateDto dto = buildTemplate("Demo", true);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "Demo")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(100L);
            return po;
        });
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenThrow(SmvGenerationException.smvGenerationError("invalid transition"));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        verify(smvGenerator).generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class));
    }

    @Test
    void addDeviceTemplate_whenNuSmvPrecheckInfraFails_shouldReturnInternalServerError() throws Exception {
        DeviceTemplateDto dto = buildTemplate("Demo", true);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "Demo")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(102L);
            return po;
        });
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenThrow(new java.io.IOException("disk io failed"));

        InternalServerException ex = assertThrows(InternalServerException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(500, ex.getCode());
    }

    @Test
    void addDeviceTemplate_whenNuSmvTemplateLoadError_shouldReturnInternalServerError() throws Exception {
        DeviceTemplateDto dto = buildTemplate("Demo", true);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "Demo")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(103L);
            return po;
        });
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenThrow(SmvGenerationException.templateLoadError("Demo", new RuntimeException("db down")));

        InternalServerException ex = assertThrows(InternalServerException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(500, ex.getCode());
    }

    @Test
    void addDeviceTemplate_whenNuSmvTemplateLookupMissing_shouldReturnInternalServerError() throws Exception {
        DeviceTemplateDto dto = buildTemplate("Demo", true);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "Demo")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(104L);
            return po;
        });
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenThrow(SmvGenerationException.multipleDevicesFailed("__template_probe_device__(template=Demo)"));

        InternalServerException ex = assertThrows(InternalServerException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(500, ex.getCode());
    }

    @Test
    void addDeviceTemplate_whenNuSmvPrecheckSucceeds_shouldSaveAndReturnTemplate() throws Exception {
        DeviceTemplateDto dto = buildTemplate("Demo", true);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "Demo")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(101L);
            return po;
        });
        File precheckFile = File.createTempFile("template-precheck-", ".smv");
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenReturn(new SmvGenerator.GenerateResult(precheckFile, Map.of()));

        DeviceTemplateDto result = service.addDeviceTemplate(1L, dto);

        assertEquals(101L, result.getId());
        assertEquals("Demo", result.getName());
        assertFalse(precheckFile.exists());
    }

    @Test
    void addDeviceTemplate_noModeDevice_shouldPassValidationAndSave() throws Exception {
        DeviceTemplateDto dto = buildNoModeTemplate("WeatherSensor");

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "WeatherSensor")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(200L);
            return po;
        });
        File precheckFile = File.createTempFile("template-precheck-", ".smv");
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenReturn(new SmvGenerator.GenerateResult(precheckFile, Map.of()));

        DeviceTemplateDto result = service.addDeviceTemplate(1L, dto);

        assertEquals(200L, result.getId());
        assertEquals("WeatherSensor", result.getName());
        assertFalse(precheckFile.exists());
    }

    @Test
    void addDeviceTemplate_partialModeFields_shouldReject() {
        // Has Modes and InitState but no WorkingStates — incomplete mode config
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("SwitchState"));
        manifest.setInitState("Off");
        manifest.setWorkingStates(List.of());

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Partial");
        dto.setManifest(manifest);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());
    }

    @SuppressWarnings("all")
    @NonNull
    private DeviceTemplatePo anyTemplatePo() {
        return (DeviceTemplatePo) any(DeviceTemplatePo.class);
    }

    private DeviceTemplateDto buildTemplate(String name, boolean withWorkingStates) {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("SwitchState"));
        manifest.setInitState("Off");
        if (withWorkingStates) {
            DeviceManifest.WorkingState off = new DeviceManifest.WorkingState();
            off.setName("Off");
            DeviceManifest.WorkingState on = new DeviceManifest.WorkingState();
            on.setName("On");
            manifest.setWorkingStates(List.of(off, on));
        }

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(manifest);
        return dto;
    }

    private DeviceTemplateDto buildNoModeTemplate(String name) {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(manifest);
        return dto;
    }

    /**
     * An over-long device label must be refused, not left to the database.
     *
     * <p>`DeviceNodeDto.label` carries `@Size(max = 255)`, which Spring applies on the `@Valid` REST
     * path. The AI tools call this service directly from a chat turn, and no `Validator` runs there —
     * no service class carries `@Validated`, and `AbstractAiTool`'s field helpers only trim. The column
     * is `length = 255`, so an over-long label reached the insert and returned a
     * `DataIntegrityViolationException`, surfaced to the assistant as a generic 500 "please retry" —
     * which invites the model to repeat the identical failing call.
     *
     * <p>Asserted at 256, one past the limit, so the test pins the boundary rather than some
     * comfortably-illegal size; and the accepted case is exactly 255.
     */
    @Test
    void saveNodes_whenDeviceLabelExceedsTheColumnLength_shouldRejectBeforePersisting() {
        DeviceTemplatePo template = templatePo("Light",
                "{\"Name\":\"Light\",\"Modes\":[\"SwitchState\"],\"InitState\":\"off\","
                        + "\"WorkingStates\":[{\"Name\":\"off\",\"Trust\":\"trusted\",\"Privacy\":\"public\"},"
                        + "{\"Name\":\"on\",\"Trust\":\"trusted\",\"Privacy\":\"public\"}],"
                        + "\"InternalVariables\":[]}");
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        DeviceNodeDto tooLong = buildNode("lamp1", "Light");
        tooLong.setLabel("L".repeat(RequestLimits.MAX_DEVICE_LABEL_LENGTH + 1));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.saveNodes(1L, List.of(tooLong)));
        org.assertj.core.api.Assertions.assertThat(ex.getErrors().toString())
                .contains("label")
                .contains(String.valueOf(RequestLimits.MAX_DEVICE_LABEL_LENGTH));

        DeviceNodeDto atLimit = buildNode("lamp2", "Light");
        atLimit.setLabel("L".repeat(RequestLimits.MAX_DEVICE_LABEL_LENGTH));
        assertDoesNotThrow(() -> service.saveNodes(1L, List.of(atLimit)));
    }

    /**
     * A device name reserved by the fix generator must be refused when the board is saved.
     *
     * <p>`SmvConstants.FIX_GENERATED_NAME_PREFIXES` had one consumer:
     * `NusmvRequestValidator.rejectFixGeneratedPrefix`, which rejects by *prefix* on every verify and
     * simulate request. Board admission's namespace pass registered only *concrete* generated names —
     * `lambda_r{i}_c{j}` and `param_r{i}_c{j}` for the rules and conditions present at the time — and
     * never considered `condition_value_` at all.
     *
     * <p>So the device persisted and then every verification returned a `400` naming a prefix the user
     * had no reason to know about, until they renamed the device. Milder than an HTTP 500, but the same
     * shape: accepted, stored, unusable.
     *
     * <p>All three prefixes are asserted, because the gap was not uniform — `param_`/`lambda_` were
     * caught only when the index happened to match a current rule/condition, and `condition_value_`
     * never was.
     */
    @Test
    void saveNodes_whenDeviceNameUsesAFixGeneratorPrefix_shouldRejectBeforePersisting() {
        DeviceTemplatePo template = templatePo("Light",
                "{\"Name\":\"Light\",\"Modes\":[\"SwitchState\"],\"InitState\":\"off\","
                        + "\"WorkingStates\":[{\"Name\":\"off\",\"Trust\":\"trusted\",\"Privacy\":\"public\"},"
                        + "{\"Name\":\"on\",\"Trust\":\"trusted\",\"Privacy\":\"public\"}],"
                        + "\"InternalVariables\":[]}");
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        for (String prefix : cn.edu.nju.Iot_Verify.util.SmvConstants.FIX_GENERATED_NAME_PREFIXES) {
            String reserved = prefix + "r0_c1";
            ValidationException ex = assertThrows(ValidationException.class,
                    () -> service.saveNodes(1L, List.of(buildNode(reserved, "Light"))),
                    () -> "reserved prefix must be refused at the board boundary: " + reserved);
            org.assertj.core.api.Assertions.assertThat(ex.getErrors().toString())
                    .contains(prefix)
                    // The remedy must be something the user can do: a device id is immutable
                    // (`renameNode` changes only the label), so "rename the device" sent them into a
                    // loop — the rename re-runs this check and fails on the id again.
                    .contains("cannot be changed after creation")
                    .doesNotContain("Rename the device");
        }
    }

    /** A name that merely *contains* a reserved prefix is legitimate and must stay accepted. */
    @Test
    void saveNodes_whenDeviceNameOnlyContainsAReservedPrefix_shouldBeAccepted() {
        DeviceTemplatePo template = templatePo("Light",
                "{\"Name\":\"Light\",\"Modes\":[\"SwitchState\"],\"InitState\":\"off\","
                        + "\"WorkingStates\":[{\"Name\":\"off\",\"Trust\":\"trusted\",\"Privacy\":\"public\"},"
                        + "{\"Name\":\"on\",\"Trust\":\"trusted\",\"Privacy\":\"public\"}],"
                        + "\"InternalVariables\":[]}");
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(template));

        // The guard is `startsWith`, matching the request-time check it mirrors — not `contains`.
        assertDoesNotThrow(() -> service.saveNodes(1L, List.of(buildNode("myParam_light", "Light"))));
    }

    private static String discreteWriterManifest(String name, String value) {
        return "{\"Name\":\"" + name + "\",\"Modes\":[\"MachineState\"],\"InitState\":\"off\","
                + "\"WorkingStates\":[{\"Name\":\"on\",\"Trust\":\"trusted\",\"Privacy\":\"public\","
                + "\"Dynamics\":[{\"VariableName\":\"airQuality\",\"Value\":\"" + value + "\"}]},"
                + "{\"Name\":\"off\",\"Trust\":\"trusted\",\"Privacy\":\"public\"}],"
                + "\"InternalVariables\":[{\"Name\":\"airQuality\",\"IsInside\":false,\"Reads\":false,"
                + "\"FalsifiableWhenCompromised\":false,\"Trust\":\"trusted\",\"Privacy\":\"public\","
                + "\"Values\":[\"good\",\"bad\"]}],"
                + "\"ImpactedVariables\":[\"airQuality\"],"
                + "\"APIs\":[{\"Name\":\"turnOn\",\"StartState\":\"off\",\"EndState\":\"on\","
                + "\"Signal\":true}]}";
    }

    /**
     * Two devices must not be admitted when they declare different values for one shared discrete value.
     *
     * <p>`SmvModelValidator.validateDiscreteWriterAgreement` refuses this at generation time, because
     * there is no defined way to combine two different values. Nothing checked it at admission: the
     * sibling domain-consistency pass compares *declarations* — type, range, enum values,
     * `NaturalChangeRate`, default labels — and never reads `WorkingStates[].Dynamics[].Value`.
     *
     * <p>The template gate cannot catch it either, being inherently single-manifest: each template is
     * valid alone, and only the pair is contradictory. So both devices persisted and every verification
     * afterwards returned HTTP 500 (`SmvGenerationException` maps to `INTERNAL_SERVER_ERROR`) until one
     * device was deleted. Measured before the fix: generation threw `Env variable 'airQuality' conflict:
     * … device 'writer_good_1' sets it to 'good' while device 'writer_bad_1' sets it to 'bad'`.
     */
    @Test
    void saveNodes_whenTwoDevicesDeclareConflictingDiscreteEffects_shouldRejectBeforePersisting() {
        DeviceTemplatePo good = templatePo("Writer Good", discreteWriterManifest("Writer Good", "good"));
        DeviceTemplatePo bad = DeviceTemplatePo.builder()
                .id(501L).userId(1L).name("Writer Bad")
                .manifestJson(discreteWriterManifest("Writer Bad", "bad"))
                .defaultTemplate(false).build();
        when(deviceTemplateRepo.findByUserId(1L)).thenReturn(List.of(good, bad));

        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.saveNodes(1L, List.of(
                        buildNode("writerGood1", "Writer Good"),
                        buildNode("writerBad1", "Writer Bad"))));

        org.assertj.core.api.Assertions.assertThat(ex.getErrors().toString())
                .contains("airQuality")
                .contains("conflicting declared effects");
    }

    /**
     * The same pair of values on **one** template is legitimate and must stay admitted.
     *
     * <p>A device whose two working states drive a shared value to different values is normal — that is
     * what a state machine does. Only *two different devices* disagreeing has no defined combination, so
     * the check must not fire on a single writer. Without this half, narrowing the check to reject any
     * repeated value would pass the test above while breaking ordinary templates.
     */
    @Test
    void saveNodes_whenOneDeviceDrivesTheSameValueBothWays_shouldBeAccepted() {
        String manifest = "{\"Name\":\"Swinger\",\"Modes\":[\"MachineState\"],\"InitState\":\"off\","
                + "\"WorkingStates\":[{\"Name\":\"on\",\"Trust\":\"trusted\",\"Privacy\":\"public\","
                + "\"Dynamics\":[{\"VariableName\":\"airQuality\",\"Value\":\"good\"}]},"
                + "{\"Name\":\"off\",\"Trust\":\"trusted\",\"Privacy\":\"public\","
                + "\"Dynamics\":[{\"VariableName\":\"airQuality\",\"Value\":\"bad\"}]}],"
                + "\"InternalVariables\":[{\"Name\":\"airQuality\",\"IsInside\":false,\"Reads\":false,"
                + "\"FalsifiableWhenCompromised\":false,\"Trust\":\"trusted\",\"Privacy\":\"public\","
                + "\"Values\":[\"good\",\"bad\"]}],"
                + "\"ImpactedVariables\":[\"airQuality\"],"
                + "\"APIs\":[{\"Name\":\"turnOn\",\"StartState\":\"off\",\"EndState\":\"on\","
                + "\"Signal\":true}]}";
        when(deviceTemplateRepo.findByUserId(1L))
                .thenReturn(List.of(templatePo("Swinger", manifest)));

        assertDoesNotThrow(() -> service.saveNodes(1L, List.of(buildNode("swinger1", "Swinger"))));
    }

    private DeviceNodeDto buildNode(String id, String templateName) {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId(id);
        node.setTemplateName(templateName);
        node.setLabel(id);
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(0.0);
        position.setY(0.0);
        node.setPosition(position);
        node.setState("off");
        node.setWidth(176);
        node.setHeight(128);
        return node;
    }

    private DeviceTemplatePo templatePo(String name, String manifestJson) {
        return DeviceTemplatePo.builder()
                .id(500L)
                .userId(1L)
                .name(name)
                .manifestJson(manifestJson)
                .defaultTemplate(false)
                .build();
    }

    // ======================== FIX-1: validateSmvIdentifier + checkVariableCollisions ========================

    @Test
    void addDeviceTemplate_internalVarWithInvalidChars_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", "temp-value", null);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("invalid characters");
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());
    }

    @Test
    void addDeviceTemplate_internalVarWithDigitPrefix_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", "3temp", null);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("invalid characters");
    }

    /*
     * Modes are compared on the token generation emits, not on the name the user typed.
     *
     * `sanitizeSmvToken` rescues a NuSMV reserved word by prefixing `_`, so mode `next` becomes `_next`. The
     * collision check used to compare raw names, which made `next` and `_next` look distinct — and the generated
     * model then declared the same enum constant twice. Verified against NuSMV 2.7.1: "TYPE ERROR: duplicate
     * constants in the enum type of variable", i.e. the user's verification failed with an engine type error
     * instead of a message naming the template field to rename.
     *
     * Modes and working states are the only identifier kinds that skip the reserved-word rejection variables get,
     * precisely because generation rescues them; that makes comparing pre-rescue names the bug.
     */
    @Test
    void addDeviceTemplate_modesCollidingAfterReservedWordRescue_shouldReject() {
        DeviceTemplateDto dto = buildTemplate("T1", true);
        dto.getManifest().setModes(List.of("next", "_next"));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("collides with another mode")
                .contains("_next");
    }

    /*
     * The same fix closes a second collision the raw comparison missed, and this one is worse.
     *
     * A mode named `next` is rescued to `_next`; an InternalVariable literally named `_next` is legal and needs no
     * rescue. Raw comparison saw `next` vs `_next` and passed them. The generated model then declares `_next`
     * both as an enum constant of the mode variable and as a variable name — and NuSMV 2.7.1 does not report a
     * type error for that, it **terminates by a signal** with "Aborting batch mode" and no diagnosable message,
     * where the control model differing only in the constant name verifies normally. That is the worst possible
     * failure shape for a verification product: no verdict and nothing to act on.
     */
    @Test
    void addDeviceTemplate_modeCollidingWithInternalVariableAfterRescue_shouldReject() {
        // A complete manifest, so validation reaches the collision check rather than stopping on a missing
        // InitState — my first version of this test used a fixture without one and asserted on the wrong message.
        DeviceTemplateDto dto = buildTemplate("T1", true);
        dto.getManifest().setModes(List.of("next"));
        DeviceManifest.InternalVariable iv = new DeviceManifest.InternalVariable();
        iv.setName("_next");
        iv.setIsInside(true);
        iv.setValues(List.of("a", "b"));
        iv.setTrust("trusted");
        iv.setPrivacy("public");
        iv.setFalsifiableWhenCompromised(false);
        dto.getManifest().setInternalVariables(List.of(iv));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("collides with mode name");
    }

    @Test
    void addDeviceTemplate_modesDistinctAfterNormalization_shouldBeAccepted() {
        // The boundary: a single reserved-word mode is legal, because generation renames it unambiguously.
        DeviceTemplateDto dto = buildTemplate("T1", true);
        dto.getManifest().setModes(List.of("next"));

        org.assertj.core.api.Assertions.assertThatCode(() -> service.addDeviceTemplate(1L, dto))
                .doesNotThrowAnyException();
    }

    @Test
    void addDeviceTemplate_internalVarWithReservedWord_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", "MODULE", null);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("reserved word");
    }

    @Test
    void addDeviceTemplate_internalVarReservedWordCaseInsensitive_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", "Define", null);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("reserved word");
    }

    @Test
    void addDeviceTemplate_impactedVarWithInvalidChars_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", null, "humidity!");

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("invalid characters");
    }

    @Test
    void addDeviceTemplate_impactedVarReservedWord_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", null, "NEXT");

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("reserved word");
    }

    @Test
    void addDeviceTemplate_internalVarCollidesWithMode_shouldReject() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("Power"));
        manifest.setInitState("on");
        DeviceManifest.WorkingState on = new DeviceManifest.WorkingState();
        on.setName("on");
        DeviceManifest.WorkingState off = new DeviceManifest.WorkingState();
        off.setName("off");
        manifest.setWorkingStates(List.of(on, off));
        // InternalVariable name "power" collides with mode "Power" (case-insensitive)
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("power").isInside(true).lowerBound(0).upperBound(100).build()));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Collider");
        dto.setManifest(manifest);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("collides with mode name");
    }

    @Test
    void addDeviceTemplate_internalVarWithLowercaseA_Prefix_shouldReject() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setInitState("idle");
        manifest.setWorkingStates(List.of(workingState("idle")));
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("a_temperature")
                        .isInside(true)
                        .lowerBound(0)
                        .upperBound(100)
                        .build()));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("BadPrefix");
        dto.setManifest(manifest);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("must not start with 'a_'")
                .contains("reserved for environment pool identifiers");
    }

    @Test
    void addDeviceTemplate_internalVarWithUppercaseA_Prefix_shouldReject() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setInitState("idle");
        manifest.setWorkingStates(List.of(workingState("idle")));
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("A_temperature")
                        .isInside(true)
                        .lowerBound(0)
                        .upperBound(100)
                        .build()));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("UppercasePrefix");
        dto.setManifest(manifest);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("must not start with 'a_'")
                .contains("reserved for environment pool identifiers");
    }

    @Test
    void addDeviceTemplate_impactedVarCollidesWithLocalInternalVar_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", "temperature", null);
        dto.getManifest().setImpactedVariables(List.of("Temperature"));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("cannot share a name with a local InternalVariable");
    }

    @Test
    void addDeviceTemplate_impactedVarCollidesWithEnvironmentInternalVar_shouldAllow() throws Exception {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false).reads(true).trust("untrusted").privacy("public")
                        .lowerBound(0).upperBound(100).naturalChangeRate("0").build()));
        manifest.setImpactedVariables(List.of("temperature"));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("ThermostatLike");
        dto.setManifest(manifest);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "ThermostatLike")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(300L);
            return po;
        });
        File precheckFile = File.createTempFile("template-precheck-", ".smv");
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenReturn(new SmvGenerator.GenerateResult(precheckFile, Map.of()));

        DeviceTemplateDto result = service.addDeviceTemplate(1L, dto);
        assertNotNull(result);
        assertEquals(300L, result.getId());
        assertFalse(precheckFile.exists());
    }

    @Test
    void addDeviceTemplate_noModeWithImpactedVarCollision_shouldAllow() throws Exception {
        // Environment InternalVariable + ImpactedVariable with the same name is allowed.
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("humidity").isInside(false).reads(true).trust("untrusted").privacy("public")
                        .lowerBound(0).upperBound(100).naturalChangeRate("0").build()));
        manifest.setImpactedVariables(List.of("humidity"));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Sensor");
        dto.setManifest(manifest);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "Sensor")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(301L);
            return po;
        });
        File precheckFile = File.createTempFile("template-precheck-", ".smv");
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenReturn(new SmvGenerator.GenerateResult(precheckFile, Map.of()));

        // Should succeed now
        DeviceTemplateDto result = service.addDeviceTemplate(1L, dto);
        assertNotNull(result);
        assertEquals(301L, result.getId());
        assertFalse(precheckFile.exists());
    }

    @Test
    void addDeviceTemplate_blankInternalVarName_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", "  ", null);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("must not be blank");
    }

    @Test
    void addDeviceTemplate_impactedVarWithSpace_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("T1", null, "humidity bad");

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        assertEquals(400, ex.getCode());
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("whitespace");
    }

    @Test
    void addDeviceTemplate_generatedTrustIdentifierCollision_shouldReject() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(true).lowerBound(0).upperBound(100).build(),
                DeviceManifest.InternalVariable.builder()
                        .name("trust_temperature").isInside(true).lowerBound(0).upperBound(100).build()));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("TrustCollision");
        dto.setManifest(manifest);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("generated NuSMV identifier 'trust_temperature'")
                .contains("collides");
    }

    @Test
    void addDeviceTemplate_generatedRateIdentifierCollision_shouldReject() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("temperature").isInside(false).reads(true).trust("untrusted").privacy("public")
                        .lowerBound(0).upperBound(100).naturalChangeRate("0").build(),
                DeviceManifest.InternalVariable.builder()
                        .name("temperature_rate").isInside(true).lowerBound(-10).upperBound(10).build()));
        manifest.setImpactedVariables(List.of("temperature"));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("RateCollision");
        dto.setManifest(manifest);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("generated NuSMV identifier 'temperature_rate'")
                .contains("collides");
    }

    @Test
    void addDeviceTemplate_generatedApiSignalIdentifierCollision_shouldReject() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("Power"));
        manifest.setInitState("off");
        manifest.setWorkingStates(List.of(
                DeviceManifest.WorkingState.builder().name("off").build(),
                DeviceManifest.WorkingState.builder().name("on").build()));
        manifest.setInternalVariables(List.of(
                DeviceManifest.InternalVariable.builder()
                        .name("press_a").isInside(true).lowerBound(0).upperBound(1).build()));
        manifest.setApis(List.of(DeviceManifest.API.builder()
                .name("press")
                .signal(true)
                .startState("off")
                .endState("on")
                .build()));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("ApiSignalCollision");
        dto.setManifest(manifest);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("generated NuSMV identifier 'press_a'")
                .contains("collides");
    }

    @Test
    void addDeviceTemplate_attackFlagIdentifierCollision_shouldReject() {
        DeviceTemplateDto dto = buildTemplateWithVar("AttackCollision", "is_attack", null);

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("generated NuSMV identifier 'is_attack'")
                .contains("collides");
    }

    @Test
    void addDeviceTemplate_prefixedBusinessVariableWithoutGeneratedCollision_shouldAllow() throws Exception {
        DeviceTemplateDto dto = buildTemplateWithVar("PrefixedBusinessVariable", "trust_temperature", null);

        when(deviceTemplateRepo.existsByUserIdAndNameIgnoreCase(1L, "PrefixedBusinessVariable")).thenReturn(false);
        when(deviceTemplateRepo.saveAndFlush(anyTemplatePo())).thenAnswer(inv -> {
            DeviceTemplatePo po = Objects.requireNonNull(inv.getArgument(0, DeviceTemplatePo.class));
            po.setId(302L);
            return po;
        });
        File precheckFile = File.createTempFile("template-precheck-", ".smv");
        when(smvGenerator.generate(
                anyLong(), anyList(), anyList(), anyList(), any(), anyBoolean(),
                any(SmvGenerator.GeneratePurpose.class)))
                .thenReturn(new SmvGenerator.GenerateResult(precheckFile, Map.of()));

        DeviceTemplateDto saved = service.addDeviceTemplate(1L, dto);

        assertEquals(302L, saved.getId());
        assertFalse(precheckFile.exists());
    }

    /**
     * A mode whose rescued token equals a working-state value declares one identifier twice.
     *
     * <p>NuSMV keeps variables and enumeration constants in one module namespace, so a mode variable named
     * `_next` alongside a state `next` — which `sanitizeSmvToken` also rescues to `_next` — emits the
     * identifier twice. Measured on 2.7.1: `FanMode: {_next, idle}; _next: {on, auto};` gives
     * `line 4: multiple declaration of identifier: _next`, exit 1.
     *
     * <p>The accepted half is the one that matters most here: two *modes* legitimately share a state value.
     * The bundled `Thermostat` has `auto` in both `ThermostatFanMode` and `ThermostatMode` and that model is
     * accepted by the engine, so the guard checks mode-token-against-state-token one way rather than
     * registering state names as identifiers, which would refuse a shipped template.
     *
     * <p>Found by `ManifestAdmissionParsesInNuSmvPropertyTest`, which generates mode names from a pool of
     * rescue-colliding tokens and parses each model with the real engine. `2e2b1e4` had closed mode-vs-mode
     * and `trust_<mode>_<state>`; this namespace pair had no counterpart.
     */
    @Test
    void addDeviceTemplate_whenAModeTokenEqualsAStateToken_shouldReject() {
        DeviceTemplateDto rejected = twoModeTemplate("Mode State Clash", "next;on", "idle;auto");
        rejected.getManifest().setModes(List.of("_next", "Fan"));

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, rejected));
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("_next")
                .contains("working-state value");
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());

        // Two modes sharing a state value is legal — the Thermostat shape. Both columns must still vary,
        // or the single-distinct-state check fires first and this would pass for the wrong reason.
        DeviceTemplateDto accepted = twoModeTemplate("Shared State Value", "auto;on", "low;auto");
        accepted.getManifest().setModes(List.of("FanMode", "HvacMode"));
        assertDoesNotThrow(() -> service.addDeviceTemplate(1L, accepted));
    }

    private static DeviceTemplateDto twoModeTemplate(String name, String stateA, String stateB) {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("Power", "Fan"));
        manifest.setInitState(stateA);
        manifest.setWorkingStates(List.of(
                workingState(stateA), workingState(stateB)));
        manifest.setInternalVariables(List.of());
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(manifest);
        return dto;
    }

    private static DeviceManifest.WorkingState workingState(String tuple) {
        DeviceManifest.WorkingState state = new DeviceManifest.WorkingState();
        state.setName(tuple);
        state.setTrust("trusted");
        state.setPrivacy("public");
        return state;
    }

    /**
     * A mode with only one distinct working state is a constant NuSMV cannot initialise.
     *
     * <p>Same engine limitation as a single-value `InternalVariables` domain, one field over. Measured on
     * 2.7.1: `VAR Power: {on}; ASSIGN init(Power) := on;` gives
     * `WARNING: single-value variable 'p_1.Power' has been stored as a constant` then
     * `A variable is expected in left-hand-side of assignment`, exit 1.
     *
     * <p>Reachable with entirely ordinary names — no reserved word, no rescue, no punctuation: modes
     * `["Power","Fan"]` with tuples `on;low` and `on;high` leave `Power` with the single value `on`. The
     * earlier fix closed the variable-domain spelling of this and did not reach the per-mode one.
     *
     * <p>Found by a property probe that generated manifests and parsed each with the real engine, which
     * is the point worth keeping: the field-by-field approach cannot find the field nobody thought of.
     *
     * <p>Both directions asserted, and the accepted case matters as much as the rejection — every
     * multi-mode bundled template relies on a mode legitimately having two or more states.
     */
    @Test
    void addDeviceTemplate_whenAModeHasOnlyOneDistinctState_shouldReject() {
        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, twoModeTemplate("Single State Mode", "on;low", "on;high")));
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("Power")
                .contains("one distinct working state");
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());

        assertDoesNotThrow(() ->
                service.addDeviceTemplate(1L, twoModeTemplate("Two State Modes", "on;low", "off;high")));
    }

    /**
     * A domain too wide for the engine must be refused, because the engine fails *silently*.
     *
     * <p>Measured on NuSMV 2.7.1: `v: 0..300000` prints the banner and dies — rc=127, no error text,
     * **zero verdicts** — deterministically, in batch and `-int` mode alike. `0..100000` still answers in
     * 0.37 s, so the cliff sits between them. Nothing bounded the width before: the schema declares
     * `LowerBound`/`UpperBound` as plain `integer`, and this validator checked only `low > high` and
     * `low == high`. So the template persisted and every later verification of any board using it
     * returned nothing at all, with no diagnosis pointing back at the template.
     *
     * <p>Both directions asserted. The 45 bundled templates and 6 example scenes top out at **101**
     * values across 30 numeric domains, so the accepted case sits far above anything shipped while
     * staying under the cap — a check that crept down toward 101 would start refusing real templates,
     * and this pins that it does not.
     */
    @Test
    void addDeviceTemplate_whenNumericDomainIsTooWideForTheEngine_shouldReject() {
        DeviceManifest.InternalVariable tooWide = new DeviceManifest.InternalVariable();
        tooWide.setName("reading");
        tooWide.setIsInside(true);
        tooWide.setFalsifiableWhenCompromised(false);
        tooWide.setTrust("trusted");
        tooWide.setPrivacy("public");
        tooWide.setLowerBound(0);
        tooWide.setUpperBound(RequestLimits.MAX_NUMERIC_DOMAIN_VALUES);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, singleVariableTemplate("Too Wide", tooWide)));
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("reading")
                .contains(String.valueOf(RequestLimits.MAX_NUMERIC_DOMAIN_VALUES));
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());

        DeviceManifest.InternalVariable atLimit = new DeviceManifest.InternalVariable();
        atLimit.setName("reading");
        atLimit.setIsInside(true);
        atLimit.setFalsifiableWhenCompromised(false);
        atLimit.setTrust("trusted");
        atLimit.setPrivacy("public");
        atLimit.setLowerBound(1);
        atLimit.setUpperBound(RequestLimits.MAX_NUMERIC_DOMAIN_VALUES);
        assertDoesNotThrow(() ->
                service.addDeviceTemplate(1L, singleVariableTemplate("At Limit", atLimit)));
    }

    /**
     * A domain of exactly one value is a constant to NuSMV, and a constant cannot be initialised.
     *
     * <p>Generation always emits `init(<name>) := <value>`. Measured on 2.7.1:
     * `VAR level: 5..5; ASSIGN init(level) := 5;` produces
     * `WARNING: single-value variable 'level' has been stored as a constant` followed by
     * `A variable is expected in left-hand-side of assignment`, exit 1. The identical model with `5..6`
     * is clean, so cardinality is the whole difference.
     *
     * <p>Only `LowerBound > UpperBound` was checked, so `5 == 5` passed all four template gates; the
     * template persisted and every later verification of a board using it died in the engine.
     * `runTemplateNuSmvPrecheck` cannot catch it — it generates model text without invoking NuSMV, and it
     * runs after `saveAndFlush` in any case.
     *
     * <p>Three cases, because the defect has three spellings and one boundary: numeric `5..5`, a
     * one-member enum, and the control `5..6` that must stay accepted.
     */
    @Test
    void addDeviceTemplate_whenADomainHasExactlyOneValue_shouldReject() {
        DeviceManifest.InternalVariable numeric = new DeviceManifest.InternalVariable();
        numeric.setName("level");
        numeric.setIsInside(true);
        numeric.setFalsifiableWhenCompromised(false);
        numeric.setTrust("trusted");
        numeric.setPrivacy("public");
        numeric.setLowerBound(5);
        numeric.setUpperBound(5);

        BadRequestException numericFailure = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, singleVariableTemplate("Narrow Numeric", numeric)));
        org.assertj.core.api.Assertions.assertThat(numericFailure.getMessage())
                .contains("level")
                .contains("LowerBound equal to UpperBound");

        DeviceManifest.InternalVariable singleEnum = new DeviceManifest.InternalVariable();
        singleEnum.setName("smoke");
        singleEnum.setIsInside(true);
        singleEnum.setFalsifiableWhenCompromised(false);
        singleEnum.setTrust("trusted");
        singleEnum.setPrivacy("public");
        singleEnum.setValues(List.of("detected"));

        BadRequestException enumFailure = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, singleVariableTemplate("Narrow Enum", singleEnum)));
        org.assertj.core.api.Assertions.assertThat(enumFailure.getMessage())
                .contains("smoke")
                .contains("single enum value");

        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());

        // The boundary: a two-wide domain is the narrowest NuSMV can model as a variable.
        DeviceManifest.InternalVariable widest = new DeviceManifest.InternalVariable();
        widest.setName("level");
        widest.setIsInside(true);
        widest.setFalsifiableWhenCompromised(false);
        widest.setTrust("trusted");
        widest.setPrivacy("public");
        widest.setLowerBound(5);
        widest.setUpperBound(6);
        assertDoesNotThrow(() ->
                service.addDeviceTemplate(1L, singleVariableTemplate("Two Wide", widest)));
    }

    private static DeviceTemplateDto singleVariableTemplate(
            String name, DeviceManifest.InternalVariable variable) {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setInternalVariables(List.of(variable));
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(manifest);
        return dto;
    }

    private static String rescuedModeManifest(String variableName) {
        return "{\"Name\":\"Rescue Probe\",\"Modes\":[\"Next\"],\"InitState\":\"cold\","
                + "\"WorkingStates\":[{\"Name\":\"cold\",\"Dynamics\":[{\"VariableName\":\"" + variableName + "\",\"Value\":\"low\"}],\"Trust\":\"trusted\",\"Privacy\":\"public\"},"
                + "{\"Name\":\"warm\",\"Dynamics\":[{\"VariableName\":\"" + variableName + "\",\"Value\":\"high\"}],\"Trust\":\"trusted\",\"Privacy\":\"public\"}],"
                + "\"InternalVariables\":[{\"Name\":\"" + variableName + "\",\"IsInside\":true,"
                + "\"FalsifiableWhenCompromised\":false,\"Trust\":\"trusted\",\"Privacy\":\"public\","
                + "\"Values\":[\"low\",\"high\"]}],"
                + "\"APIs\":[{\"Name\":\"warmUp\",\"StartState\":\"cold\",\"EndState\":\"warm\","
                + "\"Signal\":true}]}";
    }

    /**
     * The collision guard must compare the token generation actually emits, not the authored one.
     *
     * <p>`DeviceSmvDataFactory.extractModes` stores `sanitizeSmvToken(rawMode)`, and
     * `SmvDeviceModuleBuilder.appendStatePropertyVariables` builds `trust_<mode>_<state>` from that.
     * `DeviceManifestModes.modeNames` only trims, so the guard once compared the pre-rescue name and
     * could not see a collision against the post-rescue one.
     *
     * <p>Mode `Next` is the entrance: the schema's reserved-word enum is case-**sensitive** so it is
     * admitted, while `sanitizeSmvToken` folds case and rescues it to `_Next`. Measured before the fix —
     * a template with that mode plus an InternalVariable named `trust__Next_cold` was admitted, emitted
     * `trust__Next_cold` twice, and NuSMV refused the model with `multiple declaration of identifier`.
     *
     * <p>Both names are asserted, and that pairing is the whole point: the **post**-rescue name must be
     * refused, and the **pre**-rescue name must still be accepted. Asserting only the rejection would
     * also pass if the guard compared the raw name, which is the bug. The state leg needs no such case
     * because `DeviceManifestModes.modeStates` already routes each segment through `cleanStateName`.
     *
     * <p>This regression was missing: the fix shipped as a production-only change, so reverting it left
     * the whole suite green.
     */
    @Test
    void addDeviceTemplate_whenVariableCollidesWithTheRescuedModeToken_shouldReject() {
        DeviceTemplateDto rejected = new DeviceTemplateDto();
        rejected.setName("Rescue Probe");
        rejected.setManifest(JsonUtils.fromJson(
                rescuedModeManifest("trust__Next_cold"), DeviceManifest.class));

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, rejected));
        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("trust__Next_cold")
                .contains("collides");
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());

        // The pre-rescue spelling collides with nothing the generator emits, so it must stay accepted.
        DeviceTemplateDto accepted = new DeviceTemplateDto();
        accepted.setName("Rescue Probe");
        accepted.setManifest(JsonUtils.fromJson(
                rescuedModeManifest("trust_Next_cold"), DeviceManifest.class));
        assertDoesNotThrow(() -> service.addDeviceTemplate(1L, accepted));
    }

    /**
     * A reserved word is a legal SMV token and an illegal enumeration constant.
     *
     * <p>The pattern check added alongside this one cannot catch it: `next` matches
     * `^[a-zA-Z_][a-zA-Z0-9_]*$` perfectly. Measured before this check — `Values: ["next", "ok"]` passed
     * all three admission stages, emitted `authState: {next, ok};`, and NuSMV 2.7.1 refused the model
     * with `at token "next": syntax error` (exit 1). `TRUE`/`FALSE` fail slightly differently, as
     * `Invalid enumerative value`, but also exit 1.
     *
     * <p>The case-sensitivity is the part worth pinning, and it deliberately differs from
     * `validateSmvIdentifier`, which folds case for *names*. NuSMV's lexer is case-sensitive here:
     * measured, `{Next, ok}` and `{NEXT, ok}` compile while `{next, ok}` does not. So folding case would
     * reject values the engine accepts. A name can afford to over-reject — it is `.equals()`-matched and
     * never rescued — but a value is emitted verbatim, so the rule must match the engine exactly.
     */
    @Test
    void addDeviceTemplate_enumValueThatIsAReservedWord_shouldReject() {
        DeviceManifest.InternalVariable variable = new DeviceManifest.InternalVariable();
        variable.setName("authState");
        variable.setIsInside(true);  // Restored: test本地变量路径
        variable.setFalsifiableWhenCompromised(false);
        variable.setTrust("trusted");
        variable.setPrivacy("public");
        variable.setValues(List.of("next", "ok"));

        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("TestMode"));
        manifest.setInitState("idle");

        // 添加WorkingState with Dynamics以通过frozen-variable检查
        DeviceManifest.WorkingState idle = new DeviceManifest.WorkingState();
        idle.setName("idle");
        idle.setTrust("trusted");
        idle.setPrivacy("public");
        DeviceManifest.Dynamic dynamic = new DeviceManifest.Dynamic();
        dynamic.setVariableName("authState");
        dynamic.setValue("ok");
        idle.setDynamics(List.of(dynamic));

        manifest.setWorkingStates(List.of(idle));
        manifest.setInternalVariables(List.of(variable));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Reserved Value Sensor");
        dto.setManifest(manifest);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("authState")
                .contains("next")
                .contains("reserved word");
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());
    }

    /** A case variant NuSMV actually accepts must stay admitted — the check must not fold case. */
    @Test
    void addDeviceTemplate_enumValueThatIsACaseVariantOfAReservedWord_shouldBeAccepted() {
        DeviceManifest.InternalVariable variable = new DeviceManifest.InternalVariable();
        variable.setName("authState");
        variable.setIsInside(true);  // Restored: test local variable path
        variable.setFalsifiableWhenCompromised(false);
        variable.setTrust("trusted");
        variable.setPrivacy("public");
        // NuSMV 2.7.1 compiles `{Next, ok}` — verified directly against the engine.
        variable.setValues(List.of("Next", "ok"));

        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("TestMode"));
        manifest.setInitState("idle");

        // Add 2 WorkingStates with Dynamics (NuSMV requires at least 2 states per mode)
        DeviceManifest.WorkingState idle = new DeviceManifest.WorkingState();
        idle.setName("idle");
        idle.setTrust("trusted");
        idle.setPrivacy("public");
        DeviceManifest.Dynamic dynamic = new DeviceManifest.Dynamic();
        dynamic.setVariableName("authState");
        dynamic.setValue("Next");
        idle.setDynamics(List.of(dynamic));

        DeviceManifest.WorkingState active = new DeviceManifest.WorkingState();
        active.setName("active");
        active.setTrust("trusted");
        active.setPrivacy("public");
        DeviceManifest.Dynamic dynamic2 = new DeviceManifest.Dynamic();
        dynamic2.setVariableName("authState");
        dynamic2.setValue("ok");
        active.setDynamics(List.of(dynamic2));

        manifest.setWorkingStates(List.of(idle, active));
        manifest.setInternalVariables(List.of(variable));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Case Variant Sensor");
        dto.setManifest(manifest);

        assertDoesNotThrow(() -> service.addDeviceTemplate(1L, dto));
    }

    /**
     * An enum value is emitted as a bare SMV token, so it must be a legal one.
     *
     * <p>`SmvDeviceModuleBuilder` writes `Values` straight into the `{...}` domain and onto the
     * right-hand side of every comparison against the variable. Nothing checked them: the schema had
     * only `minLength: 1`, and the Java side checked emptiness and duplication *after* a
     * `replace(" ", "")` whose own comment calls it cosmetic ("match sample.smv"). That strip removes
     * the one character NuSMV tolerates as a separator and keeps every character it rejects.
     *
     * <p>Measured before the fix: `Values: ["hot!", "ok"]` passed the schema and all four validators,
     * emitted `authState: {hot!, ok};`, and NuSMV refused the model with `at token "!": syntax error`.
     * The template persisted, so every later verification of any board using it died in the engine.
     *
     * <p>Validation happens after space removal on purpose, and the second half of this test pins that:
     * bundled `Door RFID` ("not authorized") and `Thermostat` ("pending cool", …) depend on the
     * allowance, so a stricter pattern would have broken template loading instead.
     */
    @Test
    void addDeviceTemplate_enumValueThatIsNotAnSmvToken_shouldReject() {
        DeviceManifest.InternalVariable variable = new DeviceManifest.InternalVariable();
        variable.setName("authState");
        variable.setIsInside(true);  // Restored: test local variable path
        variable.setFalsifiableWhenCompromised(false);
        variable.setTrust("trusted");
        variable.setPrivacy("public");
        variable.setValues(List.of("hot!", "ok"));

        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("TestMode"));
        manifest.setInitState("idle");

        // Add 2 WorkingStates with Dynamics
        DeviceManifest.WorkingState idle = new DeviceManifest.WorkingState();
        idle.setName("idle");
        idle.setTrust("trusted");
        idle.setPrivacy("public");
        DeviceManifest.Dynamic d1 = new DeviceManifest.Dynamic();
        d1.setVariableName("authState");
        d1.setValue("ok");
        idle.setDynamics(List.of(d1));

        DeviceManifest.WorkingState active = new DeviceManifest.WorkingState();
        active.setName("active");
        active.setTrust("trusted");
        active.setPrivacy("public");
        DeviceManifest.Dynamic d2 = new DeviceManifest.Dynamic();
        d2.setVariableName("authState");
        d2.setValue("ok");
        active.setDynamics(List.of(d2));

        manifest.setWorkingStates(List.of(idle, active));
        manifest.setInternalVariables(List.of(variable));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Bad Values Sensor");
        dto.setManifest(manifest);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("authState")
                .contains("hot!")
                .contains("legal NuSMV token");
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());
    }

    /** A value legal only after space removal must still be accepted — bundled templates rely on it. */
    @Test
    void addDeviceTemplate_enumValueWithSpaces_shouldBeAccepted() {
        DeviceManifest.InternalVariable variable = new DeviceManifest.InternalVariable();
        variable.setName("RFID");
        variable.setIsInside(true);  // Restored: test local variable path
        variable.setFalsifiableWhenCompromised(false);
        variable.setTrust("trusted");
        variable.setPrivacy("private");
        variable.setValues(List.of("authorized", "not authorized"));

        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of("RFIDMode"));
        manifest.setInitState("idle");

        // Add 2 WorkingStates with Dynamics
        DeviceManifest.WorkingState idle = new DeviceManifest.WorkingState();
        idle.setName("idle");
        idle.setTrust("trusted");
        idle.setPrivacy("private");
        DeviceManifest.Dynamic d1 = new DeviceManifest.Dynamic();
        d1.setVariableName("RFID");
        d1.setValue("authorized");
        idle.setDynamics(List.of(d1));

        DeviceManifest.WorkingState scanning = new DeviceManifest.WorkingState();
        scanning.setName("scanning");
        scanning.setTrust("trusted");
        scanning.setPrivacy("private");
        DeviceManifest.Dynamic d2 = new DeviceManifest.Dynamic();
        d2.setVariableName("RFID");
        d2.setValue("not authorized");
        scanning.setDynamics(List.of(d2));

        manifest.setWorkingStates(List.of(idle, scanning));
        manifest.setInternalVariables(List.of(variable));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Spaced Values Sensor");
        dto.setManifest(manifest);

        assertDoesNotThrow(() -> service.addDeviceTemplate(1L, dto));
    }

    @Test
    void addDeviceTemplate_impactedVariableWithoutOwnDomain_shouldReject() {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        manifest.setInternalVariables(List.of());
        manifest.setImpactedVariables(List.of("temperature"));

        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName("Incomplete Actuator");
        dto.setManifest(manifest);

        BadRequestException exception = assertThrows(BadRequestException.class, () ->
                service.addDeviceTemplate(1L, dto));

        org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                .contains("has no domain in this manifest")
                // The remedy must name a field that still exists. This assertion used to require the
                // message to say "EnvironmentDomains", so it actively pinned advice the schema rejects.
                .contains("IsInside=false")
                .contains("Reads=false")
                .doesNotContain("EnvironmentDomains");
        verify(deviceTemplateRepo, never()).saveAndFlush(anyTemplatePo());
    }

    /**
     * Helper: build a no-mode template with an optional InternalVariable and/or ImpactedVariable.
     */
    private DeviceTemplateDto buildTemplateWithVar(String name, String internalVarName, String impactedVarName) {
        DeviceManifest manifest = new DeviceManifest();
        manifest.setModes(List.of());
        manifest.setInitState("");
        manifest.setWorkingStates(List.of());
        if (internalVarName != null) {
            manifest.setInternalVariables(List.of(
                    DeviceManifest.InternalVariable.builder()
                            .name(internalVarName).isInside(true).lowerBound(0).upperBound(100).build()));
        }
        if (impactedVarName != null) {
            manifest.setImpactedVariables(List.of(impactedVarName));
        }
        DeviceTemplateDto dto = new DeviceTemplateDto();
        dto.setName(name);
        dto.setManifest(manifest);
        return dto;
    }

    private static BoardEditHistoryState historyState(int entryCount, String tokenCharacter) {
        return new BoardEditHistoryState(
                entryCount,
                new BoardUndoAvailability(entryCount > 0, false),
                tokenCharacter.repeat(64));
    }
}
