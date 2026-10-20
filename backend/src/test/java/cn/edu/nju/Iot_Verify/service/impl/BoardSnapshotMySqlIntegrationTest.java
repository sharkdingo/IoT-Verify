package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.po.BoardEnvironmentVariablePo;
import cn.edu.nju.Iot_Verify.po.DeviceNodePo;
import cn.edu.nju.Iot_Verify.po.DeviceNodeId;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.*;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
class BoardSnapshotMySqlIntegrationTest {
    @Autowired private DeviceNodeRepository nodes;
    @Autowired private BoardEnvironmentVariableRepository environment;
    @Autowired private SpecificationRepository specifications;
    @Autowired private RuleRepository rules;
    @Autowired private BoardLayoutRepository layouts;
    @Autowired private DeviceTemplateRepository templates;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCapturesShareOwnershipAndKeepEveryCollectionAheadOfTheNextWrite() throws Exception {
        var transaction = new TransactionTemplate(transactions);
        Long userId = createBoard(transaction);
        var bothReading = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        DeviceNodeRepository observedNodes = mock(DeviceNodeRepository.class);
        when(observedNodes.findByUserId(userId)).thenAnswer(invocation -> {
            var captured = nodes.findByUserId(userId);
            bothReading.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return captured;
        });
        var service = service(observedNodes, transaction);
        var threads = Executors.newFixedThreadPool(3);
        try {
            var first = threads.submit(() -> service.getSemanticSnapshot(userId));
            var second = threads.submit(() -> service.getSemanticSnapshot(userId));
            assertTrue(bothReading.await(5, TimeUnit.SECONDS), "same-user captures must overlap");
            var writerStarted = new CountDownLatch(1);
            var edit = threads.submit(() -> transaction.executeWithoutResult(status -> {
                writerStarted.countDown();
                users.findByIdForUpdate(userId).orElseThrow();
                var node = nodes.findById(new DeviceNodeId("light", userId)).orElseThrow();
                node.setLabel("after");
                nodes.saveAndFlush(node);
                var variable = environment.findByUserIdOrderByNameAsc(userId).get(0);
                variable.setValue("after");
                environment.saveAndFlush(variable);
            }));
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> edit.get(200, TimeUnit.MILLISECONDS),
                    "a writer must wait until both captures release shared user-row ownership");
            release.countDown();
            for (var capture : java.util.List.of(first, second)) {
                var snapshot = capture.get(5, TimeUnit.SECONDS);
                assertEquals("before", snapshot.nodes().get(0).getLabel());
                assertEquals("before", snapshot.environmentVariables().get(0).getValue());
            }
            edit.get(5, TimeUnit.SECONDS);
            var next = service.getSemanticSnapshot(userId);
            assertEquals("after", next.nodes().get(0).getLabel());
            assertEquals("after", next.environmentVariables().get(0).getValue());
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
            deleteBoard(transaction, userId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void snapshotsInsideCallerTransactionsKeepExclusiveOwnershipAndReadTheirOwnChanges() throws Exception {
        var transaction = new TransactionTemplate(transactions);
        Long userId = createBoard(transaction);
        // Independent service instances cannot accidentally pass through the same JVM stripe.
        var firstService = service(nodes, transaction);
        var secondService = service(nodes, transaction);
        var captured = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> transaction.executeWithoutResult(status -> {
                var node = nodes.findById(new DeviceNodeId("light", userId)).orElseThrow();
                node.setLabel("uncommitted");
                nodes.saveAndFlush(node);
                assertEquals("uncommitted", firstService.getSemanticSnapshot(userId).nodes().get(0).getLabel());
                captured.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                status.setRollbackOnly();
            }));
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            var secondStarted = new CountDownLatch(1);
            var second = threads.submit(() -> transaction.execute(status -> {
                secondStarted.countDown();
                return secondService.getSemanticSnapshot(userId);
            }));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals("before", second.get(5, TimeUnit.SECONDS).nodes().get(0).getLabel());
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
            deleteBoard(transaction, userId);
        }
    }

    private BoardStorageServiceImpl service(DeviceNodeRepository nodeRepository, TransactionTemplate transaction) {
        return new BoardStorageServiceImpl(nodeRepository, environment, specifications, rules, layouts,
                templates, null, transaction, null, new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(),
                null, new DeviceTemplateMapper(), null, users, null);
    }

    private Long createBoard(TransactionTemplate transaction) {
        return transaction.execute(status -> {
            var user = users.saveAndFlush(UserPo.builder()
                    .phone("139" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000)))
                    .username("snapshot_" + UUID.randomUUID().toString().substring(0, 8))
                    .password("unused-test-password").build());
            nodes.saveAndFlush(DeviceNodePo.builder()
                    .id("light").userId(user.getId()).templateName("Light").label("before")
                    .posX(100D).posY(100D).width(160).height(128).state("off")
                    .currentStateTrust("trusted").currentStatePrivacy("public")
                    .variablesJson("[]").privaciesJson("[]").build());
            environment.saveAndFlush(BoardEnvironmentVariablePo.builder().userId(user.getId()).name("phase")
                    .value("before").trust("trusted").privacy("public").build());
            return user.getId();
        });
    }

    private void deleteBoard(TransactionTemplate transaction, Long userId) {
        transaction.executeWithoutResult(status -> {
            nodes.deleteByUserId(userId);
            environment.deleteByUserId(userId);
            users.deleteById(userId);
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void capturedRowsRemainStableWhileALaterEditCommitsDuringDecoding() throws Exception {
        var transaction = new TransactionTemplate(transactions);
        UserPo user = transaction.execute(status -> users.saveAndFlush(UserPo.builder()
                .phone("139" + String.format("%08d", Math.floorMod(System.nanoTime(), 100_000_000)))
                .username("snapshot_" + UUID.randomUUID().toString().substring(0, 8))
                .password("unused-test-password").build()));
        Long userId = user.getId();
        transaction.executeWithoutResult(status -> nodes.saveAndFlush(DeviceNodePo.builder()
                .id("light").userId(userId).templateName("Light").label("before")
                .posX(100D).posY(100D).width(160).height(128).state("off")
                .currentStateTrust("trusted").currentStatePrivacy("public")
                .variablesJson("[]").privaciesJson("[]").build()));
        var decoding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var mapper = new DeviceNodeMapper() {
            @Override
            public DeviceNodeDto toDto(DeviceNodePo row) {
                if ("before".equals(row.getLabel())) {
                    decoding.countDown();
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                }
                return super.toDto(row);
            }
        };
        var service = new BoardStorageServiceImpl(nodes, environment, specifications, rules, layouts,
                templates, null, transaction, null, new SpecificationMapper(), new RuleMapper(), mapper,
                null, new DeviceTemplateMapper(), null, users, null);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var captured = threads.submit(() -> service.getSemanticSnapshot(userId));
            assertTrue(decoding.await(5, TimeUnit.SECONDS));
            var edit = threads.submit(() -> transaction.executeWithoutResult(status -> {
                users.findByIdForUpdate(userId).orElseThrow();
                var row = nodes.findById(new DeviceNodeId("light", userId)).orElseThrow();
                row.setLabel("after");
                nodes.saveAndFlush(row);
            }));
            edit.get(5, TimeUnit.SECONDS);
            var next = threads.submit(() -> service.getSemanticSnapshot(userId));
            assertEquals("after", next.get(5, TimeUnit.SECONDS).nodes().get(0).getLabel());
            release.countDown();
            assertEquals("before", captured.get(5, TimeUnit.SECONDS).nodes().get(0).getLabel());
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
            transaction.executeWithoutResult(status -> {
                nodes.deleteByUserId(userId);
                users.deleteById(userId);
            });
        }
    }
}
