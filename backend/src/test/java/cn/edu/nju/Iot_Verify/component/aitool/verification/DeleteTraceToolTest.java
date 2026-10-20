package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.dto.trace.TraceDto;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteTraceToolTest {

    @Mock
    private VerificationService verificationService;

    private ObjectMapper objectMapper;
    private DeleteTraceTool tool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new DeleteTraceTool(
                verificationService, objectMapper, new AiDestructiveActionGuard(objectMapper));
        UserContextHolder.clear();
        UserContextHolder.setChatSessionId("trace-test-session");
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void execute_withMissingTraceId_shouldReturnValidationError() throws Exception {
        UserContextHolder.setUserId(1L);
        String result = tool.execute("{}");

        JsonNode json = objectMapper.readTree(result);
        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        assertEquals(400, json.path("status").asInt());
    }

    @Test
    void execute_withUnknownField_shouldRejectBeforeLoadingTrace() throws Exception {
        UserContextHolder.setUserId(1L);
        JsonNode json = objectMapper.readTree(tool.execute(
                "{\"traceId\":8,\"confirmed\":false,\"force\":true}"));

        assertEquals("VALIDATION_ERROR", json.path("errorCode").asText());
        verify(verificationService, never()).getTrace(1L, 8L);
    }

    @Test
    void execute_withNotFoundTrace_shouldReturnBusinessError() throws Exception {
        UserContextHolder.setUserId(1L);
        when(verificationService.getTrace(1L, 99L))
                .thenThrow(new ResourceNotFoundException("Trace", 99L));

        String result = tool.execute("{\"traceId\":99,\"confirmed\":false}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals("BUSINESS_ERROR", json.path("errorCode").asText());
        assertEquals(404, json.path("status").asInt());
    }

    @Test
    void execute_withValidTrace_shouldDeleteSuccessfully() throws Exception {
        UserContextHolder.setUserId(1L);
        SpecificationDto specification = new SpecificationDto();
        specification.setId("spec_2");
        specification.setTemplateId("3");
        specification.setTemplateLabel("Door must remain closed");
        TraceDto trace = TraceDto.builder()
                .id(8L)
                .violatedSpecId("spec_2")
                .violatedSpec(specification)
                .build();
        when(verificationService.getTrace(1L, 8L)).thenReturn(trace);

        String preview = tool.execute("{\"traceId\":8,\"confirmed\":false}");
        JsonNode previewJson = objectMapper.readTree(preview);
        assertTrue(previewJson.path("requiresUserConfirmation").asBoolean());
        verify(verificationService, never()).deleteTrace(1L, 8L);

        UserContextHolder.setConfirmedProtectedAction("DESTRUCTIVE", previewJson.path("impactToken").asText());
        String result = tool.execute("{\"traceId\":8,\"confirmed\":true,\"impactToken\":\""
                + previewJson.path("impactToken").asText() + "\"}");
        JsonNode json = objectMapper.readTree(result);

        assertEquals(true, json.path("deleted").asBoolean());
        assertEquals(8L, json.path("traceId").asLong());
        assertEquals(false, json.has("violatedSpecId"));
        assertEquals("Door must remain closed",
                json.path("violatedSpecification").path("specificationLabel").asText());
        verify(verificationService).deleteTrace(1L, 8L);
    }
}
