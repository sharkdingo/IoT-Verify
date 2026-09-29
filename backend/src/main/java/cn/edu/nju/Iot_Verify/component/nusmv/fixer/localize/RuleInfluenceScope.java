package cn.edu.nju.Iot_Verify.component.nusmv.fixer.localize;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rules that can influence a specification's truth value (a rule-level cone of influence).
 *
 * <p>Repair is only meaningful on rules whose behaviour can reach the property. A rule that fired in the
 * counterexample but writes nothing the property depends on — a CO2-driven window rule under a
 * camera-at-night property — cannot be part of any repair, yet every strategy used to spend full-model
 * verifications on it. This computes the fixpoint instead: the property's devices and environment
 * domains are relevant; a rule is relevant when its command writes a relevant device or a relevant
 * domain (through the target's declared impact); everything a relevant rule reads becomes relevant.
 *
 * <p>Deliberately an over-approximation. A device in the scope brings every environment domain it
 * declares, because its own transitions and dynamics may read them, and a domain brings every device
 * that declares an impact on it. Missing a relevant rule would make a strategy report "no repair" for a
 * repairable model; including an irrelevant one only costs search time.
 */
public final class RuleInfluenceScope {

    private RuleInfluenceScope() {
    }

    /**
     * Relevant rule indices in ascending order, or empty when the property references no device of the
     * model. Callers must treat the empty result as "unknown scope", not as "no relevant rule".
     */
    public static Optional<Set<Integer>> relevantRuleIndices(
            SpecificationDto spec, List<RuleDto> rules, Map<String, DeviceSmvData> deviceSmvMap) {
        if (spec == null || rules == null || deviceSmvMap == null || deviceSmvMap.isEmpty()) {
            return Optional.empty();
        }
        Set<String> devices = new HashSet<>();
        Set<String> domains = new HashSet<>();
        // A property on a shared environment variable is seeded through its device: the closure below
        // brings in every domain a scope device declares.
        for (SpecConditionDto condition : specConditions(spec)) {
            DeviceSmvData smv = resolve(condition == null ? null : condition.getDeviceId(), deviceSmvMap);
            if (smv != null) devices.add(smv.getVarName());
        }
        if (devices.isEmpty()) {
            return Optional.empty();
        }

        Set<Integer> relevant = new TreeSet<>();
        boolean changed = true;
        while (changed) {
            changed = closeOverDevicesAndDomains(devices, domains, deviceSmvMap);
            for (int index = 0; index < rules.size(); index++) {
                RuleDto rule = rules.get(index);
                if (relevant.contains(index) || !writesScopeDevice(rule, devices, deviceSmvMap)) continue;
                relevant.add(index);
                addReads(rule, devices, deviceSmvMap);
                changed = true;
            }
        }
        return Optional.of(relevant);
    }

    private static boolean closeOverDevicesAndDomains(
            Set<String> devices, Set<String> domains, Map<String, DeviceSmvData> deviceSmvMap) {
        boolean changed = false;
        for (DeviceSmvData smv : deviceSmvMap.values()) {
            if (smv == null || smv.getVarName() == null) continue;
            if (devices.contains(smv.getVarName()) && smv.getEnvVariables() != null
                    && domains.addAll(smv.getEnvVariables().keySet())) {
                changed = true;
            }
            if (!devices.contains(smv.getVarName())
                    && impactedDomains(smv).stream().anyMatch(domains::contains)) {
                devices.add(smv.getVarName());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * A target that impacts a relevant domain is already a scope device: the closure adds it before the
     * rules are scanned, and the loop repeats until neither set grows.
     */
    private static boolean writesScopeDevice(RuleDto rule, Set<String> devices,
                                             Map<String, DeviceSmvData> deviceSmvMap) {
        if (rule == null || rule.getCommand() == null) return false;
        DeviceSmvData target = resolve(rule.getCommand().getDeviceName(), deviceSmvMap);
        return target != null && devices.contains(target.getVarName());
    }

    private static void addReads(RuleDto rule, Set<String> devices, Map<String, DeviceSmvData> deviceSmvMap) {
        if (rule.getConditions() != null) {
            for (RuleDto.Condition condition : rule.getConditions()) {
                if (condition == null) continue;
                // The device's environment domains follow in the next closure pass.
                DeviceSmvData smv = resolve(condition.getDeviceName(), deviceSmvMap);
                if (smv != null) devices.add(smv.getVarName());
            }
        }
        // Content provenance feeds privacy properties on the target.
        DeviceSmvData content = resolve(rule.getCommand().getContentDevice(), deviceSmvMap);
        if (content != null) devices.add(content.getVarName());
    }

    private static Set<String> impactedDomains(DeviceSmvData smv) {
        Set<String> impacted = new HashSet<>();
        if (smv.getImpactedVariables() != null) impacted.addAll(smv.getImpactedVariables());
        if (smv.getImpactedEnvironmentVariables() != null) {
            impacted.addAll(smv.getImpactedEnvironmentVariables().keySet());
        }
        return impacted;
    }

    private static List<SpecConditionDto> specConditions(SpecificationDto spec) {
        List<SpecConditionDto> conditions = new ArrayList<>();
        if (spec.getAConditions() != null) conditions.addAll(spec.getAConditions());
        if (spec.getIfConditions() != null) conditions.addAll(spec.getIfConditions());
        if (spec.getThenConditions() != null) conditions.addAll(spec.getThenConditions());
        return conditions;
    }

    /** An unknown reference contributes nothing; model generation reports it separately. */
    private static DeviceSmvData resolve(String reference, Map<String, DeviceSmvData> deviceSmvMap) {
        DeviceSmvData smv = DeviceReferenceResolver.resolve(reference, deviceSmvMap);
        return smv == null || smv.getVarName() == null ? null : smv;
    }
}
