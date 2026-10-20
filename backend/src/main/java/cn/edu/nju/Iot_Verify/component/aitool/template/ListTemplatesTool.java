package cn.edu.nju.Iot_Verify.component.aitool.template;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.RecommendationCapabilityView;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ListTemplatesTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;

    public ListTemplatesTool(BoardStorageService boardStorageService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
    }

    @Override
    public String getName() {
        return "list_templates";
    }

    @Override
    public LlmToolSpec getDefinition() {
        Map<String, Object> props = new HashMap<>();
        props.put("keyword", Map.of(
                "type", "string",
                "maxLength", 100,
                "description", "Optional keyword to filter templates by name. Leave empty to return all."
        ));
        props.put("detail", Map.of(
                "type", "boolean",
                "description", "If true, return semantic manifest details (modes, variables, APIs, transitions) without the UI-only Icon. Default false (summary only)."
        ));
        props.put("names", Map.of("type", "array", "minItems", 1,
                "maxItems", RequestLimits.MAX_TEMPLATES, "uniqueItems", true,
                "items", Map.of("type", "string", "minLength", 1, "maxLength", 100),
                "description", "Optional exact TEMPLATE names, not device labels or instance IDs. For current-scene analysis copy the unique devices[].templateName values from board_overview verbatim and use detail=true. deviceLabel identifies an instance and is not its templateName. Unknown names return the available template names so you can correct the request; do not treat a wrong-name lookup as missing platform ability. Do not combine with a non-empty keyword."));
        props.put("deviceIds", Map.of("type", "array", "minItems", 1,
                "maxItems", RequestLimits.MAX_DEVICES, "uniqueItems", true,
                "items", Map.of("type", "string", "minLength", 1, "maxLength", RequestLimits.MAX_IDENTIFIER_LENGTH),
                "description", "For saved-scene analysis, copy exact devices[].id values from board_overview. The server reads their actual templates from one current Board snapshot, deduplicating shared templates. Include intermediate devices and command/content sources needed by the chain; use detail=true. Do not combine with names or a non-empty keyword."));

        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", props, Collections.emptyList()
        );

        return LlmToolSpec.of(getName(), "List available device templates. Templates define device behavior: modes, variables, transitions, and APIs.", schema);
    }

    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("keyword", "detail", "names", "deviceIds"));
            String keyword = optionalTextArg(args, "keyword", "", 100);
            boolean detail = booleanArg(args, "detail", false);
            Set<String> names = new LinkedHashSet<>();
            if (args.has("names")) {
                JsonNode requested = args.get("names");
                if (args.has("deviceIds") || !keyword.isBlank() || !requested.isArray() || requested.isEmpty()
                        || requested.size() > RequestLimits.MAX_TEMPLATES) {
                    return errorJson("names must be a non-empty array of exact template names, without keyword.", "VALIDATION_ERROR", 400);
                }
                for (JsonNode name : requested) {
                    if (!name.isTextual() || name.textValue().isBlank() || name.textValue().length() > 100
                            || !names.add(name.textValue())) {
                        return errorJson("names must contain distinct, non-empty strings of at most 100 characters.", "VALIDATION_ERROR", 400);
                    }
                }
            }

            Set<String> deviceIds = new LinkedHashSet<>();
            if (args.has("deviceIds")) {
                JsonNode requested = args.get("deviceIds");
                if (!keyword.isBlank() || !requested.isArray() || requested.isEmpty()
                        || requested.size() > RequestLimits.MAX_DEVICES) {
                    return errorJson("deviceIds must be a non-empty array of exact Board device ids, without names or keyword.", "VALIDATION_ERROR", 400);
                }
                for (JsonNode id : requested) {
                    if (!id.isTextual() || id.textValue().isBlank() || id.textValue().length() > RequestLimits.MAX_IDENTIFIER_LENGTH
                            || !deviceIds.add(id.textValue())) {
                        return errorJson("deviceIds must contain distinct, non-empty Board ids.", "VALIDATION_ERROR", 400);
                    }
                }
            }
            List<DeviceTemplateDto> templates;
            if (deviceIds.isEmpty()) {
                templates = safeList(boardStorageService.getDeviceTemplates(userId));
            } else {
                BoardSemanticSnapshotDto board = boardStorageService.getSemanticSnapshot(userId);
                Map<String, DeviceNodeDto> nodes = board.nodes().stream()
                        .collect(Collectors.toMap(DeviceNodeDto::getId, node -> node));
                List<String> unknown = deviceIds.stream().filter(id -> !nodes.containsKey(id)).toList();
                if (!unknown.isEmpty()) {
                    return errorJson("Some requested devices are absent from the current Board. Read board_overview again before inspecting their behavior.",
                            "VALIDATION_ERROR", 422, Map.of("unknownDeviceIds", unknown));
                }
                deviceIds.forEach(id -> names.add(nodes.get(id).getTemplateName()));
                templates = board.deviceTemplates();
            }
            if (!names.isEmpty()) {
                Map<String, DeviceTemplateDto> available = RecommendationCapabilityView.indexTemplates(templates);
                Set<String> selected = new HashSet<>();
                List<String> unknown = new ArrayList<>();
                for (String name : names) {
                    DeviceTemplateDto template = RecommendationCapabilityView.resolveTemplate(available, name);
                    if (template == null) {
                        unknown.add(name);
                    } else {
                        selected.add(template.getName());
                    }
                }
                if (!unknown.isEmpty()) {
                    return errorJson("Some requested template names are unavailable. Use templateName, not deviceLabel; correct the names before reading behavior.",
                            "VALIDATION_ERROR", 422, Map.of("unknownTemplateNames", unknown,
                                    "availableTemplateNames", templates.stream().map(DeviceTemplateDto::getName).toList()));
                }
                templates = templates.stream().filter(template -> selected.contains(template.getName())).toList();
            }

            if (!keyword.isBlank()) {
                String kw = keyword.toLowerCase(Locale.ROOT);
                templates = templates.stream()
                        .filter(t -> t.getName() != null && t.getName().toLowerCase(Locale.ROOT).contains(kw))
                        .collect(Collectors.toList());
            }

            if (templates.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                        "message", "No templates found.",
                        "count", 0
                ));
            }

            if (detail) {
                List<Map<String, Object>> details = templates.stream()
                        .map(template -> TemplateToolPresenter.present(template, objectMapper))
                        .toList();
                return objectMapper.writeValueAsString(Map.of("count", details.size(), "templates", details,
                        "message", TemplateToolPresenter.SEMANTIC_NOTE));
            }

            // Summary mode: name + description + modes + API names
            List<Map<String, Object>> summaries = templates.stream().map(t -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", t.getId());
                summary.put("name", t.getName());
                if (t.getManifest() != null) {
                    summary.put("description", t.getManifest().getDescription());
                    summary.put("modes", t.getManifest().getModes());
                    if (t.getManifest().getApis() != null) {
                        summary.put("apis", t.getManifest().getApis().stream()
                                .map(DeviceTemplateDto.DeviceManifest.API::getName)
                                .toList());
                    }
                }
                return summary;
            }).toList();

            return objectMapper.writeValueAsString(Map.of("count", summaries.size(), "templates", summaries));
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("list_templates busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("list_templates business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("list_templates failed", e);
            return errorJson("Failed to list templates.", "INTERNAL_ERROR", 500);
        }
    }

}
