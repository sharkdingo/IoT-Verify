package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.exception.AsyncTaskDispatchOutcomeUnknownException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiToolResponseHelperTest {

    @Test
    void success_whenSerializationFails_shouldReturnUnavailableResultJson() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });

        String result = AiToolResponseHelper.success(
                failingMapper,
                Map.of("message", "ok"),
                "Operation accepted.");

        JsonNode json = new ObjectMapper().readTree(result);
        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertTrue(json.path("message").asText().contains("may already have been committed"));
        assertTrue(json.path("warning").asText().contains("serialization failed"));
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
    }

    @Test
    void asyncDispatchUnknown_whenSerializationFails_shouldKeepReconciliationIdentity() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });

        String result = AiToolResponseHelper.asyncTaskDispatchOutcomeUnknown(
                failingMapper, 23L, "verify_task_status");

        JsonNode json = new ObjectMapper().readTree(result);
        assertEquals("RESULT_UNAVAILABLE", json.path("resultStatus").asText());
        assertEquals(AsyncTaskDispatchOutcomeUnknownException.REASON_CODE,
                json.path("errorCode").asText());
        assertEquals(23L, json.path("taskId").asLong());
        assertEquals("verify_task_status", json.path("statusTool").asText());
        assertTrue(json.path("mutationMayHaveCommitted").asBoolean());
    }
}
