package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceNodeRepository;
import cn.edu.nju.Iot_Verify.repository.BoardEnvironmentVariableRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.RuleRepository;
import cn.edu.nju.Iot_Verify.repository.BoardLayoutRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Per-user serialization of board writes.
 *
 * <p>Deliberately behavioural rather than reflective. An earlier version of this class only read
 * {@code userWriteLocks} and {@code getUserWriteLock} through reflection and asserted that the array
 * had 1024 non-null slots and that a userId mapped to the same slot twice. Every one of those
 * assertions restates the field initializer or the determinism of an array read, so all 25
 * {@code synchronized (getUserWriteLock(userId))} blocks in the service could be deleted and the file
 * stayed green — it pinned the lock *table*, never the locking.
 */
class BoardStorageServiceImplStripedLockTest {

    private static final long LOCK_STRIPES = 1024L;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    @Test
    @DisplayName("Two writers for the same user cannot be inside the critical section at once")
    void sameUser_writesAreSerialized() throws Exception {
        Overlap overlap = runTwoConcurrentWrites(7L, 7L);

        assertFalse(overlap.observed(),
                "a second writer entered the transaction while the first was still inside it");
    }

    @Test
    @DisplayName("Users sharing a stripe are serialized too — collision is safe, not a bug")
    void collidingUsers_areAlsoSerialized() throws Exception {
        // 1 and 1025 both map to stripe 1. Sharing a lock costs concurrency, never correctness.
        Overlap overlap = runTwoConcurrentWrites(1L, 1L + LOCK_STRIPES);

        assertFalse(overlap.observed(),
                "users sharing a stripe must still exclude each other");
    }

    @Test
    @DisplayName("Users on different stripes are not serialized against each other")
    void differentStripes_runConcurrently() throws Exception {
        // The point of striping: without it a single global lock would serialize unrelated accounts.
        Overlap overlap = runTwoConcurrentWrites(1L, 2L, true);

        assertTrue(overlap.observed(),
                "different stripes must allow concurrent writes, or striping buys nothing");
    }

    /**
     * Runs {@code saveNodes} for two user ids on two threads, with each transaction held
     * open until both have had a chance to enter, and reports whether they were ever inside together.
     */
    private Overlap runTwoConcurrentWrites(long firstUserId, long secondUserId) throws Exception {
        return runTwoConcurrentWrites(firstUserId, secondUserId, false);
    }

    /**
     * Runs {@code saveNodes} for two user ids on two threads, with each transaction held
     * open until both have had a chance to enter, and reports whether they were ever inside together.
     *
     * <p>{@code expectConcurrency} decides how long the first arrival waits for the second. For the
     * serialized cases the wait must be short and best-effort, because the second thread *cannot*
     * arrive until the first leaves — waiting for it would deadlock. For the concurrent case the wait
     * has to be generous instead: a fixed 300 ms window made the assertion a race against the
     * scheduler, so on a loaded machine (the one also running NuSMV subprocesses) the pair missed each
     * other and the failure blamed the lock design rather than the timing.
     */
    private Overlap runTwoConcurrentWrites(long firstUserId, long secondUserId,
                                              boolean expectConcurrency) throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean overlapObserved = new AtomicBoolean(false);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(new UserPo()));

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            if (inside.incrementAndGet() > 1) overlapObserved.set(true);
            bothEntered.countDown();
            bothEntered.await(expectConcurrency ? 5000 : 300, TimeUnit.MILLISECONDS);
            inside.decrementAndGet();
            return ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null);
        });

        BoardStorageServiceImpl service = serviceWith(userRepository, transactionTemplate);

        var pending = new ArrayList<java.util.concurrent.Future<?>>();
        for (long userId : new long[] {firstUserId, secondUserId}) {
            pending.add(threads.submit(() -> {
                try {
                    service.saveNodes(userId, List.of());
                } finally {
                    done.countDown();
                }
            }));
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "both writers must complete");
        for (var operation : pending) operation.get(1, TimeUnit.SECONDS);
        return new Overlap(overlapObserved.get());
    }

    private BoardStorageServiceImpl serviceWith(UserRepository userRepository,
                                                TransactionTemplate transactionTemplate) {
        return new BoardStorageServiceImpl(
                mock(DeviceNodeRepository.class), mock(BoardEnvironmentVariableRepository.class),
                mock(SpecificationRepository.class), mock(RuleRepository.class),
                mock(BoardLayoutRepository.class), mock(DeviceTemplateRepository.class), null,
                transactionTemplate, null, mock(SpecificationMapper.class), mock(RuleMapper.class),
                mock(DeviceNodeMapper.class), null, mock(DeviceTemplateMapper.class), null,
                userRepository, null);
    }

    private record Overlap(boolean observed) {
    }
}
