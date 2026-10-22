package cn.edu.nju.Iot_Verify.component.nusmv;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.dto.verification.AntecedentReachability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pairs each NuSMV per-property verdict with the specification the generator emitted for it.
 *
 * <p>Board verification and automatic-fix forward verification both need to know <em>which</em>
 * specification a verdict describes, not only whether all of them passed, so the matching rule lives
 * here once rather than in each caller.
 */
public final class SpecResultAlignment {

    private SpecResultAlignment() {
    }

    /**
     * @param aligned    one slot per emitted specification, in emission order; a slot is {@code null}
     *                   when no result could be paired with it at all
     * @param backFilled how many slots were paired by position because no expression matched — a guess,
     *                   so any positive value means per-specification attribution is not trustworthy
     * @param reordered  whether NuSMV returned results in a different order than emitted
     */
    public record Alignment(List<SpecCheckResult> aligned, List<SpecCheckResult> antecedents,
                            int expectedResultCount, int backFilled, boolean reordered) {
        public AntecedentReachability antecedentReachability(int index,
                SmvGenerationContext.EmittedSpec emitted) {
            if (emitted.spec() == null
                    || !AntecedentReachability.appliesTo(emitted.spec().getTemplateId())) {
                return AntecedentReachability.NOT_APPLICABLE;
            }
            SpecCheckResult result = antecedents.get(index);
            if (backFilled > 0 || result == null) return AntecedentReachability.INCONCLUSIVE;
            return result.isPassed() ? AntecedentReachability.UNREACHABLE : AntecedentReachability.REACHABLE;
        }
    }

    public static Alignment align(List<SpecCheckResult> rawSpecResults,
                                  List<SmvGenerationContext.EmittedSpec> emittedSpecs) {
        Map<String, Deque<SpecCheckResult>> byExpression = new HashMap<>();
        Set<SpecCheckResult> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SpecCheckResult result : rawSpecResults) {
            if (result == null) continue;
            String key = normalizeSpecExpression(result.getSpecExpression());
            if (key.isBlank()) continue;
            byExpression.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(result);
        }

        List<SpecCheckResult> aligned = new ArrayList<>(Collections.nCopies(emittedSpecs.size(), null));
        List<SpecCheckResult> antecedents = new ArrayList<>(Collections.nCopies(emittedSpecs.size(), null));
        int expectedResultCount = emittedSpecs.size();
        boolean reordered = false;
        for (int i = 0; i < emittedSpecs.size(); i++) {
            SmvGenerationContext.EmittedSpec emittedSpec = emittedSpecs.get(i);
            String key = normalizeSpecExpression(emittedSpec != null ? emittedSpec.expression() : null);
            Deque<SpecCheckResult> matches = byExpression.get(key);
            if (matches == null || matches.isEmpty()) {
                continue;
            }
            SpecCheckResult matched = matches.removeFirst();
            aligned.set(i, matched);
            used.add(matched);
            if (i >= rawSpecResults.size() || rawSpecResults.get(i) != matched) {
                reordered = true;
            }
        }

        // Reserve auxiliary verdicts before considering positional recovery. A failed !EF query is
        // diagnostic evidence, never a failed user property or a counterexample to that property.
        for (int i = 0; i < emittedSpecs.size(); i++) {
            var emitted = emittedSpecs.get(i);
            if (emitted == null || emitted.antecedentExpression() == null) continue;
            expectedResultCount++;
            Deque<SpecCheckResult> matches = byExpression.get(normalizeSpecExpression(emitted.antecedentExpression()));
            if (matches != null && !matches.isEmpty()) {
                SpecCheckResult matched = matches.removeFirst();
                antecedents.set(i, matched);
                used.add(matched);
            }
        }

        Deque<SpecCheckResult> unmatchedInOriginalOrder = new ArrayDeque<>();
        for (SpecCheckResult result : rawSpecResults) {
            if (result != null && !used.contains(result)) {
                unmatchedInOriginalOrder.add(result);
            }
        }
        /*
         * Positional back-fill for results no expression matched — and it reports how many.
         *
         * This is a *guess*: it pairs leftover results with unfilled slots by position. The count must reach
         * the caller because the result set still looks complete afterwards, so nothing else would reveal that
         * per-specification attribution was guessed. An all-pass verdict is order-independent; anything that
         * depends on *which* specification failed is not.
         *
         * It should not fire: NuSMV echoes each specification verbatim, so the expression match succeeds even
         * when it reorders them. If it does fire, something changed about that echo.
         */
        int backFilled = 0;
        for (int i = 0; expectedResultCount == emittedSpecs.size()
                && i < aligned.size() && !unmatchedInOriginalOrder.isEmpty(); i++) {
            if (aligned.get(i) == null) {
                aligned.set(i, unmatchedInOriginalOrder.removeFirst());
                backFilled++;
            }
        }
        return new Alignment(aligned, antecedents, expectedResultCount, backFilled, reordered);
    }

    /*
     * Why stripping parentheses is safe here, and what makes it safe.
     *
     * This looks alarming in isolation: dropping every parenthesis makes semantically *different* formulas
     * normalize alike — `AG (a & (b | c))` and `AG ((a & b) | c)` both become `aga&b|c`, and NuSMV really does
     * give them different verdicts (measured on 2.7.1: `false` and `true` respectively). Colliding keys share one
     * Deque, so attribution would then depend on arrival order — and NuSMV *does* reorder: three specs submitted
     * `AG (a & (b|c))`, `AG ((a&b)|c)`, `AG a` came back with `AG a` first.
     *
     * It is nevertheless unreachable, because a collision needs two specs from the same template with identical
     * operands — i.e. a duplicate — and `BoardStorageServiceImpl.validateNoIdenticalSpecifications` rejects that
     * with a `ConflictException` on both spec-write paths (`saveSpecsInternal` and `saveBoardBatch`). The seven
     * templates in `docs/architecture/spec-templates.md` differ by operator (`AG`/`AF`, `AX`/`AF`, `EX`/`EG`),
     * which stripping does not touch, so all seven yield distinct keys. Auxiliary EF queries can share
     * a trigger across responses, but then describe the same predicate and have the same truth value.
     *
     * The leniency is not gratuitous either: matching by expression is what handles the reordering above. If a
     * future change lets two distinct specs share a normalized key — a new template, or relaxing the duplicate
     * check — the positional back-fill above becomes a silent misattribution, so re-derive this note then.
     */
    static String normalizeSpecExpression(String expression) {
        if (expression == null) {
            return "";
        }
        String normalized = expression.trim()
                .replaceFirst("(?i)^CTL\\s*SPEC\\s+", "")
                .replaceFirst("(?i)^LTL\\s*SPEC\\s+", "")
                .toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("\\s+", "");
        normalized = normalized.replace("(", "").replace(")", "");
        return normalized;
    }
}
