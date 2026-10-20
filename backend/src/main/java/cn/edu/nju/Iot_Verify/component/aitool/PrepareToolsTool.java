package cn.edu.nju.Iot_Verify.component.aitool;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exposes the requested schemas on the next planning round without copying them into history. */
@Slf4j
@Component
public class PrepareToolsTool extends AbstractAiTool {
    private final ObjectProvider<AiToolManager> toolManager;

    public PrepareToolsTool(ObjectMapper objectMapper, ObjectProvider<AiToolManager> toolManager) {
        super(objectMapper);
        this.toolManager = toolManager;
    }

    @Override public String getName() { return "prepare_tools"; }

    @Override public LlmToolSpec getDefinition() {
        List<String> available = toolManager.getObject().getAllToolNames();
        return LlmToolSpec.of(getName(),
                "Prepare complete argument schemas only for actions absent from the current request. "
                        + "Do not prepare an action whose schema is already provided. "
                        + "Batch the missing exact names; preparation does not execute those actions.",
                new FunctionParameterSchema("object", Map.of("names", Map.of(
                        "type", "array", "minItems", 1, "maxItems", available.size(), "uniqueItems", true,
                        "items", Map.of("type", "string", "enum", available))), List.of("names")));
    }

    @Override protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "$", Set.of("names"));
            List<String> available = toolManager.getObject().getAllToolNames();
            JsonNode names = args.get("names");
            if (names == null || !names.isArray() || names.isEmpty() || names.size() > available.size()) {
                return errorJson("names must be a non-empty array of registered tool names.", "VALIDATION_ERROR", 400);
            }
            List<String> selected = new ArrayList<>();
            for (JsonNode name : names) {
                if (!name.isTextual() || !available.contains(name.textValue()) || selected.contains(name.textValue())) {
                    return errorJson("Copy distinct exact tool names from the available catalog.", "VALIDATION_ERROR", 400);
                }
                selected.add(name.textValue());
            }
            return objectMapper.writeValueAsString(Map.of(
                    "operation", "toolSchemasPrepared", "loadedTools", selected, "count", selected.size()));
        } catch (ArgParseException failure) {
            return failure.getErrorResponse();
        } catch (ArgValidationException failure) {
            return failure.getErrorResponse();
        } catch (Exception failure) {
            log.error("prepare_tools failed", failure);
            return errorJson("Failed to prepare the requested platform actions.", "INTERNAL_ERROR", 500);
        }
    }
}
