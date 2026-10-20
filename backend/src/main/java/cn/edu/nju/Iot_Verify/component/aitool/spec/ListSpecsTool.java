package cn.edu.nju.Iot_Verify.component.aitool.spec;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ListSpecsTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;

    public ListSpecsTool(BoardStorageService boardStorageService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
    }

    @Override
    public String getName() {
        return "list_specs";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();
        props.put("keyword", Map.of(
                "type", "string",
                "maxLength", 100,
                "description", "Optional keyword to filter specifications by ID, template label, device, key, relation, or value. Leave empty to return all."
        ));

        FunctionParameterSchema schema = new FunctionParameterSchema("object", props, Collections.emptyList());

        return LlmToolSpec.of(getName(), "List formal specifications on the current board.", schema);
    }

    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("keyword"));
            String keyword = optionalTextArg(args, "keyword", "", 100);

            BoardSemanticSnapshotDto snapshot = boardStorageService.getSemanticSnapshot(userId);
            List<SpecificationDto> specs = snapshot.specifications();
            List<DeviceNodeDto> nodes = snapshot.nodes();
            List<DeviceTemplateDto> templates = snapshot.deviceTemplates();
            SpecificationFormulaPreview.Context presentationContext =
                    SpecificationToolPresenter.context(nodes, templates);
            if (!keyword.isEmpty()) {
                specs = specs.stream()
                        .filter(spec -> SpecificationToolPresenter.containsKeyword(
                                spec, presentationContext, keyword))
                        .toList();
            }

            if (specs.isEmpty()) {
                return readOnlySuccessJson(Map.of(
                        "message", "No specifications found on the board.",
                        "count", 0
                ), "No specifications found on the board.");
            }

            List<Map<String, Object>> presentedSpecs = specs.stream()
                    .map(spec -> SpecificationToolPresenter.present(spec, presentationContext))
                    .toList();
            return readOnlySuccessJson(Map.of(
                    "count", specs.size(),
                    "specs", presentedSpecs
            ), "Specifications loaded.");
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("list_specs busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("list_specs business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_specs failed", e);
            return errorJson("Failed to list specs.", "INTERNAL_ERROR", 500);
        }
    }

}
