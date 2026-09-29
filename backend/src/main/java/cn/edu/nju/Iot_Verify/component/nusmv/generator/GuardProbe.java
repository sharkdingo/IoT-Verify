package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.util.SmvConstants;

import java.util.Objects;

/**
 * A {@code DEFINE} the main module appends so a counterexample reports the value of one component of a
 * rule guard in every state. A {@code DEFINE} is a macro over existing state: it adds no variable and
 * no transition, so the model's paths and every specification's verdict are unchanged.
 *
 * <p>The builder renders each probe with the same code that renders the guard itself, which is the
 * point: condition adjustment learns from these values which other candidates the same counterexample
 * refutes, and a second, hand-written evaluator of rule conditions would be free to disagree with the
 * model NuSMV checked.
 *
 * @param id        suffix of the probe's name, unique within one model
 * @param rule      for {@link Kind#RULE_BASE} and {@link Kind#RULE_CONDITIONS}: a rule of the model, matched
 *                  by identity
 * @param condition for {@link Kind#CONDITION} and {@link Kind#OPERAND}: the condition whose atom or
 *                  compared attribute is probed
 */
public record GuardProbe(int id, Kind kind, RuleDto rule, RuleDto.Condition condition) {

    public enum Kind {
        /** The terms a rule guard has besides its conditions: the command's start state and, under attack, delivery. */
        RULE_BASE,
        /** The conjunction of a rule's trigger conditions as the guard reads them. */
        RULE_CONDITIONS,
        /** One condition's atom. */
        CONDITION,
        /** The device attribute a mode or variable condition compares, i.e. the atom's left-hand side. */
        OPERAND
    }

    public GuardProbe {
        Objects.requireNonNull(kind, "kind");
        if (id < 0) {
            throw new IllegalArgumentException("probe id must not be negative");
        }
        boolean ruleProbe = kind == Kind.RULE_BASE || kind == Kind.RULE_CONDITIONS;
        if (ruleProbe ? rule == null : condition == null) {
            throw new IllegalArgumentException(kind + " probe requires a " + (ruleProbe ? "rule" : "condition"));
        }
    }

    public static GuardProbe ofRule(int id, Kind kind, RuleDto rule) {
        return new GuardProbe(id, kind, rule, null);
    }

    public static GuardProbe ofCondition(int id, Kind kind, RuleDto.Condition condition) {
        return new GuardProbe(id, kind, null, condition);
    }

    public String name() {
        return SmvConstants.GUARD_PROBE_PREFIX + id;
    }
}
