package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one rule's command brings about: the end state and event of the API it calls and,
 * transitively, those of every rule that one of these effects triggers.
 *
 * <p>A condition established by every recorded effect on its device is an unambiguous consequence,
 * not a precondition of the rule. Conflicting outcomes in a feedback loop do not justify this pruning.
 * As an added guard it makes the automation wait for its own outcome. On trace 196, "light the way"
 * turns the bedside lamp on, the lamp turning on makes the hallway camera take a photo, and the
 * candidate guard "camera is taking a photo" on "light the way" can therefore only hold once the lamp
 * is already lit. Such a guard can still make the safety property pass, by keeping the rule from ever
 * firing, which is rule removal presented as a condition.
 *
 * <p>The closure considers a rule when one of its conditions can hold after an earlier effect.
 * Only paths supported by guaranteed outcomes can justify pruning, while a possible competing
 * effect can keep a guard available. A downstream rule with a possible priority blocker does not
 * establish its nominal effect, even when a preceding effect establishes its complete trigger.
 */
final class CommandEffects {

    private record Effect(int ruleIndex, RuleDto rule, String deviceVar, DeviceSmvData device,
                          DeviceManifest.API api, Map<String, String> start, Set<String> writes) {}

    private record Priority(List<Effect> potentialBlockers, Set<String> concurrentWrites) {}

    /** No effects: every condition is treated as a potential precondition. */
    static final CommandEffects NONE = new CommandEffects(new Scene(List.of(), Map.of(), Set.of()));

    private final Map<Integer, Effect> effects = new LinkedHashMap<>();
    private final Scene scene;
    private final Set<Integer> establishedPaths = new LinkedHashSet<>();
    private final Set<Integer> tracedBlockers = new LinkedHashSet<>();

    private CommandEffects(Scene scene) {
        this.scene = scene;
    }

    private void trace(Effect root) {
        // The edited command's direct effect is conditional on its selection, as in NuSMV's execution probe.
        add(root, true);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Effect effect : scene.writers.values()) {
                if (effect == root || effect.rule().getConditions() == null) continue;
                List<RuleDto.Condition> conditions = effect.rule().getConditions();
                if (conditions.stream().noneMatch(this::canEstablish)) continue;
                List<Effect> blockers = scene.priority(effect).potentialBlockers();
                grew |= add(effect, blockers.isEmpty() && hasEstablishedTrigger(conditions));
                grew |= traceBlockers(blockers);
            }
        }
    }

    /** Whether all recorded effects on the condition's device make it hold. */
    boolean establishes(RuleDto.Condition condition) {
        List<Effect> matching = effectsOn(condition);
        // Conflicting effects occur at different steps of a feedback loop. Either state can be
        // a useful precondition; pruning it as a one-way outcome would remove real repairs.
        return matching.stream().anyMatch(this::hasEstablishedPath)
                && matching.stream().allMatch(effect -> matches(effect, condition, FixStrategyUtils.EffectMatch.GUARANTEED));
    }

    private boolean hasEstablishedTrigger(List<RuleDto.Condition> conditions) {
        // A conjunction reads one source state. Evidence from separate effects may hold on different ticks.
        return effectsOn(conditions.get(0)).stream().filter(this::hasEstablishedPath)
                .anyMatch(source -> conditions.stream().allMatch(condition -> condition != null
                        && source.deviceVar().equals(FixStrategyUtils.resolveVarNameSafe(
                                condition.getDeviceName(), scene.deviceSmvMap))
                        && matches(source, condition, FixStrategyUtils.EffectMatch.GUARANTEED)));
    }

    private boolean hasEstablishedPath(Effect effect) {
        return establishedPaths.contains(effect.ruleIndex());
    }

    private boolean canEstablish(RuleDto.Condition condition) {
        return effectsOn(condition).stream()
                .anyMatch(effect -> matches(effect, condition, FixStrategyUtils.EffectMatch.POSSIBLE));
    }

    private boolean matches(Effect effect, RuleDto.Condition condition, FixStrategyUtils.EffectMatch match) {
        return FixStrategyUtils.matchesApiOutcome(effect.device(), effect.api(),
                scene.priority(effect).concurrentWrites(), condition, match);
    }

    private boolean traceBlockers(List<Effect> blockers) {
        boolean grew = false;
        for (Effect blocker : blockers) {
            if (!tracedBlockers.add(blocker.ruleIndex())) continue;
            grew |= add(blocker, false);
            // The earlier raw branch can suppress a rule without winning the affected modes itself.
            grew |= traceBlockers(scene.priority(blocker).potentialBlockers());
        }
        return grew;
    }

    private List<Effect> effectsOn(RuleDto.Condition condition) {
        if (condition == null || effects.isEmpty()) return List.of();
        String conditionVar = FixStrategyUtils.resolveVarNameSafe(condition.getDeviceName(), scene.deviceSmvMap);
        if (conditionVar == null) return List.of();
        return effects.values().stream()
                .filter(effect -> effect.deviceVar().equals(conditionVar))
                .toList();
    }

    /** A path can be discovered as possible first and later supported by a guaranteed outcome. */
    private boolean add(Effect effect, boolean established) {
        boolean promoted = established && establishedPaths.add(effect.ruleIndex());
        return effects.putIfAbsent(effect.ruleIndex(), effect) == null || promoted;
    }

    /** The rule order and blocker proofs stay the same across every root in a condition search. */
    static final class Scene {
        private final Map<Integer, Effect> writers = new LinkedHashMap<>();
        private final Map<String, DeviceSmvData> deviceSmvMap;
        private final Set<Integer> adjustableRules;
        private final Map<Integer, Priority> priorities = new LinkedHashMap<>();

        Scene(List<RuleDto> rules, Map<String, DeviceSmvData> deviceSmvMap, Set<Integer> adjustableRules) {
            this.deviceSmvMap = deviceSmvMap;
            this.adjustableRules = adjustableRules;
            for (int index = 0; index < rules.size(); index++) {
                RuleDto rule = rules.get(index);
                if (rule == null || rule.getCommand() == null) continue;
                String deviceVar = FixStrategyUtils.resolveVarNameSafe(rule.getCommand().getDeviceName(), deviceSmvMap);
                DeviceSmvData device = deviceVar == null ? null : deviceSmvMap.get(deviceVar);
                if (device == null) continue;
                DeviceManifest.API api = DeviceSmvDataFactory.findApi(device.getManifest(), rule.getCommand().getAction());
                if (api == null) continue;
                writers.put(index, new Effect(index, rule, deviceVar, device, api,
                        FixStrategyUtils.concreteStartState(device, api.getStartState()),
                        FixStrategyUtils.concreteEndState(device, api.getEndState()).keySet()));
            }
        }

        CommandEffects forRule(int ruleIndex) {
            Effect root = writers.get(ruleIndex);
            if (root == null) return NONE;
            CommandEffects closure = new CommandEffects(this);
            closure.trace(root);
            return closure;
        }

        private Priority priority(Effect effect) {
            return priorities.computeIfAbsent(effect.ruleIndex(), ignored -> computePriority(effect));
        }

        private Priority computePriority(Effect effect) {
            List<Effect> blockers = new ArrayList<>();
            Set<String> concurrentWrites = new LinkedHashSet<>();
            DeviceSmvData device = effect.device();
            for (Effect writer : writers.values()) {
                if (writer == effect || !writer.deviceVar().equals(effect.deviceVar())
                        || !FixStrategyUtils.overlapsStatePattern(effect.start(), writer.start())) continue;
                if (Collections.disjoint(writer.writes(), effect.writes())) {
                    concurrentWrites.addAll(writer.writes());
                } else if (writer.ruleIndex() < effect.ruleIndex() && writer.api() != effect.api()
                        && !fixedGuardsDisjoint(writer, effect)) {
                    // An earlier raw match blocks the whole command, even when that writer is itself blocked.
                    blockers.add(writer);
                }
            }
            List<DeviceManifest.Transition> transitions = device.getManifest().getTransitions();
            if (transitions != null) {
                for (DeviceManifest.Transition transition : transitions) {
                    if (transition != null && transition.getTrigger() != null
                            && FixStrategyUtils.overlapsStatePattern(effect.start(),
                                    FixStrategyUtils.concreteStartState(device, transition.getStartState()))) {
                        concurrentWrites.addAll(FixStrategyUtils.concreteEndState(device, transition.getEndState()).keySet());
                    }
                }
            }
            // The autonomous branches can supply unwritten modes only; selected rules precede them in each case.
            concurrentWrites.removeAll(effect.writes());
            return new Priority(blockers, concurrentWrites);
        }

        private boolean fixedGuardsDisjoint(Effect left, Effect right) {
            // Candidate discovery can remove an authored guard and make a competing branch live.
            if (adjustableRules.contains(left.ruleIndex()) || adjustableRules.contains(right.ruleIndex())
                    || left.rule().getConditions() == null || right.rule().getConditions() == null) return false;
            return left.rule().getConditions().stream().anyMatch(first -> right.rule().getConditions().stream()
                    .anyMatch(second -> FixStrategyUtils.stateConditionsDisjoint(first, second, deviceSmvMap)));
        }
    }
}
