package cn.edu.nju.Iot_Verify.service;

import cn.edu.nju.Iot_Verify.dto.chat.ChatHistoryPageDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatConfirmationCommandDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatPendingConfirmationDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatSessionActivityDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatSessionResponseDto;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface ChatService {
    List<ChatSessionResponseDto> getUserSessions(Long userId);
    ChatSessionResponseDto createSession(Long userId);
    ChatHistoryPageDto getHistoryPage(Long userId, String sessionId, Long beforeId, int limit);
    void markTerminalSeen(Long userId, String sessionId, Long terminalMessageId);
    void deleteSession(Long userId, String sessionId);
    String beginStreamRequest(Long userId, String sessionId, String turnId, String content);
    void abortUndispatched(Long userId, String sessionId, String executionId, String turnId);
    void endStreamRequest(Long userId, String sessionId, String executionId);
    void requestStreamStop(Long userId, String sessionId, String turnId);
    ChatSessionActivityDto getSessionActivity(Long userId, String sessionId);
    ChatPendingConfirmationDto getPendingConfirmation(Long userId, String sessionId);
    default void processStreamChat(Long userId, String sessionId, String executionId,
                                   String turnId, String content, SseEmitter emitter) {
        processStreamChat(userId, sessionId, executionId, turnId, content, null, null, emitter);
    }

    /**
     * @param locale the UI language this turn is read in ({@code zh-CN} / {@code en}); null falls back to
     *               inspecting {@code content} for Han characters, which is only a guess
     */
    void processStreamChat(Long userId, String sessionId, String executionId,
                           String turnId, String content, String locale,
                           ChatConfirmationCommandDto confirmation,
                           SseEmitter emitter);
}
