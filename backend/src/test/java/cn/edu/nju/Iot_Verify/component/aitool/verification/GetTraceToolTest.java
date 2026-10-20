package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDeviceDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceStateDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTriggeredRuleDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceVariableDto;
import cn.edu.nju.Iot_Verify.dto.trace.TraceTrustPrivacyDto;
import cn.edu.nju.Iot_Verify.component.aitool.AiToolManager;
import cn.edu.nju.Iot_Verify.component.aitool.ModelTraceToolPresenter;
import cn.edu.nju.Iot_Verify.configure.ChatExecutionConfig;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetTraceToolTest {

    @Mock
    private VerificationService verificationService;

    private ObjectMapper objectMapper;
    private GetTraceTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new GetTraceTool(verificationService, objectMapper);
        UserContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void execute_withoutLogin_shouldReturnErrorJson() throws Exception {
        String result = tool.execute("{\"traceId\":1}");

        JsonNode json = objectMapper.readTree(result);
        assertEquals("UNAUTHORIZED", json.path("errorCode").asText());
        assertEquals(401, json.path("status").asInt());
    }

    @Test
    void execute_withInvalidTraceId_shouldReturnValidationError() throws Exception {
        UserContextHolder.setUserId(1L);
        String result = tool.execute("{\"traceId\":0}");

        JsonNode json = objectMapper.readTree(result);
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
    }

    @Test
    void execute_withUnknownField_shouldRejectBeforeLoadingTrace() throws Exception {
        UserContextHolder.setUserId(1L);
        JsonNode json = objectMapper.readTree(tool.execute("{\"traceId\":1,\"raw\":true}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(verificationService, never()).getTrace(1L, 1L);
    }

    @Test
    void execute_withNotFoundTrace_shouldReturnBusinessError() throws Exception {
        UserContextHolder.setUserId(1L);
        when(verificationService.getTrace(1L, 99L))
                .thenThrow(new ResourceNotFoundException("Trace", 99L));

        String result = tool.execute("{\"traceId\":99}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(404, json.path("status").asInt());
    }

    @Test
    void execute_withValidTrace_shouldReturnTraceDetails() throws Exception {
        UserContextHolder.setUserId(1L);
        SpecificationDto violatedSpec = new SpecificationDto();
        violatedSpec.setId("spec_1");
        violatedSpec.setTemplateId("3");
        violatedSpec.setTemplateLabel("Never");
        violatedSpec.setFormula("AG(!camera.recording)");
        TraceDeviceDto device = new TraceDeviceDto();
        device.setDeviceId("camera_internal");
        device.setDeviceLabel("Front camera");
        device.setTemplateName("Camera");
        TraceDto trace = TraceDto.builder()
                .id(7L)
                .violatedSpecId("spec_1")
                .violatedSpec(violatedSpec)
                .checkedExpression("CTLSPEC AG(!camera_internal.recording)")
                .modelComplete(false)
                .disabledRuleCount(1)
                .skippedSpecCount(0)
                .states(List.of(TraceStateDto.builder()
                        .stateIndex(1)
                        .devices(List.of(device))
                        .triggeredRules(List.of(TraceTriggeredRuleDto.builder()
                                .ruleIndex(0)
                                .ruleId("42")
                                .ruleLabel("Start recording on motion")
                                .build()))
                        .compromisedAutomationLinks(List.of())
                        .build()))
                .build();
        when(verificationService.getTrace(1L, 7L)).thenReturn(trace);

        String result = tool.execute("{\"traceId\":7}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals(7L, json.path("traceId").asLong());
        assertEquals(false, json.has("violatedSpecId"));
        assertEquals("Never", json.path("violatedSpecification").path("specificationLabel").asText());
        assertEquals("CTL", json.path("violatedSpecification").path("formulaKind").asText());
        // The tool description tells the model that the trailing cycle is itself the violation "for a
        // liveness specification (templateId 2, 5, 6)", so the response has to carry that id. It did not:
        // the only spec identity emitted was `specificationLabel`, which is display wording. A model asked
        // to gate a formal judgement on a field it cannot see either guesses from the label or ignores the
        // instruction, and neither failure is visible in the output.
        assertEquals("3", json.path("violatedSpecification").path("templateId").asText());
        assertEquals(false, json.path("modelComplete").asBoolean());
        assertEquals(1, json.path("disabledRuleCount").asInt());
        assertEquals(1, json.path("stateCount").asInt());
        assertEquals(0, json.path("stateOffset").asInt());
        assertEquals(10, json.path("stateLimit").asInt());
        assertEquals(1, json.path("returnedStateCount").asInt());
        assertEquals(false, json.path("hasMoreStates").asBoolean());
        assertEquals(false, json.has("trace"));
        assertEquals("Front camera", json.path("states").get(0).path("devices").get(0)
                .path("deviceLabel").asText());
        assertEquals(false, json.path("states").get(0).path("devices").get(0).has("deviceId"));
        assertEquals("Start recording on motion", json.path("states").get(0).path("triggeredRules").get(0)
                .path("ruleLabel").asText());
        assertEquals(false, json.path("states").get(0).path("triggeredRules").get(0).has("ruleId"));
        assertEquals(false, json.path("states").get(0).path("triggeredRules").get(0).has("ruleIndex"));
        assertEquals(true, json.path("message").asText().contains("incomplete"));
    }

    @Test
    void execute_pagesLongTraceInsteadOfReturningTheWholeSequence() throws Exception {
        UserContextHolder.setUserId(1L);
        TraceDto trace = TraceDto.builder()
                .id(8L)
                .modelComplete(true)
                .states(IntStream.range(0, 12)
                        .mapToObj(index -> TraceStateDto.builder()
                                .stateIndex(index)
                                .devices(List.of())
                                .triggeredRules(List.of())
                                .compromisedAutomationLinks(List.of())
                                .build())
                        .toList())
                .build();
        when(verificationService.getTrace(1L, 8L)).thenReturn(trace);

        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"traceId\":8,\"stateOffset\":9,\"stateLimit\":2}"));

        assertEquals(12, json.path("stateCount").asInt());
        assertEquals(9, json.path("stateOffset").asInt());
        assertEquals(2, json.path("returnedStateCount").asInt());
        assertEquals(9, json.path("states").get(0).path("stateIndex").asInt());
        assertEquals(11, json.path("nextStateOffset").asInt());
        assertEquals(true, json.path("hasMoreStates").asBoolean());

        JsonNode beyondEnd = objectMapper.readTree(tool.execute(
                "{\"traceId\":8,\"stateOffset\":100000}"));
        assertEquals(100000, beyondEnd.path("stateOffset").asInt());
        assertEquals(0, beyondEnd.path("returnedStateCount").asInt());
        assertEquals(false, beyondEnd.path("hasMoreStates").asBoolean());
    }

    @Test
    void execute_keepsBedReturnAndCameraStartInTheirActualAdjacentStates() throws Exception {
        UserContextHolder.setUserId(1L);
        TraceDto trace = TraceDto.builder().id(10L).modelComplete(true).states(List.of(
                cameraState(1, "empty", "23", "on"),
                cameraState(2, "occupied", "0", "on"),
                cameraState(3, "occupied", "1", "takingphoto"))).build();
        when(verificationService.getTrace(1L, 10L)).thenReturn(trace);

        JsonNode states = objectMapper.readTree(tool.execute("{\"traceId\":10}")).path("states");

        assertEquals("on", states.get(1).path("valueSnapshot").path("deviceStates").get(0)
                .path("state").asText());
        assertEquals("occupied", states.get(1).path("valueSnapshot").path("environmentValues").get(0)
                .path("value").asText());
        assertEquals("0", states.get(1).path("valueSnapshot").path("environmentValues").get(1)
                .path("value").asText());
        assertEquals("takingphoto", states.get(2).path("valueSnapshot").path("deviceStates").get(0)
                .path("state").asText());
        assertEquals("1", states.get(2).path("valueSnapshot").path("environmentValues").get(1)
                .path("value").asText());
        assertEquals(false, states.get(0).has("changesFromPreviousState"));
        JsonNode returnToBed = states.get(1).path("changesFromPreviousState");
        assertEquals(1, returnToBed.path("previousStateIndex").asInt());
        assertEquals(0, returnToBed.path("deviceStates").size());
        assertEquals("empty", returnToBed.path("environmentValues").get(0).path("previousValue").asText());
        assertEquals("occupied", returnToBed.path("environmentValues").get(0).path("currentValue").asText());
        JsonNode cameraStart = states.get(2).path("changesFromPreviousState");
        assertEquals(2, cameraStart.path("previousStateIndex").asInt());
        assertEquals(1, cameraStart.path("deviceStates").size());
        assertEquals("on", cameraStart.path("deviceStates").get(0).path("previousState").asText());
        assertEquals("takingphoto", cameraStart.path("deviceStates").get(0).path("currentState").asText());
        assertEquals(1, cameraStart.path("environmentValues").size());
        assertEquals("time", cameraStart.path("environmentValues").get(0).path("name").asText());

        JsonNode page = objectMapper.readTree(tool.execute(
                "{\"traceId\":10,\"stateOffset\":2,\"stateLimit\":1}"));
        assertEquals(states.get(2), page.path("states").get(0));
    }

    @Test
    void statePreview_doesNotInventAdjacentChangesAcrossOmittedStates() throws Exception {
        JsonNode states = objectMapper.valueToTree(
                ModelTraceToolPresenter.states(List.of(
                        cameraState(1, "empty", "23", "on"),
                        cameraState(3, "occupied", "1", "takingphoto"))));

        assertEquals(false, states.get(1).has("changesFromPreviousState"));
    }

    @Test
    void stateChanges_matchDeviceIdentityAndModeRatherThanDisplayLabel() {
        TraceStateDto previous = cameraState(1, "empty", "23", "on");
        TraceStateDto current = cameraState(2, "empty", "23", "takingphoto");
        TraceDeviceDto unrelated = new TraceDeviceDto();
        unrelated.setDeviceId("camera_2");
        unrelated.setDeviceLabel("Hallway Camera");
        unrelated.setMode("MachineState");
        unrelated.setState("takingphoto");
        TraceDeviceDto otherMode = new TraceDeviceDto();
        otherMode.setDeviceId("camera_1");
        otherMode.setDeviceLabel("Hallway Camera");
        otherMode.setMode("PowerState");
        otherMode.setState("takingphoto");
        previous.setDevices(List.of(unrelated, otherMode, previous.getDevices().get(0)));

        JsonNode changes = objectMapper.valueToTree(ModelTraceToolPresenter.states(List.of(previous, current)))
                .get(1).path("changesFromPreviousState").path("deviceStates");

        assertEquals(1, changes.size());
        assertEquals("MachineState", changes.get(0).path("mode").asText());
        assertEquals("on", changes.get(0).path("previousState").asText());
        assertEquals("takingphoto", changes.get(0).path("currentState").asText());
    }

    @Test
    void stateChanges_doNotTreatMissingOrUnobservedValuesAsTransitions() {
        TraceStateDto previous = cameraState(1, "empty", "23", "on");
        TraceStateDto current = cameraState(2, "occupied", "0", "takingphoto");
        previous.getDevices().get(0).setState(null);
        previous.getEnvVariables().get(0).setObserved(false);
        current.getEnvVariables().get(1).setObserved(false);

        JsonNode changes = objectMapper.valueToTree(ModelTraceToolPresenter.states(List.of(previous, current)))
                .get(1).path("changesFromPreviousState");

        assertEquals(0, changes.path("deviceStates").size());
        assertEquals(0, changes.path("environmentValues").size());
        JsonNode snapshot = objectMapper.valueToTree(ModelTraceToolPresenter.states(List.of(current)))
                .get(0).path("valueSnapshot");
        assertEquals(false, snapshot.path("environmentValues").get(1).path("observed").asBoolean());
        assertEquals(false, snapshot.path("environmentValues").get(1).has("value"));
    }

    @Test
    void execute_valueViewKeepsSnapshotsAndExposesExplicitLabelDetail() throws Exception {
        UserContextHolder.setUserId(1L);
        TraceStateDto state = cameraState(1, "occupied", "0", "takingphoto");
        TraceTrustPrivacyDto label = new TraceTrustPrivacyDto();
        label.setName("takingphoto");
        label.setPropertyScope("state");
        label.setMode("MachineState");
        label.setTrust(false);
        label.setPrivacy("private");
        state.getDevices().get(0).setTrustPrivacy(List.of(label));
        state.getDevices().get(0).setPrivacies(List.of(label));
        when(verificationService.getTrace(1L, 11L)).thenReturn(
                TraceDto.builder().id(11L).modelComplete(true).states(List.of(state)).build());

        JsonNode values = objectMapper.readTree(tool.execute("{\"traceId\":11}"));
        JsonNode nullDefault = objectMapper.readTree(tool.execute(
                "{\"traceId\":11,\"includePropertyLabels\":null}"));
        JsonNode full = objectMapper.readTree(tool.execute(
                "{\"traceId\":11,\"includePropertyLabels\":true}"));
        JsonNode device = values.path("states").get(0).path("devices").get(0);
        JsonNode fullDevice = full.path("states").get(0).path("devices").get(0);

        assertEquals(false, values.path("propertyLabelsIncluded").asBoolean());
        assertEquals(values, nullDefault);
        assertEquals(false, device.has("trustPrivacyLabels"));
        assertEquals(false, device.has("privacyLabels"));
        assertEquals("takingphoto", device.path("state").asText());
        assertEquals("MachineState", device.path("mode").asText());
        assertEquals(fullDevice.path("variables"), device.path("variables"));
        assertEquals(full.path("states").get(0).path("environmentVariables"),
                values.path("states").get(0).path("environmentVariables"));
        assertEquals(true, full.path("propertyLabelsIncluded").asBoolean());
        assertEquals(false, fullDevice.path("trustPrivacyLabels").get(0).path("trust").asBoolean());
        assertEquals("private", fullDevice.path("privacyLabels").get(0).path("privacy").asText());
    }

    @Test
    void execute_rejectsNonBooleanLabelDetailBeforeReading() throws Exception {
        UserContextHolder.setUserId(1L);
        for (String value : List.of("\"true\"", "1")) {
            JsonNode result = objectMapper.readTree(tool.execute(
                    "{\"traceId\":11,\"includePropertyLabels\":" + value + "}"));
            assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        }
        verify(verificationService, never()).getTrace(1L, 11L);
    }

    @Test
    void execute_valueViewKeepsTheWholeLivenessCycleWithinTheToolByteBudget() throws Exception {
        UserContextHolder.setUserId(1L);
        List<TraceStateDto> sequence = IntStream.rangeClosed(1, 7).mapToObj(index -> {
            TraceStateDto state = cameraState(index, "occupied", String.valueOf(index), "on");
            state.setLoopStart(index == 6);
            state.setLoopBack(index == 7);
            state.setDevices(IntStream.range(0, 14).mapToObj(deviceIndex -> {
                TraceDeviceDto device = new TraceDeviceDto();
                device.setDeviceId("camera_" + deviceIndex);
                device.setDeviceLabel("Camera " + deviceIndex);
                device.setMode("MachineState");
                device.setState("on");
                List<TraceTrustPrivacyDto> labels = IntStream.range(0, 6).mapToObj(labelIndex -> {
                    TraceTrustPrivacyDto label = new TraceTrustPrivacyDto();
                    label.setName("state_" + labelIndex);
                    label.setPropertyScope("state");
                    label.setMode("MachineState");
                    label.setTrust(true);
                    label.setPrivacy("private");
                    return label;
                }).toList();
                device.setTrustPrivacy(labels);
                device.setPrivacies(labels);
                return device;
            }).toList());
            return state;
        }).toList();
        when(verificationService.getTrace(1L, 12L)).thenReturn(
                TraceDto.builder().id(12L).modelComplete(true).states(sequence).build());
        AiToolManager manager = new AiToolManager(List.of(tool), objectMapper, new ChatExecutionConfig(), org.mockito.Mockito.mock(cn.edu.nju.Iot_Verify.component.ai.ChatConfirmationDetector.class));
        manager.init();

        JsonNode values = objectMapper.readTree(manager.execute("get_trace", "{\"traceId\":12}"));
        JsonNode labels = objectMapper.readTree(manager.execute("get_trace",
                "{\"traceId\":12,\"includePropertyLabels\":true}"));

        assertEquals("SUCCESS", values.path("resultStatus").asText());
        assertEquals(7, values.path("returnedStateCount").asInt());
        assertEquals(false, values.path("hasMoreStates").asBoolean());
        assertEquals(true, values.path("states").get(5).path("loopStart").asBoolean());
        assertEquals(true, values.path("states").get(6).path("loopBack").asBoolean());
        assertEquals(true, labels.path("propertyLabelsIncluded").asBoolean());
        assertEquals(false, labels.path("hasMoreStates").asBoolean());
        assertEquals(7, labels.path("returnedStateCount").asInt());
        assertEquals(true, labels.path("states").get(6).path("loopBack").asBoolean());
        assertEquals(6, labels.path("states").get(6).path("devices").get(0)
                .path("propertyLabelsFromStateIndex").asInt());
        assertEquals(6, labels.path("states").get(0).path("devices").get(0)
                .path("trustPrivacyLabels").size());
    }

    @Test
    void execute_labelReferencesPreserveChangesAndStartEachPageWithItsOwnBaseline() throws Exception {
        UserContextHolder.setUserId(1L);
        List<TraceStateDto> sequence = IntStream.rangeClosed(1, 7).mapToObj(index -> {
            TraceStateDto state = cameraState(index, "occupied", "1", "on");
            TraceDeviceDto device = state.getDevices().get(0);
            TraceTrustPrivacyDto label = new TraceTrustPrivacyDto();
            label.setName("on");
            label.setPropertyScope("state");
            label.setMode("MachineState");
            label.setTrust(index < 3);
            label.setPrivacy(index < 4 ? "private" : "public");
            device.setTrustPrivacy(List.of(label));
            device.setPrivacies(List.of(label));
            if (index == 6) device.setDeviceId("camera_2");
            if (index == 7) {
                device.setDeviceId("camera_2");
                device.setMode("PowerState");
            }
            return state;
        }).toList();
        when(verificationService.getTrace(1L, 13L)).thenReturn(
                TraceDto.builder().id(13L).modelComplete(true).states(sequence).build());

        JsonNode states = objectMapper.readTree(tool.execute(
                "{\"traceId\":13,\"includePropertyLabels\":true}")).path("states");
        assertEquals(1, states.get(1).path("devices").get(0)
                .path("propertyLabelsFromStateIndex").asInt());
        assertEquals(false, states.get(1).path("devices").get(0).has("trustPrivacyLabels"));
        assertEquals(false, states.get(2).path("devices").get(0)
                .path("trustPrivacyLabels").get(0).path("trust").asBoolean());
        assertEquals("private", states.get(2).path("devices").get(0)
                .path("privacyLabels").get(0).path("privacy").asText());
        assertEquals("public", states.get(3).path("devices").get(0)
                .path("privacyLabels").get(0).path("privacy").asText());
        assertEquals(4, states.get(4).path("devices").get(0)
                .path("propertyLabelsFromStateIndex").asInt());
        assertEquals(false, states.get(5).path("devices").get(0).has("propertyLabelsFromStateIndex"));
        assertEquals(false, states.get(6).path("devices").get(0).has("propertyLabelsFromStateIndex"));

        JsonNode page = objectMapper.readTree(tool.execute(
                "{\"traceId\":13,\"includePropertyLabels\":true,\"stateOffset\":1,\"stateLimit\":2}"))
                .path("states");
        JsonNode baseline = page.get(0).path("devices").get(0);
        assertEquals(false, baseline.has("propertyLabelsFromStateIndex"));
        assertEquals(true, baseline.path("trustPrivacyLabels").get(0).path("trust").asBoolean());
        assertEquals("private", baseline.path("privacyLabels").get(0).path("privacy").asText());
    }

    private TraceStateDto cameraState(int index, String occupancy, String time, String cameraState) {
        TraceDeviceDto camera = new TraceDeviceDto();
        camera.setDeviceId("camera_1");
        camera.setDeviceLabel("Hallway Camera");
        camera.setMode("MachineState");
        camera.setState(cameraState);
        TraceVariableDto bed = new TraceVariableDto();
        bed.setName("bedOccupancy");
        bed.setValue(occupancy);
        TraceVariableDto clock = new TraceVariableDto();
        clock.setName("time");
        clock.setValue(time);
        return TraceStateDto.builder().stateIndex(index).devices(List.of(camera))
                .envVariables(List.of(bed, clock)).build();
    }

    /**
     * NuSMV closes a liveness cycle by re-printing its entry; page-local value comparison cannot
     * reliably distinguish a cycle from a finite path, particularly when the entry is on another page.
     */
    @Test
    void execute_marksTheCycleOfALivenessCounterexample() throws Exception {
        UserContextHolder.setUserId(1L);
        SpecificationDto violatedSpec = new SpecificationDto();
        violatedSpec.setId("spec_5");
        violatedSpec.setTemplateId("5");
        TraceDto trace = TraceDto.builder()
                .id(9L)
                .violatedSpecId("spec_5")
                .violatedSpec(violatedSpec)
                .modelComplete(true)
                .states(List.of(
                        TraceStateDto.builder().stateIndex(0).build(),
                        TraceStateDto.builder().stateIndex(1).loopStart(true).build(),
                        TraceStateDto.builder().stateIndex(2).loopBack(true).build()))
                .build();
        when(verificationService.getTrace(1L, 9L)).thenReturn(trace);

        JsonNode states = objectMapper.readTree(tool.execute("{\"traceId\":9}")).path("states");

        assertEquals(3, states.size());
        assertEquals(true, states.get(1).path("loopStart").asBoolean());
        assertEquals(true, states.get(2).path("loopBack").asBoolean());
        // Absent rather than false on an ordinary state, matching how the other optional fields behave here —
        // a finite simulation or fuzz trace must not gain two always-false keys.
        assertEquals(false, states.get(0).has("loopStart"));
        assertEquals(false, states.get(0).has("loopBack"));
        assertEquals(false, states.get(1).has("loopBack"));
    }
}
