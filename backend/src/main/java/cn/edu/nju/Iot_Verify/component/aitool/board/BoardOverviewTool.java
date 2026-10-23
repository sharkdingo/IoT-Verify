package cn.edu.nju.Iot_Verify.component.aitool.board;

import cn.edu.nju.Iot_Verify.component.ai.model.LlmToolSpec;
import cn.edu.nju.Iot_Verify.component.aitool.AbstractAiTool;
import cn.edu.nju.Iot_Verify.component.aitool.rule.RuleToolPresenter;
import cn.edu.nju.Iot_Verify.component.aitool.spec.SpecificationToolPresenter;
import cn.edu.nju.Iot_Verify.component.aitool.template.TemplateToolPresenter;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.board.BoardSemanticSnapshotDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.BaseException;
import cn.edu.nju.Iot_Verify.exception.ServiceUnavailableException;
import cn.edu.nju.Iot_Verify.service.BoardStorageService;
import cn.edu.nju.Iot_Verify.util.FunctionParameterSchema;
import cn.edu.nju.Iot_Verify.util.SpecificationFormulaPreview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
public class BoardOverviewTool extends AbstractAiTool {

    private final BoardStorageService boardStorageService;

    public BoardOverviewTool(BoardStorageService boardStorageService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.boardStorageService = boardStorageService;
    }

    @Override
    public String getName() {
        return "board_overview";
    }

    @Override
    public LlmToolSpec getDefinition() {
        FunctionParameterSchema schema = new FunctionParameterSchema(
                "object", Map.of("detail", Map.of("type", "boolean", "description",
                        "Return complete active-device manifests (default true). Use false for counts or configured links only.")),
                Collections.emptyList()
        );

        return LlmToolSpec.of(getName(), "Get current device runtime values, the shared environment pool, rule-derived edges, rules and specifications. "
                + "directRuleTargets indexes directed condition-to-command dependencies; shared inputs do not connect their targets. "
                + "Includes full active-template manifests by default in templates, so analyze API effects, transitions and reading labels from this same snapshot. "
                + "Use detail=false for counts or configured links only; list_templates can read other templates. "
                + "Copy ruleId or specId and its readRef exactly for updates; rule order is command priority, "
                + "and templateId is not a specification id.", schema);
    }

    @Override
    protected String doExecute(Long userId, String argsJson) {
        try {
            JsonNode args;
            try {
                args = parseArgs(argsJson);
            } catch (ArgParseException e) {
                return e.getErrorResponse();
            }
            requireOnlyFields(args, "arguments", Set.of("detail"));
            boolean detail = booleanArg(args, "detail", true);

            BoardSemanticSnapshotDto snapshot = boardStorageService.getSemanticSnapshot(userId);
            List<DeviceNodeDto> nodes = snapshot.nodes();
            List<DeviceTemplateDto> templates = snapshot.deviceTemplates();
            List<BoardEnvironmentVariableDto> environmentVariables = snapshot.environmentVariables();
            List<RuleDto> rules = snapshot.rules();
            List<SpecificationDto> specs = snapshot.specifications();
            Map<String, BoardNodeRef> nodeRefs = indexNodeRefs(nodes);
            SpecificationFormulaPreview.Context presentationContext =
                    SpecificationToolPresenter.context(nodes, templates);
            List<Map<String, Object>> edgeSummaries = buildRuleDerivedEdgeSummaries(nodeRefs, rules);

            Map<String, Object> overview = new LinkedHashMap<>();
            overview.put("deviceCount", nodes.size());
            overview.put("environmentVariableCount", environmentVariables.size());
            overview.put("edgeCount", edgeSummaries.size());
            overview.put("ruleCount", rules.size());
            overview.put("specCount", specs.size());

            List<Map<String, Object>> deviceSummaries = nodes.stream()
                    .filter(Objects::nonNull)
                    .map(n -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", n.getId());
                d.put("label", n.getLabel());
                d.put("templateName", n.getTemplateName());
                d.put("state", n.getState());
                d.put("currentStateTrust", n.getCurrentStateTrust());
                d.put("currentStatePrivacy", n.getCurrentStatePrivacy());
                d.put("variables", n.getVariables() != null ? n.getVariables() : List.of());
                d.put("privacies", n.getPrivacies() != null ? n.getPrivacies() : List.of());
                return d;
            }).toList();
            overview.put("devices", deviceSummaries);
            overview.put("environmentVariables", environmentVariables);
            if (detail) {
                Set<String> activeNames = nodes.stream().filter(Objects::nonNull)
                        .map(DeviceNodeDto::getTemplateName).filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                List<DeviceTemplateDto> activeTemplates = templates.stream().filter(Objects::nonNull)
                        .filter(template -> activeNames.contains(template.getName()) && template.getManifest() != null).toList();
                Set<String> availableNames = activeTemplates.stream().map(DeviceTemplateDto::getName)
                        .collect(java.util.stream.Collectors.toSet());
                overview.put("templates", activeTemplates.stream()
                        .map(template -> TemplateToolPresenter.present(template, objectMapper)).toList());
                overview.put("unavailableBehaviorDeviceIds", nodes.stream().filter(Objects::nonNull)
                        .filter(node -> !availableNames.contains(node.getTemplateName()))
                        .map(DeviceNodeDto::getId).toList());
            }

            overview.put("edges", edgeSummaries);

            Map<String, Set<String>> directRuleTargets = new LinkedHashMap<>();
            Set<String> behaviorDeviceIds = new LinkedHashSet<>();
            for (Map<String, Object> edge : edgeSummaries) {
                String sourceId = (String) edge.get("from");
                String targetId = (String) edge.get("to");
                directRuleTargets.computeIfAbsent(sourceId, ignored -> new LinkedHashSet<>()).add(targetId);
                behaviorDeviceIds.add(sourceId);
                behaviorDeviceIds.add(targetId);
            }
            for (RuleDto rule : rules) {
                if (rule == null || rule.getCommand() == null) continue;
                RuleDto.Command command = rule.getCommand();
                if (hasText(command.getDeviceName())) {
                    behaviorDeviceIds.add(resolveNodeRef(command.getDeviceName(), nodeRefs).id());
                }
                if (hasText(command.getContentDevice())) {
                    behaviorDeviceIds.add(resolveNodeRef(command.getContentDevice(), nodeRefs).id());
                }
            }
            overview.put("directRuleTargets", directRuleTargets);
            overview.put("behaviorDeviceIds", behaviorDeviceIds);

            List<Map<String, Object>> ruleSummaries = rules.stream()
                    .filter(Objects::nonNull)
                    .map(rule -> RuleToolPresenter.present(userId, rule, nodes)).toList();
            overview.put("rules", ruleSummaries);

            List<Map<String, Object>> specSummaries = specs.stream()
                    .filter(Objects::nonNull)
                    .map(s -> SpecificationToolPresenter.present(userId, s, presentationContext)).toList();
            overview.put("specs", specSummaries);
            overview.put("message", "Runtime values alone are not complete device behavior. "
                    + (detail ? "templates contains full manifests for active devices from this same snapshot, with only UI icons omitted. "
                        + "unavailableBehaviorDeviceIds explicitly identifies missing definitions; do not invent their effects. "
                        : "detail=false omits manifests for this lightweight read; use detail=true for behavior analysis. ")
                    + "directRuleTargets is computed from the displayed rules: a condition source points to its command target, "
                    + "not to another condition source or a peer target. All conditions must hold; this index alone does not prove execution. "
                    + "For API preconditions/effects, transitions, variable trust/privacy or compromise declarations, "
                    + "use the included templates first; if a needed definition is absent, read list_templates with detail=true and deviceIds copied from devices[].id, including intermediate "
                    + "and content-source devices. An intentionally lightweight read is not missing platform data; an unavailable saved "
                    + "definition must be resolved before claims about its effects. behaviorDeviceIds includes all automation condition sources, command targets "
                    + "and content sources for a complete behavior audit. Counts and configured links do not need that extra read. "
                    + (detail ? TemplateToolPresenter.SEMANTIC_NOTE : ""));

            return objectMapper.writeValueAsString(overview);
        } catch (ArgValidationException e) {
            return e.getErrorResponse();
        } catch (ServiceUnavailableException e) {
            log.warn("board_overview busy: {}", e.getMessage());
            return errorJson(e.getMessage(), "SERVICE_UNAVAILABLE", 503);
        } catch (BaseException e) {
            log.warn("board_overview business error [{}]: {}", e.getCode(), e.getMessage());
            return errorJson(e.getMessage(), "BUSINESS_ERROR", e.getCode());
        } catch (Exception e) {
            log.error("board_overview failed", e);
            return errorJson("Failed to get board overview. Please retry.", "INTERNAL_ERROR", 500);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private List<Map<String, Object>> buildRuleDerivedEdgeSummaries(Map<String, BoardNodeRef> nodeRefs,
                                                                    List<RuleDto> rules) {
        List<Map<String, Object>> summaries = new ArrayList<>();

        for (RuleDto rule : rules) {
            if (rule == null || rule.getCommand() == null || !hasText(rule.getCommand().getDeviceName())) {
                continue;
            }

            RuleDto.Command command = rule.getCommand();
            BoardNodeRef target = resolveNodeRef(command.getDeviceName(), nodeRefs);
            List<RuleDto.Condition> conditions = rule.getConditions() == null ? List.of() : rule.getConditions();

            for (RuleDto.Condition condition : conditions) {
                if (condition == null || !hasText(condition.getDeviceName())) {
                    continue;
                }

                BoardNodeRef source = resolveNodeRef(condition.getDeviceName(), nodeRefs);
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("ruleId", rule.getId());
                summary.put("from", source.id());
                summary.put("to", target.id());
                summary.put("fromLabel", source.label());
                summary.put("toLabel", target.label());
                summary.put("ruleDescription", rule.getRuleString());
                summary.put("sourceType", condition.getTargetType());
                summary.put("sourceAttribute", safe(condition.getAttribute()));
                summary.put("relation", condition.getRelation() != null ? condition.getRelation() : "=");
                summary.put("value", safe(condition.getValue()));
                summary.put("targetAction", safe(command.getAction()));
                summaries.add(summary);
            }
        }

        return summaries;
    }

    private Map<String, BoardNodeRef> indexNodeRefs(List<DeviceNodeDto> nodes) {
        Map<String, BoardNodeRef> refs = new LinkedHashMap<>();
        for (DeviceNodeDto node : nodes) {
            if (node == null) {
                continue;
            }
            BoardNodeRef ref = new BoardNodeRef(safe(node.getId()), safe(node.getLabel()));
            if (hasText(node.getId())) {
                refs.putIfAbsent(node.getId(), ref);
            }
        }
        return refs;
    }

    private BoardNodeRef resolveNodeRef(String rawRef, Map<String, BoardNodeRef> refs) {
        String ref = safe(rawRef);
        return refs.getOrDefault(ref, new BoardNodeRef(ref, "Unknown device"));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record BoardNodeRef(String id, String label) {
    }
}
