package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.po.BoardEnvironmentVariablePo;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.po.RulePo;
import cn.edu.nju.Iot_Verify.po.SpecificationPo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.service.ChatExecutionLeaseGuard;
import cn.edu.nju.Iot_Verify.repository.BoardEnvironmentVariableRepository;
import cn.edu.nju.Iot_Verify.repository.BoardLayoutRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceNodeRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.repository.RuleRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.util.mapper.BoardLayoutMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BoardStorageServiceImplSemanticSnapshotTest {

    @Mock private DeviceNodeRepository nodeRepo;
    @Mock private BoardEnvironmentVariableRepository environmentRepo;
    @Mock private SpecificationRepository specRepo;
    @Mock private RuleRepository ruleRepo;
    @Mock private BoardLayoutRepository layoutRepo;
    @Mock private DeviceTemplateRepository templateRepo;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private SpecificationMapper specificationMapper;
    @Mock private RuleMapper ruleMapper;
    @Mock private DeviceNodeMapper deviceNodeMapper;
    @Mock private BoardLayoutMapper boardLayoutMapper;
    @Mock private DeviceTemplateMapper deviceTemplateMapper;
    @Mock private UserRepository userRepository;

    private BoardStorageServiceImpl service;
    private final ThreadLocal<Boolean> transactionActive = ThreadLocal.withInitial(() -> false);

    @BeforeEach
    void setUp() {
        service = new BoardStorageServiceImpl(
                nodeRepo, environmentRepo, specRepo, ruleRepo, layoutRepo, templateRepo, null,
                transactionTemplate, null, specificationMapper, ruleMapper, deviceNodeMapper,
                boardLayoutMapper, deviceTemplateMapper, null, userRepository, null);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            transactionActive.set(true);
            try {
                return ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null);
            } finally {
                transactionActive.remove();
            }
        });
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.of(new UserPo()));
    }

    @Test
    void getSemanticSnapshot_readsEveryModelCollectionInOneTransaction() {
        DeviceNodePo nodePo = new DeviceNodePo();
        DeviceNodeDto node = new DeviceNodeDto();
        BoardEnvironmentVariablePo environmentPo = BoardEnvironmentVariablePo.builder()
                .name("temperature").value("20").trust("trusted").privacy("public").build();
        RulePo rulePo = new RulePo();
        RuleDto rule = new RuleDto();
        SpecificationPo specPo = new SpecificationPo();
        SpecificationDto spec = new SpecificationDto();
        DeviceTemplatePo templatePo = new DeviceTemplatePo();
        DeviceTemplateDto template = new DeviceTemplateDto();

        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(nodePo));
        when(deviceNodeMapper.toDto(nodePo)).thenReturn(node);
        when(environmentRepo.findByUserIdOrderByNameAsc(1L)).thenReturn(List.of(environmentPo));
        when(ruleRepo.findByUserIdOrderByExecutionOrderAscIdAsc(1L)).thenReturn(List.of(rulePo));
        when(ruleMapper.toDto(rulePo)).thenReturn(rule);
        when(specRepo.findByUserId(1L)).thenReturn(List.of(specPo));
        when(specificationMapper.toDto(specPo)).thenReturn(spec);
        when(templateRepo.findByUserId(1L)).thenReturn(List.of(templatePo));
        when(deviceTemplateMapper.toDto(templatePo)).thenReturn(template);

        BoardSemanticSnapshotDto snapshot = service.getSemanticSnapshot(1L);

        assertSame(node, snapshot.nodes().get(0));
        assertEquals("20", snapshot.environmentVariables().get(0).getValue());
        assertSame(rule, snapshot.rules().get(0));
        assertSame(spec, snapshot.specifications().get(0));
        assertSame(template, snapshot.deviceTemplates().get(0));
        verify(transactionTemplate).execute(any());
        verify(userRepository).findByIdForShare(1L);
        verify(userRepository, never()).findByIdForUpdate(1L);
        verify(nodeRepo).findByUserId(1L);
        verify(environmentRepo).findByUserIdOrderByNameAsc(1L);
        verify(ruleRepo).findByUserIdOrderByExecutionOrderAscIdAsc(1L);
        verify(specRepo).findByUserId(1L);
        verify(templateRepo).findByUserId(1L);
    }

    @Test
    void missingAccountCannotBecomeAnEmptySuccessfulSnapshot() {
        when(userRepository.findByIdForShare(1L)).thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class, () -> service.getSemanticSnapshot(1L));

        verifyNoInteractions(nodeRepo, environmentRepo, ruleRepo, specRepo, templateRepo);
    }

    @Test
    void sharedCaptureStillRejectsALostChatExecutionLeaseBeforeReadingModelRows() {
        var guard = mock(ChatExecutionLeaseGuard.class);
        service.setChatExecutionLeaseGuard(guard);
        var lostLease = new IllegalStateException("execution ownership lost");
        doThrow(lostLease).when(guard).requireCurrentExecutionLease();

        assertSame(lostLease, assertThrows(IllegalStateException.class, () -> service.getSemanticSnapshot(1L)));

        verifyNoInteractions(nodeRepo, environmentRepo, ruleRepo, specRepo, templateRepo);
    }

    @Test
    void snapshotDecodingReleasesTheTransactionAndAllowsTheNextCapture() throws Exception {
        DeviceNodePo firstRow = DeviceNodePo.builder().id("first").userId(1L).build();
        DeviceNodePo nextRow = DeviceNodePo.builder().id("next").userId(1L).build();
        DeviceNodeDto firstNode = new DeviceNodeDto();
        DeviceNodeDto nextNode = new DeviceNodeDto();
        when(nodeRepo.findByUserId(1L)).thenReturn(List.of(firstRow), List.of(nextRow));
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deviceNodeMapper.toDto(firstRow)).thenAnswer(invocation -> {
            assertFalse(transactionActive.get(), "pure decoding must not retain a database transaction");
            decoding.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return firstNode;
        });
        when(deviceNodeMapper.toDto(nextRow)).thenReturn(nextNode);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> service.getSemanticSnapshot(1L));
            assertTrue(decoding.await(5, TimeUnit.SECONDS));
            var next = threads.submit(() -> service.getSemanticSnapshot(1L));
            assertSame(nextNode, next.get(5, TimeUnit.SECONDS).nodes().get(0));
            release.countDown();
            assertSame(firstNode, first.get(5, TimeUnit.SECONDS).nodes().get(0));
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
    }
}
