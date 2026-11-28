package cn.edu.nju.Iot_Verify.component.aitool.node;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.service.NodeService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SearchNodeTool extends AbstractAiTool {

    private final NodeService nodeService;

    public SearchNodeTool(NodeService nodeService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.nodeService = nodeService;
    }

    @Override
    public String getName() {
        return "search_devices";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();
        props.put("keyword", Map.of(
                "type", "string",
                "maxLength", 100,
                "description", "Case-insensitive substring matched against device name, template name "
                        + "(e.g. 'Air Conditioner') and device id. Leave empty to return all devices."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, Collections.emptyList()
        );

        return LlmToolSpec.of(getName(), "Search devices on the canvas, supports filtering by template type or name", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args = parseArgs(argsJson);
            requireOnlyFields(args, "arguments", Set.of("keyword"));
            String keyword = optionalTextArg(args, "keyword", "", 100);
            log.info("Executing search_devices, keyword: {}", keyword);
            List<DeviceNodeDto> devices = nodeService.searchNodes(userId, keyword);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("count", devices.size());
            response.put("devices", devices);
            if (devices.isEmpty()) {
                response.put("message", keyword.isEmpty()
                        ? "No devices are currently on the board."
                        : "No devices matched the requested name or template.");
            }
            return objectMapper.writeValueAsString(response);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (Exception e) {
            return readOnlyToolFailure(log, "search_devices", e,
                    "Search devices failed. Please retry.");
        }
    }

}
