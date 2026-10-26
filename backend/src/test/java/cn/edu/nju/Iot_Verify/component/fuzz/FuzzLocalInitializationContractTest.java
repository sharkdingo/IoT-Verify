package cn.edu.nju.Iot_Verify.component.fuzz;

import cn.edu.nju.Iot_Verify.component.fuzz.paper.PaperSeed;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvModelValidator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.module.SmvDeviceModuleBuilder;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.device.VariableStateDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzLocalInitializationContractTest {

    @ParameterizedTest
    @MethodSource("omittedOverrides")
    void omittedOverridesHaveTheSameInitialMeaningAsTheFormalModel(
            String state, List<VariableStateDto> variables) {
        ModelInputSnapshot snapshot = snapshot(state, variables);

        assertEquals(Map.of("contact", state, "profile", "stable", "steps", "-2"),
                initialVariables(FuzzModel.from(snapshot)));
        String module = formalModule(snapshot);
        assertTrue(module.contains("init(contact) := " + state + ";"));
        assertTrue(module.contains("init(profile) := stable;"));
        assertTrue(module.contains("init(steps) := -2;"));
    }

    @Test
    void omittedOverridesDoNotMakeAValidBoardSearchIneligible() {
        ModelInputSnapshot snapshot = snapshot("closed", List.of());

        FuzzEngineResult result = new FuzzEngine().run(new FuzzEngineInput(snapshot,
                new FuzzEngineConfig(List.of(), 2, 3, 2, 20261008L)), null, () -> false);

        assertEquals(FuzzEngineOutcome.BUDGET_EXHAUSTED, result.outcome());
        assertTrue(result.eligibility().get(0).supported());
        assertEquals(2, result.iterations());
        assertEquals(4, result.generatedPaths());
    }

    @Test
    void paperDomainAcceptsOmittedOverridesWithoutReducingTheLegalDomain() {
        var preview = new FuzzPaperDomainPreviewer().preview(
                snapshot("closed", null), 3, "a".repeat(64));

        assertEquals(3, preview.getLocalVariableDomains().size());
        var contact = preview.getLocalVariableDomains().stream()
                .filter(domain -> "contact".equals(domain.getProperty())).findFirst().orElseThrow();
        assertEquals(List.of("open", "closed"), contact.getLegalValues());
        var steps = preview.getLocalVariableDomains().stream()
                .filter(domain -> "steps".equals(domain.getProperty())).findFirst().orElseThrow();
        assertEquals(-2, steps.getLowerBound());
        assertEquals(3, steps.getUpperBound());
    }

    @Test
    void paperSeedsStillRandomizeLegalLocalValuesInsteadOfUsingTheBoardDefault() {
        FuzzModel model = FuzzModel.from(snapshot("closed", null));
        var domain = model.paperEventDomain(1);
        Set<String> values = new HashSet<>();
        for (long nonce = 0; nonce < 16; nonce++) {
            var initial = model.simulatePaperSeed(PaperSeed.empty(nonce), domain, 1, () -> false)
                    .traceStates().get(0).getDevices().get(0).getVariables();
            String steps = initial.stream().filter(value -> "steps".equals(value.getName()))
                    .findFirst().orElseThrow().getValue();
            assertTrue(Integer.parseInt(steps) >= -2 && Integer.parseInt(steps) <= 3);
            values.add(steps);
        }
        assertTrue(values.size() > 1);
    }

    @Test
    void explicitLegalOverridesRetainTheirAuthoredValuesInBothEngines() {
        ModelInputSnapshot snapshot = snapshot("closed", List.of(
                new VariableStateDto("steps", "2", null),
                new VariableStateDto("profile", "beta", null)));

        assertEquals(Map.of("contact", "closed", "profile", "beta", "steps", "2"),
                initialVariables(FuzzModel.from(snapshot)));
        String module = formalModule(snapshot);
        assertTrue(module.contains("init(profile) := beta;"));
        assertTrue(module.contains("init(steps) := 2;"));
    }

    @ParameterizedTest
    @MethodSource("invalidExplicitValues")
    void presentInvalidOverridesAreRejectedInsteadOfDefaulted(String value) {
        ModelInputSnapshot snapshot = snapshot("closed",
                List.of(new VariableStateDto("steps", value, null)));

        var modelError = assertThrows(FuzzModel.ModelException.class, () -> FuzzModel.from(snapshot));
        assertTrue(modelError.getMessage().contains("steps"));
        var previewError = assertThrows(FuzzModel.ModelException.class,
                () -> new FuzzPaperDomainPreviewer().preview(snapshot, 3, "a".repeat(64)));
        assertTrue(previewError.getMessage().contains("steps"));
    }

    private static Stream<Arguments> omittedOverrides() {
        return Stream.of(Arguments.of("closed", null), Arguments.of("closed", List.of()),
                Arguments.of("open", null), Arguments.of("open", List.of()));
    }

    private static Stream<Arguments> invalidExplicitValues() {
        return Stream.of(Arguments.of((String) null), Arguments.of(""), Arguments.of(" "),
                Arguments.of("-3"), Arguments.of("4"), Arguments.of("not-a-number"));
    }

    private static Map<String, String> initialVariables(FuzzModel model) {
        return model.simulate(new long[0], 1).traceStates().get(0).getDevices().get(0)
                .getVariables().stream().collect(Collectors.toMap(value -> value.getName(), value -> value.getValue()));
    }

    private static String formalModule(ModelInputSnapshot snapshot) {
        ObjectMapper mapper = new ObjectMapper();
        var device = new DeviceSmvDataFactory(mapper, null, new SmvModelValidator(),
                new DeviceTemplateSchemaValidator(mapper)).buildDeviceSmvMapFromTemplateSnapshots(
                snapshot.devices(), snapshot.templateManifests()).get("window_1");
        return new SmvDeviceModuleBuilder().build(device, false, false);
    }

    private static ModelInputSnapshot snapshot(String state, List<VariableStateDto> variables) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName("window_1");
        device.setTemplateName("Window");
        device.setState(state);
        device.setVariables(variables);
        DeviceManifest.InternalVariable contact = DeviceManifest.InternalVariable.builder()
                .name("contact").isInside(true).values(List.of("open", "closed")).build();
        DeviceManifest.InternalVariable profile = DeviceManifest.InternalVariable.builder()
                .name("profile").isInside(true).values(List.of("stable", "beta")).build();
        DeviceManifest.InternalVariable steps = DeviceManifest.InternalVariable.builder()
                .name("steps").isInside(true).lowerBound(-2).upperBound(3).build();
        DeviceManifest manifest = DeviceManifest.builder()
                .name("Window").modes(List.of("ContactState")).initState("closed")
                .internalVariables(List.of(contact, profile, steps))
                .workingStates(List.of(
                        DeviceManifest.WorkingState.builder().name("closed").dynamics(List.of(
                                DeviceManifest.Dynamic.builder().variableName("contact").value("closed").build())).build(),
                        DeviceManifest.WorkingState.builder().name("open").dynamics(List.of(
                                DeviceManifest.Dynamic.builder().variableName("contact").value("open").build())).build()))
                .build();
        SpecConditionDto condition = new SpecConditionDto();
        condition.setDeviceId("window_1");
        condition.setTargetType("state");
        condition.setKey("state");
        condition.setRelation("in");
        condition.setValue("open,closed");
        SpecificationDto specification = new SpecificationDto();
        specification.setId("legal-states");
        specification.setName("Safety requirement");
        specification.setTemplateId("1");
        specification.setTemplateLabel("Always");
        specification.setAConditions(List.of(condition));
        specification.setIfConditions(List.of());
        specification.setThenConditions(List.of());
        return new ModelInputSnapshot(List.of(), List.of(device), List.of(), List.of(),
                List.of(specification), Map.of("Window", manifest));
    }
}
