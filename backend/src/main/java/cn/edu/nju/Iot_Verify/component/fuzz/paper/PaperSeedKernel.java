package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/** Deterministic paper-event seed generation and mutation helper. */
public final class PaperSeedKernel {

    public static final int MAX_POPULATION_SIZE = 1_000;
    public static final int MAX_INITIAL_SEED_EVENTS = 64;
    public static final int MAX_MUTATION_OPERATIONS = 128;

    private final PaperEventDomainSnapshot domain;
    private final Map<String, PaperDeviceDomain> devicesByName;
    private final Map<String, PaperEnvironmentDomain> environmentsByName;
    private final Map<PaperInitialValue.Target, PaperInitialValueDomain> initialDomainsByTarget;
    private final List<PaperInitialValueDomain> initialDomains;
    private final List<String> deviceNames;
    private final List<String> environmentNames;
    private final SplittableRandom random;

    public PaperSeedKernel(PaperEventDomain domain, long seed) {
        this.domain = PaperEventDomainSnapshot.copyOf(domain);
        this.devicesByName = indexDevices(this.domain.deviceDomains());
        this.environmentsByName = indexEnvironments(this.domain.environmentDomains());
        this.initialDomains = this.domain.initialValueDomains();
        this.initialDomainsByTarget = indexInitialDomains(this.initialDomains);
        this.deviceNames = this.domain.deviceDomains().stream()
                .map(PaperDeviceDomain::targetId)
                .toList();
        this.environmentNames = this.domain.environmentDomains().stream()
                .map(PaperEnvironmentDomain::name)
                .toList();
        this.random = new SplittableRandom(seed);
    }

    public List<PaperSeed> initialPopulation(int populationSize) {
        requirePopulationSize(populationSize);
        List<PaperSeed> seeds = new ArrayList<>(populationSize);
        for (int index = 0; index < populationSize; index++) {
            seeds.add(randomSeed());
        }
        return List.copyOf(seeds);
    }

    public List<PaperSeed> nextPopulation(PaperSeed parent, int populationSize) {
        Objects.requireNonNull(parent, "parent must not be null");
        requirePopulationSize(populationSize);
        List<PaperSeed> seeds = new ArrayList<>(populationSize);
        for (int index = 0; index < populationSize; index++) {
            seeds.add(nextSeed(parent).seed());
        }
        return List.copyOf(seeds);
    }

    public List<PaperInitialValue> materializeInitialValues(PaperSeed seed) {
        Objects.requireNonNull(seed, "seed must not be null");
        validateSeed(seed);
        Map<PaperInitialValue.Target, PaperInitialValue> overrides = new LinkedHashMap<>();
        seed.initialOverrides().forEach(value -> overrides.put(value.target(), value));
        return domain.materializeInitialValues(seed.initializationNonce()).stream()
                .map(value -> overrides.getOrDefault(value.target(), value))
                .toList();
    }

    public GeneratedSeed nextSeed(PaperSeed parent) {
        Objects.requireNonNull(parent, "parent must not be null");
        NextSeedStrategy strategy = strategyForRoll(random.nextInt(100));
        PaperSeed seed = strategy == NextSeedStrategy.MUTATION ? mutate(parent) : randomSeed();
        return new GeneratedSeed(seed, strategy);
    }

    public static NextSeedStrategy strategyForRoll(int percentileRoll) {
        if (percentileRoll < 0 || percentileRoll > 99) {
            throw new IllegalArgumentException("percentileRoll must be between 0 and 99");
        }
        return percentileRoll < 95 ? NextSeedStrategy.MUTATION : NextSeedStrategy.RANDOM;
    }

    public PaperSeed mutate(PaperSeed source) {
        Objects.requireNonNull(source, "source must not be null");
        return mutateBatch(source).seed();
    }

    MutationBatch mutateBatch(PaperSeed source) {
        Objects.requireNonNull(source, "source must not be null");
        validateSeed(source);
        int operationCount = mutationOperationCount(mutationNodeCount(source));
        PaperSeed current = source;
        List<MutationStep> steps = new ArrayList<>(operationCount);
        for (int operation = 0; operation < operationCount; operation++) {
            MutationStep step = mutateOnce(current);
            current = step.seed();
            steps.add(step);
        }
        // Mutation retains the selected seed's baseline identity and changes only
        // selected initial targets or ordinary events. The 5% branch resamples both.
        PaperSeed offspring = new PaperSeed(
                source.initializationNonce(), current.initialOverrides(), current.events());
        return new MutationBatch(offspring, steps);
    }

    private MutationStep mutateOnce(PaperSeed source) {
        SelectedNode selected = selectMutationNodePositionFirst(source);
        if (selected == null) {
            return new MutationStep(source, null, null);
        }
        if (selected.initialDomain() != null) {
            return new MutationStep(
                    modifyInitialValue(source, selected.initialDomain()),
                    selected,
                    MutationOperation.MODIFICATION);
        }
        PaperEvent selectedEvent = selected.event();
        MutationOperation operation;
        if (selectedEvent.type() == PaperEvent.Type.DEVICE) {
            operation = MutationOperation.MODIFICATION;
        } else {
            List<MutationOperation> viable = new ArrayList<>();
            if (hasFreeEnvironmentSlot(source)) {
                viable.add(MutationOperation.ADDITION);
            }
            viable.add(MutationOperation.DELETION);
            if (canModifyEnvironmentEvent(source, selectedEvent)) {
                viable.add(MutationOperation.MODIFICATION);
            }
            operation = randomItem(viable);
        }
        return new MutationStep(
                mutateSelectedEvent(
                        source,
                        new SelectedEvent(selected.position(), selectedEvent),
                        operation),
                selected,
                operation);
    }

    static int mutationOperationCountForRoll(int nodeCount, double unitRoll) {
        if (nodeCount < 0) {
            throw new IllegalArgumentException("nodeCount must not be negative");
        }
        if (!(unitRoll >= 0.0 && unitRoll < 1.0)) {
            throw new IllegalArgumentException("unitRoll must be in [0, 1)");
        }
        double mutationRate = unitRoll * 0.09 + 0.01;
        return (int) Math.min(
                MAX_MUTATION_OPERATIONS,
                Math.round(mutationRate * nodeCount));
    }

    private int mutationOperationCount(int nodeCount) {
        return mutationOperationCountForRoll(nodeCount, random.nextDouble());
    }

    int mutationNodeCount(PaperSeed seed) {
        Objects.requireNonNull(seed, "seed must not be null");
        return Math.addExact(initialDomains.size(), seed.events().size());
    }

    public PaperSeed addEnvironmentEvent(PaperSeed source) {
        Objects.requireNonNull(source, "source must not be null");
        SelectedEvent selected = selectEventPositionFirst(source, PaperEvent.Type.ENVIRONMENT);
        return selected == null
                ? source
                : mutateSelectedEvent(source, selected, MutationOperation.ADDITION);
    }

    private PaperSeed addEnvironmentEvent(PaperSeed source, PaperEvent selected) {
        if (environmentNames.isEmpty() || source.events().size() >= PaperSeed.MAX_EVENTS) return source;
        Set<EventSlot> occupied = occupiedSlots(source);
        EventSlot selectedSlot = null;
        long availableCount = 0L;
        for (int position = 1; position <= domain.traceLength(); position++) {
            for (String name : environmentNames) {
                EventSlot slot = new EventSlot(PaperEvent.Type.ENVIRONMENT, name, position);
                if (!occupied.contains(slot)) {
                    availableCount++;
                    if (random.nextLong(availableCount) == 0L) {
                        selectedSlot = slot;
                    }
                }
            }
        }
        return selectedSlot == null
                ? source
                : append(source, environmentEvent(selectedSlot.name(), selectedSlot.position()));
    }

    public PaperSeed deleteEnvironmentEvent(PaperSeed source) {
        Objects.requireNonNull(source, "source must not be null");
        SelectedEvent selected = selectEventPositionFirst(source, PaperEvent.Type.ENVIRONMENT);
        return selected == null
                ? source
                : mutateSelectedEvent(source, selected, MutationOperation.DELETION);
    }

    private PaperSeed deleteEnvironmentEvent(PaperSeed source, PaperEvent selected) {
        List<PaperEvent> events = new ArrayList<>(source.events());
        events.remove(selected);
        return new PaperSeed(source.initializationNonce(), source.initialOverrides(), events);
    }

    public PaperSeed modifyEnvironmentEvent(PaperSeed source) {
        Objects.requireNonNull(source, "source must not be null");
        SelectedEvent selected = selectEventPositionFirst(source, PaperEvent.Type.ENVIRONMENT);
        return selected == null
                ? source
                : mutateSelectedEvent(source, selected, MutationOperation.MODIFICATION);
    }

    private PaperSeed modifyEnvironmentEvent(PaperSeed source, PaperEvent selected) {
        if (!canModifyEnvironmentEvent(source, selected)) return source;
        Set<EventSlot> occupied = occupiedSlots(source);
        occupied.remove(EventSlot.of(selected));
        List<Integer> availablePositions = new ArrayList<>();
        for (int position = 1; position <= domain.traceLength(); position++) {
            EventSlot slot = new EventSlot(PaperEvent.Type.ENVIRONMENT, selected.name(), position);
            if (position != selected.position() && !occupied.contains(slot)) {
                availablePositions.add(position);
            }
        }
        int position = randomItem(availablePositions);
        String value = requireEnvironment(selected.name())
                .differentEventValue(random, selected.value());
        return replace(source, selected, new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, selected.name(), value, position));
    }

    public PaperSeed modifyDeviceEvent(PaperSeed source) {
        Objects.requireNonNull(source, "source must not be null");
        SelectedEvent selected = selectEventPositionFirst(source, PaperEvent.Type.DEVICE);
        return selected == null
                ? source
                : mutateSelectedEvent(source, selected, MutationOperation.MODIFICATION);
    }

    private PaperSeed modifyDeviceEvent(PaperSeed source, PaperEvent selected) {
        PaperDeviceDomain descriptor = devicesByName.get(selected.name());
        if (descriptor == null) return source;
        List<String> modes = descriptor.legalValues();
        String mode = differentValue(modes, selected.value());
        PaperEvent replacement = new PaperEvent(
                PaperEvent.Type.DEVICE, selected.name(), mode, 1);
        return replacement.equals(selected) ? source : replace(source, selected, replacement);
    }

    public PaperSeed modifyInitialValue(
            PaperSeed source, PaperInitialValue.Target target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        PaperInitialValueDomain selected = initialDomainsByTarget.get(target);
        return selected == null ? source : modifyInitialValue(source, selected);
    }

    private PaperSeed modifyInitialValue(
            PaperSeed source, PaperInitialValueDomain selected) {
        String current = effectiveInitialValue(source, selected.target());
        if (!selected.hasAlternativeValue(current)) return source;
        String replacement = selected.differentValue(random, current);
        if (replacement.equals(current)) return source;

        String baseline = baselineInitialValue(source.initializationNonce(), selected.target());
        List<PaperInitialValue> overrides = new ArrayList<>(source.initialOverrides());
        overrides.removeIf(value -> value.target().equals(selected.target()));
        if (!replacement.equals(baseline)) {
            overrides.add(new PaperInitialValue(
                    selected.type(),
                    selected.targetId(),
                    selected.property(),
                    replacement));
        }
        return new PaperSeed(source.initializationNonce(), overrides, source.events());
    }

    PaperSeed mutateSelectedEvent(
            PaperSeed source, SelectedEvent selected, MutationOperation operation) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(selected, "selected must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        PaperEvent event = selected.event();
        if (selected.position() != event.position() || !source.events().contains(event)) {
            throw new IllegalArgumentException("selected event must belong to its selected position");
        }
        if (event.type() == PaperEvent.Type.DEVICE) {
            return operation == MutationOperation.MODIFICATION
                    ? modifyDeviceEvent(source, event)
                    : source;
        }
        return switch (operation) {
            case ADDITION -> addEnvironmentEvent(source, event);
            case DELETION -> deleteEnvironmentEvent(source, event);
            case MODIFICATION -> modifyEnvironmentEvent(source, event);
        };
    }

    private PaperSeed randomSeed() {
        long initializationNonce = random.nextLong();
        long availableSlots = availableSlotCount();
        if (availableSlots == 0L) {
            return new PaperSeed(initializationNonce, List.of(), List.of());
        }
        int maxEvents = (int) Math.min(
                Math.min((long) MAX_INITIAL_SEED_EVENTS, availableSlots),
                Math.max(1L, domain.traceLength()));
        int targetCount = 1 + random.nextInt(maxEvents);
        Map<EventSlot, PaperEvent> events = new LinkedHashMap<>();
        int attempts = targetCount * 32 + 64;
        while (events.size() < targetCount && attempts-- > 0) {
            PaperEvent event = randomEventPositionFirst();
            events.putIfAbsent(EventSlot.of(event), event);
        }
        if (events.size() < targetCount) {
            fillDeterministically(events, targetCount);
        }
        return new PaperSeed(
                initializationNonce, List.of(), new ArrayList<>(events.values()));
    }

    private PaperEvent randomEventPositionFirst() {
        int position = randomPosition();
        if (position > 1) {
            if (!environmentNames.isEmpty()) {
                String name = randomItem(environmentNames);
                return environmentEvent(name, position);
            }
            position = 1;
        }
        if (deviceNames.isEmpty()) {
            String name = randomItem(environmentNames);
            return environmentEvent(name, position);
        }
        if (environmentNames.isEmpty() || random.nextBoolean()) {
            String name = randomItem(deviceNames);
            return new PaperEvent(
                    PaperEvent.Type.DEVICE,
                    name,
                    randomItem(devicesByName.get(name).legalValues()),
                    1);
        }
        String name = randomItem(environmentNames);
        return environmentEvent(name, position);
    }

    private void fillDeterministically(Map<EventSlot, PaperEvent> events, int targetCount) {
        for (String name : deviceNames) {
            if (events.size() >= targetCount) return;
            PaperEvent event = new PaperEvent(
                    PaperEvent.Type.DEVICE,
                    name,
                    randomItem(devicesByName.get(name).legalValues()),
                    1);
            events.putIfAbsent(EventSlot.of(event), event);
        }
        for (int position = 1; position <= domain.traceLength(); position++) {
            for (String name : environmentNames) {
                if (events.size() >= targetCount) return;
                PaperEvent event = environmentEvent(name, position);
                events.putIfAbsent(EventSlot.of(event), event);
            }
        }
    }

    private PaperEvent environmentEvent(String name, int position) {
        return new PaperEvent(
                PaperEvent.Type.ENVIRONMENT,
                name,
                requireEnvironment(name).randomEventValue(random),
                position);
    }

    private PaperEnvironmentDomain requireEnvironment(String name) {
        PaperEnvironmentDomain environment = environmentsByName.get(name);
        if (environment == null) {
            throw new IllegalArgumentException("unknown environment target " + name);
        }
        return environment;
    }

    private SelectedEvent selectEventPositionFirst(PaperSeed source) {
        return selectEventPositionFirst(source, null, randomPosition());
    }

    private SelectedEvent selectEventPositionFirst(PaperSeed source, PaperEvent.Type type) {
        return selectEventPositionFirst(source, type, randomPosition());
    }

    SelectedEvent selectEventPositionFirst(PaperSeed source, int firstPosition) {
        return selectEventPositionFirst(source, null, firstPosition);
    }

    private SelectedNode selectMutationNodePositionFirst(PaperSeed source) {
        return selectMutationNodePositionFirst(source, randomPosition());
    }

    SelectedNode selectMutationNodePositionFirst(PaperSeed source, int firstPosition) {
        Objects.requireNonNull(source, "source must not be null");
        if (firstPosition < 1 || firstPosition > domain.traceLength()) {
            throw new IllegalArgumentException("firstPosition is outside the trace");
        }
        Map<Integer, List<SelectedNode>> byPosition = new LinkedHashMap<>();
        if (!initialDomains.isEmpty()) {
            List<SelectedNode> initialNodes = byPosition.computeIfAbsent(
                    1, ignored -> new ArrayList<>());
            initialDomains.forEach(initial ->
                    initialNodes.add(SelectedNode.initial(initial)));
        }
        for (PaperEvent event : source.events()) {
            byPosition.computeIfAbsent(event.position(), ignored -> new ArrayList<>())
                    .add(SelectedNode.event(event));
        }
        if (byPosition.isEmpty()) return null;
        List<SelectedNode> firstNodes = byPosition.get(firstPosition);
        if (firstNodes != null && !firstNodes.isEmpty()) {
            return randomItem(firstNodes);
        }
        List<Integer> retryPositions = byPosition.keySet().stream()
                .filter(position -> position != firstPosition)
                .toList();
        if (retryPositions.isEmpty()) return null;
        int position = randomItem(retryPositions);
        return randomItem(byPosition.get(position));
    }

    private SelectedEvent selectEventPositionFirst(
            PaperSeed source, PaperEvent.Type type, int firstPosition) {
        Objects.requireNonNull(source, "source must not be null");
        if (firstPosition < 1 || firstPosition > domain.traceLength()) {
            throw new IllegalArgumentException("firstPosition is outside the trace");
        }
        Map<Integer, List<PaperEvent>> byPosition = new LinkedHashMap<>();
        for (PaperEvent event : source.events()) {
            if (type == null || event.type() == type) {
                byPosition.computeIfAbsent(event.position(), ignored -> new ArrayList<>()).add(event);
            }
        }
        if (byPosition.isEmpty()) return null;
        List<PaperEvent> firstEvents = byPosition.get(firstPosition);
        if (firstEvents != null && !firstEvents.isEmpty()) {
            PaperEvent event = randomItem(firstEvents);
            return new SelectedEvent(event.position(), event);
        }
        List<Integer> retryPositions = byPosition.keySet().stream()
                .filter(position -> position != firstPosition)
                .toList();
        if (retryPositions.isEmpty()) return null;
        int position = randomItem(retryPositions);
        PaperEvent event = randomItem(byPosition.get(position));
        return new SelectedEvent(event.position(), event);
    }

    private boolean canModifyEnvironmentEvent(PaperSeed source, PaperEvent selected) {
        if (selected.type() != PaperEvent.Type.ENVIRONMENT
                || !requireEnvironment(selected.name()).hasAlternativeEventValue(selected.value())) {
            return false;
        }
        Set<EventSlot> occupied = occupiedSlots(source);
        for (int position = 1; position <= domain.traceLength(); position++) {
            if (position != selected.position()
                    && !occupied.contains(new EventSlot(
                    PaperEvent.Type.ENVIRONMENT, selected.name(), position))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFreeEnvironmentSlot(PaperSeed source) {
        if (environmentNames.isEmpty() || source.events().size() >= PaperSeed.MAX_EVENTS) return false;
        long totalSlots = Math.multiplyExact((long) environmentNames.size(), domain.traceLength());
        long occupied = source.events().stream()
                .filter(event -> event.type() == PaperEvent.Type.ENVIRONMENT)
                .count();
        return occupied < totalSlots;
    }

    private long availableSlotCount() {
        try {
            return Math.addExact(
                    deviceNames.size(),
                    Math.multiplyExact((long) environmentNames.size(), domain.traceLength()));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("paper event domain is too large", exception);
        }
    }

    private int randomPosition() {
        return 1 + random.nextInt(domain.traceLength());
    }

    private String differentValue(List<String> values, String current) {
        return PaperRandomChoice.different(random, values, current);
    }

    private <T> T randomItem(List<T> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("cannot choose from an empty domain");
        }
        return values.get(random.nextInt(values.size()));
    }

    private PaperSeed append(PaperSeed source, PaperEvent event) {
        List<PaperEvent> events = new ArrayList<>(source.events());
        events.add(event);
        return new PaperSeed(source.initializationNonce(), source.initialOverrides(), events);
    }

    private PaperSeed replace(PaperSeed source, PaperEvent selected, PaperEvent replacement) {
        List<PaperEvent> events = new ArrayList<>(source.events());
        int index = events.indexOf(selected);
        if (index < 0) return source;
        events.set(index, replacement);
        return new PaperSeed(source.initializationNonce(), source.initialOverrides(), events);
    }

    private String effectiveInitialValue(
            PaperSeed seed, PaperInitialValue.Target target) {
        return seed.initialOverrides().stream()
                .filter(value -> value.target().equals(target))
                .map(PaperInitialValue::value)
                .findFirst()
                .orElseGet(() -> baselineInitialValue(seed.initializationNonce(), target));
    }

    private String baselineInitialValue(
            long initializationNonce, PaperInitialValue.Target target) {
        return domain.materializeInitialValues(initializationNonce).stream()
                .filter(value -> value.target().equals(target))
                .map(PaperInitialValue::value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown paper initial-state target"));
    }

    private void validateSeed(PaperSeed seed) {
        for (PaperInitialValue override : seed.initialOverrides()) {
            PaperInitialValueDomain initialDomain = initialDomainsByTarget.get(override.target());
            if (initialDomain == null || !initialDomain.isValidValue(override.value())) {
                throw new IllegalArgumentException(
                        "initial override is outside its legal domain");
            }
        }
        for (PaperEvent event : seed.events()) {
            if (event.position() > domain.traceLength()) {
                throw new IllegalArgumentException("event position exceeds the trace length");
            }
            if (event.type() == PaperEvent.Type.DEVICE) {
                PaperDeviceDomain device = devicesByName.get(event.name());
                if (device == null || !device.legalValues().contains(event.value())) {
                    throw new IllegalArgumentException("device event is outside its legal domain");
                }
            } else {
                PaperEnvironmentDomain environment = environmentsByName.get(event.name());
                if (environment == null || !environment.isValidEventValue(event.value())) {
                    throw new IllegalArgumentException(
                            "environment event is outside its legal domain");
                }
            }
        }
    }

    private static Map<String, PaperDeviceDomain> indexDevices(List<PaperDeviceDomain> domains) {
        Map<String, PaperDeviceDomain> indexed = new LinkedHashMap<>();
        for (PaperDeviceDomain domain : domains) {
            indexed.put(domain.targetId(), domain);
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, PaperEnvironmentDomain> indexEnvironments(
            List<PaperEnvironmentDomain> domains) {
        Map<String, PaperEnvironmentDomain> indexed = new LinkedHashMap<>();
        for (PaperEnvironmentDomain domain : domains) {
            indexed.put(domain.name(), domain);
        }
        return Map.copyOf(indexed);
    }

    private static Map<PaperInitialValue.Target, PaperInitialValueDomain> indexInitialDomains(
            List<PaperInitialValueDomain> domains) {
        Map<PaperInitialValue.Target, PaperInitialValueDomain> indexed = new LinkedHashMap<>();
        for (PaperInitialValueDomain domain : domains) {
            indexed.put(domain.target(), domain);
        }
        return Map.copyOf(indexed);
    }

    private Set<EventSlot> occupiedSlots(PaperSeed source) {
        Set<EventSlot> slots = new HashSet<>();
        source.events().forEach(event -> slots.add(EventSlot.of(event)));
        return slots;
    }

    private void requirePopulationSize(int populationSize) {
        if (populationSize < 1 || populationSize > MAX_POPULATION_SIZE) {
            throw new IllegalArgumentException(
                    "populationSize must be between 1 and " + MAX_POPULATION_SIZE);
        }
    }

    public enum NextSeedStrategy {
        MUTATION,
        RANDOM
    }

    public enum MutationOperation {
        ADDITION,
        DELETION,
        MODIFICATION
    }

    record SelectedEvent(int position, PaperEvent event) {
        SelectedEvent {
            event = Objects.requireNonNull(event, "event must not be null");
            if (position != event.position()) {
                throw new IllegalArgumentException("selected position must match the event position");
            }
        }
    }

    record SelectedNode(
            int position,
            PaperInitialValueDomain initialDomain,
            PaperEvent event) {

        private static SelectedNode initial(PaperInitialValueDomain domain) {
            return new SelectedNode(1, domain, null);
        }

        private static SelectedNode event(PaperEvent event) {
            return new SelectedNode(event.position(), null, event);
        }

        SelectedNode {
            if ((initialDomain == null) == (event == null)) {
                throw new IllegalArgumentException(
                        "a selected node must be either an initial target or an event");
            }
            if (position < 1 || (initialDomain != null && position != 1)
                    || (event != null && position != event.position())) {
                throw new IllegalArgumentException("selected node position is invalid");
            }
        }

        boolean initial() {
            return initialDomain != null;
        }
    }

    record MutationStep(
            PaperSeed seed,
            SelectedNode selected,
            MutationOperation operation) {
        MutationStep {
            seed = Objects.requireNonNull(seed, "seed must not be null");
            if ((selected == null) != (operation == null)) {
                throw new IllegalArgumentException(
                        "selection and mutation operation must either both exist or both be absent");
            }
        }
    }

    record MutationBatch(PaperSeed seed, List<MutationStep> steps) {
        MutationBatch {
            seed = Objects.requireNonNull(seed, "seed must not be null");
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record GeneratedSeed(PaperSeed seed, NextSeedStrategy strategy) {
        public GeneratedSeed {
            seed = Objects.requireNonNull(seed, "seed must not be null");
            strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        }
    }

    private record EventSlot(PaperEvent.Type type, String name, int position) {
        private static EventSlot of(PaperEvent event) {
            return new EventSlot(event.type(), event.name(), event.position());
        }
    }
}
