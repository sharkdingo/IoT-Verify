package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.po.TaskView;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.FuzzTaskRepository;
import cn.edu.nju.Iot_Verify.repository.SimulationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskLeaseUpdateMySqlIntegrationTest {

    private static final long WAIT_SECONDS = 10;
    private static final String WORKER_ID = "lease-test-worker";

    @Autowired private VerificationTaskRepository verificationTasks;
    @Autowired private SimulationTaskRepository simulationTasks;
    @Autowired private FuzzTaskRepository fuzzTasks;
    @Autowired private PlatformTransactionManager transactionManager;

    enum TaskKind { VERIFICATION, SIMULATION, FUZZ }

    static Stream<Arguments> operations() {
        return Stream.of(TaskKind.values()).flatMap(kind -> Stream.of(
                Arguments.of(kind, true), Arguments.of(kind, false)));
    }

    @ParameterizedTest(name = "{0}: start={1}")
    @MethodSource("operations")
    void originalLeaseExpiringDuringRealRowLockWaitRejectsUpdate(TaskKind kind, boolean start) throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        LocalDateTime expiry = verificationTasks.currentDatabaseTime().plusSeconds(3);
        Operation operation = transactions.execute(status -> seedTask(kind, start, expiry));
        assertNotNull(operation);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch workerReachedLock = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                operation.lock().get().orElseThrow();
                holderLocked.countDown();
                await(releaseHolder);
            }));
            await(holderLocked);
            var worker = executor.submit(() -> TaskLeaseRenewal.updateWithConfirmation(
                    transactions,
                    () -> {
                        workerReachedLock.countDown();
                        return operation.lock().get();
                    },
                    verificationTasks::currentDatabaseTime,
                    (task, now) -> operation.update().apply(now)));
            await(workerReachedLock);
            assertTrue(verificationTasks.currentDatabaseTime().isBefore(expiry),
                    "The worker must reach the lock while its original lease is still live");
            assertThrows(TimeoutException.class, () -> worker.get(100, TimeUnit.MILLISECONDS),
                    "The worker must actually wait for the other transaction's row lock");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (verificationTasks.currentDatabaseTime().isBefore(expiry)) {
                assertTrue(System.nanoTime() < deadline, "The database clock must reach the lease expiry");
                Thread.sleep(50);
            }
            releaseHolder.countDown();
            holder.get(WAIT_SECONDS, TimeUnit.SECONDS);
            TaskLeaseRenewal.LeaseUpdateResult result = worker.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertEquals(0, result.updated());
            assertEquals(Long.MIN_VALUE, result.confirmationStartedNanos());
            transactions.executeWithoutResult(status -> {
                TaskView persisted = operation.lock().get().orElseThrow();
                assertEquals(start ? "PENDING" : "RUNNING", persisted.getTaskStatusName());
                assertEquals(0, persisted.getProgress());
                assertEquals(expiry, persisted.getLeaseExpiresAt());
            });
        } finally {
            releaseHolder.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
            transactions.executeWithoutResult(status -> operation.delete().run());
        }
    }

    private Operation seedTask(TaskKind kind, boolean start, LocalDateTime expiry) {
        LocalDateTime now = verificationTasks.currentDatabaseTime();
        return switch (kind) {
            case VERIFICATION -> {
                Long id = verificationTasks.saveAndFlush(VerificationTaskPo.builder()
                        .userId(9001L).createdAt(now).progress(0).workerId(WORKER_ID).leaseExpiresAt(expiry)
                        .status(start ? VerificationTaskPo.TaskStatus.PENDING : VerificationTaskPo.TaskStatus.RUNNING)
                        .build()).getId();
                yield new Operation(
                        () -> verificationTasks.findByIdForUpdate(id).map(TaskView.class::cast),
                        time -> start
                                ? verificationTasks.startTaskIfStillPending(id, VerificationTaskPo.TaskStatus.RUNNING,
                                time, 0, "[]", VerificationTaskPo.TaskStatus.PENDING, WORKER_ID, time, time.plusMinutes(2))
                                : verificationTasks.updateProgressIfActive(id, 50, TaskProgressStage.EXECUTING_MODEL_CHECKER,
                                WORKER_ID, time),
                        () -> verificationTasks.deleteById(id));
            }
            case SIMULATION -> {
                Long id = simulationTasks.saveAndFlush(SimulationTaskPo.builder()
                        .userId(9001L).createdAt(now).progress(0).workerId(WORKER_ID).leaseExpiresAt(expiry)
                        .status(start ? SimulationTaskPo.TaskStatus.PENDING : SimulationTaskPo.TaskStatus.RUNNING)
                        .build()).getId();
                yield new Operation(
                        () -> simulationTasks.findByIdForUpdate(id).map(TaskView.class::cast),
                        time -> start
                                ? simulationTasks.startTaskIfStillPending(id, SimulationTaskPo.TaskStatus.RUNNING,
                                time, 0, "[]", SimulationTaskPo.TaskStatus.PENDING, WORKER_ID, time, time.plusMinutes(2))
                                : simulationTasks.updateProgressIfActive(id, 50, TaskProgressStage.EXECUTING_MODEL_CHECKER,
                                WORKER_ID, time),
                        () -> simulationTasks.deleteById(id));
            }
            case FUZZ -> {
                Long id = fuzzTasks.saveAndFlush(FuzzTaskPo.builder()
                        .userId(9001L).createdAt(now).progress(0).workerId(WORKER_ID).leaseExpiresAt(expiry)
                        .status(start ? FuzzTaskPo.TaskStatus.PENDING : FuzzTaskPo.TaskStatus.RUNNING)
                        .targetSpecIdsJson("[]").maxIterations(1).pathLength(2).populationSize(1)
                        .modelInputSnapshotJson("{}").modelSnapshotJson("{}")
                        .build()).getId();
                yield new Operation(
                        () -> fuzzTasks.findByIdForUpdate(id).map(TaskView.class::cast),
                        time -> start
                                ? fuzzTasks.startTaskIfStillPending(id, FuzzTaskPo.TaskStatus.RUNNING, time,
                                WORKER_ID, time, time.plusMinutes(2), "[]", FuzzTaskPo.TaskStatus.PENDING)
                                : fuzzTasks.updateProgressIfActive(id, 50, TaskProgressStage.EXPLORING_CANDIDATES,
                                WORKER_ID, time),
                        () -> fuzzTasks.deleteById(id));
            }
        };
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out coordinating task row lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted coordinating task row lock", e);
        }
    }

    private record Operation(Supplier<Optional<TaskView>> lock, Function<LocalDateTime, Integer> update,
                             Runnable delete) {
    }
}
