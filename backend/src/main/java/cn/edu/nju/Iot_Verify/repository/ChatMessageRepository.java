// src/main/java/cn/edu/nju/Iot_Verify/repository/ChatMessageRepository.java
package cn.edu.nju.Iot_Verify.repository;

import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.component.ai.model.ChatExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessagePo, Long> {
    // Full session history for frontend timeline.
    List<ChatMessagePo> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<ChatMessagePo> findBySessionIdOrderByIdDesc(String sessionId, Pageable pageable);
    List<ChatMessagePo> findBySessionIdAndIdLessThanOrderByIdDesc(
            String sessionId, Long id, Pageable pageable);
    Optional<ChatMessagePo> findByIdAndSessionIdAndRole(Long id, String sessionId, String role);
    @Query("""
            select message from ChatMessagePo message
             where message.sessionId = :sessionId and message.role = 'tool'
               and (:beforeId is null or message.id < :beforeId)
             order by message.id desc
            """)
    List<ChatMessagePo> findToolActivityPage(@Param("sessionId") String sessionId,
                                            @Param("beforeId") Long beforeId, Pageable pageable);
    Optional<ChatMessagePo> findFirstBySessionIdAndIdLessThanAndRoleOrderByIdDesc(
            String sessionId, Long id, String role);

    // Recent message window for AI context. The database id is the cross-instance order.
    List<ChatMessagePo> findTop80BySessionIdOrderByIdDesc(String sessionId);

    @Query("""
            select m from ChatMessagePo m where m.sessionId = :sessionId and m.role = 'user'
              and (:beforeId is null or m.id < :beforeId)
              and (:messageId is null or m.id = :messageId)
              and (:query is null or locate(lower(:query), lower(m.content)) > 0)
            order by m.id desc
            """)
    List<ChatMessagePo> findUserOriginals(@Param("sessionId") String sessionId,
            @Param("beforeId") Long beforeId, @Param("messageId") Long messageId,
            @Param("query") String query, Pageable pageable);

    List<ChatMessagePo> findBySessionIdAndRoleAndIdGreaterThanOrderByIdAsc(
            String sessionId, String role, Long afterId, Pageable pageable);

    long countBySessionId(String sessionId);
    boolean existsBySessionIdAndTurnId(String sessionId, String turnId);
    boolean existsBySessionIdAndTurnIdAndExecutionIdAndRole(
            String sessionId, String turnId, String executionId, String role);
    boolean existsByIdAndSessionIdAndRoleAndExecutionStatusIsNotNull(
            Long id, String sessionId, String role);

    @Query("""
            select message.id as messageId,
                   message.sessionId as sessionId,
                   message.executionStatus as executionStatus
              from ChatMessagePo message
             where message.id in (
                   select max(latest.id)
                     from ChatMessagePo latest
                    where latest.sessionId in :sessionIds
                      and latest.role = 'assistant'
                      and latest.executionStatus is not null
                    group by latest.sessionId)
            """)
    List<LatestTerminalView> findLatestTerminalBySessionIdIn(
            @Param("sessionIds") Collection<String> sessionIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    int deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
            String sessionId, String turnId, String executionId, String role);

    void deleteBySessionId(String sessionId);
    void deleteBySessionIdIn(Collection<String> sessionIds);

    interface LatestTerminalView {
        Long getMessageId();
        String getSessionId();
        ChatExecutionStatus getExecutionStatus();
    }
}
