package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.BoardEditJournalPo;
import cn.edu.nju.Iot_Verify.service.board.BoardUndoAvailability;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BoardEditJournalRepository extends JpaRepository<BoardEditJournalPo, Long> {

    /** The next edit to undo: the newest entry still in effect. */
    Optional<BoardEditJournalPo> findFirstByUserIdAndUndoneFalseOrderBySequenceDesc(Long userId);

    /** The next edit to redo: the oldest undone entry, so redo replays in original order. */
    Optional<BoardEditJournalPo> findFirstByUserIdAndUndoneTrueOrderBySequenceAsc(Long userId);

    Optional<BoardEditJournalPo> findFirstByUserIdOrderBySequenceDesc(Long userId);

    /** Oldest-first, for trimming the account's history to its depth limit. */
    List<BoardEditJournalPo> findByUserIdOrderBySequenceAsc(Long userId);

    long countByUserId(Long userId);

    /**
     * The sequence of the account's {@code offset}-th oldest entry, used as the trim cutoff.
     *
     * <p>A projection rather than the entities: trimming runs on every reversible mutation, and
     * loading up to 51 full rows — each carrying two JSON snapshot blobs — into the persistence
     * context to read one number was pure overhead.
     */
    @Query(value = "select e.sequence from BoardEditJournalPo e where e.userId = :userId"
            + " order by e.sequence asc")
    List<Long> findSequencesOldestFirst(@Param("userId") Long userId, Pageable pageable);

    /** Bulk trim, so the oldest excess entries go in one statement instead of entity-by-entity. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BoardEditJournalPo e where e.userId = :userId and e.sequence <= :maxSequence")
    int deleteByUserIdAndSequenceLessThanEqual(@Param("userId") Long userId,
                                               @Param("maxSequence") long maxSequence);

    /**
     * Discards the account's abandoned redo branch in one statement.
     *
     * <p>Checked on every new edit and issued only when such rows exist. The bulk operation replaces
     * the previous read-then-{@code deleteAll} path that loaded rows only to delete them one by one.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BoardEditJournalPo e where e.userId = :userId and e.undone = true")
    int deleteByUserIdAndUndoneTrue(@Param("userId") Long userId);

    /** Consistent read used to avoid an empty-range delete and its MySQL next-key lock. */
    boolean existsByUserIdAndUndoneTrue(Long userId);

    long countByUserIdAndUndoneFalse(Long userId);

    long countByUserIdAndUndoneTrue(Long userId);

    @Query("SELECT new cn.edu.nju.Iot_Verify.service.board.BoardUndoAvailability("
            + "COUNT(CASE WHEN e.undone = false THEN 1 END) > 0, "
            + "COUNT(CASE WHEN e.undone = true THEN 1 END) > 0) "
            + "FROM BoardEditJournalPo e WHERE e.userId = :userId")
    BoardUndoAvailability findAvailabilityByUserId(@Param("userId") Long userId);

    void deleteByUserId(Long userId);
}
