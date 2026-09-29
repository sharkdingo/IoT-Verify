package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.parameterize.ParameterizationConfig;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.GuardProbe;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generalizes a condition assignment that forward verification rejected to every assignment the
 * rejection's violating path cannot tell apart from it.
 *
 * <p>The condition search's models differ only in the trigger conditions of the rules in repair scope.
 * Along a path π of the rejected candidate's model, another assignment's model takes the same
 * transitions as long as each scoped rule's raw guard has the same value in every state of π where the
 * rule's other guard terms (the command's start state, and under attack the delivery guard) hold:
 * the selected branches, and so every rule-driven variable, then agree. Trust and privacy labels are
 * the exception — their transitions read the conditions' own labels — but no rule guard reads them, so
 * the path's projection onto every other variable is still a path of the other model. A specification
 * that reads no trust or privacy label is then violated on it too, and forward verification would
 * reject that assignment on the same specification: as the target it still fails, or as one the
 * original rules satisfied it is newly broken. The lemma excludes exactly those assignments.
 *
 * <p>The values it needs are read from the violating path itself: NuSMV prints every {@code DEFINE}
 * on it, and {@link GuardProbe}s are rendered by the code that renders the guards, so no second
 * evaluator of rule conditions exists. Anything unreadable fails closed — no lemma is learned and only
 * the exact assignment is excluded.
 */
@Slf4j
final class CounterexampleLemma {

    /** The lemma that excludes every assignment: all of them share the rejected one's violation. */
    static final String REFUTES_ALL = "FALSE";

    private static final Pattern STATE_LINE = Pattern.compile("^->\\s*State:\\s*\\d+\\.\\d+\\s*<-$");
    private static final Pattern INPUT_LINE = Pattern.compile("^->\\s*Input:\\s*\\d+\\.\\d+\\s*<-$");
    private static final Pattern ASSIGNMENT = Pattern.compile("^([A-Za-z_][\\w.$#\\[\\]]*)\\s*=\\s*(\\S+)$");
    /** An observed operand value spliced into the lemma must be a single NuSMV literal. */
    private static final Pattern LITERAL = Pattern.compile("^-?[A-Za-z0-9_$#]+$");
    private static final Pattern CONDITION_KEY = Pattern.compile("^r(\\d+)_c\\d+$");

    /**
     * One condition of a scoped rule in the search model.
     *
     * @param lambda        the search variable that keeps or adds the condition
     * @param valueVariable for a candidate whose value the search chooses, that variable; the atom is then
     *                      that choice related to the observed operand, otherwise it is fixed
     * @param relation      for a chosen-value candidate, its normalized relation
     */
    private record Atom(String lambda, String valueVariable, String relation, GuardProbe probe) {
        boolean chosenValue() {
            return valueVariable != null;
        }
    }

    private record ScopedRule(int ruleIndex, List<Atom> atoms) {}

    private final List<ScopedRule> scopedRules;
    private final List<GuardProbe> atomProbes;

    private CounterexampleLemma(List<ScopedRule> scopedRules, List<GuardProbe> atomProbes) {
        this.scopedRules = scopedRules;
        this.atomProbes = atomProbes;
    }

    /**
     * @param augmentedRules          the rules with candidate conditions appended, as the search model has them
     * @param conditionLambdas        the search's {@code r<rule>_c<condition>} keys and their variables
     * @param candidateConditionValues the candidates whose value the search chooses
     * @return {@code null} when some scoped condition cannot be described, in which case nothing is learned
     */
    static CounterexampleLemma plan(
            List<RuleDto> augmentedRules,
            Map<String, String> conditionLambdas,
            Map<String, ParameterizationConfig.ConditionValueInfo> candidateConditionValues) {
        Set<Integer> ruleIndices = new LinkedHashSet<>();
        for (String key : conditionLambdas.keySet()) {
            int ruleIndex = ruleIndexOf(key);
            if (ruleIndex < 0 || ruleIndex >= augmentedRules.size()) return null;
            ruleIndices.add(ruleIndex);
        }
        List<ScopedRule> scopedRules = new ArrayList<>();
        List<GuardProbe> atomProbes = new ArrayList<>();
        for (int ruleIndex : ruleIndices) {
            List<RuleDto.Condition> conditions = augmentedRules.get(ruleIndex).getConditions();
            List<Atom> atoms = new ArrayList<>();
            for (int conditionIndex = 0; conditionIndex < conditions.size(); conditionIndex++) {
                RuleDto.Condition condition = conditions.get(conditionIndex);
                String key = "r" + ruleIndex + "_c" + conditionIndex;
                String lambda = conditionLambdas.get(key);
                // A rule the search cannot fully switch has a guard this lemma cannot describe.
                if (condition == null || lambda == null) return null;
                ParameterizationConfig.ConditionValueInfo valueInfo = candidateConditionValues.get(key);
                Atom atom;
                if (valueInfo != null) {
                    String relation = SmvRelationUtils.normalizeRelation(condition.getRelation());
                    if (relation == null || !SmvRelationUtils.isSupportedRelation(relation)) return null;
                    atom = new Atom(lambda, valueInfo.getFrozenVarName(), relation,
                            GuardProbe.ofCondition(atomProbes.size(), GuardProbe.Kind.OPERAND, condition));
                } else {
                    atom = new Atom(lambda, null, null,
                            GuardProbe.ofCondition(atomProbes.size(), GuardProbe.Kind.CONDITION, condition));
                }
                atomProbes.add(atom.probe());
                atoms.add(atom);
            }
            scopedRules.add(new ScopedRule(ruleIndex, List.copyOf(atoms)));
        }
        return new CounterexampleLemma(List.copyOf(scopedRules), List.copyOf(atomProbes));
    }

    /** The probes to add to the forward-verification model of one candidate. */
    Attempt attempt(List<RuleDto> candidateRules) {
        List<GuardProbe> probes = new ArrayList<>(atomProbes);
        Map<Integer, GuardProbe> baseProbes = new HashMap<>();
        Map<Integer, GuardProbe> conditionProbes = new HashMap<>();
        for (ScopedRule rule : scopedRules) {
            RuleDto candidateRule = candidateRules.get(rule.ruleIndex());
            GuardProbe base = GuardProbe.ofRule(probes.size(), GuardProbe.Kind.RULE_BASE, candidateRule);
            probes.add(base);
            GuardProbe conditions = GuardProbe.ofRule(probes.size(), GuardProbe.Kind.RULE_CONDITIONS, candidateRule);
            probes.add(conditions);
            baseProbes.put(rule.ruleIndex(), base);
            conditionProbes.put(rule.ruleIndex(), conditions);
        }
        return new Attempt(List.copyOf(probes), baseProbes, conditionProbes);
    }

    final class Attempt {
        private final List<GuardProbe> probes;
        private final Map<Integer, GuardProbe> baseProbes;
        private final Map<Integer, GuardProbe> conditionProbes;

        private Attempt(List<GuardProbe> probes,
                        Map<Integer, GuardProbe> baseProbes,
                        Map<Integer, GuardProbe> conditionProbes) {
            this.probes = probes;
            this.baseProbes = baseProbes;
            this.conditionProbes = conditionProbes;
        }

        List<GuardProbe> probes() {
            return probes;
        }

        /**
         * The lemma a rejection proves: an {@code INVAR} over the search variables that holds exactly for
         * the assignments whose guards differ from the rejected candidate's somewhere on its violating
         * path. {@link #REFUTES_ALL} when no assignment's do.
         *
         * @return {@code null} when nothing can be learned from this rejection
         */
        String learn(FixStrategyUtils.Verification verification, List<SpecificationDto> specs) {
            FixStrategyUtils.Witness witness = verification.witness();
            if (witness == null) return null;
            SpecificationDto spec = uniqueSpec(specs, witness.specId());
            if (spec == null || readsSecurityLabels(spec)) {
                log.debug("No condition lemma: the violated specification {} is missing or reads trust/privacy labels",
                        witness.specId());
                return null;
            }
            List<Map<String, String>> states = valuations(witness.counterexample(),
                    probes.stream().map(GuardProbe::name).toList());
            if (states == null) {
                log.debug("No condition lemma: the violating path does not report every guard probe");
                return null;
            }
            Set<String> disjuncts = new LinkedHashSet<>();
            for (Map<String, String> state : states) {
                for (ScopedRule rule : scopedRules) {
                    String base = state.get(baseProbes.get(rule.ruleIndex()).name());
                    if ("FALSE".equals(base)) continue;
                    if (!"TRUE".equals(base)) return failClosed("rule base", base);
                    String conditions = state.get(conditionProbes.get(rule.ruleIndex()).name());
                    if (!"TRUE".equals(conditions) && !"FALSE".equals(conditions)) {
                        return failClosed("rule conditions", conditions);
                    }
                    List<String> literals = new ArrayList<>();
                    for (Atom atom : rule.atoms()) {
                        String observed = state.get(atom.probe().name());
                        String literal = literal(atom, observed);
                        if (literal == null) return failClosed("condition", observed);
                        if (!literal.equals("TRUE")) literals.add(literal);
                    }
                    if ("TRUE".equals(conditions)) {
                        // The rejected candidate's guard held here; an assignment differs where it fails.
                        // With no constraint left, every assignment's guard holds too.
                        if (!literals.isEmpty()) disjuncts.add("!(" + String.join(" & ", literals) + ")");
                    } else {
                        // The rejected candidate's guard failed here; an assignment differs where it holds.
                        // With no constraint left every condition held, contradicting the reported guard.
                        if (literals.isEmpty()) return failClosed("rule conditions", conditions);
                        disjuncts.add("(" + String.join(" & ", literals) + ")");
                    }
                }
            }
            return disjuncts.isEmpty() ? REFUTES_ALL : "(" + String.join(" | ", disjuncts) + ")";
        }
    }

    /**
     * The condition's value in the search model, given what the path reports for it: {@code TRUE} when it
     * holds whatever the search chooses, otherwise the constraint on the search variables.
     */
    private static String literal(Atom atom, String observed) {
        if (observed == null) return null;
        if (!atom.chosenValue()) {
            if ("TRUE".equals(observed)) return "TRUE";
            return "FALSE".equals(observed) ? "!" + atom.lambda() : null;
        }
        if (!LITERAL.matcher(observed).matches()) return null;
        String relation = SmvRelationUtils.ruleRelationExpression(observed, atom.relation(), atom.valueVariable());
        return relation == null ? null : "(!" + atom.lambda() + " | (" + relation + "))";
    }

    private static String failClosed(String what, String observed) {
        log.debug("No condition lemma: unexpected {} probe value '{}'", what, observed);
        return null;
    }

    private static SpecificationDto uniqueSpec(List<SpecificationDto> specs, String specId) {
        if (specs == null) return null;
        List<SpecificationDto> matches = specs.stream()
                .filter(spec -> spec != null && Objects.equals(specId, spec.getId()))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Whether the specification's verdict depends on labels the lemma's argument does not cover. */
    private static boolean readsSecurityLabels(SpecificationDto spec) {
        // Template 7's body is built from trust and attack expressions whatever its conditions say.
        if ("7".equals(spec.getTemplateId())) return true;
        return Stream.of(spec.getAConditions(), spec.getIfConditions(), spec.getThenConditions())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .anyMatch(condition -> condition == null || condition.getTargetType() == null
                        || isSecurityLabel(condition));
    }

    private static boolean isSecurityLabel(SpecConditionDto condition) {
        String targetType = condition.getTargetType().trim().toLowerCase(Locale.ROOT);
        return "trust".equals(targetType) || "privacy".equals(targetType);
    }

    /**
     * The value of each {@code name} in every state of a NuSMV trace. NuSMV prints a state's changed values
     * only, so each state inherits the previous one's. {@code null} when the trace has no state or its first
     * state does not report every name.
     */
    static List<Map<String, String>> valuations(String trace, List<String> names) {
        if (trace == null) return null;
        Set<String> wanted = Set.copyOf(names);
        List<Map<String, String>> states = new ArrayList<>();
        Map<String, String> current = null;
        boolean inInput = false;
        for (String rawLine : trace.split("\n")) {
            String line = rawLine.trim();
            if (STATE_LINE.matcher(line).matches()) {
                current = current == null ? new TreeMap<>() : new TreeMap<>(current);
                states.add(current);
                inInput = false;
                continue;
            }
            if (INPUT_LINE.matcher(line).matches()) {
                inInput = true;
                continue;
            }
            if (current == null || inInput) continue;
            Matcher assignment = ASSIGNMENT.matcher(line);
            if (assignment.matches() && wanted.contains(assignment.group(1))) {
                current.put(assignment.group(1), assignment.group(2));
            }
        }
        if (states.isEmpty() || !states.get(0).keySet().containsAll(wanted)) return null;
        return states;
    }

    private static int ruleIndexOf(String key) {
        Matcher matcher = CONDITION_KEY.matcher(key);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
