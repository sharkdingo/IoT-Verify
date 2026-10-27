package cn.edu.nju.Iot_Verify.component.fuzz.paper;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperSeedKernelTest {

    @Test
    void fixedKernelSeedReproducesInitialAndNextPopulations() {
        PaperEventDomainSnapshot domain = domain();
        PaperSeedKernel firstKernel = new PaperSeedKernel(domain, 42L);
        PaperSeedKernel secondKernel = new PaperSeedKernel(domain, 42L);

        List<PaperSeed> firstPopulation = firstKernel.initialPopulation(40);
        List<PaperSeed> secondPopulation = secondKernel.initialPopulation(40);
        assertEquals(firstPopulation, secondPopulation);
        assertEquals(
                firstKernel.nextPopulation(firstPopulation.get(0), 40),
                secondKernel.nextPopulation(secondPopulation.get(0), 40));

        for (PaperSeed seed : firstPopulation) {
            assertTrue(seed.initialOverrides().isEmpty());
            assertTrue(seed.events().size() <= PaperSeedKernel.MAX_INITIAL_SEED_EVENTS);
            for (PaperEvent event : seed.events()) {
                assertTrue(event.position() >= 1 && event.position() <= domain.traceLength());
                if (event.type() == PaperEvent.Type.DEVICE) {
                    assertEquals(1, event.position());
                    assertTrue(domain.device(event.name()).legalValues().contains(event.value()));
                } else {
                    assertTrue(domain.environment(event.name()).eventValues().contains(event.value()));
                }
            }
        }
    }

    @Test
    void strategyBoundaryImplementsNinetyFivePercentMutation() {
        assertEquals(PaperSeedKernel.NextSeedStrategy.MUTATION,
                PaperSeedKernel.strategyForRoll(0));
        assertEquals(PaperSeedKernel.NextSeedStrategy.MUTATION,
                PaperSeedKernel.strategyForRoll(94));
        assertEquals(PaperSeedKernel.NextSeedStrategy.RANDOM,
                PaperSeedKernel.strategyForRoll(95));
        assertEquals(PaperSeedKernel.NextSeedStrategy.RANDOM,
                PaperSeedKernel.strategyForRoll(99));
        assertThrows(IllegalArgumentException.class, () -> PaperSeedKernel.strategyForRoll(-1));
        assertThrows(IllegalArgumentException.class, () -> PaperSeedKernel.strategyForRoll(100));
    }

    @Test
    void environmentOperationsAddDeleteAndModifyValueAndPosition() {
        PaperSeedKernel kernel = new PaperSeedKernel(domain(), 73L);
        PaperEvent device = new PaperEvent(PaperEvent.Type.DEVICE, "thermostat", "off", 1);
        PaperEvent environment = new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:-1", 2);
        PaperSeed source = new PaperSeed(991L, List.of(device, environment));

        PaperSeed added = kernel.addEnvironmentEvent(source);
        assertEquals(source.events().size() + 1, added.events().size());
        assertEquals(source.initializationNonce(), added.initializationNonce());

        PaperSeed deleted = kernel.deleteEnvironmentEvent(source);
        assertEquals(List.of(device), deleted.events());
        assertEquals(source.initializationNonce(), deleted.initializationNonce());

        PaperSeed modified = kernel.modifyEnvironmentEvent(source);
        PaperEvent changed = modified.events().stream()
                .filter(event -> event.type() == PaperEvent.Type.ENVIRONMENT)
                .findFirst()
                .orElseThrow();
        assertNotEquals(environment.value(), changed.value());
        assertNotEquals(environment.position(), changed.position());
        assertTrue(domain().environment("temperature").eventValues().contains(changed.value()));
        assertEquals(source.initializationNonce(), modified.initializationNonce());
    }

    @Test
    void environmentAdditionGeneratesItsPositionAcrossAllFreeSlots() {
        PaperEvent selected = new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:-1", 2);
        PaperSeed source = new PaperSeed(991L, List.of(selected));
        java.util.Set<Integer> addedPositions = new java.util.HashSet<>();

        for (long seed = 0; seed < 100; seed++) {
            PaperSeed added = new PaperSeedKernel(domain(), seed).addEnvironmentEvent(source);
            added.events().stream()
                    .filter(event -> !event.equals(selected))
                    .findFirst()
                    .ifPresent(event -> addedPositions.add(event.position()));
        }

        assertTrue(addedPositions.size() > 1);
        assertTrue(addedPositions.stream().anyMatch(position -> position != selected.position()));
    }

    @Test
    void deviceModificationUsesOnlyLegalModesAtPositionOne() {
        PaperSeedKernel kernel = new PaperSeedKernel(domain(), 19L);
        PaperSeed source = new PaperSeed(55L, List.of(new PaperEvent(
                PaperEvent.Type.DEVICE, "thermostat", "off", 1)));

        PaperSeed modified = kernel.modifyDeviceEvent(source);

        assertEquals(55L, modified.initializationNonce());
        assertEquals(List.of(new PaperEvent(
                PaperEvent.Type.DEVICE, "thermostat", "heat", 1)), modified.events());
    }

    @Test
    void positionFirstSelectionRetriesEmptyPositionsAndMutatesOnlyTheSelectedEvent() {
        PaperSeedKernel kernel = new PaperSeedKernel(domain(), 29L);
        PaperEvent atTwo = new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:-1", 2);
        PaperEvent atFive = new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:1", 5);
        PaperSeed source = new PaperSeed(77L, List.of(atTwo, atFive));

        PaperSeedKernel.SelectedEvent retried = kernel.selectEventPositionFirst(source, 3);

        assertNotEquals(3, retried.position());
        assertEquals(retried.position(), retried.event().position());
        assertTrue(List.of(atTwo, atFive).contains(retried.event()));

        PaperSeedKernel.SelectedEvent selected = kernel.selectEventPositionFirst(source, 2);
        PaperSeed deleted = kernel.mutateSelectedEvent(
                source, selected, PaperSeedKernel.MutationOperation.DELETION);

        assertEquals(2, selected.position());
        assertEquals(atTwo, selected.event());
        assertEquals(List.of(atFive), deleted.events());
    }

    @Test
    void environmentModificationRequiresBothValueAndPositionToBeMutable() {
        PaperEventDomainSnapshot onePosition = new PaperEventDomainSnapshot(
                1,
                List.of(),
                List.of(new PaperEnvironmentDomain(
                        "temperature",
                        "environment",
                        "temperature",
                        List.of("18", "21"),
                        List.of("-1", "+1"))));
        PaperSeed onePositionSeed = new PaperSeed(1L, List.of(new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:-1", 1)));

        assertEquals(onePositionSeed,
                new PaperSeedKernel(onePosition, 7L)
                        .modifyEnvironmentEvent(onePositionSeed));

        PaperEventDomainSnapshot oneValue = new PaperEventDomainSnapshot(
                3,
                List.of(),
                List.of(new PaperEnvironmentDomain(
                        "temperature",
                        "environment",
                        "temperature",
                        List.of("18"),
                        List.of("0"))));
        PaperSeed oneValueSeed = new PaperSeed(2L, List.of(new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:0", 2)));

        assertEquals(oneValueSeed,
                new PaperSeedKernel(oneValue, 8L)
                        .modifyEnvironmentEvent(oneValueSeed));
    }

    @Test
    void deviceMutationNeverAddsAnUnselectedDeviceEvent() {
        PaperEventDomainSnapshot devicesOnly = deviceMutationDomain();
        PaperSeed parent = new PaperSeed(55L, List.of(new PaperEvent(
                PaperEvent.Type.DEVICE, "thermostat", "off", 1)));
        int deviceEventMutations = 0;

        for (long kernelSeed = 0; kernelSeed < 100; kernelSeed++) {
            PaperSeedKernel.MutationBatch batch =
                    new PaperSeedKernel(devicesOnly, kernelSeed).mutateBatch(parent);
            PaperSeed mutated = batch.seed();
            assertEquals(1, mutated.events().size());
            assertEquals("thermostat", mutated.events().get(0).name());
            assertEquals(PaperEvent.Type.DEVICE, mutated.events().get(0).type());
            assertEquals(1, mutated.events().get(0).position());
            for (PaperSeedKernel.MutationStep step : batch.steps()) {
                if (step.selected() != null && !step.selected().initial()) {
                    deviceEventMutations++;
                    assertEquals(parent.events().get(0), step.selected().event());
                    assertEquals(PaperSeedKernel.MutationOperation.MODIFICATION, step.operation());
                    assertEquals(List.of(new PaperEvent(
                            PaperEvent.Type.DEVICE, "thermostat", "heat", 1)),
                            step.seed().events());
                }
            }
        }
        assertTrue(deviceEventMutations > 0, "The fixture must execute an ordinary device Event mutation");

        assertTrue(new PaperSeedKernel(devicesOnly, 1L)
                .mutate(PaperSeed.empty(9L)).events().isEmpty());
    }

    @Test
    void deletingOrdinaryEnvironmentEventDoesNotDeleteRandomInitialState() {
        PaperSeedKernel kernel = new PaperSeedKernel(domain(), 101L);
        PaperSeed source = new PaperSeed(707L, List.of(new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:1", 4)));
        List<PaperEvent> initialBeforeDeletion = kernel.materializeInitialState(source);

        PaperSeed deleted = kernel.deleteEnvironmentEvent(source);

        assertTrue(deleted.events().isEmpty());
        assertEquals(707L, deleted.initializationNonce());
        assertEquals(initialBeforeDeletion, kernel.materializeInitialState(deleted));
        assertTrue(initialBeforeDeletion.stream()
                .anyMatch(event -> event.type() == PaperEvent.Type.ENVIRONMENT));
    }

    @Test
    void positionFirstGenerationPreservesEnvironmentEventBias() {
        PaperEventDomainSnapshot domain = new PaperEventDomainSnapshot(
                8,
                List.of(new PaperDeviceDomain("thermostat", "mode", List.of("off", "heat"))),
                List.of(new PaperEnvironmentDomain(
                        "temperature",
                        "environment",
                        "temperature",
                        List.of("18", "21"),
                        List.of("-1", "+1"))));
        List<PaperEvent> events = new PaperSeedKernel(domain, 2024L)
                .initialPopulation(200)
                .stream()
                .flatMap(seed -> seed.events().stream())
                .toList();

        long deviceEvents = events.stream()
                .filter(event -> event.type() == PaperEvent.Type.DEVICE)
                .count();
        long environmentEvents = events.stream()
                .filter(event -> event.type() == PaperEvent.Type.ENVIRONMENT)
                .count();

        assertTrue(environmentEvents > deviceEvents);
        assertTrue(events.stream()
                .filter(event -> event.position() > 1)
                .allMatch(event -> event.type() == PaperEvent.Type.ENVIRONMENT));
    }

    @Test
    void mutationKeepsParentNonceWhileRandomSeedsResampleIt() {
        PaperEventDomainSnapshot mutationDomain = new PaperEventDomainSnapshot(
                5, deviceMutationDomain().deviceDomains(), domain().environmentDomains());
        PaperInitialValue override = new PaperInitialValue(
                PaperInitialValue.Type.ENVIRONMENT_VALUE,
                "environment",
                "temperature",
                "21");
        PaperSeed parent = new PaperSeed(
                Long.MIN_VALUE,
                List.of(override),
                List.of(new PaperEvent(
                        PaperEvent.Type.DEVICE, "thermostat", "off", 1)));
        PaperSeedKernel kernel = new PaperSeedKernel(mutationDomain, 8080L);
        boolean sawDeviceEventMutation = false;
        boolean sawInitialValueMutation = false;
        boolean sawRandom = false;

        for (int iteration = 0; iteration < 500; iteration++) {
            PaperSeedKernel.GeneratedSeed generated = kernel.nextSeed(parent);
            if (generated.strategy() == PaperSeedKernel.NextSeedStrategy.MUTATION) {
                assertEquals(parent.initializationNonce(), generated.seed().initializationNonce());
                if (!parent.events().equals(generated.seed().events())) {
                    sawDeviceEventMutation = true;
                    assertEquals(List.of(new PaperEvent(
                            PaperEvent.Type.DEVICE, "thermostat", "heat", 1)),
                            generated.seed().events());
                }
                if (!parent.initialOverrides().equals(generated.seed().initialOverrides())) {
                    sawInitialValueMutation = true;
                }
            } else {
                sawRandom = true;
                assertNotEquals(parent.initializationNonce(), generated.seed().initializationNonce());
                assertTrue(generated.seed().initialOverrides().isEmpty());
            }
        }

        assertTrue(sawDeviceEventMutation, "The mutation branch must change an ordinary device Event");
        assertTrue(sawInitialValueMutation, "The mutation branch must change an initial value");
        assertTrue(sawRandom);
    }

    @Test
    void mutationBatchUsesBoundedPercentagePolicyAndIsFullyDeterministic() {
        assertEquals(0, PaperSeedKernel.mutationOperationCountForRoll(0, 0.0));
        assertEquals(0, PaperSeedKernel.mutationOperationCountForRoll(1, 0.999999));
        assertEquals(0, PaperSeedKernel.mutationOperationCountForRoll(5, 0.999999));
        assertEquals(1, PaperSeedKernel.mutationOperationCountForRoll(6, 0.999999));
        assertEquals(1, PaperSeedKernel.mutationOperationCountForRoll(100, 0.0));
        assertEquals(10, PaperSeedKernel.mutationOperationCountForRoll(100, 0.999999));
        assertEquals(128, PaperSeedKernel.mutationOperationCountForRoll(
                PaperSeed.MAX_EVENTS, 0.999999));
        assertThrows(IllegalArgumentException.class,
                () -> PaperSeedKernel.mutationOperationCountForRoll(-1, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> PaperSeedKernel.mutationOperationCountForRoll(1, 1.0));

        PaperEventDomainSnapshot largeDomain = new PaperEventDomainSnapshot(
                100,
                List.of(),
                List.of(new PaperEnvironmentDomain(
                        "temperature",
                        "environment",
                        "temperature",
                        List.of("18", "21"),
                        List.of("-1", "+1"))));
        PaperSeed source = new PaperSeed(991L,
                java.util.stream.IntStream.rangeClosed(1, 100)
                        .mapToObj(position -> new PaperEvent(
                                PaperEvent.Type.ENVIRONMENT,
                                "temperature",
                                position % 2 == 0 ? "rate:-1" : "rate:1",
                                position))
                        .toList());
        PaperSeedKernel first = new PaperSeedKernel(largeDomain, 2026L);
        PaperSeedKernel second = new PaperSeedKernel(largeDomain, 2026L);

        PaperSeedKernel.MutationBatch firstBatch = first.mutateBatch(source);
        PaperSeedKernel.MutationBatch secondBatch = second.mutateBatch(source);

        assertEquals(firstBatch, secondBatch);
        assertTrue(firstBatch.steps().size() <= 10);
        assertEquals(991L, firstBatch.seed().initializationNonce());
        assertTrue(firstBatch.steps().stream()
                .filter(step -> step.selected() != null)
                .allMatch(step -> step.selected().initial()
                        ? step.selected().position() == 1
                        : step.selected().position()
                        == step.selected().event().position()));

        PaperSeedKernel third = new PaperSeedKernel(largeDomain, 2026L);
        assertEquals(firstBatch.seed(), third.mutate(source));
    }

    @Test
    void mutationPoolContainsEveryInitialTargetOncePlusOrdinaryEvents() {
        PaperEventDomainSnapshot domain = domainWithLocalVariables();
        PaperInitialValue environmentOverride = new PaperInitialValue(
                PaperInitialValue.Type.ENVIRONMENT_VALUE,
                "environment",
                "temperature",
                "21");
        PaperEvent ordinary = new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:1", 1);
        PaperSeed source = new PaperSeed(
                41L, List.of(environmentOverride), List.of(ordinary));

        PaperSeedKernel countKernel = new PaperSeedKernel(domain, 1L);
        assertEquals(domain.initialValueDomains().size() + 1,
                countKernel.mutationNodeCount(source));

        java.util.Set<String> selectedNodes = new java.util.HashSet<>();
        for (long kernelSeed = 0; kernelSeed < 500; kernelSeed++) {
            PaperSeedKernel.SelectedNode selected = new PaperSeedKernel(domain, kernelSeed)
                    .selectMutationNodePositionFirst(source, 1);
            selectedNodes.add(selected.initial()
                    ? "initial:" + selected.initialDomain().target()
                    : "event:" + selected.event());
        }

        assertEquals(domain.initialValueDomains().size() + 1, selectedNodes.size());
    }

    @Test
    void initialMutationChangesOnlyTheSelectedTargetAndKeepsOrdinaryEvents() {
        PaperEventDomainSnapshot domain = domainWithLocalVariables();
        PaperEvent ordinary = new PaperEvent(
                PaperEvent.Type.ENVIRONMENT, "temperature", "rate:-1", 4);
        PaperSeed source = new PaperSeed(73L, List.of(ordinary));
        PaperSeedKernel baselineKernel = new PaperSeedKernel(domain, 11L);
        List<PaperInitialValue> baseline = baselineKernel.materializeInitialValues(source);

        for (PaperInitialValue initial : baseline) {
            PaperSeedKernel kernel = new PaperSeedKernel(domain, 91L);
            PaperSeed mutated = kernel.modifyInitialValue(source, initial.target());
            List<PaperInitialValue> effective = kernel.materializeInitialValues(mutated);

            assertEquals(source.initializationNonce(), mutated.initializationNonce());
            assertEquals(source.events(), mutated.events());
            assertEquals(1, mutated.initialOverrides().size());
            assertEquals(initial.target(), mutated.initialOverrides().get(0).target());
            for (int index = 0; index < baseline.size(); index++) {
                PaperInitialValue before = baseline.get(index);
                PaperInitialValue after = effective.get(index);
                assertEquals(before.target(), after.target());
                if (before.target().equals(initial.target())) {
                    assertNotEquals(before.value(), after.value());
                } else {
                    assertEquals(before.value(), after.value());
                }
            }
        }
    }

    @Test
    void localOnlyAndSingletonInitialDomainsRemainValidAndMayMutateZeroNodes() {
        PaperInitialValueDomain singletonLocal = PaperInitialValueDomain.enumerated(
                PaperInitialValue.Type.DEVICE_VARIABLE,
                "sensor_1",
                "firmware",
                List.of("stable"));
        PaperEventDomainSnapshot localOnly = new PaperEventDomainSnapshot(
                3, List.of(), List.of(), List.of(singletonLocal));
        PaperSeedKernel kernel = new PaperSeedKernel(localOnly, 17L);

        List<PaperSeed> population = kernel.initialPopulation(5);

        assertTrue(population.stream().allMatch(seed ->
                seed.events().isEmpty() && seed.initialOverrides().isEmpty()));
        PaperSeed source = PaperSeed.empty(7L);
        assertEquals(1, kernel.mutationNodeCount(source));
        assertEquals(source, kernel.mutate(source));
        assertEquals(source, kernel.modifyInitialValue(source, singletonLocal.target()));
        assertEquals(List.of(new PaperInitialValue(
                        PaperInitialValue.Type.DEVICE_VARIABLE,
                        "sensor_1",
                        "firmware",
                        "stable")),
                kernel.materializeInitialValues(source));
    }

    @Test
    void alternativeIndexVisitsEveryNonExcludedValueExactlyOnce() {
        int size = 5;
        for (int excluded = 0; excluded < size; excluded++) {
            int excludedIndex = excluded;
            List<Integer> selected = IntStream.range(0, size - 1)
                    .map(compact -> PaperRandomChoice.alternativeIndex(
                            size, excludedIndex, compact))
                    .boxed()
                    .toList();

            assertEquals(size - 1, new HashSet<>(selected).size());
            assertFalse(selected.contains(excluded));
        }
    }

    @Test
    void populationSizeIsBounded() {
        PaperSeedKernel kernel = new PaperSeedKernel(domain(), 1L);

        assertThrows(IllegalArgumentException.class, () -> kernel.initialPopulation(0));
        assertThrows(IllegalArgumentException.class, () -> kernel.initialPopulation(
                PaperSeedKernel.MAX_POPULATION_SIZE + 1));
        assertThrows(IllegalArgumentException.class, () -> kernel.nextPopulation(
                PaperSeed.empty(1L), 0));
    }

    private static PaperEventDomainSnapshot deviceMutationDomain() {
        return new PaperEventDomainSnapshot(
                5,
                List.of(
                        new PaperDeviceDomain("thermostat", "mode", List.of("off", "heat")),
                        new PaperDeviceDomain("lock", "mode", List.of("locked", "unlocked")),
                        new PaperDeviceDomain("lamp", "mode", List.of("off", "on")),
                        new PaperDeviceDomain("fan", "mode", List.of("off", "on")),
                        new PaperDeviceDomain("alarm", "mode", List.of("off", "on")),
                        new PaperDeviceDomain("window", "mode", List.of("closed", "open"))),
                List.of());
    }

    private static PaperEventDomainSnapshot domain() {
        return new PaperEventDomainSnapshot(
                5,
                List.of(new PaperDeviceDomain(
                        "thermostat", "mode", List.of("off", "heat"))),
                List.of(new PaperEnvironmentDomain(
                        "temperature",
                        "environment",
                        "temperature",
                        List.of("18", "21"),
                        List.of("-1", "+1"))));
    }

    private static PaperEventDomainSnapshot domainWithLocalVariables() {
        return new PaperEventDomainSnapshot(
                5,
                List.of(new PaperDeviceDomain(
                        "thermostat", "mode", List.of("off", "heat"))),
                List.of(new PaperEnvironmentDomain(
                        "temperature",
                        "environment",
                        "temperature",
                        List.of("18", "21"),
                        List.of("-1", "+1"))),
                List.of(PaperInitialValueDomain.enumerated(
                        PaperInitialValue.Type.DEVICE_VARIABLE,
                        "thermostat",
                        "profile",
                        List.of("eco", "comfort"))));
    }
}
