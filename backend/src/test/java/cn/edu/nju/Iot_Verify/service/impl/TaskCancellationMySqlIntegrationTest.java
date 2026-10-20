package cn.edu.nju.Iot_Verify.service.impl;

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
import cn.edu.nju.Iot_Verify.repository.VerificationTaskRepository;
import cn.edu.nju.Iot_Verify.service.board.MySqlAvailableCondition;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ExtendWith(MySqlAvailableCondition.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskCancellationMySqlIntegrationTest {

    @Autowired private VerificationTaskRepository verificationTasks;
    @Autowired private SimulationTaskRepository simulationTasks;
    @Autowired private FuzzTaskRepository fuzzTasks;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;

    enum TaskKind { VERIFICATION, SIMULATION, FUZZ }

    @ParameterizedTest
    @EnumSource(TaskKind.class)
    void cancellationReadsCommittedStatusAfterAnotherTransactionCancels(TaskKind kind) {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionTemplate competing = new TransactionTemplate(transactionManager);
        competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Operation operation = transactions.execute(status -> seedTask(kind));
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
                return new CancellationService(operation).cancel(9001L, operation.id());
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

    private Operation seedTask(TaskKind kind) {
        LocalDateTime now = verificationTasks.currentDatabaseTime();
        return switch (kind) {
            case VERIFICATION -> {
                Long id = verificationTasks.saveAndFlush(VerificationTaskPo.builder()
                        .userId(9001L).createdAt(now).progress(0)
                        .status(VerificationTaskPo.TaskStatus.PENDING).build()).getId();
                yield new Operation(id, "verification_task",
                        () -> verificationTasks.findByIdAndUserId(id, 9001L).map(TaskView.class::cast),
                        () -> verificationTasks.findByIdAndUserIdForUpdate(id, 9001L).map(TaskView.class::cast),
                        time -> verificationTasks.cancelTaskIfStillActive(id,
                                VerificationTaskPo.TaskStatus.CANCELLED, time, VerificationOutcome.INCONCLUSIVE,
                                List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING)),
                        () -> verificationTasks.deleteById(id));
            }
            case SIMULATION -> {
                Long id = simulationTasks.saveAndFlush(SimulationTaskPo.builder()
                        .userId(9001L).createdAt(now).progress(0)
                        .status(SimulationTaskPo.TaskStatus.PENDING).build()).getId();
                yield new Operation(id, "simulation_task",
                        () -> simulationTasks.findByIdAndUserId(id, 9001L).map(TaskView.class::cast),
                        () -> simulationTasks.findByIdAndUserIdForUpdate(id, 9001L).map(TaskView.class::cast),
                        time -> simulationTasks.cancelTaskIfStillActive(id, SimulationTaskPo.TaskStatus.CANCELLED,
                                time, List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING)),
                        () -> simulationTasks.deleteById(id));
            }
            case FUZZ -> {
                Long id = fuzzTasks.saveAndFlush(FuzzTaskPo.builder()
                        .userId(9001L).createdAt(now).progress(0).status(FuzzTaskPo.TaskStatus.PENDING)
                        .targetSpecIdsJson("[]").maxIterations(1).pathLength(2).populationSize(1)
                        .modelInputSnapshotJson("{}").modelSnapshotJson("{}").build()).getId();
                yield new Operation(id, "fuzz_task",
                        () -> fuzzTasks.findByIdAndUserId(id, 9001L).map(TaskView.class::cast),
                        () -> fuzzTasks.findByIdAndUserIdForUpdate(id, 9001L).map(TaskView.class::cast),
                        time -> fuzzTasks.cancelTaskIfStillActive(id, FuzzTaskPo.TaskStatus.CANCELLED,
                                time, List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING)),
                        () -> fuzzTasks.deleteById(id));
            }
        };
    }

    private record Operation(Long id, String table, Supplier<Optional<TaskView>> read,
                             Supplier<Optional<TaskView>> lock, Function<LocalDateTime, Integer> cancel,
                             Runnable delete) {}

    private static class CancellationService extends AbstractAsyncTaskService<TaskView> {
        private final Operation operation;

        private CancellationService(Operation operation) {
            super(new ObjectMapper(), "Task");
            this.operation = operation;
        }

        private TaskCancellationResultDto cancel(Long userId, Long taskId) {
            return cancelTask(userId, taskId);
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
