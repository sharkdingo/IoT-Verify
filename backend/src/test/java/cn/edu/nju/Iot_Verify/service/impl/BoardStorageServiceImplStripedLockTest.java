package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceNodeRepository;
import cn.edu.nju.Iot_Verify.repository.BoardEnvironmentVariableRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.RuleRepository;
import cn.edu.nju.Iot_Verify.repository.BoardLayoutRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.service.board.BoardEditHistoryState;
import cn.edu.nju.Iot_Verify.service.board.BoardEditJournal;
import cn.edu.nju.Iot_Verify.service.board.BoardUndoAvailability;
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
 * Clears confirmed history through the production write entry point to exercise actual exclusion.
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
        boolean overlap = writesOverlap(7L, 7L, false);

        assertFalse(overlap,
                "a second writer reached completion while the first was still in its transaction");
    }

    @Test
    @DisplayName("Users sharing a stripe are serialized too — collision is safe, not a bug")
    void collidingUsers_areAlsoSerialized() throws Exception {
        // 1 and 1025 both map to stripe 1. Sharing a lock costs concurrency, never correctness.
        boolean overlap = writesOverlap(1L, 1L + LOCK_STRIPES, false);

        assertFalse(overlap,
                "users sharing a stripe must still exclude each other");
    }

    @Test
    @DisplayName("Users on different stripes are not serialized against each other")
    void differentStripes_runConcurrently() throws Exception {
        // The point of striping: without it a single global lock would serialize unrelated accounts.
        boolean overlap = writesOverlap(1L, 2L, true);

        assertTrue(overlap,
                "different stripes must allow concurrent writes, or striping buys nothing");
    }

    /**
     * Holds each transaction at completion until both writers have had a chance to arrive.
     *
     * <p>{@code expectConcurrency} decides how long the first arrival waits for the second. For the
     * serialized cases the wait must be short and best-effort, because the second thread *cannot*
     * arrive until the first leaves — waiting for it would deadlock. For the concurrent case the wait
     * has to be generous instead: a fixed 300 ms window made the assertion a race against the
     * scheduler, so on a loaded machine (the one also running NuSMV subprocesses) the pair missed each
     * other and the failure blamed the lock design rather than the timing.
     */
    private boolean writesOverlap(long firstUserId, long secondUserId,
                                  boolean expectConcurrency) throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicBoolean overlapObserved = new AtomicBoolean(false);
        CountDownLatch bothFinishedWork = new CountDownLatch(2);
        CountDownLatch done = new CountDownLatch(2);

        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(new UserPo()));

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var result = ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null);
            if (inside.incrementAndGet() > 1) overlapObserved.set(true);
            bothFinishedWork.countDown();
            bothFinishedWork.await(expectConcurrency ? 5000 : 300, TimeUnit.MILLISECONDS);
            inside.decrementAndGet();
            return result;
        });

        BoardEditJournal journal = mock(BoardEditJournal.class);
        when(journal.historyState(anyLong())).thenReturn(
                new BoardEditHistoryState(1, new BoardUndoAvailability(1L, 1L, null), "confirmed"));
        BoardStorageServiceImpl service = serviceWith(userRepository, transactionTemplate, journal);

        var pending = new ArrayList<java.util.concurrent.Future<?>>();
        for (long userId : new long[] {firstUserId, secondUserId}) {
            pending.add(threads.submit(() -> {
                try {
                    service.clearBoardEditHistory(userId, "confirmed");
                } finally {
                    done.countDown();
                }
            }));
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "both writers must complete");
        for (var operation : pending) operation.get(1, TimeUnit.SECONDS);
        return overlapObserved.get();
    }

    private BoardStorageServiceImpl serviceWith(UserRepository userRepository,
                                                TransactionTemplate transactionTemplate,
                                                BoardEditJournal journal) {
        return new BoardStorageServiceImpl(
                mock(DeviceNodeRepository.class), mock(BoardEnvironmentVariableRepository.class),
                mock(SpecificationRepository.class), mock(RuleRepository.class),
                mock(BoardLayoutRepository.class), mock(DeviceTemplateRepository.class), null,
                transactionTemplate, null, mock(SpecificationMapper.class), mock(RuleMapper.class),
                mock(DeviceNodeMapper.class), null, mock(DeviceTemplateMapper.class), null,
                userRepository, journal);
    }
}
