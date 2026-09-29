package cn.edu.nju.Iot_Verify.component.nusmv.fixer;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.fix.FaultRuleDto;
import cn.edu.nju.Iot_Verify.dto.fix.ParameterTarget;
import cn.edu.nju.Iot_Verify.dto.fix.PreferredRange;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import lombok.Builder;
import lombok.Getter;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * State of one fix request shared by the {@link FixStrategy} implementations it runs: the frozen
 * inputs (rules, devices, specifications, counterexample, deadline), plus the per-strategy outcomes
 * and alternatives-search timing that {@link RuleFixer} reads back into each strategy attempt.
 */
@Getter
@Builder
public class FixContext {
    private final Long traceId;
    private final List<FaultRuleDto> faultRules;
    /**
     * Rule indices a strategy may edit or remove: the localized fault rules first, then the other rules
     * that can influence the violated property (see {@code RuleInfluenceScope}). Built once by
     * {@link RuleFixer} so the three strategies search the same scope.
     */
    @Builder.Default
    private final List<Integer> repairRuleIndices = List.of();
    private final List<RuleDto> allRules;
    private final List<DeviceVerificationDto> devices;
    @Builder.Default
    private final List<BoardEnvironmentVariableDto> environmentVariables = List.of();
    private final List<SpecificationDto> specs;
    private final Map<String, DeviceSmvData> deviceSmvMap;
    private final int violatedSpecIndex;
    private final Long userId;
    private final AttackScenarioDto attackScenario;
    private final boolean enablePrivacy;
    private final int maxAttempts;
    private final Map<String, PreferredRange> preferredRanges;
    /** First complete state of the persisted counterexample used only for candidate solving. */
    private final TraceStateDto counterexampleInitialState;
    /** Production fixer paths require replay; low-level strategy tests may omit it explicitly. */
    private final boolean requireCounterexampleReplay;
    private final Instant deadline;
    /** The time checked against {@link #deadline} and the alternatives search, so tests can advance it. */
    @Builder.Default
    private final Clock clock = Clock.systemUTC();

    @Builder.Default
    private final Set<String> diagnostics = new LinkedHashSet<>();
    @Builder.Default
    private final Map<String, String> strategyGenerationFailures = new LinkedHashMap<>();
    @Builder.Default
    private final Map<String, String> strategySolverFailures = new LinkedHashMap<>();
    @Builder.Default
    private final Map<String, StrategyNoResult> strategyNoResults = new LinkedHashMap<>();
    @Builder.Default
    private final Map<String, StrategySearchProgress> strategySearchProgress = new LinkedHashMap<>();
    @Builder.Default
    private final Map<String, ParameterTarget> parameterTargets = new LinkedHashMap<>();
    @Builder.Default
    private final Set<String> matchedPreferredRangeTargetIds = new LinkedHashSet<>();
    /**
     * Shortest search for further alternatives. Without it a strategy that finds its first repair within a
     * second or two would get no time to look for others, while it is exactly then that looking is cheap.
     * On the demo Boards one candidate costs a solver run and a forward verification of a second or two
     * together, so this covers several candidates beyond the first repair.
     */
    static final Duration MIN_ALTERNATIVES_SEARCH = Duration.ofSeconds(10);

    /** Strategies from the current one to the end of the request that will actually run. */
    private int strategiesLeft;
    /** When the running strategy started; null when a strategy is driven without {@link #beginStrategy}. */
    private Instant strategyStartedAt;
    /**
     * Set once the running strategy has found its first verified repair. The user already has an answer
     * then, so looking for alternatives must not cost unboundedly more than getting that answer did, nor
     * more than a fair share of the time left to the strategies still to run. The request deadline alone
     * would let a strategy that is the only one requested spend minutes on alternatives after it found
     * a repair in seconds.
     */
    private volatile Instant alternativesDeadline;
    /**
     * Verdict of the unmodified rules on the forward-verification model, needed only once a candidate
     * repairs the target while other specifications still fail. The holder is set even when the verdict
     * could not be obtained, so a failure is not retried; a run cut off by the deadline or a
     * cancellation leaves it unset.
     */
    private BaselineVerdict baselineVerdict;

    /**
     * Returns true when the search must stop: the deadline has passed, or this worker was
     * interrupted because the request was cancelled.
     *
     * <p>Interruption is checked here rather than left to each strategy loop. A strategy's broad
     * {@code catch (Exception)} consumes the {@code InterruptedException} that
     * {@code Semaphore.tryAcquire} throws — which also clears the flag — so a loop that only
     * watched the deadline would keep launching NuSMV runs for a request whose response was already
     * sent, holding permits from the shared executor semaphore the whole time. {@code isInterrupted}
     * is non-clearing, so asking here does not hide the signal from anyone else.
     *
     * <p>Once the running strategy has a verified repair, its time for further alternatives counts
     * too (see {@link #startAlternativesSearch()}).
     */
    public boolean isExpired() {
        Instant effective = effectiveDeadline();
        return Thread.currentThread().isInterrupted()
                || (effective != null && clock.instant().isAfter(effective));
    }

    /** Remaining wall-clock budget for a child NuSMV call, in whole milliseconds. */
    public long remainingMillis() {
        Instant effective = effectiveDeadline();
        if (effective == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, Duration.between(clock.instant(), effective).toMillis());
    }

    private Instant effectiveDeadline() {
        Instant alternatives = alternativesDeadline;
        if (alternatives == null) return deadline;
        return deadline == null || alternatives.isBefore(deadline) ? alternatives : deadline;
    }

    /**
     * Start one strategy's run: forget what the previous run of this strategy recorded, and how the
     * previous strategy shared the clock.
     *
     * @param strategiesLeft the strategies from this one to the end of the request that will run
     */
    public synchronized void beginStrategy(String strategy, int strategiesLeft) {
        strategyGenerationFailures.remove(strategy);
        strategySolverFailures.remove(strategy);
        strategyNoResults.remove(strategy);
        this.strategiesLeft = Math.max(1, strategiesLeft);
        strategyStartedAt = clock.instant();
        alternativesDeadline = null;
    }

    /**
     * The running strategy found its first verified repair. From now on it looks for alternatives for as
     * long again as that repair took, but at least {@link #MIN_ALTERNATIVES_SEARCH}, so the wait for the
     * others is proportional to the wait for the first answer. It never gets more than an equal share of
     * the remaining time among itself and the strategies still to run, so listing alternatives cannot
     * starve a later strategy. A later repair of the same strategy does not restart this window. Nothing
     * is bounded without a request deadline, which production always sets.
     */
    public synchronized void startAlternativesSearch() {
        if (deadline == null || alternativesDeadline != null) return;
        Instant now = clock.instant();
        long toFirstRepair = strategyStartedAt == null ? 0 : Duration.between(strategyStartedAt, now).toMillis();
        long share = Duration.between(now, deadline).toMillis() / Math.max(1, strategiesLeft);
        long window = Math.min(share, Math.max(toFirstRepair, MIN_ALTERNATIVES_SEARCH.toMillis()));
        alternativesDeadline = now.plusMillis(window);
    }

    public synchronized void addDiagnostic(String diagnostic) {
        if (diagnostic != null && !diagnostic.isBlank()) {
            diagnostics.add(diagnostic);
        }
    }

    public synchronized List<String> diagnosticsSnapshot() {
        return new ArrayList<>(diagnostics);
    }

    public synchronized void recordStrategyGenerationFailure(String strategy, String reason) {
        if (strategy != null && !strategy.isBlank() && reason != null && !reason.isBlank()) {
            strategyGenerationFailures.put(strategy, reason);
        }
    }

    public synchronized String strategyGenerationFailure(String strategy) {
        return strategyGenerationFailures.get(strategy);
    }

    public synchronized void clearStrategySolverFailure(String strategy) {
        strategySolverFailures.remove(strategy);
    }

    public synchronized void recordStrategySolverFailure(String strategy, String reason) {
        if (strategy != null && !strategy.isBlank() && reason != null && !reason.isBlank()) {
            strategySolverFailures.put(strategy, reason);
        }
    }

    public synchronized String strategySolverFailure(String strategy) {
        return strategySolverFailures.get(strategy);
    }

    public synchronized void recordStrategyNoResult(String strategy, String status, String reason) {
        if (strategy != null && !strategy.isBlank() && status != null && !status.isBlank()
                && reason != null && !reason.isBlank()) {
            strategyNoResults.put(strategy, new StrategyNoResult(status, reason));
        }
    }

    public synchronized StrategyNoResult strategyNoResult(String strategy) {
        return strategyNoResults.get(strategy);
    }

    public synchronized void initializeStrategySearch(String strategy, int attemptLimit) {
        if (strategy != null && !strategy.isBlank()) {
            strategySearchProgress.put(strategy,
                    new StrategySearchProgress(0, Math.max(0, attemptLimit)));
        }
    }

    public synchronized void addStrategyAttempts(String strategy, int attempts) {
        if (strategy == null || strategy.isBlank() || attempts <= 0) {
            return;
        }
        StrategySearchProgress current = strategySearchProgress.get(strategy);
        if (current == null) {
            strategySearchProgress.put(strategy, new StrategySearchProgress(attempts, attempts));
            return;
        }
        long updated = (long) current.attemptsUsed() + attempts;
        strategySearchProgress.put(strategy, new StrategySearchProgress(
                (int) Math.min(updated, current.attemptLimit()), current.attemptLimit()));
    }

    public synchronized StrategySearchProgress strategySearchProgress(String strategy) {
        return strategySearchProgress.get(strategy);
    }

    public synchronized boolean hasStrategyAttemptsLeft(String strategy) {
        StrategySearchProgress progress = strategySearchProgress.get(strategy);
        return progress != null && progress.attemptsUsed() < progress.attemptLimit();
    }

    public synchronized void registerParameterTarget(ParameterTarget target) {
        if (target != null && target.getTargetId() != null && !target.getTargetId().isBlank()) {
            parameterTargets.putIfAbsent(target.getTargetId(), target);
        }
    }

    public synchronized List<ParameterTarget> parameterTargetsSnapshot() {
        return new ArrayList<>(parameterTargets.values());
    }

    public synchronized void markPreferredRangeTargetMatched(String targetId) {
        if (targetId != null && !targetId.isBlank()) {
            matchedPreferredRangeTargetIds.add(targetId);
        }
    }

    public synchronized Set<String> matchedPreferredRangeTargetIdsSnapshot() {
        return new LinkedHashSet<>(matchedPreferredRangeTargetIds);
    }

    public synchronized BaselineVerdict baselineVerdict() {
        return baselineVerdict;
    }

    public synchronized void recordBaselineVerdict(BaselineVerdict verdict) {
        if (baselineVerdict == null) {
            baselineVerdict = Objects.requireNonNull(verdict, "verdict");
        }
    }

    public AttackScenarioDto resolvedAttackScenario() {
        return Objects.requireNonNull(attackScenario, "attackScenario is required");
    }

    public record StrategyNoResult(String status, String reason) {
    }

    public record StrategySearchProgress(int attemptsUsed, int attemptLimit) {
    }

    /**
     * @param violatedSpecIds the specifications the original rules violate; {@code null} when that
     *                        could not be established, in which case no candidate may leave any
     *                        specification failing
     */
    public record BaselineVerdict(Set<String> violatedSpecIds) {
        public static BaselineVerdict unavailable() {
            return new BaselineVerdict(null);
        }

        public boolean available() {
            return violatedSpecIds != null;
        }
    }
}
