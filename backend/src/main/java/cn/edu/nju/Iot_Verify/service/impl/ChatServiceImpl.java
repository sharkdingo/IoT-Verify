package cn.edu.nju.Iot_Verify.service.impl;

import cn.edu.nju.Iot_Verify.component.ai.AiTaskContinuationStore;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector.ConfirmationDecision;
import cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector.ConfirmationKind;
import cn.edu.nju.Iot_Verify.component.ai.LlmChatService;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControl;
import cn.edu.nju.Iot_Verify.component.ai.LlmRequestControlHolder;
import cn.edu.nju.Iot_Verify.component.ai.LlmMessageCodec;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmChatResponse;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmStreamInterruptedException;
import cn.edu.nju.Iot_Verify.component.ai.model.ChatExecutionStatus;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmMessage;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolCall;
import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResponseHelper;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolManager;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolResultContract;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.scenario.AiScenarioDraftStore;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.chat.ChatMessageResponseDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatHistoryPageDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatConfirmationCommandDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatPendingConfirmationDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatSessionResponseDto;
import cn.edu.nju.Iot_Verify.dto.chat.ChatSessionActivityDto;
import cn.edu.nju.Iot_Verify.dto.chat.StreamResponseDto;
import cn.edu.nju.Iot_Verify.exception.ConflictException;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ChatSessionBusyException;
import cn.edu.nju.Iot_Verify.exception.ChatHistoryQuotaExceededException;
import cn.edu.nju.Iot_Verify.exception.ChatAdmissionOutcomeUnknownException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.exception.ChatConfirmationConflictException;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.po.ChatMessagePo;
import cn.edu.nju.Iot_Verify.po.ChatSessionPo;
import cn.edu.nju.Iot_Verify.repository.ChatMessageRepository;
import cn.edu.nju.Iot_Verify.repository.ChatSessionRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.ChatExecutionControl;
import cn.edu.nju.Iot_Verify.service.ChatService;
import cn.edu.nju.Iot_Verify.util.ChatLanguagePreference;
import cn.edu.nju.Iot_Verify.util.mapper.ChatMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import cn.edu.nju.Iot_Verify.component.ai.chat.ChatToolProgressPresenter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService, ChatExecutionControl {
    private static final int MAX_PRE_ADMISSION_STOP_TURNS = 64;
    private static final Duration PRE_ADMISSION_STOP_TTL = Duration.ofMinutes(2);

    private static final int SESSION_LOCK_STRIPES = 256;
    private static final long EXECUTION_CONTROL_POLL_NANOS = Duration.ofMillis(250).toNanos();
    private static final Object[] SESSION_LOCKS = createSessionLocks();
    private static final Set<String> CONTINUATION_SENSITIVE_FIELDS = Set.of(
            "impactToken", "confirmationToken", "domainImpactToken", "suggestionToken");
    private static final Set<String> TOOL_RESULT_CONTROL_FIELDS = Set.of(
            "error", "errorCode", "status", "requiresUserConfirmation",
            "resultAvailable", "resultStatus", "mutationMayHaveCommitted", "objectiveStatus");
    private static final Set<String> PERSISTED_PROGRESS_FIELDS = Set.of(
            "stage", "toolName", "round", "outcome", "successfulSteps",
            "failedSteps", "unconfirmedSteps", "detail", "claimReview");
    private static final Set<String> PERSISTED_PROGRESS_STAGES = Set.of(
            "CONTEXT_READY", "TASK_RESUMED", "PLANNING", "REASONING",
            "TOOL_EXECUTION", "TOOL_RESULT", "EXECUTION_GUARD", "CLAIM_REVIEW", "WRITING_RESPONSE");
    private static final Pattern UNSUPPORTED_TOOL_EVIDENCE_CLAIM = Pattern.compile(
            "(?is)(?:\\b(?:the\\s+)?(?:tool|function|api)(?:\\s+(?:call|execution))?\\s+"
                    + "(?:result|response|output)\\s+"
                    + "(?:shows|indicates|confirms|returned|reports|succeeded|completed)\\b"
                    + "|\\baccording\\s+to\\s+(?:the\\s+)?(?:tool|function|api)"
                    + "(?:\\s+(?:call|execution))?\\s+(?:result|response|output)\\b"
                    + "|\\b(?:the\\s+)?(?:tool|function|api)(?:\\s+(?:call|execution))?\\s+"
                    + "(?:returned|reported)\\b"
                    + "|(?:工具|函数|接口)(?:调用|执行)?(?:结果|响应|输出)"
                    + "(?:显示|表明|证明|返回|确认|成功|完成)"
                    + "|(?:工具|函数|接口)(?:调用|执行)?(?:已返回|返回了|已报告))");
    private static final Pattern UNSUPPORTED_PLATFORM_MUTATION_CLAIM = Pattern.compile(
            "(?is)(?:\\b(?:I|we)(?:'ve|\\s+have)?\\s+(?:already\\s+|successfully\\s+)*"
                    + "(?:added|created|deleted|removed|renamed|updated|modified|changed|applied|ran|started|"
                    + "stopped|cancelled|canceled|verified|simulated|saved|reset|replaced|imported|completed|"
                    + "finished)\\b[^.!?。！？]{0,160}?\\b(?:devices?|rules?|specs?|specifications?|board|scene|"
                    + "simulation|verification|traces?|templates?|environment\\s+variables?|model)\\b"
                    + "|\\b(?:added|created|deleted|removed|renamed|updated|modified|changed|applied|ran|started|"
                    + "stopped|cancelled|canceled|verified|simulated|saved|reset|replaced|imported|completed|"
                    + "finished)\\s+(?:all|every|the|your|current|these|those|our)\\s+"
                    + "(?:devices?|rules?|specs?|specifications?|board|scene|simulation|verification|traces?|"
                    + "templates?|environment\\s+variables?|model)\\b"
                    + "|\\b(?:all|every|the|your|current|these|those|our)\\s+"
                    + "(?:devices?|rules?|specs?|specifications?|board|scene|traces?|templates?|"
                    + "environment\\s+variables?|model)\\s+(?:successfully\\s+)?"
                    + "(?:added|created|deleted|removed|renamed|updated|modified|changed|applied|saved|reset|"
                    + "replaced|imported)\\b"
                    + "|\\b(?:the|all|every|your|current|these|those|our)?\\s*"
                    + "(?:devices?|rules?|specs?|specifications?|board|scene|simulation|verification|traces?|"
                    + "templates?|environment\\s+variables?|model)\\b[^.!?。！？]{0,80}"
                    + "\\b(?:was|were|has\\s+been|have\\s+been|is\\s+now|are\\s+now)\\s+"
                    + "(?:successfully\\s+)?"
                    + "(?:added|created|deleted|removed|renamed|updated|modified|changed|applied|saved|reset|"
                    + "replaced|imported)\\b"
                    + "|\\b(?:the|your|current|our)?\\s*(?:simulation|verification)\\b[^.!?。！？]{0,80}"
                    + "\\b(?:was|has\\s+been|is\\s+now)\\s+(?:successfully\\s+)?"
                    + "(?:started|stopped|cancelled|canceled|verified|simulated|completed|finished)\\b"
                    + "|\\b(?:the|all|every|your|current|these|those|our)?\\s*"
                    + "(?:simulation|verification|model)\\b[^.!?。！？]{0,40}\\b"
                    + "(?:is|was|has\\s+been)\\s+(?:now\\s+)?(?:complete|completed|successful|finished)\\b"
                    + "|(?:(?:我|本助手|我们)(?:已经|已)?(?:成功)?|(?:已经|已)(?:成功)?)"
                    + "(?:添加|创建|删除|移除|重命名|更新|修改|应用|运行|启动|停止|取消|验证|模拟|保存|重置|"
                    + "替换|导入|完成)(?!的)(?:了)?[^.!?。！？]{0,160}?"
                    + "(?:设备|规则|规约|画布|场景|仿真|模拟|验证|轨迹|模板|环境变量)"
                    + "|(?:全部|所有|你的|您的|当前|这些|该)?"
                    + "(?:设备|规则|规约|画布|场景|仿真|模拟|验证|轨迹|模板|环境变量)"
                    + "[^.!?。！？]{0,80}(?:已经|已)(?:成功)?(?:被)?"
                    + "(?:添加|创建|删除|移除|重命名|更新|修改|应用|保存|重置|替换|导入)(?!的)"
                    + "|(?:(?:我|本助手|我们)(?:已经|已)?(?:成功)?)"
                    + "(?:更新|修改|重置|替换)(?:了)?(?:当前|你的|您的)?模型"
                    + "|(?:当前|你的|您的)模型[^.!?。！？]{0,40}(?:已经|已)(?:成功)?(?:被)?"
                    + "(?:更新|修改|重置|替换)"
                    + "|(?:当前|该)?(?:仿真|模拟|验证)[^.!?。！？]{0,80}?(?:已经|已)(?:成功)?(?:被)?"
                    + "(?:运行|启动|停止|取消|验证|模拟|完成))");
    private static final Pattern NON_OPERATIONAL_PLATFORM_CONTENT = Pattern.compile(
            "(?is)(?:\\b(?:created|drafted|wrote|generated|proposed)\\b[^.!?。！？]{0,48}"
                    + "\\b(?:sample|example|illustrative|hypothetical|draft)\\s+"
                    + "(?:device|rule|spec|specification|scene|model|configuration)\\b"
                    + "|(?:创建|生成|编写|起草|提供)(?:了)?[^.!?。！？]{0,48}"
                    + "(?:示例|样例|假设|草案|参考)(?:设备|规则|规约|场景|模型|配置)"
                    + "|(?:已)?(?:删除|移除|重命名|更新|修改|取消)的"
                    + "(?:设备|规则|规约|画布|场景|轨迹|模板|环境变量|模型))");
    private static final Pattern NON_OPERATIONAL_CLAIM_SPAN = Pattern.compile(
            "(?is)(?:[零一二三四五六七八九十百千两\\d]+(?:个|条|份|项)|这(?:些|个|条|份|项)|那(?:些|个|条|份|项))"
                    + "已(?:保存|创建|导入)(?:设备|规则|规约|场景|模板|环境变量)"
                    + "|(?:没有|暂无|尚无|不存在|(?:尚|暂)?(?:未|没(?:有)?)(?:找到|查到|检索到))(?:任何)?(?:已经|已)(?:完成|保存)(?:的)?(?:正式)?"
                    + "(?:验证(?:运行|结果|记录)?|仿真(?:运行|结果|记录)?|反例(?:轨迹|记录)?|运行|结果|记录)"
                    + "|(?:(?:我|本助手|我们)(?:已经|已)?(?:成功)?|(?:已经|已)(?:成功)?)"
                    + "完成(?:了)?(?:对)?(?:当前|这些|所有|上述|已有|全部|[零一二三四五六七八九十百千两\\d]+(?:个|条|项))?"
                    + "(?:设备|规则|规约|画布|场景|模型|仿真|验证|轨迹|模板|环境变量)(?:之间|间)?(?:的)?"
                    + "(?:触发链路|链路|条件|状态|行为|影响)?(?:的)?(?:静态)?(?:分析|梳理|整理|说明|审查)"
                    + "|\\b(?:I|we)(?:'ve|\\s+have)?\\s+(?:completed|finished)\\s+(?:the\\s+)?"
                    + "(?:static\\s+)?(?:analysis|review|inspection|explanation|audit)\\s+(?:of|for)\\s+"
                    + "(?:(?:the|current|these|all|your)\\s+)*(?:devices?|rules?|specs?|specifications?|board|scene|model)\\b");
    private static final Pattern NEGATED_COMPLETION_CLAIM = Pattern.compile(
            "(?:不能|无法|不应)(?:声称|宣称|称为|说成)(已经|已)(?:成功)?(?:被)?"
                    + "(?:运行|启动|停止|取消|验证|模拟|完成)");
    private static final Pattern UNSUPPORTED_PLATFORM_READ_CLAIM = Pattern.compile(
            "(?is)(?:\\b(?:your(?:\\s+current)?|the\\s+current|current)\\s+"
                    + "(?:board|scene)\\s+(?:currently\\s+)?(?:contains?|has|includes?|shows?|lists?)\\b"
                    + "|\\b(?:I|we)(?:'ve|\\s+have)?\\s+"
                    + "(?:checked|inspected|read|reviewed|examined|looked\\s+at)\\s+"
                    + "(?:your(?:\\s+current)?|the(?:\\s+current)?|current)\\s+"
                    + "(?:board|scene|devices?|rules?|specs?|specifications?|model)\\b"
                    + "|\\b(?:I|we)(?:'ve|\\s+have)?\\s+(?:(?:can|could)\\s+)?"
                    + "(?:see|saw|seen|found|observ(?:e|ed)|list(?:ed)?)\\b[^.!?。！？]{0,96}"
                    + "\\b(?:devices?|rules?|specs?|specifications?)\\b[^.!?。！？]{0,48}"
                    + "\\b(?:on|in)\\s+(?:your(?:\\s+current)?|the(?:\\s+current)?|current)\\s+"
                    + "(?:board|scene)\\b"
                    + "|\\bthere\\s+(?:is|are)\\s+[^.!?。！？]{0,80}"
                    + "\\b(?:devices?|rules?|specs?|specifications?)\\b[^.!?。！？]{0,40}"
                    + "\\b(?:on|in)\\s+(?:your(?:\\s+current)?|the\\s+current|current)\\s+"
                    + "(?:board|scene)\\b"
                    + "|(?:当前|你的|您的)(?:画布|场景)(?:中)?(?:有|包含|共有|显示)"
                    + "|(?:我|我们|本助手)(?:已经|已)?(?:查看|检查|读取|核对)(?:了)?"
                    + "(?:当前|你的|您的)(?:画布|场景|设备|规则|规约|模型))");
    private static final Pattern NON_ASSERTIVE_CLAIM_CONTEXT = Pattern.compile(
            "(?is)(?:\\b(?:whether|hypothetically)\\b|\\bif\\s+(?:the|your|this|that)\\b"
                    + "|(?:是否|假设|如果))");
    private static final Pattern HISTORICAL_CLAIM_CONTEXT = Pattern.compile(
            "(?is)(?:\\b(?:previously|historically)\\b"
                    + "|\\b(?:during|in|from)\\s+(?:the\\s+)?"
                    + "(?:previous|prior|earlier|last|historical)\\b"
                    + "|(?:在|于)?(?:上次|先前|此前|之前|过去)(?:迁移|运行|记录|会话)?(?:中|期间)?)");
    private static final Pattern CURRENT_CLAIM_CONTEXT = Pattern.compile(
            "(?is)(?:\\b(?:current|now|this\\s+turn)\\b"
                    + "|\\byour(?:\\s+current)?\\s+(?:board|scene)\\b"
                    + "|(?:当前|本轮|现在|你的画布|您的画布))");
    private static final Pattern LEADING_HISTORICAL_CLAIM_CONTEXT = Pattern.compile(
            "(?is)^\\s*(?:previously|historically"
                    + "|(?:during|in|from)\\s+(?:the\\s+)?"
                    + "(?:previous|prior|earlier|last|historical)\\b[^,，.!?。！？]{0,48}"
                    + "|(?:在|于)?(?:上次|先前|此前|之前|过去)(?:迁移|运行|记录|会话)?(?:中|期间)?)"
                    + "\\s*[,，]\\s*(?:(?:the|all|every|your|these|those|our)\\s+"
                    + "|(?:全部|所有|你的|您的|这些|该)\\s*)*$");
    private static final Pattern CLAIM_CLAUSE_BOUNDARY = Pattern.compile(
            "(?is)[,，;；:：\\n]|\\b(?:but|however|yet|then|and)\\b|(?:但是|不过|然而|然后|并且|而且|但)");
    private static final Pattern COORDINATED_CLAIM_SUFFIX = Pattern.compile(
            "(?is)^\\s*[,，;；]?\\s*(?:并且|而且|同时|并|且|\\b(?:and|then)\\b)\\s*(.+)");
    private static final Set<String> TOOL_RESULT_OUTCOMES = Set.of(
            "USABLE", "PARTIAL", "FAILED", "RESULT_UNAVAILABLE", "CONFIRMATION_REQUIRED");
    private static final Set<String> EXECUTION_GUARD_OUTCOMES = Set.of(
            "NO_PROGRESS", "EMERGENCY_LIMIT");

    private final ChatSessionRepository sessionRepo;
    private final ChatMessageRepository messageRepo;
    private final UserRepository userRepository;
    private final LlmChatService llmChatService;
    private final LlmMessageCodec messageCodec;
    private final ChatConfirmationDetector chatConfirmationDetector;
    private final AiToolManager aiToolManager;
    private final AiDestructiveActionGuard destructiveActionGuard;
    private final AiScenarioDraftStore scenarioDraftStore;
    private final AiTaskContinuationStore taskContinuationStore;
    private final ObjectMapper objectMapper;
    private final ChatToolProgressPresenter toolProgressPresenter;
    private final ChatMapper chatMapper;
    private final TransactionTemplate transactionTemplate;
    private final ChatExecutionConfig chatExecutionConfig;
    private final ConcurrentMap<String, ActiveStreamRequest> activeStreamRequests = new ConcurrentHashMap<>();

    @Override
    @Transactional(readOnly = true)
    public List<ChatSessionResponseDto> getUserSessions(Long userId) {
        LocalDateTime now = databaseNow();
        List<ChatSessionPo> sessions = sessionRepo.findTop100ByUserIdOrderByUpdatedAtDesc(userId);
        List<ChatSessionResponseDto> response = chatMapper.toChatSessionDtoList(sessions);
        Map<String, ChatMessageRepository.LatestTerminalView> latestTerminals = sessions.isEmpty()
                ? Map.of()
                : messageRepo.findLatestTerminalBySessionIdIn(
                                sessions.stream().map(ChatSessionPo::getId).toList()).stream()
                        .collect(Collectors.toMap(
                                ChatMessageRepository.LatestTerminalView::getSessionId,
                                terminal -> terminal));
        for (int index = 0; index < response.size(); index++) {
            ChatSessionPo session = sessions.get(index);
            ChatSessionResponseDto sessionResponse = response.get(index);
            sessionResponse.setActive(hasActiveExecutionLease(session, now));
            ChatMessageRepository.LatestTerminalView latestTerminal = latestTerminals.get(session.getId());
            if (latestTerminal != null) {
                sessionResponse.setLatestTerminalMessageId(latestTerminal.getMessageId());
                sessionResponse.setLatestExecutionStatus(latestTerminal.getExecutionStatus());
                sessionResponse.setHasUnreadUpdate(session.getLastSeenTerminalMessageId() == null
                        || latestTerminal.getMessageId() > session.getLastSeenTerminalMessageId());
            }
        }
        return response;
    }

    @Override
    @Transactional
    public ChatSessionResponseDto createSession(Long userId) {
        requireActiveUserForWrite(userId);
        if (sessionRepo.countByUserId(userId) >= RequestLimits.MAX_CHAT_SESSIONS) {
            throw new BadRequestException("At most 100 chat sessions can be stored per user. Delete an old session first.");
        }
        ChatSessionPo session = new ChatSessionPo();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(null);
        LocalDateTime now = databaseNow();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return chatMapper.toChatSessionDto(sessionRepo.save(session));
    }

    @Override
    @Transactional(readOnly = true)
    public ChatPendingConfirmationDto getPendingConfirmation(Long userId, String sessionId) {
        sessionRepo.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
        return ChatPendingConfirmationDto.builder()
                .sessionId(sessionId)
                .actions(pendingProtectedActions(
                        destructiveActionGuard.pendingContext(userId, sessionId).orElse(null),
                        scenarioDraftStore.pendingApplication(userId, sessionId).orElse(null)))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ChatHistoryPageDto getHistoryPage(Long userId, String sessionId, Long beforeId, int limit) {
        sessionRepo.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
        int pageSize = Math.min(RequestLimits.MAX_CHAT_HISTORY_PAGE_SIZE,
                Math.max(1, limit));
        int rawPageSize = Math.min(200, RequestLimits.MAX_CHAT_HISTORY_RAW_SCAN);
        List<ChatMessagePo> newestFirst = new ArrayList<>();
        Long cursor = beforeId;
        boolean exhausted = false;
        while (newestFirst.size() < RequestLimits.MAX_CHAT_HISTORY_RAW_SCAN) {
            int remaining = Math.min(rawPageSize,
                    RequestLimits.MAX_CHAT_HISTORY_RAW_SCAN - newestFirst.size());
            List<ChatMessagePo> chunk = cursor == null
                    ? messageRepo.findBySessionIdOrderByIdDesc(sessionId, PageRequest.of(0, remaining))
                    : messageRepo.findBySessionIdAndIdLessThanOrderByIdDesc(
                            sessionId, cursor, PageRequest.of(0, remaining));
            if (chunk == null || chunk.isEmpty()) {
                exhausted = true;
                break;
            }
            newestFirst.addAll(chunk);
            ChatMessagePo oldest = chunk.get(chunk.size() - 1);
            cursor = oldest == null ? cursor : oldest.getId();
            if (chunk.size() < remaining || oldest == null || oldest.getId() == null) {
                exhausted = true;
                break;
            }
            List<ChatMessagePo> scannedChronologically = new ArrayList<>(newestFirst);
            java.util.Collections.reverse(scannedChronologically);
            if (filterFrontendVisibleMessages(scannedChronologically).size() > pageSize) {
                break;
            }
        }

        List<ChatMessagePo> allMessages = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(allMessages);
        List<ChatMessagePo> visibleMessages = filterFrontendVisibleMessages(allMessages);
        boolean hasMore = visibleMessages.size() > pageSize || !exhausted;
        int fromIndex = Math.max(0, visibleMessages.size() - pageSize);
        List<ChatMessagePo> selected = visibleMessages.subList(fromIndex, visibleMessages.size());
        List<ChatMessageResponseDto> response = chatMapper.toChatMessageDtoList(selected);
        attachPersistedExecutionTraces(selected, response);
        Long nextBeforeId = null;
        if (hasMore) {
            if (!selected.isEmpty()) {
                nextBeforeId = selected.get(0).getId();
            } else if (!allMessages.isEmpty()) {
                nextBeforeId = allMessages.get(0).getId();
            }
        }
        return ChatHistoryPageDto.builder()
                .messages(response)
                .nextBeforeId(nextBeforeId)
                .hasMore(hasMore && nextBeforeId != null)
                .build();
    }

    @Override
    public void markTerminalSeen(Long userId, String sessionId, Long terminalMessageId) {
        if (terminalMessageId == null || terminalMessageId <= 0) {
            throw new BadRequestException("Terminal message id must be a positive integer.");
        }
        synchronized (sessionLock(sessionId)) {
            transactionTemplate.executeWithoutResult(status -> {
                requireActiveUserForWrite(userId);
                ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
                if (!messageRepo.existsByIdAndSessionIdAndRoleAndExecutionStatusIsNotNull(
                        terminalMessageId, sessionId, "assistant")) {
                    throw new BadRequestException(
                            "The acknowledged message is not a terminal assistant message in this session.");
                }
                Long currentCursor = session.getLastSeenTerminalMessageId();
                if (currentCursor == null || terminalMessageId > currentCursor) {
                    session.setLastSeenTerminalMessageId(terminalMessageId);
                    sessionRepo.saveAndFlush(session);
                }
            });
        }
    }

    @Override
    @Transactional
    public void deleteSession(Long userId, String sessionId) {
        synchronized (sessionLock(sessionId)) {
            requireActiveUserForWrite(userId);
            ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(sessionId, userId)
                    .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
            if (hasActiveExecutionLease(session, databaseNow())) {
                throw new ChatSessionBusyException(sessionId);
            }
            messageRepo.deleteBySessionId(sessionId);
            sessionRepo.deleteById(Objects.requireNonNull(sessionId, "sessionId must not be null"));
            destructiveActionGuard.clearSession(userId, sessionId);
            scenarioDraftStore.clearSession(userId, sessionId);
            taskContinuationStore.clearSession(userId, sessionId);
        }
    }

    @Override
    public String beginStreamRequest(Long userId, String sessionId, String turnId, String content) {
        String effectiveTurnId = requireTurnId(turnId);
        String effectiveContent = requireTurnContent(content);
        synchronized (sessionLock(sessionId)) {
            AtomicReference<ActiveStreamRequest> claimed = new AtomicReference<>();
            AtomicLong confirmationStartedNanos = new AtomicLong(Long.MIN_VALUE);
            AtomicBoolean stoppedBeforeAdmission = new AtomicBoolean();
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    requireActiveUserForWrite(userId);
                    ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(sessionId, userId)
                            .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
                    LocalDateTime stopFenceNow = databaseNow();
                    purgeExpiredPreAdmissionStops(session, stopFenceNow);
                    if (session.getPreAdmissionStopTurns().containsKey(effectiveTurnId)) {
                        session.getPreAdmissionStopTurns().remove(effectiveTurnId);
                        sessionRepo.saveAndFlush(session);
                        stoppedBeforeAdmission.set(true);
                        return;
                    }
                    confirmationStartedNanos.set(System.nanoTime());
                    LocalDateTime now = databaseNow();
                    if (hasActiveExecutionLease(session, now)) {
                        throw new ChatSessionBusyException(sessionId);
                    }
                    long messageCount = messageRepo.countBySessionId(sessionId);
                    long requiredTurnCapacity = requiredMessageCapacityForTurn();
                    if (messageCount + requiredTurnCapacity > chatExecutionConfig.getMaxMessagesPerSession()) {
                        throw new ChatHistoryQuotaExceededException(
                                messageCount,
                                chatExecutionConfig.getMaxMessagesPerSession(),
                                requiredTurnCapacity);
                    }
                    if (messageRepo.existsBySessionIdAndTurnId(sessionId, effectiveTurnId)) {
                        throw new ConflictException("Turn id has already been used in this chat session.");
                    }
                    String requestId = UUID.randomUUID().toString();
                    session.setActiveExecutionId(requestId);
                    session.setActiveExecutionTurnId(effectiveTurnId);
                    session.setActiveExecutionExpiresAt(now.plus(executionLeaseTtl()));
                    session.setExecutionStopRequested(false);
                    session.setExecutionUserStopRequested(false);
                    sessionRepo.saveAndFlush(session);
                    saveUserTurn(sessionId, effectiveTurnId, requestId, effectiveContent);
                    claimed.set(new ActiveStreamRequest(
                            userId, requestId, effectiveTurnId, effectiveContent,
                            confirmationStartedNanos.get()));
                });
            } catch (RuntimeException admissionFailure) {
                ActiveStreamRequest ambiguousRequest = claimed.get();
                if (ambiguousRequest == null) throw admissionFailure;
                try {
                    compensateAmbiguousAdmission(userId, sessionId, ambiguousRequest);
                } catch (RuntimeException compensationFailure) {
                    ChatAdmissionOutcomeUnknownException unknown =
                            new ChatAdmissionOutcomeUnknownException(
                                    "Could not confirm the outcome of the chat admission transaction.",
                                    admissionFailure);
                    unknown.addSuppressed(compensationFailure);
                    throw unknown;
                }
                throw new ServiceUnavailableException(
                        "Chat admission could not be committed; retry", admissionFailure);
            }
            if (stoppedBeforeAdmission.get()) {
                throw new ConflictException("This chat turn was stopped before execution began.");
            }
            ActiveStreamRequest request = claimed.get();
            if (request == null) {
                throw new IllegalStateException("Could not claim the chat execution lease.");
            }
            if (!leaseRoundTripLeavesHeartbeatWindow(
                    confirmationStartedNanos.get(), executionLeaseTtl())) {
                abortClaimedStreamRequest(userId, sessionId, request);
                throw new ServiceUnavailableException(
                        "Chat execution lease expired before the request was accepted; retry");
            }
            ActiveStreamRequest existing = activeStreamRequests.putIfAbsent(sessionId, request);
            if (existing != null) {
                abortClaimedStreamRequest(userId, sessionId, request);
                throw new ChatSessionBusyException(sessionId);
            }
            if (!leaseRoundTripLeavesHeartbeatWindow(
                    confirmationStartedNanos.get(), executionLeaseTtl())) {
                abortClaimedStreamRequest(userId, sessionId, request);
                throw new ServiceUnavailableException(
                        "Chat execution lease expired before the request was accepted; retry");
            }
            return request.requestId();
        }
    }

    @Override
    public void abortUndispatched(
            Long userId, String sessionId, String executionId, String turnId) {
        String effectiveTurnId = requireTurnId(turnId);
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("Execution id is required to abort an undispatched chat turn.");
        }
        synchronized (sessionLock(sessionId)) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(sessionId, userId)
                            .orElse(null);
                    if (session == null) {
                        requireAdmittedUserTurnAbsent(sessionId, effectiveTurnId, executionId);
                        return;
                    }
                    int deleted = messageRepo.deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                            sessionId, effectiveTurnId, executionId, "user");
                    if (deleted != 1) {
                        throw new IllegalStateException(
                                "The admitted user turn could not be identified uniquely for rollback.");
                    }
                    if (Objects.equals(executionId, session.getActiveExecutionId())) {
                        clearExecutionLease(session);
                        sessionRepo.saveAndFlush(session);
                    }
                });
            } finally {
                activeStreamRequests.computeIfPresent(sessionId, (ignored, request) ->
                        Objects.equals(request.userId(), userId)
                                && Objects.equals(request.requestId(), executionId)
                                && Objects.equals(request.turnId(), effectiveTurnId)
                                ? null : request);
            }
        }
    }

    private void abortClaimedStreamRequest(
            Long userId, String sessionId, ActiveStreamRequest request) {
        try {
            abortUndispatched(userId, sessionId, request.requestId(), request.turnId());
        } catch (RuntimeException rollbackFailure) {
            throw new ChatAdmissionOutcomeUnknownException(
                    "Could not confirm rollback of the persisted chat admission.", rollbackFailure);
        }
    }

    private void compensateAmbiguousAdmission(
            Long userId, String sessionId, ActiveStreamRequest request) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElse(null);
                if (session == null) {
                    requireAdmittedUserTurnAbsent(
                            sessionId, request.turnId(), request.requestId());
                    return;
                }
                int deleted = messageRepo.deleteBySessionIdAndTurnIdAndExecutionIdAndRole(
                        sessionId, request.turnId(), request.requestId(), "user");
                if (deleted == 0 && messageRepo.existsBySessionIdAndTurnId(
                        sessionId, request.turnId())) {
                    throw new IllegalStateException(
                            "The ambiguous admission turn belongs to a different execution.");
                }
                if (deleted > 1) {
                    throw new IllegalStateException(
                            "The ambiguous admission matched multiple user turns.");
                }
                if (Objects.equals(request.requestId(), session.getActiveExecutionId())) {
                    clearExecutionLease(session);
                    sessionRepo.saveAndFlush(session);
                }
            });
        } finally {
            activeStreamRequests.remove(sessionId, request);
        }
    }

    private void requireAdmittedUserTurnAbsent(
            String sessionId, String turnId, String executionId) {
        if (messageRepo.existsBySessionIdAndTurnIdAndExecutionIdAndRole(
                sessionId, turnId, executionId, "user")) {
            throw new IllegalStateException(
                    "The chat session disappeared while its admitted user turn still exists.");
        }
    }

    @Override
    public void endStreamRequest(Long userId, String sessionId, String executionId) {
        if (sessionId == null || executionId == null || executionId.isBlank()) return;
        activeStreamRequests.computeIfPresent(sessionId, (ignored, request) ->
                Objects.equals(request.userId(), userId)
                        && Objects.equals(request.requestId(), executionId)
                        ? null
                        : request);
        releaseExecutionLease(userId, sessionId, executionId);
    }

    @Override
    public void requestStreamStop(Long userId, String sessionId, String turnId) {
        String effectiveTurnId = turnId == null ? null : requireTurnId(turnId);
        synchronized (sessionLock(sessionId)) {
            AtomicReference<String> requestIdRef = new AtomicReference<>();
            transactionTemplate.executeWithoutResult(status -> {
                ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(sessionId, userId)
                        .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
                LocalDateTime now = databaseNow();
                if (!hasActiveExecutionLease(session, now)) {
                    if (effectiveTurnId == null
                            || Objects.equals(effectiveTurnId, session.getActiveExecutionTurnId())) {
                        requestIdRef.set(session.getActiveExecutionId());
                    }
                    clearExecutionLease(session);
                    recordPreAdmissionStopIfNeeded(session, effectiveTurnId, now);
                    sessionRepo.saveAndFlush(session);
                    return;
                }
                if (effectiveTurnId != null
                        && !Objects.equals(effectiveTurnId, session.getActiveExecutionTurnId())) {
                    recordPreAdmissionStopIfNeeded(session, effectiveTurnId, now);
                    sessionRepo.saveAndFlush(session);
                    return;
                }
                session.setExecutionStopRequested(true);
                session.setExecutionUserStopRequested(true);
                sessionRepo.saveAndFlush(session);
                requestIdRef.set(session.getActiveExecutionId());
            });
            String requestId = requestIdRef.get();
            ActiveStreamRequest request = activeStreamRequests.get(sessionId);
            if (request != null && Objects.equals(request.userId(), userId)
                    && Objects.equals(request.requestId(), requestId)) {
                stopActiveRequest(sessionId, request, true);
            }
        }
    }

    private void recordPreAdmissionStopIfNeeded(
            ChatSessionPo session, String turnId, LocalDateTime now) {
        if (turnId == null || messageRepo.existsBySessionIdAndTurnId(session.getId(), turnId)) return;
        Map<String, LocalDateTime> stoppedTurns = session.getPreAdmissionStopTurns();
        purgeExpiredPreAdmissionStops(session, now);
        if (!stoppedTurns.containsKey(turnId) && stoppedTurns.size() >= MAX_PRE_ADMISSION_STOP_TURNS) {
            throw new ConflictException(
                    "Too many chat turns are waiting for pre-admission stop acknowledgement.");
        }
        stoppedTurns.putIfAbsent(turnId, now);
    }

    private void purgeExpiredPreAdmissionStops(ChatSessionPo session, LocalDateTime now) {
        LocalDateTime cutoff = now.minus(PRE_ADMISSION_STOP_TTL);
        session.getPreAdmissionStopTurns().entrySet().removeIf(entry ->
                entry.getValue() == null || !entry.getValue().isAfter(cutoff));
    }

    @Override
    public void requestUserExecutionStop(Long userId) {
        if (userId == null) return;
        markUserExecutionsStopped(userId, true);
        stopLocalUserExecutions(userId, true);
    }

    @Override
    public void requestLocalUserExecutionStop(Long userId) {
        if (userId == null) return;
        try {
            markUserExecutionsStopped(userId, false);
        } catch (RuntimeException e) {
            log.warn("Could not mark chat sessions stopped for deleted user {}", userId, e);
        }
        stopLocalUserExecutions(userId, false);
        runUserCleanupStep("destructive-action confirmations", () -> destructiveActionGuard.clearUser(userId));
        runUserCleanupStep("scenario drafts", () -> scenarioDraftStore.clearUser(userId));
        runUserCleanupStep("task continuations", () -> taskContinuationStore.clearUser(userId));
    }

    private void markUserExecutionsStopped(Long userId, boolean userInitiated) {
        independentTransactionTemplate().executeWithoutResult(status -> {
            List<ChatSessionPo> sessions = sessionRepo.findByUserIdForUpdate(userId);
            LocalDateTime now = databaseNow();
            for (ChatSessionPo session : sessions) {
                if (!hasActiveExecutionLease(session, now)) continue;
                session.setExecutionStopRequested(true);
                session.setExecutionUserStopRequested(userInitiated);
            }
            sessionRepo.saveAllAndFlush(sessions);
        });
    }

    private void stopLocalUserExecutions(Long userId, boolean userInitiated) {
        activeStreamRequests.forEach((sessionId, request) -> {
            if (!Objects.equals(request.userId(), userId)) return;
            stopActiveRequest(sessionId, request, userInitiated);
        });
    }

    private TransactionTemplate independentTransactionTemplate() {
        TransactionTemplate independent = new TransactionTemplate(
                Objects.requireNonNull(transactionTemplate.getTransactionManager(),
                        "transaction manager is required"));
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return independent;
    }

    private void runUserCleanupStep(String resource, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException e) {
            log.warn("Could not clear {} during account cleanup", resource, e);
        }
    }

    private void stopActiveRequest(String sessionId, ActiveStreamRequest request, boolean userInitiated) {
        if (userInitiated) {
            request.userStopRequested().set(true);
        }
        request.stopRequested().set(true);
        request.requestControl().cancel();
        SseEmitter emitter = request.emitter().get();
        if (emitter == null) return;
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("Chat emitter was already complete while stopping session {}", sessionId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ChatSessionActivityDto getSessionActivity(Long userId, String sessionId) {
        ChatSessionPo session = sessionRepo.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
        return ChatSessionActivityDto.builder()
                .sessionId(sessionId)
                .active(hasActiveExecutionLease(session, databaseNow()))
                .build();
    }

    private void releaseExecutionLease(Long userId, String sessionId, String requestId) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(sessionId, userId).orElse(null);
            if (session == null || !Objects.equals(session.getActiveExecutionId(), requestId)) return;
            clearExecutionLease(session);
            sessionRepo.saveAndFlush(session);
        });
    }

    private boolean hasActiveExecutionLease(ChatSessionPo session, LocalDateTime now) {
        return session != null
                && session.getActiveExecutionId() != null
                && !session.getActiveExecutionId().isBlank()
                && session.getActiveExecutionExpiresAt() != null
                && session.getActiveExecutionExpiresAt().isAfter(now);
    }

    private void clearExecutionLease(ChatSessionPo session) {
        session.setActiveExecutionId(null);
        session.setActiveExecutionTurnId(null);
        session.setActiveExecutionExpiresAt(null);
        session.setExecutionStopRequested(false);
        session.setExecutionUserStopRequested(false);
    }

    @Scheduled(fixedDelayString = "${chat.execution.lease-heartbeat-ms:10000}")
    public void maintainExecutionLeases() {
        try {
            databaseNow();
        } catch (RuntimeException e) {
            stopChatExecutionsWithExpiredConfirmation(e);
            return;
        }
        try {
            int cleared = sessionRepo.clearExpiredExecutionLeases(databaseNow());
            if (cleared > 0) {
                log.debug("Cleared {} expired chat execution lease(s)", cleared);
            }
        } catch (RuntimeException e) {
            log.warn("Could not clear expired chat execution leases: {}", e.toString());
        }
        Map<String, ActiveStreamRequest> requests = Map.copyOf(activeStreamRequests);
        ExecutionLeaseRenewalBatch renewal = ExecutionLeaseRenewalBatch.none();
        try {
            renewal = renewActiveExecutionLeases(requests);
            ExecutionLeaseRenewalBatch confirmedRenewal = renewal;
            requests.forEach((sessionId, request) -> {
                if (activeStreamRequests.get(sessionId) != request) return;
                ExecutionLeaseState state = confirmedRenewal.renewedLeases().get(sessionId);
                if (state == null || !Objects.equals(state.requestId(), request.requestId())) {
                    if (activeStreamRequests.remove(sessionId, request)) {
                        stopActiveRequest(sessionId, request, false);
                        log.warn("Stopped local chat execution after losing its lease: sessionId={}, executionId={}",
                                sessionId, request.requestId());
                    }
                    return;
                }
                request.leaseConfirmation().confirmAt(confirmedRenewal.confirmationStartedNanos());
                if (state.stopRequested()) {
                    stopActiveRequest(sessionId, request, state.userStopRequested());
                }
            });
        } catch (RuntimeException e) {
            requests.forEach((sessionId, request) -> {
                if (request.leaseConfirmation().isUnconfirmedFor(executionLeaseTtl())
                        && activeStreamRequests.remove(sessionId, request)) {
                    stopActiveRequest(sessionId, request, false);
                    log.warn("Stopped local chat execution after its lease could not be confirmed for a full TTL: "
                                    + "sessionId={}, executionId={}",
                            sessionId, request.requestId());
                } else {
                    log.warn("Could not renew chat execution lease for session {}: {}", sessionId, e.toString());
                }
            });
        }
        if (!renewal.renewedLeases().isEmpty()
                && !leaseRoundTripLeavesHeartbeatWindow(
                        renewal.confirmationStartedNanos(), executionLeaseTtl())) {
            renewal.renewedLeases().forEach((sessionId, state) -> {
                ActiveStreamRequest request = requests.get(sessionId);
                if (request != null && Objects.equals(state.requestId(), request.requestId())
                        && activeStreamRequests.remove(sessionId, request)) {
                    stopActiveRequest(sessionId, request, false);
                    log.warn("Stopped local chat execution because lease maintenance consumed its heartbeat window: "
                                    + "sessionId={}, executionId={}",
                            sessionId, request.requestId());
                }
            });
        }
    }

    private ExecutionLeaseRenewalBatch renewActiveExecutionLeases(
            Map<String, ActiveStreamRequest> requests) {
        if (requests.isEmpty()) return ExecutionLeaseRenewalBatch.none();

        Set<String> sessionIds = new LinkedHashSet<>(requests.keySet());
        long confirmationStartedNanos = System.nanoTime();
        long checkedAtNanos = confirmationStartedNanos;
        LocalDateTime checkedAt = databaseNow();
        Map<String, ExecutionLeaseState> renewedLeases = transactionTemplate.execute(status -> {
            List<ChatSessionPo> sessions = sessionRepo.findByIdInForUpdate(sessionIds);
            LocalDateTime databaseNow = databaseNow();
            long elapsedNanos = Math.max(0L, System.nanoTime() - checkedAtNanos);
            LocalDateTime monotonicNow = checkedAt.plusNanos(elapsedNanos);
            LocalDateTime effectiveNow = databaseNow.isAfter(monotonicNow) ? databaseNow : monotonicNow;
            List<ChatSessionPo> renewableSessions = new ArrayList<>();
            Map<String, ExecutionLeaseState> states = new HashMap<>();
            for (ChatSessionPo session : sessions) {
                ActiveStreamRequest request = requests.get(session.getId());
                if (request == null
                        || !Objects.equals(request.userId(), session.getUserId())
                        || !Objects.equals(request.requestId(), session.getActiveExecutionId())
                        || session.getActiveExecutionExpiresAt() == null
                        || !session.getActiveExecutionExpiresAt().isAfter(effectiveNow)) {
                    continue;
                }
                renewableSessions.add(session);
                states.put(session.getId(), new ExecutionLeaseState(
                        request.requestId(),
                        Boolean.TRUE.equals(session.getExecutionStopRequested()),
                        Boolean.TRUE.equals(session.getExecutionUserStopRequested())));
            }
            if (renewableSessions.isEmpty()) return Map.of();

            LocalDateTime renewedExpiry = effectiveNow.plus(executionLeaseTtl());
            renewableSessions.forEach(session -> session.setActiveExecutionExpiresAt(renewedExpiry));
            sessionRepo.flush();
            return Map.copyOf(states);
        });
        if (renewedLeases == null || renewedLeases.isEmpty()
                || !leaseRoundTripLeavesHeartbeatWindow(
                        confirmationStartedNanos, executionLeaseTtl())) {
            return ExecutionLeaseRenewalBatch.none();
        }
        return new ExecutionLeaseRenewalBatch(
                renewedLeases, confirmationStartedNanos);
    }

    private boolean leaseRoundTripLeavesHeartbeatWindow(long startedNanos, Duration ttl) {
        if (startedNanos == Long.MIN_VALUE || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        Duration heartbeat = Duration.ofMillis(chatExecutionConfig.getLeaseHeartbeatMs());
        if (heartbeat.isZero() || heartbeat.isNegative() || ttl.compareTo(heartbeat) <= 0) {
            return false;
        }
        long elapsedNanos = System.nanoTime() - startedNanos;
        return elapsedNanos >= 0 && elapsedNanos < ttl.minus(heartbeat).toNanos();
    }

    private void stopChatExecutionsWithExpiredConfirmation(RuntimeException cause) {
        activeStreamRequests.forEach((sessionId, request) -> {
            if (request.leaseConfirmation().isUnconfirmedFor(executionLeaseTtl())
                    && activeStreamRequests.remove(sessionId, request)) {
                stopActiveRequest(sessionId, request, false);
                log.warn("Stopped local chat execution after the database could not confirm its lease for a full TTL: "
                                + "sessionId={}, executionId={}",
                        sessionId, request.requestId());
            }
        });
        log.warn("Could not read database time while maintaining chat execution leases: {}", cause.toString());
    }

    private Duration executionLeaseTtl() {
        return Duration.ofMillis(chatExecutionConfig.getLeaseTtlMs());
    }

    private LocalDateTime databaseNow() {
        return Objects.requireNonNull(
                sessionRepo.currentDatabaseTime(),
                "Database current timestamp must not be null");
    }

    private Object sessionLock(String sessionId) {
        String requiredSessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        int hash = requiredSessionId.hashCode();
        hash ^= hash >>> 16;
        return SESSION_LOCKS[hash & (SESSION_LOCK_STRIPES - 1)];
    }

    private static Object[] createSessionLocks() {
        Object[] locks = new Object[SESSION_LOCK_STRIPES];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    @Override
    public void processStreamChat(Long userId,
                                  String sessionId,
                                  String executionId,
                                  String turnId,
                                  String content,
                                  String locale,
                                  ChatConfirmationCommandDto confirmation,
                                  SseEmitter emitter) {
        if (turnId == null || turnId.isBlank()) {
            throw new BadRequestException("Turn id is required");
        }
        String effectiveTurnId = turnId.trim();
        ActiveStreamRequest activeRequest = activeStreamRequests.get(sessionId);
        if (activeRequest == null
                || !Objects.equals(activeRequest.userId(), userId)
                || !Objects.equals(activeRequest.requestId(), executionId)) {
            log.warn("Rejected chat worker without its claimed execution lease: sessionId={}, executionId={}",
                    sessionId, executionId);
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                log.debug("Chat emitter was already complete for unowned execution {}", executionId);
            }
            return;
        }
        if (!activeRequest.matches(effectiveTurnId, content)) {
            log.error("Rejected chat worker whose turn payload differs from its admitted request: "
                            + "sessionId={}, executionId={}, turnId={}",
                    sessionId, executionId, effectiveTurnId);
            try {
                emitter.completeWithError(new IllegalStateException(
                        "The dispatched chat turn does not match its persisted admission."));
            } catch (IllegalStateException e) {
                log.debug("Chat emitter was already complete for mismatched execution {}", executionId);
            }
            return;
        }
        AiDestructiveActionGuard.PendingActionContext destructivePending;
        AiScenarioDraftStore.PendingApplication scenarioPending;
        AiTaskContinuationStore.ContinuationContext continuationContext;
        ConfirmationDecision confirmationDecision;
        boolean preferChinese = ChatLanguagePreference.prefersChinese(locale, content);
        StringBuilder finalAnswer = new StringBuilder();
        List<StreamResponseDto.ProgressDto> executionTrace = new ArrayList<>();
        long executionStartedNanos = System.nanoTime();
        AtomicBoolean isDisconnect = activeRequest.stopRequested();
        AtomicBoolean isUserStop = activeRequest.userStopRequested();
        BooleanSupplier shouldStop = () -> synchronizeExecutionStop(
                userId, sessionId, activeRequest, isDisconnect, isUserStop, false);
        BooleanSupplier forceStopCheck = () -> synchronizeExecutionStop(
                userId, sessionId, activeRequest, isDisconnect, isUserStop, true);
        activeRequest.emitter().compareAndSet(null, emitter);
        Set<StreamResponseDto.CommandDto> commandSet = new LinkedHashSet<>();
        Set<String> confirmedMutationTools = new LinkedHashSet<>();
        boolean frontendCommandsDelivered = false;
        boolean frontendCommandsDeliveryFailed = false;
        ToolLoopResult loopResult = ToolLoopResult.empty();
        TerminalPersistenceState terminalPersistence = TerminalPersistenceState.NOT_ATTEMPTED;
        AtomicBoolean serverCompletion = new AtomicBoolean(false);
        emitter.onCompletion(() -> {
            if (!serverCompletion.get()) {
                log.debug("Chat SSE transport detached; execution will continue in the background: "
                                + "sessionId={}, executionId={}",
                        sessionId, executionId);
            }
        });
        emitter.onTimeout(() -> {
            log.debug("Chat SSE transport timed out; execution will continue in the background: "
                            + "sessionId={}, executionId={}",
                    sessionId, executionId);
        });
        emitter.onError(ex -> {
            log.debug("Chat SSE transport failed; execution will continue in the background: "
                            + "sessionId={}, executionId={}, reason={}",
                    sessionId, executionId, ex == null ? "unknown" : ex.toString());
        });
        try {
            UserContextHolder.setUserId(userId);
            UserContextHolder.setChatSessionId(sessionId);
            UserContextHolder.setChatExecutionId(executionId);
            UserContextHolder.setChatUserMessage(content);
            LlmRequestControlHolder.set(activeRequest.requestControl());
            destructivePending = destructiveActionGuard.pendingContext(userId, sessionId).orElse(null);
            scenarioPending = scenarioDraftStore.pendingApplication(userId, sessionId).orElse(null);
            continuationContext = taskContinuationStore.pendingContext(userId, sessionId).orElse(null);
            EnumSet<ConfirmationKind> pendingKinds = EnumSet.noneOf(ConfirmationKind.class);
            if (destructivePending != null) {
                pendingKinds.add("reset_default_templates".equals(destructivePending.toolName())
                        ? ConfirmationKind.DEFAULT_TEMPLATE_RESET
                        : ConfirmationKind.DESTRUCTIVE);
            }
            if (scenarioPending != null) pendingKinds.add(ConfirmationKind.SCENE_REPLACEMENT);
            if (pendingKinds.isEmpty() && continuationContext != null
                    && continuationContext.pendingToolName() != null) {
                pendingKinds.add(ConfirmationKind.CHOICE);
            }
            confirmationDecision = resolveConfirmation(content, confirmation, pendingKinds,
                    pendingProtectedActions(destructivePending, scenarioPending));
            if (confirmationDecision.cancelled()) {
                clearCancelledPendingAction(userId, sessionId, confirmationDecision.kind(),
                        confirmation == null ? null : confirmation.getConfirmationId());
            }
            UserContextHolder.setConfirmedProtectedAction(
                    confirmationDecision.confirmed() && confirmationDecision.kind() != ConfirmationKind.CHOICE
                            ? confirmationDecision.kind().name()
                            : null,
                    confirmation == null ? null : confirmation.getConfirmationId());
            executeOwnedSessionWrite(userId, sessionId, executionId,
                    () -> touchSessionTitle(sessionId, userId, content));
            if (shouldStop.getAsBoolean()) {
                return;
            }
            if (!sendSseProgress(emitter, "CONTEXT_READY", null, null,
                    null, null, null, executionTrace)) {
                isDisconnect.set(true);
                return;
            }

            List<ChatMessagePo> historyPO = getSmartHistory(
                    sessionId, chatExecutionConfig.getHistoryCharLimit());
            List<LlmMessage> messages = new ArrayList<>();
            messages.add(LlmMessage.system(buildToolPlanningSystemPrompt().content() + "\n"
                    + buildVisibleReplySystemPrompt(true).content()));
            messages.addAll(messageCodec.toMessages(historyPO));
            LlmMessage pendingTaskContext = buildPendingTaskSystemPrompt(
                    destructivePending,
                    scenarioPending,
                    continuationContext,
                    confirmationDecision,
                    content);
            if (pendingTaskContext != null) {
                messages.add(pendingTaskContext);
                if (confirmationDecision.confirmed() && continuationContext != null && !sendSseProgress(
                        emitter,
                        "TASK_RESUMED",
                        null,
                        null,
                        null,
                        null,
                        toolProgressPresenter.compactProgressDetail(continuationContext.originalObjective()),
                        executionTrace)) {
                    isDisconnect.set(true);
                    return;
                }
            }

            List<LlmToolSpec> availableTools = aiToolManager.getAllToolDefinitions();
            List<LlmToolSpec> tools = availableTools == null ? List.of() : availableTools;
            log.debug("Starting model-driven planning with {} discoverable tools", tools.size());
            GuardedReplyStream replyStream = new GuardedReplyStream(finalAnswer, emitter, isDisconnect,
                    shouldStop, preferChinese, executionTrace, confirmedMutationTools,
                    activeRequest.requestControl()::cancel);
            loopResult = executeToolLoop(
                    userId, sessionId, messages, tools, commandSet, emitter, isDisconnect,
                    preferChinese, executionTrace, executionId, shouldStop, forceStopCheck,
                    confirmedMutationTools, replyStream,
                    confirmationDecision.confirmed()
                            ? confirmationDecision.kind() == ConfirmationKind.SCENE_REPLACEMENT
                                ? "apply_scenario" : destructivePending == null ? null : destructivePending.toolName()
                            : null);
            replyStream.resolveToolRound();

            if (forceStopCheck.getAsBoolean()) {
                return;
            }

            if (loopResult.confirmationPending()) {
                taskContinuationStore.savePendingStep(
                        userId,
                        sessionId,
                        continuationContext == null ? content : continuationContext.originalObjective(),
                        content,
                        loopResult.pendingToolName(),
                        sanitizePendingResultForContinuation(loopResult.pendingToolResult()));
            } else if (shouldPreservePendingTask(
                    userId,
                    sessionId,
                    destructivePending != null || scenarioPending != null,
                    continuationContext,
                    confirmationDecision,
                    loopResult)) {
                taskContinuationStore.rememberUserMessage(userId, sessionId, content);
            } else {
                taskContinuationStore.clear(userId, sessionId);
            }

            if (shouldStop.getAsBoolean()) {
                log.info("Chat execution stop was requested during tool loop");
                return;
            }

            if (!commandSet.isEmpty()) {
                boolean commandsDelivered = sendFrontendCommands(emitter, commandSet);
                if (!commandsDelivered) {
                    // Switching conversations may detach the browser stream. The terminal row
                    // remains authoritative and the client reconciles Board state from polling.
                    log.debug("Chat refresh commands were not delivered; execution continues in the background: "
                                    + "sessionId={}, executionId={}",
                            sessionId, executionId);
                    frontendCommandsDeliveryFailed = true;
                } else {
                    frontendCommandsDelivered = true;
                }
                commandSet.clear();
            }

            // These two notices are the audit record of failed, unconfirmed, and guard-stopped steps.
            // They are appended to finalAnswer unconditionally, because that is what gets persisted:
            // a transient unwritable connection must not erase "N steps failed" from the stored turn,
            // which is the only place the user can still read it after a reload. A failed send only
            // detaches the transport; it must not cancel the durable execution.
            String executionNotice = toolExecutionNotice(loopResult, preferChinese);
            if (!executionNotice.isBlank()) {
                if (!finalAnswer.isEmpty() && !finalAnswer.toString().endsWith("\n\n")) {
                    finalAnswer.append("\n\n");
                    if (!sendSseChunk(emitter, "\n\n")) isDisconnect.set(true);
                }
                finalAnswer.append(executionNotice);
                if (!sendSseChunk(emitter, executionNotice)) isDisconnect.set(true);
            }

            String guardNotice = executionGuardNotice(loopResult, preferChinese);
            if (!guardNotice.isBlank()) {
                finalAnswer.append(guardNotice);
                if (!sendSseChunk(emitter, guardNotice)) isDisconnect.set(true);
            }

            int replyStart = finalAnswer.length();
            ReplyGuardOutcome replyGuardOutcome = replyStream.outcome();
            if (!loopResult.replyComplete() && replyGuardOutcome != ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE) {
                if (!finalAnswer.isEmpty()) {
                    finalAnswer.append("\n\n");
                    if (!sendSseChunk(emitter, "\n\n")) isDisconnect.set(true);
                }
                if (!sendSseProgress(emitter, "WRITING_RESPONSE", null, null,
                        null, loopResult, null, executionTrace)) {
                    isDisconnect.set(true);
                    return;
                }
                replyGuardOutcome = mergeReplyGuardOutcome(replyGuardOutcome, streamGuardedAssistantReply(
                        withVisibleReplyPrompt(messages, loopResult), finalAnswer, emitter, isDisconnect, shouldStop,
                        preferChinese, executionTrace, confirmedMutationTools));
            }
            boolean finalResponseProduced = loopResult.replyComplete()
                    ? replyStream.hasText() : finalAnswer.length() > replyStart;

            if (!finalResponseProduced && !isDisconnect.get()) {
                String fallbackText = missingFinalResponseFallback(loopResult, preferChinese);
                if (sendSseChunk(emitter, fallbackText)) {
                    finalAnswer.append(fallbackText);
                }
            }

            if (forceStopCheck.getAsBoolean()) {
                return;
            }

            ChatExecutionStatus finalStatus = terminalStatus(loopResult, replyGuardOutcome);
            String userStoppedNotice = interruptedAuditNotice(loopResult, preferChinese, true);
            TerminalPersistenceResult persistedTerminal = persistAssistantTerminal(
                    userId,
                    sessionId,
                    executionId,
                    effectiveTurnId,
                    finalAnswer.toString(),
                    executionTrace,
                    elapsedSeconds(executionStartedNanos),
                    finalStatus,
                    userStoppedNotice);
            terminalPersistence = persistedTerminal.persisted()
                    ? TerminalPersistenceState.PERSISTED
                    : TerminalPersistenceState.FAILED;
            if (terminalPersistence == TerminalPersistenceState.FAILED) {
                serverCompletion.set(true);
                sendSseErrorMessage(
                        emitter, terminalPersistenceFailureMessage(preferChinese), isDisconnect);
                return;
            }
            ChatExecutionStatus persistedStatus = persistedTerminal.executionStatus();
            if (persistedStatus == ChatExecutionStatus.STOPPED
                    && !sendSseChunk(emitter, userStoppedNotice)) {
                isDisconnect.set(true);
                return;
            }
            if (!sendSseTerminal(emitter, effectiveTurnId, persistedStatus)) {
                isDisconnect.set(true);
                return;
            }
            serverCompletion.set(true);
            completeEmitter(emitter, isDisconnect);
        } catch (Exception e) {
            // Losing the SSE transport is not a cancellation signal. Only the durable stop
            // fence may interrupt the worker; provider/transport failures still get a terminal
            // audit row so a detached browser can observe the outcome later.
            if (isDisconnect.get()) {
                isDisconnect.set(true);
                log.info("Chat execution ended after an explicit or durable stop: {}", e.toString());
                return;
            }
            log.error("Chat Error", e);
            String errorMessage = errorMessageFor(e, preferChinese);
            if (!commandSet.isEmpty()) {
                boolean pendingCommandsDelivered = sendFrontendCommands(emitter, commandSet);
                if (pendingCommandsDelivered) {
                    commandSet.clear();
                    frontendCommandsDelivered = true;
                    errorMessage += preferChinese
                            ? " 一个或多个较早的工具写入可能已经完成；客户端已请求刷新受影响数据。重试前请检查当前状态。"
                            : " One or more earlier tool mutations may already have completed; "
                                + "the client was asked to refresh affected data. Review current state before retrying.";
                } else {
                    errorMessage += preferChinese
                            ? " 一个或多个较早的工具写入可能已经完成，但刷新指令未能完整送达。重试前请重新加载并检查当前状态。"
                            : " One or more earlier tool mutations may already have completed, but the refresh "
                                + "instructions could not be fully delivered. Reload and review current state before retrying.";
                }
            } else if (frontendCommandsDeliveryFailed) {
                errorMessage += preferChinese
                        ? " 一个或多个较早的工具操作可能已经完成，但刷新指令未能送达；请重新加载并检查当前状态。"
                        : " One or more earlier tool actions may have completed, but refresh instructions were not delivered; "
                            + "reload and review current state before retrying.";
            } else if (frontendCommandsDelivered) {
                errorMessage += preferChinese
                        ? " 一个或多个较早的工具操作已经完成，受影响数据也已刷新；最终 AI 说明生成失败。"
                        : " One or more earlier tool actions completed and affected data was refreshed; "
                            + "the final AI explanation could not be generated.";
            }
            ChatExecutionStatus status = loopResult.hadToolCalls()
                    || executionTrace.stream().anyMatch(progress ->
                            "TOOL_EXECUTION".equals(progress.getStage())
                                    || "TOOL_RESULT".equals(progress.getStage()))
                    || !commandSet.isEmpty()
                    || frontendCommandsDeliveryFailed
                    || frontendCommandsDelivered
                    || !finalAnswer.isEmpty()
                    ? ChatExecutionStatus.PARTIAL
                    : ChatExecutionStatus.FAILED;
            String userStoppedNotice = interruptedAuditNotice(loopResult, preferChinese, true);
            TerminalPersistenceResult persistedTerminal = persistAssistantTerminal(
                    userId,
                    sessionId,
                    executionId,
                    effectiveTurnId,
                    finalAnswer.isEmpty() ? errorMessage : finalAnswer + "\n\n> " + errorMessage,
                    executionTrace,
                    elapsedSeconds(executionStartedNanos),
                    status,
                    userStoppedNotice);
            terminalPersistence = persistedTerminal.persisted()
                    ? TerminalPersistenceState.PERSISTED
                    : TerminalPersistenceState.FAILED;
            if (terminalPersistence == TerminalPersistenceState.FAILED) {
                errorMessage += terminalPersistenceFailureSuffix(preferChinese);
                serverCompletion.set(true);
                sendSseErrorMessage(emitter, errorMessage, isDisconnect);
                return;
            }
            ChatExecutionStatus persistedStatus = persistedTerminal.executionStatus();
            if (persistedStatus == ChatExecutionStatus.STOPPED) {
                if (!sendSseChunk(emitter, userStoppedNotice)) {
                    isDisconnect.set(true);
                    return;
                }
            } else if (!sendSseError(emitter, errorMessage)) {
                isDisconnect.set(true);
                return;
            }
            if (!sendSseTerminal(emitter, effectiveTurnId, persistedStatus)) {
                isDisconnect.set(true);
                return;
            }
            serverCompletion.set(true);
            completeEmitter(emitter, isDisconnect);
        } finally {
            if (terminalPersistence == TerminalPersistenceState.NOT_ATTEMPTED
                    && isDisconnect.get()) {
                try {
                    ChatExecutionStatus interruptedStatus = isUserStop.get()
                            ? ChatExecutionStatus.STOPPED
                            : ChatExecutionStatus.DISCONNECTED;
                    String userStoppedNotice = interruptedAuditNotice(loopResult, preferChinese, true);
                    TerminalPersistenceResult persistedTerminal = persistAssistantTerminal(
                            userId,
                            sessionId,
                            executionId,
                            effectiveTurnId,
                            interruptedAuditNotice(loopResult, preferChinese, isUserStop.get()),
                            executionTrace,
                            elapsedSeconds(executionStartedNanos),
                            interruptedStatus,
                            userStoppedNotice);
                    if (persistedTerminal.persisted()) {
                        ChatExecutionStatus persistedStatus = persistedTerminal.executionStatus();
                        boolean streamWritable = persistedStatus != ChatExecutionStatus.STOPPED
                                || sendSseChunk(emitter, userStoppedNotice);
                        if (streamWritable) {
                            sendSseTerminal(emitter, effectiveTurnId, persistedStatus);
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not settle interrupted chat stream for session {}: {}",
                            sessionId, e.toString());
                } finally {
                    serverCompletion.set(true);
                    completeInterruptedEmitter(emitter);
                }
            }
            UserContextHolder.clear();
            LlmRequestControlHolder.clear();
        }
    }

    private ConfirmationDecision resolveConfirmation(
            String content,
            ChatConfirmationCommandDto command,
            EnumSet<ConfirmationKind> pendingKinds,
            List<ChatPendingConfirmationDto.Action> pendingActions) {
        if (command == null) {
            return pendingKinds.size() == 1 && pendingKinds.contains(ConfirmationKind.CHOICE)
                    ? chatConfirmationDetector.detect(content, pendingKinds)
                    : ConfirmationDecision.none();
        }
        ConfirmationKind requestedKind = switch (command.getKind()) {
            case DESTRUCTIVE -> ConfirmationKind.DESTRUCTIVE;
            case DEFAULT_TEMPLATE_RESET -> ConfirmationKind.DEFAULT_TEMPLATE_RESET;
            case SCENE_REPLACEMENT -> ConfirmationKind.SCENE_REPLACEMENT;
        };
        if (pendingActions.stream().noneMatch(pending -> pending.kind() == command.getKind()
                && pending.confirmationId().equals(command.getConfirmationId()))) {
            throw new ChatConfirmationConflictException();
        }
        return command.getAction() == ChatConfirmationCommandDto.Action.CONFIRM
                ? ConfirmationDecision.confirmed(requestedKind)
                : ConfirmationDecision.cancelled(requestedKind);
    }

    private List<ChatPendingConfirmationDto.Action> pendingProtectedActions(
            AiDestructiveActionGuard.PendingActionContext destructive,
            AiScenarioDraftStore.PendingApplication scenario) {
        List<ChatPendingConfirmationDto.Action> actions = new ArrayList<>();
        if (destructive != null) {
            actions.add(new ChatPendingConfirmationDto.Action(
                    "reset_default_templates".equals(destructive.toolName())
                            ? ChatConfirmationCommandDto.Kind.DEFAULT_TEMPLATE_RESET
                            : ChatConfirmationCommandDto.Kind.DESTRUCTIVE,
                    destructive.impactToken(), destructive.toolName(), destructive.displayTarget()));
        }
        if (scenario != null) {
            actions.add(new ChatPendingConfirmationDto.Action(ChatConfirmationCommandDto.Kind.SCENE_REPLACEMENT,
                    scenario.confirmationId(), "apply_scenario", scenario.scenarioName()));
        }
        return actions;
    }

    private boolean synchronizeExecutionStop(Long userId,
                                             String sessionId,
                                             ActiveStreamRequest activeRequest,
                                             AtomicBoolean isDisconnect,
                                             AtomicBoolean isUserStop,
                                             boolean forcePoll) {
        if (isDisconnect.get()) return true;
        if (Thread.currentThread().isInterrupted()) {
            isDisconnect.set(true);
            activeRequest.requestControl().cancel();
            return true;
        }
        long nowNanos = System.nanoTime();
        long nextPoll = activeRequest.nextControlPollNanos().get();
        if (!forcePoll && (nowNanos < nextPoll || !activeRequest.nextControlPollNanos().compareAndSet(
                nextPoll, nowNanos + EXECUTION_CONTROL_POLL_NANOS))) {
            return isDisconnect.get();
        }
        if (forcePoll) {
            activeRequest.nextControlPollNanos().set(nowNanos + EXECUTION_CONTROL_POLL_NANOS);
        }
        try {
            ChatSessionPo session = sessionRepo.findByIdAndUserId(sessionId, userId).orElse(null);
            LocalDateTime now = databaseNow();
            boolean sameExecution = session != null
                    && Objects.equals(session.getActiveExecutionId(), activeRequest.requestId());
            if (!sameExecution) {
                isDisconnect.set(true);
                return true;
            }
            if (!hasActiveExecutionLease(session, now)) {
                log.warn("Chat execution lease expired before its heartbeat renewed it: sessionId={}, executionId={}",
                        sessionId, activeRequest.requestId());
                isDisconnect.set(true);
                return true;
            }
            if (Boolean.TRUE.equals(session.getExecutionStopRequested())) {
                if (Boolean.TRUE.equals(session.getExecutionUserStopRequested())) {
                    isUserStop.set(true);
                }
                isDisconnect.set(true);
            }
        } catch (RuntimeException e) {
            if (activeRequest.leaseConfirmation().isUnconfirmedFor(executionLeaseTtl())) {
                isDisconnect.set(true);
                activeRequest.requestControl().cancel();
                log.warn("Stopped local chat execution after its lease could not be confirmed for a full TTL: "
                                + "sessionId={}, executionId={}",
                        sessionId, activeRequest.requestId());
            } else {
                log.warn("Could not poll distributed chat execution control for session {}: {}",
                        sessionId, e.toString());
            }
        }
        return isDisconnect.get();
    }

    private String requireTurnId(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            throw new BadRequestException("Turn id is required");
        }
        String effectiveTurnId = turnId.trim();
        if (effectiveTurnId.length() > 64) {
            throw new BadRequestException("Turn id must not exceed 64 characters");
        }
        return effectiveTurnId;
    }

    private String requireTurnContent(String content) {
        if (content == null) {
            throw new BadRequestException("Content is required");
        }
        if (content.length() > RequestLimits.MAX_CHAT_CONTENT_LENGTH) {
            throw new BadRequestException("Content must not exceed 10000 characters");
        }
        if (collapseTitleWhitespace(content).isEmpty()) {
            throw new BadRequestException("Content is required");
        }
        return content;
    }

    private String titleFromContent(String content) {
        String normalized = collapseTitleWhitespace(content);
        if (normalized.codePointCount(0, normalized.length()) <= 12) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, 12);
        return normalized.substring(0, end) + "...";
    }

    private String collapseTitleWhitespace(String content) {
        StringBuilder normalized = new StringBuilder(content.length());
        boolean pendingSpace = false;
        for (int index = 0; index < content.length();) {
            int codePoint = content.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (normalized.length() > 0) {
                    pendingSpace = true;
                }
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private void touchSessionTitle(String sessionId, Long userId, String content) {
        sessionRepo.findByIdAndUserId(
                Objects.requireNonNull(sessionId, "sessionId must not be null"), userId).ifPresent(session -> {
            session.setUpdatedAt(databaseNow());
            if (isUntitledSessionTitle(session.getTitle())) {
                session.setTitle(titleFromContent(content));
            }
            sessionRepo.save(session);
        });
    }

    /**
     * Shared by the tool-aware prompt and the guarded summary fallback so their platform
     * self-description cannot drift.
     */
    private static final String ANSWER_PRESENTATION_RULES = """
        - Present each chain and inspection priority once, with its evidence and next check together. On a
          follow-up, use earlier findings without repeating the entire topology. Keep all requested coverage
          and material limitations, but omit repeated introductions, conclusions and checklists.
          State shared limitations and evidence scope once instead of repeating them in each table row.
          For a topology question, group the answer by chain and combine shared chain segments. Put each
          chain's conditions, missing links and inspection priority in that same section; do not duplicate
          those paths in separate topology, priority and final-checklist sections. A follow-up should develop
          the requested distinction or decision, not retell already explained trigger steps.
          For a multi-device topology request, prefer one compact chain table with complete guards/actions
          and one short ordered inspection list. Do not add another overall checklist or an unrelated
          specification inventory. Expand only where the requested analysis needs it, not to repeat coverage.
          When citing a configured specification, including final summaries and table labels, preserve its
          complete IF, THEN and temporal scope from the tool result. Keep current-state preconditions such as
          "the door is already locked"; omitting one broadens the claim beyond what was checked. Do not replace
          its IF with a downstream device just because an automation chain connects them.
          For a full formal cross-check, start with the outcome, then use one compact rule/chain table,
          one specification/result table, one indexed value table per counterexample, and one prioritized
          list. Put shared-variable dependencies and gaps beside their relevant chain or check; include
          a variable inventory only if requested. Do not add separate prose retellings of the topology,
          rules, counterexamples or final conclusions. Preserve every requested item, complete condition
          and material limitation in these inventories; explain causes once beside their evidence.
        """;

    private static final String ASSISTANT_IDENTITY_LINE =
            "You are the intelligent expert assistant for the IoT-Verify platform. "
                    + "This is a smart home simulation and formal verification platform based on NuSMV.";

    private static final String CURRENT_EVIDENCE_REPLY_RULES = """
        Before continuing the analysis, check it against the latest tool results, including after a
        stream interruption. Earlier assistant prose is not evidence and must not override those results.
        Under applicable/configured specifications, list only actual specifications returned by tools;
        label related automation rules separately, and mark suggested properties as not yet configured.
        When two observed values first change at the same stateIndex, they are observed together;
        do not say one preceded the other. A trigger condition in an earlier state is not evidence that
        its resulting action occurred before another change at the action's stateIndex.
        Keep full-audit inventories and chronological evidence in the requested compact tables;
        place each cause beside its indexed evidence instead of retelling those chains in prose.
        """;

    private LlmMessage buildToolPlanningSystemPrompt() {
        String systemPromptContent = ASSISTANT_IDENTITY_LINE + "\n" + """
        Your behavior guidelines:
        1. Handle system notices: if a tool result contains system notice text, explicitly explain it — a notice
           can change the next call, which is this round's decision to make.
        2. Avoid exposing internal IDs unless user asks.
        3. Treat resultAvailable=false or resultStatus=RESULT_UNAVAILABLE as an unconfirmed outcome,
           never as success. Do not retry a possibly committed mutation until refreshed state is inspected.
        4. If any tool result has requiresUserConfirmation=true, stop tool planning for this user turn.
           Never accept a proposed alternative, rename, deletion, or other pending action on the user's behalf.
        5. Plan toward the user's complete objective, not toward a fixed workflow. The registered tools are
           composable capabilities: freely combine reads, targeted writes, deletion previews, creation, rules,
           specifications, simulation, verification, and status checks when their schemas and results support it.
           Continue until the objective is complete or a real confirmation, unavailable-result, or safety boundary
           requires a new user turn. Do not stop merely because one successful tool call finished.
           Batch independent read calls in one response when their arguments are already known, such as
           reading several identified traces. Wait for results before choosing dependent calls.
        6. Start promptly with one short sentence in the user's language identifying the observed evidence or
           the missing fact that decides the next call. Do not delay the first update to write a complete
           plan or compare every possible action. Reason from observed Board state, not assumptions.
           Check earlier results against current evidence and explain unexpected differences.
           Reserve the complete analysis for the answer after the necessary tools return: decompose the user's
           question, cover its requirements, indexed causes and limitations, and name a material alternative
           with the reason for your choice only when it changes the decision.
           This is a public explanation, not private hidden chain-of-thought. Never expose opaque tokens,
           internal ids or raw control context, or claim an unsupported Board read/change.
           Do not predict a result you have not received yet.
        7. Your assistant text is streamed directly to the user. Before tool calls, give only a brief
           evidence-based explanation of what is still needed. After the first round, give at most one
           sentence about new evidence or the remaining question; do not repeat the scope or counts.
           When no further calls are needed, write the complete answer in this same response, using the
           actual tool results.
           Lead with the finding, cover every requested device and question, and avoid repeating earlier
           explanations. There is no separate answer-generation round after a normal response.
           Explain technical terms in the user's language; presentation must preserve every requested aspect.

        Destructive Action Guidelines:
        - Device, template, rule, specification, and saved-trace deletion, deletion of a whole
          verification run (delete_verification_run) or counterexample-search run (delete_fuzz_run),
          dismissal of a failed/cancelled task, clearing the Board's undo/redo edit history, clearing the
          Board (clear_board), refreshing the bundled default templates (reset_default_templates, which
          also reconciles the Environment Pool and clears edit history), and applying a formal fix are
          always two-turn operations.
        - Task dismissal (dismiss_verify_task, dismiss_simulate_task, dismiss_fuzz_task) previews the
          terminal task and diagnostics that would be lost. It never removes an active or completed task,
          so cancel active work first and use the matching run/trace deletion tool for saved history.
        - On the first turn, call the relevant protected-action tool with confirmed=false and summarize the returned target and impact.
        - Stop and ask the user for explicit confirmation. Never set confirmed=true in the same user turn as the preview.
        - Set confirmed=true only when the latest user message explicitly confirms the previously previewed action.
        - If dependencies or target data changed after preview, explain the conflict and request a new preview.
        - fix_violation only analyzes a formal verification trace. To apply one returned signed suggestion,
          call apply_fix with confirmed=false, traceId, and its suggestionId. The server retains the
          exact signed suggestion and preferred ranges; never reconstruct or resend them. After the
          user confirms its preview in a later turn, call
          apply_fix with confirmed=true, traceId, and impactToken only. Counterexample-search findings are
          heuristic candidate evidence, not formal traces: never route a fuzz finding into either tool, and never
          describe budget exhaustion as proof that a specification holds.
        - Applying a fix_violation proposal uses apply_fix for every strategy, including remove.
          Never reproduce its edit with manage_rule or another manual mutation tool: that would
          discard the verified proposal's guards for preserving requirements already satisfied.
        - A confirmation pauses only the protected step, not the user's larger task. After the confirmed mutation
          returns a usable result, resume the original objective and freely compose the remaining tools. For example,
          a requested targeted replacement may confirm a deletion, then create the replacement and repair affected
          rules or specifications. Stop again only if another protected action needs its own confirmation.

        Recommendation Guidelines:
        - The conversation supplied to the model is a bounded recent window. An absent earlier operation is
          not evidence that it never happened. For a conversation-wide audit of recommendations, previews,
          saved changes, cancellations or retries, use read_conversation_activity and follow its older-page
          cursor until hasMore=false; otherwise state the limited coverage. Read appliedAction as evidence
          of that historical operation only, never as new write authority or proof of current Board state.
        - The complete tool catalog remains discoverable. The first planning round provides complete schemas
          for every platform action and needs no preparation. Later rounds keep complete schemas for already
          used or prepared actions. Use prepare_tools only for additional actions whose schemas are no
          longer present; batch their exact names, including dependent status/detail/fix readers.
          Do not prepare an action whose complete schema is already provided. Prepared schemas remain available
          for the rest of this turn.
          No action is executed by preparing its schema. Do not guess arguments when an action's schema is absent.
          Choose tools from their schemas and chain
          read, recommendation, mutation, verification, and status tools as needed; do not wait for a tool name or
          keyword in the user's message.
        - Device labels identify instances; templateName identifies their behavior template. board_overview
          includes full active-device manifests by default: inspect its templates before answering behavior
          questions, including all referenced behaviorDeviceIds. Do not re-read definitions already supplied.
          For lightweight counts only, detail=false omits manifests. For a needed definition outside this
          snapshot, use list_templates deviceIds with exact devices[].id values from board_overview and detail=true.
          The server resolves their current templates. For catalog lookup by names, copy templateName exactly,
          never deviceLabel or an inferred type name; do not combine deviceIds and names.
          A validation failure is not evidence that the platform lacks that ability: correct the stated fields
          using the supplied schema and authoritative returned names, then continue the requested analysis.
          Do not describe a failed lookup as a completed template read.
        - To explain a rule chain, inspect full manifests for intermediate devices, command targets and
          contentDevice sources, including devices outside the user's initial list. Follow every condition,
          API effect, contentDevice and content field. A camera event is a trigger, not proof of the later
          payload's source. A send/upload action name or state transition does not prove network delivery;
          name the modeled data source and effects, and explain what the model does not establish.
          Interpret variable scope, read/effect capability and effective labels using the semantic note
          returned with either full manifest view. InternalVariables does not mean device-local. Distinguish a reported
          specification subject from the environment without assuming independent physical values or
          replacing the current Environment Pool labels with template defaults.
          Do not ask the user for behavior definitions already readable from those manifests. An indirect
          chain requires an actual rule/API-effect path; sharing condition inputs does not connect devices.
          Use board_overview.directRuleTargets as the directed condition-to-command index. Follow arrows
          forward; two targets of a shared clock/condition are not connected to each other. Check every
          claimed downstream connection against the actual path before answering. For a complete automation
          behavior audit, read its behaviorDeviceIds and inspect their included templates; they include every
          command target and content source.
          Complete behavior-dependent checks with available tools in this turn rather than leaving them as
          questions the user must resolve. Attribute model facts to the model, not to observed physical execution.
        - For any question about the current scene, including counts of devices, rules, or specifications, use
          board_overview and answer from its current counts and semantic data rather than chat history. Do not call
          it ritualistically when server-authoritative confirmation context or a fresh tool result already provides
          the exact state needed for the next step.
          This also applies to follow-ups about current links or specifications: a previous user turn's overview
          is a historical snapshot, not current-turn evidence. Read-only analysis permits read tools. Before
          describing current Board facts, obtain board_overview in this turn; when discussing only earlier
          findings, explicitly attribute them to the earlier snapshot instead of claiming a fresh inspection.
        - Simulation does not check specifications. Its modelSnapshot.specificationCount describes only the
          simulation model, not the saved Board's specification count. Read board_overview for current
          configured specifications and formal verification for their outcomes. A simulation list is only
          metadata; read get_simulation_trace for the relevant state windows before explaining state changes.
        - To extend or complete the existing scene, inspect current Board state only when the task depends on facts
          not already supplied by the user or a fresh tool result. Then freely compose recommendation, mutation,
          simulation, verification, and status tools. Before add_device, use list_templates only when an exact valid
          template name is not already known. Never replace a user-supplied exact value with a recommendation merely
          because a recommendation tool exists. Preserve existing scene content unless full replacement is requested.
        - A recommendation is a reviewed candidate, not an applied change and not a formal-verification result.
        - If the user asks only to recommend or explore, return the candidates and ask what to apply. Do not call
          add_device, manage_rule, or manage_spec in the same turn on the user's behalf. When the user explicitly
          delegates completing or modifying the scene, validated candidates may be applied with targeted mutation
          tools in the same turn; report that newly created items are not formally verified.
        - Set the recommendation tools' language argument to the language of the user's latest message. It
          defaults to en and decides the language of candidate names, reasons/rationales, and filter
          explanations inside the tool result, so leaving it unset gives a Chinese-speaking user English
          candidates. Do not translate returned candidate text yourself.
        - If a recommendation result reports filteredItems, truncatedCount, or adjustedItems, preserve those
          distinctions: rejected candidates have itemized reasons; truncated candidates were never inspected;
          deterministic defaults/normalizations are adjustments, not AI-authored values.
        - Applying one device/rule/specification recommendation is a targeted append. Use recommend_scenario only
          when the user explicitly requests a complete replacement/import draft; it cannot be described as a local
          addition or as already applied.
          Only change saved Board data when this user turn requests that change. Searching, reviewing or
          comparing repair candidates is not permission to temporarily edit a saved rule and re-verify it.
          Use fix_violation for candidate search; use apply_fix only for the verified candidate and its
          separate user confirmation. A refused write does not remove read/search abilities; correct
          the workflow instead of claiming the attempted edit or the user's objective completed.
          When the user specifies the device types, pass their known exact templateNames to recommend_scenario
          so generation reads complete capabilities only for that selected set. Include every type needed by
          the requested draft; omit the scope when selection itself is part of the task. Do not guess capabilities
          or exclude needed current devices merely to shorten a prompt.
          If a draft is PARTIAL, inspect its filteredItems and objectiveIssues and correct the stated issues
          through recommend_scenario while the requested goal remains feasible. Keep the original counts,
          boundaries, devices and source semantics; do not weaken the request or apply a draft to make it pass.
          Standalone rule/specification recommendations read the saved Board, not an unapplied scene draft.
        - If the user asks to apply the latest recommend_scenario draft, call apply_scenario with confirmed=false.
          Do not call board_overview and do not delete or recreate devices individually. apply_scenario owns the
          authoritative current-board preview and the atomic full-scene replacement.
        - After apply_scenario returns requiresUserConfirmation=true, stop. In a later turn, call apply_scenario with
          confirmed=true only when the latest user message explicitly confirms that exact replacement preview.
        - When the user explicitly delegates a multi-step Board change, validated recommendations are inputs to the
          task rather than a mandatory stopping point. Apply the supported candidates with targeted mutation tools,
          then continue with the remaining requested steps and report any part that could not be completed.

        Household requirements:
        - Help household users state desired behavior, forbidden situations, and functions that must remain usable.
          Keep these three distinct. Map each stated requirement to proposed automation and an independent
          specification using actual device capabilities. Mark unstated requirements as unknown, not inferred consent.
        - Do not derive every requirement from the rules being checked: a rule and its restatement can agree while
          both violate the user's goal. When a requested behavior cannot be expressed by the current templates,
          state the gap and ask for the missing decision instead of inventing a sensor, authentication, room or timer.
          Propose only operations the platform exposes. Rules support in-place updates and confirmed deletion;
          there is no enable/disable toggle, so do not offer "disable this rule" as an available operation.
        - For edits to an existing specification, read list_specs and use manage_spec action=update with its specId,
          exact expectedSpec, and the complete desired condition groups. Preserve its identity and unrelated fields.
          Changed rules and specifications remain unverified until a fresh verification run checks the saved model.
        - Explain formal outcomes together with antecedentReachability. SATISFIED with UNREACHABLE means a response
          passed without its trigger ever occurring; INCONCLUSIVE reachability does not establish useful behavior.
          REACHABLE only establishes the trigger is possible, not that the response is guaranteed. Do not call a fix
          useful merely because formulas passed, or weaken requirements to obtain a pass without the user's request.
        - Simulation observes one possible path. Bounded exploration searches for candidates within its budget and
          input assumptions. Formal verification checks the saved requirements under the modeled assumptions.
          These are different questions, not mandatory sequential stages. Current-board verification does not confirm
          an older exploration finding's initial state, events, or path. A model step has no fixed duration in seconds.

        Rule Recommendation Guidelines:
        - Use recommend_rules when the user asks for suggestions or has not supplied enough rule logic. When the user
          supplies a complete rule and asks to save it, validate it and use manage_rule directly.
          For edits to an existing rule, read list_rules and use manage_rule action=update with the exact
          expectedRule and its ruleId. Preserve every condition, command field and label the user did not
          ask to change. Never add a replacement and delete the original to implement an edit: that changes
          identity/order and can leave both rules active. A formal repair suggestion still uses apply_fix.
        - Analyze device capabilities (APIs, variables, modes, states) from authoritative tool results as needed.
        - Only recommend rules using APIs, variables, modes, or states that actually exist in the device templates
        - Explain why each suggestion matches the user's goal and the available device capabilities; do not invent a numeric confidence score

        Generated Model Inspection:
        - When inspecting the current scene's device behavior, call list_templates with its relevant
          deviceIds and detail=true. Read the full catalog only for catalog exploration
          or a request for additional templates; unrelated manifests add no evidence to a saved model audit.
        - For a comparison, inspect saved results before starting a duplicate baseline. Reuse a baseline only
          when its saved modelFingerprint is non-null and exactly matches the compared run's fingerprint,
          with the requested attack, privacy, model-semantics and path-length settings. Counts or labels alone
          do not establish equivalence. Say which historical snapshot is reused; a fresh run is still required
          when the user asks to rerun, the scene changed, or matching evidence is unavailable.
        - Template capabilities and run metadata are not the generated NuSMV text. Use read_run_model to inspect
          the exact model saved with a completed verification or simulation, and read its remaining pages when
          the requested analysis depends on them. Do not invent a model or claim an unread excerpt was checked.
        - The saved model belongs to that run's frozen snapshot. Use the matching run result for completeness,
          attack/privacy assumptions and verdict; do not describe it as the current Board after a scene edit.
          Reading the model is not formal verification. Simulation is one possible path; bounded exploration
          findings require formal checking, and no finding within a budget is never proof of satisfaction.
        - Copy saved identifiers from fresh Board or list results. An automation-link attack needs the rule's
          persisted id, not its display order or ruleIndex. An exploration target needs the specification's
          saved id, not its templateId or position. Never guess an id from a numbered user-facing list.

        Available tools:
        - Prepare complete action schemas: prepare_tools
        - Saved conversation operation history: read_conversation_activity
        - Device: add_device, edit_device, delete_device, search_devices, recommend_related_devices
        - Environment: manage_environment
        - Rule: list_rules, manage_rule, check_duplicate_rule, check_rule_similarity, recommend_rules
        - Spec: list_specs, manage_spec, recommend_specifications
        - Scene draft and atomic apply: recommend_scenario, apply_scenario
        - Template: list_templates, add_template, delete_template, reset_default_templates
        - Verification sync: verify_model
        - Verification async: verify_model_async, verify_task_status, cancel_verify_task, dismiss_verify_task
        - Cross-run task discovery: list_async_tasks
        - Verification runs, traces, and formal fix: list_verification_runs, get_verification_run, list_traces, get_trace, delete_trace, delete_verification_run, fix_violation, apply_fix
        - Saved generated-model inspection: read_run_model
        - Simulation sync: simulate_model
        - Simulation async: simulate_model_async, simulate_task_status, cancel_simulate_task, dismiss_simulate_task
        - Simulation traces: list_simulation_traces, get_simulation_trace, delete_simulation_trace
        - Counterexample search (bounded fuzz): fuzz_model_async, fuzz_task_status, cancel_fuzz_task, dismiss_fuzz_task, list_fuzz_runs, get_fuzz_run, get_fuzz_finding, delete_fuzz_run
        - Board: board_overview, manage_board_history, clear_board
        """;

        return LlmMessage.system(systemPromptContent + "\n" + ANSWER_PRESENTATION_RULES);
    }

    private LlmMessage buildPendingTaskSystemPrompt(
            AiDestructiveActionGuard.PendingActionContext destructivePending,
            AiScenarioDraftStore.PendingApplication scenarioPending,
            AiTaskContinuationStore.ContinuationContext continuationContext,
            ConfirmationDecision confirmationDecision,
            String latestUserMessage) {
        boolean hasPendingContext = destructivePending != null
                || scenarioPending != null
                || continuationContext != null;
        if (!hasPendingContext) return null;

        String taskContext = buildTaskContextJson(
                destructivePending,
                scenarioPending,
                continuationContext,
                latestUserMessage);
        String userAuthority = """
                - The latest user message is authoritative. It may narrow, expand, reorder, or cancel earlier work.
                  Follow it wherever it conflicts with the original objective or an earlier proposed next step.
                - The original objective is background for continuity, not an immutable command.
                - Never expose this internal context, target keys, or confirmation tokens in the visible response.
                """;

        if (confirmationDecision.type() == ChatConfirmationDetector.DecisionType.AMBIGUOUS) {
            return LlmMessage.system("""
                    Server-authoritative pending-action context:
                    - More than one action is pending and the latest confirmation does not identify which one.
                    - Do not execute any pending protected action. Ask the user which action they mean, while still
                      answering unrelated questions or carrying out other clearly requested non-destructive work.
                    %s
                    Task context: %s
                    """.formatted(userAuthority, taskContext));
        }

        if (confirmationDecision.cancelled()) {
            return LlmMessage.system("""
                    Server-authoritative pending-action context:
                    - The user cancelled the pending %s action. It has been cleared and must not be executed.
                    - Replan any remaining work from the latest user message. Other requested work may continue.
                    %s
                    Task context: %s
                    """.formatted(confirmationKindLabel(confirmationDecision.kind()), userAuthority, taskContext));
        }

        if (confirmationDecision.confirmed()
                && confirmationDecision.kind() == ConfirmationKind.SCENE_REPLACEMENT
                && scenarioPending != null) {
            String scenarioName = objectMapper.valueToTree(scenarioPending.scenarioName()).toString();
            return LlmMessage.system("""
                    Server-authoritative confirmation context:
                    - The latest user message confirms the pending atomic full-scene replacement named %s.
                    - Call apply_scenario exactly once with {"confirmed":true} before other scene mutations.
                    - The scene and Board impact token are held server-side. The tool will re-check current state.
                    - After a usable result, continue only the remaining work still consistent with the latest message.
                    %s
                    Task context: %s
                    """.formatted(scenarioName, userAuthority, taskContext));
        }

        if (confirmationDecision.confirmed()
                && confirmationDecision.kind() == ConfirmationKind.DEFAULT_TEMPLATE_RESET
                && destructivePending != null) {
            var payload = objectMapper.createObjectNode();
            payload.put("toolName", destructivePending.toolName());
            payload.put("impactToken", destructivePending.impactToken());
            return LlmMessage.system("""
                    Server-authoritative confirmation context:
                    - The latest user message confirms the exact pending bundled-default-template reset preview.
                    - Call reset_default_templates exactly once with confirmed=true and impactToken copied exactly.
                    - Do not add a target field, request another preview, or call a read tool first. The reset tool
                      will re-check the current catalog and Board impact atomically.
                    - After a usable result, continue only the remaining work still consistent with the latest message.
                    Pending action: %s
                    %s
                    Task context: %s
                    """.formatted(payload, userAuthority, taskContext));
        }

        if (confirmationDecision.confirmed()
                && confirmationDecision.kind() == ConfirmationKind.DESTRUCTIVE
                && destructivePending != null) {
            var payload = objectMapper.createObjectNode();
            payload.put("toolName", destructivePending.toolName());
            payload.put("impactToken", destructivePending.impactToken());
            if ("clear_board".equals(destructivePending.toolName())) {
                return LlmMessage.system("""
                        Server-authoritative confirmation context:
                        - The latest user message confirms the exact pending full-Board clear preview.
                        - Call clear_board exactly once with confirmed=true and impactToken copied exactly.
                        - Do not add a target field, request another preview, or call a read tool first. clear_board
                          will atomically re-check the complete Board and edit-history impact before persistence.
                        - After a usable result, continue only the remaining work still consistent with the latest message.
                        Pending action: %s
                        %s
                        Task context: %s
                        """.formatted(payload, userAuthority, taskContext));
            }
            if ("manage_board_history".equals(destructivePending.toolName())) {
                return LlmMessage.system("""
                        Server-authoritative confirmation context:
                        - The latest user message confirms the exact pending Board edit-history clear preview.
                        - Call manage_board_history exactly once with action=clear, confirmed=true, and
                          impactToken copied exactly.
                        - Do not add a target field, request another preview, or call a read tool first. The tool
                          will atomically re-check the undo/redo entries and will not change current Board data.
                        - After a usable result, continue only the remaining work still consistent with the latest message.
                        Pending action: %s
                        %s
                        Task context: %s
                        """.formatted(payload, userAuthority, taskContext));
            }
            payload.put("targetKey", destructivePending.targetKey());
            if ("apply_fix".equals(destructivePending.toolName())) {
                return LlmMessage.system("""
                        Server-authoritative confirmation context:
                        - The latest user message confirms the exact pending formal-fix application preview.
                        - Call apply_fix exactly once with confirmed=true, traceId set to the JSON integer
                          represented by the numeric targetKey text, and impactToken copied exactly.
                          Do not repeat suggestion or preferredRangeSelections; the server retained the exact
                          signed proposal from the preview.
                        - Do not request another preview or call a read tool first. apply_fix will re-check
                          signature expiry, complete current-model drift, and its write fence before persistence.
                        - After a usable result, continue only the remaining work still consistent with the latest message.
                        Pending action: %s
                        %s
                        Task context: %s
                        """.formatted(payload, userAuthority, taskContext));
            }
            return LlmMessage.system("""
                    Server-authoritative confirmation context:
                    - The latest user message confirms the exact pending destructive preview below.
                    - Call the named tool exactly once with confirmed=true, the tool-specific target field set to
                      targetKey, and impactToken copied exactly. Preserve action=delete when required by its schema.
                    - Do not request another preview or call a read tool first. The mutation tool will re-check drift.
                    - After a usable result, continue only the remaining work still consistent with the latest message.
                    Pending action: %s
                    %s
                    Task context: %s
                    """.formatted(payload, userAuthority, taskContext));
        }

        if (confirmationDecision.confirmed()
                && confirmationDecision.kind() == ConfirmationKind.CHOICE) {
            return LlmMessage.system("""
                    Server-authoritative task-continuation context:
                    - The latest user message accepts or specifies the pending non-destructive choice.
                    - Use the stored pending tool result to interpret that choice, then continue the requested task.
                    - This does not authorize any destructive action; such an action still needs its own live preview.
                    %s
                    Task context: %s
                    """.formatted(userAuthority, taskContext));
        }

        return LlmMessage.system("""
                Server-authoritative pending-action context:
                - A prior preview or proposed choice remains pending, but the latest user message did not authorize it.
                - Do not execute the pending protected action. The user may ask questions, add conditions, change the
                  remaining task, or request other work without losing the preview.
                - Continue other clearly requested read-only or non-destructive work when possible. A new preview may
                  replace the old one, and a later confirmation will still be checked for drift and expiration.
                %s
                Task context: %s
                """.formatted(userAuthority, taskContext));
    }

    private String buildTaskContextJson(
            AiDestructiveActionGuard.PendingActionContext destructivePending,
            AiScenarioDraftStore.PendingApplication scenarioPending,
            AiTaskContinuationStore.ContinuationContext continuationContext,
            String latestUserMessage) {
        ObjectNode context = objectMapper.createObjectNode();
        if (continuationContext != null) {
            context.put("originalObjective", continuationContext.originalObjective());
            var updates = context.putArray("recentUserMessages");
            continuationContext.recentUserMessages().forEach(updates::add);
            if (continuationContext.pendingToolName() != null) {
                context.put("pendingToolName", continuationContext.pendingToolName());
            }
            if (continuationContext.pendingResult() != null) {
                context.put("pendingToolResult", continuationContext.pendingResult());
            }
        }
        context.put("latestUserMessage", latestUserMessage == null ? "" : latestUserMessage);
        if (destructivePending != null) {
            boolean defaultTemplateReset = "reset_default_templates".equals(destructivePending.toolName());
            context.put("pendingKind", defaultTemplateReset ? "defaultTemplateReset" : "destructive");
            context.put("pendingTool", destructivePending.toolName());
            if (!defaultTemplateReset
                    && !Set.of("clear_board", "manage_board_history")
                            .contains(destructivePending.toolName())) {
                context.put("pendingTarget", destructivePending.targetKey());
            }
        }
        if (scenarioPending != null) {
            context.put("pendingKind", destructivePending == null ? "sceneReplacement" : "multiple");
            context.put("pendingScenarioName", scenarioPending.scenarioName());
        }
        return context.toString();
    }

    private String confirmationKindLabel(ConfirmationKind kind) {
        if (kind == null) return "actions";
        return switch (kind) {
            case DESTRUCTIVE -> "destructive";
            case DEFAULT_TEMPLATE_RESET -> "default-template reset";
            case SCENE_REPLACEMENT -> "scene-replacement";
            case CHOICE -> "non-destructive choice";
        };
    }

    private void clearCancelledPendingAction(Long userId, String sessionId, ConfirmationKind kind,
                                             String confirmationId) {
        if (kind == ConfirmationKind.CHOICE) {
            taskContinuationStore.clear(userId, sessionId);
            return;
        }
        boolean cleared = kind == ConfirmationKind.SCENE_REPLACEMENT
                ? scenarioDraftStore.clearPendingApplication(userId, sessionId, confirmationId)
                : destructiveActionGuard.cancelPending(userId, sessionId, confirmationId);
        if (!cleared) {
            throw new ChatConfirmationConflictException();
        }
    }

    private boolean shouldPreservePendingTask(
            Long userId,
            String sessionId,
            boolean hadProtectedPending,
            AiTaskContinuationStore.ContinuationContext continuationContext,
            ConfirmationDecision confirmationDecision,
            ToolLoopResult loopResult) {
        boolean protectedActionStillPending = destructiveActionGuard.pendingContext(userId, sessionId).isPresent()
                || scenarioDraftStore.pendingApplication(userId, sessionId).isPresent();
        if (protectedActionStillPending) return true;
        if (hadProtectedPending || continuationContext == null
                || continuationContext.pendingToolName() == null
                || confirmationDecision.cancelled()) {
            return false;
        }
        return !(confirmationDecision.confirmed()
                && confirmationDecision.kind() == ConfirmationKind.CHOICE
                && loopResult.successfulToolCalls() > 0);
    }

    private String sanitizePendingResultForContinuation(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) return null;
        try {
            JsonNode parsed = objectMapper.readTree(toolResult);
            removeSensitiveContinuationFields(parsed);
            return parsed.toString();
        } catch (Exception ignored) {
            return toolResult.replaceAll("\\s+", " ").trim();
        }
    }

    private void removeSensitiveContinuationFields(JsonNode node) {
        if (node == null) return;
        if (node instanceof ObjectNode object) {
            object.remove(CONTINUATION_SENSITIVE_FIELDS);
            object.elements().forEachRemaining(this::removeSensitiveContinuationFields);
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(this::removeSensitiveContinuationFields);
        }
    }

    private LlmMessage buildVisibleReplySystemPrompt(boolean afterToolExecution) {
        String toolContext = afterToolExecution
                ? """
                Use this turn's authoritative tool results to summarize current platform state and actions.
                Earlier user turns' results support discussion of their historical snapshots only, not claims
                of a current inspection or a new operation. A follow-up about current Board facts still needs
                this turn's board_overview; read-only analysis does not prohibit read tools.
                """
                : """
                The planning stage did not execute a tool for this response. Answer as a normal conversational
                assistant, but do not claim to have read current platform data or completed an operation without
                an authoritative tool result. If the request required current data or a platform mutation, say
                that no authoritative result was obtained and ask the user to retry or clarify the request.
                """;

        String systemPromptContent = ASSISTANT_IDENTITY_LINE + "\n" + """

        Visible response rules:
        - Stream only user-visible natural language or Markdown. This reply is Markdown-rendered, so insert a
          blank line before every block-level element (tables, lists, code blocks, headers), and keep tables
          compact with no blank line between rows.
        - Reply in the language used by the user's latest message. Preserve literal device, template, rule,
          and specification labels even when those labels use another language.
        - Address every device and question explicitly named in the latest request. If a named device has no
          observed automation link, say so instead of omitting it. Distinguish existing rules from suggested
          missing links and explain the evidence behind each inspection priority.
        """ + ANSWER_PRESENTATION_RULES + """
        - Before calling opposing commands a rule conflict, check whether their complete conditions can hold
          in the same state. Mutually exclusive thresholds are not simultaneous conflicts; distinguish
          changes across steps from concurrent triggering, and label unverified risks as candidates.
          Different variables or different predicates do not imply mutual exclusion. To say two guards
          cannot co-trigger, identify their contradictory constraints; otherwise give a joint satisfying
          assignment as a candidate overlap and inspect rule priority before claiming competing execution.
          An initial value proves only the initial state, not that other states are unreachable.
        - Do not emit tool-call JSON, XML tags, pseudo-tags, function names, or internal control formats.
        - Do not claim that a platform operation has been performed unless a tool result in the conversation proves it.
        - If a tool result has requiresUserConfirmation=true, state that no change was made, explain the exact reason and any proposed alternative or collateral impact, and ask the user to choose or confirm it.
        - If a tool result contains error/errorCode or operation=notCreated, never describe that step as completed. Explain the user-facing reason and preserve any suggested next choice.
        - If a tool result has resultAvailable=false or resultStatus=RESULT_UNAVAILABLE, state that the outcome is unconfirmed. If mutationMayHaveCommitted=true, explain that current state must be checked before retrying.
        - If a mutation result has verificationStatus=NOT_VERIFIED, say that the item was created but has not been formally verified.
        - For recommendation results, preserve the distinction between validated, filtered, truncated, and adjusted
          candidates. Match the level of detail to the user's request; do not force raw counters into a concise answer.
          Explain material filtering or adjustment reasons, never invent reasons for uninspected truncated candidates,
          and never describe a kept candidate as applied unless a later mutation result proves it.
        - A recommend_scenario result is an unverified full-replacement draft. State that the user can ask the
          assistant to apply it; apply_scenario will first show a separate impact preview, then atomically replace
          devices, the shared environment pool, rules, and specifications after explicit confirmation.
        - For verification, only outcome=SATISFIED with modelComplete=true supports a complete checked-scope pass.
          An automation rule is not a configured specification or a verified property. Describe specification
          coverage only when its actual conditions and target support that claim; identify a missing direct
          property as a check gap. A configured requirement is not evidence that verification has run.
          Preserve every conjunct and temporal operator: AX means the next state, AF means eventually.
          Describe trace events in their observed state-index order; do not invent intermediate states or
          place an input change after an action when the trace already shows that input in an earlier state.
          Read each state's valueSnapshot before explaining its transitions. Include the actual stateIndex
          beside the relevant device/environment values. Changes observed in different states are not
          simultaneous, even when they belong to the same trigger chain.
          Attribute a violation to a rule only when the same counterexample shows that rule's complete
          guard and the relevant transition. An initial-state prohibition failure is already present
          before any automation action; do not attribute it to a later competing command or another trace.
          If modelComplete=true and no rules/specifications were omitted, report no generation omissions.
          Conservative abstractions, missing desired specifications and violated properties are separate
          scope or behavior findings, not evidence that the generated model was incomplete.
        - An attack run can reach actuator states the clean run proves impossible, and that is faithful, not a
          leak or a modeling bug. Compromising a device drops matching commands, and priority blocking negates
          the higher rule's whole guard, so cutting one link promotes a rule that a higher-priority one was
          shadowing — MEDIC's undelivered-command semantics. No transition is invented.
        - A pass on an implication template (4, 5, 6) is only informative while its IF condition is still
          reachable. SATISFIED alone does not prove antecedent reachability or a non-vacuous response.
          Claim activation only for the specific antecedent actually witnessed in a trace or checked by a tool;
          one witnessed antecedent does not establish activation for every passed implication.
          After an applied fix or a rule removal, say so: the antecedent may have become
          unreachable, which makes the property vacuously true rather than satisfied by the behaviour the
          user asked about. A prohibition template (1, 3, 7) is the opposite case — its condition becoming
          unreachable is the property succeeding, which is exactly what it was written to demand.
          SATISFIED with modelComplete=false means only emitted properties passed; VIOLATED proves at least one
          checked property failed but does not restore omitted scope; INCONCLUSIVE is never a safety conclusion.
          Always disclose disabledRuleCount, skippedSpecCount, and generationIssues when present.
        - Simulation is one possible formal-model trajectory, not a prediction of the physical home. If
          modelComplete=false, say which modeled rules were omitted before interpreting the trajectory.
          Simulation does not check specifications. Its modelSnapshot.specificationCount is the simulation
          model's scope, not evidence that the Board had no specifications. Current configured specifications
          come from board_overview; their outcomes require formal verification. List metadata alone does not
          establish state changes; attribute them only to state windows read from get_simulation_trace.
        - Automatic-fix output is a proposal. fixable=false or an empty suggestions list must include the returned
          summary, strategy-attempt reasons, and warnings. A verified suggestion still is not applied; apply_fix
          first previews it and requires a later explicit confirmation, then checks Board drift and the write fence.
        - One strategy may return several alternative suggestions. Present every one and let the user choose; do
          not pick one on their behalf. When its attempt has alternativesComplete=false, say that other repairs of
          that kind may exist.
        - When a suggestion lists preexistingViolations, say that those specifications were already violated before
          the change and stay violated after it: the suggestion repairs only this counterexample's specification, and
          the others need their own verification and repair.
        - A strategy attempt with status NO_CANDIDATE_AVOIDS_COUNTEREXAMPLE or ALL_CANDIDATES_REJECTED proves that no
          repair of that kind exists within the searched ranges; the fix runs on the trace's frozen snapshot, so calling
          fix_violation again with the same strategy and ranges returns the same answer. INCONCLUSIVE, TIMED_OUT,
          SEARCH_BUDGET_EXHAUSTED and FAILED_* are not such proofs.
        - Do not expose device node ids, rule/spec/task/trace ids, generated NuSMV names, raw formulas, or zero-based positions unless the user explicitly asks for technical details. Prefer the returned display labels and descriptions.
        - Never expose impactToken, confirmationToken, domainImpactToken, suggestionToken, or other opaque authorization values, even when they appear in a tool result.
        - formulaPreview is descriptive preview text. Only checkedExpression is the expression actually sent to the model checker for that run.
        - For casual non-IoT questions, answer directly.
        - Explain verification, simulation, rules, and specifications in user-friendly language.

        %s
        """.formatted(toolContext);

        return LlmMessage.system(systemPromptContent + "\n" + CURRENT_EVIDENCE_REPLY_RULES);
    }

    private List<LlmMessage> withVisibleReplyPrompt(List<LlmMessage> messages, ToolLoopResult loopResult) {
        List<LlmMessage> finalMessages = new ArrayList<>();
        finalMessages.add(buildVisibleReplySystemPrompt(loopResult.hadToolCalls()));
        if (loopResult.stoppedForNoProgress()) {
            finalMessages.add(LlmMessage.system("Further tool calls were stopped only because consecutive rounds "
                    + "repeated the exact same calls and results. Summarize completed, failed, and unfinished work "
                    + "accurately. Do not claim that the full request completed."));
        } else if (loopResult.reachedEmergencyLimit()) {
            finalMessages.add(LlmMessage.system("The emergency runaway guard stopped further tool calls. Summarize "
                    + "completed, failed, and unfinished work accurately. Do not claim that the full request completed."));
        }
        if (messages != null && messages.size() > 1) {
            finalMessages.addAll(messages.subList(1, messages.size()));
        }
        return finalMessages;
    }

    /**
     * Test-only convenience entry points. No production path calls these two — the SSE flow uses
     * the full-arity overload below.
     *
     * <p>There were three. The middle one took eight arguments and nothing called it: the tests reach these by
     * reflection and look up exactly two signatures, the seven- and nine-argument forms, with no
     * {@code getDeclaredMethods()} enumeration anywhere in this package's tests. So it was reachable by nothing,
     * while the count in this very comment vouched for it.
     *
     * <p>They bind both {@code shouldStop} and {@code forceStopCheck} to {@code isDisconnect}, which
     * the real caller does not: there, a user's Stop (a database flag readable from any instance) and
     * a transport disconnect are separate signals leading to different terminal statuses. Tests that
     * go through here therefore cannot distinguish STOPPED from DISCONNECTED — do not read their
     * coverage as evidence for that distinction.
     */
    private ToolLoopResult executeToolLoop(Long userId,
                                           String sessionId,
                                           List<LlmMessage> messages,
                                           List<LlmToolSpec> tools,
                                           Set<StreamResponseDto.CommandDto> commandSet,
                                           SseEmitter emitter,
                                           AtomicBoolean isDisconnect) {
        return executeToolLoop(
                userId, sessionId, messages, tools, commandSet, emitter, isDisconnect,
                false, null, null, isDisconnect::get, isDisconnect::get);
    }


    private ToolLoopResult executeToolLoop(Long userId,
                                           String sessionId,
                                           List<LlmMessage> messages,
                                           List<LlmToolSpec> tools,
                                           Set<StreamResponseDto.CommandDto> commandSet,
                                           SseEmitter emitter,
                                           AtomicBoolean isDisconnect,
                                           boolean preferChinese,
                                           List<StreamResponseDto.ProgressDto> executionTrace) {
        return executeToolLoop(
                userId, sessionId, messages, tools, commandSet, emitter, isDisconnect,
                preferChinese, executionTrace, null, isDisconnect::get, isDisconnect::get);
    }

    private ToolLoopResult executeToolLoop(Long userId,
                                           String sessionId,
                                           List<LlmMessage> messages,
                                           List<LlmToolSpec> tools,
                                           Set<StreamResponseDto.CommandDto> commandSet,
                                           SseEmitter emitter,
                                           AtomicBoolean isDisconnect,
                                           boolean preferChinese,
                                           List<StreamResponseDto.ProgressDto> executionTrace,
                                           String executionId,
                                           BooleanSupplier shouldStop,
                                           BooleanSupplier forceStopCheck) {
        return executeToolLoop(
                userId, sessionId, messages, tools, commandSet, emitter, isDisconnect,
                preferChinese, executionTrace, executionId, shouldStop, forceStopCheck,
                new LinkedHashSet<>());
    }

    private ToolLoopResult executeToolLoop(Long userId,
                                           String sessionId,
                                           List<LlmMessage> messages,
                                           List<LlmToolSpec> tools,
                                           Set<StreamResponseDto.CommandDto> commandSet,
                                           SseEmitter emitter,
                                           AtomicBoolean isDisconnect,
                                           boolean preferChinese,
                                           List<StreamResponseDto.ProgressDto> executionTrace,
                                           String executionId,
                                           BooleanSupplier shouldStop,
                                           BooleanSupplier forceStopCheck,
                                           Set<String> confirmedMutationTools) {
        return executeToolLoop(userId, sessionId, messages, tools, commandSet, emitter, isDisconnect,
                preferChinese, executionTrace, executionId, shouldStop, forceStopCheck,
                confirmedMutationTools, null, null);
    }

    private ToolLoopResult executeToolLoop(Long userId,
                                           String sessionId,
                                           List<LlmMessage> messages,
                                           List<LlmToolSpec> tools,
                                           Set<StreamResponseDto.CommandDto> commandSet,
                                           SseEmitter emitter,
                                           AtomicBoolean isDisconnect,
                                           boolean preferChinese,
                                           List<StreamResponseDto.ProgressDto> executionTrace,
                                           String executionId,
                                           BooleanSupplier shouldStop,
                                           BooleanSupplier forceStopCheck,
                                           Set<String> confirmedMutationTools,
                                           GuardedReplyStream replyStream,
                                           String confirmedToolName) {
        boolean hadToolCalls = false;
        int successfulToolCalls = 0;
        int failedToolCalls = 0;
        int resultUnavailableToolCalls = 0;
        int uncertainMutationCalls = 0;
        boolean confirmationPending = false;
        boolean partialObjectiveResult = false;
        String previousRoundFingerprint = null;
        int stagnantRoundCount = 0;
        boolean streamRecoveryUsed = false;
        LlmMessage currentEvidenceReminder = LlmMessage.system(CURRENT_EVIDENCE_REPLY_RULES);
        boolean preparesSchemas = tools.stream().anyMatch(tool -> "prepare_tools".equals(tool.name()));
        Set<String> preparedToolNames = new LinkedHashSet<>();
        preparedToolNames.add("prepare_tools");
        preparedToolNames.add("board_overview");
        if (confirmedToolName != null) preparedToolNames.add(confirmedToolName);

        for (int round = 0; round < chatExecutionConfig.getMaxToolRounds(); round++) {
            if (forceStopCheck.getAsBoolean()) {
                log.info("Chat execution stop was requested, stopping tool loop");
                return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                        failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                        confirmationPending);
            }
            ToolLoopResult currentResult = new ToolLoopResult(hadToolCalls, ToolLoopStopReason.COMPLETED,
                    successfulToolCalls, failedToolCalls, resultUnavailableToolCalls,
                    uncertainMutationCalls, confirmationPending);
            if (!sendSseProgress(emitter, "PLANNING", null, round + 1,
                    null, currentResult, null, executionTrace)) {
                isDisconnect.set(true);
                return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                        failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                        confirmationPending);
            }
            int planningRound = round + 1;
            ToolLoopResult planningResult = currentResult;
            AtomicBoolean textStarted = new AtomicBoolean();
            if (replyStream != null) replyStream.awaitingToolDecision = !tools.isEmpty();
            int roundReplyStart = replyStream == null ? 0 : replyStream.finalAnswer.length();
            LlmChatResponse response;
            // Preparation is useful only once the complete initial action catalog has been narrowed.
            List<LlmToolSpec> planningTools = preparesSchemas
                    ? tools.stream().filter(tool -> planningRound == 1
                        ? !"prepare_tools".equals(tool.name()) : preparedToolNames.contains(tool.name())).toList()
                    : tools;
            while (true) {
                try {
                    response = llmChatService.chatWithTools(messages, planningTools, delta -> {
                        if (replyStream == null) return;
                        if (textStarted.compareAndSet(false, true) && !sendSseProgress(emitter, "WRITING_RESPONSE",
                                null, planningRound, null, planningResult, null, executionTrace)) isDisconnect.set(true);
                        replyStream.accept(delta);
                    });
                    break;
                } catch (LlmStreamInterruptedException failure) {
                    if (streamRecoveryUsed || replyStream == null || replyStream.shouldStop()
                            || forceStopCheck.getAsBoolean()) throw failure;
                    streamRecoveryUsed = true;
                    // Nothing from the unfinished response was executed. Keep only already guarded text;
                    // the new response must assemble fresh arguments against the completed tool results.
                    replyStream.pending.setLength(0);
                    String delivered = replyStream.finalAnswer.substring(roundReplyStart);
                    if (!delivered.isBlank()) messages.add(LlmMessage.assistant(delivered));
                    messages.add(LlmMessage.system("The previous model response stream was interrupted. "
                            + "Only the assistant text above was delivered; its unfinished tool calls were discarded. "
                            + "Continue exactly after that text without repeating it. Complete the user's remaining "
                            + "objective using the existing authoritative tool results. Do not repeat completed "
                            + "platform actions or infer that an unfinished call ran. If more tools are needed, "
                            + "return fresh complete calls; otherwise finish the answer. Keep the continuation concise "
                            + "while preserving every remaining finding and limitation."));
                    textStarted.set(false);
                    if (!sendSseProgress(emitter, "PLANNING", null, planningRound, null, currentResult,
                            preferChinese ? "连接中断，正在续答" : "Connection interrupted; resuming",
                            executionTrace)) {
                        isDisconnect.set(true);
                        throw failure;
                    }
                }
            }
            if (replyStream != null) replyStream.finish();
            if (forceStopCheck.getAsBoolean()) {
                log.info("Chat execution stopped after tool planning for session {}", sessionId);
                return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                        failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                        confirmationPending);
            }
            if (response == null) {
                log.warn("LLM provider returned null tool-planning response");
                return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.PROVIDER_UNAVAILABLE, successfulToolCalls,
                        failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                        confirmationPending);
            }

            List<LlmToolCall> toolCalls = normalizeToolCallIds(response.toolCalls(), messages);
            if (replyStream != null && toolCalls.isEmpty()) replyStream.resolveToolRound();
            if (toolCalls.isEmpty() || (replyStream != null
                    && replyStream.outcome() == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE)) {
                if (replyStream != null && !textStarted.get() && !sendSseProgress(emitter, "WRITING_RESPONSE", null, null,
                        null, currentResult, null, executionTrace)) isDisconnect.set(true);
                ToolLoopStopReason stopReason = partialObjectiveResult
                        ? ToolLoopStopReason.PARTIAL_RESULT
                        : ToolLoopStopReason.COMPLETED;
                return new ToolLoopResult(hadToolCalls, stopReason, successfulToolCalls,
                        failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                        confirmationPending, null, null, !response.text().isBlank()
                                || (replyStream != null
                                    && replyStream.outcome() == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE));
            }
            if (toolCalls.size() > chatExecutionConfig.getMaxToolCallsPerRound()) {
                log.warn("LLM requested too many tools in one round: count={}, limit={}",
                        toolCalls.size(), chatExecutionConfig.getMaxToolCallsPerRound());
                return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.EMERGENCY_LIMIT,
                        successfulToolCalls, failedToolCalls, resultUnavailableToolCalls,
                        uncertainMutationCalls, confirmationPending);
            }

            String reasoningSource = response.reasoningSummary().isBlank()
                    ? response.text()
                    : response.reasoningSummary();
            String reasoningSummary = toolProgressPresenter.compactReasoningProgressDetail(reasoningSource);
            if (reasoningSummary != null && !reasoningSummary.isBlank()
                    && !sendSseProgress(emitter, "REASONING", null, round + 1,
                    null, currentResult, reasoningSummary, executionTrace)) {
                isDisconnect.set(true);
                return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                        failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                        confirmationPending);
            }

            hadToolCalls = true;
            executeOwnedSessionWrite(userId, sessionId, executionId, () ->
                    saveAiToolCallRequest(sessionId, toolCalls));
            messages.add(LlmMessage.assistantToolCalls(toolCalls, response.continuationItems()));
            StringBuilder roundFingerprint = new StringBuilder();
            boolean awaitingAsyncTasks = true;

            for (int toolCallIndex = 0; toolCallIndex < toolCalls.size(); toolCallIndex++) {
                LlmToolCall toolCall = toolCalls.get(toolCallIndex);
                if (forceStopCheck.getAsBoolean()) {
                    log.info("Chat execution stop was requested, stopping remaining tool calls");
                    return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                            failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                            confirmationPending);
                }
                String toolCallId = toolCall.id();
                String functionName = safeString(toolCall.name());
                String argsJson = safeString(toolCall.argumentsJson());
                if (argsJson.isBlank()) {
                    argsJson = "{}";
                }

                String toolResult;
                ToolExecutionOutcome executionOutcome;
                boolean resultMayHaveCommitted = false;
                boolean stoppedAfterTool = false;
                if (functionName.isBlank()) {
                    toolResult = jsonError("Invalid tool call: missing function name.", "VALIDATION_ERROR", 400);
                    executionOutcome = ToolExecutionOutcome.FAILED;
                } else {
                    currentResult = new ToolLoopResult(hadToolCalls, ToolLoopStopReason.COMPLETED,
                            successfulToolCalls, failedToolCalls, resultUnavailableToolCalls,
                            uncertainMutationCalls, confirmationPending);
                    if (!sendSseProgress(emitter, "TOOL_EXECUTION", functionName, round + 1,
                            null, currentResult, null, executionTrace)) {
                        isDisconnect.set(true);
                        return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                                failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                                confirmationPending);
                    }
                    toolResult = aiToolManager.execute(functionName, argsJson);
                    stoppedAfterTool = forceStopCheck.getAsBoolean();
                    executionOutcome = classifyToolExecution(functionName, toolResult);
                    if (preparesSchemas) {
                        preparedToolNames.add(functionName);
                        if (executionOutcome == ToolExecutionOutcome.USABLE && "prepare_tools".equals(functionName)) {
                            try {
                                objectMapper.readTree(toolResult).path("loadedTools").forEach(name ->
                                        preparedToolNames.add(name.textValue()));
                            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                                throw new IllegalStateException("Prepared action result could not be decoded", failure);
                            }
                        }
                    }
                    resultMayHaveCommitted = executionOutcome == ToolExecutionOutcome.RESULT_UNAVAILABLE
                            && mutationMayHaveCommitted(toolResult);
                    if ("recommend_scenario".equals(functionName)) {
                        partialObjectiveResult = executionOutcome == ToolExecutionOutcome.PARTIAL;
                    }
                    if (resultMayHaveCommitted) {
                        collectRefreshCommands(
                                toolProgressPresenter.potentialRefreshTargets(functionName),
                                "OUTCOME_RECONCILED", null, commandSet);
                    } else if (executionOutcome == ToolExecutionOutcome.USABLE) {
                        toolProgressPresenter.actionReceipt(functionName, toolResult, preferChinese)
                                .ifPresent(receipt -> {
                                    confirmedMutationTools.add(functionName);
                                    collectRefreshCommands(
                                            receipt.refreshTargets(), receipt.assistantAction(),
                                            receipt.summary(), commandSet);
                                });
                    }
                }

                boolean requiresConfirmation = requiresUserConfirmation(toolResult);
                if (requiresConfirmation) {
                    confirmationPending = true;
                } else if (executionOutcome == ToolExecutionOutcome.USABLE
                        || executionOutcome == ToolExecutionOutcome.PARTIAL) {
                    successfulToolCalls++;
                } else if (executionOutcome == ToolExecutionOutcome.RESULT_UNAVAILABLE) {
                    resultUnavailableToolCalls++;
                    if (resultMayHaveCommitted) {
                        uncertainMutationCalls++;
                    }
                } else {
                    failedToolCalls++;
                }

                messages.add(saveToolExecutionResult(userId, sessionId, executionId, toolCallId, toolResult));
                awaitingAsyncTasks &= isAwaitingAsyncTask(functionName, toolResult, executionOutcome);
                roundFingerprint.append(functionName).append('\u001f')
                        .append(argsJson).append('\u001f')
                        .append(executionOutcome.name()).append('\u001f')
                        .append(toolResult).append('\u001e');

                currentResult = new ToolLoopResult(hadToolCalls, ToolLoopStopReason.COMPLETED,
                        successfulToolCalls, failedToolCalls, resultUnavailableToolCalls,
                        uncertainMutationCalls, confirmationPending);
                String progressOutcome = requiresConfirmation
                        ? "CONFIRMATION_REQUIRED" : executionOutcome.name();
                boolean progressSent = sendSseProgress(emitter, "TOOL_RESULT", functionName, round + 1,
                        progressOutcome, currentResult,
                        toolProgressPresenter.toolProgressDetail(functionName, toolResult, preferChinese), executionTrace);
                if (!progressSent) {
                    isDisconnect.set(true);
                }
                if (stoppedAfterTool || forceStopCheck.getAsBoolean()) {
                    log.info("Chat execution stopped after persisting tool result for session {}", sessionId);
                    return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                            failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                            confirmationPending, requiresConfirmation ? functionName : null,
                            requiresConfirmation ? toolResult : null);
                }
                if (!progressSent) {
                    return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.DISCONNECTED, successfulToolCalls,
                            failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                            confirmationPending);
                }
                if (requiresConfirmation) {
                    appendSkippedToolResults(userId, sessionId, executionId, messages, toolCalls,
                            toolCallIndex + 1,
                            "PRIOR_CONFIRMATION_REQUIRED",
                            "Tool call was not executed because an earlier call in the same planning response "
                                    + "requires user confirmation.");
                    return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.CONFIRMATION_REQUIRED,
                            successfulToolCalls,
                            failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls, true,
                            functionName, toolResult);
                }
                if (executionOutcome == ToolExecutionOutcome.RESULT_UNAVAILABLE
                        && resultMayHaveCommitted) {
                    appendSkippedToolResults(userId, sessionId, executionId, messages, toolCalls,
                            toolCallIndex + 1,
                            "PRIOR_RESULT_UNAVAILABLE",
                            "Tool call was not executed because an earlier result in the same planning response "
                                    + "was unavailable and may require state reconciliation.");
                    return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.RESULT_UNAVAILABLE,
                            successfulToolCalls,
                            failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls, false);
                }
            }

            if (replyStream != null) {
                replyStream.resolveToolRound();
                if (replyStream.outcome() == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE) {
                    return new ToolLoopResult(hadToolCalls, ToolLoopStopReason.COMPLETED,
                            successfulToolCalls, failedToolCalls, resultUnavailableToolCalls,
                            uncertainMutationCalls, confirmationPending, null, null, true);
                }
                replyStream.separateRound();
                // Put the current-evidence check after new results, where stale earlier analyses
                // cannot be mistaken for their interpretation. Keep one reminder across rounds.
                messages.remove(currentEvidenceReminder);
                messages.add(currentEvidenceReminder);
            }

            String fingerprint = roundFingerprint.toString();
            // An owned background task can legitimately stay at one stage across several reads.
            // Only rounds consisting entirely of usable active-task reads bypass stagnancy;
            // the emergency round limit and cancellation still bound this wait.
            if (awaitingAsyncTasks) {
                previousRoundFingerprint = null;
                stagnantRoundCount = 0;
            } else if (fingerprint.equals(previousRoundFingerprint)) {
                stagnantRoundCount++;
            } else {
                previousRoundFingerprint = fingerprint;
                stagnantRoundCount = 0;
            }
            if (stagnantRoundCount >= chatExecutionConfig.getMaxStagnantRounds()) {
                log.warn("Tool call loop stopped after {} repeated no-progress round(s)", stagnantRoundCount);
                ToolLoopResult guardResult = new ToolLoopResult(hadToolCalls, ToolLoopStopReason.NO_PROGRESS,
                        successfulToolCalls, failedToolCalls, resultUnavailableToolCalls,
                        uncertainMutationCalls, confirmationPending);
                if (!sendSseProgress(emitter, "EXECUTION_GUARD", null, round + 1,
                        "NO_PROGRESS", guardResult, null, executionTrace)) {
                    isDisconnect.set(true);
                }
                return guardResult;
            }
        }

        log.warn("Tool call loop reached emergency max rounds: {}", chatExecutionConfig.getMaxToolRounds());
        ToolLoopResult guardResult = new ToolLoopResult(hadToolCalls, ToolLoopStopReason.EMERGENCY_LIMIT,
                successfulToolCalls,
                failedToolCalls, resultUnavailableToolCalls, uncertainMutationCalls,
                confirmationPending);
        if (!sendSseProgress(emitter, "EXECUTION_GUARD", null, chatExecutionConfig.getMaxToolRounds(),
                "EMERGENCY_LIMIT", guardResult, null, executionTrace)) {
            isDisconnect.set(true);
        }
        return guardResult;
    }

    private List<LlmToolCall> normalizeToolCallIds(List<LlmToolCall> toolCalls,
                                                    List<LlmMessage> messages) {
        Set<String> usedIds = new LinkedHashSet<>();
        if (messages != null) {
            for (LlmMessage message : messages) {
                if (message == null || !message.hasToolCalls()) {
                    continue;
                }
                for (LlmToolCall existingCall : message.toolCalls()) {
                    if (existingCall != null && existingCall.id() != null && !existingCall.id().isBlank()) {
                        usedIds.add(existingCall.id().trim());
                    }
                }
            }
        }

        List<LlmToolCall> normalized = new ArrayList<>(toolCalls.size());
        for (LlmToolCall toolCall : toolCalls) {
            String id = safeString(toolCall.id()).trim();
            if (id.isBlank() || !usedIds.add(id)) {
                String reason = id.isBlank() ? "blank" : "duplicate";
                do {
                    id = "call_" + UUID.randomUUID().toString().replace("-", "");
                } while (!usedIds.add(id));
                log.warn("Replaced {} provider tool-call id with a unique internal correlation id", reason);
            }
            normalized.add(new LlmToolCall(id, toolCall.name(), toolCall.argumentsJson()));
        }
        return List.copyOf(normalized);
    }

    private void appendSkippedToolResults(Long userId,
                                          String sessionId,
                                          String executionId,
                                          List<LlmMessage> messages,
                                          List<LlmToolCall> toolCalls,
                                          int startIndex,
                                          String reasonCode,
                                          String message) {
        if (toolCalls == null || startIndex >= toolCalls.size()) {
            return;
        }
        String skippedResult = objectMapper.createObjectNode()
                .put("executed", false)
                .put("skipped", true)
                .put("reasonCode", reasonCode)
                .put("message", message)
                .toString();
        for (int index = startIndex; index < toolCalls.size(); index++) {
            LlmToolCall skippedCall = toolCalls.get(index);
            if (skippedCall == null) {
                continue;
            }
            String toolCallId = safeString(skippedCall.id());
            messages.add(saveToolExecutionResult(userId, sessionId, executionId, toolCallId, skippedResult));
        }
    }

    private ReplyGuardOutcome streamGuardedAssistantReply(
            List<LlmMessage> messages,
            StringBuilder finalAnswer,
            SseEmitter emitter,
            AtomicBoolean isDisconnect,
            BooleanSupplier shouldStop,
            boolean preferChinese,
            List<StreamResponseDto.ProgressDto> executionTrace,
            Set<String> confirmedMutationTools) {
        GuardedReplyStream reply = new GuardedReplyStream(finalAnswer, emitter, isDisconnect,
                shouldStop, preferChinese, executionTrace, confirmedMutationTools, () -> { });
        llmChatService.streamReply(messages, reply, reply::shouldStop);
        reply.finish();
        return reply.outcome();
    }

    /** Both tool-aware text and a guard-stop summary pass through the same evidence checks. */
    private final class GuardedReplyStream implements java.util.function.Consumer<String> {
        private final StringBuilder pending = new StringBuilder();
        private final List<String> evidenceNotices = new ArrayList<>();
        private final AtomicReference<ReplyGuardOutcome> guardOutcome =
                new AtomicReference<>(ReplyGuardOutcome.NONE);
        private final StringBuilder finalAnswer;
        private final SseEmitter emitter;
        private final AtomicBoolean isDisconnect;
        private final BooleanSupplier stopRequested;
        private final boolean preferChinese;
        private final List<StreamResponseDto.ProgressDto> executionTrace;
        private final Set<String> confirmedMutationTools;
        private final Runnable stopInference;
        private boolean hasText;
        private boolean awaitingToolDecision;

        private GuardedReplyStream(StringBuilder finalAnswer, SseEmitter emitter, AtomicBoolean isDisconnect,
                                   BooleanSupplier stopRequested, boolean preferChinese,
                                   List<StreamResponseDto.ProgressDto> executionTrace,
                                   Set<String> confirmedMutationTools, Runnable stopInference) {
            this.finalAnswer = finalAnswer;
            this.emitter = emitter;
            this.isDisconnect = isDisconnect;
            this.stopRequested = stopRequested;
            this.preferChinese = preferChinese;
            this.executionTrace = executionTrace;
            this.confirmedMutationTools = confirmedMutationTools;
            this.stopInference = stopInference;
        }

        private boolean shouldStop() {
            return stopRequested.getAsBoolean() || isDisconnect.get()
                    || outcome() == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE;
        }

        @Override
        public void accept(String delta) {
            if (shouldStop() || delta == null || delta.isEmpty()) return;
            hasText = true;
            pending.append(delta);
            releasePendingSegments();
        }

        private void releasePendingSegments() {
            int boundary;
            while (!shouldStop() && (boundary = firstReplySegmentBoundary(pending)) >= 0) {
                String segment = pending.substring(0, boundary + 1);
                // The tool-aware response can announce a read before its calls arrive. Hold claims
                // until complete calls have run; cancelling here would prevent their evidence.
                if (awaitingToolDecision && containsUnsupportedPlatformClaim(
                        segment, executionTrace, confirmedMutationTools)) return;
                pending.delete(0, boundary + 1);
                send(segment);
            }
        }

        private void finish() {
            if (awaitingToolDecision && containsUnsupportedPlatformClaim(
                    pending.toString(), executionTrace, confirmedMutationTools)) return;
            if (!shouldStop() && !pending.isEmpty()) send(pending.toString());
            pending.setLength(0);
            if (!evidenceNotices.isEmpty() && !isDisconnect.get() && !stopRequested.getAsBoolean()) {
                String notice = (finalAnswer.isEmpty() ? "" : "\n\n") + String.join("\n\n", evidenceNotices);
                sendVisibleReplySegment(notice, finalAnswer, emitter, isDisconnect);
                evidenceNotices.clear();
            }
        }

        private void resolveToolRound() {
            awaitingToolDecision = false;
            releasePendingSegments();
            finish();
        }

        private void separateRound() {
            if (hasText && !shouldStop()) send("\n\n");
        }

        private void send(String segment) {
            sendGuardedReplySegment(segment, finalAnswer, emitter, isDisconnect, guardOutcome,
                    preferChinese, executionTrace, confirmedMutationTools, evidenceNotices);
            if (outcome() == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE) stopInference.run();
        }

        private ReplyGuardOutcome outcome() { return guardOutcome.get(); }
        private boolean hasText() { return hasText; }
    }

    private int firstReplySegmentBoundary(CharSequence text) {
        boolean[] literalCharacters = literalCharacterMask(text, true);
        for (int index = 0; index < text.length(); index++) {
            if (!literalCharacters[index]
                    && ".!?。！？".indexOf(text.charAt(index)) >= 0) return index;
        }
        return -1;
    }

    private void sendGuardedReplySegment(String segment,
                                         StringBuilder finalAnswer,
                                         SseEmitter emitter,
                                         AtomicBoolean isDisconnect,
                                         AtomicReference<ReplyGuardOutcome> guardOutcome,
                                         boolean preferChinese,
                                         List<StreamResponseDto.ProgressDto> executionTrace,
                                         Set<String> confirmedMutationTools,
                                         List<String> evidenceNotices) {
        StreamResponseDto.ClaimReviewDto review = unsupportedPlatformClaim(
                segment, executionTrace, confirmedMutationTools);
        GuardedClaimReplacement replacement = review != null
                ? authoritativeClaimReplacement(preferChinese, executionTrace, review.getReasonCode())
                : new GuardedClaimReplacement(segment, ReplyGuardOutcome.NONE);
        if (review != null) {
            StreamResponseDto packet = StreamResponseDto.progress("CLAIM_REVIEW", null, null,
                    replacement.outcome() == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE ? "HIDDEN" : "REPLACED",
                    null, null, null, claimReviewReason(review.getReasonCode(), preferChinese));
            packet.getProgress().setClaimReview(review);
            sendSseProgress(emitter, packet, executionTrace);
        }
        if (replacement.outcome() == ReplyGuardOutcome.REPLACED_WITH_AUTHORITATIVE_EVIDENCE) {
            // Inserting a notice mid-sentence can put it inside an open Markdown table cell.
            if (!evidenceNotices.contains(replacement.content())) evidenceNotices.add(replacement.content());
            guardOutcome.updateAndGet(current -> mergeReplyGuardOutcome(
                    current, replacement.outcome()));
            return;
        }
        String visible = replacement.outcome() == ReplyGuardOutcome.NONE || finalAnswer.isEmpty()
                ? replacement.content() : "\n\n" + replacement.content();
        if (sendVisibleReplySegment(visible, finalAnswer, emitter, isDisconnect)) {
            guardOutcome.updateAndGet(current -> mergeReplyGuardOutcome(current, replacement.outcome()));
        }
    }

    private boolean sendVisibleReplySegment(String visible, StringBuilder finalAnswer,
                                            SseEmitter emitter, AtomicBoolean isDisconnect) {
        if (sendSseChunk(emitter, visible)) {
            finalAnswer.append(visible);
            return true;
        }
        log.debug("SSE transport could not receive the response segment; execution continues");
        isDisconnect.set(true);
        return false;
    }

    private ReplyGuardOutcome mergeReplyGuardOutcome(
            ReplyGuardOutcome current, ReplyGuardOutcome next) {
        if (current == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE
                || next == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE) {
            return ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE;
        }
        if (current == ReplyGuardOutcome.REPLACED_WITH_AUTHORITATIVE_EVIDENCE
                || next == ReplyGuardOutcome.REPLACED_WITH_AUTHORITATIVE_EVIDENCE) {
            return ReplyGuardOutcome.REPLACED_WITH_AUTHORITATIVE_EVIDENCE;
        }
        return ReplyGuardOutcome.NONE;
    }

    static boolean containsUnsupportedPlatformClaim(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String checkableReply = maskLiteralContent(reply);
        if (hasUnsupportedClaim(checkableReply, UNSUPPORTED_TOOL_EVIDENCE_CLAIM, false, false)) return true;
        if (hasUnsupportedClaim(checkableReply, UNSUPPORTED_PLATFORM_READ_CLAIM, false, true)) return true;
        return hasUnsupportedClaim(checkableReply, UNSUPPORTED_PLATFORM_MUTATION_CLAIM, true, false);
    }

    private boolean containsUnsupportedPlatformClaim(
            String reply,
            List<StreamResponseDto.ProgressDto> executionTrace,
            Set<String> confirmedMutationTools) {
        return unsupportedPlatformClaim(reply, executionTrace, confirmedMutationTools) != null;
    }

    private StreamResponseDto.ClaimReviewDto unsupportedPlatformClaim(
            String reply,
            List<StreamResponseDto.ProgressDto> executionTrace,
            Set<String> confirmedMutationTools) {
        if (reply == null || reply.isBlank()) return null;
        String checkableReply = maskLiteralContent(reply);
        java.util.regex.MatchResult match;
        if (!hasUsableToolEvidence(executionTrace, null)
                && (match = findUnsupportedClaim(checkableReply, UNSUPPORTED_TOOL_EVIDENCE_CLAIM, false, false)) != null) {
            return claimReview(reply, match, StreamResponseDto.ClaimReviewReason.MISSING_TOOL_EVIDENCE);
        }
        if (!hasUsableToolEvidence(executionTrace, Set.of("board_overview"))
                && (match = findUnsupportedClaim(checkableReply, UNSUPPORTED_PLATFORM_READ_CLAIM, false, true)) != null) {
            return claimReview(reply, match, StreamResponseDto.ClaimReviewReason.MISSING_BOARD_SNAPSHOT);
        }
        if ((confirmedMutationTools == null || confirmedMutationTools.stream()
                .noneMatch(AiToolResultContract.mutationCapableTools()::contains))
                && (match = findUnsupportedClaim(checkableReply, UNSUPPORTED_PLATFORM_MUTATION_CLAIM, true, false)) != null) {
            return claimReview(reply, match, StreamResponseDto.ClaimReviewReason.MISSING_OPERATION_RECEIPT);
        }
        return null;
    }

    private StreamResponseDto.ClaimReviewDto claimReview(
            String reply, java.util.regex.MatchResult match, StreamResponseDto.ClaimReviewReason reason) {
        // Offsets survive literal masking. Keep the exact public segment under the same ownership
        // as its chat history; do not put user content or provider reasoning in server logs.
        return new StreamResponseDto.ClaimReviewDto(reason, reply, reply.substring(match.start(), match.end()));
    }

    private String claimReviewReason(StreamResponseDto.ClaimReviewReason reason, boolean preferChinese) {
        return switch (reason) {
            case MISSING_TOOL_EVIDENCE -> preferChinese
                    ? "这句话引用了工具结果，但本轮没有可确认的工具结果，因此隐藏了原句。"
                    : "This sentence cites a tool result, but this turn has no confirmed tool result. The sentence was hidden.";
            case MISSING_BOARD_SNAPSHOT -> preferChinese
                    ? "这句话声称已查看当前画布，但本轮没有成功读取画布的记录，因此隐藏了原句。"
                    : "This sentence claims to have inspected the current Board, but this turn has no successful Board read. The sentence was hidden.";
            case MISSING_OPERATION_RECEIPT -> preferChinese
                    ? "这句话声称已完成一项操作，但本轮没有相应的完成记录，因此隐藏了原句。"
                    : "This sentence claims an operation completed, but this turn has no corresponding completion record. The sentence was hidden.";
        };
    }

    private boolean hasUsableToolEvidence(
            List<StreamResponseDto.ProgressDto> executionTrace, Set<String> allowedTools) {
        return executionTrace != null && executionTrace.stream().anyMatch(progress -> progress != null
                && "TOOL_RESULT".equals(progress.getStage())
                && "USABLE".equals(progress.getOutcome())
                && (allowedTools == null || allowedTools.contains(progress.getToolName())));
    }

    private static String maskLiteralContent(String text) {
        boolean[] literalCharacters = literalCharacterMask(text, false);
        char[] checkable = text.toCharArray();
        for (int index = 0; index < checkable.length; index++) {
            if (literalCharacters[index]) checkable[index] = ' ';
        }
        return new String(checkable);
    }

    private static boolean[] literalCharacterMask(CharSequence text, boolean maskUnclosedLiteral) {
        boolean[] masked = new boolean[text.length()];
        int literalStart = -1;
        char quoteEnd = 0;
        char codeDelimiter = 0;
        int codeDelimiterLength = 0;
        boolean fencedCode = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (codeDelimiterLength > 0) {
                if (current != codeDelimiter) {
                    masked[index] = true;
                    continue;
                }
                int runLength = delimiterRunLength(text, index, codeDelimiter);
                markLiteralRun(masked, index, runLength);
                boolean closes = fencedCode
                        ? runLength >= codeDelimiterLength
                            && isFenceDelimiterLine(text, index, runLength, true)
                        : runLength == codeDelimiterLength;
                if (closes) {
                    literalStart = -1;
                    codeDelimiter = 0;
                    codeDelimiterLength = 0;
                    fencedCode = false;
                }
                index += runLength - 1;
                continue;
            }
            if (quoteEnd != 0) {
                masked[index] = true;
                if (current == quoteEnd && !isEscaped(text, index)
                        && (quoteEnd != '\'' || isAsciiSingleQuoteClosing(text, index))) {
                    literalStart = -1;
                    quoteEnd = 0;
                }
                continue;
            }
            if (current == '`') {
                int runLength = delimiterRunLength(text, index, current);
                markLiteralRun(masked, index, runLength);
                literalStart = index;
                codeDelimiter = current;
                codeDelimiterLength = runLength;
                fencedCode = runLength >= 3 && isFenceDelimiterLine(text, index, runLength, false);
                index += runLength - 1;
                continue;
            }
            if (current == '~') {
                int runLength = delimiterRunLength(text, index, current);
                if (runLength >= 3 && isFenceDelimiterLine(text, index, runLength, false)) {
                    markLiteralRun(masked, index, runLength);
                    literalStart = index;
                    codeDelimiter = current;
                    codeDelimiterLength = runLength;
                    fencedCode = true;
                    index += runLength - 1;
                    continue;
                }
            }
            char matchingQuote = matchingQuote(current);
            if (matchingQuote != 0 && !isEscaped(text, index)
                    && (current != '\'' || isAsciiSingleQuoteOpening(text, index))) {
                masked[index] = true;
                literalStart = index;
                quoteEnd = matchingQuote;
            }
        }
        if (!maskUnclosedLiteral && literalStart >= 0) {
            Arrays.fill(masked, literalStart, masked.length, false);
        }
        return masked;
    }

    private static int delimiterRunLength(CharSequence text, int start, char delimiter) {
        int end = start;
        while (end < text.length() && text.charAt(end) == delimiter) end++;
        return end - start;
    }

    private static boolean isFenceDelimiterLine(
            CharSequence text, int delimiterStart, int delimiterLength, boolean closing) {
        int lineStart = delimiterStart;
        while (lineStart > 0
                && text.charAt(lineStart - 1) != '\n'
                && text.charAt(lineStart - 1) != '\r') {
            lineStart--;
        }
        if (delimiterStart - lineStart > 3) return false;
        for (int index = lineStart; index < delimiterStart; index++) {
            if (text.charAt(index) != ' ') return false;
        }
        if (!closing) return true;
        for (int index = delimiterStart + delimiterLength; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\n' || current == '\r') return true;
            if (!Character.isWhitespace(current)) return false;
        }
        return true;
    }

    private static void markLiteralRun(boolean[] masked, int start, int length) {
        for (int index = start; index < start + length; index++) masked[index] = true;
    }

    private static char matchingQuote(char opening) {
        return switch (opening) {
            case '"' -> '"';
            case '\'' -> '\'';
            case '“' -> '”';
            case '‘' -> '’';
            case '「' -> '」';
            case '『' -> '』';
            case '«' -> '»';
            case '‹' -> '›';
            default -> 0;
        };
    }

    private static boolean isAsciiSingleQuoteOpening(CharSequence text, int index) {
        if (index + 1 >= text.length() || Character.isWhitespace(text.charAt(index + 1))) {
            return false;
        }
        return index == 0 || !isWordCharacter(text.charAt(index - 1));
    }

    private static boolean isAsciiSingleQuoteClosing(CharSequence text, int index) {
        if (index == 0 || Character.isWhitespace(text.charAt(index - 1))) return false;
        return index + 1 >= text.length() || !isWordCharacter(text.charAt(index + 1));
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static boolean isEscaped(CharSequence text, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static boolean hasUnsupportedClaim(
            String reply,
            Pattern pattern,
            boolean allowNonOperationalContent,
            boolean leadingHistoryOverridesCurrentContext) {
        return findUnsupportedClaim(reply, pattern, allowNonOperationalContent, leadingHistoryOverridesCurrentContext) != null;
    }

    private static java.util.regex.MatchResult findUnsupportedClaim(
            String reply,
            Pattern pattern,
            boolean allowNonOperationalContent,
            boolean leadingHistoryOverridesCurrentContext) {
        String checkableReply = reply;
        if (allowNonOperationalContent) {
            char[] checkable = reply.toCharArray();
            Matcher negated = NEGATED_COMPLETION_CLAIM.matcher(reply);
            while (negated.find()) {
                // Mask only the denied completion marker, preserving offsets and nouns that
                // an adjacent affirmative predicate may share without repeating "verification".
                Arrays.fill(checkable, negated.start(1), negated.end(1), ' ');
            }
            checkableReply = new String(checkable);
        }
        Matcher matcher = pattern.matcher(checkableReply);
        while (matcher.find()) {
            String claimClause = claimClause(reply, matcher.start(), matcher.end());
            String trimmedClause = claimClause.trim();
            boolean leadingHistoricalContext = hasLeadingHistoricalClaimContext(reply, matcher.start());
            if (trimmedClause.endsWith("?") || trimmedClause.endsWith("？")
                    || NON_ASSERTIVE_CLAIM_CONTEXT.matcher(claimClause).find()
                    || (leadingHistoryOverridesCurrentContext && leadingHistoricalContext)
                    || ((HISTORICAL_CLAIM_CONTEXT.matcher(claimClause).find()
                            || leadingHistoricalContext)
                        && !CURRENT_CLAIM_CONTEXT.matcher(claimClause).find())) continue;
            if (allowNonOperationalContent
                    && NON_OPERATIONAL_PLATFORM_CONTENT.matcher(matcher.group()).find()) continue;
            if (allowNonOperationalContent) {
                // Empty/completed records and completed analysis describe information or work,
                // not a platform action. A later actual operation must still be checked.
                Matcher noun = NON_OPERATIONAL_CLAIM_SPAN.matcher(reply);
                boolean nounModifier = false;
                while (noun.find()) {
                    if (noun.start() < matcher.end() && noun.end() >= matcher.end()) {
                        // "Completed analysis and deleted devices" shares its completed subject.
                        // Check that continuation with the same operation pattern before exempting the analysis.
                        Matcher coordinated = COORDINATED_CLAIM_SUFFIX.matcher(checkableReply.substring(noun.end()));
                        if (coordinated.find() && (hasUnsupportedClaim("我已经" + coordinated.group(1), pattern, false, false)
                                || hasUnsupportedClaim("I " + coordinated.group(1), pattern, false, false))) continue;
                        nounModifier = true;
                        break;
                    }
                }
                if (nounModifier) continue;
            }
            return matcher.toMatchResult();
        }
        return null;
    }

    private static boolean hasLeadingHistoricalClaimContext(String reply, int claimStart) {
        int sentenceStart = 0;
        for (int index = Math.min(claimStart, reply.length()) - 1; index >= 0; index--) {
            if (".!?。！？".indexOf(reply.charAt(index)) >= 0) {
                sentenceStart = index + 1;
                break;
            }
        }
        return LEADING_HISTORICAL_CLAIM_CONTEXT
                .matcher(reply.substring(sentenceStart, Math.max(sentenceStart, claimStart)))
                .matches();
    }

    private static String claimClause(String reply, int claimStart, int claimEnd) {
        int clauseStart = 0;
        int clauseEnd = reply.length();
        Matcher boundary = CLAIM_CLAUSE_BOUNDARY.matcher(reply);
        while (boundary.find()) {
            if (boundary.end() <= claimStart) {
                clauseStart = boundary.end();
            } else if (boundary.start() >= claimEnd) {
                clauseEnd = boundary.start();
                break;
            }
        }
        return reply.substring(clauseStart, clauseEnd);
    }

    private GuardedClaimReplacement authoritativeClaimReplacement(
            boolean preferChinese, List<StreamResponseDto.ProgressDto> executionTrace,
            StreamResponseDto.ClaimReviewReason reasonCode) {
        String reason = claimReviewReason(reasonCode, preferChinese);
        List<String> authoritativeDetails = executionTrace == null ? List.of() : executionTrace.stream()
                .filter(progress -> progress != null
                        && "TOOL_RESULT".equals(progress.getStage())
                        && ("USABLE".equals(progress.getOutcome())
                            || "CONFIRMATION_REQUIRED".equals(progress.getOutcome()))
                        && progress.getDetail() != null
                        && !progress.getDetail().isBlank())
                .map(StreamResponseDto.ProgressDto::getDetail)
                .distinct()
                .toList();
        if (authoritativeDetails.size() > 4) {
            authoritativeDetails = authoritativeDetails.subList(authoritativeDetails.size() - 4, authoritativeDetails.size());
        }
        if (!authoritativeDetails.isEmpty()) {
            return new GuardedClaimReplacement(preferChinese
                    ? "这项说法尚未核实。" + reason + "本轮可确认的信息是："
                        + String.join("；", authoritativeDetails)
                    : "That claim has not been verified. " + reason + " This turn confirms: "
                        + String.join("; ", authoritativeDetails),
                    ReplyGuardOutcome.REPLACED_WITH_AUTHORITATIVE_EVIDENCE);
        }
        return new GuardedClaimReplacement(preferChinese
                ? "系统已隐藏一段未核实的回复。" + reason + "可展开“回答核对”查看原句；请核对实际执行记录，再决定是否重试，不要假定数据已经改变。"
                : "The system hid an unverified reply. " + reason + " Expand Reply check to inspect the original. Check the execution record before retrying, and "
                    + "do not assume data changed until an explicit successful record is shown.",
                ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE);
    }

    private ToolExecutionOutcome classifyToolExecution(String functionName, String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return ToolExecutionOutcome.FAILED;
        }
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            if (!root.isObject() || root.isEmpty() || !hasValidToolResultControlFields(root)) {
                return ToolExecutionOutcome.FAILED;
            }
            if (root.path("requiresUserConfirmation").asBoolean(false)) {
                return ToolExecutionOutcome.FAILED;
            }
            if ((root.has("resultAvailable") && !root.path("resultAvailable").asBoolean(true))
                    || "RESULT_UNAVAILABLE".equals(root.path("resultStatus").asText())) {
                return ToolExecutionOutcome.RESULT_UNAVAILABLE;
            }
            String error = root.path("error").asText("");
            String errorCode = root.path("errorCode").asText("");
            if (!error.isBlank() || !errorCode.isBlank()
                    || (root.has("status") && root.path("status").intValue() >= 400)) {
                return ToolExecutionOutcome.FAILED;
            }
            if (!toolProgressPresenter.hasValidKnownToolPayload(functionName, root)) {
                return ToolExecutionOutcome.FAILED;
            }
            if ("PARTIAL".equals(root.path("objectiveStatus").asText())) {
                return ToolExecutionOutcome.PARTIAL;
            }
            boolean hasSemanticPayload = root.properties().stream()
                    .anyMatch(entry -> !TOOL_RESULT_CONTROL_FIELDS.contains(entry.getKey()));
            return hasSemanticPayload ? ToolExecutionOutcome.USABLE : ToolExecutionOutcome.FAILED;
        } catch (Exception ignore) {
            return ToolExecutionOutcome.FAILED;
        }
    }

    private boolean isAwaitingAsyncTask(String functionName, String toolResult,
                                       ToolExecutionOutcome outcome) {
        if (outcome != ToolExecutionOutcome.USABLE
                || !("verify_task_status".equals(functionName)
                || "simulate_task_status".equals(functionName)
                || "fuzz_task_status".equals(functionName))) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            JsonNode taskId = root.path("taskId");
            JsonNode task = root.path("task");
            JsonNode id = task.path("id");
            JsonNode status = task.path("status");
            return taskId.isIntegralNumber() && taskId.canConvertToLong() && taskId.longValue() > 0
                    && id.isIntegralNumber() && id.canConvertToLong() && id.longValue() == taskId.longValue()
                    && status.isTextual()
                    && ("PENDING".equals(status.textValue()) || "RUNNING".equals(status.textValue()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    private boolean hasArray(JsonNode root, String field) {
        return root != null && root.has(field) && root.get(field).isArray();
    }

    private boolean hasValidToolResultControlFields(JsonNode root) {
        return AiToolResultContract.hasValidControlFields(root);
    }

    /**
     * Whether a tool's write may already be in the database.
     *
     * <p>Fails toward "may have committed" on unreadable output. This is the only input to the
     * uncertain-mutation count and to the notice that tells the model which steps reported no write,
     * so a parse failure must not be reported as "nothing was written": a tool that did commit and
     * then produced malformed or truncated JSON would otherwise be summarised as a no-op, and the
     * model would retry it against state it never inspected.
     */
    private boolean mutationMayHaveCommitted(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            if (root == null || !root.isObject()) {
                log.warn("Tool result was not a JSON object; treating the mutation as possibly committed");
                return true;
            }
            JsonNode value = root.get("mutationMayHaveCommitted");
            return value != null && value.isBoolean() && value.booleanValue();
        } catch (Exception e) {
            log.warn("Could not read a tool result's mutation outcome; treating it as possibly committed", e);
            return true;
        }
    }

    private boolean sendFrontendCommands(SseEmitter emitter, Set<StreamResponseDto.CommandDto> commandSet) {
        List<StreamResponseDto.CommandDto> orderedCommands = commandSet.stream()
                .sorted(java.util.Comparator.comparing(this::carriesAssistantAction))
                .toList();
        for (StreamResponseDto.CommandDto cmd : orderedCommands) {
            try {
                StreamResponseDto packet = StreamResponseDto.command(cmd);
                emitter.send(SseEmitter.event().data(packet, MediaType.APPLICATION_JSON));
                log.info("Sent frontend command: type={}, payload={}", cmd.getType(), cmd.getPayload());
            } catch (IOException | IllegalStateException e) {
                log.warn("Failed to send command", e);
                return false;
            }
        }
        return true;
    }

    private boolean carriesAssistantAction(StreamResponseDto.CommandDto command) {
        return command != null && command.getPayload() != null
                && command.getPayload().containsKey("assistantAction");
    }

    private boolean sendSseTerminal(
            SseEmitter emitter, String turnId, ChatExecutionStatus executionStatus) {
        try {
            emitter.send(SseEmitter.event().data(
                    StreamResponseDto.terminal(turnId, executionStatus),
                    MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send chat terminal frame for turn {}: {}", turnId, e.toString());
            return false;
        }
    }

    private boolean sendSseProgress(SseEmitter emitter, String stage, String toolName, Integer round) {
        return sendSseProgress(emitter, stage, toolName, round, null, null);
    }

    private boolean sendSseProgress(SseEmitter emitter,
                                    String stage,
                                    String toolName,
                                    Integer round,
                                    String outcome,
                                    ToolLoopResult result) {
        return sendSseProgress(emitter, stage, toolName, round, outcome, result, null);
    }

    private boolean sendSseProgress(SseEmitter emitter,
                                    String stage,
                                    String toolName,
                                    Integer round,
                                    String outcome,
                                    ToolLoopResult result,
                                    String detail) {
        return sendSseProgress(emitter, stage, toolName, round, outcome, result, detail, null);
    }

    private boolean sendSseProgress(SseEmitter emitter,
                                    String stage,
                                    String toolName,
                                    Integer round,
                                    String outcome,
                                    ToolLoopResult result,
                                    String detail,
                                    List<StreamResponseDto.ProgressDto> executionTrace) {
        StreamResponseDto packet = StreamResponseDto.progress(
                stage,
                toolName,
                round,
                outcome,
                result == null ? null : result.successfulToolCalls(),
                result == null ? null : result.failedToolCalls(),
                result == null ? null : result.resultUnavailableToolCalls()
                        + (result.confirmationPending() ? 1 : 0),
                detail
        );
        return sendSseProgress(emitter, packet, executionTrace);
    }

    private boolean sendSseProgress(SseEmitter emitter, StreamResponseDto packet,
                                    List<StreamResponseDto.ProgressDto> executionTrace) {
        if (executionTrace != null && packet.getProgress() != null) {
            executionTrace.add(packet.getProgress());
        }
        try {
            emitter.send(SseEmitter.event()
                    .data(packet, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send chat progress stage {}: {}", packet.getProgress().getStage(), e.toString());
            // SSE is only the live view. The session execution and its terminal audit remain
            // authoritative, so a detached browser must not cancel background work.
            return true;
        }
    }


    private void collectRefreshCommands(
            List<String> targets,
            String assistantAction,
            String assistantSummary,
            Set<StreamResponseDto.CommandDto> commandSet) {
        for (int index = 0; index < targets.size(); index++) {
            commandSet.add(index == 0
                    ? refreshCommand(targets.get(index), assistantAction, assistantSummary)
                    : refreshCommand(targets.get(index)));
        }
    }

    private StreamResponseDto.CommandDto refreshCommand(String target) {
        return new StreamResponseDto.CommandDto("REFRESH_DATA", Map.of("target", target));
    }

    private StreamResponseDto.CommandDto refreshCommand(
            String target, String assistantAction, String assistantSummary) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target);
        if (assistantAction != null && !assistantAction.isBlank()) {
            payload.put("assistantAction", assistantAction);
        }
        if (assistantSummary != null && !assistantSummary.isBlank()) {
            payload.put("assistantSummary", assistantSummary);
        }
        return new StreamResponseDto.CommandDto("REFRESH_DATA", payload);
    }

    private String toolExecutionNotice(ToolLoopResult result, boolean preferChinese) {
        if (result == null) {
            return "";
        }
        if (!result.hadToolCalls()) {
            /*
             * No prose for this case: the execution-trace header already carries a `No platform tools ran` badge,
             * driven by the same `hadToolCalls` signal and rendered through the client's own i18n. A sentence here
             * was the second owner of one fact — it restated the badge at greater length, above the answer, and in
             * a language this method can only infer, so a Chinese interface asked "hi" showed the Chinese badge
             * beside English prose saying the same thing.
             *
             * The badge is the half worth keeping: it follows the UI language, holds a fixed position, and does
             * not push the reply down the bubble.
             */
            return "";
        }
        List<String> notices = new ArrayList<>();
        if (result.stopReason() == ToolLoopStopReason.PARTIAL_RESULT) {
            notices.add(preferChinese
                    ? "工具返回了可审阅但不完整的场景草案；缺少的核心场景部分未被视为已完成。"
                    : "The tool returned a reviewable but incomplete scene draft; missing core scene parts were not treated as completed.");
        }
        if (result.failedToolCalls() > 0) {
            notices.add(preferChinese
                    ? "工具执行状态：" + result.successfulToolCalls() + " 个步骤返回了可用结果，"
                        + result.failedToolCalls() + " 个步骤失败。失败步骤未被视为已完成。"
                    : "Tool status: " + result.successfulToolCalls() + " step(s) returned a usable result; "
                        + result.failedToolCalls() + " step(s) failed. Failed steps were not treated as completed.");
        }
        if (result.resultUnavailableToolCalls() > 0) {
            String mutationNotice;
            if (preferChinese) {
                mutationNotice = result.uncertainMutationCalls() > 0
                        ? "其中 " + result.uncertainMutationCalls()
                            + " 个步骤可能已经修改了已保存状态；系统已请求刷新受影响数据。重试前请检查当前状态。"
                        : "这些未返回明细的步骤没有报告任何写入。";
                notices.add("工具执行状态：" + result.resultUnavailableToolCalls()
                        + " 个步骤没有返回可用的结果明细，因此未计为成功。" + mutationNotice);
            } else {
                mutationNotice = result.uncertainMutationCalls() > 0
                        ? " " + result.uncertainMutationCalls() + " step(s) may already have changed saved state; affected data was requested to refresh. Inspect current state before retrying."
                        : " No mutation was reported for those unavailable results.";
                notices.add("Tool status: " + result.resultUnavailableToolCalls()
                        + " step(s) returned no usable result details and were not counted as successful."
                        + mutationNotice);
            }
        }
        if (result.confirmationPending()) {
            notices.add(preferChinese
                    ? "提示：当前只是未写入的影响预览或备选方案，尚未应用；请明确确认后再继续。"
                    : "A no-write preview or proposed alternative still requires explicit confirmation. "
                        + "The pending action was not applied.");
            if ("apply_scenario".equals(result.pendingToolName())) {
                String assumptions = toolProgressPresenter.scenarioEnvironmentAssumptions(
                        result.pendingToolResult(), preferChinese);
                if (assumptions != null) notices.add(assumptions);
            }
        }
        return notices.isEmpty() ? "" : String.join(" ", notices) + "\n\n";
    }

    private String executionGuardNotice(ToolLoopResult result, boolean preferChinese) {
        if (result == null || (!result.stoppedForNoProgress() && !result.reachedEmergencyLimit())) {
            return "";
        }
        if (result.stoppedForNoProgress()) {
            return preferChinese
                    ? "检测到连续多轮完全相同的工具调用和结果，已停止重复执行；正在整理已完成、失败和未完成的步骤。\n\n"
                    : "Consecutive rounds repeated the exact same tool calls and results, so duplicate execution stopped. "
                        + "The assistant is now summarizing completed, failed, and unfinished steps.\n\n";
        }
        return preferChinese
                ? "任务触发了异常循环安全保护；此前步骤不会回滚，助手正在整理已完成、失败和未完成的内容。\n\n"
                : "The task reached the emergency runaway guard. Earlier steps were not rolled back; the assistant "
                    + "is now summarizing completed, failed, and unfinished work.\n\n";
    }

    private String missingFinalResponseFallback(ToolLoopResult result, boolean preferChinese) {
        if (result != null && result.hadToolCalls()) {
            if (preferChinese) {
                return "工具处理已结束，但未生成最终 AI 说明。期间获得 "
                        + result.successfulToolCalls() + " 个可用结果、"
                        + result.failedToolCalls() + " 个失败步骤和 "
                        + result.resultUnavailableToolCalls() + " 个未确认结果。"
                        + "不要假定整个请求已经完成；请检查刷新后的当前状态及上方的确认提示。";
            }
            return "Tool processing ended without a final AI explanation. It produced "
                    + result.successfulToolCalls() + " usable result(s) and "
                    + result.failedToolCalls() + " failed step(s), with "
                    + result.resultUnavailableToolCalls() + " unconfirmed result(s). Do not assume the whole request completed; "
                    + "review the refreshed current state and any confirmation notice above.";
        }
        return preferChinese
                ? "暂时无法生成回复，请重试。"
                : "I could not generate a response. Please try again.";
    }

    private void saveUserTurn(String sid, String turnId, String executionId, String content) {
        ChatMessagePo po = new ChatMessagePo();
        po.setSessionId(sid);
        po.setRole("user");
        po.setContent(content);
        po.setTurnId(turnId);
        po.setExecutionId(executionId);
        messageRepo.saveAndFlush(po);
    }

    private void saveAssistantMsg(String sid,
                                  String turnId,
                                  String content,
                                  List<StreamResponseDto.ProgressDto> executionTrace,
                                  Integer elapsedSeconds,
                                  ChatExecutionStatus executionStatus) {
        ChatMessagePo po = new ChatMessagePo();
        po.setSessionId(sid);
        po.setRole("assistant");
        po.setContent(content);
        po.setTurnId(turnId);
        po.setExecutionId(UserContextHolder.getChatExecutionId());
        po.setExecutionElapsedSeconds(elapsedSeconds);
        po.setExecutionStatus(executionStatus);
        if (executionStatus == ChatExecutionStatus.COMPLETED
                && !hasCompletedToolEvidence(executionTrace)) {
            throw new IllegalStateException(
                    "A completed chat terminal record requires persisted successful tool evidence");
        }
        if (executionTrace != null && !executionTrace.isEmpty()) {
            try {
                po.setExecutionTraceJson(objectMapper.writeValueAsString(executionTrace));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not serialize the chat execution trace for terminal persistence", e);
            }
        }
        messageRepo.saveAndFlush(po);
    }

    private TerminalPersistenceResult persistAssistantTerminal(
            Long userId,
            String sessionId,
            String executionId,
            String turnId,
            String content,
            List<StreamResponseDto.ProgressDto> executionTrace,
            Integer elapsedSeconds,
            ChatExecutionStatus executionStatus,
            String userStoppedContent) {
        AtomicReference<ChatExecutionStatus> persistedStatus = new AtomicReference<>();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ChatSessionPo session = requireOwnedSessionForWrite(userId, sessionId, executionId);
                boolean explicitlyStopped = Boolean.TRUE.equals(session.getExecutionStopRequested())
                        && Boolean.TRUE.equals(session.getExecutionUserStopRequested());
                ChatExecutionStatus authoritativeStatus = explicitlyStopped
                        ? ChatExecutionStatus.STOPPED
                        : executionStatus;
                String authoritativeContent = explicitlyStopped ? userStoppedContent : content;
                saveAssistantMsg(
                        sessionId,
                        turnId,
                        authoritativeContent,
                        executionTrace,
                        elapsedSeconds,
                        authoritativeStatus);
                persistedStatus.set(authoritativeStatus);
            });
            if (persistedStatus.get() == null) {
                throw new IllegalStateException("Terminal transaction completed without a status.");
            }
            return new TerminalPersistenceResult(true, persistedStatus.get());
        } catch (RuntimeException e) {
            log.warn("Could not persist {} chat terminal record for session {}: {}",
                    executionStatus, sessionId, e.toString());
            return new TerminalPersistenceResult(false, executionStatus);
        }
    }

    private int elapsedSeconds(long executionStartedNanos) {
        return (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(0L, (System.nanoTime() - executionStartedNanos) / 1_000_000_000L));
    }

    private String terminalPersistenceFailureMessage(boolean preferChinese) {
        return preferChinese
                ? "最终回复未能写入会话历史，本轮记录不完整。请先刷新会话历史和当前画布状态，再决定是否重试。"
                : "The final response could not be saved to conversation history, so this turn is incomplete. "
                    + "Refresh the conversation history and current Board state before deciding whether to retry.";
    }

    private String terminalPersistenceFailureSuffix(boolean preferChinese) {
        return preferChinese
                ? " 最终错误记录也未能写入会话历史；请刷新会话历史和当前画布状态后再重试。"
                : " The terminal error record also could not be saved to conversation history; "
                    + "refresh the conversation history and current Board state before retrying.";
    }

    private ChatExecutionStatus terminalStatus(
            ToolLoopResult result, ReplyGuardOutcome replyGuardOutcome) {
        if (result == null) return ChatExecutionStatus.FAILED;
        if (result.confirmationPending()) return ChatExecutionStatus.AWAITING_CONFIRMATION;
        if (result.resultUnavailableToolCalls() > 0) return ChatExecutionStatus.PARTIAL;
        if (result.failedToolCalls() > 0) {
            return result.successfulToolCalls() > 0
                    ? ChatExecutionStatus.PARTIAL
                    : ChatExecutionStatus.FAILED;
        }
        if (result.stopReason() == ToolLoopStopReason.NO_PROGRESS
                || result.stopReason() == ToolLoopStopReason.EMERGENCY_LIMIT) {
            return result.hadToolCalls() ? ChatExecutionStatus.PARTIAL : ChatExecutionStatus.FAILED;
        }
        if (result.stopReason() == ToolLoopStopReason.PROVIDER_UNAVAILABLE) {
            return result.successfulToolCalls() > 0
                    ? ChatExecutionStatus.PARTIAL
                    : ChatExecutionStatus.FAILED;
        }
        if (result.stopReason() == ToolLoopStopReason.PARTIAL_RESULT) {
            return ChatExecutionStatus.PARTIAL;
        }
        ChatExecutionStatus status = result.hadToolCalls()
                ? ChatExecutionStatus.COMPLETED
                : ChatExecutionStatus.PARTIAL;
        return replyGuardOutcome == ReplyGuardOutcome.HIDDEN_WITHOUT_EVIDENCE
                && status == ChatExecutionStatus.COMPLETED
                ? ChatExecutionStatus.PARTIAL : status;
    }

    private String interruptedAuditNotice(ToolLoopResult result, boolean preferChinese, boolean userStopped) {
        String opening;
        if (preferChinese) {
            opening = userStopped ? "用户已停止本次 AI 回复。" : "连接在任务完成前中断。";
        } else {
            opening = userStopped ? "The user stopped this AI response." : "The connection ended before the task completed.";
        }
        if (result != null && result.hadToolCalls()) {
            String priorStepPrefix = userStopped
                    ? (preferChinese ? "停止前有 " : " Before stopping, ")
                    : (preferChinese ? "中断前有 " : " Before the disconnect, ");
            return preferChinese
                    ? opening + priorStepPrefix + result.successfulToolCalls()
                        + " 个步骤返回可用结果、" + result.failedToolCalls() + " 个步骤失败、"
                        + result.resultUnavailableToolCalls()
                        + " 个步骤结果未确认。已开始的写入不会因停止或断线回滚，请以刷新后的画布和运行历史为准。"
                    : opening + priorStepPrefix
                        + result.successfulToolCalls() + " step(s) returned usable results, "
                        + result.failedToolCalls() + " failed, and "
                        + result.resultUnavailableToolCalls()
                        + " had unconfirmed results. Started writes are not rolled back by a stop or disconnect; "
                        + "rely on the refreshed Board and run history.";
        }
        return preferChinese
                ? opening + "未记录到已完成的工具步骤。需要时可以重新发起请求。"
                : opening + " No completed tool step was recorded. Start the request again if it is still needed.";
    }

    private boolean isUntitledSessionTitle(String title) {
        return title == null || title.isBlank() || "New Chat".equals(title)
                || title.matches("Chat \\d+");
    }

    private void saveAiToolCallRequest(String sid, List<LlmToolCall> toolCalls) {
        ChatMessagePo po = new ChatMessagePo();
        po.setSessionId(sid);
        po.setRole("assistant");
        po.setContent(messageCodec.serializeToolCalls(toolCalls));
        messageRepo.saveAndFlush(po);
    }

    private LlmMessage saveToolExecutionResult(Long userId, String sid, String executionId,
                                              String toolCallId, String result) {
        ChatMessagePo po = new ChatMessagePo();
        po.setSessionId(sid);
        po.setRole("tool");
        po.setContent(messageCodec.serializeToolResult(toolCallId, result));
        executeOwnedSessionWrite(userId, sid, executionId, () -> messageRepo.saveAndFlush(po));
        return messageCodec.toMessage(po);
    }

    private void executeOwnedSessionWrite(Long userId,
                                          String sessionId,
                                          String executionId,
                                          Runnable write) {
        transactionTemplate.executeWithoutResult(status -> {
            requireOwnedSessionForWrite(userId, sessionId, executionId);
            write.run();
        });
    }

    private ChatSessionPo requireOwnedSessionForWrite(
            Long userId, String sessionId, String executionId) {
        requireActiveUserForWrite(userId);
        String requiredSessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (executionId == null || executionId.isBlank()) {
            return sessionRepo.findByIdAndUserId(requiredSessionId, userId)
                    .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
        }
        ChatSessionPo session = sessionRepo.findByIdAndUserIdForUpdate(requiredSessionId, userId)
                .orElseThrow(() -> ResourceNotFoundException.session(sessionId));
        LocalDateTime now = databaseNow();
        if (!Objects.equals(executionId, session.getActiveExecutionId())
                || session.getActiveExecutionExpiresAt() == null
                || !session.getActiveExecutionExpiresAt().isAfter(now)) {
            throw new ConflictException(
                    "The assistant execution no longer owns this session. No new message was committed.");
        }
        return session;
    }

    private void requireActiveUserForWrite(Long userId) {
        if (userId == null || userRepository.findByIdForUpdate(userId).isEmpty()) {
            throw UnauthorizedException.invalidToken();
        }
    }

    private long requiredMessageCapacityForTurn() {
        long perRound = 1L + chatExecutionConfig.getMaxToolCallsPerRound();
        try {
            return Math.addExact(2L, Math.multiplyExact(
                    perRound, (long) chatExecutionConfig.getMaxToolRounds()));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private record ActiveStreamRequest(
            Long userId,
            String requestId,
            String turnId,
            String content,
            AtomicBoolean stopRequested,
            AtomicBoolean userStopRequested,
            AtomicReference<SseEmitter> emitter,
            AtomicLong nextControlPollNanos,
            LeaseConfirmation leaseConfirmation,
            LlmRequestControl requestControl) {

        private ActiveStreamRequest(
                Long userId,
                String requestId,
                String turnId,
                String content,
                long confirmationStartedNanos) {
            this(userId, requestId, turnId, content,
                    new AtomicBoolean(false), new AtomicBoolean(false),
                    new AtomicReference<>(),
                    new AtomicLong(System.nanoTime() + EXECUTION_CONTROL_POLL_NANOS),
                    new LeaseConfirmation(confirmationStartedNanos),
                    new LlmRequestControl());
        }

        private boolean matches(String candidateTurnId, String candidateContent) {
            return Objects.equals(turnId, candidateTurnId)
                    && Objects.equals(content, candidateContent);
        }
    }

    private record ExecutionLeaseState(
            String requestId,
            boolean stopRequested,
            boolean userStopRequested) {
    }

    private record ExecutionLeaseRenewalBatch(
            Map<String, ExecutionLeaseState> renewedLeases,
            long confirmationStartedNanos) {

        private static ExecutionLeaseRenewalBatch none() {
            return new ExecutionLeaseRenewalBatch(Map.of(), Long.MIN_VALUE);
        }
    }

    private List<ChatMessagePo> getSmartHistory(String sessionId, int limitCharCount) {
        List<ChatMessagePo> allMessages = new ArrayList<>(messageRepo.findTop80BySessionIdOrderByIdDesc(sessionId));
        java.util.Collections.reverse(allMessages);
        Deque<ChatMessagePo> safeHistory = new ArrayDeque<>();
        int currentLength = 0;

        for (int i = allMessages.size() - 1; i >= 0; i--) {
            ChatMessagePo current = allMessages.get(i);
            if (current == null) {
                continue;
            }

            if (isToolMessage(current)) {
                int assistantIndex = i;
                while (assistantIndex >= 0 && isToolMessage(allMessages.get(assistantIndex))) {
                    assistantIndex--;
                }
                int firstToolIndex = assistantIndex + 1;
                if (assistantIndex >= 0 && isAssistantToolCallMessage(allMessages.get(assistantIndex))) {
                    if (!isCompleteToolCallBlock(allMessages, assistantIndex, i)) {
                        log.warn("Dropping incomplete persisted tool-call block from chat history: "
                                + "sessionId={}, assistantMessageId={}",
                                sessionId, allMessages.get(assistantIndex).getId());
                        i = assistantIndex;
                        continue;
                    }
                    int blockLength = 0;
                    for (int j = assistantIndex; j <= i; j++) {
                        blockLength += messageLength(allMessages.get(j));
                    }
                    if (currentLength + blockLength > limitCharCount) {
                        // Always keep the newest coherent tool-call block,
                        // otherwise the next completion may miss required tool context.
                        if (safeHistory.isEmpty()) {
                            for (int j = i; j >= assistantIndex; j--) {
                                safeHistory.addFirst(allMessages.get(j));
                            }
                        }
                        break;
                    }
                    for (int j = i; j >= assistantIndex; j--) {
                        safeHistory.addFirst(allMessages.get(j));
                    }
                    currentLength += blockLength;
                    i = assistantIndex;
                    continue;
                }
                // Skip isolated tool blocks when corresponding assistant tool_call message is out of window.
                i = firstToolIndex;
                continue;
            }

            if (isAssistantToolCallMessage(current)) {
                log.warn("Dropping persisted assistant tool-call message without adjacent results: "
                        + "sessionId={}, assistantMessageId={}", sessionId, current.getId());
                continue;
            }

            int msgLen = messageLength(current);
            if (currentLength + msgLen > limitCharCount) {
                // Ensure the latest user/assistant message is never dropped from context.
                if (safeHistory.isEmpty()) {
                    safeHistory.addFirst(current);
                }
                break;
            }
            safeHistory.addFirst(current);
            currentLength += msgLen;
        }

        return new ArrayList<>(safeHistory);
    }

    private boolean isCompleteToolCallBlock(List<ChatMessagePo> messages,
                                            int assistantIndex,
                                            int lastToolIndex) {
        try {
            LlmMessage assistant = messageCodec.toMessage(messages.get(assistantIndex));
            List<LlmToolCall> calls = assistant.toolCalls();
            Set<String> expectedIds = calls.stream()
                    .map(LlmToolCall::id)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (expectedIds.size() != calls.size()) {
                return false;
            }

            Set<String> actualIds = new LinkedHashSet<>();
            for (int index = assistantIndex + 1; index <= lastToolIndex; index++) {
                LlmMessage toolMessage = messageCodec.toMessage(messages.get(index));
                if (toolMessage.toolCallId() == null || !actualIds.add(toolMessage.toolCallId())) {
                    return false;
                }
            }
            return actualIds.equals(expectedIds);
        } catch (Exception e) {
            log.warn("Ignoring malformed persisted tool-call block: {}", e.toString());
            return false;
        }
    }

    private boolean isToolMessage(ChatMessagePo msg) {
        return messageCodec.isToolResultContent(msg);
    }

    private boolean isAssistantToolCallMessage(ChatMessagePo msg) {
        return messageCodec.isAssistantToolCallContent(msg);
    }

    private boolean isFrontendVisibleMessage(ChatMessagePo msg) {
        if (msg == null) {
            return false;
        }
        if (isToolMessage(msg) || isAssistantToolCallMessage(msg)) {
            return false;
        }
        return true;
    }

    private List<ChatMessagePo> filterFrontendVisibleMessages(List<ChatMessagePo> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ChatMessagePo> visible = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessagePo msg = messages.get(i);
            if (!isFrontendVisibleMessage(msg)) {
                continue;
            }
            if (isAssistantToolPlaceholderAdjacentToTool(messages, i)) {
                continue;
            }
            visible.add(msg);
        }
        return visible;
    }

    private void attachPersistedExecutionTraces(List<ChatMessagePo> visibleMessages,
                                                 List<ChatMessageResponseDto> response) {
        if (visibleMessages == null || response == null
                || visibleMessages.size() != response.size()) {
            return;
        }

        for (int visibleIndex = 0; visibleIndex < visibleMessages.size(); visibleIndex++) {
            ChatMessagePo message = visibleMessages.get(visibleIndex);
            ChatMessageResponseDto dto = response.get(visibleIndex);
            if ("assistant".equalsIgnoreCase(message.getRole()) && dto != null) {
                List<StreamResponseDto.ProgressDto> persistedTrace = readPersistedExecutionTrace(message);
                if (persistedTrace != null) {
                    dto.setExecutionTrace(persistedTrace);
                    dto.setExecutionElapsedSeconds(message.getExecutionElapsedSeconds());
                }
                if (message.getExecutionStatus() == ChatExecutionStatus.COMPLETED
                        && !hasCompletedToolEvidence(persistedTrace)) {
                    dto.setExecutionStatus(null);
                    log.warn("Cleared unproven COMPLETED status for chat message {} because its persisted "
                            + "execution trace is missing, malformed, unpaired, or has no successful tool result",
                            message.getId());
                }
            }
        }
    }

    private List<StreamResponseDto.ProgressDto> readPersistedExecutionTrace(ChatMessagePo message) {
        String traceJson = message.getExecutionTraceJson();
        if (traceJson == null || traceJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(traceJson);
            if (!root.isArray() || root.isEmpty()) {
                throw new IllegalStateException("execution trace shape is invalid");
            }
            for (JsonNode progress : root) {
                if (!isValidPersistedProgress(progress)) {
                    throw new IllegalStateException("execution trace shape is invalid");
                }
            }
            List<StreamResponseDto.ProgressDto> trace = objectMapper.convertValue(
                    root, new TypeReference<List<StreamResponseDto.ProgressDto>>() { });
            return List.copyOf(trace);
        } catch (Exception e) {
            log.warn("Could not read persisted execution trace for message {}: {}",
                    message.getId(), e.toString());
            return null;
        }
    }

    private boolean isValidPersistedProgress(JsonNode progress) {
        if (progress == null || !progress.isObject()
                || progress.properties().stream()
                    .anyMatch(entry -> !PERSISTED_PROGRESS_FIELDS.contains(entry.getKey()))) {
            return false;
        }
        JsonNode stage = progress.get("stage");
        if (stage == null || !stage.isTextual() || !PERSISTED_PROGRESS_STAGES.contains(stage.textValue())
                || !isOptionalProgressText(progress, "toolName")
                || !isOptionalProgressText(progress, "detail")
                || !isOptionalProgressCounter(progress, "round")
                || !isOptionalProgressCounter(progress, "successfulSteps")
                || !isOptionalProgressCounter(progress, "failedSteps")
                || !isOptionalProgressCounter(progress, "unconfirmedSteps")) {
            return false;
        }
        JsonNode outcome = progress.get("outcome");
        if ("CLAIM_REVIEW".equals(stage.textValue())) {
            JsonNode review = progress.get("claimReview");
            return outcome != null && outcome.isTextual() && Set.of("HIDDEN", "REPLACED").contains(outcome.textValue())
                    && (progress.get("toolName") == null || progress.get("toolName").isNull())
                    && review != null && review.isObject() && review.size() == 3
                    && review.path("originalText").isTextual() && !review.path("originalText").textValue().isBlank()
                    && review.path("matchedText").isTextual() && !review.path("matchedText").textValue().isBlank()
                    && review.path("originalText").textValue().contains(review.path("matchedText").textValue())
                    && review.path("reasonCode").isTextual()
                    && java.util.Arrays.stream(StreamResponseDto.ClaimReviewReason.values())
                        .anyMatch(reason -> reason.name().equals(review.path("reasonCode").textValue()));
        }
        if (progress.hasNonNull("claimReview")) return false;
        if ("TOOL_RESULT".equals(stage.textValue())) {
            return outcome != null && outcome.isTextual()
                    && TOOL_RESULT_OUTCOMES.contains(outcome.textValue());
        }
        if ("EXECUTION_GUARD".equals(stage.textValue())) {
            return outcome != null && outcome.isTextual()
                    && EXECUTION_GUARD_OUTCOMES.contains(outcome.textValue());
        }
        return outcome == null || outcome.isNull();
    }

    private boolean isOptionalProgressText(JsonNode progress, String field) {
        JsonNode value = progress.get(field);
        return value == null || value.isNull() || value.isTextual();
    }

    private boolean isOptionalProgressCounter(JsonNode progress, String field) {
        JsonNode value = progress.get(field);
        return value == null || value.isNull()
                || (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0);
    }

    private boolean hasCompletedToolEvidence(List<StreamResponseDto.ProgressDto> trace) {
        if (trace == null || trace.isEmpty()) {
            return false;
        }
        boolean hasUsableResult = false;
        StreamResponseDto.ProgressDto pendingExecution = null;
        Set<String> unresolvedPartialTools = new LinkedHashSet<>();
        for (StreamResponseDto.ProgressDto progress : trace) {
            if (progress == null) {
                return false;
            }
            if ("EXECUTION_GUARD".equals(progress.getStage())
                    || ("CLAIM_REVIEW".equals(progress.getStage()) && "HIDDEN".equals(progress.getOutcome()))) {
                return false;
            }
            if ("TOOL_EXECUTION".equals(progress.getStage())) {
                if (pendingExecution != null
                        || progress.getToolName() == null || progress.getToolName().isBlank()
                        || progress.getRound() == null || progress.getRound() < 1) {
                    return false;
                }
                pendingExecution = progress;
                continue;
            }
            if ("TOOL_RESULT".equals(progress.getStage())) {
                if (pendingExecution == null
                        || !Objects.equals(pendingExecution.getToolName(), progress.getToolName())
                        || !Objects.equals(pendingExecution.getRound(), progress.getRound())) {
                    return false;
                }
                String toolName = pendingExecution.getToolName();
                pendingExecution = null;
                if ("USABLE".equals(progress.getOutcome())) {
                    unresolvedPartialTools.remove(toolName);
                    hasUsableResult = true;
                } else if ("PARTIAL".equals(progress.getOutcome())) {
                    unresolvedPartialTools.add(toolName);
                } else {
                    return false;
                }
            }
        }
        return hasUsableResult && pendingExecution == null && unresolvedPartialTools.isEmpty();
    }

    private boolean isAssistantToolPlaceholderAdjacentToTool(List<ChatMessagePo> messages, int index) {
        if (messages == null || index < 0 || index >= messages.size()) {
            return false;
        }
        ChatMessagePo current = messages.get(index);
        if (current == null || !"assistant".equalsIgnoreCase(current.getRole())) {
            return false;
        }
        if (!"Calling tool...".equalsIgnoreCase(safeString(current.getContent()).trim())) {
            return false;
        }

        ChatMessagePo prev = index > 0 ? messages.get(index - 1) : null;
        ChatMessagePo next = index + 1 < messages.size() ? messages.get(index + 1) : null;
        return isToolMessage(prev) || isToolMessage(next);
    }

    private int messageLength(ChatMessagePo msg) {
        return msg == null || msg.getContent() == null ? 0 : msg.getContent().length();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String jsonError(String message, String errorCode, int status) {
        return AiToolResponseHelper.error(objectMapper, message, errorCode, status);
    }

    private boolean sendSseChunk(SseEmitter emitter, String data) {
        try {
            if (data != null) {
                StreamResponseDto chunk = new StreamResponseDto(data);
                emitter.send(SseEmitter.event().data(chunk, MediaType.APPLICATION_JSON));
                return true;
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send chat reply chunk; execution will continue: {}", e.toString());
            return true;
        }
        return true;
    }

    private void sendSseErrorMessage(
            SseEmitter emitter, String msg, AtomicBoolean isDisconnect) {
        if (sendSseError(emitter, msg)) {
            completeEmitter(emitter, isDisconnect);
            return;
        }
        isDisconnect.set(true);
        try {
            emitter.completeWithError(new IllegalStateException(msg));
        } catch (IllegalStateException e) {
            log.debug("SSE emitter was already unusable while reporting a terminal error: {}", e.getMessage());
        }
    }

    private boolean sendSseError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .data(StreamResponseDto.error(message), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send chat error frame; terminal state remains persisted: {}", e.toString());
            return true;
        }
    }

    private boolean requiresUserConfirmation(String toolResult) {
        if (toolResult == null || toolResult.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(toolResult);
            JsonNode value = root != null && root.isObject()
                    ? root.get("requiresUserConfirmation") : null;
            return value != null && value.isBoolean() && value.booleanValue();
        } catch (Exception ignore) {
            return false;
        }
    }

    private void completeEmitter(SseEmitter emitter, AtomicBoolean isDisconnect) {
        if (isDisconnect.get()) {
            return;
        }
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            isDisconnect.set(true);
            log.debug("SSE emitter was already unusable during completion: {}", e.getMessage());
        }
    }

    private void completeInterruptedEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.debug("SSE emitter was already unusable during interrupted completion: {}", e.getMessage());
        }
    }

    private String errorMessageFor(Exception e, boolean preferChinese) {
        if (e instanceof ChatConfirmationConflictException) {
            return preferChinese
                    ? "此确认已失效，未执行任何操作。请刷新会话，重新查看当前操作的影响预览后再确认。"
                    : "This confirmation is no longer valid. No operation was performed. Refresh the conversation "
                        + "and review the current action preview before confirming again.";
        }
        if (e instanceof LlmStreamInterruptedException) {
            return preferChinese
                    ? "模型连接中断，本轮未能完整完成。请查看会话中已完成的步骤，再重试继续。"
                    : "The model connection was interrupted and this turn could not finish. Review the completed "
                        + "steps in this conversation, then retry to continue.";
        }
        if (e instanceof ServiceUnavailableException) {
            return preferChinese
                    ? "AI 服务暂时不可用，请稍后重试；如果持续失败，请检查模型服务日志。"
                    : "AI service is temporarily unavailable. Try again later; if the failure persists, check the model service logs.";
        }
        if (e instanceof ResourceNotFoundException) {
            return preferChinese
                    ? "会话不存在或已无权访问。"
                    : "Chat session was not found or is no longer accessible.";
        }
        return preferChinese
                ? "系统异常，请稍后重试。"
                : "System error, please try again later.";
    }

    private enum ToolExecutionOutcome {
        USABLE,
        PARTIAL,
        FAILED,
        RESULT_UNAVAILABLE
    }

    private enum ReplyGuardOutcome {
        NONE,
        REPLACED_WITH_AUTHORITATIVE_EVIDENCE,
        HIDDEN_WITHOUT_EVIDENCE
    }

    private record GuardedClaimReplacement(String content, ReplyGuardOutcome outcome) {
    }

    private enum TerminalPersistenceState {
        NOT_ATTEMPTED,
        PERSISTED,
        FAILED
    }

    private record TerminalPersistenceResult(
            boolean persisted,
            ChatExecutionStatus executionStatus) {
    }

    private enum ToolLoopStopReason {
        COMPLETED,
        DISCONNECTED,
        PROVIDER_UNAVAILABLE,
        CONFIRMATION_REQUIRED,
        RESULT_UNAVAILABLE,
        PARTIAL_RESULT,
        NO_PROGRESS,
        EMERGENCY_LIMIT
    }

    private record ToolLoopResult(boolean hadToolCalls,
                                  ToolLoopStopReason stopReason,
                                  int successfulToolCalls,
                                  int failedToolCalls,
                                  int resultUnavailableToolCalls,
                                  int uncertainMutationCalls,
                                  boolean confirmationPending,
                                  String pendingToolName,
                                  String pendingToolResult,
                                  boolean replyComplete) {
        private ToolLoopResult(boolean hadToolCalls, ToolLoopStopReason stopReason,
                               int successfulToolCalls, int failedToolCalls, int resultUnavailableToolCalls,
                               int uncertainMutationCalls, boolean confirmationPending,
                               String pendingToolName, String pendingToolResult) {
            this(hadToolCalls, stopReason, successfulToolCalls, failedToolCalls,
                    resultUnavailableToolCalls, uncertainMutationCalls, confirmationPending,
                    pendingToolName, pendingToolResult, false);
        }
        private ToolLoopResult(boolean hadToolCalls,
                               ToolLoopStopReason stopReason,
                               int successfulToolCalls,
                               int failedToolCalls,
                               int resultUnavailableToolCalls,
                               int uncertainMutationCalls,
                               boolean confirmationPending) {
            this(hadToolCalls, stopReason, successfulToolCalls, failedToolCalls,
                    resultUnavailableToolCalls, uncertainMutationCalls, confirmationPending, null, null);
        }

        private boolean stoppedForNoProgress() {
            return stopReason == ToolLoopStopReason.NO_PROGRESS;
        }

        private boolean reachedEmergencyLimit() {
            return stopReason == ToolLoopStopReason.EMERGENCY_LIMIT;
        }

        private static ToolLoopResult empty() {
            return new ToolLoopResult(false, ToolLoopStopReason.COMPLETED, 0, 0, 0, 0, false);
        }
    }
}
