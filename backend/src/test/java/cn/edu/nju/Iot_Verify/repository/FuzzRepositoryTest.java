package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzExplorationMode;
import cn.edu.nju.Iot_Verify.dto.fuzz.FuzzOutcome;
import cn.edu.nju.Iot_Verify.dto.model.TaskProgressStage;
import cn.edu.nju.Iot_Verify.po.FuzzFindingPo;
import cn.edu.nju.Iot_Verify.po.FuzzTaskPo;
import cn.edu.nju.Iot_Verify.po.SimulationTaskPo;
import cn.edu.nju.Iot_Verify.po.VerificationTaskPo;
import cn.edu.nju.Iot_Verify.dto.verification.VerificationOutcome;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzFindingSummaryProjection;
import cn.edu.nju.Iot_Verify.repository.projection.FuzzTaskSummaryProjection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class FuzzRepositoryTest {

    @Autowired private FuzzTaskRepository taskRepository;
    @Autowired private FuzzFindingRepository findingRepository;
    @Autowired private ChatSessionRepository chatSessionRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private VerificationTaskRepository verificationTaskRepository;
    @Autowired private SimulationTaskRepository simulationTaskRepository;

    @Test
    void currentDatabaseTimeProvidesTheSharedLeaseClock() {
        LocalDateTime fuzzTime = taskRepository.currentDatabaseTime();
        LocalDateTime verificationTime = verificationTaskRepository.currentDatabaseTime();
        LocalDateTime simulationTime = simulationTaskRepository.currentDatabaseTime();

        assertNotNull(fuzzTime);
        assertNotNull(verificationTime);
        assertNotNull(simulationTime);
        assertTrue(!verificationTime.isBefore(fuzzTime));
        assertTrue(!simulationTime.isBefore(verificationTime));
    }

    @Test
    void verificationAndSimulationRecoveryOnlyFailsExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime activeUntil = now.plusMinutes(2);
        List<VerificationTaskPo.TaskStatus> verificationActive = List.of(
                VerificationTaskPo.TaskStatus.PENDING,
                VerificationTaskPo.TaskStatus.RUNNING);
        List<SimulationTaskPo.TaskStatus> simulationActive = List.of(
                SimulationTaskPo.TaskStatus.PENDING,
                SimulationTaskPo.TaskStatus.RUNNING);

        // Partway through on purpose: the sweep must preserve this, not overwrite it with 100.
        VerificationTaskPo expiredVerification = verificationTaskRepository.saveAndFlush(
                VerificationTaskPo.builder()
                        .userId(21L)
                        .status(VerificationTaskPo.TaskStatus.PENDING)
                        .createdAt(now.minusMinutes(3))
                        .progress(35)
                        .workerId("old-verification-worker")
                        .leaseExpiresAt(now.minusSeconds(1))
                        .build());
        VerificationTaskPo activeVerification = verificationTaskRepository.saveAndFlush(
                VerificationTaskPo.builder()
                        .userId(21L)
                        .status(VerificationTaskPo.TaskStatus.PENDING)
                        .createdAt(now)
                        .progress(0)
                        .workerId("active-verification-worker")
                        .leaseExpiresAt(activeUntil)
                        .build());

        assertEquals(1, verificationTaskRepository.failExpiredActiveTasks(
                VerificationTaskPo.TaskStatus.FAILED,
                now,
                VerificationOutcome.INCONCLUSIVE,
                "expired",
                "[]",
                verificationActive,
                now));
        VerificationTaskPo sweptVerification = verificationTaskRepository
                .findById(expiredVerification.getId()).orElseThrow();
        assertEquals(VerificationTaskPo.TaskStatus.FAILED, sweptVerification.getStatus());
        // An abandoned run must not claim finished work: the sweep previously wrote progress = 100,
        // so a caller reading the task DTO saw 100% beside FAILED. The last heartbeat is the truth.
        assertEquals(35, sweptVerification.getProgress());
        assertEquals(VerificationTaskPo.TaskStatus.PENDING,
                verificationTaskRepository.findById(activeVerification.getId()).orElseThrow().getStatus());
        assertEquals(0, verificationTaskRepository.startTaskIfStillPending(
                activeVerification.getId(), VerificationTaskPo.TaskStatus.RUNNING, now,
                0, "[]", VerificationTaskPo.TaskStatus.PENDING,
                "wrong-worker", now, activeUntil));
        assertEquals(1, verificationTaskRepository.startTaskIfStillPending(
                activeVerification.getId(), VerificationTaskPo.TaskStatus.RUNNING, now,
                0, "[]", VerificationTaskPo.TaskStatus.PENDING,
                "active-verification-worker", now, activeUntil));

        SimulationTaskPo expiredSimulation = simulationTaskRepository.saveAndFlush(
                SimulationTaskPo.builder()
                        .userId(22L)
                        .status(SimulationTaskPo.TaskStatus.RUNNING)
                        .createdAt(now.minusMinutes(3))
                        .startedAt(now.minusMinutes(3))
                        .requestedSteps(5)
                        .progress(50)
                        .workerId("old-simulation-worker")
                        .leaseExpiresAt(now.minusSeconds(1))
                        .build());
        SimulationTaskPo activeSimulation = simulationTaskRepository.saveAndFlush(
                SimulationTaskPo.builder()
                        .userId(22L)
                        .status(SimulationTaskPo.TaskStatus.RUNNING)
                        .createdAt(now)
                        .startedAt(now)
                        .requestedSteps(5)
                        .progress(50)
                        .workerId("active-simulation-worker")
                        .leaseExpiresAt(activeUntil)
                        .build());

        assertEquals(1, simulationTaskRepository.failExpiredActiveTasks(
                SimulationTaskPo.TaskStatus.FAILED,
                now,
                "expired",
                "[]",
                simulationActive,
                now));
        assertEquals(SimulationTaskPo.TaskStatus.FAILED,
                simulationTaskRepository.findById(expiredSimulation.getId()).orElseThrow().getStatus());
        assertEquals(SimulationTaskPo.TaskStatus.RUNNING,
                simulationTaskRepository.findById(activeSimulation.getId()).orElseThrow().getStatus());
    }

    @Test
    void expiredWorkersCannotCommitTerminalTaskResultsBeforeRecoveryRuns() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiredAt = now.minusSeconds(1);

        VerificationTaskPo verification = verificationTaskRepository.saveAndFlush(
                VerificationTaskPo.builder()
                        .userId(41L)
                        .status(VerificationTaskPo.TaskStatus.RUNNING)
                        .createdAt(now.minusMinutes(1))
                        .startedAt(now.minusMinutes(1))
                        .progress(90)
                        .workerId("expired-verification-worker")
                        .leaseExpiresAt(expiredAt)
                        .build());
        assertEquals(0, verificationTaskRepository.completeTaskIfRunning(
                verification.getId(), VerificationTaskPo.TaskStatus.COMPLETED, now,
                VerificationOutcome.SATISFIED, 0, 0, 0,
                // nusmvOutput, smvModelContent, errorMessage, processingTimeMs
                "[]", "[]", "[]", "", "MODULE main", null, 1L,
                VerificationTaskPo.TaskStatus.RUNNING,
                "expired-verification-worker", now));
        assertEquals(0, verificationTaskRepository.failTaskIfActive(
                verification.getId(), VerificationTaskPo.TaskStatus.FAILED, now,
                VerificationOutcome.INCONCLUSIVE, "late failure", "[]", 1L,
                List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING),
                "expired-verification-worker", now));

        SimulationTaskPo simulation = simulationTaskRepository.saveAndFlush(
                SimulationTaskPo.builder()
                        .userId(42L)
                        .status(SimulationTaskPo.TaskStatus.RUNNING)
                        .createdAt(now.minusMinutes(1))
                        .startedAt(now.minusMinutes(1))
                        .requestedSteps(5)
                        .progress(90)
                        .workerId("expired-simulation-worker")
                        .leaseExpiresAt(expiredAt)
                        .build());
        assertEquals(0, simulationTaskRepository.completeTaskIfRunning(
                simulation.getId(), SimulationTaskPo.TaskStatus.COMPLETED, now,
                5, 99L, null, "[]", "[]", 1L,
                SimulationTaskPo.TaskStatus.RUNNING,
                "expired-simulation-worker", now));
        assertEquals(0, simulationTaskRepository.failTaskIfActive(
                simulation.getId(), SimulationTaskPo.TaskStatus.FAILED, now,
                "late failure", "[]", 1L,
                List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING),
                "expired-simulation-worker", now));

        FuzzTaskPo fuzz = taskForLease("expired-fuzz-worker", expiredAt);
        fuzz.setStatus(FuzzTaskPo.TaskStatus.RUNNING);
        fuzz.setStartedAt(now.minusMinutes(1));
        fuzz = taskRepository.saveAndFlush(fuzz);
        assertEquals(0, taskRepository.completeTaskIfRunning(
                fuzz.getId(), FuzzTaskPo.TaskStatus.COMPLETED, now, 1L,
                FuzzOutcome.BUDGET_EXHAUSTED, 7L, 1, 1L, 1L,
                "{\"eligibleSpecIds\":[],\"ineligibleSpecs\":[],\"requestedSpecCount\":0,\"eligibleSpecCount\":0}",
                "[]", 0, "[]", FuzzTaskPo.TaskStatus.RUNNING,
                "expired-fuzz-worker", now));
        assertEquals(0, taskRepository.failTaskIfActive(
                fuzz.getId(), FuzzTaskPo.TaskStatus.FAILED, now, 1L,
                "late failure", "[]",
                List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING),
                "expired-fuzz-worker", now));

        assertEquals(VerificationTaskPo.TaskStatus.RUNNING,
                verificationTaskRepository.findById(verification.getId()).orElseThrow().getStatus());
        assertEquals(SimulationTaskPo.TaskStatus.RUNNING,
                simulationTaskRepository.findById(simulation.getId()).orElseThrow().getStatus());
        assertEquals(FuzzTaskPo.TaskStatus.RUNNING,
                taskRepository.findById(fuzz.getId()).orElseThrow().getStatus());
    }

    @Test
    void expiredWorkersCannotUpdateProgress() {
        LocalDateTime now = LocalDateTime.now();
        FuzzTaskPo task = taskForLease("expired-progress-worker", now.minusSeconds(1));
        task.setStatus(FuzzTaskPo.TaskStatus.RUNNING);
        task.setProgress(10);
        task = taskRepository.saveAndFlush(task);

        assertEquals(0, taskRepository.updateProgressIfActive(
                task.getId(), 80, TaskProgressStage.PERSISTING_RESULT,
                "expired-progress-worker", now));
        FuzzTaskPo persisted = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(10, persisted.getProgress());
        assertEquals(FuzzTaskPo.TaskStatus.RUNNING, persisted.getStatus());
    }

    @Test
    void accountCleanupBulkDeleteFlushesPendingDerivedDeletesBeforeClearingPersistenceContext() {
        ChatSessionPo session = new ChatSessionPo();
        session.setId("account-cleanup-session");
        session.setUserId(71L);
        chatSessionRepository.saveAndFlush(session);

        ChatMessagePo message = new ChatMessagePo();
        message.setSessionId(session.getId());
        message.setRole("user");
        message.setContent("pending removal");
        chatMessageRepository.saveAndFlush(message);

        findingRepository.saveAndFlush(FuzzFindingPo.builder()
                .userId(71L)
                .fuzzTaskId(901L)
                .violatedSpecId("spec-cleanup")
                .violatedSpecJson("{}")
                .firstViolationStep(0)
                .statesJson("[]")
                .inputEventsJson("[]")
                .seed(1L)
                .stateCount(0)
                .createdAt(LocalDateTime.now())
                .build());

        chatMessageRepository.deleteBySessionIdIn(List.of(session.getId()));
        chatSessionRepository.deleteByUserId(71L);
        assertEquals(1, findingRepository.deleteByUserId(71L));

        assertTrue(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).isEmpty());
        assertTrue(chatSessionRepository.findByIdAndUserId(session.getId(), 71L).isEmpty());
    }

    @Test
    void terminalTransitionsAreAtomicAndFindingsRemainUserScoped() {
        LocalDateTime leaseExpiresAt = LocalDateTime.now().plusMinutes(2);
        FuzzTaskPo task = taskRepository.save(FuzzTaskPo.builder()
                .userId(11L)
                .status(FuzzTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .progress(0)
                .targetSpecIdsJson("[]")
                .maxIterations(100)
                .pathLength(10)
                .populationSize(5)
                .modelInputSnapshotJson("{}")
                .modelSnapshotJson("{}")
                .workerId("worker-a")
                .leaseExpiresAt(leaseExpiresAt)
                .build());

        assertEquals(1, taskRepository.startTaskIfStillPending(
                task.getId(), FuzzTaskPo.TaskStatus.RUNNING, LocalDateTime.now(),
                "worker-a", LocalDateTime.now(), leaseExpiresAt, "[]",
                FuzzTaskPo.TaskStatus.PENDING));
        assertEquals(1, taskRepository.updateProgressIfActive(
                task.getId(), 50, TaskProgressStage.EXPLORING_CANDIDATES,
                "worker-a", LocalDateTime.now()));
        FuzzTaskPo taskWithProgress = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(50, taskWithProgress.getProgress());
        assertEquals(TaskProgressStage.EXPLORING_CANDIDATES, taskWithProgress.getProgressStage());
        assertEquals(1, taskRepository.completeTaskIfRunning(
                task.getId(), FuzzTaskPo.TaskStatus.COMPLETED, LocalDateTime.now(), 10L,
                FuzzOutcome.BUDGET_EXHAUSTED, 7L, 100, 500L, 10L,
                "{\"eligibleSpecIds\":[],\"ineligibleSpecs\":[],\"requestedSpecCount\":0,\"eligibleSpecCount\":0}",
                "[]", 0, "[]", FuzzTaskPo.TaskStatus.RUNNING,
                "worker-a", LocalDateTime.now()));
        assertEquals(0, taskRepository.cancelTaskIfStillActive(
                task.getId(), FuzzTaskPo.TaskStatus.CANCELLED, LocalDateTime.now(),
                List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING)));
        assertEquals(0, taskRepository.updateProgressIfActive(
                task.getId(), 50, TaskProgressStage.EXPLORING_CANDIDATES,
                "worker-a", LocalDateTime.now()));
        assertEquals(FuzzExplorationMode.BOARD_SNAPSHOT,
                taskRepository.findById(task.getId()).orElseThrow().getExplorationMode());

        // Partway through on purpose: a user-cancelled run must keep the progress it reached, not be
        // overwritten with 100. A run cancelled at 30% was observed live publishing 100%.
        FuzzTaskPo cancelledTask = taskRepository.save(FuzzTaskPo.builder()
                .userId(11L)
                .status(FuzzTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .progress(30)
                .explorationMode(FuzzExplorationMode.PAPER_COMPATIBLE)
                .targetSpecIdsJson("[]")
                .maxIterations(100)
                .pathLength(10)
                .populationSize(5)
                .modelInputSnapshotJson("{}")
                .modelSnapshotJson("{}")
                .workerId("worker-a")
                .leaseExpiresAt(leaseExpiresAt)
                .build());
        assertEquals(1, taskRepository.cancelTaskIfStillActive(
                cancelledTask.getId(), FuzzTaskPo.TaskStatus.CANCELLED, LocalDateTime.now(),
                List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING)));
        assertEquals(0, taskRepository.failTaskIfActive(
                cancelledTask.getId(), FuzzTaskPo.TaskStatus.FAILED, LocalDateTime.now(), 1L,
                "late failure", "[]",
                List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING),
                "worker-a", LocalDateTime.now()));
        assertEquals(0, taskRepository.completeTaskIfRunning(
                cancelledTask.getId(), FuzzTaskPo.TaskStatus.COMPLETED, LocalDateTime.now(), 1L,
                FuzzOutcome.BUDGET_EXHAUSTED, 7L, 1, 1L, 1L,
                "{\"eligibleSpecIds\":[],\"ineligibleSpecs\":[],\"requestedSpecCount\":0,\"eligibleSpecCount\":0}",
                "[]", 0, "[]", FuzzTaskPo.TaskStatus.RUNNING,
                "worker-a", LocalDateTime.now()));
        FuzzTaskPo settledCancellation = taskRepository.findById(cancelledTask.getId()).orElseThrow();
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE, settledCancellation.getExplorationMode());
        assertEquals(FuzzTaskPo.TaskStatus.CANCELLED, settledCancellation.getStatus());
        // The user stopped it at 30%; publishing 100 would claim work the run never did.
        assertEquals(30, settledCancellation.getProgress());

        findingRepository.save(FuzzFindingPo.builder()
                .userId(11L)
                .fuzzTaskId(task.getId())
                .violatedSpecId("spec-1")
                .violatedSpecJson("{}")
                .firstViolationStep(2)
                .statesJson("[]")
                .inputEventsJson("[]")
                .seed(7L)
                .stateCount(0)
                .createdAt(LocalDateTime.now())
                .build());

        assertEquals(1, findingRepository
                .findByUserIdAndFuzzTaskId(11L, task.getId()).size());
        assertTrue(findingRepository
                .findByUserIdAndFuzzTaskId(12L, task.getId()).isEmpty());
        assertEquals(1L, findingRepository.countByUserIdAndFuzzTaskId(11L, task.getId()));
        assertEquals(0L, findingRepository.countByUserIdAndFuzzTaskId(12L, task.getId()));
        List<FuzzTaskSummaryProjection> completedRuns = taskRepository
                .findSummaryByUserIdAndStatusOrderByCreatedAtDescIdDesc(
                11L, FuzzTaskPo.TaskStatus.COMPLETED, PageRequest.of(0, 25));
        assertEquals(1, completedRuns.size());
        assertEquals(FuzzExplorationMode.BOARD_SNAPSHOT,
                completedRuns.get(0).getExplorationMode());
        List<FuzzTaskSummaryProjection> inbox = taskRepository
                .findSummaryByUserIdAndStatusNotOrderByCreatedAtDescIdDesc(
                11L, FuzzTaskPo.TaskStatus.COMPLETED, PageRequest.of(0, 100));
        assertEquals(List.of(cancelledTask.getId()),
                inbox.stream().map(FuzzTaskSummaryProjection::getId).toList());
        assertEquals(FuzzExplorationMode.PAPER_COMPATIBLE,
                inbox.get(0).getExplorationMode());
    }

    @Test
    void leaseRecoveryOnlyFailsExpiredTasks() {
        LocalDateTime now = LocalDateTime.now();
        FuzzTaskPo owned = taskRepository.save(taskForLease("worker-a", now.plusMinutes(1)));
        FuzzTaskPo otherLive = taskRepository.save(taskForLease("worker-b", now.plusMinutes(1)));
        FuzzTaskPo expired = taskRepository.save(taskForLease("worker-dead", now.minusSeconds(1)));

        assertEquals(1, taskRepository.failExpiredActiveTasks(
                FuzzTaskPo.TaskStatus.FAILED,
                now,
                "Worker lease expired",
                "[]",
                List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING),
                now));

        assertEquals(FuzzTaskPo.TaskStatus.PENDING,
                taskRepository.findById(owned.getId()).orElseThrow().getStatus());
        assertEquals(FuzzTaskPo.TaskStatus.PENDING,
                taskRepository.findById(otherLive.getId()).orElseThrow().getStatus());
        assertEquals(FuzzTaskPo.TaskStatus.FAILED,
                taskRepository.findById(expired.getId()).orElseThrow().getStatus());
    }

    /**
     * A lease expiring at exactly the sampled instant is expired, in all three sweeps.
     *
     * This boundary is why the three queries were allowed to disagree: the fuzz sweep used a strict
     * {@code <} while verification and simulation used {@code <=}, and the coverage above only ever
     * wrote a lease a second in the past or a minute in the future, so nothing distinguished them.
     * Both the clock and the column carry microsecond precision, so the equality itself is rare in
     * production; what this pins is that all three sweeps place the boundary where the thirteen
     * renewal, start, and terminal-commit guards already place it.
     *
     * {@code ==} must count as expired because every renewal and terminal-commit guard in these
     * repositories requires {@code leaseExpiresAt > :currentTime}: a lease at the sampled instant can
     * no longer renew, progress, or commit a result, so treating it as live left a row that no worker
     * could advance sitting unreclaimed until the next maintenance tick.
     *
     * <p>The instant is truncated, and that is load-bearing rather than tidiness. H2 <em>rounds</em> a
     * {@code TIMESTAMP(6)} column to the nearest microsecond, while {@code LocalDateTime.now()} carries
     * finer digits than that on both CI and a dev box. Whenever the sub-microsecond remainder is
     * >= 500ns the stored lease lands strictly <em>after</em> the value passed as
     * {@code expiredBefore}, so the row correctly does not match {@code <=} and the test fails having
     * never created the equality it is about. Measured on H2 2.3.232: 91 of 200 raw {@code now()}
     * samples missed the boundary, which is why this passed twice locally and failed on its first CI
     * run. Truncating makes the fixture store exactly what it asserts on.
     */
    @Test
    void leaseRecoveryTreatsALeaseExpiringAtTheSampledInstantAsExpired() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        FuzzTaskPo fuzz = taskRepository.save(taskForLease("worker-boundary", now));
        assertEquals(1, taskRepository.failExpiredActiveTasks(
                FuzzTaskPo.TaskStatus.FAILED,
                now,
                "Worker lease expired",
                "[]",
                List.of(FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING),
                now));
        assertEquals(FuzzTaskPo.TaskStatus.FAILED,
                taskRepository.findById(fuzz.getId()).orElseThrow().getStatus());

        VerificationTaskPo verification = verificationTaskRepository.saveAndFlush(
                VerificationTaskPo.builder()
                        .userId(12L)
                        .status(VerificationTaskPo.TaskStatus.RUNNING)
                        .createdAt(now)
                        .progress(20)
                        .workerId("worker-boundary")
                        .leaseExpiresAt(now)
                        .build());
        assertEquals(1, verificationTaskRepository.failExpiredActiveTasks(
                VerificationTaskPo.TaskStatus.FAILED,
                now,
                VerificationOutcome.INCONCLUSIVE,
                "expired",
                "[]",
                List.of(VerificationTaskPo.TaskStatus.PENDING, VerificationTaskPo.TaskStatus.RUNNING),
                now));
        assertEquals(VerificationTaskPo.TaskStatus.FAILED,
                verificationTaskRepository.findById(verification.getId()).orElseThrow().getStatus());

        SimulationTaskPo simulation = simulationTaskRepository.saveAndFlush(
                SimulationTaskPo.builder()
                        .userId(13L)
                        .status(SimulationTaskPo.TaskStatus.RUNNING)
                        .createdAt(now)
                        .requestedSteps(5)
                        .progress(20)
                        .workerId("worker-boundary")
                        .leaseExpiresAt(now)
                        .build());
        assertEquals(1, simulationTaskRepository.failExpiredActiveTasks(
                SimulationTaskPo.TaskStatus.FAILED,
                now,
                "expired",
                "[]",
                List.of(SimulationTaskPo.TaskStatus.PENDING, SimulationTaskPo.TaskStatus.RUNNING),
                now));
        assertEquals(SimulationTaskPo.TaskStatus.FAILED,
                simulationTaskRepository.findById(simulation.getId()).orElseThrow().getStatus());
    }

    @Test
    void admissionCountsOnlyActiveTasksAndDeletesOnlyOwnedUndispatchedRows() {
        LocalDateTime lease = LocalDateTime.now().plusMinutes(2);
        FuzzTaskPo pending = taskForLease("worker-a", lease);
        pending.setUserId(31L);
        pending = taskRepository.save(pending);
        FuzzTaskPo running = taskForLease("worker-a", lease);
        running.setUserId(31L);
        running.setStatus(FuzzTaskPo.TaskStatus.RUNNING);
        taskRepository.save(running);
        FuzzTaskPo completed = taskForLease("worker-a", lease);
        completed.setUserId(31L);
        completed.setStatus(FuzzTaskPo.TaskStatus.COMPLETED);
        completed = taskRepository.save(completed);
        FuzzTaskPo otherUser = taskForLease("worker-a", lease);
        otherUser.setUserId(32L);
        taskRepository.save(otherUser);

        List<FuzzTaskPo.TaskStatus> active = List.of(
                FuzzTaskPo.TaskStatus.PENDING, FuzzTaskPo.TaskStatus.RUNNING);
        assertEquals(3L, taskRepository.countByUserId(31L));
        assertEquals(1L, taskRepository.countByUserId(32L));
        assertEquals(2L, taskRepository.countByUserIdAndStatusIn(31L, active));
        assertEquals(0, taskRepository.deleteUndispatchedTask(
                pending.getId(), 31L, "worker-b", FuzzTaskPo.TaskStatus.PENDING));
        assertEquals(0, taskRepository.deleteUndispatchedTask(
                completed.getId(), 31L, "worker-a", FuzzTaskPo.TaskStatus.PENDING));
        assertEquals(1, taskRepository.deleteUndispatchedTask(
                pending.getId(), 31L, "worker-a", FuzzTaskPo.TaskStatus.PENDING));
        assertTrue(taskRepository.findById(pending.getId()).isEmpty());
        assertTrue(taskRepository.findById(completed.getId()).isPresent());
        assertEquals(2L, taskRepository.countByUserId(31L));
    }

    @Test
    void listProjectionsDoNotExposeFrozenInputOrFindingEvidenceColumns() {
        LocalDateTime now = LocalDateTime.now();
        FuzzTaskPo task = taskRepository.saveAndFlush(FuzzTaskPo.builder()
                .userId(21L)
                .status(FuzzTaskPo.TaskStatus.COMPLETED)
                .createdAt(now)
                .completedAt(now.plusSeconds(1))
                .progress(100)
                .targetSpecIdsJson("[]")
                .maxIterations(1)
                .pathLength(1)
                .populationSize(1)
                .modelInputSnapshotJson("{\"large\":\"frozen\"}")
                .modelSnapshotJson("{\"capturedAt\":\"2026-07-15T00:00:00\",\"templatesFrozen\":true}")
                .outcome(FuzzOutcome.BUDGET_EXHAUSTED)
                .effectiveSeed(1L)
                .iterations(1)
                .generatedPaths(1L)
                .elapsedMs(1L)
                .eligibilityJson("{\"eligibleSpecIds\":[],\"eligibleSpecLabels\":{},\"ineligibleSpecs\":[],\"requestedSpecCount\":0,\"eligibleSpecCount\":0}")
                .limitationsJson("[]")
                .findingCount(0)
                .build());

        List<FuzzTaskSummaryProjection> tasks = taskRepository
                .findSummaryByUserIdAndStatusOrderByCreatedAtDescIdDesc(
                        21L, FuzzTaskPo.TaskStatus.COMPLETED, PageRequest.of(0, 10));

        assertEquals(List.of(task.getId()), tasks.stream().map(FuzzTaskSummaryProjection::getId).toList());
        assertTrue(tasks.stream().noneMatch(item -> java.lang.reflect.Proxy.isProxyClass(item.getClass())),
                "history metadata must not rebuild a proxy interceptor chain for every getter");
        assertTrue(Arrays.stream(FuzzTaskSummaryProjection.class.getMethods())
                .map(Method::getName)
                .noneMatch(name -> name.equals("getModelInputSnapshotJson")
                        || name.equals("getCheckLogsJson")));
        assertTrue(Arrays.stream(FuzzFindingSummaryProjection.class.getMethods())
                .map(Method::getName)
                .noneMatch(name -> name.equals("getViolatedSpecJson")
                        || name.equals("getStatesJson")
                        || name.equals("getInputEventsJson")));
        assertTrue(Arrays.stream(FuzzFindingRepository.class.getMethods())
                .map(Method::getName)
                .noneMatch(name -> name.contains("OrderByCreatedAt")));
    }

    private FuzzTaskPo taskForLease(String workerId, LocalDateTime leaseExpiresAt) {
        return FuzzTaskPo.builder()
                .userId(11L)
                .status(FuzzTaskPo.TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .progress(0)
                .targetSpecIdsJson("[]")
                .maxIterations(1)
                .pathLength(1)
                .populationSize(1)
                .modelInputSnapshotJson("{}")
                .modelSnapshotJson("{}")
                .workerId(workerId)
                .leaseExpiresAt(leaseExpiresAt)
                .build();
    }
}
