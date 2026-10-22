<script setup lang="ts">
import { defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  AudioOutlined,
  CloseOutlined, DeleteOutlined,
  MessageOutlined, PlusOutlined, RobotOutlined, SendOutlined,
  UserOutlined, StopOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined,
  CopyOutlined, ThunderboltOutlined, SafetyCertificateOutlined, CheckOutlined,
  CodeOutlined, ExperimentOutlined, DownOutlined, LoadingOutlined
} from '@ant-design/icons-vue';

import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';

import type {
  ChatConfirmationAction,
  ChatConfirmationCommand,
  ChatProtectedAction,
  ChatLogoutPreparation,
  ChatMessage,
  ChatHistoryPage,
  ChatSession,
  ChatSessionObservationBaseline,
  StreamCommand,
  StreamProgress,
  StreamTerminal
} from '@/types/chat';
import {
  ChatStreamError,
  createSession,
  deleteSession,
  getSessionActivity,
  getPendingConfirmation,
  getSessionHistory,
  getSessionList,
  hasCompletedToolEvidence,
  markSessionTerminalSeen,
  requestSessionStop,
  sendStreamChat
} from '@/api/chat';
import { useChatStore } from '@/stores/chat';
import { useAuth } from '@/stores/auth';
import { errorDetails, localizedTextOrFallback, type ApiErrorDetails } from '@/utils/userMessage';
import FailureDetails from '@/components/FailureDetails.vue';
import HouseholdGoalComposer from '@/components/HouseholdGoalComposer.vue';
import { REQUEST_LIMITS } from '@/constants/requestLimits';
import { confirmDestructive, notifyBlocked, notifyError, notifyInfo, notifySuccess } from '@/utils/feedback'
import HintTooltip from '@/components/common/HintTooltip.vue'
import { useModalAccessibility } from '@/composables/useModalAccessibility'

type BoardChatContext = {
  deviceCount?: number;
  ruleCount?: number;
  specCount?: number;
  templateCount?: number;
  devices?: Array<{ label?: string; templateName?: string }>;
  rules?: Array<{ name?: string; description?: string }>;
  specs?: Array<{ name?: string; formulaPreview?: string }>;
  templates?: string[];
};

const props = withDefaults(defineProps<{
  boardMode?: boolean;
  getBoardContext?: () => BoardChatContext | null;
  interactionLocked?: boolean;
  prepareInteraction?: () => boolean;
  executeCommand?: (command: StreamCommand) => Promise<boolean>;
  initialSessionObservations?: ChatSessionObservationBaseline | null;
}>(), {
  boardMode: false,
  interactionLocked: false
});
const { t, locale } = useI18n();
const ChatMarkdown = defineAsyncComponent(() => import('@/components/ChatMarkdown.vue'));

// 使用全局状态
const chatStore = useChatStore();
const visible = computed(() => chatStore.state.visible);
const { state: authState } = useAuth();
const authenticatedUserId = computed(() => authState.user?.userId ?? null);
let chatAuthEpoch = 0;
let userSessionNavigationEpoch = 0;

const boardContext = ref<BoardChatContext | null>(null);

const refreshBoardContext = () => {
  boardContext.value = props.getBoardContext?.() ?? null;
};

const joinNames = (items: Array<string | undefined | null> | undefined, fallback: string, limit = 4) => {
  const names = Array.from(new Set(
      (items ?? [])
          .map(item => (item ?? '').trim())
          .filter(Boolean)
  )).slice(0, limit);
  return names.length > 0 ? names.join(t('app.chat.presetTasks.listSeparator')) : fallback;
};

const presetTasks = computed(() => [
  ...(props.boardMode && boardContext.value
      ? (() => {
        const ctx = boardContext.value!;
        const p = (key: string, args?: Record<string, unknown>) =>
          t(`app.chat.presetTasks.${key}`, args ?? {});
        const deviceCount = ctx.deviceCount ?? 0;
        const ruleCount = ctx.ruleCount ?? 0;
        const specCount = ctx.specCount ?? 0;
        const templateCount = ctx.templateCount ?? 0;
        const deviceNames = joinNames(
            ctx.devices?.map(device => device.label || device.templateName), p('fallback.devices'));
        const templateNames = joinNames(ctx.templates, p('fallback.templates'), 5);
        const ruleNames = joinNames(
            ctx.rules?.map(rule => rule.name || rule.description), p('fallback.rules'), 3);
        const specNames = joinNames(
            ctx.specs?.map(spec => spec.name || spec.formulaPreview), p('fallback.specs'), 3);

        if (deviceCount === 0) {
          return [
            {
              icon: ThunderboltOutlined,
              title: p('empty.fromTemplates.title'),
              desc: p('empty.fromTemplates.desc', { count: templateCount }),
              text: p('empty.fromTemplates.text', { templates: templateNames })
            },
            {
              icon: ExperimentOutlined,
              title: p('empty.starterDevices.title'),
              text: p('empty.starterDevices.text', { templates: templateNames })
            },
            {
              icon: SafetyCertificateOutlined,
              title: p('empty.planGoals.title'),
              text: p('empty.planGoals.text')
            },
            {
              icon: CodeOutlined,
              title: p('empty.setupSteps.title'),
              text: p('empty.setupSteps.text')
            }
          ];
        }

        return [
          {
            icon: ThunderboltOutlined,
            title: ruleCount === 0 ? p('scene.addRules.title') : p('scene.reviewRules.title'),
            desc: p('scene.rulesDesc', { devices: deviceCount, rules: ruleCount }),
            text: ruleCount === 0
                ? p('scene.addRules.text', { devices: deviceNames })
                : p('scene.reviewRules.text', { rules: ruleNames, devices: deviceNames })
          },
          {
            icon: ExperimentOutlined,
            title: deviceCount < 5 ? p('scene.addDevices.title') : p('scene.refineDevices.title'),
            desc: p('scene.devicesDesc', { devices: deviceNames }),
            text: deviceCount < 5
                ? p('scene.addDevices.text', { devices: deviceNames })
                : p('scene.refineDevices.text', { devices: deviceNames })
          },
          {
            icon: SafetyCertificateOutlined,
            title: specCount === 0 ? p('scene.generateSpecs.title') : p('scene.reviewSpecs.title'),
            desc: p('scene.specsDesc', { count: specCount }),
            text: specCount === 0
                ? p('scene.generateSpecs.text', { devices: deviceNames, rules: ruleCount })
                : p('scene.reviewSpecs.text', { specs: specNames })
          },
          {
            icon: CodeOutlined,
            title: p('scene.violationTest.title'),
            text: p('scene.violationTest.text',
                { devices: deviceCount, rules: ruleCount, specs: specCount })
          }
        ];
      })()
      : [
        {
          icon: ThunderboltOutlined,
          title: t('app.chat.presetTasks.quickDevice.title'),
          text: t('app.chat.presetTasks.quickDevice.text')
        },
        {
          icon: SafetyCertificateOutlined,
          title: t('app.chat.presetTasks.verification.title'),
          desc: t('app.chat.presetTasks.verification.desc'),
          text: t('app.chat.presetTasks.verification.text')
        },
        {
          icon: ExperimentOutlined,
          title: t('app.chat.presetTasks.scenario.title'),
          desc: t('app.chat.presetTasks.scenario.desc'),
          text: t('app.chat.presetTasks.scenario.text')
        },
        {
          icon: CodeOutlined,
          title: t('app.chat.presetTasks.script.title'),
          desc: t('app.chat.presetTasks.script.desc'),
          text: t('app.chat.presetTasks.script.text')
        }
      ])
]);

// State - 使用全局状态，不重复定义
const isSidebarOpen = ref(false);
const isRecording = ref(false);
const sessions = ref<ChatSession[]>([]);
const terminalSeenRequests = new Map<string, number>();
const isLoadingSessions = ref(false);
const sessionListLoadFailed = ref(false);
const isCreatingSession = ref(false);
const isPreparingStreamSession = ref(false);
const currentSessionId = ref<string>('');
const messages = ref<ChatMessage[]>([]);
const pendingConfirmationActions = ref<ChatProtectedAction[]>([]);
const pendingConfirmationLoadFailed = ref(false);
const inputValue = ref('');
const inputRef = ref<HTMLTextAreaElement | null>(null);
const isStreaming = ref(false);
const isSettlingStream = ref(false);
const isMonitoringRemoteExecution = ref(false);
const remoteActivityCheckFailed = ref(false);
const streamProgress = ref<StreamProgress | null>(null);
const streamProgressEvents = ref<StreamProgress[]>([]);
const streamElapsedSeconds = ref(0);
const streamLastActivitySeconds = ref(0);
const streamWaitingForUpdate = computed(() =>
    streamElapsedSeconds.value - streamLastActivitySeconds.value >= 15);
const streamActivityLabel = computed(() => {
  const progress = streamProgress.value;
  if (progress?.stage === 'TOOL_EXECUTION') return readableToolName(progress);
  if (progress?.stage === 'PLANNING' && progress.detail) return progress.detail;
  if (messages.value[messages.value.length - 1]?.content) return t('app.chat.responseStreaming');
  return progress ? progressEventTitle(progress) : t('app.chat.progressContextTitle');
});
let streamElapsedTimer: ReturnType<typeof setInterval> | null = null;
let confirmationRequestEpoch = 0;
const reconciliationRequired = computed({
  get: () => chatStore.state.reconciliationRequired,
  set: (required: boolean) => chatStore.setReconciliationRequired(required)
});
const isRetryingReconciliation = ref(false);
const activeStreamSessionId = ref('');
const activeStreamTurnId = ref('');
const scopedAuthToken = ref<string | null>(authState.token);
let scopedAuthUserId = authenticatedUserId.value;
const activeStreamAuthToken = ref<string | null>(null);
const historyReplacementWithoutTerminalAllowed = ref(false);
const explicitStopRegistrationPending = ref(false);
const activeStreamRetryDraft = ref('');
const isLoadingHistory = ref(false);
const historyLoadFailed = ref(false);
const historyLoadDiagnostics = ref<ApiErrorDetails | null>(null);
const isLoadingOlderHistory = ref(false);
const historyHasMore = ref(false);
const historyNextBeforeId = ref<number | null>(null);
const hasAuthoritativeActiveSession = computed(() =>
    sessions.value.some(session => session.active));
const hasCurrentAuthoritativeActiveSession = computed(() =>
    Boolean(currentSessionId.value)
    && sessions.value.some(session => session.id === currentSessionId.value && session.active));
const isAssistantBusy = computed(() =>
    isStreaming.value || isSettlingStream.value || isMonitoringRemoteExecution.value
    || reconciliationRequired.value || hasCurrentAuthoritativeActiveSession.value);
const hasAnyAssistantWork = computed(() =>
    isStreaming.value || isSettlingStream.value || reconciliationRequired.value
    || hasAuthoritativeActiveSession.value || chatStore.state.activeCount > 0);
const isLoading = computed(() => isAssistantBusy.value || isLoadingHistory.value);
const TOOL_LABEL_KEYS: Record<string, string> = {
  prepare_tools: 'app.chat.toolLabels.prepareTools',
  edit_device: 'app.chat.toolLabels.editDevice',
  apply_fix: 'app.chat.toolLabels.applyFix',
  delete_verification_run: 'app.chat.toolLabels.deleteVerificationRun',
  dismiss_verify_task: 'app.chat.toolLabels.dismissVerifyTask',
  dismiss_simulate_task: 'app.chat.toolLabels.dismissSimulateTask',
  fuzz_model_async: 'app.chat.toolLabels.fuzzModelAsync',
  fuzz_task_status: 'app.chat.toolLabels.fuzzTaskStatus',
  cancel_fuzz_task: 'app.chat.toolLabels.cancelFuzzTask',
  list_fuzz_runs: 'app.chat.toolLabels.listFuzzRuns',
  get_fuzz_run: 'app.chat.toolLabels.getFuzzRun',
  get_fuzz_finding: 'app.chat.toolLabels.getFuzzFinding',
  delete_fuzz_run: 'app.chat.toolLabels.deleteFuzzRun',
  dismiss_fuzz_task: 'app.chat.toolLabels.dismissFuzzTask',
  add_device: 'app.chat.toolLabels.addDevice',
  delete_device: 'app.chat.toolLabels.deleteDevice',
  search_devices: 'app.chat.toolLabels.searchDevices',
  recommend_related_devices: 'app.chat.toolLabels.recommendRelatedDevices',
  manage_environment: 'app.chat.toolLabels.manageEnvironment',
  list_rules: 'app.chat.toolLabels.listRules',
  manage_rule: 'app.chat.toolLabels.manageRule',
  check_duplicate_rule: 'app.chat.toolLabels.checkDuplicateRule',
  check_rule_similarity: 'app.chat.toolLabels.checkRuleSimilarity',
  recommend_rules: 'app.chat.toolLabels.recommendRules',
  list_specs: 'app.chat.toolLabels.listSpecs',
  manage_spec: 'app.chat.toolLabels.manageSpec',
  recommend_specifications: 'app.chat.toolLabels.recommendSpecifications',
  recommend_scenario: 'app.chat.toolLabels.recommendScenario',
  apply_scenario: 'app.chat.toolLabels.applyScenario',
  list_templates: 'app.chat.toolLabels.listTemplates',
  add_template: 'app.chat.toolLabels.addTemplate',
  delete_template: 'app.chat.toolLabels.deleteTemplate',
  reset_default_templates: 'app.chat.toolLabels.resetDefaultTemplates',
  verify_model: 'app.chat.toolLabels.verifyModel',
  verify_model_async: 'app.chat.toolLabels.verifyModelAsync',
  verify_task_status: 'app.chat.toolLabels.verifyTaskStatus',
  cancel_verify_task: 'app.chat.toolLabels.cancelVerifyTask',
  list_traces: 'app.chat.toolLabels.listTraces',
  get_trace: 'app.chat.toolLabels.getTrace',
  delete_trace: 'app.chat.toolLabels.deleteTrace',
  fix_violation: 'app.chat.toolLabels.fixViolation',
  simulate_model: 'app.chat.toolLabels.simulateModel',
  simulate_model_async: 'app.chat.toolLabels.simulateModelAsync',
  simulate_task_status: 'app.chat.toolLabels.simulateTaskStatus',
  cancel_simulate_task: 'app.chat.toolLabels.cancelSimulateTask',
  list_simulation_traces: 'app.chat.toolLabels.listSimulationTraces',
  get_simulation_trace: 'app.chat.toolLabels.getSimulationTrace',
  delete_simulation_trace: 'app.chat.toolLabels.deleteSimulationTrace',
  list_async_tasks: 'app.chat.toolLabels.listAsyncTasks',
  list_verification_runs: 'app.chat.toolLabels.listVerificationRuns',
  get_verification_run: 'app.chat.toolLabels.getVerificationRun',
  read_run_model: 'app.chat.toolLabels.readRunModel',
  read_conversation_activity: 'app.chat.toolLabels.readConversationActivity',
  manage_board_history: 'app.chat.toolLabels.manageBoardHistory',
  clear_board: 'app.chat.toolLabels.clearBoard',
  board_overview: 'app.chat.toolLabels.boardOverview'
};
const readableToolName = (progress: StreamProgress) => {
  const toolName = progress.toolName || '';
  const translationKey = TOOL_LABEL_KEYS[toolName];
  return translationKey ? t(translationKey) : (toolName || t('app.chat.progressUnknownTool')).replace(/_/g, ' ');
};
const progressEventTitle = (progress: StreamProgress) => {
  if (progress.stage === 'CONTEXT_READY') return t('app.chat.progressContextTitle');
  if (progress.stage === 'TASK_RESUMED') return t('app.chat.progressTaskResumedTitle');
  if (progress.stage === 'PLANNING') return t('app.chat.progressPlanningTitle');
  if (progress.stage === 'REASONING') return t('app.chat.progressReasoningTitle');
  if (progress.stage === 'CLAIM_REVIEW') return t('app.chat.progressClaimReviewTitle');
  if (progress.stage === 'TOOL_EXECUTION' || progress.stage === 'TOOL_RESULT') {
    return readableToolName(progress);
  }
  if (progress.stage === 'EXECUTION_GUARD') {
    return progress.outcome === 'NO_PROGRESS'
        ? t('app.chat.progressNoProgressTitle')
        : t('app.chat.progressEmergencyTitle');
  }
  return t('app.chat.progressWritingTitle');
};
const progressEventDetail = (progress: StreamProgress, streaming = false) => {
  const round = progress.round || 1;
  if (progress.stage === 'CONTEXT_READY') return t('app.chat.progressContextDetail');
  if (progress.stage === 'TASK_RESUMED') {
    return t('app.chat.progressTaskResumedDetail', {
      objective: progress.detail || t('app.chat.progressTaskResumedFallback')
    });
  }
  if (progress.stage === 'PLANNING') {
    return t('app.chat.progressPlanningDetail', { round });
  }
  if (progress.stage === 'REASONING') {
    // An empty detail means the model returned no summary this round. Say that, rather than
    // describing reasoning that did not happen — a canned "summarizing the goal…" line renamed
    // the absence of reasoning as reasoning.
    return progress.detail || t('app.chat.progressReasoningFallback');
  }
  if (progress.stage === 'CLAIM_REVIEW') {
    return t(`app.chat.claimReviewReasons.${progress.claimReview.reasonCode}`);
  }
  if (progress.stage === 'TOOL_EXECUTION') {
    return t('app.chat.progressToolStartedDetail', {
      round,
      tool: progress.toolName || t('app.chat.progressUnknownTool')
    });
  }
  if (progress.stage === 'TOOL_RESULT') {
    if (progress.detail) return progress.detail;
    if (progress.outcome === 'FAILED') return t('app.chat.progressToolFailedDetail', { round });
    if (progress.outcome === 'PARTIAL') return t('app.chat.progressToolPartialDetail', { round });
    if (progress.outcome === 'RESULT_UNAVAILABLE') return t('app.chat.progressToolUnconfirmedDetail', { round });
    if (progress.outcome === 'CONFIRMATION_REQUIRED') return t('app.chat.progressToolNeedsConfirmationDetail', { round });
    return '';
  }
  if (progress.stage === 'EXECUTION_GUARD') {
    return progress.outcome === 'NO_PROGRESS'
        ? t('app.chat.progressNoProgressDetail')
        : t('app.chat.progressEmergencyDetail');
  }
  // The final stage reads in the past tense once the turn is over.
  //
  // Every detail here was derived from the stage alone, with no notion that the turn had since ended, so the
  // last entry kept saying "正在根据工具的实际结果说明…" — *currently* explaining — indefinitely. A measurement
  // of a settled panel found it claiming both states at once (`statesCompleted: true, statesStillRunning:
  // true`), and a review reached the same conclusion from the screen: the text "can read as still in progress,
  // so completion is somewhat ambiguous".
  //
  // Only this stage needs the distinction. Every earlier stage is followed by another entry, so its
  // progressive reads naturally as a log of what was happening then; `WRITING_RESPONSE` is terminal, and a
  // terminal step still narrating itself is the one that leaves a user unsure whether to wait.
  return streaming
    ? t('app.chat.progressWritingDetail')
    : t('app.chat.progressWritingDetailDone');
};
const progressEventStatus = (progress: StreamProgress) => {
  if (progress.stage === 'TOOL_EXECUTION') return t('app.chat.progressStatusStarted');
  if (progress.stage === 'TOOL_RESULT') {
    if (progress.outcome === 'FAILED') return t('app.chat.progressStatusFailed');
    if (progress.outcome === 'PARTIAL') return t('app.chat.progressStatusPartial');
    if (progress.outcome === 'RESULT_UNAVAILABLE') return t('app.chat.progressStatusUnconfirmed');
    if (progress.outcome === 'CONFIRMATION_REQUIRED') return t('app.chat.progressStatusConfirmation');
    return t('app.chat.progressStatusSucceeded');
  }
  if (progress.stage === 'TASK_RESUMED') return t('app.chat.progressStatusResumed');
  if (progress.stage === 'CLAIM_REVIEW') return t('app.chat.progressClaimHidden');
  return null;
};
/**
 * Memoized execution trace totals calculation.
 *
 * This cache prevents redundant filtering when the same trace is queried multiple times
 * (e.g., in the template rendering for successful/failed/unconfirmed counts).
 */
const executionTraceTotalsCache = new Map<StreamProgress[], { successful: number; failed: number; unconfirmed: number }>();
const executionTraceTotals = (trace: StreamProgress[]) => {
  const cached = executionTraceTotalsCache.get(trace);
  if (cached) return cached;

  const latest = [...trace].reverse().find(progress =>
      progress.successfulSteps != null
      || progress.failedSteps != null
      || progress.unconfirmedSteps != null);
  const result = {
    successful: latest?.successfulSteps ?? trace.filter(progress =>
        progress.stage === 'TOOL_RESULT' && progress.outcome === 'USABLE').length,
    failed: latest?.failedSteps ?? trace.filter(progress =>
        progress.stage === 'TOOL_RESULT' && progress.outcome === 'FAILED').length,
    unconfirmed: latest?.unconfirmedSteps ?? trace.filter(progress =>
        progress.stage === 'TOOL_RESULT'
        && (progress.outcome === 'RESULT_UNAVAILABLE'
            || progress.outcome === 'CONFIRMATION_REQUIRED')).length
  };

  executionTraceTotalsCache.set(trace, result);
  return result;
};
const traceHasToolResults = (trace: StreamProgress[]) =>
    trace.some(progress => progress.stage === 'TOOL_RESULT');
const traceHasToolActivity = (trace: StreamProgress[]) =>
    trace.some(progress => progress.stage === 'TOOL_EXECUTION' || progress.stage === 'TOOL_RESULT');
const executionTraceStatus = (
    trace: StreamProgress[],
    active: boolean,
    status?: ChatMessage['executionStatus'],
    settling = false
) => {
  if (active) return t('app.chat.executionTraceRunning');
  if (settling) return t('app.chat.executionTraceSettling');
  if (status === 'AWAITING_CONFIRMATION') {
    return executionTraceTotals(trace).successful > 0
        ? t('app.chat.executionTracePartialAwaitingConfirmation')
        : t('app.chat.executionTraceAwaitingConfirmation');
  }
  if (status === 'STOPPED') return t('app.chat.executionTraceStopped');
  if (status === 'DISCONNECTED') return t('app.chat.executionTraceDisconnected');
  const guard = [...trace].reverse().find(progress => progress.stage === 'EXECUTION_GUARD');
  if (guard) {
    return guard.outcome === 'NO_PROGRESS'
      ? t('app.chat.executionTraceStoppedNoProgress')
      : t('app.chat.executionTraceStoppedLimit');
  }
  if (status === 'PARTIAL') {
    return trace.length > 0 && !traceHasToolActivity(trace)
      ? t('app.chat.executionTraceNoTool')
      : t('app.chat.executionTracePartial');
  }
  if (status === 'FAILED') return t('app.chat.executionTraceFailed');
  if (status !== 'COMPLETED' || !hasCompletedToolEvidence(trace)) {
    return t('app.chat.executionTraceUnverified');
  }
  const lastResult = [...trace].reverse().find(progress => progress.stage === 'TOOL_RESULT');
  return lastResult?.outcome === 'CONFIRMATION_REQUIRED'
      ? t('app.chat.executionTraceWaiting')
      : t('app.chat.executionTraceCompleted');
};
const sessionExecutionStatus = (session: ChatSession) => {
  if (session.active) return t('app.chat.sessionActive');
  switch (session.latestExecutionStatus) {
    case 'COMPLETED': return t('app.chat.executionTraceCompleted');
    case 'AWAITING_CONFIRMATION': return t('app.chat.executionTraceAwaitingConfirmation');
    case 'PARTIAL': return t('app.chat.sessionExecutionDetails');
    case 'STOPPED': return t('app.chat.executionTraceStopped');
    case 'DISCONNECTED': return t('app.chat.executionTraceDisconnected');
    case 'FAILED': return t('app.chat.executionTraceFailed');
    default: return '';
  }
};
const sessionStatusClass = (session: ChatSession) => ({
  'is-running': session.active,
  'is-unread': session.hasUnreadUpdate,
  'is-failed': !session.active && session.latestExecutionStatus === 'FAILED',
  'is-warning': !session.active && (session.latestExecutionStatus === 'PARTIAL'
    || session.latestExecutionStatus === 'STOPPED'
    || session.latestExecutionStatus === 'DISCONNECTED'
    || session.latestExecutionStatus === 'AWAITING_CONFIRMATION')
});
const sessionStatusTitle = (session: ChatSession) => session.hasUnreadUpdate
    ? t('app.chat.sessionUnreadUpdate', { status: sessionExecutionStatus(session) })
    : sessionExecutionStatus(session);
const progressEventClass = (progress: StreamProgress, active: boolean) => ({
  'is-success': progress.stage === 'TOOL_RESULT' && progress.outcome === 'USABLE',
  'is-warning': progress.stage === 'EXECUTION_GUARD'
      || progress.stage === 'CLAIM_REVIEW'
      || progress.outcome === 'PARTIAL'
      || progress.outcome === 'RESULT_UNAVAILABLE'
      || progress.outcome === 'CONFIRMATION_REQUIRED',
  'is-error': progress.stage === 'TOOL_RESULT' && progress.outcome === 'FAILED',
  'is-reasoning': progress.stage === 'REASONING',
  'is-current': active && progress === streamProgressEvents.value[streamProgressEvents.value.length - 1]
});
const isCurrentProgressEvent = (progress: StreamProgress, active: boolean) =>
    active && progress === streamProgressEvents.value[streamProgressEvents.value.length - 1];
const appendStreamProgress = (progress: StreamProgress) => {
  streamLastActivitySeconds.value = streamElapsedSeconds.value;
  streamProgress.value = progress;
  const previous = streamProgressEvents.value[streamProgressEvents.value.length - 1];
  if (previous
      && progress.stage !== 'CLAIM_REVIEW'
      && previous.stage === progress.stage
      && (previous.toolName ?? null) === (progress.toolName ?? null)
      && (previous.round ?? null) === (progress.round ?? null)
      && (previous.outcome ?? null) === (progress.outcome ?? null)
      && (previous.successfulSteps ?? null) === (progress.successfulSteps ?? null)
      && (previous.failedSteps ?? null) === (progress.failedSteps ?? null)
      && (previous.unconfirmedSteps ?? null) === (progress.unconfirmedSteps ?? null)
      && (previous.detail ?? null) === (progress.detail ?? null)) {
    return;
  }
  streamProgressEvents.value.push({ ...progress });
  nextTick(() => {
    const activeTrace = chatPanelRef.value?.querySelector<HTMLElement>(
        '[data-testid="chat-assistant-pending"] .chat-execution-events');
    if (activeTrace) activeTrace.scrollTop = activeTrace.scrollHeight;
    scrollToBottom(false);
  });
};
const isActiveAssistantMessage = (index: number) =>
    isStreaming.value && index === messages.value.length - 1;
const isSettlingAssistantMessage = (index: number) =>
    isSettlingStream.value
    && Boolean(activeStreamSessionId.value)
    && activeStreamSessionId.value === currentSessionId.value
    && index === messages.value.length - 1;

/**
 * Per-message execution trace cache.
 *
 * Prevents redundant trace lookups when the same message is rendered multiple times
 * in the template (e.g., for totals and detailed progress).
 */
const messageExecutionTraceCache = new Map<string, StreamProgress[]>();
const messageExecutionTrace = (message: ChatMessage, index: number) => {
  if (isActiveAssistantMessage(index)) return streamProgressEvents.value;

  const cacheKey = `${message.id}-${index}`;
  const cached = messageExecutionTraceCache.get(cacheKey);
  if (cached) return cached;

  const trace = message.executionTrace ?? [];
  messageExecutionTraceCache.set(cacheKey, trace);
  return trace;
};
const messageExecutionElapsed = (message: ChatMessage, index: number) =>
    isActiveAssistantMessage(index) ? streamElapsedSeconds.value : (message.executionElapsedSeconds ?? 0);
/**
 * Turns whose reasoning panel the user collapsed by hand, keyed by `turnId`.
 *
 * Reasoning used to shut the instant the turn ended, so anyone who was not watching the stream
 * never read it. The newest turn now stays open once it completes — but a deliberate collapse is
 * remembered, because re-expanding what the user just closed is worse than hiding it.
 */
const collapsedExecutionTraces = ref(new Set<string>());

/**
 * Clear performance caches when messages change.
 */
const clearPerformanceCaches = () => {
  executionTraceTotalsCache.clear();
  messageExecutionTraceCache.clear();
  // processedContentCache is kept across sessions as message content is immutable
};

/**
 * Identity a collapse is remembered under.
 *
 * `turnId` is optional on `ChatMessage`, and a row without one could record no collapse at all — so
 * `:open` re-expanded the panel on the next render, discarding the user's deliberate close. Only the
 * last message can be collapsed here, so its index is a stable enough fallback key.
 */
const executionTraceKey = (msg: ChatMessage, index: number) => msg.turnId ?? `index:${index}`;

const isExecutionTraceOpen = (msg: ChatMessage, index: number) => {
  if (isActiveAssistantMessage(index)) return true;
  if (index !== messages.value.length - 1) return false;
  return !collapsedExecutionTraces.value.has(executionTraceKey(msg, index));
};

const handleExecutionTraceToggle = (event: Event, active: boolean, msg?: ChatMessage, index?: number) => {
  const target = event.currentTarget as HTMLDetailsElement | null;
  if (msg && typeof index === 'number' && !active) {
    const key = executionTraceKey(msg, index);
    const collapsed = new Set(collapsedExecutionTraces.value);
    if (target?.open) collapsed.delete(key);
    else collapsed.add(key);
    collapsedExecutionTraces.value = collapsed;
  }
  return handleExecutionTraceScroll(event, active);
};

const handleExecutionTraceScroll = (event: Event, active: boolean) => {
  const details = event.currentTarget as HTMLDetailsElement | null;
  if (!details?.open || active) return;
  nextTick(() => {
    const events = details.querySelector<HTMLElement>('.chat-execution-events');
    if (events) events.scrollTop = 0;
  });
};
watch(hasAnyAssistantWork, busy => chatStore.setStreaming(busy), { immediate: true });

// Clear execution trace caches when streaming progress updates
watch(streamProgressEvents, () => {
  executionTraceTotalsCache.clear();
}, { deep: true });

const scrollRef = ref<HTMLElement | null>(null);
const chatPanelRef = ref<HTMLElement | null>(null);
const sidebarToggleRef = ref<HTMLButtonElement | null>(null);
watch(isSidebarOpen, open => {
  // Move focus before inert hides the selected session on a compact panel or after resizing.
  if (!open && chatPanelRef.value?.querySelector('.sidebar')?.contains(document.activeElement)) {
    sidebarToggleRef.value?.focus({ preventScroll: true });
  }
}, { flush: 'sync' });
const { setDialogRef: setChatDialogRef, handleModalKeydown: handleChatKeydown } = useModalAccessibility(
  visible, () => chatStore.closeChat(),
  () => document.querySelector<HTMLElement>('[data-testid="open-ai-assistant"]'),
  { trapFocus: false }
);
const setChatPanelRef = (element: unknown) => {
  chatPanelRef.value = element instanceof HTMLElement ? element : null;
  setChatDialogRef(element);
};
const abortController = ref<AbortController | null>(null);
const historyAbortController = ref<AbortController | null>(null);
let streamRequestEpoch = 0;
let historyRequestEpoch = 0;
let sessionListRequestEpoch = 0;
let settlementPromise: Promise<ChatLogoutPreparation> | null = null;
let settlementAbortController: AbortController | null = null;
let settlementDetachedTransport: AbortController | null = null;
let reconciliationPromise: Promise<boolean> | null = null;
let activityMonitorEpoch = 0;
let activityMonitorAbortController: AbortController | null = null;
let activeSessionsMonitorEpoch = 0;
let activeSessionsPollTimer: ReturnType<typeof setTimeout> | null = null;
let activeSessionsPollInFlight = false;
let chatViewMounted = false;
let sessionCompletionObservationsInitialized = false;
const chatPosition = ref<{ left: number; top: number; width: number; height: number } | null>(null);
const composerHeight = ref(44);
const composerMinHeight = ref(44);
const composerMaxHeight = ref(128);
let composerResize: { pointerId: number; startY: number; height: number; target: HTMLElement } | null = null;

const setComposerHeight = (height: number) => {
  composerHeight.value = Math.min(composerMaxHeight.value, Math.max(composerMinHeight.value, height));
};

const stopComposerResize = (event?: PointerEvent) => {
  const gesture = composerResize;
  if (!gesture || (event && event.pointerId !== gesture.pointerId)) return;
  composerResize = null;
  try {
    gesture.target.releasePointerCapture?.(gesture.pointerId);
  } catch {
    // Cancellation or window blur may have already released the pointer.
  }
};

const startComposerResize = (event: PointerEvent) => {
  if (event.button !== 0 || event.isPrimary === false) return;
  const target = event.currentTarget as HTMLElement;
  target.focus({ preventScroll: true });
  composerResize = { pointerId: event.pointerId, startY: event.clientY,
    height: composerHeight.value, target };
  target.setPointerCapture?.(event.pointerId);
  event.preventDefault();
};

const moveComposerResize = (event: PointerEvent) => {
  if (!composerResize || event.pointerId !== composerResize.pointerId) return;
  if (event.pointerType === 'mouse' && event.buttons === 0) {
    stopComposerResize(event);
    return;
  }
  // The composer is bottom-anchored: moving its top edge upward increases the writing space.
  setComposerHeight(composerResize.height + composerResize.startY - event.clientY);
};

const handleComposerResizeKeydown = (event: KeyboardEvent) => {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
  event.preventDefault();
  setComposerHeight(event.key === 'Home' ? composerMinHeight.value
    : event.key === 'End' ? composerMaxHeight.value
      : composerHeight.value + (event.key === 'ArrowUp' ? 16 : -16));
};

const clampComposerHeight = () => {
  if (!inputRef.value) return;
  composerMinHeight.value = parseFloat(getComputedStyle(inputRef.value).minHeight) || 44;
  composerMaxHeight.value = Math.max(composerMinHeight.value,
    Math.min(parseFloat(getComputedStyle(document.documentElement).fontSize) * 8 || 128, window.innerHeight * 0.28));
  setComposerHeight(composerHeight.value);
};

watch([visible, () => chatPosition.value?.height], async () => {
  await nextTick();
  if (visible.value) clampComposerHeight();
}, { immediate: true });

const panelInteraction = ref<{
  mode: 'drag' | 'resize';
  pointerId: number;
  target: HTMLElement;
  startX: number;
  startY: number;
  left: number;
  top: number;
  width: number;
  height: number;
} | null>(null);

const CHAT_MIN_WIDTH = 320;
const CHAT_MIN_HEIGHT = 360;
const CHAT_ABSOLUTE_MIN_HEIGHT = 260;
const CHAT_DEFAULT_WIDTH = 760;
const CHAT_DEFAULT_HEIGHT = 640;
const CHAT_DEFAULT_VERTICAL_SLACK = 96;
const CHAT_COMPACT_WIDTH = 960;

const isCompactChatWidth = (width: number) => width < CHAT_COMPACT_WIDTH;

const canAdjustChatPanel = () => typeof window !== 'undefined' && window.innerWidth > 720;

const getChatMinimumHeight = (availableHeight: number) => Math.min(
    CHAT_MIN_HEIGHT,
    Math.max(CHAT_ABSOLUTE_MIN_HEIGHT, availableHeight - CHAT_DEFAULT_VERTICAL_SLACK * 2)
);

const getChatViewportInsets = () => {
  const boardToolRail = props.boardMode && window.innerWidth < 1280 ? 128 : 12;
  return {
    left: 12,
    right: boardToolRail,
    top: props.boardMode ? 76 : 12,
    bottom: 12
  };
};

const clampChatGeometry = (left: number, top: number, width: number, height: number) => {
  const insets = getChatViewportInsets();
  const availableWidth = Math.max(240, window.innerWidth - insets.left - insets.right);
  const availableHeight = Math.max(260, window.innerHeight - insets.top - insets.bottom);
  const minWidth = Math.min(CHAT_MIN_WIDTH, availableWidth);
  const minHeight = Math.min(getChatMinimumHeight(availableHeight), availableHeight);
  const nextWidth = Math.min(Math.max(width, minWidth), availableWidth);
  const nextHeight = Math.min(Math.max(height, minHeight), availableHeight);
  const minLeft = insets.left;
  const maxLeft = Math.max(minLeft, window.innerWidth - insets.right - nextWidth);
  const minTop = insets.top;
  const maxTop = Math.max(minTop, window.innerHeight - insets.bottom - nextHeight);
  return {
    left: Math.min(Math.max(minLeft, left), maxLeft),
    top: Math.min(Math.max(minTop, top), maxTop),
    width: nextWidth,
    height: nextHeight
  };
};

const createDefaultChatGeometry = () => {
  const insets = getChatViewportInsets();
  const availableWidth = Math.max(240, window.innerWidth - insets.left - insets.right);
  const availableHeight = Math.max(260, window.innerHeight - insets.top - insets.bottom);
  const width = Math.min(CHAT_DEFAULT_WIDTH, availableWidth);
  // A floating window needs visible room to move and grow. Taking every available vertical pixel
  // at common 720px desktop heights made a draggable/resizable panel appear inert.
  const height = Math.min(
      CHAT_DEFAULT_HEIGHT,
      Math.max(getChatMinimumHeight(availableHeight), availableHeight - CHAT_DEFAULT_VERTICAL_SLACK)
  );
  const left = Math.max(insets.left, window.innerWidth - insets.right - width);
  const top = Math.max(insets.top, insets.top + 12);
  return clampChatGeometry(left, top, width, height);
};

const chatPanelStyle = computed<Record<string, string>>(() => {
  const position = chatPosition.value;
  if (typeof window !== 'undefined' && window.innerWidth <= 720) return {} as Record<string, string>;
  if (!position) return {} as Record<string, string>;
  return {
    left: `${position.left}px`,
    top: `${position.top}px`,
    right: 'auto',
    bottom: 'auto',
    width: `${position.width}px`,
    height: `${position.height}px`
  };
});

const isChatPanelCompact = computed(() => {
  if (typeof window === 'undefined') return false;
  const width = chatPosition.value?.width ?? window.innerWidth;
  return isCompactChatWidth(width);
});

watch(isChatPanelCompact, (compact, wasCompact) => {
  if (compact && !wasCompact) {
    isSidebarOpen.value = false;
  }
});

const removePanelInteractionListeners = () => {
  window.removeEventListener('pointermove', onPanelInteractionMove);
  window.removeEventListener('pointerup', stopPanelInteraction);
  window.removeEventListener('pointercancel', stopPanelInteraction);
};

const stopPanelInteraction = (event?: PointerEvent) => {
  const state = panelInteraction.value;
  if (state && event?.pointerId !== undefined && event.pointerId !== state.pointerId) return;

  panelInteraction.value = null;
  removePanelInteractionListeners();
  if (!state) return;

  state.target.removeEventListener('lostpointercapture', onPanelLostPointerCapture);
  try {
    state.target.releasePointerCapture?.(state.pointerId);
  } catch {
    // The browser may already have released capture while the window was resizing or losing focus.
  }
};

const onPanelLostPointerCapture = (event: PointerEvent) => {
  stopPanelInteraction(event);
};

const onPanelInteractionMove = (event: PointerEvent) => {
  const state = panelInteraction.value;
  if (!state || event.pointerId !== state.pointerId) return;
  if (event.pointerType === 'mouse' && event.buttons === 0) {
    stopPanelInteraction(event);
    return;
  }

  if (state.mode === 'drag') {
    chatPosition.value = clampChatGeometry(
        state.left + event.clientX - state.startX,
        state.top + event.clientY - state.startY,
        state.width,
        state.height
    );
    return;
  }

  const insets = getChatViewportInsets();
  const maxWidth = window.innerWidth - insets.right - state.left;
  const maxHeight = window.innerHeight - insets.bottom - state.top;
  chatPosition.value = clampChatGeometry(
      state.left,
      state.top,
      Math.min(state.width + event.clientX - state.startX, maxWidth),
      Math.min(state.height + event.clientY - state.startY, maxHeight)
  );
};

const startPanelInteraction = (event: PointerEvent, mode: 'drag' | 'resize') => {
  if (event.button !== 0 || event.isPrimary === false || !canAdjustChatPanel()
      || panelInteraction.value) return;
  const panel = chatPanelRef.value;
  if (!panel) return;

  event.preventDefault();
  const target = event.currentTarget as HTMLElement;
  // Entry and viewport transitions interpolate the rendered box; they must not resize the window.
  const position = chatPosition.value ?? createDefaultChatGeometry();
  const geometry = clampChatGeometry(position.left, position.top, position.width, position.height);
  chatPosition.value = geometry;
  panelInteraction.value = {
    mode,
    pointerId: event.pointerId,
    target,
    startX: event.clientX,
    startY: event.clientY,
    left: geometry.left,
    top: geometry.top,
    width: geometry.width,
    height: geometry.height
  };

  try {
    target.setPointerCapture?.(event.pointerId);
  } catch {
    // Pointer capture is optional; window listeners retain the fallback path.
  }
  target.addEventListener('lostpointercapture', onPanelLostPointerCapture);
  window.addEventListener('pointermove', onPanelInteractionMove);
  window.addEventListener('pointerup', stopPanelInteraction);
  window.addEventListener('pointercancel', stopPanelInteraction);
};

const startPanelDrag = (event: PointerEvent) => {
  const target = event.target as HTMLElement | null;
  if (target?.closest('button, a, input, textarea, select, [role="button"], .chat-resize-handle')) return;
  startPanelInteraction(event, 'drag');
};

const startPanelResize = (event: PointerEvent) => {
  event.stopPropagation();
  startPanelInteraction(event, 'resize');
};

const clampExistingChatPosition = () => {
  const position = chatPosition.value;
  chatPosition.value = position
      ? clampChatGeometry(position.left, position.top, position.width, position.height)
      : createDefaultChatGeometry();
  if (isCompactChatWidth(chatPosition.value.width)) {
    isSidebarOpen.value = false;
  }
};

/**
 * Throttle utility for limiting function execution frequency.
 *
 * Returns a throttled version of the function that executes at most once per `delay` milliseconds.
 */
const throttle = <T extends (...args: any[]) => any>(fn: T, delay: number): T => {
  let lastCall = 0;
  let timeoutId: ReturnType<typeof setTimeout> | null = null;

  return ((...args: any[]) => {
    const now = Date.now();
    const timeSinceLastCall = now - lastCall;

    const execute = () => {
      lastCall = Date.now();
      fn(...args);
    };

    if (timeSinceLastCall >= delay) {
      if (timeoutId) {
        clearTimeout(timeoutId);
        timeoutId = null;
      }
      execute();
    } else {
      if (timeoutId) clearTimeout(timeoutId);
      timeoutId = setTimeout(() => {
        execute();
        timeoutId = null;
      }, delay - timeSinceLastCall);
    }
  }) as T;
};

/*
 * Cancelling the gesture is immediate; only the re-clamp is throttled.
 *
 * Both used to sit inside one `throttle(..., 200)`, which is a TRAILING-edge throttle — so
 * `stopPanelInteraction()` could fire up to 200ms after the resize that scheduled it, and cancel a drag
 * the user had begun in the meantime. The panel then moved a few pixels and stopped: `Full CI` failed on
 * "releases an interrupted panel gesture when the viewport changes" with the panel 19px from its origin
 * where the gesture asked for 100, on a commit that changed one documentation anchor. It passed on the
 * commit before and three times locally, which is the signature of a race rather than a regression.
 *
 * Splitting them is safe because `stopPanelInteraction` is idempotent and does no layout work — it clears
 * the interaction ref, removes the listeners and releases pointer capture — so there is nothing to
 * throttle. `clampExistingChatPosition` is the part that reads geometry, and it keeps the throttle.
 */
const stopPanelInteractionOnViewportResize = () => stopPanelInteraction();

const clampChatPositionAfterResize = throttle(() => {
  clampExistingChatPosition();
}, 200);

const handleChatViewportResize = () => {
  stopComposerResize();
  clampComposerHeight();
  stopPanelInteractionOnViewportResize();
  clampChatPositionAfterResize();
};

const handlePanelWindowBlur = () => {
  stopComposerResize();
  stopPanelInteraction();
};

// ================= 文本处理辅助函数 =================

const convertLatexDelimiters = (text: string) => {
  const pattern = /(```[\S\s]*?```|`.*?`)|\\\[([\S\s]*?[^\\])\\]|\\\((.*?)\\\)/g;
  return text.replace(pattern, (match, codeBlock, squareBracket, roundBracket) => {
    if (codeBlock !== undefined) return codeBlock;
    if (squareBracket !== undefined) return `$$${squareBracket}$$`;
    if (roundBracket !== undefined) return `$${roundBracket}$`;
    return match;
  });
};

/**
 * 最终渲染内容的预处理（带 memoization）
 *
 * Cache prevents redundant string processing for the same content across renders.
 */
const processedContentCache = new Map<string, string>();
const getProcessedContent = (content: string) => {
  if (!content) return '';

  const cached = processedContentCache.get(content);
  if (cached !== undefined) return cached;

  const processed = convertLatexDelimiters(content);

  processedContentCache.set(content, processed);
  return processed;
};

const copyFullMessage = async (content: string) => {
  try {
    await navigator.clipboard.writeText(content);
    notifySuccess(t('app.chat.copied'));
  } catch (err) {
    notifyError(t('app.chat.copyFailed'));
  }
};

// ================= 交互逻辑 =================

const formatStreamError = (error: unknown) => {
  if (error instanceof ChatStreamError) {
    switch (error.kind) {
      case 'MISSING_BODY':
        return t('app.chat.missingResponseBody');
      case 'INCOMPLETE_STREAM':
        return t('app.chat.incompleteResponseStream');
      case 'INVALID_FRAME':
        return t('app.chat.invalidResponseStream');
      case 'HTTP_ERROR':
        if (error.status === 403) return t('app.chat.authorizationFailed');
        if (error.status === 401) return t('app.chat.authenticationExpired');
        if (error.reasonCode === 'CHAT_HISTORY_LIMIT_REACHED') {
          return t('app.chat.historyLimitReached');
        }
        if (error.reasonCode === 'CHAT_SESSION_BUSY') {
          return t('app.chat.sessionBusyOnSend');
        }
        if (error.reasonCode === 'USER_CHAT_OPERATION_BUSY') {
          return Number.isSafeInteger(error.limit) && (error.limit ?? 0) > 0
              ? t('app.chat.assistantOperationBusy', { limit: error.limit })
              : t('app.chat.assistantOperationBusyUnknown');
        }
        if (error.status === 429) return t('app.chat.assistantOperationBusyUnknown');
        return t('app.chat.httpRequestFailed', { status: error.status ?? '?' });
      case 'SERVER_FRAME':
        return localizedTextOrFallback(error.message, t('app.chat.serverStreamError'), locale.value);
      default:
        break;
    }
  }
  if (error instanceof TypeError) return t('app.chat.networkError');
  const message = error instanceof Error ? error.message : String(error || '');
  return localizedTextOrFallback(message, t('app.chat.requestFailed'), locale.value);
};

/**
 * The live recognizer, held so teardown can stop it.
 *
 * A function-local instance keeps the microphone open and keeps writing into `inputValue` after the
 * component is gone (logout, or navigating off the board), because nothing can reach it to abort.
 */
let activeRecognition: any = null;

const stopListening = () => {
  if (!activeRecognition) return;
  const recognition = activeRecognition;
  activeRecognition = null;
  // `abort` discards pending results rather than delivering them into a torn-down component; only
  // fall back to `stop` when the engine has no `abort`. Chaining these with `??` ran both, because
  // `abort()` returns undefined.
  try {
    if (typeof recognition.abort === 'function') recognition.abort();
    else recognition.stop?.();
  } catch { /* already ended */ }
  isRecording.value = false;
};

const startListening = () => {
  const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
  if (!SpeechRecognition) {
    notifyBlocked(t('app.chat.speechUnsupported'));
    return;
  }
  if (isRecording.value) return;

  try {
    const recognition = new SpeechRecognition();
    recognition.lang = locale.value === 'zh-CN' ? 'zh-CN' : 'en-US';
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;

    recognition.onstart = () => { isRecording.value = true; notifyInfo(t('app.chat.startSpeaking')); };
    recognition.onend = () => { activeRecognition = null; isRecording.value = false; };
    recognition.onerror = () => {
      activeRecognition = null;
      isRecording.value = false;
      notifyError(t('app.chat.speechError'));
    };
    recognition.onresult = (event: any) => {
      // A result that arrives after teardown has no field to write into.
      if (activeRecognition !== recognition) return;
      const transcript = event.results[0][0].transcript;
      if (transcript) inputValue.value += transcript;
    };
    activeRecognition = recognition;
    recognition.start();
  } catch (e) {
    activeRecognition = null;
    notifyError(t('app.chat.speechStartFailed'));
    isRecording.value = false;
  }
};



// 监听 visible 变化，执行相应逻辑
watch(visible, (newVal) => {
  if (!newVal) {
    stopComposerResize();
    stopPanelInteraction();
    return;
  }
  void initSessions().then(() => nextTick(() => acknowledgeCurrentVisibleHistory()));
  refreshBoardContext();
  const nextGeometry = chatPosition.value
      ? clampChatGeometry(
          chatPosition.value.left,
          chatPosition.value.top,
          chatPosition.value.width,
          chatPosition.value.height
      )
      : createDefaultChatGeometry();
  chatPosition.value = nextGeometry;
  isSidebarOpen.value = !props.boardMode && !isCompactChatWidth(nextGeometry.width) && window.innerWidth >= 1120;
  nextTick(clampExistingChatPosition);
});

watch(sessions, currentSessions => {
  chatStore.setActiveCount(currentSessions.filter(session => session.active).length);
  chatStore.setUnreadCount(currentSessions.filter(session => session.hasUnreadUpdate).length);
}, { deep: true });

const invokeSystemCommand = async (command: StreamCommand): Promise<boolean> => {
  if (!props.executeCommand) {
    console.warn('[Chat] No system-command executor is available');
    return false;
  }
  try {
    return await props.executeCommand(command);
  } catch (error) {
    console.error('[Chat] System command failed:', error);
    return false;
  }
};

const markReconciliationRequired = () => {
  if (!reconciliationRequired.value) {
    notifyError(t('app.chat.reconciliationFailed'));
  }
  reconciliationRequired.value = true;
};

const reconcileAuthoritativeState = async (
    announceSuccess = false
): Promise<boolean> => {
  if (reconciliationPromise) return reconciliationPromise;
  const reconciliationAuthEpoch = chatAuthEpoch;
  isRetryingReconciliation.value = true;
  let ownedReconciliation!: Promise<boolean>;
  ownedReconciliation = (async () => {
    try {
      const refreshed = await invokeSystemCommand({
        type: 'REFRESH_DATA',
        payload: { target: 'board_state' }
      });
      if (reconciliationAuthEpoch !== chatAuthEpoch) return false;
      if (!refreshed) {
        markReconciliationRequired();
        return false;
      }
      reconciliationRequired.value = false;
      if (announceSuccess) notifySuccess(t('app.chat.reconciliationSucceeded'));
      return true;
    } finally {
      if (reconciliationPromise === ownedReconciliation) {
        if (reconciliationAuthEpoch === chatAuthEpoch) {
          isRetryingReconciliation.value = false;
        }
        reconciliationPromise = null;
      }
    }
  })();
  reconciliationPromise = ownedReconciliation;
  return ownedReconciliation;
};

const executeStreamCommand = async (command: StreamCommand): Promise<boolean> => {
  const completed = await invokeSystemCommand(command);
  if (completed) {
    if (command.payload.assistantSummary || command.payload.assistantAction) {
      notifyInfo(command.payload.assistantSummary
        ?? t(`app.chat.assistantActions.${command.payload.assistantAction}`));
    }
    return true;
  }
  if (command.type === 'REFRESH_DATA' && command.payload?.target !== 'board_state') {
    const reconciled = await reconcileAuthoritativeState();
    if (reconciled) {
      notifyBlocked(t('app.chat.targetRefreshRecovered'));
      if (command.payload.assistantSummary || command.payload.assistantAction) {
        notifyInfo(command.payload.assistantSummary
          ?? t(`app.chat.assistantActions.${command.payload.assistantAction}`));
      }
      return true;
    }
  } else if (command.type === 'REFRESH_DATA') {
    markReconciliationRequired();
  }
  return false;
};

const retryAuthoritativeReconciliation = async () => {
  if (activeStreamSessionId.value) {
    const result = await settleActiveRequest(false);
    if (result === 'ready') notifySuccess(t('app.chat.reconciliationSucceeded'));
    return;
  }
  await reconcileAuthoritativeState(true);
};

const updateSessionActivity = (sessionId: string, active: boolean) => {
  if (!sessions.value.some(session => session.id === sessionId)) return;
  // Keep API snapshots immutable. A caller may reuse a response object while a background
  // poll or an account switch is still in flight; mutating it would leak activity into another
  // conversation or test fixture.
  sessions.value = sessions.value.map(session => session.id === sessionId
    ? { ...session, active }
    : session);
};

const isHttpNotFound = (error: any) => error?.response?.status === 404;

const clearAuthoritativelyDeletedSession = (sessionId: string) => {
  sessionListRequestEpoch += 1;
  sessions.value = sessions.value.filter(session => session.id !== sessionId);

  if (activeStreamSessionId.value === sessionId) {
    stopSessionActivityMonitor();
    activeStreamSessionId.value = '';
    activeStreamTurnId.value = '';
    activeStreamAuthToken.value = null;
    historyReplacementWithoutTerminalAllowed.value = false;
    explicitStopRegistrationPending.value = false;
    activeStreamRetryDraft.value = '';
  }

  if (currentSessionId.value !== sessionId) return;
  historyRequestEpoch += 1;
  historyAbortController.value?.abort();
  historyAbortController.value = null;
  confirmationRequestEpoch += 1;
  isLoadingHistory.value = false;
  historyLoadFailed.value = false;
  historyLoadDiagnostics.value = null;
  currentSessionId.value = '';
  messages.value = [];
  historyHasMore.value = false;
  historyNextBeforeId.value = null;
  pendingConfirmationActions.value = [];
  pendingConfirmationLoadFailed.value = false;
  clearPerformanceCaches();
};

const reportAuthoritativeSessionDeletion = (sessionId: string) => {
  clearAuthoritativelyDeletedSession(sessionId);
  notifyBlocked(t('app.chat.sessionRemovedExternally'));
};

const refreshPendingConfirmation = async (sessionId = currentSessionId.value, signal?: AbortSignal) => {
  const requestEpoch = ++confirmationRequestEpoch;
  if (!sessionId) {
    pendingConfirmationActions.value = [];
    pendingConfirmationLoadFailed.value = false;
    return;
  }
  try {
    const pending = await getPendingConfirmation(sessionId, signal);
    if (requestEpoch === confirmationRequestEpoch && currentSessionId.value === sessionId) {
      pendingConfirmationActions.value = pending.actions;
      pendingConfirmationLoadFailed.value = false;
    }
  } catch (error: any) {
    if (signal?.aborted || error?.name === 'CanceledError' || error?.code === 'ERR_CANCELED') return;
    if (requestEpoch === confirmationRequestEpoch && currentSessionId.value === sessionId) {
      pendingConfirmationLoadFailed.value = true;
    }
    console.warn('[Chat] Could not refresh pending confirmation state:', error);
  }
};

const waitForActivityPoll = (signal: AbortSignal) => new Promise<void>(resolve => {
  const timer = window.setTimeout(done, 1000);
  function done() {
    window.clearTimeout(timer);
    signal.removeEventListener('abort', done);
    resolve();
  }
  if (signal.aborted) {
    done();
    return;
  }
  signal.addEventListener('abort', done, { once: true });
});

const stopSessionActivityMonitor = () => {
  activityMonitorEpoch += 1;
  activityMonitorAbortController?.abort();
  activityMonitorAbortController = null;
  isMonitoringRemoteExecution.value = false;
  remoteActivityCheckFailed.value = false;
};

const monitorSessionActivity = (sessionId: string, knownActive = false) => {
  if (!sessionId || (isStreaming.value && activeStreamSessionId.value === sessionId)) return;
  stopSessionActivityMonitor();
  const monitorEpoch = ++activityMonitorEpoch;
  const controller = new AbortController();
  activityMonitorAbortController = controller;
  if (knownActive) {
    activeStreamSessionId.value = sessionId;
    activeStreamTurnId.value = '';
    activeStreamAuthToken.value = scopedAuthToken.value;
    historyReplacementWithoutTerminalAllowed.value = true;
    activeStreamRetryDraft.value = '';
    isMonitoringRemoteExecution.value = true;
    updateSessionActivity(sessionId, true);
  }

  void (async () => {
    let consecutiveFailures = 0;
    while (!controller.signal.aborted && monitorEpoch === activityMonitorEpoch) {
      try {
        const activity = await getSessionActivity(sessionId, { signal: controller.signal });
        if (controller.signal.aborted || monitorEpoch !== activityMonitorEpoch) return;
        updateSessionActivity(sessionId, activity.active);
        if (!activity.active) {
          if (isMonitoringRemoteExecution.value && activeStreamSessionId.value === sessionId) {
            await settleActiveRequest(false);
          }
          return;
        }
        if (currentSessionId.value !== sessionId) return;
        consecutiveFailures = 0;
        remoteActivityCheckFailed.value = false;
        activeStreamSessionId.value = sessionId;
        activeStreamTurnId.value = '';
        activeStreamAuthToken.value = scopedAuthToken.value;
        historyReplacementWithoutTerminalAllowed.value = true;
        activeStreamRetryDraft.value = '';
        isMonitoringRemoteExecution.value = true;
      } catch (error: any) {
        if (controller.signal.aborted || monitorEpoch !== activityMonitorEpoch) return;
        if (error?.response?.status === 404) {
          updateSessionActivity(sessionId, false);
          if (activeStreamSessionId.value === sessionId) await settleActiveRequest(false);
          return;
        }
        consecutiveFailures += 1;
        if (consecutiveFailures >= 3) remoteActivityCheckFailed.value = true;
        console.warn('[Chat] Could not refresh authoritative session activity:', error);
      }
      await waitForActivityPoll(controller.signal);
    }
  })();
};

const stopActiveSessionsMonitor = () => {
  activeSessionsMonitorEpoch += 1;
  if (activeSessionsPollTimer) {
    clearTimeout(activeSessionsPollTimer);
    activeSessionsPollTimer = null;
  }
};

const scheduleActiveSessionsPoll = () => {
  if (!chatViewMounted || activeSessionsPollTimer) return;
  activeSessionsPollTimer = setTimeout(() => {
    activeSessionsPollTimer = null;
    void pollActiveSessions();
  }, hasAuthoritativeActiveSession.value ? 1000 : 5000);
};

type SessionCompletionObservation = Pick<ChatSession, 'active' | 'latestTerminalMessageId'>;

const captureSessionCompletionObservations = (
    availableSessions: ChatSessionObservationBaseline['sessions']
) => new Map(
    availableSessions.map(session => [session.id, {
      active: session.active,
      latestTerminalMessageId: session.latestTerminalMessageId
    }]));

const sessionCompletionBaseline = () => {
  const handoff = !sessionCompletionObservationsInitialized
      && props.initialSessionObservations?.userId === authenticatedUserId.value
      ? props.initialSessionObservations : null;
  return {
    previous: captureSessionCompletionObservations(handoff?.sessions ?? sessions.value),
    compareTerminalMessages: sessionCompletionObservationsInitialized || handoff !== null,
    handoff
  };
};

const completedBackgroundSessions = (
    previous: Map<string, SessionCompletionObservation>,
    nextSessions: ChatSession[],
    compareTerminalMessages: boolean
) => nextSessions.filter(session => {
  const prior = previous.get(session.id);
  const observedNewTerminal = compareTerminalMessages
      && session.latestTerminalMessageId !== null
      && session.latestTerminalMessageId !== prior?.latestTerminalMessageId;
  const observedActiveSettlement = prior?.active === true && !session.active;
  return (observedNewTerminal || observedActiveSettlement)
      && session.id !== activeStreamSessionId.value;
});

const observedRemovedActiveSession = (
    previous: Map<string, SessionCompletionObservation>, nextSessions: ChatSession[]
) => Array.from(previous).some(([id, observation]) => observation.active
    && id !== activeStreamSessionId.value && !nextSessions.some(session => session.id === id));

const reconcileCompletedBackgroundSessions = async (
    completedSessions: ChatSession[],
    authEpoch: number,
    handoff: ChatSessionObservationBaseline | null = null,
    removedActiveSession = false
) => {
  if (handoff?.reconciliation) {
    try {
      await handoff.reconciliation;
    } catch (error) {
      console.warn('[Chat] The pre-mount assistant reconciliation did not complete:', error);
    }
  }
  if (!chatViewMounted || authEpoch !== chatAuthEpoch) return;
  if (completedSessions.length === 0) {
    if (removedActiveSession || (handoff && reconciliationRequired.value)) await reconcileAuthoritativeState();
    return;
  }
  const reconciled = await reconcileAuthoritativeState();
  if (!chatViewMounted || authEpoch !== chatAuthEpoch) return;
  completedSessions.forEach(session => {
    notifyInfo(t(reconciled
      ? 'app.chat.backgroundSessionCompleted'
      : 'app.chat.backgroundSessionCompletedSyncPending', {
      title: session.title || t('app.chat.newChat')
    }));
  });
};

const pollActiveSessions = async () => {
  if (isLoadingSessions.value) {
    scheduleActiveSessionsPoll();
    return;
  }
  if (activeSessionsPollInFlight) return;
  activeSessionsPollInFlight = true;
  const pollEpoch = activeSessionsMonitorEpoch;
  const authEpoch = chatAuthEpoch;
  const sessionListEpoch = sessionListRequestEpoch;
  const baseline = sessionCompletionBaseline();
  try {
    const res = await getSessionList();
    if (authEpoch !== chatAuthEpoch || pollEpoch !== activeSessionsMonitorEpoch
        || sessionListEpoch !== sessionListRequestEpoch
        || !Array.isArray(res)) return;
    const ownedSessions = authenticatedUserId.value === null
        ? res
        : res.filter(session => session.userId === authenticatedUserId.value);
    sessions.value = ownedSessions;
    sessionCompletionObservationsInitialized = true;
    await reconcileCompletedBackgroundSessions(
        completedBackgroundSessions(baseline.previous, ownedSessions, baseline.compareTerminalMessages),
        authEpoch, baseline.handoff, observedRemovedActiveSession(baseline.previous, ownedSessions));
  } catch (error) {
    if (authEpoch === chatAuthEpoch && pollEpoch === activeSessionsMonitorEpoch
        && sessionListEpoch === sessionListRequestEpoch) {
      console.warn('[Chat] Could not refresh active chat sessions:', error);
    }
  } finally {
    activeSessionsPollInFlight = false;
    // A stale request can finish after a monitor stop/restart. Re-arm from current state so the
    // replacement monitor is not lost merely because its first timer fired while this request ran.
    if (chatViewMounted) scheduleActiveSessionsPoll();
  }
};

watch(hasAuthoritativeActiveSession, () => {
  stopActiveSessionsMonitor();
  scheduleActiveSessionsPoll();
}, { immediate: true });

const reattachAuthoritativeActiveSession = (availableSessions: ChatSession[]) => {
  const activeSession = availableSessions.find(session =>
    session.id === currentSessionId.value && session.active)
      ?? (!currentSessionId.value ? availableSessions.find(session => session.active) : undefined);
  if (!activeSession) return;

  if (currentSessionId.value === activeSession.id) {
    monitorSessionActivity(activeSession.id, true);
    return;
  }
  void handleSelectSession(activeSession.id, true);
};

const initSessions = async (reattachActive = true): Promise<boolean> => {
  const requestEpoch = ++sessionListRequestEpoch;
  const authEpoch = chatAuthEpoch;
  const baseline = sessionCompletionBaseline();
  isLoadingSessions.value = true;
  sessionListLoadFailed.value = false;
  try {
    const res = await getSessionList();
    if (!Array.isArray(res)) throw new Error('Session list response is not an array');
    const ownedSessions = authenticatedUserId.value === null
        ? res
        : res.filter(session => session.userId === authenticatedUserId.value);
    if (requestEpoch === sessionListRequestEpoch) {
      sessionCompletionObservationsInitialized = true;
      sessions.value = ownedSessions;
      if (reattachActive && !isStreaming.value && !activeStreamSessionId.value) {
        reattachAuthoritativeActiveSession(ownedSessions);
      }
      await reconcileCompletedBackgroundSessions(
          completedBackgroundSessions(baseline.previous, ownedSessions, baseline.compareTerminalMessages),
          authEpoch, baseline.handoff, observedRemovedActiveSession(baseline.previous, ownedSessions));
      return true;
    }
    return false;
  } catch (e) {
    if (requestEpoch === sessionListRequestEpoch) sessionListLoadFailed.value = true;
    console.error('Failed to load conversation list:', e);
    return false;
  } finally {
    if (requestEpoch === sessionListRequestEpoch) isLoadingSessions.value = false;
  }
};

const handleCreateSession = async (signal?: AbortSignal, notifyOnFailure = true) => {
  const authEpoch = chatAuthEpoch;
  try {
    const session = await createSession(signal);
    if (authEpoch !== chatAuthEpoch) return '';
    if (session && session.id) {
      sessionListRequestEpoch += 1;
      isLoadingSessions.value = false;
      sessionListLoadFailed.value = false;
      sessions.value = [session, ...sessions.value.filter(item => item.id !== session.id)];
      return session.id;
    }
    throw new Error('Chat session response is incomplete');
  } catch (e) {
    if (authEpoch !== chatAuthEpoch) return '';
    console.error('Failed to create conversation:', e);
    if (notifyOnFailure) notifyError(t('app.chat.createSessionFailed'));
    return null;
  }
};

const onNewChatClick = async () => {
  if (isCreatingSession.value || isPreparingStreamSession.value || isSettlingStream.value) return;
  const navigationEpoch = ++userSessionNavigationEpoch;
  isCreatingSession.value = true;
  try {
    const newId = await handleCreateSession();
    if (!newId) return;
    // A session click made while creation was in flight is the user's newer navigation intent.
    if (navigationEpoch !== userSessionNavigationEpoch) return;
    await handleSelectSession(newId);
    // 新建对话后自动收起侧边栏（移动端友好）
    if (window.innerWidth < 960 || isChatPanelCompact.value) isSidebarOpen.value = false;
  } finally {
    isCreatingSession.value = false;
  }
};

const handleUserSelectSession = (sessionId: string, knownActive = false) => {
  userSessionNavigationEpoch += 1;
  return handleSelectSession(sessionId, knownActive);
};

const handleDelete = async (sessionId: string) => {
  const confirmed = await confirmDestructive({
    title: t('app.chat.deleteSessionTitle'),
    message: t('app.chat.deleteSessionMessage'),
    confirmText: t('app.delete')
  });
  if (!confirmed) return;

  try {
    await deleteSession(sessionId);
    clearAuthoritativelyDeletedSession(sessionId);
    notifySuccess(t('app.chat.sessionDeleted'));
  } catch (error: any) {
    const reasonCode = error?.response?.data?.data?.reasonCode;
    notifyError(reasonCode === 'CHAT_SESSION_BUSY'
        ? t('app.chat.sessionStillRunning')
        : t('app.chat.deleteFailed'));
  }
};

const handleSelectSession = async (sessionId: string, knownActive = false) => {
  if (isPreparingStreamSession.value || isSettlingStream.value) return;
  if (currentSessionId.value === sessionId && !historyLoadFailed.value) {
    void acknowledgeCurrentVisibleHistory();
    if (!isAssistantBusy.value) {
      monitorSessionActivity(sessionId, knownActive);
      void refreshPendingConfirmation(sessionId);
    }
    return;
  }

  // Navigation detaches the live transport only. The durable execution remains owned by its
  // session and is reflected by the sidebar activity indicator until the server settles it.
  detachCurrentExecutionForNavigation();
  historyRequestEpoch += 1;
  historyAbortController.value?.abort();

  currentSessionId.value = sessionId;
  messages.value = [];
  historyHasMore.value = false;
  historyNextBeforeId.value = null;
  pendingConfirmationActions.value = [];
  pendingConfirmationLoadFailed.value = false;
  historyLoadFailed.value = false;
  historyLoadDiagnostics.value = null;
  clearPerformanceCaches();
  isLoadingHistory.value = true;
  const requestEpoch = ++historyRequestEpoch;
  const controller = new AbortController();
  historyAbortController.value = controller;
  if (knownActive) monitorSessionActivity(sessionId, true);

  // 移动端体验优化：选中后收起侧边栏
  if (window.innerWidth < 960 || isChatPanelCompact.value) isSidebarOpen.value = false;

  try {
    const historyPage = await getSessionHistory(sessionId, controller.signal);
    if (requestEpoch !== historyRequestEpoch || currentSessionId.value !== sessionId) return;
    applyHistoryPage(historyPage, false);
    historyLoadFailed.value = false;
    historyLoadDiagnostics.value = null;
    await refreshPendingConfirmation(sessionId, controller.signal);
    scrollToBottom(true);
    if (!knownActive) monitorSessionActivity(sessionId);
  } catch (e: any) {
    if (requestEpoch !== historyRequestEpoch) return;
    if (e?.name !== 'CanceledError' && e?.code !== 'ERR_CANCELED') {
      historyLoadFailed.value = true;
      historyLoadDiagnostics.value = errorDetails(e);
    }
  } finally {
    if (requestEpoch === historyRequestEpoch) {
      isLoadingHistory.value = false;
      if (historyAbortController.value === controller) historyAbortController.value = null;
    }
  }
};

const acknowledgeVisibleTerminal = async (sessionId: string, history: ChatMessage[]) => {
  if (!visible.value || document.hidden || currentSessionId.value !== sessionId) return;
  const terminal = [...history].reverse().find(message =>
    message.role === 'assistant'
    && Number.isSafeInteger(message.id)
    && (message.id ?? 0) > 0
    && Boolean(message.executionStatus));
  if (!terminal?.id || !terminal.executionStatus) return;
  const terminalMessageId = terminal.id;
  if ((terminalSeenRequests.get(sessionId) ?? 0) >= terminalMessageId) return;

  const authEpoch = chatAuthEpoch;
  terminalSeenRequests.set(sessionId, terminalMessageId);
  try {
    await markSessionTerminalSeen(sessionId, terminalMessageId);
    if (authEpoch !== chatAuthEpoch) return;
    if (sessions.value.some(session => session.id === sessionId && session.hasUnreadUpdate)) {
      await initSessions(false);
    }
  } catch (error) {
    if (terminalSeenRequests.get(sessionId) === terminalMessageId) {
      terminalSeenRequests.delete(sessionId);
    }
    console.warn(`Could not acknowledge visible assistant result for session ${sessionId}:`, error);
  }
};

const acknowledgeCurrentVisibleHistory = () => {
  const sessionId = currentSessionId.value;
  if (sessionId) void acknowledgeVisibleTerminal(sessionId, messages.value);
};

const applyHistoryPage = (page: ChatHistoryPage, prepend: boolean) => {
  if (prepend) {
    const existingIds = new Set(messages.value.map(message => message.id).filter(id => id != null));
    messages.value = [...page.messages.filter(message => message.id == null || !existingIds.has(message.id)), ...messages.value];
  } else {
    // Optimistic turns append to the local collection, never to the authoritative response.
    messages.value = [...page.messages];
    const sessionId = currentSessionId.value;
    if (sessionId) void nextTick(() => acknowledgeVisibleTerminal(sessionId, page.messages));
  }
  historyHasMore.value = page.hasMore && page.nextBeforeId != null;
  historyNextBeforeId.value = page.nextBeforeId;
};

const loadOlderHistory = async () => {
  const sessionId = currentSessionId.value;
  const beforeId = historyNextBeforeId.value;
  if (!sessionId || beforeId == null || !historyHasMore.value || isLoadingOlderHistory.value) return;
  isLoadingOlderHistory.value = true;
  const viewport = scrollRef.value;
  const previousHeight = viewport?.scrollHeight ?? 0;
  const previousTop = viewport?.scrollTop ?? 0;
  try {
    const page = await getSessionHistory(sessionId, { beforeId, limit: 50 });
    if (currentSessionId.value !== sessionId) return;
    applyHistoryPage(page, true);
    await nextTick();
    if (viewport) viewport.scrollTop = viewport.scrollHeight - previousHeight + previousTop;
  } catch (error) {
    console.warn('[Chat] Failed to load older history:', error);
    notifyBlocked(t('app.chat.historyLoadOlderFailed'));
  } finally {
    isLoadingOlderHistory.value = false;
  }
};

const markActiveAssistantAsResponseStopped = () => {
  const lastMessage = messages.value[messages.value.length - 1];
  const notice = t('app.chat.responseStoppedReconcile');
  if (lastMessage?.role === 'assistant') {
    lastMessage.content = lastMessage.content?.trim()
        ? `${lastMessage.content}\n\n> ${notice}`
        : notice;
  } else {
    messages.value.push({ role: 'assistant', content: notice });
  }
  scrollToBottom(false);
};

const hasTerminalAssistantRecord = (history: ChatMessage[], turnId?: string) => {
  if (turnId) {
    return history.some(message =>
      message.role === 'assistant'
      && message.turnId === turnId
      && Boolean(message.executionStatus));
  }
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const message = history[index];
    if (message.role === 'assistant' && message.executionStatus) return true;
    if (message.role === 'user') return false;
  }
  return false;
};

const restoreRetryDraftIfTurnWasNotAdmitted = (history: ChatMessage[], turnId: string) => {
  if (!activeStreamRetryDraft.value || !turnId) return;
  const userTurnWasPersisted = history.some(message =>
    message.role === 'user' && message.turnId === turnId);
  if (!userTurnWasPersisted && !inputValue.value) {
    inputValue.value = activeStreamRetryDraft.value;
  }
};

const detachActiveTransport = (showCancelledMessage = false) => {
  if (streamElapsedTimer) {
    clearInterval(streamElapsedTimer);
    streamElapsedTimer = null;
  }
  const controller = abortController.value;
  if (!controller) return null;
  streamRequestEpoch += 1;
  if (abortController.value === controller) abortController.value = null;
  isStreaming.value = false;
  if (showCancelledMessage) {
    markActiveAssistantAsResponseStopped();
  }
  return controller;
};

const abortActiveTransport = (showCancelledMessage = false) => {
  detachActiveTransport(showCancelledMessage)?.abort();
};

const detachCurrentExecutionForNavigation = () => {
  const detachedSessionId = activeStreamSessionId.value;
  stopSessionActivityMonitor();
  abortActiveTransport(false);
  isStreaming.value = false;
  streamProgress.value = null;
  streamProgressEvents.value = [];
  streamElapsedSeconds.value = 0;
  if (detachedSessionId) updateSessionActivity(detachedSessionId, true);
  activeStreamSessionId.value = '';
  activeStreamTurnId.value = '';
  activeStreamAuthToken.value = null;
  historyReplacementWithoutTerminalAllowed.value = false;
  explicitStopRegistrationPending.value = false;
  activeStreamRetryDraft.value = '';
};

const requestOwnedSessionStop = (sessionId: string, turnId?: string): Promise<void> => {
  const authToken = activeStreamAuthToken.value || scopedAuthToken.value;
  return authToken
      ? requestSessionStop(sessionId, turnId, authToken)
      : requestSessionStop(sessionId, turnId);
};

const resetChatForAuthSubjectChange = (previousAuthToken: string | null) => {
  const sessionId = activeStreamSessionId.value;
  const activeSessionIds = Array.from(new Set([
    ...sessions.value.filter(session => session.active).map(session => session.id),
    ...(sessionId ? [sessionId] : [])
  ]));
  const stopAlreadyPending = explicitStopRegistrationPending.value && Boolean(settlementPromise);
  if (previousAuthToken) {
    const turnId = activeStreamTurnId.value;
    const ownerToken = activeStreamAuthToken.value || previousAuthToken;
    activeSessionIds.forEach(activeSessionId => {
      if (activeSessionId === sessionId && stopAlreadyPending) return;
      void requestSessionStop(
          activeSessionId,
          activeSessionId === sessionId ? turnId || undefined : undefined,
          ownerToken
      ).catch(error => {
        console.warn(`[Chat] Failed to stop session ${activeSessionId} during an account change:`, error);
      });
    });
  }
  settlementDetachedTransport?.abort();
  settlementDetachedTransport = null;
  chatAuthEpoch += 1;
  streamRequestEpoch += 1;
  sessionListRequestEpoch += 1;
  historyRequestEpoch += 1;
  confirmationRequestEpoch += 1;
  abortActiveTransport(false);
  stopSessionActivityMonitor();
  stopActiveSessionsMonitor();
  settlementAbortController?.abort();
  settlementAbortController = null;
  settlementPromise = null;
  reconciliationPromise = null;
  historyAbortController.value?.abort();
  historyAbortController.value = null;

  sessions.value = [];
  sessionCompletionObservationsInitialized = false;
  terminalSeenRequests.clear();
  currentSessionId.value = '';
  messages.value = [];
  // The fallback key is positional (`index:<n>`), so a collapse recorded for one account's last row
  // would apply to an unrelated message at the same index after the subject changes.
  collapsedExecutionTraces.value = new Set();
  clearPerformanceCaches();
  inputValue.value = '';
  pendingConfirmationActions.value = [];
  pendingConfirmationLoadFailed.value = false;
  activeStreamSessionId.value = '';
  activeStreamTurnId.value = '';
  activeStreamAuthToken.value = null;
  historyReplacementWithoutTerminalAllowed.value = false;
  explicitStopRegistrationPending.value = false;
  activeStreamRetryDraft.value = '';
  isStreaming.value = false;
  isSettlingStream.value = false;
  isMonitoringRemoteExecution.value = false;
  reconciliationRequired.value = false;
  isRetryingReconciliation.value = false;
  isLoadingSessions.value = false;
  sessionListLoadFailed.value = false;
  isCreatingSession.value = false;
  isPreparingStreamSession.value = false;
  isLoadingHistory.value = false;
  historyLoadFailed.value = false;
  historyLoadDiagnostics.value = null;
  isLoadingOlderHistory.value = false;
  historyHasMore.value = false;
  historyNextBeforeId.value = null;
  streamProgress.value = null;
  streamProgressEvents.value = [];
  streamElapsedSeconds.value = 0;
  boardContext.value = null;
  chatStore.setActiveCount(0);
  chatStore.setStreaming(false);
};

const waitForSessionIdle = async (sessionId: string, signal?: AbortSignal): Promise<boolean> => {
  const requestAuthEpoch = chatAuthEpoch;
  const ownsSession = () => chatViewMounted && requestAuthEpoch === chatAuthEpoch;
  const deadline = Date.now() + 10_000;
  let consecutiveFailures = 0;
  while (Date.now() < deadline) {
    if (!ownsSession()) return false;
    try {
      const activity = await getSessionActivity(sessionId, { signal });
      if (!ownsSession()) return false;
      if (!activity.active) return true;
      consecutiveFailures = 0;
    } catch (error: any) {
      if (!ownsSession()) return false;
      if (error?.response?.status === 404) return true;
      consecutiveFailures += 1;
      if (consecutiveFailures >= 3) throw error;
    }
    await new Promise(resolve => window.setTimeout(resolve, 500));
  }
  return false;
};

const settleActiveRequest = (
    showCancelledMessage = false
): Promise<ChatLogoutPreparation> => {
  if (settlementPromise) return settlementPromise;
  const sessionId = activeStreamSessionId.value || currentSessionId.value;
  const turnId = activeStreamTurnId.value;
  const wasMonitoringRemoteExecution = isMonitoringRemoteExecution.value
      && activeStreamSessionId.value === sessionId;
  const stopRequested = showCancelledMessage && Boolean(activeStreamSessionId.value || currentSessionId.value);
  if (stopRequested) explicitStopRegistrationPending.value = true;
  const mustRegisterExplicitStop = explicitStopRegistrationPending.value;
  if (stopRequested || wasMonitoringRemoteExecution) {
    historyReplacementWithoutTerminalAllowed.value = true;
  }
  const replaceHistoryWithoutTerminal = historyReplacementWithoutTerminalAllowed.value;
  if (!abortController.value && !activeStreamSessionId.value) {
    if (!reconciliationRequired.value) return Promise.resolve('ready');
    return reconcileAuthoritativeState().then(
        reconciled => reconciled ? 'ready' : 'reconciliation-failed');
  }
  if (wasMonitoringRemoteExecution) stopSessionActivityMonitor();

  let detachedController: AbortController | null = null;
  if (stopRequested && sessionId) {
    // Invalidate the original send flow before the stop endpoint closes SSE; otherwise it
    // can race into its normal-completion reconciliation path.
    detachedController = detachActiveTransport(showCancelledMessage);
    settlementDetachedTransport = detachedController;
  } else {
    abortActiveTransport(showCancelledMessage && Boolean(sessionId));
  }
  if (!sessionId) {
    if (turnId) {
      messages.value = messages.value.filter(message => message.turnId !== turnId);
      if (activeStreamRetryDraft.value && !inputValue.value) {
        inputValue.value = activeStreamRetryDraft.value;
      }
      activeStreamTurnId.value = '';
      activeStreamAuthToken.value = null;
      historyReplacementWithoutTerminalAllowed.value = false;
      explicitStopRegistrationPending.value = false;
      activeStreamRetryDraft.value = '';
    }
    return initSessions(false).then(() => 'ready');
  }
  isSettlingStream.value = true;
  const settlementAuthEpoch = chatAuthEpoch;
  const settlementContextIsCurrent = () => settlementAuthEpoch === chatAuthEpoch;
  const controller = new AbortController();
  settlementAbortController = controller;
  let ownedSettlement!: Promise<ChatLogoutPreparation>;
  ownedSettlement = (async () => {
    let outcome: ChatLogoutPreparation = 'ready';
    let retainActiveSession = false;
    try {
      if (mustRegisterExplicitStop) {
        try {
          await requestOwnedSessionStop(sessionId, turnId || undefined);
          if (!settlementContextIsCurrent()) return 'ready';
          explicitStopRegistrationPending.value = false;
        } catch (error) {
          if (!settlementContextIsCurrent()) return 'ready';
          if (isHttpNotFound(error)) {
            explicitStopRegistrationPending.value = false;
            reportAuthoritativeSessionDeletion(sessionId);
            return 'ready';
          }
          outcome = 'outcome-unknown';
          retainActiveSession = true;
          reconciliationRequired.value = true;
          console.warn('[Chat] Failed to register explicit stop before aborting transport:', error);
          notifyBlocked(t('app.chat.stopOutcomeUnknown'));
          return outcome;
        } finally {
          detachedController?.abort();
          if (settlementDetachedTransport === detachedController) {
            settlementDetachedTransport = null;
          }
        }
      }
      try {
        const idle = await waitForSessionIdle(sessionId, controller.signal);
        if (!settlementContextIsCurrent()) return 'ready';
        if (!idle) {
          retainActiveSession = true;
          if (wasMonitoringRemoteExecution) {
            monitorSessionActivity(sessionId, true);
            notifyBlocked(t('app.chat.sessionStillRunningRetry'));
            return 'reconciliation-failed';
          }
          reconciliationRequired.value = true;
          notifyBlocked(t('app.chat.sessionStillRunningRetry'));
          return 'reconciliation-failed';
        }
      } catch (error) {
        if (!settlementContextIsCurrent()) return 'ready';
        if (controller.signal.aborted) return 'reconciliation-failed';
        outcome = 'outcome-unknown';
        retainActiveSession = true;
        reconciliationRequired.value = true;
        console.error('[Chat] Failed to confirm that the stopped request is idle:', error);
      }

      const reconciled = await reconcileAuthoritativeState();
      if (!settlementContextIsCurrent()) return 'ready';

      try {
        if (currentSessionId.value === sessionId) {
          historyRequestEpoch += 1;
          historyAbortController.value?.abort();
          historyAbortController.value = null;
          isLoadingHistory.value = false;
          const settlementHistoryEpoch = historyRequestEpoch;
          const history = await getSessionHistory(sessionId, controller.signal);
          if (!settlementContextIsCurrent()) return 'ready';
          if (settlementHistoryEpoch !== historyRequestEpoch
              || currentSessionId.value !== sessionId) {
            return reconciled ? outcome : 'reconciliation-failed';
          }
          const hasTerminal = hasTerminalAssistantRecord(history.messages, turnId);
          if (hasTerminal || replaceHistoryWithoutTerminal) {
            applyHistoryPage(history, false);
            historyLoadFailed.value = false;
            historyLoadDiagnostics.value = null;
            if (replaceHistoryWithoutTerminal && !hasTerminal) {
              restoreRetryDraftIfTurnWasNotAdmitted(history.messages, turnId);
              notifyBlocked(t('app.chat.incompleteStreamHistoryRestored'));
            }
          } else {
            notifyBlocked(t('app.chat.historyTerminalRecordMissing'));
            retainActiveSession = true;
            reconciliationRequired.value = true;
            return 'reconciliation-failed';
          }
        }
        await initSessions(false);
        if (!settlementContextIsCurrent()) return 'ready';
        await refreshPendingConfirmation(sessionId);
        if (!settlementContextIsCurrent()) return 'ready';
      } catch (error) {
        if (!settlementContextIsCurrent()) return 'ready';
        if (isHttpNotFound(error)) {
          reportAuthoritativeSessionDeletion(sessionId);
          return reconciled ? 'ready' : 'reconciliation-failed';
        }
        console.error('[Chat] Failed to reload chat history after settlement:', error);
        notifyBlocked(t('app.chat.historyReloadAfterSettleFailed'));
        retainActiveSession = true;
        reconciliationRequired.value = true;
        return 'reconciliation-failed';
      }
      if (!reconciled) return 'reconciliation-failed';
      if (outcome === 'outcome-unknown') {
        reconciliationRequired.value = true;
        notifyBlocked(t('app.chat.stopOutcomeUnknown'));
      }
      return outcome;
    } finally {
      const ownsSettlement = settlementPromise === ownedSettlement;
      const contextIsCurrent = settlementContextIsCurrent();
      if (ownsSettlement && contextIsCurrent) {
        if (!retainActiveSession && activeStreamSessionId.value === sessionId) {
          activeStreamSessionId.value = '';
          activeStreamTurnId.value = '';
          activeStreamAuthToken.value = null;
          historyReplacementWithoutTerminalAllowed.value = false;
          explicitStopRegistrationPending.value = false;
          activeStreamRetryDraft.value = '';
          updateSessionActivity(sessionId, false);
        }
        isSettlingStream.value = false;
      }
      if (settlementAbortController === controller) settlementAbortController = null;
      if (ownsSettlement) settlementPromise = null;
      if (ownsSettlement && contextIsCurrent
          && !retainActiveSession && !activeStreamSessionId.value) {
        reattachAuthoritativeActiveSession(sessions.value);
      }
    }
  })();
  settlementPromise = ownedSettlement;
  return ownedSettlement;
};

const handleStop = async () => {
  await settleActiveRequest(true);
};

const prepareForLogout = async (): Promise<ChatLogoutPreparation> => {
  const requestAuthEpoch = chatAuthEpoch;
  const requestToken = authState.token;
  const ownsPreparation = () => chatViewMounted && requestAuthEpoch === chatAuthEpoch
    && requestToken === authState.token;
  let outcome: ChatLogoutPreparation = await initSessions(false)
      ? 'ready'
      : 'outcome-unknown';
  if (!ownsPreparation()) return 'outcome-unknown';
  const attachedSessionId = activeStreamSessionId.value;
  const backgroundSessionIds = Array.from(new Set(
      sessions.value
          .filter(session => session.active && session.id !== attachedSessionId)
          .map(session => session.id)));
  const stopConfirmedSessionIds: string[] = [];
  const ownerToken = requestToken;

  for (const sessionId of backgroundSessionIds) {
    try {
      await (ownerToken
          ? requestSessionStop(sessionId, undefined, ownerToken)
          : requestSessionStop(sessionId));
      if (!ownsPreparation()) return 'outcome-unknown';
      stopConfirmedSessionIds.push(sessionId);
    } catch (error) {
      if (!ownsPreparation()) return 'outcome-unknown';
      if (isHttpNotFound(error)) continue;
      console.warn(`[Chat] Failed to stop background session ${sessionId} before logout:`, error);
      outcome = 'outcome-unknown';
    }
  }

  if (attachedSessionId) {
    const attachedOutcome = await settleActiveRequest(true);
    if (!ownsPreparation()) return 'outcome-unknown';
    if (attachedOutcome === 'reconciliation-failed') return attachedOutcome;
    if (attachedOutcome === 'outcome-unknown') outcome = attachedOutcome;
  }

  for (const sessionId of stopConfirmedSessionIds) {
    try {
      const idle = await waitForSessionIdle(sessionId);
      if (!ownsPreparation()) return 'outcome-unknown';
      if (!idle) return 'reconciliation-failed';
      updateSessionActivity(sessionId, false);
    } catch (error) {
      if (!ownsPreparation()) return 'outcome-unknown';
      console.warn(`[Chat] Could not confirm background session ${sessionId} stopped before logout:`, error);
      outcome = 'outcome-unknown';
    }
  }

  if (backgroundSessionIds.length > 0 || reconciliationRequired.value) {
    const reconciled = await reconcileAuthoritativeState();
    if (!ownsPreparation()) return 'outcome-unknown';
    if (!reconciled) return 'reconciliation-failed';
    await initSessions(false);
    if (!ownsPreparation()) return 'outcome-unknown';
  }
  return outcome;
};

defineExpose({ prepareForLogout });

const handleDocumentVisibilityChange = () => {
  if (document.hidden) {
    stopPanelInteraction();
    stopComposerResize();
    return;
  }
  if (!document.hidden && visible.value) void initSessions();
};

const handlePanelWindowFocus = () => {
  if (visible.value) void initSessions();
};

watch(authenticatedUserId, (nextUserId, previousUserId) => {
  if (nextUserId === previousUserId) return;
  const previousAuthToken = scopedAuthToken.value;
  resetChatForAuthSubjectChange(previousAuthToken);
  scopedAuthToken.value = authState.token;
  scopedAuthUserId = nextUserId;
  if (nextUserId !== null && visible.value) {
    refreshBoardContext();
    void initSessions();
  }
}, { flush: 'sync' });

watch(() => authState.token, nextToken => {
  if (authenticatedUserId.value !== scopedAuthUserId) return;
  scopedAuthToken.value = nextToken;
  if (activeStreamAuthToken.value) activeStreamAuthToken.value = nextToken;
}, { flush: 'post' });

onMounted(() => {
  chatViewMounted = true;
  if (visible.value) {
    refreshBoardContext();
    void initSessions();
    nextTick(clampExistingChatPosition);
  }
  scheduleActiveSessionsPoll();
  window.addEventListener('resize', handleChatViewportResize);
  window.addEventListener('blur', handlePanelWindowBlur);
  window.addEventListener('focus', handlePanelWindowFocus);
  document.addEventListener('visibilitychange', handleDocumentVisibilityChange);
});

onUnmounted(() => {
  chatViewMounted = false;
  abortActiveTransport(false);
  stopListening();
  stopSessionActivityMonitor();
  stopActiveSessionsMonitor();
  settlementAbortController?.abort();
  settlementAbortController = null;
  settlementDetachedTransport?.abort();
  settlementDetachedTransport = null;
  chatStore.setStreaming(false);
  if (streamElapsedTimer) clearInterval(streamElapsedTimer);
  streamElapsedTimer = null;
  historyRequestEpoch += 1;
  historyAbortController.value?.abort();
  historyAbortController.value = null;
  isLoadingHistory.value = false;
  stopPanelInteraction();
  stopComposerResize();
  window.removeEventListener('resize', handleChatViewportResize);
  window.removeEventListener('blur', handlePanelWindowBlur);
  window.removeEventListener('focus', handlePanelWindowFocus);
  document.removeEventListener('visibilitychange', handleDocumentVisibilityChange);
});

const submitChatTurn = async (content: string, confirmation?: ChatConfirmationCommand) => {
  if (props.interactionLocked) {
    notifyBlocked(t('app.chat.boardInteractionLocked'));
    return;
  }
  if (!content || isLoading.value) return;
  if (content.length > REQUEST_LIMITS.chatContentCharacters) {
    notifyBlocked(t('app.chat.contentTooLong', {
      limit: REQUEST_LIMITS.chatContentCharacters.toLocaleString()
    }));
    return;
  }
  if (props.prepareInteraction?.() === false || props.interactionLocked) {
    notifyBlocked(t('app.chat.boardInteractionLocked'));
    return;
  }
  const requestAuthEpoch = chatAuthEpoch;
  const turnId = typeof globalThis.crypto?.randomUUID === 'function'
      ? globalThis.crypto.randomUUID()
      : `chat-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

  if (!confirmation) inputValue.value = '';
  messages.value.push({ role: 'user', content, turnId });
  scrollToBottom(true);

  isStreaming.value = true;
  const requestEpoch = ++streamRequestEpoch;
  const requestContextIsCurrent = () =>
      requestAuthEpoch === chatAuthEpoch && requestEpoch === streamRequestEpoch;
  const controller = new AbortController();
  abortController.value = controller;
  activeStreamTurnId.value = turnId;
  historyReplacementWithoutTerminalAllowed.value = false;
  explicitStopRegistrationPending.value = false;
  activeStreamRetryDraft.value = confirmation ? '' : content;

  // 用于跟踪是否收到了任何内容
  let hasReceivedContent = false;
  let streamErrorHandled = false;
  let requestAccepted = false;
  let requestDispatched = false;
  let acceptedStreamFailed = false;
  // The streaming row is owned by `turnId`, not by its position. "Load older messages" prepends a
  // page, which shifts every index: an index-owned row would send subsequent chunks, the terminal
  // status, and the execution trace into an unrelated archived message.
  const activeAssistantMessage = () =>
      messages.value.find(message => message.role === 'assistant' && message.turnId === turnId);
  streamProgressEvents.value = [];
  appendStreamProgress({ stage: 'CONTEXT_READY' });
  streamElapsedSeconds.value = 0;
  const streamStartedAt = Date.now();
  streamLastActivitySeconds.value = 0;
  if (streamElapsedTimer) clearInterval(streamElapsedTimer);
  streamElapsedTimer = setInterval(() => {
    streamElapsedSeconds.value = Math.floor((Date.now() - streamStartedAt) / 1000);
  }, 1000);
  let commandExecutionChain: Promise<void> = Promise.resolve();
  const queueCommandExecution = (command: StreamCommand) => {
    commandExecutionChain = commandExecutionChain.then(async () => {
      await executeStreamCommand(command);
    });
  };

  try {
    let targetSessionId = currentSessionId.value;
    // 自动创建新会话
    if (!targetSessionId) {
      isPreparingStreamSession.value = true;
      try {
        const newId = await handleCreateSession(controller.signal, false);
        if (requestEpoch !== streamRequestEpoch) return;
        if (!newId) throw new Error(t('app.chat.createSessionFailed'));
        targetSessionId = newId;
        currentSessionId.value = targetSessionId;
      } finally {
        isPreparingStreamSession.value = false;
      }
    }
    activeStreamSessionId.value = targetSessionId;
    updateSessionActivity(targetSessionId, true);

    // 先插入一条空的 AI 消息占位
    messages.value.push({
      role: 'assistant',
      content: '',
      turnId,
      executionTrace: [...streamProgressEvents.value]
    });
    scrollToBottom(true);

    const renderStreamError = (error: unknown) => {
      if (requestEpoch !== streamRequestEpoch) return;
      streamErrorHandled = true;
      const msg = activeAssistantMessage();
      if (!msg) return;

      const prefix = error instanceof ChatStreamError && error.serverFrame
          ? t('app.chat.serverError')
          : t('app.chat.requestFailed');
      const errorText = `${prefix}: ${formatStreamError(error)}`;
      if (hasReceivedContent && msg.content) {
        msg.content += `\n\n> ${errorText}`;
      } else {
        msg.content = errorText;
        hasReceivedContent = true;
      }
      scrollToBottom(false);
    };

    // sendStreamChat reads the current token synchronously before dispatch. Capture
    // the same value here after any asynchronous session creation has completed.
    activeStreamAuthToken.value = authState.token;
    requestDispatched = true;
    await sendStreamChat(
        targetSessionId,
        content,
        {
          isCurrentRequest: requestContextIsCurrent,
          onMessage: (chunk) => {
            if (requestEpoch !== streamRequestEpoch) return;
            if (!chunk) return;

            hasReceivedContent = true;
            const msg = activeAssistantMessage();
            if (!msg) return;
            // 直接追加，渲染器会自动处理 Markdown 格式
            msg.content += chunk;
            streamLastActivitySeconds.value = streamElapsedSeconds.value;
            scrollToBottom(false);
          },
          onAccepted: () => {
            if (requestEpoch !== streamRequestEpoch) return;
            requestAccepted = true;
            if (confirmation) pendingConfirmationActions.value = [];
          },
          onCommand: (cmd: StreamCommand) => {
            if (requestEpoch !== streamRequestEpoch) return;
            queueCommandExecution(cmd);
          },
          onProgress: (progress: StreamProgress) => {
            if (requestEpoch !== streamRequestEpoch) return;
            appendStreamProgress(progress);
            const msg = activeAssistantMessage();
            if (msg) msg.executionTrace = [...streamProgressEvents.value];
          },
          onError: (err: any) => {
            if (requestEpoch !== streamRequestEpoch) return;
            if (requestAccepted) {
              acceptedStreamFailed = true;
              historyReplacementWithoutTerminalAllowed.value = true;
            }
            renderStreamError(err);
            console.error('[Chat] Streaming error:', err);
          },
          onFinish: (terminal: StreamTerminal) => {
            if (requestEpoch !== streamRequestEpoch) return;
            const msg = activeAssistantMessage();
            if (msg) msg.executionStatus = terminal.executionStatus;
            // Refresh title/time only after the server stream actually completed.
            void initSessions();
          }
        },
        controller,
        turnId,
        confirmation
    );
    requestAccepted = true;
  } catch (error) {
    if (requestEpoch !== streamRequestEpoch) return;
    const definitelyRejected = error instanceof ChatStreamError && error.kind === 'HTTP_ERROR';
    if (requestDispatched && !requestAccepted && !definitelyRejected) {
      requestAccepted = true;
    }
    if (requestAccepted) {
      acceptedStreamFailed = true;
      historyReplacementWithoutTerminalAllowed.value = true;
    }
    console.error('[Chat] Failed to send message:', error);
    if (!requestAccepted) {
      messages.value = messages.value.filter(message => message.turnId !== turnId);
      if (!confirmation && !inputValue.value) inputValue.value = content;
      if (activeStreamTurnId.value === turnId) {
        activeStreamTurnId.value = '';
        historyReplacementWithoutTerminalAllowed.value = false;
        explicitStopRegistrationPending.value = false;
        activeStreamRetryDraft.value = '';
      }
      notifyError(t('app.chat.sendFailedWithReason', { reason: formatStreamError(error) }));
    } else if (!streamErrorHandled && !hasReceivedContent) {
      notifyError(t('app.chat.sendFailedWithReason', {
        reason: error instanceof Error ? error.message : String(error)
      }));
    }
  } finally {
    if (requestEpoch === streamRequestEpoch) {
      await commandExecutionChain;
      if (requestEpoch !== streamRequestEpoch || !requestContextIsCurrent()) return;
      isStreaming.value = false;
      streamProgress.value = null;
      const completedMessage = activeAssistantMessage();
      if (completedMessage) {
        completedMessage.executionTrace = [...streamProgressEvents.value];
        completedMessage.executionElapsedSeconds = streamElapsedSeconds.value;
      }
      if (streamElapsedTimer) {
        clearInterval(streamElapsedTimer);
        streamElapsedTimer = null;
      }
      if (abortController.value === controller) abortController.value = null;
      const completedSessionId = activeStreamSessionId.value;
      const completedTurnId = activeStreamTurnId.value;
      if (completedSessionId) {
        isSettlingStream.value = true;
        let retainActiveSession = false;
        try {
          if (!requestAccepted) {
            await initSessions();
            if (!requestContextIsCurrent()) return;
            await refreshPendingConfirmation(completedSessionId);
            if (!requestContextIsCurrent()) return;
          } else {
            const idle = await waitForSessionIdle(completedSessionId);
            if (!requestContextIsCurrent()) return;
            if (!idle) {
              retainActiveSession = true;
              reconciliationRequired.value = true;
              notifyBlocked(t('app.chat.sessionStillRunningRetry'));
            } else {
              if (acceptedStreamFailed) {
                await reconcileAuthoritativeState();
                if (!requestContextIsCurrent()) return;
              }
              if (currentSessionId.value === completedSessionId) {
                try {
                  const history = await getSessionHistory(completedSessionId);
                  if (!requestContextIsCurrent()) return;
                  const hasTerminal = hasTerminalAssistantRecord(history.messages, completedTurnId);
                  if (acceptedStreamFailed || hasTerminal) {
                    applyHistoryPage(history, false);
                    if (acceptedStreamFailed && !hasTerminal) {
                      restoreRetryDraftIfTurnWasNotAdmitted(history.messages, completedTurnId);
                      notifyBlocked(t('app.chat.incompleteStreamHistoryRestored'));
                    }
                  } else {
                    notifyBlocked(t('app.chat.historyTerminalRecordMissing'));
                    retainActiveSession = true;
                    reconciliationRequired.value = true;
                  }
                  await initSessions();
                  if (!requestContextIsCurrent()) return;
                  await refreshPendingConfirmation(completedSessionId);
                  if (!requestContextIsCurrent()) return;
                } catch (error) {
                  if (!requestContextIsCurrent()) return;
                  if (isHttpNotFound(error)) {
                    const reconciledDeletedSession = await reconcileAuthoritativeState();
                    if (!requestContextIsCurrent()) return;
                    reportAuthoritativeSessionDeletion(completedSessionId);
                    if (!reconciledDeletedSession) reconciliationRequired.value = true;
                  } else {
                    console.error('[Chat] Failed to reload authoritative history after stream completion:', error);
                    notifyBlocked(t('app.chat.historyReloadAfterSettleFailed'));
                    retainActiveSession = true;
                    reconciliationRequired.value = true;
                  }
                }
              }
            }
          }
        } catch (error) {
          if (!requestContextIsCurrent()) return;
          console.error('[Chat] Failed to confirm normal stream completion:', error);
          retainActiveSession = true;
          reconciliationRequired.value = true;
          const reconciled = await reconcileAuthoritativeState();
          if (!requestContextIsCurrent()) return;
          reconciliationRequired.value = true;
          if (reconciled) {
            notifyBlocked(t('app.chat.stopOutcomeUnknown'));
          }
        } finally {
          if (requestContextIsCurrent()
              && !retainActiveSession && activeStreamSessionId.value === completedSessionId) {
            activeStreamSessionId.value = '';
            activeStreamTurnId.value = '';
            activeStreamAuthToken.value = null;
            historyReplacementWithoutTerminalAllowed.value = false;
            explicitStopRegistrationPending.value = false;
            activeStreamRetryDraft.value = '';
            updateSessionActivity(completedSessionId, false);
          }
          if (requestContextIsCurrent()) isSettlingStream.value = false;
          if (requestContextIsCurrent()
              && !retainActiveSession && !activeStreamSessionId.value) {
            reattachAuthoritativeActiveSession(sessions.value);
          }
        }
      }
    }
  }
};

const handleSend = async () => {
  await submitChatTurn(inputValue.value.trim());
};

// Confirmation names the write being authorized; tool labels can also describe a read or preview.
const CONFIRMATION_LABEL_KEYS: Record<string, string> = {
  delete_device: 'app.deleteDevice',
  delete_template: 'app.deleteTemplate',
  delete_trace: 'app.chat.confirmationDeleteTrace',
  delete_simulation_trace: 'app.deleteSimulationRunTitle',
  manage_rule: 'app.deleteRule',
  manage_spec: 'app.deleteSpecification',
  manage_board_history: 'app.chat.confirmationClearBoardHistory',
  clear_board: 'app.sceneClear',
  reset_default_templates: 'app.resetDefaultTemplates',
  apply_scenario: 'app.chat.confirmationKinds.SCENE_REPLACEMENT'
};
const confirmationActionLabel = (pending: ChatProtectedAction) =>
    t(CONFIRMATION_LABEL_KEYS[pending.toolName] || TOOL_LABEL_KEYS[pending.toolName]
      || `app.chat.confirmationKinds.${pending.kind}`);

const handleProtectedConfirmation = async (
    pending: ChatProtectedAction,
    action: ChatConfirmationAction
) => {
  if (isLoading.value || props.interactionLocked) return;
  const content = action === 'CONFIRM'
      ? t('app.chat.protectedConfirmationAccepted', { action: confirmationActionLabel(pending), target: pending.target })
      : t('app.chat.protectedConfirmationCancelled', { action: confirmationActionLabel(pending), target: pending.target });
  await submitChatTurn(content, { kind: pending.kind, action, confirmationId: pending.confirmationId });
};

const retryPendingConfirmation = () => {
  if (!currentSessionId.value || isLoading.value) return;
  void refreshPendingConfirmation(currentSessionId.value);
};

const handleTaskClick = (text: string) => {
  if (props.interactionLocked) {
    notifyBlocked(t('app.chat.boardInteractionLocked'));
    return;
  }
  refreshBoardContext();
  inputValue.value = text;
  nextTick(() => inputRef.value?.focus());
};

const prepareHouseholdDraft = (text: string) => {
  if (props.interactionLocked || isLoading.value || historyLoadFailed.value || inputValue.value.trim()) return;
  inputValue.value = text;
  nextTick(() => inputRef.value?.focus());
};

const scrollToBottom = (force = false) => {
  nextTick(() => {
    if (!scrollRef.value) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.value;
    // 只有当用户确实在底部，或者强制滚动时，才自动滚动。防止用户回看历史时被强制拉回底部。
    const isNearBottom = scrollHeight - scrollTop - clientHeight < 150;
    if (force || isNearBottom) scrollRef.value.scrollTop = scrollHeight;
  });
};
</script>

<template>
  <div :class="['global-chat-wrapper', { 'global-chat-wrapper--board': props.boardMode }]">

    <transition name="panel-zoom">
      <div
        :ref="setChatPanelRef"
        v-show="visible"
        data-testid="chat-panel"
        :class="[
          'chat-panel',
          {
            dragging: panelInteraction?.mode === 'drag',
            resizing: panelInteraction?.mode === 'resize',
            'chat-panel--compact': isChatPanelCompact,
            'chat-panel--sidebar-open': isSidebarOpen
          }
        ]"
        :style="chatPanelStyle"
        role="region"
        :aria-label="t('app.aiAssistant')"
        tabindex="-1"
        @keydown="handleChatKeydown"
      >

        <div :class="['sidebar', { collapsed: !isSidebarOpen }]"
          :inert="isSidebarOpen ? undefined : true" :aria-hidden="!isSidebarOpen">
          <div class="sidebar-header">
            <button type="button" class="new-chat-btn iot-action-label"
              :disabled="isSettlingStream || isCreatingSession || isPreparingStreamSession"
              @click="onNewChatClick">
              <PlusOutlined aria-hidden="true"/> <span class="btn-label">{{ t('app.chat.newChat') }}</span>
            </button>
          </div>

          <div class="session-list">
            <div
              v-if="isLoadingSessions && sessions.length === 0"
              class="session-list-state"
              data-testid="chat-session-list-loading"
              role="status"
              aria-live="polite"
            >
              <span>{{ t('app.chat.loadingSessions') }}</span>
              <div class="typing-indicator" aria-hidden="true"><span></span><span></span><span></span></div>
            </div>
            <button
              v-else-if="sessionListLoadFailed && sessions.length === 0"
              type="button"
              class="session-list-state session-list-retry"
              data-testid="chat-session-list-retry"
              @click="initSessions()"
            >
              <span>{{ t('app.chat.loadSessionsFailed') }}</span>
              <span class="session-list-retry__action">{{ t('app.chat.retryLoadSessions') }}</span>
            </button>
            <div
              v-else-if="sessions.length === 0"
              class="session-list-state"
              data-testid="chat-session-list-empty"
            >
              {{ t('app.chat.noSessions') }}
            </div>
            <div
                v-for="session in sessions"
                :key="session.id"
                :class="['session-item', { active: currentSessionId === session.id }]"
            >
              <button
                type="button"
                class="session-select-button"
                :data-testid="`chat-session-${session.id}`"
                :disabled="isSettlingStream || isPreparingStreamSession"
                @click="handleUserSelectSession(session.id, session.active)"
              >
                <MessageOutlined class="item-icon"/>
                <span class="session-copy">
                  <span class="item-title">{{ session.title || t('app.chat.newChat') }}</span>
                  <span
                    v-if="sessionExecutionStatus(session)"
                    class="session-status"
                    :class="sessionStatusClass(session)"
                    :data-testid="session.active ? 'chat-session-active' : 'chat-session-status'"
                    :aria-label="sessionStatusTitle(session)"
                    :title="sessionStatusTitle(session)"
                  >
                    <span class="session-status-dot" aria-hidden="true"></span>
                    <span>{{ sessionExecutionStatus(session) }}</span>
                  </span>
                </span>
              </button>
              <HintTooltip :content="visible ? (session.active ? t('app.chat.sessionStillRunning') : t('app.delete')) : ''">
                <button
                  type="button"
                  class="delete-btn-wrapper"
                  :aria-label="t('app.delete')"
                  :disabled="isSettlingStream || isPreparingStreamSession || session.active"
                  @click.stop="handleDelete(session.id)"
                >
                  <DeleteOutlined class="delete-icon"/>
                </button>
              </HintTooltip>
            </div>
          </div>

          <div class="sidebar-footer">
            <!-- 移除深色模式切换功能 -->
          </div>
        </div>

        <HintTooltip v-if="isChatPanelCompact && isSidebarOpen" :content="visible ? t('app.collapse') : ''">
          <button
            type="button"
            class="sidebar-scrim"
            data-testid="chat-sidebar-scrim"
            :aria-label="t('app.collapse')"
            @click="isSidebarOpen = false"
          ></button>
        </HintTooltip>

        <div class="main-content">
          <div
            class="glass-header"
            data-testid="chat-drag-handle"
            @pointerdown="startPanelDrag"
          >
            <div class="header-left-group">
              <button
                  ref="sidebarToggleRef"
                  type="button"
                  class="control-icon-button sidebar-toggle"
                  data-testid="chat-sidebar-toggle"
                  :aria-label="isSidebarOpen ? t('app.collapse') : t('app.expand')"
                  :aria-expanded="isSidebarOpen"
                  @click="isSidebarOpen = !isSidebarOpen"
                >
                  <component :is="isSidebarOpen ? MenuFoldOutlined : MenuUnfoldOutlined" />
              </button>
              <div class="header-title board-panel-title">
                <RobotOutlined class="header-title-icon" aria-hidden="true" />
                <span>{{ t('app.aiAssistant') }}</span>
              </div>
            </div>
            <div class="header-controls">
              <HintTooltip :content="visible ? t('app.close') : ''">
                <button
                  type="button"
                  class="control-icon-button control-icon-button--danger"
                  data-testid="chat-close"
                  :aria-label="t('app.close')"
                  @click="chatStore.closeChat()"
                >
                  <CloseOutlined />
                </button>
              </HintTooltip>
            </div>
          </div>

          <!-- No tooltip on a drag handle: a hint would appear under the cursor at the moment the user starts
               dragging, which is the one moment it is in the way. `aria-label` still names it. -->
          <button
            type="button"
            class="chat-resize-handle"
            data-testid="chat-resize-handle"
            :aria-label="t('app.resize')"
            @pointerdown="startPanelResize"
          />

          <div
            class="messages-viewport"
            ref="scrollRef"
          >
            <div
              v-if="messages.length > 0 && historyHasMore && !isLoadingHistory"
              class="history-pagination"
            >
              <button
                type="button"
                class="history-pagination__button iot-action-label"
                data-testid="chat-load-older"
                :disabled="isLoadingOlderHistory"
                @click="loadOlderHistory"
              >
                {{ isLoadingOlderHistory ? t('app.chat.loadingOlderHistory') : t('app.chat.loadOlderHistory') }}
              </button>
            </div>

            <div
              v-if="historyLoadFailed && currentSessionId"
              class="session-list-state session-list-retry"
              data-testid="chat-history-retry"
              role="alert"
            >
              <div>
                <p>{{ t('app.chat.loadConversationFailed') }}</p>
                <FailureDetails v-if="historyLoadDiagnostics" :message="t('app.chat.loadConversationFailed')"
                  suggestion="" v-bind="historyLoadDiagnostics" />
              </div>
              <button type="button" class="history-pagination__button iot-action-label" @click="handleUserSelectSession(currentSessionId)">
                {{ t('app.retry') }}
              </button>
            </div>

            <div v-else-if="messages.length === 0" class="welcome-screen">
              <HouseholdGoalComposer v-if="boardMode"
                :key="`${authenticatedUserId}:${currentSessionId}`"
                :disabled="interactionLocked || isLoading || historyLoadFailed"
                :draft-occupied="!!inputValue.trim()"
                @draft="prepareHouseholdDraft" />
              <div v-if="!boardMode" class="brand-logo">
                <div class="logo-inner">
                  <img src="/AI.png" :alt="t('app.aiAssistant')" class="custom-logo-img iot-device-artwork" />
                </div>
              </div>

              <div class="task-grid">
                <button
                    type="button"
                    v-for="(task, index) in presetTasks"
                    :key="index"
                    class="task-card"
                    @click="handleTaskClick(task.text)"
                    :disabled="interactionLocked"
                    :style="{ animationDelay: `${index * 0.1}s` }"
                >
                  <div class="task-icon">
                    <component :is="task.icon" />
                  </div>
                  <div class="task-info">
                    <div class="task-title">{{ task.title }}</div>
                    <div v-if="task.desc" class="task-desc">{{ task.desc }}</div>
                  </div>
                </button>
              </div>
            </div>

            <div v-if="isLoadingHistory" class="thinking-state" role="status" aria-live="polite">
              <span class="thinking-text">{{ t('app.chat.loadingConversation') }}</span>
              <div class="typing-indicator"><span></span><span></span><span></span></div>
            </div>
            <div v-else-if="isSettlingStream" class="thinking-state" role="status" aria-live="polite">
              <RobotOutlined class="thinking-avatar" />
              <span>{{ t('app.chat.waitingForServerToStop') }}</span>
              <div class="typing-indicator" aria-hidden="true"><span></span><span></span><span></span></div>
            </div>
            <div
              v-else-if="isMonitoringRemoteExecution"
              class="remote-execution-state"
              data-testid="chat-remote-execution"
              role="status"
              aria-live="polite"
            >
              <RobotOutlined class="thinking-avatar" />
              <div>
                <strong>{{ t('app.chat.remoteExecutionTitle') }}</strong>
                <p>{{ remoteActivityCheckFailed
                  ? t('app.chat.remoteExecutionActivityUnavailable')
                  : t('app.chat.remoteExecutionMessage') }}</p>
              </div>
              <div class="typing-indicator" aria-hidden="true"><span></span><span></span><span></span></div>
            </div>
            <div
              v-if="reconciliationRequired"
              class="reconciliation-state"
              data-testid="chat-reconciliation-required"
              role="alert"
            >
              <div>
                <strong>{{ t('app.chat.reconciliationRequiredTitle') }}</strong>
                <p>{{ t('app.chat.reconciliationRequiredMessage') }}</p>
              </div>
              <button
                type="button"
                class="reconciliation-retry"
                data-testid="chat-reconciliation-retry"
                :disabled="isRetryingReconciliation"
                @click="retryAuthoritativeReconciliation"
              >
                {{ isRetryingReconciliation
                  ? t('app.chat.reconciliationRetrying')
                  : t('app.chat.reconciliationRetry') }}
              </button>
            </div>

            <template v-for="(msg, index) in messages" :key="msg.id || msg.turnId || `msg-${index}`">
              <div :class="['msg-row', msg.role === 'user' ? 'user-row' : 'ai-row']">
                <div class="avatar-container">
                  <UserOutlined v-if="msg.role === 'user'"/>
                  <RobotOutlined v-else/>
                </div>

                <div class="msg-content-wrapper">
                  <div v-if="msg.role === 'user'" class="msg-body user-msg-body">
                    {{ msg.content }}
                  </div>

                  <article
                    v-else
                    :class="[
                      'msg-body',
                      'vue-markdown-wrapper',
                      {
                        'assistant-pending-body': isStreaming && index === messages.length - 1,
                        'has-execution-trace': messageExecutionTrace(msg, index).length > 0
                      }
                    ]"
                    :aria-busy="isStreaming && index === messages.length - 1 ? 'true' : undefined"
                  >
                    <details
                      v-if="messageExecutionTrace(msg, index).length"
                      class="chat-execution-trace"
                      :open="isExecutionTraceOpen(msg, index)"
                      :data-testid="isActiveAssistantMessage(index) ? 'chat-assistant-pending' : undefined"
                      :role="isActiveAssistantMessage(index) ? 'status' : undefined"
                      :aria-live="isActiveAssistantMessage(index) ? 'polite' : undefined"
                      aria-atomic="false"
                      @toggle="handleExecutionTraceToggle($event, isActiveAssistantMessage(index), msg, index)"
                    >
                      <summary class="chat-execution-header">
                        <span class="chat-execution-chevron" aria-hidden="true">
                          <DownOutlined />
                        </span>
                        <div class="chat-execution-heading">
                          <span class="chat-execution-icon" aria-hidden="true"><CodeOutlined /></span>
                          <div>
                            <strong>{{ t('app.chat.executionTraceTitle') }}</strong>
                          </div>
                        </div>
                        <div class="chat-execution-meta">
                          <span
                            class="chat-execution-state"
                            :class="{
                              'is-running': isActiveAssistantMessage(index),
                              'is-stopped': !isActiveAssistantMessage(index)
                                && (messageExecutionTrace(msg, index).some(progress => progress.stage === 'EXECUTION_GUARD')
                                  || msg.executionStatus === 'PARTIAL'
                                  || msg.executionStatus === 'STOPPED'
                                  || msg.executionStatus === 'DISCONNECTED'),
                              'is-failed': !isActiveAssistantMessage(index)
                                && msg.executionStatus === 'FAILED'
                            }"
                          >
                            {{ executionTraceStatus(
                              messageExecutionTrace(msg, index),
                              isActiveAssistantMessage(index),
                              msg.executionStatus,
                              isSettlingAssistantMessage(index)
                            ) }}
                          </span>
                          <span>{{ t('app.chat.progressElapsed', { seconds: messageExecutionElapsed(msg, index) }) }}</span>
                        </div>
                      </summary>
                      <div
                        v-if="traceHasToolResults(messageExecutionTrace(msg, index))"
                        class="chat-execution-metrics"
                      >
                        <!--
                          The colour follows the number, not the label.

                          All three spans carried their status class unconditionally, so a turn where
                          everything worked rendered "**0** failed" in the error colour and "**0**
                          unconfirmed" in the warning colour. A measurement of the panel picked the error
                          styling up as a genuine failure signal — `showsError: true, errorText: "0 failed"` —
                          which is exactly the misreading a user makes at a glance: red beside a count means
                          something went wrong.
                          Zero failures is the good outcome and now reads as ordinary text; the status colours
                          appear only when there is a status to report.
                        -->
                        <span :class="executionTraceTotals(messageExecutionTrace(msg, index)).successful > 0 ? 'is-success' : ''">
                          <strong>{{ executionTraceTotals(messageExecutionTrace(msg, index)).successful }}</strong>
                          {{ t('app.chat.executionMetricSucceeded') }}
                        </span>
                        <span :class="executionTraceTotals(messageExecutionTrace(msg, index)).failed > 0 ? 'is-error' : ''">
                          <strong>{{ executionTraceTotals(messageExecutionTrace(msg, index)).failed }}</strong>
                          {{ t('app.chat.executionMetricFailed') }}
                        </span>
                        <span :class="executionTraceTotals(messageExecutionTrace(msg, index)).unconfirmed > 0 ? 'is-warning' : ''">
                          <strong>{{ executionTraceTotals(messageExecutionTrace(msg, index)).unconfirmed }}</strong>
                          {{ t('app.chat.executionMetricUnconfirmed') }}
                        </span>
                      </div>
                      <ol class="chat-execution-events" data-testid="chat-execution-trace">
                        <li
                          v-for="(progress, progressIndex) in messageExecutionTrace(msg, index)"
                          :key="`${progress.stage}-${progress.round || 0}-${progress.toolName || ''}-${progressIndex}`"
                          :class="progressEventClass(progress, isActiveAssistantMessage(index))"
                          :aria-current="isCurrentProgressEvent(progress, isActiveAssistantMessage(index)) ? 'step' : undefined"
                        >
                          <span class="chat-execution-step" aria-hidden="true">{{ progressIndex + 1 }}</span>
                          <div class="chat-execution-event-copy">
                            <strong>{{ progressEventTitle(progress) }}</strong>
                            <!-- Reasoning keeps its line breaks: the backend preserves them
                                 precisely so a decomposition does not read as one run-on line. -->
                            <p v-if="progressEventDetail(progress, isActiveAssistantMessage(index))" :class="{ 'is-reasoning-copy': progress.stage === 'REASONING' }">{{ progressEventDetail(progress, isActiveAssistantMessage(index)) }}</p>
                            <details v-if="progress.stage === 'CLAIM_REVIEW'" class="chat-claim-review" data-testid="chat-claim-review">
                              <summary>{{ t('app.chat.claimReviewOriginal') }}</summary>
                              <blockquote>{{ progress.claimReview.originalText }}</blockquote>
                              <p>{{ t('app.chat.claimReviewMatched') }} <code>{{ progress.claimReview.matchedText }}</code></p>
                            </details>
                          </div>
                          <span
                            v-if="progressEventStatus(progress)"
                            class="chat-execution-outcome"
                          >
                            {{ progressEventStatus(progress) }}
                          </span>
                        </li>
                      </ol>
                    </details>
                    <div
                      v-else-if="msg.executionStatus"
                      class="chat-terminal-status"
                      data-testid="chat-terminal-status"
                    >
                      <span
                        class="chat-execution-state"
                        :class="{
                          'is-stopped': msg.executionStatus === 'PARTIAL'
                            || msg.executionStatus === 'STOPPED'
                            || msg.executionStatus === 'DISCONNECTED',
                          'is-failed': msg.executionStatus === 'FAILED'
                        }"
                      >
                        {{ executionTraceStatus([], false, msg.executionStatus) }}
                      </span>
                      <span>{{ t('app.chat.progressElapsed', { seconds: msg.executionElapsedSeconds ?? 0 }) }}</span>
                    </div>
                    <ChatMarkdown v-if="msg.content" :source="getProcessedContent(msg.content) || ''" />
                  </article>

                  <div v-if="msg.role === 'assistant' && msg.content && !isStreaming" class="msg-actions">
                    <HintTooltip :content="visible ? t('app.chat.copyAll') : ''">
                      <button
                        type="button"
                        class="action-btn-small"
                        :aria-label="t('app.chat.copyAll')"
                        @click="copyFullMessage(msg.content)"
                      >
                        <CopyOutlined />
                      </button>
                    </HintTooltip>
                  </div>

                </div>
              </div>
            </template>
            <div class="scroll-spacer"></div>
          </div>

          <div class="input-floating-area">
            <div
              v-if="pendingConfirmationLoadFailed"
              class="protected-confirmation is-load-error"
              data-testid="chat-confirmation-load-error"
            >
              <div class="protected-confirmation__copy">
                <SafetyCertificateOutlined aria-hidden="true" />
                <span>{{ t('app.chat.confirmationLoadFailed') }}</span>
              </div>
              <div class="protected-confirmation__actions">
                <button
                  type="button"
                  class="protected-confirmation__button iot-action-label"
                  :disabled="interactionLocked || isLoading"
                  @click="retryPendingConfirmation"
                >
                  {{ t('app.retry') }}
                </button>
              </div>
            </div>
            <div
              v-for="pending in pendingConfirmationActions"
              :key="pending.confirmationId"
              class="protected-confirmation"
              data-testid="chat-protected-confirmation"
            >
              <div class="protected-confirmation__copy">
                <SafetyCertificateOutlined aria-hidden="true" />
                <span>
                  <strong>{{ t('app.chat.protectedConfirmationPrompt', { action: confirmationActionLabel(pending) }) }}</strong>
                  <span class="block" data-testid="chat-confirmation-target">{{ pending.target }}</span>
                </span>
              </div>
              <div class="protected-confirmation__actions">
                <button
                  type="button"
                  class="protected-confirmation__button is-cancel iot-action-label"
                  :disabled="interactionLocked || isLoading"
                  @click="handleProtectedConfirmation(pending, 'CANCEL')"
                >
                  <CloseOutlined aria-hidden="true" />
                  {{ t('app.cancel') }}
                </button>
                <button
                  type="button"
                  class="protected-confirmation__button is-confirm iot-action-label"
                  :disabled="interactionLocked || isLoading"
                  @click="handleProtectedConfirmation(pending, 'CONFIRM')"
                >
                  <CheckOutlined aria-hidden="true" />
                  {{ t('app.confirm') }}
                </button>
              </div>
            </div>
            <div class="input-card" :class="{ 'is-streaming': isStreaming }">
              <div
                v-if="!isStreaming"
                role="separator"
                tabindex="0"
                class="chat-input-resize-handle"
                data-testid="chat-input-resize-handle"
                aria-orientation="horizontal"
                :aria-label="t('app.chat.resizeInput')"
                aria-describedby="chat-input-resize-help"
                :aria-valuemin="Math.round(composerMinHeight)"
                :aria-valuemax="Math.round(composerMaxHeight)"
                :aria-valuenow="Math.round(composerHeight)"
                @pointerdown="startComposerResize"
                @pointermove="moveComposerResize"
                @pointerup="stopComposerResize"
                @pointercancel="stopComposerResize"
                @lostpointercapture="stopComposerResize"
                @keydown="handleComposerResizeKeydown"
              ><span aria-hidden="true"></span></div>
              <span id="chat-input-resize-help" class="sr-only">{{ t('app.chat.resizeInputHelp') }}</span>
              <div
                v-if="isStreaming"
                class="chat-response-status"
                data-testid="chat-response-status"
                role="status"
                aria-live="polite"
              >
                <span class="chat-response-phase"><LoadingOutlined aria-hidden="true" />{{ streamActivityLabel }}</span>
                <span class="chat-response-time" aria-hidden="true">{{ t('app.chat.progressElapsed', { seconds: streamElapsedSeconds }) }}</span>
                <span v-if="streamWaitingForUpdate" class="chat-response-wait">{{ t('app.chat.responseWaiting') }}</span>
              </div>
              <textarea
                ref="inputRef"
                v-model="inputValue"
                data-testid="chat-input"
                :placeholder="t('app.chat.inputPlaceholder')"
                :aria-label="t('app.chat.messageInput')"
                :disabled="interactionLocked || isLoading || historyLoadFailed"
                rows="2"
                :style="{ height: `${composerHeight}px` }"
                :maxlength="REQUEST_LIMITS.chatContentCharacters"
                @keydown.ctrl.enter="handleSend"
                class="modern-textarea"
              ></textarea>
              <div class="input-actions">
                <div class="left-tools">
                  <HintTooltip :content="visible ? (isRecording ? t('app.chat.stopVoiceInput') : t('app.chat.startVoiceInput')) : ''">
                    <button
                      type="button"
                      :class="['tool-icon', { active: isRecording }]"
                      :aria-pressed="isRecording"
                      :aria-label="isRecording ? t('app.chat.stopVoiceInput') : t('app.chat.startVoiceInput')"
                      @click="isRecording ? stopListening() : startListening()"
                      :disabled="interactionLocked || isLoading || historyLoadFailed"
                    >
                      <AudioOutlined/>
                    </button>
                  </HintTooltip>
                </div>
                <div class="right-tools">
                  <!--
                    The conditional lives on the wrappers, not the buttons.

                    `v-if` and `v-else` must remain adjacent siblings; giving each button its own tooltip *inside*
                    a shared wrapper would separate them and Vue rejects it ("v-else has no adjacent v-if"). So
                    the branch moves up one level and each wrapper carries the hint its own button needs.
                  -->
                  <HintTooltip
                    v-if="isStreaming || isMonitoringRemoteExecution"
                    :content="visible ? t(isMonitoringRemoteExecution
                      ? 'app.chat.stopRemoteExecution'
                      : 'app.chat.stopResponse') : ''"
                  >
                    <button
                      type="button"
                      class="action-btn stop"
                      data-testid="chat-stop"
                      :aria-label="t(isMonitoringRemoteExecution
                        ? 'app.chat.stopRemoteExecution'
                        : 'app.chat.stopResponse')"
                      @click="handleStop"
                    ><StopOutlined/></button>
                  </HintTooltip>
                  <HintTooltip
                    v-else
                    :content="visible && interactionLocked ? t('app.chat.boardInteractionLocked') : ''"
                  >
                    <button
                      type="button"
                      class="action-btn send"
                      data-testid="chat-send"
                      :aria-label="t('app.chat.sendMessage')"
                      @click="handleSend"
                      :disabled="interactionLocked || !inputValue.trim() || isLoading || historyLoadFailed"
                    ><SendOutlined/></button>
                  </HintTooltip>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.global-chat-wrapper {
  position: fixed;
  inset: 0;
  z-index: var(--z-chat-panel);
  pointer-events: none;
  font-family: var(--iot-font-body);
  --chat-accent: var(--accent-strong);
  --chat-success: var(--success);
  --chat-bg: var(--surface-panel);
  --chat-sidebar-bg: var(--surface-panel-muted);
  --chat-input-bg: var(--surface-elevated);
  --chat-control-bg: var(--surface-control);
  --chat-text: var(--text);
  --chat-muted: var(--text-muted);
  --chat-border: var(--border);
  --chat-shadow: var(--shadow-elevated);
  --chat-user-bg: color-mix(in srgb, var(--accent-strong) 12%, var(--surface-control));
  --chat-ai-bg: color-mix(in srgb, var(--surface-elevated) 86%, transparent);
  --chat-safe-left: 1rem;
  --chat-safe-right: 1rem;
}

:global(:root[data-theme='dark'] .global-chat-wrapper) {
  --chat-user-bg: color-mix(in srgb, var(--accent-strong) 20%, var(--surface-control));
  --chat-ai-bg: color-mix(in srgb, var(--surface-elevated) 92%, transparent);
}

.global-chat-wrapper--board {
  --chat-safe-left: calc(20rem + 1rem);
  --chat-safe-right: calc(20rem + 1rem);
}

@media (min-width: 1280px) {
  .global-chat-wrapper--board {
    --chat-safe-right: calc(20rem + 8.25rem + 2rem);
  }
}

.chat-panel {
  position: fixed;
  top: calc(4rem + 0.85rem);
  right: var(--chat-safe-right);
  width: min(31rem, calc(100vw - var(--chat-safe-left) - var(--chat-safe-right)));
  height: min(42rem, calc(100dvh - 5.75rem));
  min-width: min(20rem, calc(100vw - 1.5rem));
  min-height: min(22rem, max(16.25rem, calc(100dvh - 17.5rem)));
  display: flex;
  pointer-events: auto;
  overflow: hidden;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-panel);
  background: var(--chat-bg);
  color: var(--chat-text);
  box-shadow: var(--chat-shadow);
  transition: top 0.28s ease, right 0.28s ease, bottom 0.28s ease, left 0.28s ease, width 0.28s ease, height 0.28s ease, border-radius 0.28s ease, box-shadow 0.28s ease;
}

.chat-panel.dragging,
.chat-panel.resizing {
  transition: none;
}

.panel-zoom-enter-active,
.panel-zoom-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.panel-zoom-enter-from,
.panel-zoom-leave-to {
  opacity: 0;
  transform: translateY(0.5rem) scale(0.98);
}

.sidebar {
  width: clamp(12.5rem, 22%, 16rem);
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--chat-border);
  background: var(--chat-sidebar-bg);
  white-space: nowrap;
  transition: width 0.22s ease, opacity 0.16s ease;
}

.sidebar.collapsed {
  width: 0;
  border-right: 0;
  opacity: 0;
}

.sidebar-header {
  padding: 0.75rem;
}

.new-chat-btn {
  width: 100%;
  min-height: var(--iot-target-min);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.45rem;
  border: 1px solid color-mix(in srgb, var(--chat-accent) 40%, var(--chat-border));
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--chat-accent) 12%, transparent);
  color: var(--chat-text);
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease, transform 0.16s ease;
}

.new-chat-btn:hover:not(:disabled) {
  border-color: var(--chat-accent);
  background: color-mix(in srgb, var(--chat-accent) 18%, transparent);
}

.new-chat-btn:active:not(:disabled) {
  transform: translateY(1px);
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 0.65rem 0.9rem;
}

.session-list-state {
  width: 100%;
  min-height: 5rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  border: 1px dashed color-mix(in srgb, var(--chat-border) 82%, transparent);
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--chat-control-bg) 42%, transparent);
  color: var(--chat-muted);
  padding: 0.75rem;
  font: inherit;
  font-size: var(--iot-font-caption);
  line-height: 1.45;
  text-align: center;
  white-space: normal;
}

.session-list-retry {
  cursor: pointer;
}

.session-list-retry:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--chat-accent) 54%, var(--chat-border));
  color: var(--chat-text);
}

.session-list-retry__action {
  color: var(--chat-accent);
  font-weight: 600;
}

.session-item {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.55rem;
  margin-bottom: 0.25rem;
  padding: 0.6rem 0.55rem;
  border: 1px solid transparent;
  border-radius: var(--iot-radius-well);
  color: var(--chat-muted);
  font-size: var(--iot-font-label);
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

.session-item:hover,
.session-item:focus-within {
  outline: none;
  border-color: var(--chat-border);
  background: color-mix(in srgb, var(--chat-control-bg) 76%, transparent);
  color: var(--chat-text);
}

.session-select-button {
  min-width: 0;
  min-height: var(--iot-target-min);
  flex: 1;
  display: flex;
  align-items: center;
  gap: 0.55rem;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.session-item.active {
  border-color: color-mix(in srgb, var(--chat-accent) 46%, var(--chat-border));
  background: color-mix(in srgb, var(--chat-accent) 14%, var(--chat-control-bg));
  color: var(--chat-text);
}

.item-icon {
  flex: 0 0 auto;
  color: var(--chat-accent);
}

.item-title {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}

.session-status {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  color: var(--chat-muted);
  font-size: var(--iot-font-caption);
  line-height: 1.25;
  white-space: normal;
}

.session-status-dot {
  width: 0.42rem;
  height: 0.42rem;
  flex: 0 0 auto;
  border-radius: 50%;
  background: currentColor;
}

.session-status.is-running {
  color: var(--chat-success);
}

.session-status.is-warning {
  color: var(--warning);
}

.session-status.is-failed {
  color: var(--danger);
}

.session-status.is-unread {
  color: var(--chat-accent);
  font-weight: 600;
}

.session-status.is-running .session-status-dot {
  background: var(--chat-success);
  box-shadow: 0 0 0 0.2rem color-mix(in srgb, var(--chat-success) 18%, transparent);
}

.delete-btn-wrapper {
  width: var(--iot-target-min);
  height: var(--iot-target-min);
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: var(--iot-radius-action);
  background: transparent;
  color: var(--chat-muted);
  cursor: pointer;
  transition: background 0.16s ease, color 0.16s ease;
}

.delete-btn-wrapper:hover:not(:disabled) {
  background: color-mix(in srgb, var(--danger) 14%, transparent);
  color: var(--danger);
}

.sidebar-footer {
  display: none;
}

.main-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  position: relative;
  background: color-mix(in srgb, var(--chat-bg) 92%, transparent);
}

.chat-panel--compact .main-content {
  flex: 0 0 100%;
  width: 100%;
}

.chat-panel--compact .sidebar {
  position: absolute;
  inset: 0 auto 0 0;
  z-index: 20;
  width: clamp(13rem, 42%, 15.5rem);
  max-width: calc(100% - 4rem);
  border-right: 1px solid var(--chat-border);
  box-shadow: var(--shadow-floating);
  transform: translateX(0);
  transition: transform 0.22s ease, opacity 0.16s ease;
}

.chat-panel--compact .sidebar.collapsed {
  width: clamp(13rem, 42%, 15.5rem);
  max-width: calc(100% - 4rem);
  border-right: 1px solid var(--chat-border);
  opacity: 0;
  pointer-events: none;
  transform: translateX(-102%);
}

.sidebar-scrim {
  position: absolute;
  inset: 0;
  z-index: 19;
  border: 0;
  background: color-mix(in srgb, var(--chat-bg) 48%, transparent);
  cursor: default;
}

.chat-panel--compact .sidebar-scrim {
  backdrop-filter: blur(1px);
}

.glass-header {
  flex: 0 0 auto;
  min-height: 3.25rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.75rem;
  padding: 0.7rem 0.85rem;
  border-bottom: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-bg) 88%, transparent);
  cursor: grab;
  touch-action: none;
  user-select: none;
}

.chat-panel.dragging .glass-header {
  cursor: grabbing;
}

.header-left-group {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.65rem;
}

.header-title {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.45rem;
  color: var(--chat-text);
}

.header-title-icon {
  color: var(--chat-accent);
}

.header-controls {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.control-icon-button {
  width: var(--iot-target-min);
  height: var(--iot-target-min);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: var(--iot-radius-well);
  background: transparent;
  color: var(--chat-muted);
  cursor: pointer;
  font: inherit;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

.control-icon-button:hover:not(:disabled) {
  border-color: var(--chat-border);
  background: var(--chat-control-bg);
  color: var(--chat-text);
}

.control-icon-button--danger:hover:not(:disabled),
.control-icon-button--danger:focus-visible {
  color: var(--danger);
}

.chat-resize-handle {
  position: absolute;
  right: 0;
  bottom: 0;
  z-index: 6;
  width: var(--iot-target-min);
  height: var(--iot-target-min);
  border: 0;
  border-radius: var(--iot-radius-control);
  background:
      linear-gradient(135deg, transparent 0 45%, color-mix(in srgb, var(--chat-muted) 64%, transparent) 46% 54%, transparent 55%),
      linear-gradient(135deg, transparent 0 62%, color-mix(in srgb, var(--chat-muted) 64%, transparent) 63% 71%, transparent 72%);
  cursor: nwse-resize;
  opacity: 0.65;
  touch-action: none;
}

.chat-resize-handle:hover,
.chat-resize-handle:focus-visible {
  opacity: 1;
  background-color: color-mix(in srgb, var(--chat-control-bg) 70%, transparent);
}

.messages-viewport {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0.9rem 1rem 1rem;
  scroll-behavior: smooth;
}

.scroll-spacer {
  height: 0.25rem;
  flex-shrink: 0;
}

.welcome-screen {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  max-width: 46rem;
  margin: 0 auto;
  padding: 0.85rem 0.15rem 1rem;
  color: var(--chat-text);
  text-align: center;
}

.brand-logo {
  margin-bottom: 0.65rem;
}

.logo-inner {
  width: 3.25rem;
  height: 3.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--chat-accent) 34%, var(--chat-border));
  border-radius: var(--iot-radius-panel);
  background: color-mix(in srgb, var(--chat-accent) 12%, var(--chat-control-bg));
  box-shadow: 0 12px 28px color-mix(in srgb, var(--chat-accent) 18%, transparent);
}

.custom-logo-img {
  width: 125%;
  height: 125%;
  object-fit: contain;
}

.task-grid {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(16rem, 100%), 1fr));
  gap: 0.55rem;
}

.task-card {
  width: 100%;
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: flex-start;
  gap: 0.6rem;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-card);
  background: var(--chat-ai-bg);
  color: var(--chat-text);
  cursor: pointer;
  padding: 0.65rem;
  text-align: left;
  font: inherit;
  transition: border-color 0.16s ease, background 0.16s ease, transform 0.16s ease, box-shadow 0.16s ease;
}

.task-card:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--chat-accent) 62%, var(--chat-border));
  background: color-mix(in srgb, var(--chat-accent) 9%, var(--chat-ai-bg));
  box-shadow: var(--shadow-raised);
  transform: translateY(-1px);
}

.task-card:disabled {
  cursor: not-allowed;
  opacity: 1;
  color: var(--chat-muted);
  border-color: var(--chat-border);
  background: var(--chat-control-bg);
  box-shadow: none;
  transform: none;
}

.task-card:disabled .task-title,
.task-card:disabled .task-icon {
  color: var(--chat-muted);
}

.task-card:disabled .task-icon {
  background: var(--chat-control-bg);
}

.task-icon {
  width: 2rem;
  height: 2rem;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--chat-accent) 14%, var(--chat-control-bg));
  color: var(--chat-accent);
  font-size: 1rem;
}

.task-info {
  min-width: 0;
  flex: 1;
}

.task-title {
  color: var(--chat-text);
  font-size: var(--iot-font-label);
  font-weight: 600;
}

.task-desc {
  margin-top: 0.15rem;
  color: var(--chat-muted);
  font-size: var(--iot-font-caption);
  line-height: 1.35;
}

.msg-row {
  width: 100%;
  max-width: 48rem;
  display: flex;
  gap: 0.65rem;
  margin: 0 auto 1rem;
}

.history-pagination {
  display: flex;
  justify-content: center;
  margin: 0 auto 0.8rem;
}

.history-pagination__button {
  min-height: var(--iot-target-min);
  border: 0;
  background: transparent;
  color: var(--chat-accent);
  cursor: pointer;
  padding: 0.35rem 0.55rem;
}

.history-pagination__button:disabled {
  cursor: wait;
  color: var(--chat-muted);
  opacity: 1;
}

.ai-row {
  flex-direction: row;
}

.user-row {
  flex-direction: row-reverse;
}

.avatar-container {
  width: 2rem;
  height: 2rem;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--iot-radius-well);
  font-size: var(--iot-font-control);
}

.ai-row .avatar-container {
  background: color-mix(in srgb, var(--chat-success) 16%, var(--chat-control-bg));
  color: var(--chat-success);
}

.user-row .avatar-container {
  background: color-mix(in srgb, var(--chat-accent) 16%, var(--chat-control-bg));
  color: var(--chat-accent);
}

.msg-content-wrapper {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.msg-body {
  min-width: 0;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-card);
  padding: 0.7rem 0.85rem;
  color: var(--chat-text);
  font-size: var(--iot-font-control);
  line-height: 1.6;
}

.user-msg-body {
  max-width: min(34rem, 100%);
  align-self: flex-end;
  border-color: color-mix(in srgb, var(--chat-accent) 32%, var(--chat-border));
  background: var(--chat-user-bg);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.vue-markdown-wrapper {
  max-width: 100%;
  background: var(--chat-ai-bg);
  overflow-wrap: anywhere;
}

.assistant-pending-body {
  width: fit-content;
  min-width: 8.5rem;
  max-width: 100%;
  align-self: flex-start;
  padding: 0.55rem 0.75rem;
  background: var(--chat-ai-bg);
}

.msg-body.has-execution-trace,
.assistant-pending-body.has-execution-trace {
  width: 100%;
  max-width: 100%;
  align-self: stretch;
}

.msg-actions {
  display: flex;
  gap: 0.35rem;
  margin-top: 0.25rem;
}

.action-btn-small {
  width: var(--iot-target-min);
  height: var(--iot-target-min);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: var(--iot-radius-action);
  background: transparent;
  color: var(--chat-muted);
  cursor: pointer;
}

.action-btn-small:hover:not(:disabled) {
  border-color: var(--chat-border);
  background: var(--chat-control-bg);
  color: var(--chat-text);
}

.thinking-state {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  color: var(--chat-muted);
  font-size: var(--iot-font-label);
}

.remote-execution-state {
  max-width: 48rem;
  margin: 0.75rem auto;
  padding: 0.7rem 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 0.65rem;
  border-top: 1px solid color-mix(in srgb, var(--chat-accent) 42%, var(--chat-border));
  border-bottom: 1px solid color-mix(in srgb, var(--chat-accent) 42%, var(--chat-border));
  color: var(--chat-text);
  font-size: var(--iot-font-label);
}

.remote-execution-state p {
  margin: 0.2rem 0 0;
  color: var(--chat-muted);
}

.reconciliation-state {
  max-width: 48rem;
  margin: 0.75rem auto;
  padding: 0.75rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  border: 1px solid color-mix(in srgb, var(--danger) 45%, var(--chat-border));
  border-radius: var(--iot-radius-well);
  background: color-mix(in srgb, var(--danger) 8%, var(--chat-ai-bg));
  color: var(--chat-text);
  font-size: var(--iot-font-label);
}

.chat-execution-trace {
  width: 100%;
  min-width: 0;
  color: var(--chat-text);
  font-size: var(--iot-font-label);
  overflow-wrap: anywhere;
}

.chat-execution-header {
  display: grid;
  grid-template-columns: 1.75rem minmax(0, 1fr) auto;
  align-items: center;
  gap: 0.65rem;
  cursor: pointer;
  min-height: 3.15rem;
  padding: 0.2rem 0 0.65rem;
  list-style: none;
  border-bottom: 1px solid var(--chat-border);
}

.chat-execution-header::-webkit-details-marker {
  display: none;
}

.chat-execution-chevron {
  width: 1.75rem;
  height: 1.75rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-control);
  color: var(--chat-muted);
  transition: transform 0.16s ease, color 0.16s ease, border-color 0.16s ease;
}

.chat-execution-trace[open] .chat-execution-chevron {
  transform: rotate(180deg);
  border-color: color-mix(in srgb, var(--chat-accent) 45%, var(--chat-border));
  color: var(--chat-accent);
}

.chat-execution-heading {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.55rem;
}

.chat-execution-heading > div {
  min-width: 0;
}

.chat-execution-icon {
  flex: 0 0 auto;
  width: 1.75rem;
  height: 1.75rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--iot-radius-control);
  background: color-mix(in srgb, var(--chat-accent) 11%, var(--chat-control-bg));
  color: var(--chat-accent);
}

.chat-execution-heading strong {
  display: block;
  font-size: var(--iot-font-control);
  line-height: 1.3;
}

.chat-execution-event-copy p {
  margin: 0.12rem 0 0;
  color: var(--chat-muted);
  font-weight: 400;
  line-height: 1.45;
}

/* Reasoning is the one entry carrying an argument rather than a status, so it keeps the model's
   paragraph and list breaks and reads at full contrast instead of as muted metadata. */
.chat-execution-event-copy p.is-reasoning-copy {
  white-space: pre-wrap;
  color: var(--chat-text);
  line-height: 1.55;
}

.chat-claim-review {
  margin-top: var(--iot-space-sm);
  min-width: 0;
  overflow-wrap: anywhere;
}

.chat-claim-review summary {
  width: fit-content;
  padding: var(--iot-space-2xs) 0;
  color: var(--chat-muted);
  cursor: pointer;
}

.chat-claim-review summary:hover,
.chat-claim-review summary:focus-visible {
  color: var(--chat-text);
}

.chat-claim-review blockquote {
  margin: var(--iot-space-sm) 0;
  padding: var(--iot-space-sm) var(--iot-space-md);
  border-inline-start: 2px solid var(--chat-border);
  border-radius: var(--iot-radius-control);
  background: var(--chat-control-bg);
  color: var(--chat-text);
  white-space: pre-wrap;
}

.chat-execution-meta {
  display: flex;
  align-items: flex-end;
  flex-direction: column;
  gap: 0.2rem;
  color: var(--chat-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.chat-execution-state {
  padding: 0.1rem 0.4rem;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-control);
  background: var(--chat-control-bg);
  color: var(--chat-muted);
  font-size: var(--iot-font-caption);
  font-weight: 600;
}

.chat-execution-state.is-running {
  border-color: color-mix(in srgb, var(--chat-accent) 48%, var(--chat-border));
  color: var(--chat-accent);
}

.chat-execution-state.is-stopped {
  border-color: color-mix(in srgb, var(--warning) 48%, var(--chat-border));
  color: var(--warning);
}

.chat-execution-state.is-failed {
  border-color: color-mix(in srgb, var(--danger) 48%, var(--chat-border));
  color: var(--danger);
}

.chat-terminal-status {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.65rem;
  color: var(--chat-muted);
  font-size: var(--iot-font-caption);
}

.chat-response-status {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.4rem 0.75rem;
  color: var(--chat-muted);
  font-size: var(--iot-font-caption);
  line-height: 1.5;
}

.chat-response-phase {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 0.4rem;
  color: var(--chat-text);
  overflow-wrap: anywhere;
}

.chat-response-phase .anticon {
  flex-shrink: 0;
}

.chat-response-time {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}

.chat-response-wait {
  flex-basis: 100%;
}

.chat-execution-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
  padding: 0.6rem 0;
  border-bottom: 1px solid var(--chat-border);
}

.chat-execution-metrics span {
  display: inline-flex;
  align-items: baseline;
  gap: 0.25rem;
  padding: 0.18rem 0.45rem;
  border-radius: var(--iot-radius-control);
  background: var(--chat-control-bg);
  color: var(--chat-muted);
}

.chat-execution-metrics strong {
  color: var(--chat-text);
  font-size: var(--iot-font-control);
  font-variant-numeric: tabular-nums;
}

.chat-execution-metrics .is-success strong {
  color: var(--chat-success);
}

.chat-execution-metrics .is-warning strong {
  color: var(--warning);
}

.chat-execution-metrics .is-error strong {
  color: var(--danger);
}

.chat-execution-events {
  max-height: min(22rem, 48vh);
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
  scrollbar-width: thin;
}

.chat-execution-trace + .chat-markdown-stub,
.chat-execution-trace + :deep(.markdown-body) {
  margin-top: 0.75rem;
}

.chat-execution-events li {
  display: grid;
  grid-template-columns: 1.55rem minmax(0, 1fr) auto;
  align-items: center;
  gap: 0.65rem;
  min-height: 3.2rem;
  padding: 0.55rem 0.15rem;
  border-bottom: 1px solid color-mix(in srgb, var(--chat-border) 65%, transparent);
  color: var(--chat-muted);
  line-height: 1.45;
}

.chat-execution-events li:last-child {
  border-bottom: 0;
}

.chat-execution-events li.is-current {
  color: var(--chat-text);
  background: color-mix(in srgb, var(--chat-accent) 5%, transparent);
}

.chat-execution-step {
  width: 1.4rem;
  height: 1.4rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--chat-border);
  border-radius: 50%;
  background: var(--chat-control-bg);
  color: var(--chat-muted);
  font-size: var(--iot-font-caption);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.chat-execution-event-copy {
  min-width: 0;
}

.chat-execution-event-copy strong {
  display: block;
  color: var(--chat-text);
  font-size: var(--iot-font-label);
  line-height: 1.35;
}

.chat-execution-outcome {
  justify-self: end;
  padding: 0.12rem 0.4rem;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-control);
  background: var(--chat-control-bg);
  color: var(--chat-muted);
  font-size: var(--iot-font-caption);
  font-weight: 600;
  white-space: nowrap;
}

.chat-execution-events li.is-success .chat-execution-step,
.chat-execution-events li.is-success .chat-execution-outcome {
  border-color: color-mix(in srgb, var(--chat-success) 48%, var(--chat-border));
  color: var(--chat-success);
}

.chat-execution-events li.is-warning .chat-execution-step,
.chat-execution-events li.is-warning .chat-execution-outcome {
  border-color: color-mix(in srgb, var(--warning) 48%, var(--chat-border));
  color: var(--warning);
}

.chat-execution-events li.is-error .chat-execution-step,
.chat-execution-events li.is-error .chat-execution-outcome {
  border-color: color-mix(in srgb, var(--danger) 48%, var(--chat-border));
  color: var(--danger);
}

.chat-execution-events li.is-reasoning .chat-execution-step {
  border-color: color-mix(in srgb, var(--chat-accent) 48%, var(--chat-border));
  background: color-mix(in srgb, var(--chat-accent) 9%, var(--chat-control-bg));
  color: var(--chat-accent);
}

.reconciliation-state p {
  margin: 0.2rem 0 0;
  color: var(--chat-muted);
}

.reconciliation-retry {
  flex: 0 0 auto;
  min-height: var(--iot-target-min);
  padding: 0.35rem 0.65rem;
  border: 1px solid var(--accent-fill);
  border-radius: var(--iot-radius-action);
  background: var(--accent-fill);
  color: var(--text-on-fill);
  cursor: pointer;
  font: inherit;
  font-weight: 600;
}

.reconciliation-retry:disabled {
  cursor: wait;
  background: var(--accent-fill-disabled);
  border-color: var(--accent-fill-disabled);
}

.reconciliation-retry:hover:not(:disabled) {
  background: var(--accent-fill-hover);
  border-color: var(--accent-fill-hover);
}

.reconciliation-retry:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}

.input-floating-area {
  position: relative;
  z-index: 2;
  flex: 0 0 auto;
  /* Reserve the resize corner so its target cannot cover the composer actions. */
  padding: 0.85rem 1rem var(--iot-target-min);
  background: linear-gradient(to top, var(--chat-bg) 86%, transparent);
}

.protected-confirmation {
  max-width: 48rem;
  margin: 0 auto 0.55rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 0.75rem;
  border: 1px solid color-mix(in srgb, var(--danger) 45%, var(--chat-border));
  border-radius: var(--iot-radius-well);
  /* The theme-aware danger tokens, not `#fff1f2` / `#991b1b` mixed with chat tokens. Those are rose-50
     and red-800: at 88% and 84% they dominated the mix, so in dark theme this error banner stayed a
     near-white card with dark red text — a light-theme surface inside a dark interface, which is the one
     thing a user reads as "something is broken about the app" rather than "something is wrong with my
     request". */
  background: color-mix(in srgb, var(--danger-surface) 88%, var(--chat-input-bg));
  color: var(--danger);
  padding: 0.65rem 0.75rem;
  box-shadow: var(--shadow-raised);
}

.protected-confirmation.is-load-error {
  border-color: color-mix(in srgb, var(--warning) 48%, var(--chat-border));
  background: color-mix(in srgb, var(--warning-surface) 88%, var(--chat-input-bg));
  color: var(--warning);
}

.protected-confirmation__copy,
.protected-confirmation__actions,
.protected-confirmation__button {
  display: flex;
  align-items: center;
}

.protected-confirmation__copy {
  min-width: 0;
  gap: 0.5rem;
  font-size: var(--iot-font-label);
  font-weight: 600;
}

.protected-confirmation__actions {
  flex-shrink: 0;
  gap: 0.4rem;
}

.protected-confirmation__button {
  min-height: var(--iot-target-min);
  gap: 0.3rem;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-action);
  padding: 0.4rem 0.6rem;
  background: var(--chat-input-bg);
  color: var(--chat-text);
  font: inherit;
  font-size: var(--iot-font-control);
  font-weight: 600;
  cursor: pointer;
}

.protected-confirmation__button.is-confirm {
  border-color: var(--danger);
  background: var(--danger-fill);
  color: var(--text-on-fill);
}

.protected-confirmation__button:disabled {
  cursor: not-allowed;
  color: var(--chat-muted);
  background: var(--chat-control-bg);
  opacity: 1;
}

/* Disabled confirmation keeps opaque label ink on the shared neutral fill. */
.protected-confirmation__button.is-confirm:disabled {
  border-color: transparent;
  background: var(--accent-fill-disabled);
  color: var(--text-on-fill);
}

.input-card {
  max-width: 48rem;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--chat-border);
  border-radius: var(--iot-radius-card);
  background: var(--chat-input-bg);
  box-shadow: var(--shadow-raised);
  padding: 0.65rem;
}

.input-card:focus-within {
  border-color: color-mix(in srgb, var(--chat-accent) 54%, var(--chat-border));
}

/* Keep activity and Stop in the fixed composer instead of reserving an unusable draft field. */
.input-card.is-streaming {
  flex-direction: row;
  align-items: center;
  gap: 0.75rem;
}

.input-card.is-streaming .modern-textarea,
.input-card.is-streaming .left-tools {
  display: none;
}

.input-card.is-streaming .input-actions {
  flex: 0 0 auto;
  align-self: flex-end;
  margin-top: 0;
}

.modern-textarea {
  width: 100%;
  min-height: var(--iot-target-min);
  max-height: min(8rem, 28vh);
  resize: none;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--chat-text);
  font: inherit;
  font-size: var(--iot-font-control);
  line-height: 1.45;
}

.chat-input-resize-handle {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 20px;
  flex: 0 0 20px;
  margin: -0.4rem 0 0.2rem;
  border-radius: var(--iot-radius-pill);
  cursor: ns-resize;
  touch-action: none;
}

.chat-input-resize-handle span {
  width: 2.5rem;
  height: 3px;
  border-radius: var(--iot-radius-pill);
  background: var(--chat-muted);
}

.chat-input-resize-handle:hover,
.chat-input-resize-handle:focus-visible {
  background: var(--chat-control-bg);
  outline: 2px solid var(--accent-border);
  outline-offset: 1px;
}

.modern-textarea::placeholder {
  color: var(--chat-muted);
}

.modern-textarea:disabled {
  cursor: not-allowed;
  color: var(--chat-muted);
  opacity: 1;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 0.45rem;
}

.left-tools,
.right-tools {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

.tool-icon,
.action-btn {
  width: var(--iot-target-min);
  height: var(--iot-target-min);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: var(--iot-radius-well);
  background: transparent;
  color: var(--chat-muted);
  cursor: pointer;
  font: inherit;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.tool-icon:hover:not(:disabled) {
  border-color: var(--chat-border);
  background: var(--chat-control-bg);
  color: var(--chat-text);
}

.new-chat-btn:disabled,
.control-icon-button:disabled,
.delete-btn-wrapper:disabled,
.action-btn-small:disabled,
.tool-icon:disabled {
  color: var(--chat-muted);
  background: var(--chat-control-bg);
  border-color: var(--chat-border);
  cursor: not-allowed;
}

.tool-icon.active:not(:disabled) {
  color: var(--danger);
}

.action-btn.send {
  border-color: var(--accent-fill);
  background: var(--accent-fill);
  color: var(--text-on-fill);
}

.action-btn.send:hover:not(:disabled),
.action-btn.send:focus-visible:not(:disabled) {
  border-color: var(--accent-fill-hover);
  background: var(--accent-fill-hover);
  transform: none;
}

.new-chat-btn:focus-visible,
.session-list-retry:focus-visible,
.session-select-button:focus-visible,
.control-icon-button:focus-visible,
.delete-btn-wrapper:focus-visible,
.chat-resize-handle:focus-visible,
.task-card:focus-visible,
.history-pagination__button:focus-visible,
.action-btn-small:focus-visible,
.protected-confirmation__button:focus-visible,
.modern-textarea:focus-visible,
.tool-icon:focus-visible,
.action-btn:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}

.action-btn.send:disabled {
  border-color: var(--chat-border);
  background: var(--chat-control-bg);
  color: var(--chat-muted);
  cursor: default;
}

.action-btn.stop {
  border-color: color-mix(in srgb, var(--danger) 44%, var(--chat-border));
  background: color-mix(in srgb, var(--danger) 10%, transparent);
  color: var(--danger);
}

.typing-indicator span {
  display: inline-block;
  width: 0.36rem;
  height: 0.36rem;
  margin: 0 0.1rem;
  border-radius: var(--iot-radius-pill);
  background: var(--chat-muted);
  animation: typing 1.4s infinite ease-in-out both;
}

.typing-indicator span:nth-child(1) {
  animation-delay: -0.32s;
}

.typing-indicator span:nth-child(2) {
  animation-delay: -0.16s;
}

.vue-markdown-wrapper :deep(*) {
  animation: fade-in 0.24s ease-in-out;
}

.vue-markdown-wrapper :deep(table) {
  width: 100%;
  margin: 0.75rem 0;
  border-collapse: collapse;
  font-size: var(--iot-font-label);
}

.vue-markdown-wrapper :deep(th),
.vue-markdown-wrapper :deep(td) {
  border: 1px solid var(--chat-border);
  padding: 0.45rem 0.6rem;
  text-align: left;
}

.vue-markdown-wrapper :deep(th) {
  background: var(--chat-control-bg);
  font-weight: 600;
}

.vue-markdown-wrapper :deep(p) {
  margin: 0 0 0.7rem;
  line-height: 1.6;
}

.vue-markdown-wrapper :deep(p:last-child) {
  margin-bottom: 0;
}

@keyframes fade-in {
  0% {
    opacity: 0;
    transform: translateY(0.2rem);
  }
  100% {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes typing {
  0%, 80%, 100% {
    transform: scale(0);
  }
  40% {
    transform: scale(1);
  }
}

@media (max-width: 720px) {
  .global-chat-wrapper,
  .global-chat-wrapper--board {
    --chat-safe-left: 0.5rem;
    --chat-safe-right: 0.5rem;
  }

  .chat-panel {
    top: calc(3.5rem + 0.5rem);
    right: var(--chat-safe-right);
    bottom: 0.5rem;
    left: var(--chat-safe-left);
    width: auto;
    height: auto;
    border-radius: var(--iot-radius-card);
  }

  /* The mobile panel already has a fixed viewport geometry; a resize
   * affordance only consumes space and is not a useful touch interaction. */
  .chat-resize-handle {
    display: none;
  }

  .glass-header {
    cursor: default;
    touch-action: auto;
  }

  .sidebar {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 20;
    width: min(17rem, 82vw);
    box-shadow: var(--shadow-floating);
  }

  .sidebar.collapsed {
    width: 0;
  }

  .task-grid {
    grid-template-columns: 1fr;
  }

  .messages-viewport {
    padding: 0.75rem;
  }

  .welcome-screen {
    align-items: stretch;
    text-align: left;
    padding-top: 0.25rem;
  }

  .brand-logo {
    display: none;
  }

  .task-card {
    padding: 0.6rem;
  }

  .msg-row {
    gap: 0.5rem;
    margin-bottom: 0.75rem;
  }

  .avatar-container {
    width: 1.8rem;
    height: 1.8rem;
  }

  .msg-body {
    padding: 0.6rem 0.7rem;
  }

  .chat-execution-header {
    grid-template-columns: 1.55rem minmax(0, 1fr);
    gap: 0.5rem;
  }

  .chat-execution-icon {
    display: none;
  }

  .chat-execution-meta {
    grid-column: 2;
    align-items: center;
    flex-direction: row;
    flex-wrap: wrap;
  }

  .chat-execution-events li {
    grid-template-columns: 1.4rem minmax(0, 1fr);
    align-items: start;
    gap: 0.5rem;
  }

  .chat-execution-outcome {
    grid-column: 2;
    justify-self: start;
  }

  .chat-execution-events {
    max-height: min(12rem, 24vh);
  }

  .input-floating-area {
    padding: 0.65rem 0.75rem 0.75rem;
  }
}

@media (max-height: 720px) {
  .chat-panel {
    top: calc(4rem + 0.5rem);
    height: min(38rem, calc(100dvh - 5rem));
  }

  .glass-header {
    min-height: 3rem;
    padding-block: 0.55rem;
  }

  .messages-viewport {
    padding-bottom: 0.75rem;
  }

  .brand-logo {
    display: none;
  }

  .welcome-screen {
    justify-content: flex-start;
    padding-top: 0.25rem;
  }

  .task-card {
    padding: 0.55rem;
  }

}
</style>
