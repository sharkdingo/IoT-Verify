package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ChatSessionRepository extends JpaRepository<ChatSessionPo, String>, DatabaseClockRepository {

    List<ChatSessionPo> findTop100ByUserIdOrderByUpdatedAtDesc(Long userId);
    long countByUserId(Long userId);
    Optional<ChatSessionPo> findByIdAndUserId(String id, Long userId);

    @Query("""
            select chatSession.activeExecutionId as activeExecutionId,
                   chatSession.activeExecutionExpiresAt as activeExecutionExpiresAt,
                   chatSession.executionStopRequested as executionStopRequested
              from ChatSessionPo chatSession
             where chatSession.id = :id and chatSession.userId = :userId
            """)
    Optional<ExecutionLeaseView> findExecutionLeaseByIdAndUserId(@Param("id") String id,
                                                                  @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select chatSession from ChatSessionPo chatSession where chatSession.id = :id and chatSession.userId = :userId")
    Optional<ChatSessionPo> findByIdAndUserIdForUpdate(@Param("id") String id,
                                                       @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select chatSession
              from ChatSessionPo chatSession
             where chatSession.id in :sessionIds
             order by chatSession.id
            """)
    List<ChatSessionPo> findByIdInForUpdate(@Param("sessionIds") Set<String> sessionIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select chatSession
              from ChatSessionPo chatSession
             where chatSession.userId = :userId
             order by chatSession.id
            """)
    List<ChatSessionPo> findByUserIdForUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select chatSession
              from ChatSessionPo chatSession
             where chatSession.activeExecutionId is not null
               and (chatSession.activeExecutionExpiresAt is null
                    or chatSession.activeExecutionExpiresAt <= :now)
             order by chatSession.id
            """)
    List<ChatSessionPo> findExpiredExecutionLeasesForUpdate(@Param("now") LocalDateTime now);

    @Transactional
    default int clearExpiredExecutionLeases(LocalDateTime now) {
        List<ChatSessionPo> sessions = findExpiredExecutionLeasesForUpdate(now);
        sessions.forEach(session -> {
            session.setActiveExecutionId(null);
            session.setActiveExecutionTurnId(null);
            session.setActiveExecutionExpiresAt(null);
            session.setExecutionStopRequested(false);
            session.setExecutionUserStopRequested(false);
        });
        flush();
        return sessions.size();
    }

    void deleteByUserId(Long userId);

    interface ExecutionLeaseView {
        String getActiveExecutionId();
        LocalDateTime getActiveExecutionExpiresAt();
        Boolean getExecutionStopRequested();
    }
}
