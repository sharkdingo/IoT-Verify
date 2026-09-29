package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy.StrategyOutcome;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreexistingViolationDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The verified repairs one strategy lists for the user to choose from.
 *
 * <p>A repair is identified by what it changes: the removed rules, the flipped conditions, the moved
 * thresholds. Only minimal ones are listed — a repair whose changes contain another listed repair's
 * changes edits more of the board without being needed, and the user cannot tell it apart from a real
 * alternative. Strategies exclude every superset of a listed change set from their search; this
 * collector keeps the invariant regardless of the order in which repairs arrive.
 *
 * <p>The list is complete only when the strategy exhausted its search space and every candidate it could
 * not check is covered by a listed repair. A candidate that was not checked might have been a repair;
 * claiming that none is missing would then be a guess.
 */
final class FixAlternatives {

    /** More alternatives than this stop being a choice and become a list to scroll. */
    static final int LIMIT = 5;

    private record Entry(Set<String> changes, FixSuggestionDto suggestion) {}

    private final FixContext ctx;
    private final List<Entry> listed = new ArrayList<>();
    private final List<Set<String>> unsettled = new ArrayList<>();
    private boolean anyRejected;

    FixAlternatives(FixContext ctx) {
        this.ctx = ctx;
    }

    boolean full() {
        return listed.size() >= LIMIT;
    }

    /** Whether some listed repair changes only part of {@code changes}. */
    boolean covers(Set<String> changes) {
        return listed.stream().anyMatch(entry -> changes.containsAll(entry.changes()));
    }

    /** The change sets listed so far, from which strategies exclude supersets. */
    List<Set<String>> listedChanges() {
        return listed.stream().map(Entry::changes).toList();
    }

    /** Whether forward verification rejected at least one candidate, as opposed to none reaching it. */
    boolean anyRejected() {
        return anyRejected;
    }

    /** A candidate forward verification did not accept. */
    void notAccepted(Set<String> changes, FixStrategyUtils.Verification verification) {
        if (verification.verdict() == FixStrategyUtils.Verdict.INCONCLUSIVE) {
            unsettled.add(Set.copyOf(changes));
        } else {
            anyRejected = true;
        }
    }

    /**
     * List a verified repair. One that a listed repair already covers is dropped; listed repairs this
     * one covers are replaced by it. The first repair starts this strategy's share of the time for
     * finding the others (see {@link FixContext#startAlternativesSearch()}).
     */
    void accept(Set<String> changes, FixSuggestionDto suggestion, FixStrategyUtils.Verification verification) {
        if (!verification.isAccepted()) {
            throw new IllegalArgumentException("Only an accepted candidate can be listed");
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("A repair must change something");
        }
        if (covers(changes)) return;
        listed.removeIf(entry -> entry.changes().containsAll(changes));
        suggestion.setPreexistingViolations(preexistingViolations(verification.preexistingViolationIds()));
        listed.add(new Entry(Set.copyOf(changes), suggestion));
        ctx.startAlternativesSearch();
    }

    /**
     * @param exhausted whether the strategy's search space was exhausted, rather than cut short by the
     *                  listing limit, the attempt budget, the time share or an error
     */
    StrategyOutcome outcome(boolean exhausted) {
        boolean complete = exhausted && unsettled.stream().allMatch(this::covers);
        return new StrategyOutcome(listed.stream()
                .sorted(Comparator.comparingInt(entry -> entry.changes().size()))
                .map(Entry::suggestion)
                .toList(), complete);
    }

    private List<PreexistingViolationDto> preexistingViolations(List<String> specIds) {
        if (specIds.isEmpty()) return List.of();
        SpecificationFormulaPreview.Context preview =
                SpecificationFormulaPreview.modelContext(ctx.getDevices(), Map.of());
        List<PreexistingViolationDto> violations = new ArrayList<>();
        for (String specId : specIds) {
            SpecificationDto spec = ctx.getSpecs().stream()
                    .filter(candidate -> candidate != null && Objects.equals(specId, candidate.getId()))
                    .findFirst()
                    // forwardVerify only reports ids it read from this request's own emitted specifications.
                    .orElseThrow(() -> new IllegalStateException(
                            "Accepted pre-existing violation names an unknown specification: " + specId));
            violations.add(PreexistingViolationDto.builder()
                    .specId(specId)
                    .templateId(spec.getTemplateId())
                    .formulaPreview(SpecificationFormulaPreview.format(spec, preview))
                    .build());
        }
        return violations;
    }
}
