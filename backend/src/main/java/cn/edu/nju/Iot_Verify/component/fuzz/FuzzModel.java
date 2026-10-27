package cn.edu.nju.Iot_Verify.component.fuzz;

import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperAtom;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperAtomValuation;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperCondition;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperDeviceDomain;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperEnvironmentDomain;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperEvent;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperEventDomainSnapshot;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperInitialValue;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperInitialValueDomain;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperMonitorFsm;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperPredecessorResolver;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperSeed;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperStructuredMonitorFactory;
import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperTruthValue;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.template.DeviceManifestModes;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.util.NaturalChangeRateParser;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;

/** Parsed, executable subset of the current NuSMV model semantics. */
final class FuzzModel {

    private final List<DeviceModel> devices;
    private final Map<String, DeviceModel> devicesById;
    private final Map<String, ValueDomain> environmentDomains;
    private final Map<String, ModelTokenSource> environmentTokenSources;
    private final List<RuleDto> rules;
    private final Map<String, List<RuleDto>> rulesByTarget;
    private final ModelState initialState;
    private final int choicesPerTransition;
    private final Map<String, NumericReachability> environmentReachability;
    private final Map<String, Map<String, NumericReachability>> localReachability;

    private FuzzModel(
            List<DeviceModel> devices,
            Map<String, DeviceModel> devicesById,
            Map<String, ValueDomain> environmentDomains,
            Map<String, ModelTokenSource> environmentTokenSources,
            List<RuleDto> rules,
            Map<String, List<RuleDto>> rulesByTarget,
            ModelState initialState,
            int choicesPerTransition) {
        this.devices = devices;
        this.devicesById = devicesById;
        this.environmentDomains = environmentDomains;
        this.environmentTokenSources = environmentTokenSources;
        this.rules = rules;
        this.rulesByTarget = rulesByTarget;
        this.initialState = initialState;
        this.choicesPerTransition = choicesPerTransition;
        this.environmentReachability = new LinkedHashMap<>();
        this.localReachability = new LinkedHashMap<>();
        indexNumericReachability();
    }

    static FuzzModel from(BoardDataConverter.ModelInputSnapshot snapshot) {
        if (snapshot == null) {
            throw error("Model snapshot is required.");
        }

        Map<String, DeviceManifest> manifests = new LinkedHashMap<>();
        snapshot.templateManifests().forEach((name, manifest) -> {
            if (hasText(name) && manifest != null) {
                manifests.putIfAbsent(name.trim().toLowerCase(Locale.ROOT), manifest);
            }
        });

        List<DeviceModel> deviceModels = new ArrayList<>();
        Map<String, DeviceModel> devicesById = new LinkedHashMap<>();
        Map<String, ValueDomain> environmentDomains = new LinkedHashMap<>();
        Map<String, ModelTokenSource> environmentTokenSources = new LinkedHashMap<>();
        // NuSMV CASE branches use the captured Board order for first-match writes.
        for (DeviceVerificationDto device : snapshot.devices()) {
            if (device == null || !hasText(device.getVarName())) {
                throw error("Every model device must have a canonical ID.");
            }
            String id = device.getVarName().trim();
            if (devicesById.containsKey(id)) {
                throw error("Duplicate canonical device ID '" + id + "'.");
            }
            DeviceManifest manifest = resolveManifest(device.getTemplateName(), manifests);
            if (manifest == null) {
                throw error("No captured manifest exists for template '" + device.getTemplateName() + "'.");
            }
            DeviceModel parsed = DeviceModel.from(device, manifest, environmentDomains);
            deviceModels.add(parsed);
            devicesById.put(id, parsed);
            mergeEnvironmentTokenSources(environmentTokenSources, parsed);
        }

        LinkedHashMap<String, String> initialEnvironment = new LinkedHashMap<>();
        Map<String, BoardEnvironmentVariableDto> submittedEnvironment = new LinkedHashMap<>();
        for (BoardEnvironmentVariableDto variable : snapshot.environmentVariables()) {
            if (variable == null || !hasText(variable.getName())) {
                throw error("Every environment value must have a name.");
            }
            String name = variable.getName().trim();
            if (submittedEnvironment.putIfAbsent(name, variable) != null) {
                throw error("Duplicate environment value '" + name + "'.");
            }
            if (!environmentDomains.containsKey(name)) {
                throw error("Environment value '" + name + "' has no captured manifest domain.");
            }
        }
        for (Map.Entry<String, ValueDomain> entry : environmentDomains.entrySet()) {
            BoardEnvironmentVariableDto submitted = submittedEnvironment.get(entry.getKey());
            if (submitted == null || !hasText(submitted.getValue())) {
                throw error("Environment variable '" + entry.getKey()
                        + "' requires an explicit initial value.");
            }
            String value = cleanLiteral(submitted.getValue());
            entry.getValue().requireValue(value, "environment variable '" + entry.getKey() + "'");
            initialEnvironment.put(entry.getKey(), value);
        }

        List<RuleDto> rules = snapshot.rules() == null ? List.of() : snapshot.rules();
        validateRules(rules, devicesById, environmentDomains);
        Map<String, List<RuleDto>> rulesByTarget = groupRulesByTarget(rules);
        LinkedHashMap<String, DeviceState> initialDevices = new LinkedHashMap<>();
        int choices = environmentDomains.size();
        for (DeviceModel device : deviceModels) {
            initialDevices.put(device.id, device.initialState.copy());
            choices += device.localDomains.size();
        }
        return new FuzzModel(
                List.copyOf(deviceModels),
                Collections.unmodifiableMap(devicesById),
                Collections.unmodifiableMap(environmentDomains),
                Collections.unmodifiableMap(environmentTokenSources),
                List.copyOf(rules),
                rulesByTarget,
                new ModelState(initialDevices, initialEnvironment, List.of()),
                choices);
    }

    int geneCount(int pathLength) {
        return Math.max(1, Math.max(0, pathLength - 1) * choicesPerTransition);
    }

    void validateSpecification(SpecificationDto specification) {
        if (specification == null) {
            throw error("Specification is required.");
        }
        String templateId = specification.getTemplateId();
        int aCount = size(specification.getAConditions());
        int ifCount = size(specification.getIfConditions());
        int thenCount = size(specification.getThenConditions());
        if ("1".equals(templateId) || "3".equals(templateId)) {
            if (aCount == 0 || ifCount != 0 || thenCount != 0) {
                throw error("Template " + templateId + " requires only aConditions.");
            }
        } else if ("4".equals(templateId)) {
            if (aCount != 0 || ifCount == 0 || thenCount == 0) {
                throw error("Template 4 requires ifConditions and thenConditions only.");
            }
        } else {
            throw error("Unsupported specification template '" + templateId + "'.");
        }
        for (SpecConditionDto condition : allConditions(specification)) {
            validateSpecificationCondition(condition);
        }
    }

    Simulation simulate(long[] genes, int pathLength) {
        return simulate(genes, pathLength, () -> false, List.of());
    }

    Simulation simulate(
            long[] genes,
            int pathLength,
            BooleanSupplier cancelled,
            List<SpecificationDto> earlyStopTargets) {
        GeneCursor cursor = new GeneCursor(genes);
        List<ModelState> states = new ArrayList<>(pathLength);
        List<TraceStateDto> traceStates = new ArrayList<>(pathLength);
        List<FuzzInputEvent> events = new ArrayList<>();
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        Map<String, SpecificationDto> monitoredTargets = new LinkedHashMap<>();
        for (SpecificationDto specification : safe(earlyStopTargets)) {
            if (specification != null && hasText(specification.getId())) {
                monitoredTargets.putIfAbsent(specification.getId(), specification);
            }
        }
        Set<String> violatedTargets = new LinkedHashSet<>();
        ModelState current = initialState.copy();
        states.add(current);
        traceStates.add(toTraceState(current, 0));
        recordViolationsAtStep(monitoredTargets, violatedTargets, null, current, 0);
        if (allMonitoredTargetsViolated(monitoredTargets, violatedTargets)) {
            return simulationResult(states, traceStates, events, cursor, false);
        }
        for (int step = 1; step < pathLength; step++) {
            if (cancellation.getAsBoolean()) {
                return simulationResult(states, traceStates, events, cursor, true);
            }
            ModelState previous = current;
            int eventStart = events.size();
            try {
                current = nextState(current, cursor, step, events, cancellation);
            } catch (SimulationCancelledException ignored) {
                while (events.size() > eventStart) {
                    events.remove(events.size() - 1);
                }
                return simulationResult(states, traceStates, events, cursor, true);
            }
            states.add(current);
            traceStates.add(toTraceState(current, step));
            recordViolationsAtStep(monitoredTargets, violatedTargets, previous, current, step);
            if (allMonitoredTargetsViolated(monitoredTargets, violatedTargets)) {
                break;
            }
        }
        return simulationResult(states, traceStates, events, cursor, false);
    }

    PaperEventDomainSnapshot paperEventDomain(int pathLength) {
        List<PaperDeviceDomain> paperDevices = new ArrayList<>();
        for (DeviceModel device : devices) {
            List<String> workingStates = device.workingStates.stream()
                    .map(DeviceManifest.WorkingState::getName)
                    .map(String::trim)
                    .toList();
            if (!workingStates.isEmpty()) {
                paperDevices.add(new PaperDeviceDomain(device.id, "state", workingStates));
            }
        }
        List<PaperEnvironmentDomain> paperEnvironment = new ArrayList<>();
        for (Map.Entry<String, ValueDomain> entry : environmentDomains.entrySet()) {
            ValueDomain valueDomain = entry.getValue();
            paperEnvironment.add(valueDomain.isNumeric()
                    ? PaperEnvironmentDomain.numeric(
                    entry.getKey(),
                    "environment",
                    entry.getKey(),
                    valueDomain.lower,
                    valueDomain.upper,
                    valueDomain.lowerRate,
                    valueDomain.upperRate)
                    : new PaperEnvironmentDomain(
                    entry.getKey(),
                    "environment",
                    entry.getKey(),
                    valueDomain.paperInitialValues(),
                    List.of()));
        }
        try {
            return new PaperEventDomainSnapshot(
                    pathLength,
                    paperDevices,
                    paperEnvironment,
                    paperLocalVariableDomains());
        } catch (IllegalArgumentException exception) {
            throw error("Paper-compatible event domain is invalid: " + exception.getMessage());
        }
    }

    List<PaperInitialValueDomain> paperLocalVariableDomains() {
        List<PaperInitialValueDomain> localVariables = new ArrayList<>();
        for (DeviceModel device : devices) {
            for (Map.Entry<String, ValueDomain> entry : device.localDomains.entrySet()) {
                ValueDomain domain = entry.getValue();
                localVariables.add(domain.isNumeric()
                        ? PaperInitialValueDomain.numeric(
                        PaperInitialValue.Type.DEVICE_VARIABLE,
                        device.id,
                        entry.getKey(),
                        domain.lower,
                        domain.upper)
                        : PaperInitialValueDomain.enumerated(
                        PaperInitialValue.Type.DEVICE_VARIABLE,
                        device.id,
                        entry.getKey(),
                        domain.values));
            }
        }
        return List.copyOf(localVariables);
    }

    Simulation simulatePaperSeed(
            PaperSeed seed,
            PaperEventDomainSnapshot eventDomain,
            int pathLength,
            BooleanSupplier cancelled) {
        return simulatePaperSeed(
                seed, eventDomain, pathLength, cancelled, List.of());
    }

    Simulation simulatePaperSeed(
            PaperSeed seed,
            PaperEventDomainSnapshot eventDomain,
            int pathLength,
            BooleanSupplier cancelled,
            List<SpecificationDto> earlyStopTargets) {
        if (seed == null || eventDomain == null) {
            throw error("Paper-compatible simulation requires a seed and event domain.");
        }
        if (pathLength != eventDomain.traceLength()) {
            throw error("Paper-compatible trace length does not match its event domain.");
        }
        BooleanSupplier cancellation = cancelled == null ? () -> false : cancelled;
        for (PaperEvent event : seed.events()) {
            validatePaperEvent(event, eventDomain, false);
        }
        for (PaperInitialValue override : seed.initialOverrides()) {
            validatePaperInitialValue(override, eventDomain);
        }

        ModelState current = initialState.copy();
        List<FuzzInputEvent> inputEvents = new ArrayList<>();
        List<PaperInitialValue> randomInitialValues = eventDomain.materializeInitialValues(
                seed.initializationNonce());
        for (PaperInitialValue value : randomInitialValues) {
            validatePaperInitialValue(value, eventDomain);
            applyPaperInitialValue(current, value, eventDomain);
            inputEvents.add(toPaperInputEvent(
                    value, FuzzInputEventSource.RANDOM_INITIAL_STATE));
        }
        for (PaperInitialValue override : seed.initialOverrides()) {
            applyPaperInitialValue(current, override, eventDomain);
            inputEvents.add(toPaperInputEvent(
                    override, FuzzInputEventSource.SEED_EVENT));
        }
        for (PaperEvent event : seed.events()) {
            if (event.position() == 1) {
                applyPaperEvent(current, event);
                inputEvents.add(toPaperInputEvent(
                        event, eventDomain, FuzzInputEventSource.SEED_EVENT));
            }
        }

        SplittableRandom transitionRandom = new SplittableRandom(
                seed.initializationNonce() ^ 0xBB67AE8584CAA73BL);
        long[] genes = new long[geneCount(pathLength)];
        for (int index = 0; index < genes.length; index++) {
            genes[index] = transitionRandom.nextLong();
        }
        GeneCursor cursor = new GeneCursor(genes);
        List<ModelState> states = new ArrayList<>(pathLength);
        List<TraceStateDto> traceStates = new ArrayList<>(pathLength);
        List<PaperMonitorFsm> earlyStopMonitors = (earlyStopTargets == null
                ? List.<SpecificationDto>of()
                : earlyStopTargets).stream()
                .map(PaperStructuredMonitorFactory::from)
                .toList();
        states.add(current);
        traceStates.add(toTraceState(current, 0));
        if (allPaperMonitorsViolated(earlyStopMonitors, current)) {
            return simulationResult(states, traceStates, inputEvents, cursor, false);
        }
        for (int step = 1; step < pathLength; step++) {
            if (cancellation.getAsBoolean()) {
                return simulationResult(states, traceStates, inputEvents, cursor, true);
            }
            int eventStart = inputEvents.size();
            try {
                int eventPosition = step + 1;
                PaperStep paperStep = preparePaperStep(
                        current, seed.events(), eventPosition, eventDomain, inputEvents);
                current = nextState(
                        current,
                        paperStep.inputState(),
                        cursor,
                        step,
                        inputEvents,
                        cancellation,
                        paperStep.overrides());
            } catch (SimulationCancelledException ignored) {
                while (inputEvents.size() > eventStart) {
                    inputEvents.remove(inputEvents.size() - 1);
                }
                return simulationResult(states, traceStates, inputEvents, cursor, true);
            }
            states.add(current);
            traceStates.add(toTraceState(current, step));
            if (allPaperMonitorsViolated(earlyStopMonitors, current)) {
                return simulationResult(states, traceStates, inputEvents, cursor, false);
            }
        }
        return simulationResult(states, traceStates, inputEvents, cursor, false);
    }

    private boolean allPaperMonitorsViolated(
            List<PaperMonitorFsm> monitors,
            ModelState state) {
        if (monitors.isEmpty()) {
            return false;
        }
        for (PaperMonitorFsm monitor : monitors) {
            monitor.step(atom -> paperAtomMatches(atom, state));
        }
        return monitors.stream().allMatch(monitor ->
                monitor.currentTruthValue() == PaperTruthValue.FALSE);
    }

    /**
     * Applies scheduled paper inputs before the formal transition step. Numeric rate events
     * are previewed for guards, while their un-clamped base and rate are retained so the
     * target state can combine the event and device impact in one clamp operation.
     */
    private PaperStep preparePaperStep(
            ModelState previous,
            List<PaperEvent> seedEvents,
            int position,
            PaperEventDomainSnapshot eventDomain,
            List<FuzzInputEvent> inputEvents) {
        ModelState inputState = previous.copy();
        Map<String, Integer> eventRates = new LinkedHashMap<>();
        Map<String, String> eventBaseValues = new LinkedHashMap<>();
        for (PaperEvent event : seedEvents) {
            if (event.position() != position) {
                continue;
            }
            if (isPaperEnvironmentRateEvent(event)) {
                ValueDomain domain = environmentDomains.get(event.name());
                String previousValue = previous.environment.get(event.name());
                int currentValue = parseInteger(previousValue, "paper-compatible environment value");
                int rate = parseInteger(
                        event.value().substring(PaperEnvironmentDomain.RATE_PREFIX.length()),
                        "paper-compatible environment rate");
                inputState.environment.put(
                        event.name(),
                        Long.toString(domain.clamp((long) currentValue + rate)));
                eventRates.put(event.name(), rate);
                eventBaseValues.put(event.name(), previousValue);
            } else {
                applyPaperEvent(inputState, event);
            }
            inputEvents.add(toPaperInputEvent(
                    event, eventDomain, FuzzInputEventSource.SEED_EVENT));
        }
        return new PaperStep(
                inputState,
                new PaperStepOverrides(eventRates, eventBaseValues, true));
    }

    private void validatePaperEvent(
            PaperEvent event,
            PaperEventDomainSnapshot eventDomain,
            boolean randomInitialEvent) {
        if (event == null || event.position() > eventDomain.traceLength()) {
            throw error("Paper-compatible event is outside the configured trace.");
        }
        if (event.type() == PaperEvent.Type.DEVICE) {
            PaperDeviceDomain domain = eventDomain.device(event.name());
            if (domain == null || !domain.legalValues().contains(event.value())) {
                throw error("Paper-compatible device event is outside its legal domain.");
            }
            return;
        }
        PaperEnvironmentDomain domain = eventDomain.environment(event.name());
        boolean allowed = domain != null && (randomInitialEvent
                ? domain.isValidInitialValue(event.value())
                : domain.isValidEventValue(event.value()));
        if (!allowed) {
            throw error("Paper-compatible environment event is outside its legal domain.");
        }
    }

    private void validatePaperInitialValue(
            PaperInitialValue value,
            PaperEventDomainSnapshot eventDomain) {
        if (value == null) {
            throw error("Paper-compatible initial value is required.");
        }
        PaperInitialValueDomain domain = eventDomain.initialValueDomain(value.target());
        if (domain == null || !domain.isValidValue(value.value())) {
            throw error("Paper-compatible initial value is outside its legal domain.");
        }
    }

    private FuzzInputEvent toPaperInputEvent(
            PaperInitialValue value,
            FuzzInputEventSource source) {
        FuzzInputEventKind kind = switch (value.type()) {
            case DEVICE_STATE -> FuzzInputEventKind.DEVICE_STATE;
            case ENVIRONMENT_VALUE -> FuzzInputEventKind.ENVIRONMENT_VALUE;
            case DEVICE_VARIABLE -> FuzzInputEventKind.DEVICE_VARIABLE;
        };
        return new FuzzInputEvent(
                0,
                kind,
                value.targetId(),
                value.property(),
                value.value(),
                source);
    }

    private FuzzInputEvent toPaperInputEvent(
            PaperEvent event,
            PaperEventDomainSnapshot eventDomain,
            FuzzInputEventSource source) {
        if (event.type() == PaperEvent.Type.DEVICE) {
            PaperDeviceDomain domain = eventDomain.device(event.name());
            return new FuzzInputEvent(
                    event.position() - 1,
                    FuzzInputEventKind.DEVICE_STATE,
                    domain.targetId(),
                    domain.property(),
                    event.value(),
                    source);
        }
        PaperEnvironmentDomain domain = eventDomain.environment(event.name());
        return new FuzzInputEvent(
                event.position() - 1,
                source == FuzzInputEventSource.RANDOM_INITIAL_STATE || !domain.hasRateDomain()
                        ? FuzzInputEventKind.ENVIRONMENT_VALUE
                        : FuzzInputEventKind.ENVIRONMENT_RATE,
                domain.targetId(),
                domain.property(),
                event.value(),
                source);
    }

    private void applyPaperEvent(ModelState state, PaperEvent event) {
        if (event.type() == PaperEvent.Type.DEVICE) {
            DeviceModel device = devicesById.get(event.name());
            if (device == null) {
                throw error("Unknown paper-compatible device event target '" + event.name() + "'.");
            }
            int workingStateIndex = -1;
            for (int index = 0; index < device.workingStates.size(); index++) {
                if (event.value().equals(device.workingStates.get(index).getName())) {
                    workingStateIndex = index;
                    break;
                }
            }
            if (workingStateIndex < 0) {
                throw error("Illegal paper-compatible working state '" + event.value() + "'.");
            }
            String[] selected = device.workingStateTuples.get(workingStateIndex);
            System.arraycopy(selected, 0, state.devices.get(device.id).modes, 0, selected.length);
            return;
        }

        ValueDomain domain = environmentDomains.get(event.name());
        if (domain == null) {
            throw error("Unknown paper-compatible environment event target '" + event.name() + "'.");
        }
        String value;
        if (isPaperEnvironmentRateEvent(event)) {
            int rate = parseInteger(
                    event.value().substring(PaperEnvironmentDomain.RATE_PREFIX.length()),
                    "paper-compatible environment rate");
            int current = parseInteger(state.environment.get(event.name()), "paper-compatible environment value");
            value = Long.toString(domain.clamp((long) current + rate));
        } else {
            value = cleanLiteral(event.value());
            domain.requireValue(value, "paper-compatible environment event");
        }
        state.environment.put(event.name(), value);
    }

    private void applyPaperInitialValue(
            ModelState state,
            PaperInitialValue value,
            PaperEventDomainSnapshot eventDomain) {
        switch (value.type()) {
            case DEVICE_STATE -> applyPaperEvent(state, new PaperEvent(
                    PaperEvent.Type.DEVICE,
                    value.targetId(),
                    value.value(),
                    1));
            case ENVIRONMENT_VALUE -> {
                PaperEnvironmentDomain environment = eventDomain.environment(
                        value.targetId(), value.property());
                if (environment == null) {
                    throw error("Unknown paper-compatible environment initial target.");
                }
                ValueDomain domain = environmentDomains.get(environment.name());
                if (domain == null) {
                    throw error("Paper-compatible environment initial target is outside the model.");
                }
                String normalized = cleanLiteral(value.value());
                domain.requireValue(normalized, "paper-compatible environment initial value");
                state.environment.put(environment.name(), normalized);
            }
            case DEVICE_VARIABLE -> {
                DeviceModel device = devicesById.get(value.targetId());
                ValueDomain domain = device == null
                        ? null
                        : device.localDomains.get(value.property());
                if (domain == null) {
                    throw error("Unknown paper-compatible device variable initial target.");
                }
                String normalized = cleanLiteral(value.value());
                domain.requireValue(normalized, "paper-compatible device variable initial value");
                state.devices.get(device.id).locals.put(value.property(), normalized);
            }
        }
    }

    private boolean isPaperEnvironmentRateEvent(PaperEvent event) {
        if (event.type() != PaperEvent.Type.ENVIRONMENT
                || !event.value().startsWith(PaperEnvironmentDomain.RATE_PREFIX)) {
            return false;
        }
        ValueDomain domain = environmentDomains.get(event.name());
        return domain != null && domain.isNumeric();
    }

    private Simulation simulationResult(
            List<ModelState> states,
            List<TraceStateDto> traceStates,
            List<FuzzInputEvent> events,
            GeneCursor cursor,
            boolean cancelled) {
        return new Simulation(
                List.copyOf(states),
                List.copyOf(traceStates),
                List.copyOf(events),
                cursor.mutableChoiceBounds(),
                cancelled);
    }

    private void recordViolationsAtStep(
            Map<String, SpecificationDto> monitoredTargets,
            Set<String> violatedTargets,
            ModelState previous,
            ModelState current,
            int step) {
        for (Map.Entry<String, SpecificationDto> entry : monitoredTargets.entrySet()) {
            if (violatedTargets.contains(entry.getKey())) {
                continue;
            }
            SpecificationDto specification = entry.getValue();
            boolean violated = switch (specification.getTemplateId()) {
                case "1" -> !groupMatches(specification.getAConditions(), current);
                case "3" -> groupMatches(specification.getAConditions(), current);
                case "4" -> step > 0
                        && groupMatches(specification.getIfConditions(), previous)
                        && !groupMatches(specification.getThenConditions(), current);
                default -> false;
            };
            if (violated) {
                violatedTargets.add(entry.getKey());
            }
        }
    }

    private static boolean allMonitoredTargetsViolated(
            Map<String, SpecificationDto> monitoredTargets,
            Set<String> violatedTargets) {
        return !monitoredTargets.isEmpty() && violatedTargets.size() == monitoredTargets.size();
    }

    private void indexNumericReachability() {
        for (Map.Entry<String, ValueDomain> entry : environmentDomains.entrySet()) {
            String name = entry.getKey();
            ValueDomain domain = entry.getValue();
            boolean mayBeWritten = devices.stream().anyMatch(device ->
                    device.numericImpactedEnvironment.contains(name)
                            || transitionAssignment(device.transitions, name) != null);
            if (domain.isNumeric() && !mayBeWritten) {
                environmentReachability.put(name, rateReachability(domain, initialState.environment.get(name)));
            }
        }
        for (DeviceModel device : devices) {
            Map<String, NumericReachability> local = new LinkedHashMap<>();
            for (Map.Entry<String, ValueDomain> entry : device.localDomains.entrySet()) {
                String name = entry.getKey();
                ValueDomain domain = entry.getValue();
                boolean hasDynamic = device.workingStates.stream().anyMatch(state -> {
                    DynamicEffect effect = dynamicEffect(state, name);
                    return effect.numericRate() != 0 || hasText(effect.discreteValue());
                });
                if (domain.isNumeric() && !hasDynamic && transitionAssignment(device.transitions, name) == null) {
                    local.put(name, rateReachability(domain, initialState.devices.get(device.id).locals.get(name)));
                }
            }
            localReachability.put(device.id, local);
        }
    }

    private static NumericReachability rateReachability(ValueDomain domain, String initial) {
        return new NumericReachability(parseInteger(initial, "for initial numeric state"),
                domain.lower, domain.upper, domain.lowerRate, domain.upperRate);
    }

    private List<TimedCondition> timedConditions(List<SpecConditionDto> conditions, boolean truth) {
        return safe(conditions).stream()
                .map(condition -> timedCondition(condition, truth))
                .toList();
    }

    private TimedCondition timedCondition(SpecConditionDto condition, boolean truth) {
        if (!"variable".equals(normalized(condition.getTargetType()))) {
            return (state, step) -> discreteDistance(condition, truth, state, step);
        }
        DeviceModel device = devicesById.get(condition.getDeviceId());
        String key = conditionKey(condition);
        ValueDomain domain = device.localDomains.get(key);
        boolean local = domain != null;
        if (!local) {
            domain = environmentDomains.get(key);
        }
        if (domain == null || !domain.isNumeric()) {
            return (state, step) -> discreteDistance(condition, truth, state, step);
        }
        NumericReachability reachable = local ? localReachability.get(device.id).get(key)
                : environmentReachability.get(key);
        String relation = normalizeRelation(condition.getRelation());
        Set<Integer> expected = new LinkedHashSet<>();
        for (String value : splitCandidates(condition.getValue(), relation, false)) {
            expected.add(parseInteger(value, "for numeric specification condition"));
        }
        ValueDomain numericDomain = domain;
        double span = Math.max(1L, (long) domain.upper - domain.lower);
        return (state, step) -> {
            if (step == 0) {
                return specificationConditionMatches(condition, state) == truth ? 0.0 : Double.POSITIVE_INFINITY;
            }
            String actual = resolveConditionValue(device, state.devices.get(device.id), state, condition, "variable");
            int value = parseInteger(actual, "for numeric specification state");
            // The nearest target and its feasibility use the same support, including set alternatives.
            // Unknown writers keep the full declared domain; only a proof may narrow it.
            double distance = reachable != null ? reachable.distanceAt(step, value, relation, expected, truth)
                    : NumericReachability.distanceInRange(value, numericDomain.lower, numericDomain.upper,
                    relation, expected, truth);
            return distance / span;
        };
    }

    private double discreteDistance(SpecConditionDto condition, boolean truth, ModelState state, int step) {
        if (specificationConditionMatches(condition, state) == truth) {
            return 0.0;
        }
        return step == 0 ? Double.POSITIVE_INFINITY : 1.0;
    }

    SpecEvaluation evaluate(SpecificationDto specification, Simulation simulation) {
        List<ModelState> states = simulation.states();
        if ("1".equals(specification.getTemplateId())) {
            List<TimedCondition> failures = timedConditions(specification.getAConditions(), false);
            double distance = Double.POSITIVE_INFINITY;
            for (int step = 0; step < states.size(); step++) {
                if (!groupMatches(specification.getAConditions(), states.get(step))) {
                    return new SpecEvaluation(step, 0.0);
                }
                distance = Math.min(distance, groupFalsificationDistance(failures, states.get(step), step));
            }
            return new SpecEvaluation(-1, finiteDistance(distance));
        }
        if ("3".equals(specification.getTemplateId())) {
            List<TimedCondition> matches = timedConditions(specification.getAConditions(), true);
            double distance = Double.POSITIVE_INFINITY;
            for (int step = 0; step < states.size(); step++) {
                if (groupMatches(specification.getAConditions(), states.get(step))) {
                    return new SpecEvaluation(step, 0.0);
                }
                distance = Math.min(distance, groupMatchDistance(matches, states.get(step), step));
            }
            return new SpecEvaluation(-1, finiteDistance(distance));
        }

        List<TimedCondition> antecedents = timedConditions(specification.getIfConditions(), true);
        List<TimedCondition> failures = timedConditions(specification.getThenConditions(), false);
        double distance = Double.POSITIVE_INFINITY;
        for (int step = 0; step + 1 < states.size(); step++) {
            boolean antecedent = groupMatches(specification.getIfConditions(), states.get(step));
            boolean consequent = groupMatches(specification.getThenConditions(), states.get(step + 1));
            if (antecedent && !consequent) {
                return new SpecEvaluation(step + 1, 0.0);
            }
            double antecedentDistance = groupMatchDistance(antecedents, states.get(step), step);
            double consequentFalsification = groupFalsificationDistance(failures, states.get(step + 1), step + 1);
            distance = Math.min(distance, antecedentDistance + consequentFalsification);
        }
        return new SpecEvaluation(-1, finiteDistance(distance));
    }

    SpecEvaluation evaluatePaper(
            SpecificationDto specification,
            Simulation simulation,
            int solverLevels) {
        PaperMonitorFsm monitor = PaperStructuredMonitorFactory.from(specification);
        PaperPredecessorResolver predecessorResolver = memoizedPaperPredecessorResolver();
        double traceDistance = Double.POSITIVE_INFINITY;
        for (int step = 0; step < simulation.states().size(); step++) {
            ModelState state = simulation.states().get(step);
            PaperAtomValuation valuation = paperAtomValuation(state);
            PaperTruthValue truthValue = monitor.step(valuation);
            if (truthValue == PaperTruthValue.FALSE) {
                return new SpecEvaluation(step, 0.0);
            }
            double stateDistance = monitor.distanceToViolation(
                            valuation,
                            predecessorResolver,
                            solverLevels)
                    .map(PaperMonitorFsm.Distance::combinedDistance)
                    .orElse(Double.POSITIVE_INFINITY);
            traceDistance = Math.min(traceDistance, stateDistance);
        }
        return new SpecEvaluation(-1, finiteDistance(traceDistance));
    }

    private PaperAtomValuation paperAtomValuation(ModelState state) {
        return atom -> paperAtomMatches(atom, state);
    }

    private PaperPredecessorResolver memoizedPaperPredecessorResolver() {
        Map<PaperPredecessorCacheKey, Optional<PaperCondition>> cache = new LinkedHashMap<>();
        // Rule producers depend on the literal and model, not the solver level or valuation.
        Map<PaperCondition, Optional<PaperCondition>> producers = new LinkedHashMap<>();
        return (condition, level) -> cache.computeIfAbsent(
                        new PaperPredecessorCacheKey(condition, level),
                        key -> Optional.ofNullable(previousPaperConditions(
                                key.condition(), key.level(), producers)))
                .orElse(null);
    }

    private boolean paperAtomMatches(PaperAtom atom, ModelState state) {
        DeviceModel device = devicesById.get(atom.deviceId());
        if (device == null) {
            return false;
        }
        DeviceState deviceState = state.devices.get(device.id);
        String type = normalized(atom.targetType());
        if ("state".equals(type)) {
            return compareState(device, deviceState, atom.relation(), atom.value());
        }
        String actual;
        if ("mode".equals(type)) {
            actual = deviceState.modes[device.modeIndex(atom.key())];
        } else if ("api".equals(type)) {
            actual = deviceState.apiSignals.contains(atom.key()) ? "TRUE" : "FALSE";
        } else {
            actual = deviceState.locals.get(atom.key());
            if (actual == null) {
                actual = state.environment.get(atom.key());
            }
        }
        return compare(actual, atom.relation(), atom.value());
    }

    private PaperCondition previousPaperConditions(
            PaperCondition condition,
            int currentLevel,
            Map<PaperCondition, Optional<PaperCondition>> producers) {
        if (condition instanceof PaperCondition.Terminal
                || condition instanceof MissingPaperPredecessor) {
            return null;
        }
        if (condition instanceof PaperCondition.Atom atomCondition) {
            return producers.computeIfAbsent(condition, ignored ->
                    Optional.ofNullable(paperRulePredecessors(atomCondition.atom(), true))).orElse(null);
        }
        if (condition instanceof PaperCondition.Not notCondition
                && notCondition.condition() instanceof PaperCondition.Atom atomCondition) {
            return producers.computeIfAbsent(condition, ignored ->
                    Optional.ofNullable(paperRulePredecessors(atomCondition.atom(), false))).orElse(null);
        }
        if (condition instanceof PaperCondition.All all) {
            List<PaperCondition> predecessors = new ArrayList<>();
            for (PaperCondition child : all.conditions()) {
                PaperCondition predecessor = previousPaperConditions(child, currentLevel, producers);
                if (predecessor != null) {
                    predecessors.add(predecessor);
                } else if (currentLevel == 1) {
                    predecessors.add(new MissingPaperPredecessor(child));
                }
            }
            return predecessors.isEmpty() ? null : PaperCondition.allOf(predecessors);
        }
        if (condition instanceof PaperCondition.Any any) {
            List<PaperCondition> predecessors = any.conditions().stream()
                    .map(child -> previousPaperConditions(child, currentLevel, producers))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return predecessors.isEmpty() ? null : PaperCondition.anyOf(predecessors);
        }
        return null;
    }

    private record PaperPredecessorCacheKey(PaperCondition condition, int level) {
    }

    /** Preserves a direct conjunct's zero contribution without creating a recursive producer. */
    private record MissingPaperPredecessor(PaperCondition source) implements PaperCondition {
        @Override
        public boolean test(PaperAtomValuation valuation) {
            return false;
        }

        @Override
        public double satisfaction(PaperAtomValuation valuation) {
            return 0.0;
        }
    }

    private PaperCondition paperRulePredecessors(PaperAtom target, boolean expectedTruth) {
        List<PaperCondition> guards = new ArrayList<>();
        List<PaperRuleEffect> earlierEffects = new ArrayList<>();
        for (RuleDto rule : rules) {
            RuleDto.Command command = rule.getCommand();
            DeviceModel device = command == null ? null : devicesById.get(command.getDeviceName());
            DeviceManifest.API api = device == null ? null : device.apis.get(command.getAction());
            if (api == null) {
                continue;
            }
            Set<Integer> affectedModes = affectedModeIndexes(device, api);
            PaperCondition ruleGuard = paperRuleGuard(rule);
            if (paperRuleActionProduces(rule, target, expectedTruth)) {
                PaperCondition candidateGuard = ruleGuard;
                if ("api".equals(normalized(target.targetType()))) {
                    candidateGuard = PaperCondition.allOf(
                            candidateGuard,
                            paperApiSignalChangeGuard(device, api));
                }
                List<PaperCondition> blockers = earlierEffects.stream()
                        .filter(earlier -> earlier.deviceId().equals(device.id)
                                && !Collections.disjoint(
                                earlier.affectedModes(), affectedModes))
                        .map(PaperRuleEffect::guard)
                        .toList();
                guards.add(blockers.isEmpty()
                        ? candidateGuard
                        : PaperCondition.allOf(
                        candidateGuard,
                        PaperCondition.terminal(
                                PaperCondition.not(PaperCondition.anyOf(blockers)))));
            }
            earlierEffects.add(new PaperRuleEffect(device.id, affectedModes, ruleGuard));
        }
        return guards.isEmpty() ? null : PaperCondition.anyOf(guards);
    }

    private boolean paperRuleActionProduces(
            RuleDto rule,
            PaperAtom target,
            boolean expectedTruth) {
        RuleDto.Command command = rule.getCommand();
        if (command == null || !target.deviceId().equals(command.getDeviceName())) {
            return false;
        }
        DeviceModel device = devicesById.get(command.getDeviceName());
        DeviceManifest.API api = device == null ? null : device.apis.get(command.getAction());
        if (api == null) {
            return false;
        }
        String type = normalized(target.targetType());
        if ("api".equals(type)) {
            boolean emitted = target.key().equals(api.getName()) && Boolean.TRUE.equals(api.getSignal());
            return emitted && compare("TRUE", target.relation(), target.value()) == expectedTruth;
        }
        if ("variable".equals(type)) {
            return false;
        }

        Boolean result;
        if ("mode".equals(type)) {
            String actual = stateSegment(
                    api.getEndState(), device.modeIndex(target.key()), false);
            result = hasText(actual) ? compare(actual, target.relation(), target.value()) : null;
        } else if ("state".equals(type)) {
            String[] knownResult = new String[device.modes.size()];
            for (int modeIndex = 0; modeIndex < knownResult.length; modeIndex++) {
                String end = stateSegment(api.getEndState(), modeIndex, false);
                knownResult[modeIndex] = hasText(end)
                        ? end
                        : stateSegment(api.getStartState(), modeIndex, true);
            }
            result = knownPaperStateMatch(device, knownResult, target.relation(), target.value());
        } else {
            return false;
        }
        return result != null && result == expectedTruth;
    }

    private static PaperCondition paperApiSignalChangeGuard(
            DeviceModel device,
            DeviceManifest.API api) {
        List<PaperCondition> changedModes = new ArrayList<>();
        for (int modeIndex = 0; modeIndex < device.modes.size(); modeIndex++) {
            String end = stateSegment(api.getEndState(), modeIndex, false);
            if (!hasText(end)) {
                continue;
            }
            changedModes.add(PaperCondition.atom(new PaperAtom(
                    device.id,
                    "mode",
                    device.modes.get(modeIndex),
                    null,
                    "!=",
                    end)));
        }
        return changedModes.isEmpty()
                ? PaperCondition.FALSE
                : PaperCondition.anyOf(changedModes);
    }

    private static Boolean knownPaperStateMatch(
            DeviceModel device,
            String[] knownResult,
            String rawRelation,
            String rawValue) {
        String relation = normalizeRelation(rawRelation);
        List<Map<Integer, String>> candidates = stateCandidates(device, rawValue, relation);
        boolean anyMatch = false;
        boolean anyUnknown = false;
        for (Map<Integer, String> candidate : candidates) {
            boolean mismatch = false;
            boolean unknown = false;
            for (Map.Entry<Integer, String> expected : candidate.entrySet()) {
                String actual = knownResult[expected.getKey()];
                if (!hasText(actual)) {
                    unknown = true;
                } else if (!actual.equals(expected.getValue())) {
                    mismatch = true;
                    break;
                }
            }
            if (!mismatch && !unknown) {
                anyMatch = true;
                break;
            }
            if (!mismatch) {
                anyUnknown = true;
            }
        }
        Boolean equalResult = anyMatch ? Boolean.TRUE : anyUnknown ? null : Boolean.FALSE;
        if ("=".equals(relation) || "in".equals(relation)) {
            return equalResult;
        }
        if ("!=".equals(relation) || "not in".equals(relation)) {
            return equalResult == null ? null : !equalResult;
        }
        return null;
    }

    private PaperCondition paperRuleGuard(RuleDto rule) {
        List<PaperCondition> conditions = new ArrayList<>();
        for (RuleDto.Condition condition : safe(rule.getConditions())) {
            boolean apiSignal = "api".equals(normalized(condition.getTargetType()));
            PaperAtom atom = new PaperAtom(
                    condition.getDeviceName(),
                    condition.getTargetType(),
                    condition.getAttribute(),
                    null,
                    apiSignal ? "=" : condition.getRelation(),
                    apiSignal ? "TRUE" : condition.getValue());
            conditions.add(PaperCondition.atom(atom));
        }
        RuleDto.Command command = rule.getCommand();
        DeviceModel targetDevice = devicesById.get(command.getDeviceName());
        DeviceManifest.API action = targetDevice.apis.get(command.getAction());
        for (int modeIndex = 0; modeIndex < targetDevice.modes.size(); modeIndex++) {
            String requiredStart = stateSegment(action.getStartState(), modeIndex, true);
            if (hasText(requiredStart)) {
                PaperCondition startCondition = PaperCondition.atom(new PaperAtom(
                        targetDevice.id,
                        "mode",
                        targetDevice.modes.get(modeIndex),
                        null,
                        "=",
                        requiredStart));
                // Model-added executability checks must not reweight an identical authored condition.
                if (!conditions.contains(startCondition)) {
                    conditions.add(startCondition);
                }
            }
        }
        return PaperCondition.allOf(conditions);
    }

    private ModelState nextState(
            ModelState current,
            GeneCursor cursor,
            int step,
            List<FuzzInputEvent> events,
            BooleanSupplier cancelled) {
        return nextState(
                current, current, cursor, step, events, cancelled, PaperStepOverrides.NONE);
    }

    private ModelState nextState(
            ModelState transitionSource,
            ModelState targetBase,
            GeneCursor cursor,
            int step,
            List<FuzzInputEvent> events,
            BooleanSupplier cancelled,
            PaperStepOverrides paperOverrides) {
        LinkedHashMap<String, DeviceState> nextDevices = new LinkedHashMap<>();
        Map<String, Set<DeviceManifest.Transition>> selectedTransitions = new LinkedHashMap<>();
        Set<RuleDto> triggeredRules = Collections.newSetFromMap(new IdentityHashMap<>());

        for (DeviceModel device : devices) {
            ensureNotCancelled(cancelled);
            DeviceState before = transitionSource.devices.get(device.id);
            DeviceState copiedTarget = targetBase.devices.get(device.id);
            String[] nextModes = copiedTarget.modes.clone();
            Set<Integer> ruleControlledModes = new LinkedHashSet<>();
            List<RuleGuard> earlierRuleGuards = new ArrayList<>();
            for (RuleDto rule : rulesByTarget.getOrDefault(device.id, List.of())) {
                ensureNotCancelled(cancelled);
                RuleDto.Command command = rule.getCommand();
                if (command == null || !device.id.equals(command.getDeviceName())) {
                    continue;
                }
                DeviceManifest.API api = device.apis.get(command.getAction());
                if (api == null) {
                    continue;
                }
                Set<Integer> affectedModes = affectedModeIndexes(device, api);
                boolean rawGuard = matchesPartialState(before.modes, api.getStartState(), true)
                        && ruleMatches(rule, transitionSource);
                boolean blocked = rawGuard && earlierRuleGuards.stream().anyMatch(earlier ->
                        earlier.rawGuard() && !Collections.disjoint(earlier.affectedModes(), affectedModes));
                if (rawGuard && !blocked && !affectedModes.isEmpty()) {
                    for (int modeIndex : affectedModes) {
                        nextModes[modeIndex] = stateSegment(api.getEndState(), modeIndex, false);
                        ruleControlledModes.add(modeIndex);
                    }
                    triggeredRules.add(rule);
                }
                earlierRuleGuards.add(new RuleGuard(affectedModes, rawGuard));
            }
            for (int modeIndex = 0; modeIndex < device.modes.size(); modeIndex++) {
                if (ruleControlledModes.contains(modeIndex)) {
                    continue;
                }
                for (DeviceManifest.Transition transition : device.transitions) {
                    if (!hasEndForMode(transition.getEndState(), modeIndex)
                            || !matchesPartialState(before.modes, transition.getStartState(), true)
                            || !triggerMatches(
                            device, before, transition.getTrigger(), transitionSource.environment)) {
                        continue;
                    }
                    nextModes[modeIndex] = stateSegment(transition.getEndState(), modeIndex, false);
                    break;
                }
            }
            Set<DeviceManifest.Transition> enabledTransitions = new LinkedHashSet<>();
            for (DeviceManifest.Transition transition : device.transitions) {
                if (matchesPartialState(before.modes, transition.getStartState(), true)
                        && triggerMatches(
                        device, before, transition.getTrigger(), transitionSource.environment)) {
                    enabledTransitions.add(transition);
                }
            }
            selectedTransitions.put(device.id, enabledTransitions);
            nextDevices.put(device.id, new DeviceState(
                    nextModes,
                    new LinkedHashMap<>(copiedTarget.locals),
                    new LinkedHashSet<>(),
                    copiedTarget.impactRates));
        }

        for (DeviceModel device : devices) {
            ensureNotCancelled(cancelled);
            DeviceState before = transitionSource.devices.get(device.id);
            DeviceState after = nextDevices.get(device.id);
            DeviceManifest.WorkingState activeState = device.activeWorkingState(before.modes);
            for (Map.Entry<String, ValueDomain> entry : device.localDomains.entrySet()) {
                String name = entry.getKey();
                ValueDomain domain = entry.getValue();
                String forced = transitionAssignment(selectedTransitions.get(device.id), name);
                DynamicEffect dynamic = dynamicEffect(activeState, name);
                List<String> candidates = domain.nextCandidates(
                        before.locals.get(name),
                        dynamic.numericRate(),
                        firstText(forced, dynamic.discreteValue()),
                        false);
                int selectedIndex = cursor.choose(candidates.size());
                String value = candidates.get(selectedIndex);
                after.locals.put(name, value);
                if (candidates.size() > 1) {
                    events.add(new FuzzInputEvent(
                            step,
                            FuzzInputEventKind.DEVICE_VARIABLE,
                            device.id,
                            name,
                            value,
                            FuzzInputEventSource.MODEL_CHOICE));
                }
            }
        }

        LinkedHashMap<String, String> nextEnvironment = new LinkedHashMap<>();
        for (Map.Entry<String, ValueDomain> entry : environmentDomains.entrySet()) {
            ensureNotCancelled(cancelled);
            String name = entry.getKey();
            String forced = null;
            String discreteDynamic = null;
            long currentImpactRate = 0L;
            boolean hasNumericImpact = false;
            // Whether any device declares it writes this shared value. A written value holds when no
            // declared effect applies; only a genuinely exogenous one may choose freely. Matches the
            // formal generator, so a finding and a counterexample describe the same system.
            boolean hasDeclaredWriter = false;
            for (DeviceModel device : devices) {
                if (forced == null) {
                    forced = transitionAssignment(selectedTransitions.get(device.id), name);
                }
                DeviceState deviceState = transitionSource.devices.get(device.id);
                if (entry.getValue().isNumeric() && device.numericImpactedEnvironment.contains(name)) {
                    hasNumericImpact = true;
                    // Derive the effect from the state the device is in for this step, matching the
                    // formal generator's DEFINE. Reading a stored rate carried the previous step's
                    // value, so a device that had just started acting contributed nothing until the
                    // step after -- and one starting in an acting mode contributed nothing at all.
                    currentImpactRate += dynamicEffect(
                            device.activeWorkingState(deviceState.modes), name).numericRate();
                } else if (!entry.getValue().isNumeric() && device.impactedEnvironment.contains(name)) {
                    hasDeclaredWriter = true;
                    DynamicEffect dynamic = dynamicEffect(device.activeWorkingState(deviceState.modes), name);
                    String declared = dynamic.discreteValue();
                    if (hasText(declared)) {
                        if (discreteDynamic == null) {
                            discreteDynamic = declared;
                        } else if (!discreteDynamic.equals(declared)) {
                            // Two simultaneously-active writers assigning different values. Enums have no
                            // additive composition, so taking the first would make the outcome depend on
                            // device iteration order -- the same order dependence the formal generator
                            // used to have. Refuse, matching SmvModelValidator, so a finding and a
                            // counterexample cannot disagree about whether the scene is even modelable.
                            throw error("Conflicting declared effects on shared value '" + name
                                    + "': '" + discreteDynamic + "' versus '" + declared
                                    + "'. There is no defined way to combine two different values.");
                        }
                    }
                }
            }
            List<String> candidates;
            if (entry.getValue().isNumeric()) {
                if (hasText(forced)) {
                    candidates = entry.getValue().nextEnvironmentNumericCandidates(
                            transitionSource.environment.get(name),
                            currentImpactRate,
                            forced,
                            hasNumericImpact);
                } else if (paperOverrides.eventRates().containsKey(name)) {
                    String baseValue = paperOverrides.eventBaseValues().get(name);
                    long base = parseInteger(baseValue, "for numeric environment state");
                    long rate = paperOverrides.eventRates().get(name);
                    candidates = List.of(Long.toString(
                            entry.getValue().clamp(base + rate + currentImpactRate)));
                } else {
                    candidates = entry.getValue().nextEnvironmentNumericCandidates(
                            transitionSource.environment.get(name),
                            currentImpactRate,
                            null,
                            hasNumericImpact);
                }
            } else {
                String forcedValue = firstText(forced, discreteDynamic);
                if (hasText(forcedValue)) {
                    candidates = entry.getValue().nextCandidates(
                            targetBase.environment.get(name), 0, forcedValue, false);
                } else if (paperOverrides.paperMode()) {
                    candidates = List.of(cleanLiteral(targetBase.environment.get(name)));
                } else {
                    candidates = entry.getValue().nextCandidates(
                            transitionSource.environment.get(name), 0, null, !hasDeclaredWriter);
                }
            }
            int selectedIndex = cursor.choose(candidates.size());
            String value = candidates.get(selectedIndex);
            nextEnvironment.put(name, value);
            if (candidates.size() > 1) {
                events.add(new FuzzInputEvent(
                        step,
                        FuzzInputEventKind.ENVIRONMENT_VALUE,
                        "environment",
                        name,
                        value,
                        FuzzInputEventSource.MODEL_CHOICE));
            }
        }

        for (DeviceModel device : devices) {
            ensureNotCancelled(cancelled);
            DeviceState before = transitionSource.devices.get(device.id);
            DeviceState after = nextDevices.get(device.id);
            DeviceManifest.WorkingState activeState = device.activeWorkingState(before.modes);
            for (String name : device.numericImpactedEnvironment) {
                after.impactRates.put(name, dynamicEffect(activeState, name).numericRate());
            }
        }

        for (DeviceModel device : devices) {
            ensureNotCancelled(cancelled);
            DeviceState before = transitionSource.devices.get(device.id);
            DeviceState after = nextDevices.get(device.id);
            for (DeviceManifest.API api : device.apis.values()) {
                if (Boolean.TRUE.equals(api.getSignal()) && stateEventMatches(before.modes, after.modes, api)) {
                    after.apiSignals.add(api.getName());
                }
            }
        }

        List<RuleDto> triggeredInBoardOrder = new ArrayList<>();
        for (RuleDto rule : rules) {
            ensureNotCancelled(cancelled);
            if (triggeredRules.contains(rule)) {
                triggeredInBoardOrder.add(rule);
            }
        }
        return new ModelState(nextDevices, nextEnvironment, triggeredInBoardOrder);
    }

    private boolean ruleMatches(RuleDto rule, ModelState state) {
        for (RuleDto.Condition condition : safe(rule.getConditions())) {
            if (!ruleConditionMatches(condition, state)) {
                return false;
            }
        }
        return true;
    }

    private boolean ruleConditionMatches(RuleDto.Condition condition, ModelState state) {
        DeviceModel device = devicesById.get(condition.getDeviceName());
        DeviceState deviceState = state.devices.get(condition.getDeviceName());
        String targetType = normalized(condition.getTargetType());
        if ("api".equals(targetType)) {
            return deviceState.apiSignals.contains(condition.getAttribute());
        }
        String actual;
        if ("state".equals(targetType)) {
            return compareState(device, deviceState, condition.getRelation(), condition.getValue());
        } else if ("mode".equals(targetType)) {
            int modeIndex = device.modeIndex(condition.getAttribute());
            actual = deviceState.modes[modeIndex];
        } else {
            actual = deviceState.locals.get(condition.getAttribute());
            if (actual == null) {
                actual = state.environment.get(condition.getAttribute());
            }
        }
        return compare(actual, condition.getRelation(), condition.getValue());
    }

    private boolean triggerMatches(
            DeviceModel device,
            DeviceState state,
            DeviceManifest.Trigger trigger,
            Map<String, String> environment) {
        if (trigger == null || !hasText(trigger.getAttribute())
                || !hasText(trigger.getRelation()) || !hasText(trigger.getValue())) {
            return false;
        }
        String actual = state.locals.get(trigger.getAttribute());
        int modeIndex = device.findModeIndex(trigger.getAttribute());
        if (modeIndex >= 0) {
            actual = state.modes[modeIndex];
        } else if (actual == null) {
            actual = environment.get(trigger.getAttribute());
        }
        return compare(actual, trigger.getRelation(), trigger.getValue());
    }

    private void validateSpecificationCondition(SpecConditionDto condition) {
        if (condition == null || !hasText(condition.getDeviceId())) {
            throw error("Every specification condition must identify a device.");
        }
        DeviceModel device = devicesById.get(condition.getDeviceId());
        if (device == null) {
            throw error("Specification device '" + condition.getDeviceId() + "' is not in the snapshot.");
        }
        String type = normalized(condition.getTargetType());
        String relation = normalizeRelation(condition.getRelation());
        if (relation == null || !hasText(condition.getValue())) {
            throw error("Specification conditions require a supported relation and value.");
        }
        // Trimmed once, for the same reason as the NuSMV path: every lookup below matches a manifest
        // mode, API, or variable name by exact equality, and admission stores the key trimmed.
        String key = conditionKey(condition);
        if ("state".equals(type)) {
            requireEnumRelation(relation, "state");
            if (!"state".equalsIgnoreCase(key)) {
                throw error("State specification conditions must use key 'state'.");
            }
            stateCandidates(device, condition.getValue(), relation);
            return;
        }
        if ("mode".equals(type)) {
            requireEnumRelation(relation, "mode");
            int modeIndex = device.modeIndex(key);
            for (String value : splitCandidates(condition.getValue(), relation, false)) {
                if (!device.legalModeValues.get(modeIndex).contains(cleanState(value))) {
                    throw error("Illegal value '" + value + "' for mode '" + key + "'.");
                }
            }
            return;
        }
        if ("api".equals(type)) {
            requireEnumRelation(relation, "API");
            DeviceManifest.API api = device.apis.get(key);
            if (api == null || !Boolean.TRUE.equals(api.getSignal())) {
                throw error("API '" + key + "' is not an observable signal.");
            }
            for (String value : splitCandidates(condition.getValue(), relation, false)) {
                if (!isBoolean(value)) {
                    throw error("API conditions require TRUE/FALSE values.");
                }
            }
            return;
        }
        if ("variable".equals(type)) {
            ValueDomain domain = device.localDomains.get(key);
            if (domain == null && device.readableEnvironment.contains(key)) {
                domain = environmentDomains.get(key);
            }
            if (domain == null) {
                throw error("Variable '" + key + "' is not visible to device '" + device.id + "'.");
            }
            domain.validateConditionValue(relation, condition.getValue(),
                    "Specification variable '" + key + "'");
            return;
        }
        throw error("Unsupported fuzz condition type '" + condition.getTargetType() + "'.");
    }

    private boolean groupMatches(List<SpecConditionDto> conditions, ModelState state) {
        for (SpecConditionDto condition : safe(conditions)) {
            if (!specificationConditionMatches(condition, state)) {
                return false;
            }
        }
        return true;
    }

    private static double groupMatchDistance(List<TimedCondition> conditions, ModelState state, int step) {
        if (conditions.isEmpty()) {
            return 0.0;
        }
        // Averaging preserves partial progress across conjuncts; an impossible atom stays infinite.
        // groupMatches remains the exact verdict check.
        double distance = 0.0;
        for (TimedCondition condition : conditions) {
            distance += condition.distance(state, step);
        }
        return distance / conditions.size();
    }

    private static double groupFalsificationDistance(List<TimedCondition> conditions, ModelState state, int step) {
        double distance = Double.POSITIVE_INFINITY;
        for (TimedCondition condition : conditions) {
            distance = Math.min(distance, condition.distance(state, step));
        }
        return distance;
    }

    private boolean specificationConditionMatches(SpecConditionDto condition, ModelState state) {
        DeviceModel device = devicesById.get(condition.getDeviceId());
        DeviceState deviceState = state.devices.get(device.id);
        String type = normalized(condition.getTargetType());
        if ("state".equals(type)) {
            return compareState(device, deviceState, condition.getRelation(), condition.getValue());
        }
        String actual = resolveConditionValue(device, deviceState, state, condition, type);
        return compare(actual, condition.getRelation(), condition.getValue());
    }

    /**
     * A specification condition's key as every lookup must see it.
     *
     * <p>Manifest mode, API, and variable names are matched by exact equality and admission stores the
     * key trimmed, so validation and evaluation have to normalize identically — otherwise a padded key
     * validates and then resolves to no domain, silently changing what the explorer checks.
     */
    private static String conditionKey(SpecConditionDto condition) {
        String key = condition.getKey();
        return key == null ? null : key.trim();
    }

    private String resolveConditionValue(
            DeviceModel device,
            DeviceState deviceState,
            ModelState state,
            SpecConditionDto condition,
            String type) {
        if ("mode".equals(type)) {
            return deviceState.modes[device.modeIndex(conditionKey(condition))];
        }
        if ("api".equals(type)) {
            return deviceState.apiSignals.contains(conditionKey(condition)) ? "TRUE" : "FALSE";
        }
        String value = deviceState.locals.get(conditionKey(condition));
        return value != null ? value : state.environment.get(conditionKey(condition));
    }

    private boolean compareState(DeviceModel device, DeviceState state, String rawRelation, String rawValue) {
        String relation = normalizeRelation(rawRelation);
        List<Map<Integer, String>> candidates = stateCandidates(device, rawValue, relation);
        boolean any = candidates.stream().anyMatch(candidate -> stateTupleMatches(state.modes, candidate));
        return switch (relation) {
            case "=", "in" -> any;
            case "!=", "not in" -> !any;
            default -> false;
        };
    }

    private TraceStateDto toTraceState(ModelState state, int index) {
        List<TraceDeviceDto> traceDevices = new ArrayList<>();
        for (DeviceModel device : devices) {
            DeviceState deviceState = state.devices.get(device.id);
            TraceDeviceDto traceDevice = new TraceDeviceDto();
            traceDevice.setDeviceId(device.id);
            traceDevice.setDeviceLabel(device.label);
            traceDevice.setTemplateName(device.templateName);
            traceDevice.setModelTokenSource(device.modelTokenSource);
            traceDevice.setState(String.join(";", deviceState.modes));
            traceDevice.setMode(String.join(";", device.modes));
            traceDevice.setCompromised(false);
            List<TraceVariableDto> variables = new ArrayList<>();
            for (Map.Entry<String, String> entry : deviceState.locals.entrySet()) {
                variables.add(traceVariable(
                        entry.getKey(), entry.getValue(),
                        device.modelTokenSource));
            }
            traceDevice.setVariables(variables);
            traceDevice.setTrustPrivacy(List.of());
            traceDevice.setPrivacies(List.of());
            traceDevices.add(traceDevice);
        }

        List<TraceVariableDto> environment = new ArrayList<>();
        state.environment.forEach((name, value) ->
                environment.add(traceVariable(
                        name, value,
                        environmentTokenSources.getOrDefault(name, ModelTokenSource.UNKNOWN))));
        Set<RuleDto> triggeredRuleSet = Collections.newSetFromMap(new IdentityHashMap<>());
        triggeredRuleSet.addAll(state.triggeredRules);
        List<TraceTriggeredRuleDto> triggeredRules = new ArrayList<>();
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            RuleDto rule = rules.get(ruleIndex);
            if (!triggeredRuleSet.contains(rule)) continue;
            String label = rule.getRuleString();
            if (label != null && label.isBlank()) label = null;
            triggeredRules.add(TraceTriggeredRuleDto.builder()
                    .ruleIndex(ruleIndex)
                    .ruleId(rule.getId() == null ? null : rule.getId().toString())
                    .ruleLabel(label)
                    .build());
        }
        return TraceStateDto.builder()
                .stateIndex(index)
                .devices(traceDevices)
                .triggeredRules(triggeredRules)
                .compromisedAutomationLinks(List.of())
                .trustPrivacies(List.of())
                .envVariables(environment)
                .globalVariables(List.of())
                .build();
    }

    private static TraceVariableDto traceVariable(
            String name, String value, ModelTokenSource modelTokenSource) {
        TraceVariableDto variable = new TraceVariableDto();
        variable.setName(name);
        variable.setValue(value);
        variable.setModelTokenSource(modelTokenSource);
        return variable;
    }

    private static void mergeEnvironmentTokenSources(
            Map<String, ModelTokenSource> environmentTokenSources,
            DeviceModel device) {
        Set<String> declarations = new LinkedHashSet<>(device.readableEnvironment);
        declarations.addAll(device.impactedEnvironment);
        for (String name : declarations) {
            ModelTokenSource current = environmentTokenSources.get(name);
            ModelTokenSource source = device.modelTokenSource;
            environmentTokenSources.put(name, current == null || current == source
                    ? source : ModelTokenSource.UNKNOWN);
        }
    }

    private static void validateRules(
            List<RuleDto> rules,
            Map<String, DeviceModel> devices,
            Map<String, ValueDomain> environmentDomains) {
        for (RuleDto rule : safe(rules)) {
            if (rule == null || rule.getCommand() == null) {
                throw error("Automation rules require a command.");
            }
            DeviceModel target = devices.get(rule.getCommand().getDeviceName());
            if (target == null) {
                throw error("Rule command device '" + rule.getCommand().getDeviceName() + "' is not in the snapshot.");
            }
            if (!target.apis.containsKey(rule.getCommand().getAction())) {
                throw error("Rule action '" + rule.getCommand().getAction() + "' is not declared by device '" + target.id + "'.");
            }
            if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
                throw error("Automation rules require at least one condition.");
            }
            for (RuleDto.Condition condition : rule.getConditions()) {
                if (condition == null) {
                    throw error("Automation rule conditions cannot contain null.");
                }
                DeviceModel source = devices.get(condition.getDeviceName());
                if (source == null) {
                    throw error("Rule condition device '" + condition.getDeviceName() + "' is not in the snapshot.");
                }
                String type = normalized(condition.getTargetType());
                if ("api".equals(type)) {
                    if (hasText(condition.getRelation()) || hasText(condition.getValue())) {
                        throw error("Rule API signal conditions cannot contain relation or value.");
                    }
                    DeviceManifest.API api = source.apis.get(condition.getAttribute());
                    if (api == null || !Boolean.TRUE.equals(api.getSignal())) {
                        throw error("Rule API condition '" + condition.getAttribute() + "' is not an observable signal.");
                    }
                } else if ("state".equals(type)) {
                    if (!"state".equals(condition.getAttribute())) {
                        throw error("Rule state conditions must use attribute 'state'.");
                    }
                    String relation = normalizeRelation(condition.getRelation());
                    requireEnumRelation(relation, "Rule state");
                    stateCandidates(source, condition.getValue(), relation);
                } else if ("mode".equals(type)) {
                    int modeIndex = source.modeIndex(condition.getAttribute());
                    String relation = normalizeRelation(condition.getRelation());
                    requireEnumRelation(relation, "Rule mode");
                    for (String value : splitCandidates(condition.getValue(), relation, false)) {
                        if (!source.legalModeValues.get(modeIndex).contains(cleanState(value))) {
                            throw error("Illegal rule value '" + value + "' for mode '"
                                    + condition.getAttribute() + "'.");
                        }
                    }
                } else if ("variable".equals(type)) {
                    ValueDomain domain = source.localDomains.get(condition.getAttribute());
                    if (domain == null && source.readableEnvironment.contains(condition.getAttribute())) {
                        domain = environmentDomains.get(condition.getAttribute());
                    }
                    if (domain == null) {
                        throw error("Rule variable '" + condition.getAttribute() + "' is not declared.");
                    }
                    String relation = normalizeRelation(condition.getRelation());
                    if (relation == null) {
                        throw error("Unsupported rule variable relation '" + condition.getRelation() + "'.");
                    }
                    domain.validateConditionValue(relation, condition.getValue(),
                            "Rule variable '" + condition.getAttribute() + "'");
                } else {
                    throw error("Unsupported rule condition type '" + condition.getTargetType() + "'.");
                }
            }
        }
    }

    private static Map<String, List<RuleDto>> groupRulesByTarget(List<RuleDto> rules) {
        Map<String, List<RuleDto>> grouped = new LinkedHashMap<>();
        for (RuleDto rule : rules) {
            grouped.computeIfAbsent(rule.getCommand().getDeviceName(), ignored -> new ArrayList<>())
                    .add(rule);
        }
        Map<String, List<RuleDto>> immutable = new LinkedHashMap<>();
        grouped.forEach((deviceId, deviceRules) -> immutable.put(deviceId, List.copyOf(deviceRules)));
        return Collections.unmodifiableMap(immutable);
    }

    private static DeviceManifest resolveManifest(String templateName, Map<String, DeviceManifest> manifests) {
        if (!hasText(templateName)) {
            return null;
        }
        return manifests.get(templateName.trim().toLowerCase(Locale.ROOT));
    }

    private static String transitionAssignment(Iterable<DeviceManifest.Transition> transitions, String attribute) {
        if (transitions == null) {
            return null;
        }
        for (DeviceManifest.Transition transition : transitions) {
            for (DeviceManifest.Assignment assignment : safe(transition.getAssignments())) {
                if (assignment != null && attribute.equals(assignment.getAttribute()) && hasText(assignment.getValue())) {
                    return cleanLiteral(assignment.getValue());
                }
            }
        }
        return null;
    }

    private static DynamicEffect dynamicEffect(DeviceManifest.WorkingState state, String variableName) {
        if (state == null) {
            return DynamicEffect.NONE;
        }
        for (DeviceManifest.Dynamic dynamic : safe(state.getDynamics())) {
            if (dynamic == null || !variableName.equals(dynamic.getVariableName())) {
                continue;
            }
            if (hasText(dynamic.getValue())) {
                return new DynamicEffect(cleanLiteral(dynamic.getValue()), 0);
            }
            if (hasText(dynamic.getChangeRate())) {
                return new DynamicEffect(null, parseInteger(dynamic.getChangeRate(), "dynamic change rate"));
            }
        }
        return DynamicEffect.NONE;
    }

    private static boolean stateEventMatches(String[] before, String[] after, DeviceManifest.API api) {
        if (!matchesPartialState(before, api.getStartState(), true)) {
            return false;
        }
        boolean changed = false;
        String[] end = splitState(api.getEndState());
        for (int i = 0; i < before.length && i < end.length; i++) {
            String desired = cleanState(end[i]);
            if (desired.isEmpty()) {
                continue;
            }
            if (!desired.equals(after[i])) {
                return false;
            }
            changed |= !before[i].equals(desired);
        }
        return changed;
    }

    private static boolean matchesPartialState(String[] current, String rawState, boolean wildcardUnderscore) {
        if (!hasText(rawState)) {
            return true;
        }
        String[] segments = splitState(rawState);
        for (int i = 0; i < current.length && i < segments.length; i++) {
            String raw = segments[i].trim();
            if (raw.isEmpty() || wildcardUnderscore && "_".equals(raw)) {
                continue;
            }
            if (!current[i].equals(cleanState(raw))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEndForMode(String endState, int modeIndex) {
        return hasText(stateSegment(endState, modeIndex, false));
    }

    private static Set<Integer> affectedModeIndexes(DeviceModel device, DeviceManifest.API api) {
        Set<Integer> affected = new LinkedHashSet<>();
        for (int modeIndex = 0; modeIndex < device.modes.size(); modeIndex++) {
            if (hasEndForMode(api.getEndState(), modeIndex)) {
                affected.add(modeIndex);
            }
        }
        return affected;
    }

    private static String stateSegment(String rawState, int modeIndex, boolean wildcardUnderscore) {
        String[] segments = splitState(rawState);
        if (modeIndex >= segments.length) {
            return "";
        }
        String raw = segments[modeIndex].trim();
        if (raw.isEmpty() || wildcardUnderscore && "_".equals(raw)) {
            return "";
        }
        return cleanState(raw);
    }

    private static String[] splitState(String state) {
        return state == null ? new String[0] : state.split(";", -1);
    }

    private static List<Map<Integer, String>> stateCandidates(DeviceModel device, String rawValue, String relation) {
        List<Map<Integer, String>> resolved = new ArrayList<>();
        for (String candidate : splitCandidates(rawValue, relation, device.modes.size() > 1)) {
            Map<Integer, String> tuple = new LinkedHashMap<>();
            if (candidate.contains(";")) {
                String[] segments = candidate.split(";", -1);
                if (segments.length != device.modes.size()) {
                    throw error("State tuple '" + candidate + "' does not match the device mode count.");
                }
                for (int i = 0; i < segments.length; i++) {
                    if (!segments[i].isBlank()) {
                        String value = cleanState(segments[i]);
                        if (!device.legalModeValues.get(i).contains(value)) {
                            throw error("Illegal state value '" + value + "'.");
                        }
                        tuple.put(i, value);
                    }
                }
            } else if (device.modes.size() == 1) {
                String value = cleanState(candidate);
                if (!device.legalModeValues.get(0).contains(value)) {
                    throw error("Illegal state value '" + value + "'.");
                }
                tuple.put(0, value);
            } else {
                String value = cleanState(candidate);
                int match = -1;
                for (int i = 0; i < device.legalModeValues.size(); i++) {
                    if (device.legalModeValues.get(i).contains(value)) {
                        if (match >= 0) {
                            throw error("State value '" + value + "' is ambiguous across device modes.");
                        }
                        match = i;
                    }
                }
                if (match < 0) {
                    throw error("State value '" + value + "' is not legal for the device.");
                }
                tuple.put(match, value);
            }
            if (tuple.isEmpty()) {
                throw error("State conditions require at least one concrete mode value.");
            }
            resolved.add(tuple);
        }
        return resolved;
    }

    private static List<String> splitCandidates(String value, String relation, boolean preserveSemicolon) {
        if (!hasText(value)) {
            throw error("Condition value is required.");
        }
        if ("in".equals(relation) || "not in".equals(relation)) {
            String regex = preserveSemicolon ? "[,|]" : "[,;|]";
            List<String> candidates = Arrays.stream(value.split(regex))
                    .map(String::trim)
                    .filter(FuzzModel::hasText)
                    .toList();
            if (candidates.isEmpty()) {
                throw error("Condition candidate list cannot be empty.");
            }
            return candidates;
        }
        return List.of(value.trim());
    }

    private static boolean stateTupleMatches(String[] actual, Map<Integer, String> expected) {
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            if (entry.getKey() >= actual.length || !entry.getValue().equals(actual[entry.getKey()])) {
                return false;
            }
        }
        return true;
    }

    private static boolean compare(String actual, String rawRelation, String rawExpected) {
        if (actual == null || rawExpected == null) {
            return false;
        }
        String relation = normalizeRelation(rawRelation);
        if (relation == null) {
            return false;
        }
        List<String> expected = splitCandidates(rawExpected, relation, false).stream()
                .map(FuzzModel::cleanLiteral)
                .toList();
        if ("in".equals(relation)) {
            return expected.stream().anyMatch(value -> literalEquals(actual, value));
        }
        if ("not in".equals(relation)) {
            return expected.stream().noneMatch(value -> literalEquals(actual, value));
        }
        String right = expected.get(0);
        Double leftNumber = number(actual);
        Double rightNumber = number(right);
        int comparison = leftNumber != null && rightNumber != null
                ? Double.compare(leftNumber, rightNumber)
                : cleanLiteral(actual).compareTo(right);
        return switch (relation) {
            case "=" -> literalEquals(actual, right);
            case "!=" -> !literalEquals(actual, right);
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            default -> false;
        };
    }

    private static boolean literalEquals(String left, String right) {
        if (isBoolean(left) && isBoolean(right)) {
            return left.trim().equalsIgnoreCase(right.trim());
        }
        Double leftNumber = number(left);
        Double rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber) == 0;
        }
        return cleanLiteral(left).equals(cleanLiteral(right));
    }

    private static String normalizeRelation(String relation) {
        if (relation == null) {
            return null;
        }
        return switch (relation.trim().toLowerCase(Locale.ROOT)) {
            case "=", "==", "eq" -> "=";
            case "!=", "neq" -> "!=";
            case ">", "gt" -> ">";
            case ">=", "gte" -> ">=";
            case "<", "lt" -> "<";
            case "<=", "lte" -> "<=";
            case "in" -> "in";
            case "not_in", "not in" -> "not in";
            default -> null;
        };
    }

    private static void requireEnumRelation(String relation, String label) {
        if (!"=".equals(relation) && !"!=".equals(relation)
                && !"in".equals(relation) && !"not in".equals(relation)) {
            throw error(label + " conditions support only =, !=, IN, and NOT_IN.");
        }
    }

    private static List<SpecConditionDto> allConditions(SpecificationDto specification) {
        List<SpecConditionDto> result = new ArrayList<>();
        result.addAll(safe(specification.getAConditions()));
        result.addAll(safe(specification.getIfConditions()));
        result.addAll(safe(specification.getThenConditions()));
        return result;
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    // A template 4 path without a successor cannot observe a response violation and supplies no
    // guidance. Keep that absence distinct from a measured finite distance. This value is internal
    // to the engine and never crosses the serialization boundary.
    private static double finiteDistance(double distance) {
        return Double.isFinite(distance) ? Math.max(0.0, distance) : Double.POSITIVE_INFINITY;
    }

    private static String firstText(String first, String second) {
        return hasText(first) ? first : hasText(second) ? second : null;
    }

    private static String cleanState(String value) {
        String cleaned = DeviceSmvDataFactory.cleanStateName(value);
        return cleaned == null ? "" : cleaned;
    }

    private static String cleanLiteral(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace(" ", "").trim();
        if (isBoolean(cleaned)) {
            return cleaned.toUpperCase(Locale.ROOT);
        }
        return cleaned;
    }

    private static boolean isBoolean(String value) {
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "false".equalsIgnoreCase(value.trim()));
    }

    private static Double number(String value) {
        if (value == null) {
            return null;
        }
        try {
            double numeric = Double.parseDouble(value.trim());
            // Integer literals accept -0; comparisons must agree with integer distances and NuSMV.
            return numeric == 0.0 ? 0.0 : numeric;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int parseInteger(String value, String context) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException exception) {
            throw error("Invalid integer " + context + " '" + value + "'.");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static ModelException error(String message) {
        return new ModelException(message);
    }

    record SpecEvaluation(int violationStep, double distance) {
    }

    @FunctionalInterface
    private interface TimedCondition {
        double distance(ModelState state, int step);
    }

    record Simulation(
            List<ModelState> states,
            List<TraceStateDto> traceStates,
            List<FuzzInputEvent> inputEvents,
            Map<Integer, Integer> mutableChoiceBounds,
            boolean cancelled) {

        List<FuzzInputEvent> eventsThrough(int step) {
            return inputEvents.stream().filter(event -> event.step() <= step).toList();
        }
    }

    static final class ModelException extends IllegalArgumentException {
        private ModelException(String message) {
            super(message);
        }
    }

    private static final class DeviceModel {
        private final String id;
        private final String label;
        private final String templateName;
        private final ModelTokenSource modelTokenSource;
        private final List<String> modes;
        private final List<List<String>> legalModeValues;
        private final List<DeviceManifest.WorkingState> workingStates;
        private final List<String[]> workingStateTuples;
        private final Map<String, DeviceManifest.API> apis;
        private final List<DeviceManifest.Transition> transitions;
        private final Map<String, ValueDomain> localDomains;
        private final Set<String> readableEnvironment;
        private final Set<String> impactedEnvironment;
        private final Set<String> numericImpactedEnvironment;
        private final DeviceState initialState;

        private DeviceModel(
                String id,
                String label,
                String templateName,
                ModelTokenSource modelTokenSource,
                List<String> modes,
                List<List<String>> legalModeValues,
                List<DeviceManifest.WorkingState> workingStates,
                List<String[]> workingStateTuples,
                Map<String, DeviceManifest.API> apis,
                List<DeviceManifest.Transition> transitions,
                Map<String, ValueDomain> localDomains,
                Set<String> readableEnvironment,
                Set<String> impactedEnvironment,
                Set<String> numericImpactedEnvironment,
                DeviceState initialState) {
            this.id = id;
            this.label = label;
            this.templateName = templateName;
            this.modelTokenSource = modelTokenSource;
            this.modes = modes;
            this.legalModeValues = legalModeValues;
            this.workingStates = workingStates;
            this.workingStateTuples = workingStateTuples;
            this.apis = apis;
            this.transitions = transitions;
            this.localDomains = localDomains;
            this.readableEnvironment = readableEnvironment;
            this.impactedEnvironment = impactedEnvironment;
            this.numericImpactedEnvironment = numericImpactedEnvironment;
            this.initialState = initialState;
        }

        private static DeviceModel from(
                DeviceVerificationDto device,
                DeviceManifest manifest,
                Map<String, ValueDomain> environmentDomains) {
            String id = device.getVarName().trim();
            List<String> modes = safe(manifest.getModes()).stream()
                    .map(value -> value == null ? "" : value.trim())
                    .toList();
            if (modes.stream().anyMatch(String::isBlank)
                    || new LinkedHashSet<>(modes).size() != modes.size()) {
                throw error("Manifest for device '" + id + "' has invalid or duplicate modes.");
            }

            List<DeviceManifest.WorkingState> workingStates = new ArrayList<>(safe(manifest.getWorkingStates()));
            List<String[]> tuples = new ArrayList<>();
            List<LinkedHashSet<String>> legal = new ArrayList<>();
            for (int i = 0; i < modes.size(); i++) {
                legal.add(new LinkedHashSet<>());
            }
            if (!modes.isEmpty() && workingStates.isEmpty()) {
                throw error("Manifest for device '" + id + "' declares modes without working states.");
            }
            for (DeviceManifest.WorkingState state : workingStates) {
                if (state == null || !hasText(state.getName())) {
                    throw error("Manifest for device '" + id + "' contains an unnamed working state.");
                }
                String[] tuple = parseFullState(state.getName(), modes.size(), "working state", id);
                tuples.add(tuple);
                for (int i = 0; i < tuple.length; i++) {
                    legal.get(i).add(tuple[i]);
                }
            }
            workingStates = List.copyOf(workingStates);
            List<List<String>> legalValues = legal.stream().map(values -> List.copyOf(values)).toList();

            Map<String, DeviceManifest.API> apis = new LinkedHashMap<>();
            Map<String, String> signalRoutes = new LinkedHashMap<>();
            for (DeviceManifest.API api : safe(manifest.getApis())) {
                if (api == null || !hasText(api.getName()) || api.getStartState() == null
                        || api.getSignal() == null) {
                    throw error("Manifest for device '" + id + "' contains an invalid API.");
                }
                if (apis.putIfAbsent(api.getName(), api) != null) {
                    throw error("Manifest for device '" + id + "' contains duplicate API '" + api.getName() + "'.");
                }
                validatePartialState(api.getStartState(), modes.size(), legalValues, true, "API start state", id);
                validatePartialState(api.getEndState(), modes.size(), legalValues, false, "API end state", id);
                if (!hasPossibleApiStateChange(api, legalValues)) {
                    throw error("API '" + api.getName() + "' on device '" + id
                            + "' has no representable state change.");
                }
                if (Boolean.TRUE.equals(api.getSignal())) {
                    String route = canonicalApiState(api.getStartState(), true, modes.size())
                            + "->" + canonicalApiState(api.getEndState(), false, modes.size());
                    String previous = signalRoutes.putIfAbsent(route, api.getName());
                    if (previous != null) {
                        throw error("Signal APIs '" + previous + "' and '" + api.getName()
                                + "' on device '" + id + "' have indistinguishable transitions.");
                    }
                }
            }

            List<DeviceManifest.Transition> transitions = new ArrayList<>(safe(manifest.getTransitions()));
            for (DeviceManifest.Transition transition : transitions) {
                if (transition == null || transition.getTrigger() == null) {
                    throw error("Manifest for device '" + id + "' contains an invalid transition.");
                }
                validatePartialState(transition.getStartState(), modes.size(), legalValues, true, "transition start state", id);
                validatePartialState(transition.getEndState(), modes.size(), legalValues, false, "transition end state", id);
                DeviceManifest.Trigger trigger = transition.getTrigger();
                String triggerRelation = normalizeRelation(trigger.getRelation());
                if (!hasText(trigger.getAttribute()) || triggerRelation == null
                        || "in".equals(triggerRelation) || "not in".equals(triggerRelation)
                        || !hasText(trigger.getValue())) {
                    throw error("Manifest transition for device '" + id + "' has an incomplete trigger.");
                }
                int effectCount = concreteModeCount(transition.getEndState(), modes.size())
                        + safe(transition.getAssignments()).size();
                if (effectCount != 1) {
                    throw error("Transition on device '" + id
                            + "' must contain exactly one modeled effect.");
                }
            }
            transitions = List.copyOf(transitions);
            validateSignalRouteIsolation(id, modes.size(), apis.values(), transitions);

            Map<String, ValueDomain> locals = new LinkedHashMap<>();
            Set<String> readableEnvironment = new LinkedHashSet<>();
            Map<String, ValueDomain> ownEnvironmentDomains = new LinkedHashMap<>();
            for (DeviceManifest.InternalVariable variable : safe(manifest.getInternalVariables())) {
                if (variable == null || !hasText(variable.getName()) || variable.getIsInside() == null) {
                    throw error("Manifest for device '" + id + "' contains an invalid internal variable.");
                }
                ValueDomain domain = ValueDomain.from(
                        variable.getValues(),
                        variable.getLowerBound(),
                        variable.getUpperBound(),
                        variable.getNaturalChangeRate(),
                        !Boolean.TRUE.equals(variable.getIsInside()),
                        "variable '" + variable.getName() + "'");
                if (Boolean.TRUE.equals(variable.getIsInside())) {
                    if (locals.putIfAbsent(variable.getName(), domain) != null) {
                        throw error("Duplicate local variable '" + variable.getName() + "' on device '" + id + "'.");
                    }
                } else {
                    mergeEnvironmentDomain(environmentDomains, variable.getName(), domain);
                    readableEnvironment.add(variable.getName());
                    putOwnEnvironmentDomain(ownEnvironmentDomains, variable.getName(), domain, id);
                }
            }

            Set<String> impactedEnvironment = new LinkedHashSet<>();
            Set<String> numericImpactedEnvironment = new LinkedHashSet<>();
            for (String impacted : safe(manifest.getImpactedVariables())) {
                if (!hasText(impacted) || !impactedEnvironment.add(impacted)) {
                    throw error("Device '" + id + "' has an empty or duplicate impacted variable.");
                }
                if (locals.containsKey(impacted)) {
                    throw error("Device '" + id + "' uses local variable '" + impacted
                            + "' as an impacted environment variable.");
                }
                ValueDomain domain = ownEnvironmentDomains.get(impacted);
                if (domain == null) {
                    throw error("Device '" + id + "' impacts environment variable '" + impacted
                            + "' without declaring its domain.");
                }
                mergeEnvironmentDomain(environmentDomains, impacted, domain);
                if (domain.isNumeric()) {
                    numericImpactedEnvironment.add(impacted);
                }
            }

            String initialRaw = hasText(device.getState()) ? device.getState() : manifest.getInitState();
            String[] initialModes = parseFullState(initialRaw, modes.size(), "initial state", id);
            for (int i = 0; i < initialModes.length; i++) {
                if (!legalValues.get(i).contains(initialModes[i])) {
                    throw error("Initial state '" + initialModes[i] + "' is illegal for mode '" + modes.get(i) + "'.");
                }
            }
            Map<String, VariableStateDto> submittedVariables = new LinkedHashMap<>();
            for (VariableStateDto value : safe(device.getVariables())) {
                if (value == null || !hasText(value.getName())) {
                    throw error("Device '" + id + "' contains an invalid local variable value.");
                }
                if (submittedVariables.putIfAbsent(value.getName(), value) != null) {
                    throw error("Device '" + id + "' contains duplicate local variable value '"
                            + value.getName() + "'.");
                }
                if (!locals.containsKey(value.getName())) {
                    throw error("Device '" + id + "' contains unknown local variable value '"
                            + value.getName() + "'.");
                }
            }
            LinkedHashMap<String, String> initialLocals = new LinkedHashMap<>();
            for (DeviceManifest.InternalVariable variable : safe(manifest.getInternalVariables())) {
                if (!Boolean.TRUE.equals(variable.getIsInside())) {
                    continue;
                }
                String name = variable.getName();
                VariableStateDto submitted = submittedVariables.get(name);
                String initialValue = submitted == null
                        ? DeviceManifestModes.localInitialValue(manifest, variable, initialRaw)
                        : submitted.getValue();
                if (!hasText(initialValue)) {
                    throw error("Local variable '" + name + "' on device '" + id
                            + "' has an invalid initial value.");
                }
                String value = cleanLiteral(initialValue);
                locals.get(name).requireValue(value, "local variable '" + name + "'");
                initialLocals.put(name, value);
            }

            validateDynamics(id, workingStates, locals, ownEnvironmentDomains, impactedEnvironment);
            validateTransitionTriggers(
                    id, transitions, modes, legalValues, locals, readableEnvironment, environmentDomains);
            validateTransitionAssignments(
                    id, transitions, locals, ownEnvironmentDomains, impactedEnvironment, readableEnvironment);
            LinkedHashMap<String, Integer> initialImpactRates = new LinkedHashMap<>();
            numericImpactedEnvironment.forEach(name -> initialImpactRates.put(name, 0));
            return new DeviceModel(
                    id,
                    hasText(device.getDeviceLabel()) ? device.getDeviceLabel() : id,
                    device.getTemplateName(),
                    device.getModelTokenSource() != null
                            ? device.getModelTokenSource() : ModelTokenSource.UNKNOWN,
                    List.copyOf(modes),
                    legalValues,
                    workingStates,
                    List.copyOf(tuples),
                    Collections.unmodifiableMap(apis),
                    transitions,
                    Collections.unmodifiableMap(locals),
                    Collections.unmodifiableSet(readableEnvironment),
                    Collections.unmodifiableSet(impactedEnvironment),
                    Collections.unmodifiableSet(numericImpactedEnvironment),
                    new DeviceState(initialModes, initialLocals, new LinkedHashSet<>(), initialImpactRates));
        }

        private int modeIndex(String key) {
            int index = findModeIndex(key);
            if (index >= 0) {
                return index;
            }
            throw error("Mode '" + key + "' is not declared by device '" + id + "'.");
        }

        private int findModeIndex(String key) {
            if (!hasText(key)) {
                return -1;
            }
            String cleanKey = cleanState(key);
            for (int i = 0; i < modes.size(); i++) {
                if (modes.get(i).equals(key.trim()) || modes.get(i).equalsIgnoreCase(key.trim())
                        || modes.get(i).equals(cleanKey)) {
                    return i;
                }
            }
            return -1;
        }

        private DeviceManifest.WorkingState activeWorkingState(String[] current) {
            for (int i = 0; i < workingStateTuples.size(); i++) {
                if (Arrays.equals(workingStateTuples.get(i), current)) {
                    return workingStates.get(i);
                }
            }
            return null;
        }
    }

    private static void validateDynamics(
            String deviceId,
            List<DeviceManifest.WorkingState> workingStates,
            Map<String, ValueDomain> locals,
            Map<String, ValueDomain> ownEnvironment,
            Set<String> impactedEnvironment) {
        for (DeviceManifest.WorkingState state : workingStates) {
            Set<String> seen = new LinkedHashSet<>();
            for (DeviceManifest.Dynamic dynamic : safe(state.getDynamics())) {
                if (dynamic == null || !hasText(dynamic.getVariableName())) {
                    throw error("Working-state dynamics on device '" + deviceId + "' are invalid.");
                }
                if (!seen.add(dynamic.getVariableName())) {
                    throw error("Working state '" + state.getName() + "' on device '" + deviceId
                            + "' defines duplicate dynamics for '" + dynamic.getVariableName() + "'.");
                }
                ValueDomain domain = locals.get(dynamic.getVariableName());
                if (domain == null && impactedEnvironment.contains(dynamic.getVariableName())) {
                    domain = ownEnvironment.get(dynamic.getVariableName());
                }
                if (domain == null) {
                    throw error("Dynamic variable '" + dynamic.getVariableName() + "' is not declared.");
                }
                boolean hasValue = hasText(dynamic.getValue());
                boolean hasRate = hasText(dynamic.getChangeRate());
                if (hasValue == hasRate) {
                    throw error("Dynamics must declare exactly one of value or changeRate.");
                }
                if (domain.isNumeric() && !hasRate) {
                    throw error("Numeric dynamic variable '" + dynamic.getVariableName()
                            + "' must use ChangeRate.");
                }
                if (!domain.isNumeric() && !hasValue) {
                    throw error("Enum dynamic variable '" + dynamic.getVariableName()
                            + "' must use Value.");
                }
                if (hasValue) {
                    domain.requireValue(cleanLiteral(dynamic.getValue()), "dynamic variable '" + dynamic.getVariableName() + "'");
                } else {
                    parseInteger(dynamic.getChangeRate(), "for dynamic variable '" + dynamic.getVariableName() + "'");
                }
            }
        }
    }

    private static void validateTransitionAssignments(
            String deviceId,
            List<DeviceManifest.Transition> transitions,
            Map<String, ValueDomain> locals,
            Map<String, ValueDomain> ownEnvironment,
            Set<String> impactedEnvironment,
            Set<String> readableEnvironment) {
        for (DeviceManifest.Transition transition : transitions) {
            for (DeviceManifest.Assignment assignment : safe(transition.getAssignments())) {
                if (assignment == null || !hasText(assignment.getAttribute()) || !hasText(assignment.getValue())) {
                    throw error("Transition assignment on device '" + deviceId + "' is invalid.");
                }
                ValueDomain domain = locals.get(assignment.getAttribute());
                if (domain == null && (impactedEnvironment.contains(assignment.getAttribute())
                        || readableEnvironment.contains(assignment.getAttribute()))) {
                    domain = ownEnvironment.get(assignment.getAttribute());
                }
                if (domain == null) {
                    throw error("Transition assignment variable '" + assignment.getAttribute() + "' is not declared.");
                }
                domain.requireValue(cleanLiteral(assignment.getValue()), "transition assignment '" + assignment.getAttribute() + "'");
            }
        }
    }

    private static void validateTransitionTriggers(
            String deviceId,
            List<DeviceManifest.Transition> transitions,
            List<String> modes,
            List<List<String>> legalModeValues,
            Map<String, ValueDomain> locals,
            Set<String> readableEnvironment,
            Map<String, ValueDomain> environment) {
        for (DeviceManifest.Transition transition : transitions) {
            String attribute = transition.getTrigger().getAttribute();
            int modeIndex = findModeIndex(modes, attribute);
            String relation = normalizeRelation(transition.getTrigger().getRelation());
            if (modeIndex >= 0) {
                requireEnumRelation(relation, "Transition mode trigger");
                String value = cleanState(transition.getTrigger().getValue());
                if (!legalModeValues.get(modeIndex).contains(value)) {
                    throw error("Transition trigger value '" + transition.getTrigger().getValue()
                            + "' is not legal for mode '" + attribute + "'.");
                }
                continue;
            }
            ValueDomain domain = locals.get(attribute);
            if (domain == null && readableEnvironment.contains(attribute)) {
                domain = environment.get(attribute);
            }
            if (domain == null) {
                throw error("Transition trigger variable '" + attribute
                        + "' is not readable by device '" + deviceId + "'.");
            }
            domain.validateConditionValue(relation, transition.getTrigger().getValue(),
                    "Transition trigger '" + attribute + "'");
        }
    }

    private static int findModeIndex(List<String> modes, String key) {
        if (!hasText(key)) {
            return -1;
        }
        String cleanKey = cleanState(key);
        for (int modeIndex = 0; modeIndex < modes.size(); modeIndex++) {
            String mode = modes.get(modeIndex);
            if (mode.equals(key.trim()) || mode.equalsIgnoreCase(key.trim()) || mode.equals(cleanKey)) {
                return modeIndex;
            }
        }
        return -1;
    }

    private static void mergeEnvironmentDomain(
            Map<String, ValueDomain> environmentDomains,
            String name,
            ValueDomain domain) {
        ValueDomain existing = environmentDomains.putIfAbsent(name, domain);
        if (existing != null && !existing.sameDomain(domain)) {
            throw error("Conflicting environment domains for '" + name + "'.");
        }
    }

    private static void putOwnEnvironmentDomain(
            Map<String, ValueDomain> domains,
            String name,
            ValueDomain domain,
            String deviceId) {
        ValueDomain existing = domains.putIfAbsent(name, domain);
        if (existing != null && !existing.sameDomain(domain)) {
            throw error("Device '" + deviceId + "' declares conflicting environment domains for '"
                    + name + "'.");
        }
    }

    private static String[] parseFullState(String raw, int modeCount, String context, String deviceId) {
        if (modeCount == 0) {
            if (hasText(raw)) {
                throw error("Device '" + deviceId + "' has a " + context + " but no modes.");
            }
            return new String[0];
        }
        if (!hasText(raw)) {
            throw error("Device '" + deviceId + "' is missing its " + context + ".");
        }
        String[] segments = raw.split(";", -1);
        if (segments.length != modeCount) {
            throw error("Device '" + deviceId + "' " + context + " has " + segments.length
                    + " segments for " + modeCount + " modes.");
        }
        String[] result = new String[segments.length];
        for (int i = 0; i < segments.length; i++) {
            result[i] = cleanState(segments[i]);
            if (result[i].isEmpty()) {
                throw error("Device '" + deviceId + "' " + context + " contains an empty mode value.");
            }
        }
        return result;
    }

    private static void validatePartialState(
            String raw,
            int modeCount,
            List<List<String>> legalValues,
            boolean allowWildcard,
            String context,
            String deviceId) {
        if (!hasText(raw)) {
            return;
        }
        String[] segments = raw.split(";", -1);
        if (segments.length > modeCount) {
            throw error("Device '" + deviceId + "' " + context + " has too many mode segments.");
        }
        for (int i = 0; i < segments.length; i++) {
            String rawSegment = segments[i].trim();
            if (rawSegment.isEmpty() || allowWildcard && "_".equals(rawSegment)) {
                continue;
            }
            String value = cleanState(rawSegment);
            if (i >= legalValues.size() || !legalValues.get(i).contains(value)) {
                throw error("Device '" + deviceId + "' " + context + " contains illegal value '" + value + "'.");
            }
        }
    }

    private static boolean hasPossibleApiStateChange(
            DeviceManifest.API api,
            List<List<String>> legalModeValues) {
        String[] starts = splitState(api.getStartState());
        String[] ends = splitState(api.getEndState());
        for (int modeIndex = 0; modeIndex < legalModeValues.size() && modeIndex < ends.length; modeIndex++) {
            String end = cleanState(ends[modeIndex]);
            if (end.isEmpty()) {
                continue;
            }
            String rawStart = modeIndex < starts.length ? starts[modeIndex].trim() : "";
            if (rawStart.isEmpty() || "_".equals(rawStart)) {
                if (legalModeValues.get(modeIndex).stream().anyMatch(value -> !value.equals(end))) {
                    return true;
                }
            } else if (!cleanState(rawStart).equals(end)) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalApiState(String state, boolean startState, int modeCount) {
        String[] segments = splitState(state);
        List<String> values = new ArrayList<>(modeCount);
        for (int modeIndex = 0; modeIndex < modeCount; modeIndex++) {
            String raw = modeIndex < segments.length ? segments[modeIndex].trim() : "";
            values.add(startState && "_".equals(raw) ? "" : cleanState(raw).toLowerCase(Locale.ROOT));
        }
        return String.join(";", values);
    }

    private static void validateSignalRouteIsolation(
            String deviceId,
            int modeCount,
            java.util.Collection<DeviceManifest.API> apis,
            List<DeviceManifest.Transition> transitions) {
        for (DeviceManifest.API signal : apis) {
            if (!Boolean.TRUE.equals(signal.getSignal())) {
                continue;
            }
            for (DeviceManifest.API other : apis) {
                if (signal == other) {
                    continue;
                }
                if (signalMayObserveChange(
                        signal.getStartState(), signal.getEndState(),
                        other.getStartState(), other.getEndState(), modeCount)) {
                    throw error("Observable API '" + signal.getName() + "' on device '" + deviceId
                            + "' overlaps API '" + other.getName() + "'.");
                }
            }
            for (DeviceManifest.Transition transition : transitions) {
                if (signalMayObserveChange(
                        signal.getStartState(), signal.getEndState(),
                        transition.getStartState(), transition.getEndState(), modeCount)) {
                    throw error("Observable API '" + signal.getName() + "' on device '" + deviceId
                            + "' overlaps an autonomous transition.");
                }
            }
        }
    }

    private static boolean signalMayObserveChange(
            String signalStartState,
            String signalEndState,
            String changeStartState,
            String changeEndState,
            int modeCount) {
        List<String> signalStarts = normalizedStateParts(signalStartState, modeCount);
        List<String> signalEnds = normalizedStateParts(signalEndState, modeCount);
        List<String> changeStarts = normalizedStateParts(changeStartState, modeCount);
        List<String> changeEnds = normalizedStateParts(changeEndState, modeCount);
        boolean observedChangePossible = false;
        for (int modeIndex = 0; modeIndex < modeCount; modeIndex++) {
            String signalStart = signalStarts.get(modeIndex);
            String changeStart = changeStarts.get(modeIndex);
            if (!signalStart.isEmpty() && !changeStart.isEmpty() && !signalStart.equals(changeStart)) {
                return false;
            }

            String signalEnd = signalEnds.get(modeIndex);
            if (signalEnd.isEmpty()) {
                continue;
            }
            String changeEnd = changeEnds.get(modeIndex);
            String fixedStart = !signalStart.isEmpty() ? signalStart : changeStart;
            if (!changeEnd.isEmpty()) {
                if (!signalEnd.equals(changeEnd)) {
                    return false;
                }
                if (fixedStart.isEmpty() || !fixedStart.equals(signalEnd)) {
                    observedChangePossible = true;
                }
            } else if (!fixedStart.isEmpty() && !fixedStart.equals(signalEnd)) {
                return false;
            }
        }
        return observedChangePossible;
    }

    private static List<String> normalizedStateParts(String state, int modeCount) {
        String[] parts = splitState(state);
        List<String> normalized = new ArrayList<>(modeCount);
        for (int modeIndex = 0; modeIndex < modeCount; modeIndex++) {
            String raw = modeIndex < parts.length ? parts[modeIndex].trim() : "";
            normalized.add("_".equals(raw) ? "" : cleanState(raw).toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private static int concreteModeCount(String state, int modeCount) {
        int count = 0;
        String[] segments = splitState(state);
        for (int modeIndex = 0; modeIndex < modeCount && modeIndex < segments.length; modeIndex++) {
            if (hasText(cleanState(segments[modeIndex]))) {
                count++;
            }
        }
        return count;
    }

    private static final class ValueDomain {
        private final List<String> values;
        private final Integer lower;
        private final Integer upper;
        private final int lowerRate;
        private final int upperRate;
        /** The declared interval, so candidate generation matches the formal generator exactly. */
        private final NaturalChangeRateParser.RateRange rateRange;

        private ValueDomain(
                List<String> values,
                Integer lower,
                Integer upper,
                int lowerRate,
                int upperRate) {
            this.values = values;
            this.lower = lower;
            this.upper = upper;
            this.lowerRate = lowerRate;
            this.upperRate = upperRate;
            this.rateRange = new NaturalChangeRateParser.RateRange(lowerRate, upperRate);
        }

        private static ValueDomain from(
                List<String> rawValues,
                Integer lower,
                Integer upper,
                String naturalChangeRate,
                boolean requireNaturalChangeRate,
                String context) {
            boolean discrete = rawValues != null && !rawValues.isEmpty();
            boolean numeric = lower != null && upper != null;
            if (discrete == numeric || lower != null != (upper != null)) {
                throw error(context + " must declare either values or lower/upper bounds.");
            }
            boolean hasRateDeclaration = naturalChangeRate != null;
            if (discrete && hasRateDeclaration) {
                throw error(context + " declares NaturalChangeRate, but only numeric ranges support it.");
            }
            if (numeric && requireNaturalChangeRate && !hasRateDeclaration) {
                throw error(context + " is a shared numeric environment variable and must explicitly define "
                        + "NaturalChangeRate ('[-1, 1]' for the MEDIC baseline disturbance, or '0' "
                        + "for no natural change).");
            }
            int[] rates = parseRate(naturalChangeRate, context);
            if (discrete) {
                List<String> values = new ArrayList<>(rawValues.size());
                Set<String> seen = new LinkedHashSet<>();
                for (String rawValue : rawValues) {
                    String value = cleanLiteral(rawValue);
                    if (!hasText(value) || !seen.add(value)) {
                        throw error(context + " has empty or duplicate normalized enum values.");
                    }
                    values.add(value);
                }
                return new ValueDomain(List.copyOf(values), null, null, rates[0], rates[1]);
            }
            if (lower > upper) {
                throw error(context + " has lowerBound greater than upperBound.");
            }
            return new ValueDomain(List.of(), lower, upper, rates[0], rates[1]);
        }

        private boolean isNumeric() {
            return lower != null;
        }

        private void requireValue(String value, String context) {
            if (!hasText(value)) {
                throw error(context + " requires a value.");
            }
            if (!isNumeric()) {
                if (values.stream().noneMatch(candidate -> literalEquals(candidate, value))) {
                    throw error(context + " value '" + value + "' is outside its declared domain.");
                }
                return;
            }
            int numeric = parseInteger(value, "for " + context);
            if (numeric < lower || numeric > upper) {
                throw error(context + " value " + numeric + " is outside [" + lower + ", " + upper + "].");
            }
        }

        private void validateConditionValue(String relation, String rawValue, String context) {
            List<String> candidates = splitCandidates(rawValue, relation, false);
            if (!isNumeric()) {
                requireEnumRelation(relation, context);
                for (String candidate : candidates) {
                    requireValue(cleanLiteral(candidate), context);
                }
                return;
            }
            for (String candidate : candidates) {
                requireValue(candidate.trim(), context);
            }
        }

        private List<String> nextCandidates(
                String current,
                int dynamicRate,
                String forcedValue,
                boolean freeDiscreteChoice) {
            if (hasText(forcedValue)) {
                String cleaned = cleanLiteral(forcedValue);
                requireValue(cleaned, "transition value");
                return List.of(cleaned);
            }
            if (!isNumeric()) {
                return freeDiscreteChoice ? values : List.of(cleanLiteral(current));
            }
            int currentValue = parseInteger(current, "for numeric state");
            // Same admissible-delta set the formal generator emits, so a finding and a
            // counterexample describe the same transition relation.
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            for (int delta : rateRange.admissibleDeltas()) {
                candidates.add(Long.toString(clamp((long) currentValue + dynamicRate + delta)));
            }
            return List.copyOf(candidates);
        }

        private List<String> nextEnvironmentNumericCandidates(
                String current,
                long impactRate,
                String forcedValue,
                boolean hasNumericImpact) {
            if (hasText(forcedValue)) {
                String cleaned = cleanLiteral(forcedValue);
                requireValue(cleaned, "environment transition value");
                return List.of(cleaned);
            }
            if (!isNumeric()) {
                throw error("Numeric environment transition requires a bounded numeric domain.");
            }

            long currentValue = parseInteger(current, "for numeric environment state");
            long effectiveImpactRate = hasNumericImpact ? impactRate : 0L;
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            for (int delta : rateRange.admissibleDeltas()) {
                candidates.add(Long.toString(clamp(currentValue + delta + effectiveImpactRate)));
            }
            return List.copyOf(candidates);
        }

        private List<String> paperInitialValues() {
            return values;
        }

        private long clamp(long value) {
            return Math.max(lower, Math.min(upper, value));
        }

        private boolean sameDomain(ValueDomain other) {
            return values.equals(other.values)
                    && java.util.Objects.equals(lower, other.lower)
                    && java.util.Objects.equals(upper, other.upper)
                    && lowerRate == other.lowerRate
                    && upperRate == other.upperRate;
        }

        private static int[] parseRate(String raw, String context) {
            try {
                NaturalChangeRateParser.RateRange range = NaturalChangeRateParser.parse(raw);
                return new int[]{range.lower(), range.upper()};
            } catch (NaturalChangeRateParser.ParseException exception) {
                throw error(context + " has invalid NaturalChangeRate '" + raw + "'.");
            }
        }
    }

    private static final class DeviceState {
        private final String[] modes;
        private final LinkedHashMap<String, String> locals;
        private final LinkedHashSet<String> apiSignals;
        private final LinkedHashMap<String, Integer> impactRates;

        private DeviceState(
                String[] modes,
                Map<String, String> locals,
                Set<String> apiSignals,
                Map<String, Integer> impactRates) {
            this.modes = modes.clone();
            this.locals = new LinkedHashMap<>(locals);
            this.apiSignals = new LinkedHashSet<>(apiSignals);
            this.impactRates = new LinkedHashMap<>(impactRates);
        }

        private DeviceState copy() {
            return new DeviceState(modes, locals, apiSignals, impactRates);
        }
    }

    private static final class ModelState {
        private final LinkedHashMap<String, DeviceState> devices;
        private final LinkedHashMap<String, String> environment;
        private final List<RuleDto> triggeredRules;

        private ModelState(
                Map<String, DeviceState> devices,
                Map<String, String> environment,
                List<RuleDto> triggeredRules) {
            this.devices = new LinkedHashMap<>();
            devices.forEach((id, state) -> this.devices.put(id, state.copy()));
            this.environment = new LinkedHashMap<>(environment);
            this.triggeredRules = List.copyOf(triggeredRules);
        }

        private ModelState copy() {
            return new ModelState(devices, environment, triggeredRules);
        }
    }

    private static final class GeneCursor {
        private final long[] genes;
        private final LinkedHashMap<Integer, Integer> mutableChoiceBounds = new LinkedHashMap<>();
        private int index;

        private GeneCursor(long[] genes) {
            this.genes = genes == null || genes.length == 0 ? new long[]{0L} : genes;
        }

        private int choose(int bound) {
            if (bound < 1) {
                throw error("A nondeterministic choice has no candidates.");
            }
            int geneIndex = index++ % genes.length;
            if (bound > 1) {
                mutableChoiceBounds.put(geneIndex, bound);
            }
            long gene = genes[geneIndex];
            return (int) Math.floorMod(gene, (long) bound);
        }

        private Map<Integer, Integer> mutableChoiceBounds() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(mutableChoiceBounds));
        }
    }

    private static void ensureNotCancelled(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw SimulationCancelledException.INSTANCE;
        }
    }

    private static final class SimulationCancelledException extends RuntimeException {
        private static final SimulationCancelledException INSTANCE = new SimulationCancelledException();

        private SimulationCancelledException() {
            super(null, null, false, false);
        }
    }

    private record DynamicEffect(String discreteValue, int numericRate) {
        private static final DynamicEffect NONE = new DynamicEffect(null, 0);
    }

    private record PaperStep(ModelState inputState, PaperStepOverrides overrides) {
    }

    private record PaperRuleEffect(
            String deviceId, Set<Integer> affectedModes, PaperCondition guard) {
    }

    private record PaperStepOverrides(
            Map<String, Integer> eventRates,
            Map<String, String> eventBaseValues,
            boolean paperMode) {

        private static final PaperStepOverrides NONE = new PaperStepOverrides(
                Map.of(), Map.of(), false);

        private PaperStepOverrides {
            eventRates = Map.copyOf(eventRates);
            eventBaseValues = Map.copyOf(eventBaseValues);
        }
    }

    private record RuleGuard(Set<Integer> affectedModes, boolean rawGuard) {
    }
}
