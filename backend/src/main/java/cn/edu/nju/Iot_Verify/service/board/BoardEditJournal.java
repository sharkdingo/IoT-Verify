package cn.edu.nju.Iot_Verify.service.board;

import cn.edu.nju.Iot_Verify.po.BoardEditEntityType;
import cn.edu.nju.Iot_Verify.po.BoardEditJournalPo;
import cn.edu.nju.Iot_Verify.po.BoardEditOperation;
import cn.edu.nju.Iot_Verify.repository.BoardEditJournalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Records reversible board edits and hands back the next entry to undo or redo.
 *
 * <p>Every method here must run inside the caller's transaction and under the caller's per-user
 * write lock: the journal entry and the edit it describes have to commit together, or a crash
 * between them would leave an undo that does not match reality.
 *
 * <p><b>Deployment boundary.</b> {@link #record} allocates {@code sequence} by reading the current
 * maximum and adding one, which the JVM-level per-user write lock alone cannot make safe across
 * instances. The database fences it: {@link BoardEditJournalPo} declares a unique
 * {@code (user_id, sequence)} constraint, so a lost race fails with a duplicate key instead of
 * producing two entries that share an ordinal.
 *
 * <p>What that fencing does <em>not</em> give is recovery. {@code record} runs inside the caller's
 * transaction, and a constraint violation on flush marks that transaction rollback-only, so the
 * allocation cannot simply be re-attempted — the whole board mutation fails. That is the intended
 * trade for a single-instance deployment: fail loudly rather than corrupt the ordering. A
 * multi-instance deployment wanting the mutation to survive the race needs the allocation moved
 * outside the transaction, or a database-side sequence.
 *
 * <p>This deliberately knows nothing about how to <em>apply</em> an inverse — that belongs to the
 * board service, which owns the validation and cascade rules. Keeping the split means the journal
 * cannot drift into a second, weaker copy of the board's write path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoardEditJournal {

    /**
     * How many reversible edits an account keeps. Bounded so the journal cannot grow without limit
     * over a long-lived account; beyond this depth the oldest entries are no longer reachable by
     * repeated undo anyway.
     */
    private static final int MAX_ENTRIES_PER_USER = 50;

    private final BoardEditJournalRepository journalRepo;
    private final ObjectMapper objectMapper;

    /**
     * Appends an entry for an edit that just happened, and discards any undone entries it makes
     * unreachable.
     *
     * <p>That discard is the "no clobbering newer work" rule: once the user edits again after an
     * undo, redoing the abandoned branch would overwrite the new edit, so the branch is dropped
     * rather than left as a trap.
     */
    public void record(
            Long userId,
            BoardEditEntityType entityType,
            BoardEditOperation operation,
            String entityKey,
            Object before,
            Object after) {
        record(userId, entityType, operation, entityKey, before, after, null);
    }

    /** As {@link #record}, additionally preserving a rule or specification's collection position. */
    public void record(
            Long userId,
            BoardEditEntityType entityType,
            BoardEditOperation operation,
            String entityKey,
            Object before,
            Object after,
            Integer entityOrder) {
        // Under MySQL REPEATABLE READ, deleting an empty per-user range takes a next-key lock on
        // the unique index's end gap. Two different accounts creating their first entries can then
        // deadlock when both inserts need that same gap. A consistent existence read takes no such
        // lock; issue the delete only when there is an abandoned redo branch to remove.
        if (journalRepo.existsByUserIdAndUndoneTrue(userId)) {
            journalRepo.deleteByUserIdAndUndoneTrue(userId);
        }

        // Allocation is read-max-then-add-one, and the per-user lock guarding it is in-JVM only. The
        // unique (user_id, sequence) index declared by BoardEditJournalPo turns a cross-instance
        // race into a duplicate-key failure instead of two entries sharing an ordinal — which would make
        // "the newest edit still in effect" ambiguous and silently strand one of them.
        //
        // Deliberately not retried here: this runs inside the caller's transaction, so a violation on
        // flush marks it rollback-only and a second attempt cannot commit either. A retry loop would
        // only look like resilience. The mutation fails loudly instead, which is the right trade — see
        // the class javadoc for what a multi-instance deployment would need.
        long sequence = journalRepo.findFirstByUserIdOrderBySequenceDesc(userId)
                .map(entry -> entry.getSequence() + 1)
                .orElse(1L);
        journalRepo.saveAndFlush(BoardEditJournalPo.builder()
                .userId(userId)
                .sequence(sequence)
                .entityType(entityType)
                .operation(operation)
                .entityKey(entityKey)
                .beforeJson(writeJson(before))
                .afterJson(writeJson(after))
                .entityOrder(entityOrder)
                .undone(false)
                .createdAt(LocalDateTime.now())
                .build());
        trimToDepthLimit(userId);
    }

    /**
     * Drops the oldest entries once the account exceeds {@link #MAX_ENTRIES_PER_USER}. They are the
     * least likely to be wanted and are unreachable without walking the entire history first.
     */
    private void trimToDepthLimit(Long userId) {
        long total = journalRepo.countByUserId(userId);
        int excess = (int) (total - MAX_ENTRIES_PER_USER);
        if (excess <= 0) return;
        // One sequence value, not up to 51 entities each carrying two JSON snapshot blobs, then one
        // bulk delete: this runs on every reversible mutation.
        List<Long> cutoff = journalRepo.findSequencesOldestFirst(
                userId, org.springframework.data.domain.PageRequest.of(excess - 1, 1));
        if (cutoff.isEmpty()) return;
        journalRepo.deleteByUserIdAndSequenceLessThanEqual(userId, cutoff.get(0));
    }

    /** The newest edit still in effect, or empty when there is nothing to undo. */
    public Optional<BoardEditJournalPo> nextToUndo(Long userId) {
        return journalRepo.findFirstByUserIdAndUndoneFalseOrderBySequenceDesc(userId);
    }

    /** The oldest undone edit, so a redo chain replays in the order the user originally worked. */
    public Optional<BoardEditJournalPo> nextToRedo(Long userId) {
        return journalRepo.findFirstByUserIdAndUndoneTrueOrderBySequenceAsc(userId);
    }

    /**
     * Flips an entry's state after its inverse has been applied. Called inside the same
     * transaction, so a failure to apply leaves the flag untouched and the entry retryable —
     * which is what makes a repeated undo idempotent rather than doubly-applied.
     */
    public void markUndone(BoardEditJournalPo entry, boolean undone) {
        entry.setUndone(undone);
        journalRepo.save(entry);
    }

    public BoardUndoAvailability availability(Long userId) {
        return journalRepo.findAvailabilityByUserId(userId);
    }

    /** Complete journal revision used by the clear-history preview/confirmation fence. */
    public BoardEditHistoryState historyState(Long userId) {
        List<BoardEditJournalPo> entries = journalRepo.findByUserIdOrderBySequenceAsc(userId);
        long undoCount = entries.stream().filter(entry -> !entry.isUndone()).count();
        long redoCount = entries.size() - undoCount;
        List<HistoryTokenEntry> tokenEntries = entries.stream()
                .map(entry -> new HistoryTokenEntry(
                        entry.getId(),
                        entry.getSequence(),
                        entry.getEntityType(),
                        entry.getOperation(),
                        entry.getEntityKey(),
                        entry.getBeforeJson(),
                        entry.getAfterJson(),
                        entry.getEntityOrder(),
                        entry.isUndone()))
                .toList();
        try {
            byte[] canonical = objectMapper.writeValueAsString(tokenEntries)
                    .getBytes(StandardCharsets.UTF_8);
            String impactToken = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
            return new BoardEditHistoryState(
                    entries.size(),
                    new BoardUndoAvailability(undoCount > 0, redoCount > 0),
                    impactToken);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize board edit history state", e);
        }
    }

    private record HistoryTokenEntry(
            Long journalId,
            long sequence,
            BoardEditEntityType entityType,
            BoardEditOperation operation,
            String entityKey,
            String beforeJson,
            String afterJson,
            Integer entityOrder,
            boolean undone
    ) {
    }

    /**
     * Drops the whole journal after a token-confirmed destructive preview: explicit history clear,
     * full-scene replacement, template deletion, or bundled-template reset. Each operation can make
     * retained snapshots semantically invalid, so keeping a partial journal would offer an inverse
     * that no longer describes the complete previous state.
     */
    public void clear(Long userId) {
        journalRepo.deleteByUserId(userId);
    }

    public <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            // A journal entry we cannot read is not a reason to fail the board; it is a reason to
            // treat that history as unavailable.
            log.warn("Unreadable board edit journal payload; treating entry as unusable", e);
            return null;
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize board edit journal payload", e);
        }
    }
}
