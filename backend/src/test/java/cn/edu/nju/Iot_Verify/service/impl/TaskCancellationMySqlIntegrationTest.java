package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.configure.DatabaseSchemaConstraints;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationOutcome;
import cn.edu.nju.Iot_Verify.dto.model.TaskCancellationResultDto;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.po.TaskView;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.repository.FuzzTaskRepository;
import cn.edu.nju.Iot_Verify.repository.SimulationTaskRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;
import static cn.edu.nju.Iot_Verify.service.impl.AbstractAsyncTaskService.LocalExecutionStopResult.STOPPED_BEFORE_START;
import static cn.edu.nju.Iot_Verify.service.impl.AbstractAsyncTaskService.LocalExecutionStopResult.STOP_REQUESTED;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
@Import(DatabaseSchemaConstraints.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskCancellationMySqlIntegrationTest {

    private static final int LOCK_WAIT_SECONDS = 5;
    private static final String WORKER_ID = "cancellation-it-worker";
    private static final int NEXT_WORKER_PROGRESS = 32;

    @Autowired private UserRepository users;
    @Autowired private VerificationTaskRepository verificationTasks;
    @Autowired private SimulationTaskRepository simulationTasks;
    @Autowired private FuzzTaskRepository fuzzTasks;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    private Long ownerId;

    @BeforeEach
    void createOwner() {
        ownerId = TaskMySqlTestUsers.createOwner(users);
    }

    @AfterEach
    void deleteOwner() {
        if (ownerId != null) users.deleteById(ownerId);
    }

    enum TaskKind { VERIFICATION, SIMULATION, FUZZ }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    void cancellationReadsCommittedStatusAfterAnotherTransactionCancels(TaskKind kind) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionTemplate competing = new TransactionTemplate(transactionManager);
        competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Operation operation = transactions.execute(status -> seedTask(kind, null));
        assertNotNull(operation);
        try {
            TaskCancellationResultDto result = transactions.execute(status -> {
                // Establish an old transaction snapshot without caching the task entity. The worker
                // then commits on a different connection, as it can after seeing a local cancel marker.
                assertEquals("PENDING", entityManager.createNativeQuery(
                                "SELECT status FROM " + operation.table() + " WHERE id = :id")
                        .setParameter("id", operation.id()).getSingleResult());
                competing.executeWithoutResult(worker -> assertEquals(1,
                        operation.cancel().apply(verificationTasks.currentDatabaseTime())));
                return new CancellationService(operation).cancel(ownerId, operation.id());
            });
            assertNotNull(result);
            assertFalse(result.isCancellationAccepted());
            assertEquals(TaskCancellationOutcome.ALREADY_CANCELLED, result.getCancellationOutcome());
            assertEquals("CANCELLED", result.getTaskStatus());
            assertFalse(result.isExecutionMayStillBeStopping());
            transactions.executeWithoutResult(status -> assertTrue(operation.lock().get()
                    .orElseThrow().isCancelledStatus()));
        } finally {
            transactions.executeWithoutResult(status -> operation.delete().run());
        }
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    void queuedCancellationStopsOnceAfterCommitAndFinalizesItsReceipt(TaskKind kind) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Operation queued = transactions.execute(status -> seedTask(kind, null));
        assertNotNull(queued);
        List<String> statesObservedByStop = new ArrayList<>();
        CancellationService service = observeCancellation(queued, STOPPED_BEFORE_START, statesObservedByStop);
        try {
            TaskCancellationResultDto result = transactions.execute(status -> {
                TaskCancellationResultDto receipt = service.cancel(ownerId, queued.id());
                assertAll(
                        () -> assertTrue(statesObservedByStop.isEmpty(), "Stopping a queued worker must wait for commit"),
                        () -> assertFalse(service.isLocalStopRequested(queued.id()), "The queued Future must remain available until commit"));
                return receipt;
            });

            assertNotNull(result);
            assertEquals(queued.id(), result.getTaskId());
            assertTrue(result.isCancellationAccepted());
            assertEquals(TaskCancellationOutcome.ACCEPTED, result.getCancellationOutcome());
            assertEquals("CANCELLED", result.getTaskStatus());
            assertFalse(result.isExecutionMayStillBeStopping());
            assertEquals(List.of("CANCELLED"), statesObservedByStop);
            assertTrue(service.isLocalStopRequested(queued.id()));
        } finally {
            transactions.executeWithoutResult(status -> queued.delete().run());
        }
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    void rolledBackCancellationLeavesWorkerRunningUntilLaterCommittedCancellation(TaskKind kind) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        LocalDateTime lease = verificationTasks.currentDatabaseTime().plusMinutes(5);
        Operation running = transactions.execute(status -> seedTask(kind, lease));
        assertNotNull(running);
        List<String> statesObservedByStop = new ArrayList<>();
        CancellationService service = observeCancellation(running, STOP_REQUESTED, statesObservedByStop);
        try {
            Boolean stoppedBeforeRollback = transactions.execute(status -> {
                service.cancel(ownerId, running.id());
                status.setRollbackOnly();
                return service.isLocalStopRequested(running.id());
            });
            TaskView persisted = running.read().get().orElseThrow();
            assertAll(
                    () -> assertTrue(statesObservedByStop.isEmpty(), "Rollback must not stop the running worker"),
                    () -> assertEquals(Boolean.FALSE, stoppedBeforeRollback),
                    () -> assertFalse(service.isLocalStopRequested(running.id()), "The worker must be able to proceed after rollback"),
                    () -> assertEquals("RUNNING", persisted.getTaskStatusName()),
                    () -> assertEquals(17, persisted.getProgress()),
                    () -> assertEquals(lease, persisted.getLeaseExpiresAt()));

            assertEquals(1, TaskLeaseRenewal.updateWithConfirmation(transactions, running.lock(),
                    verificationTasks::currentDatabaseTime, (task, now) -> running.advanceProgress().apply(now)).updated());
            assertEquals(NEXT_WORKER_PROGRESS, running.read().get().orElseThrow().getProgress());

            TaskCancellationResultDto result = transactions.execute(status -> service.cancel(ownerId, running.id()));
            assertNotNull(result);
            assertTrue(result.isCancellationAccepted());
            assertEquals(TaskCancellationOutcome.ACCEPTED, result.getCancellationOutcome());
            assertEquals("CANCELLED", result.getTaskStatus());
            assertTrue(result.isExecutionMayStillBeStopping());
            assertEquals(List.of("CANCELLED"), statesObservedByStop);
            assertTrue(service.isLocalStopRequested(running.id()));
        } finally {
            transactions.executeWithoutResult(status -> running.delete().run());
        }
    }

    private CancellationService observeCancellation(Operation operation,
                                                     AbstractAsyncTaskService.LocalExecutionStopResult stopResult,
                                                     List<String> statesObservedByStop) {
        TransactionTemplate reader = new TransactionTemplate(transactionManager);
        reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new CancellationService(operation, stopResult, taskId -> {
            assertEquals(operation.id(), taskId);
            // An independent transaction observes commit ordering even while Spring runs after-commit callbacks.
            statesObservedByStop.add(reader.execute(status -> operation.read().get().orElseThrow().getTaskStatusName()));
        });
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    void expirySweepDoesNotWaitOnAnUnrelatedLiveCancellationRow(TaskKind kind) throws InterruptedException {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        LocalDateTime cutoff = verificationTasks.currentDatabaseTime();
        Operation live = transactions.execute(status -> seedTask(kind, cutoff.plusMinutes(5)));
        Operation expired = transactions.execute(status -> seedTask(kind, cutoff));
        assertNotNull(live);
        assertNotNull(expired);
        var executor = Executors.newSingleThreadExecutor();
        try {
            TaskCancellationResultDto result = transactions.execute(status -> {
                assertEquals("RUNNING", live.lock().get().orElseThrow().getTaskStatusName());
                ExpirySweep sweep = expirySweep(kind, cutoff);
                var recovery = executor.submit(() -> sweep.failCandidates().applyAsInt(sweep.findCandidates().get()));
                assertEquals(1, assertDoesNotThrow(() -> recovery.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS),
                        "Expiry recovery must not wait on the unrelated, live cancellation row"));
                return new CancellationService(live).cancel(ownerId, live.id());
            });
            assertNotNull(result);
            assertTrue(result.isCancellationAccepted());
            assertEquals(TaskCancellationOutcome.ACCEPTED, result.getCancellationOutcome());
            assertEquals(live.id(), result.getTaskId());
            assertEquals("CANCELLED", result.getTaskStatus());
            assertEquals("CANCELLED", live.read().get().orElseThrow().getTaskStatusName());
            TaskView failed = expired.read().get().orElseThrow();
            assertEquals("FAILED", failed.getTaskStatusName());
            assertEquals(17, failed.getProgress());
            assertNull(failed.getLeaseExpiresAt());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(LOCK_WAIT_SECONDS, TimeUnit.SECONDS));
            transactions.executeWithoutResult(status -> {
                live.delete().run();
                expired.delete().run();
            });
        }
    }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    void expirySweepIgnoresCandidatesRenewedOrDeletedAfterSelection(TaskKind kind) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        LocalDateTime cutoff = verificationTasks.currentDatabaseTime();
        Operation renewed = transactions.execute(status -> seedTask(kind, cutoff));
        Operation deleted = transactions.execute(status -> seedTask(kind, cutoff));
        assertNotNull(renewed);
        assertNotNull(deleted);
        try {
            ExpirySweep sweep = expirySweep(kind, cutoff);
            List<Long> candidates = sweep.findCandidates().get();
            assertEquals(List.of(renewed.id(), deleted.id()), candidates);
            LocalDateTime newLease = cutoff.plusMinutes(5);
            transactions.executeWithoutResult(status -> {
                renewed.lock().get().orElseThrow().setLeaseExpiresAt(newLease);
                deleted.delete().run();
            });

            assertEquals(0, sweep.failCandidates().applyAsInt(candidates));
            TaskView running = renewed.read().get().orElseThrow();
            assertEquals("RUNNING", running.getTaskStatusName());
            assertEquals(17, running.getProgress());
            assertEquals(newLease, running.getLeaseExpiresAt());
            assertTrue(deleted.read().get().isEmpty());
        } finally {
            transactions.executeWithoutResult(status -> {
                renewed.delete().run();
                deleted.delete().run();
            });
        }
    }

    private ExpirySweep expirySweep(TaskKind kind, LocalDateTime cutoff) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return switch (kind) {
            case VERIFICATION -> {
                var active = List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING);
                yield new ExpirySweep(() -> verificationTasks.findExpiredActiveTaskIds(active, cutoff),
                        candidates -> TaskLeaseRenewal.failExpiredTasks(transactions, candidates,
                                verificationTasks::findByIdForUpdate,
                                id -> verificationTasks.failExpiredTask(id, VerificationTaskPo.TaskStatus.FAILED,
                                        cutoff, VerificationOutcome.INCONCLUSIVE, "Expired lease", "[]", active, cutoff)));
            }
            case SIMULATION -> {
                var active = List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING);
                yield new ExpirySweep(() -> simulationTasks.findExpiredActiveTaskIds(active, cutoff),
                        candidates -> TaskLeaseRenewal.failExpiredTasks(transactions, candidates,
                                simulationTasks::findByIdForUpdate,
                                id -> simulationTasks.failExpiredTask(id, SimulationTaskPo.TaskStatus.FAILED,
                                        cutoff, "Expired lease", "[]", active, cutoff)));
            }
            case FUZZ -> {
                var active = List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING);
                yield new ExpirySweep(() -> fuzzTasks.findExpiredActiveTaskIds(active, cutoff),
                        candidates -> TaskLeaseRenewal.failExpiredTasks(transactions, candidates,
                                fuzzTasks::findByIdForUpdate,
                                id -> fuzzTasks.failExpiredTask(id, FuzzTaskPo.TaskStatus.FAILED,
                                        cutoff, "Expired lease", "[]", active, cutoff)));
            }
        };
    }

    private Operation seedTask(TaskKind kind, LocalDateTime lease) {
        LocalDateTime now = verificationTasks.currentDatabaseTime();
        int progress = lease == null ? 0 : 17;
        String worker = lease == null ? null : WORKER_ID;
        return switch (kind) {
            case VERIFICATION -> {
                Long id = verificationTasks.saveAndFlush(VerificationTaskPo.builder()
                        .userId(ownerId).createdAt(now).progress(progress).workerId(worker).leaseExpiresAt(lease)
                        .status(lease == null ? VerificationTaskPo.TaskStatus.PENDING : VerificationTaskPo.TaskStatus.RUNNING)
                        .build()).getId();
                yield new Operation(id, "verification_task",
                        () -> verificationTasks.findByIdAndUserId(id, ownerId).map(TaskView.class::cast),
                        () -> verificationTasks.findByIdAndUserIdForUpdate(id, ownerId).map(TaskView.class::cast),
                        time -> verificationTasks.cancelTaskIfStillActive(id,
                                VerificationTaskPo.TaskStatus.CANCELLED, time, VerificationOutcome.INCONCLUSIVE,
                                List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING)),
                        time -> verificationTasks.updateProgressIfActive(id, NEXT_WORKER_PROGRESS,
                                TaskProgressStage.EXECUTING_MODEL_CHECKER, WORKER_ID, time),
                        () -> verificationTasks.deleteById(id));
            }
            case SIMULATION -> {
                Long id = simulationTasks.saveAndFlush(SimulationTaskPo.builder()
                        .userId(ownerId).createdAt(now).progress(progress).workerId(worker).leaseExpiresAt(lease)
                        .status(lease == null ? SimulationTaskPo.TaskStatus.PENDING : SimulationTaskPo.TaskStatus.RUNNING)
                        .build()).getId();
                yield new Operation(id, "simulation_task",
                        () -> simulationTasks.findByIdAndUserId(id, ownerId).map(TaskView.class::cast),
                        () -> simulationTasks.findByIdAndUserIdForUpdate(id, ownerId).map(TaskView.class::cast),
                        time -> simulationTasks.cancelTaskIfStillActive(id, SimulationTaskPo.TaskStatus.CANCELLED,
                                time, List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING)),
                        time -> simulationTasks.updateProgressIfActive(id, NEXT_WORKER_PROGRESS,
                                TaskProgressStage.RUNNING_SIMULATION, WORKER_ID, time),
                        () -> simulationTasks.deleteById(id));
            }
            case FUZZ -> {
                Long id = fuzzTasks.saveAndFlush(FuzzTaskPo.builder()
                        .userId(ownerId).createdAt(now).progress(progress).workerId(worker).leaseExpiresAt(lease)
                        .status(lease == null ? FuzzTaskPo.TaskStatus.PENDING : FuzzTaskPo.TaskStatus.RUNNING)
                        .targetSpecIdsJson("[]").maxIterations(1).pathLength(2).populationSize(1)
                        .modelInputSnapshotJson("{}").modelSnapshotJson("{}").build()).getId();
                yield new Operation(id, "fuzz_task",
                        () -> fuzzTasks.findByIdAndUserId(id, ownerId).map(TaskView.class::cast),
                        () -> fuzzTasks.findByIdAndUserIdForUpdate(id, ownerId).map(TaskView.class::cast),
                        time -> fuzzTasks.cancelTaskIfStillActive(id, FuzzTaskPo.TaskStatus.CANCELLED,
                                time, List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING)),
                        time -> fuzzTasks.updateProgressIfActive(id, NEXT_WORKER_PROGRESS,
                                TaskProgressStage.EXPLORING_CANDIDATES, WORKER_ID, time),
                        () -> fuzzTasks.deleteById(id));
            }
        };
    }

    private record Operation(Long id, String table, Supplier<Optional<TaskView>> read,
                             Supplier<Optional<TaskView>> lock, Function<LocalDateTime, Integer> cancel,
                             Function<LocalDateTime, Integer> advanceProgress, Runnable delete) {}

    private record ExpirySweep(Supplier<List<Long>> findCandidates, ToIntFunction<List<Long>> failCandidates) {}

    private static class CancellationService extends AbstractAsyncTaskService<TaskView> {
        private final Operation operation;
        private final LocalExecutionStopResult stopResult;
        private final Consumer<Long> stopObserver;
        private final FutureTask<Void> execution = new FutureTask<>(() -> null);

        private CancellationService(Operation operation) {
            this(operation, LocalExecutionStopResult.NONE, taskId -> {});
        }

        private CancellationService(Operation operation, LocalExecutionStopResult stopResult,
                                    Consumer<Long> stopObserver) {
            super(new ObjectMapper(), "Task");
            this.operation = operation;
            this.stopResult = stopResult;
            this.stopObserver = stopObserver;
        }

        private TaskCancellationResultDto cancel(Long userId, Long taskId) {
            return cancelTask(userId, taskId);
        }

        @Override
        protected LocalExecutionStopResult stopLocalExecution(Long taskId) {
            stopObserver.accept(taskId);
            if (stopResult != LocalExecutionStopResult.NONE) {
                execution.cancel(stopResult == STOP_REQUESTED);
            }
            return stopResult;
        }

        @Override
        protected boolean isLocalStopRequested(Long taskId) {
            return operation.id().equals(taskId) && execution.isCancelled();
        }

        @Override
        protected Optional<TaskView> findTaskByIdAndUserId(Long id, Long userId) {
            return operation.read().get();
        }

        @Override
        protected Optional<TaskView> findTaskByIdAndUserIdForUpdate(Long id, Long userId) {
            return operation.lock().get();
        }

        @Override
        protected int atomicCancelTask(Long taskId, LocalDateTime completedAt) {
            return operation.cancel().apply(completedAt);
        }

        @Override
        protected int atomicUpdateProgress(Long taskId, int progress, TaskProgressStage stage) {
            throw new UnsupportedOperationException();
        }
    }
}
