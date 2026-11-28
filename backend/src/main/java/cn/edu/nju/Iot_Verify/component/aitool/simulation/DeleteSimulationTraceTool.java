package cn.edu.nju.Iot_Verify.component.aitool.simulation;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.AiDestructiveActionGuard;
import cn.edu.nju.Iot_Verify.component.aitool.AiTaskToolSupport;
import cn.edu.nju.Iot_Verify.dto.simulation.SimulationTraceDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.SimulationService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class DeleteSimulationTraceTool extends AbstractAiTool {

    private final SimulationService simulationService;
    private final AiDestructiveActionGuard destructiveActionGuard;

    public DeleteSimulationTraceTool(SimulationService simulationService,
                                      ObjectMapper objectMapper,
                                      AiDestructiveActionGuard destructiveActionGuard) {
        super(objectMapper);
        this.simulationService = simulationService;
        this.destructiveActionGuard = destructiveActionGuard;
    }

    @Override
    public String getName() {
        return "delete_simulation_trace";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("simulationId", Map.of("type", "integer", "description", "Simulation trace ID."));
        properties.put("confirmed", Map.of(
                "type", "boolean",
                "description", "Use false to preview the saved run. Use true only in a later turn after explicit user confirmation."
        ));
        properties.put("impactToken", Map.of(
                "type", "string",
                "description", "Required with confirmed=true. Copy the opaque impactToken from the latest preview."
        ));
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object",
                properties,
                List.of("simulationId", "confirmed")
        );

        return LlmToolSpec.of(getName(), "Preview or, after explicit two-turn confirmation, delete a saved simulation trace.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);

            requireOnlyFields(args, "arguments", Set.of("simulationId", "confirmed", "impactToken"));
            long simulationId = positiveLongArg(args, "simulationId");

            SimulationTraceDto trace = simulationService.getSimulation(userId, simulationId);
            Map<String, Object> previewSummary = previewSummary(simulationId, trace);
            String displayTarget = "Simulation: " + trace.getCreatedAt() + " · " + trace.getSteps() + " steps";
            boolean confirmed = booleanArg(args, "confirmed", false);
            return AiTaskToolSupport.withDeletionConfirmation(this, args, confirmed, userId, destructiveActionGuard,
                    new AiTaskToolSupport.DeletionPreview(simulationId, displayTarget, previewSummary, previewSummary,
                            "No changes were made. Explicit user confirmation is required before deleting this saved simulation run."), () -> {
                simulationService.deleteSimulation(userId, simulationId);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("simulationId", simulationId);
                body.put("steps", trace.getSteps());
                body.put("deleted", true);
                body.put("message", "Simulation trace deleted.");
                return successJson(body);
            });
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("delete_simulation_trace busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("delete_simulation_trace business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("delete_simulation_trace failed", e);
            return errorJson("Failed to delete simulation trace.", "INTERNAL_ERROR", 500);
        }
    }

    private Map<String, Object> previewSummary(long simulationId, SimulationTraceDto trace) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("simulationId", simulationId);
        preview.put("steps", trace.getSteps());
        preview.put("modelComplete", trace.isModelComplete());
        return preview;
    }
}
