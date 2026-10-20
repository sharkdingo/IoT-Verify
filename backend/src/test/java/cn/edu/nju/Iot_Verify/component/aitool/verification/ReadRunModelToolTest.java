package cn.edu.nju.Iot_Verify.component.aitool.verification;

import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
import cn.edu.nju.Iot_Verify.exception.ResourceNotFoundException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.security.UserContextHolder;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.service.VerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadRunModelToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final VerificationService verificationService = mock(VerificationService.class);
    private final SimulationService simulationService = mock(SimulationService.class);
    private final ReadRunModelTool tool = new ReadRunModelTool(verificationService, simulationService, mapper);

    @BeforeEach
    void setUp() {
        UserContextHolder.setUserId(7L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void pagesReconstructTheExactHistoricalModelWithoutSplittingUnicode() throws Exception {
        String model = "MODULE main\n-- 🏠 温度\nVAR temperature : 0..30;\n";
        when(verificationService.getRunSmvModel(7L, 31L)).thenReturn(model);
        StringBuilder reconstructed = new StringBuilder();
        int offset = 0;
        JsonNode page;
        do {
            page = mapper.readTree(tool.execute("{\"kind\":\"verification\",\"id\":31,\"offset\":"
                    + offset + ",\"limit\":3}"));
            String excerpt = page.path("modelText").asText();
            reconstructed.append(excerpt);
            assertEquals(excerpt.codePointCount(0, excerpt.length()), page.path("returnedCharacters").asInt());
            assertEquals(offset, page.path("offset").asInt());
            assertEquals("SAVED_RUN", page.path("modelSource").asText());
            assertFalse(page.has("outcome"));
            offset = page.path("nextOffset").asInt();
        } while (page.path("hasMore").asBoolean());
        assertEquals(model, reconstructed.toString());
        assertFalse(page.has("nextOffset"));
        verifyNoInteractions(simulationService);
        verify(verificationService, never()).verify(any(), any());
    }

    @Test
    void simulationUsesItsOwnedSavedModelRatherThanVerificationOrCurrentBoard() throws Exception {
        SimulationTraceDto trace = new SimulationTraceDto();
        trace.setSmvModelContent("MODULE main\n-- simulation-only model\n");
        when(simulationService.getSimulation(7L, 42L)).thenReturn(trace);
        JsonNode result = mapper.readTree(tool.execute("{\"kind\":\"simulation\",\"id\":42}"));
        assertEquals(trace.getSmvModelContent(), result.path("modelText").asText());
        assertEquals("simulation", result.path("kind").asText());
        assertFalse(result.path("hasMore").asBoolean());
        verify(simulationService).getSimulation(7L, 42L);
        verifyNoInteractions(verificationService);
    }

    @Test
    void missingModelAndOwnershipFailureNeverGenerateOrSubstituteAModel() throws Exception {
        for (String model : List.of("", "   ")) {
            when(verificationService.getRunSmvModel(7L, 31L)).thenReturn(model);
            assertEquals(404, mapper.readTree(tool.execute("{\"kind\":\"verification\",\"id\":31}"))
                    .path("status").asInt());
        }
        when(verificationService.getRunSmvModel(7L, 31L)).thenThrow(new ResourceNotFoundException("run", 31L));
        JsonNode denied = mapper.readTree(tool.execute("{\"kind\":\"verification\",\"id\":31}"));
        assertEquals(404, denied.path("status").asInt());
        assertFalse(denied.has("modelText"));
        verifyNoInteractions(simulationService);
    }

    @Test
    void invalidArgumentsAreRejectedBeforeReadingAnyRun() throws Exception {
        for (String args : List.of("{}", "[]", "{\"kind\":\"fuzz\",\"id\":31}",
                "{\"kind\":\"verification\",\"id\":\"31\"}",
                "{\"kind\":\"verification\",\"id\":0}",
                "{\"kind\":\"verification\",\"id\":31,\"offset\":-1}",
                "{\"kind\":\"verification\",\"id\":31,\"offset\":1.0}",
                "{\"kind\":\"verification\",\"id\":31,\"offset\":2147483648}",
                "{\"kind\":\"verification\",\"id\":31,\"limit\":0}",
                "{\"kind\":\"verification\",\"id\":31,\"limit\":8001}",
                "{\"kind\":\"verification\",\"id\":31,\"modelText\":\"fake\"}")) {
            assertEquals(400, mapper.readTree(tool.execute(args)).path("status").asInt(), args);
        }
        verifyNoInteractions(verificationService, simulationService);
    }

    @Test
    void excessiveOffsetAndReadFailureAreExplicit() throws Exception {
        when(verificationService.getRunSmvModel(7L, 31L)).thenReturn("MODULE main\n");
        assertEquals(400, mapper.readTree(tool.execute(
                "{\"kind\":\"verification\",\"id\":31,\"offset\":999}")).path("status").asInt());
        when(verificationService.getRunSmvModel(7L, 31L)).thenThrow(new ServiceUnavailableException("busy"));
        JsonNode unavailable = mapper.readTree(tool.execute("{\"kind\":\"verification\",\"id\":31}"));
        assertEquals(503, unavailable.path("status").asInt());
        assertFalse(unavailable.has("modelText"));
    }
}
