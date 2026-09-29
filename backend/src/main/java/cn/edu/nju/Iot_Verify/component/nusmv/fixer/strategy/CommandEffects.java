package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one rule's command brings about: the end state and event of the API it calls and,
 * transitively, those of every rule that one of these effects triggers.
 *
 * <p>A condition established by these effects is a consequence of the rule, not a precondition of it.
 * As an added guard it makes the automation wait for its own outcome. On trace 196, "light the way"
 * turns the bedside lamp on, the lamp turning on makes the hallway camera take a photo, and the
 * candidate guard "camera is taking a photo" on "light the way" can therefore only hold once the lamp
 * is already lit. Such a guard can still make the safety property pass, by keeping the rule from ever
 * firing, which is rule removal presented as a condition.
 *
 * <p>The closure over-approximates on purpose: a rule counts as triggered as soon as one of its
 * conditions is established, whatever its other conditions and its priority. Only states, modes and
 * API events are effects; commands never assign internal variables.
 */
final class CommandEffects {

    private record Effect(String deviceVar, DeviceSmvData device, DeviceManifest.API api) {}

    /** No effects: every condition is treated as a potential precondition. */
    static final CommandEffects NONE = new CommandEffects(Map.of(), Map.of());

    private final Map<String, Effect> effects;
    private final Map<String, DeviceSmvData> deviceSmvMap;

    private CommandEffects(Map<String, Effect> effects, Map<String, DeviceSmvData> deviceSmvMap) {
        this.effects = effects;
        this.deviceSmvMap = deviceSmvMap;
    }

    /** The effects of {@code rule}'s command, closed over every rule in {@code allRules} they trigger. */
    static CommandEffects of(RuleDto rule, List<RuleDto> allRules, Map<String, DeviceSmvData> deviceSmvMap) {
        if (rule == null || deviceSmvMap == null || deviceSmvMap.isEmpty()) return NONE;
        CommandEffects closure = new CommandEffects(new LinkedHashMap<>(), deviceSmvMap);
        boolean grew = closure.add(rule);
        while (grew) {
            grew = false;
            for (RuleDto other : allRules == null ? List.<RuleDto>of() : allRules) {
                if (other == null || other.getConditions() == null) continue;
                // add() only ever grows the map, so a pass that adds nothing is the fixpoint.
                if (other.getConditions().stream().filter(Objects::nonNull).anyMatch(closure::establishes)
                        && closure.add(other)) {
                    grew = true;
                }
            }
        }
        return closure;
    }

    /** Whether some effect definitely makes {@code condition} hold. */
    boolean establishes(RuleDto.Condition condition) {
        if (condition == null || effects.isEmpty()) return false;
        String conditionVar = FixStrategyUtils.resolveVarNameSafe(condition.getDeviceName(), deviceSmvMap);
        if (conditionVar == null) return false;
        return effects.values().stream()
                .filter(effect -> effect.deviceVar().equals(conditionVar))
                .anyMatch(effect -> FixStrategyUtils.apiOutcomeSatisfies(effect.device(), effect.api(), condition));
    }

    /** Record {@code rule}'s own command effect; returns whether it was new. */
    private boolean add(RuleDto rule) {
        if (rule.getCommand() == null) return false;
        String deviceVar = FixStrategyUtils.resolveVarNameSafe(rule.getCommand().getDeviceName(), deviceSmvMap);
        DeviceSmvData device = deviceVar == null ? null : deviceSmvMap.get(deviceVar);
        DeviceManifest.API api = device == null
                ? null
                : DeviceSmvDataFactory.findApi(device.getManifest(), rule.getCommand().getAction());
        if (api == null) return false;
        return effects.putIfAbsent(deviceVar + "\u0000" + api.getName(), new Effect(deviceVar, device, api)) == null;
    }
}
