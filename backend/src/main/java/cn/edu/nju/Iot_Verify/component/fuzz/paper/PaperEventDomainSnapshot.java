package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/** Canonical immutable snapshot of a {@link PaperEventDomain}. */
public record PaperEventDomainSnapshot(
        int traceLength,
        List<PaperDeviceDomain> deviceDomains,
        List<PaperEnvironmentDomain> environmentDomains,
        List<PaperInitialValueDomain> localVariableDomains) implements PaperEventDomain {

    private static final long LOCAL_INITIALIZATION_SALT = 0x6A09E667F3BCC909L;

    public PaperEventDomainSnapshot(
            int traceLength,
            List<PaperDeviceDomain> deviceDomains,
            List<PaperEnvironmentDomain> environmentDomains) {
        this(traceLength, deviceDomains, environmentDomains, List.of());
    }

    public PaperEventDomainSnapshot {
        if (traceLength < 1 || traceLength > MAX_TRACE_LENGTH) {
            throw new IllegalArgumentException(
                    "traceLength must be between 1 and " + MAX_TRACE_LENGTH);
        }
        deviceDomains = immutableDeviceDomains(deviceDomains);
        environmentDomains = immutableEnvironmentDomains(environmentDomains);
        localVariableDomains = immutableLocalVariableDomains(localVariableDomains);
        if (deviceDomains.isEmpty() && environmentDomains.isEmpty()
                && localVariableDomains.isEmpty()) {
            throw new IllegalArgumentException(
                    "the event domain must expose an initial-state target");
        }
        requireUniqueInitialTargets(deviceDomains, environmentDomains, localVariableDomains);
    }

    public static PaperEventDomainSnapshot copyOf(PaperEventDomain source) {
        Objects.requireNonNull(source, "source must not be null");
        return source instanceof PaperEventDomainSnapshot snapshot
                ? snapshot
                : new PaperEventDomainSnapshot(
                        source.traceLength(),
                        source.deviceDomains(),
                        source.environmentDomains(),
                        source.localVariableDomains());
    }

    @Override
    public List<PaperInitialValue> materializeInitialValues(long initializationNonce) {
        List<PaperInitialValueDomain> domains = initialValueDomains();
        List<PaperInitialValue> initial = new ArrayList<>(domains.size());
        SplittableRandom sharedRandom = new SplittableRandom(initializationNonce);
        // Adding local targets must not advance the device and environment sequence.
        SplittableRandom localRandom = new SplittableRandom(initializationNonce ^ LOCAL_INITIALIZATION_SALT);
        for (PaperInitialValueDomain domain : domains) {
            SplittableRandom source = domain.type() == PaperInitialValue.Type.DEVICE_VARIABLE ? localRandom : sharedRandom;
            initial.add(new PaperInitialValue(
                    domain.type(), domain.targetId(), domain.property(), domain.randomValue(source)));
        }
        return List.copyOf(initial);
    }

    public List<PaperInitialValueDomain> initialValueDomains() {
        List<PaperInitialValueDomain> domains = new ArrayList<>(
                deviceDomains.size() + environmentDomains.size() + localVariableDomains.size());
        for (PaperDeviceDomain device : deviceDomains) {
            domains.add(PaperInitialValueDomain.enumerated(
                    PaperInitialValue.Type.DEVICE_STATE,
                    device.targetId(),
                    device.property(),
                    device.legalValues()));
        }
        for (PaperEnvironmentDomain environment : environmentDomains) {
            domains.add(environment.valueLowerBound() == null
                    ? PaperInitialValueDomain.enumerated(
                    PaperInitialValue.Type.ENVIRONMENT_VALUE,
                    environment.targetId(),
                    environment.property(),
                    environment.initialStateValues())
                    : PaperInitialValueDomain.numeric(
                    PaperInitialValue.Type.ENVIRONMENT_VALUE,
                    environment.targetId(),
                    environment.property(),
                    environment.valueLowerBound(),
                    environment.valueUpperBound()));
        }
        domains.addAll(localVariableDomains);
        return List.copyOf(domains);
    }

    public PaperInitialValueDomain initialValueDomain(PaperInitialValue.Target target) {
        if (target == null) return null;
        return initialValueDomains().stream()
                .filter(domain -> domain.target().equals(target))
                .findFirst()
                .orElse(null);
    }

    public PaperEnvironmentDomain environment(
            String targetId, String property) {
        return environmentDomains.stream()
                .filter(domain -> domain.targetId().equals(targetId)
                        && domain.property().equals(property))
                .findFirst()
                .orElse(null);
    }

    public PaperDeviceDomain device(String targetId) {
        return deviceDomains.stream()
                .filter(domain -> domain.targetId().equals(targetId))
                .findFirst()
                .orElse(null);
    }

    public PaperEnvironmentDomain environment(String name) {
        return environmentDomains.stream()
                .filter(domain -> domain.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static List<PaperDeviceDomain> immutableDeviceDomains(List<PaperDeviceDomain> input) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.size() > MAX_TARGETS_PER_TYPE) {
            throw new IllegalArgumentException(
                    "device target count exceeds " + MAX_TARGETS_PER_TYPE);
        }
        List<PaperDeviceDomain> sorted = input.stream()
                .map(domain -> Objects.requireNonNull(domain, "device domain must not be null"))
                .sorted(java.util.Comparator.comparing(PaperDeviceDomain::targetId))
                .toList();
        if (new HashSet<>(sorted.stream().map(PaperDeviceDomain::targetId).toList()).size()
                != sorted.size()) {
            throw new IllegalArgumentException("device target IDs must be unique");
        }
        return List.copyOf(sorted);
    }

    private static List<PaperEnvironmentDomain> immutableEnvironmentDomains(
            List<PaperEnvironmentDomain> input) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.size() > MAX_TARGETS_PER_TYPE) {
            throw new IllegalArgumentException(
                    "environment target count exceeds " + MAX_TARGETS_PER_TYPE);
        }
        List<PaperEnvironmentDomain> sorted = input.stream()
                .map(domain -> Objects.requireNonNull(domain, "environment domain must not be null"))
                .sorted(java.util.Comparator.comparing(PaperEnvironmentDomain::name))
                .toList();
        if (new HashSet<>(sorted.stream().map(PaperEnvironmentDomain::name).toList()).size()
                != sorted.size()) {
            throw new IllegalArgumentException("environment domain names must be unique");
        }
        return List.copyOf(sorted);
    }

    private static List<PaperInitialValueDomain> immutableLocalVariableDomains(
            List<PaperInitialValueDomain> input) {
        if (input == null || input.isEmpty()) return List.of();
        if (input.size() > MAX_TARGETS_PER_TYPE) {
            throw new IllegalArgumentException(
                    "local variable target count exceeds " + MAX_TARGETS_PER_TYPE);
        }
        List<PaperInitialValueDomain> sorted = input.stream()
                .map(domain -> Objects.requireNonNull(
                        domain, "local variable domain must not be null"))
                .sorted(Comparator.comparing(PaperInitialValueDomain::targetId)
                        .thenComparing(PaperInitialValueDomain::property))
                .toList();
        if (sorted.stream().anyMatch(domain ->
                domain.type() != PaperInitialValue.Type.DEVICE_VARIABLE)) {
            throw new IllegalArgumentException(
                    "local variable domains must use DEVICE_VARIABLE targets");
        }
        if (new HashSet<>(sorted.stream()
                .map(PaperInitialValueDomain::target)
                .toList()).size() != sorted.size()) {
            throw new IllegalArgumentException(
                    "local variable targets must be unique");
        }
        return List.copyOf(sorted);
    }

    private static void requireUniqueInitialTargets(
            List<PaperDeviceDomain> devices,
            List<PaperEnvironmentDomain> environments,
            List<PaperInitialValueDomain> locals) {
        List<PaperInitialValue.Target> targets = new ArrayList<>(
                devices.size() + environments.size() + locals.size());
        devices.forEach(device -> targets.add(new PaperInitialValue.Target(
                PaperInitialValue.Type.DEVICE_STATE,
                device.targetId(),
                device.property())));
        environments.forEach(environment -> targets.add(new PaperInitialValue.Target(
                PaperInitialValue.Type.ENVIRONMENT_VALUE,
                environment.targetId(),
                environment.property())));
        locals.forEach(local -> targets.add(local.target()));
        if (new HashSet<>(targets).size() != targets.size()) {
            throw new IllegalArgumentException("paper initial-state targets must be unique");
        }
    }
}
