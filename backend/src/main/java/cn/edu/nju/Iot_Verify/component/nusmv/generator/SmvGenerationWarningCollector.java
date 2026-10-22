package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueReasonCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SmvGenerationWarningCollector {

    private final List<String> checkLogWarnings = new ArrayList<>();
    private final List<ModelGenerationIssueDto> generationIssues = new ArrayList<>();
    private final Set<String> disabledRuleKeys = new HashSet<>();
    private final Set<String> skippedSpecKeys = new HashSet<>();
    private final List<SmvGenerationContext.EmittedSpec> emittedSpecs = new ArrayList<>();

    public synchronized void disabledRule(RuleDto rule,
                                          ModelGenerationIssueReasonCode reasonCode,
                                          String reason) {
        String key = ruleKey(rule);
        if (disabledRuleKeys.add(key)) {
            String label = ruleLabel(rule);
            String safeReason = sanitizeReason(reason);
            checkLogWarnings.add("Generation warning [rule-disabled] " + label + ": " + safeReason);
            generationIssues.add(ModelGenerationIssueDto.builder()
                    .issueType("RULE_DISABLED")
                    .itemLabel(label)
                    .reasonCode(reasonCode)
                    .reason(safeReason)
                    .build());
        }
    }

    public synchronized void skippedSpec(SpecificationDto spec,
                                         ModelGenerationIssueReasonCode reasonCode,
                                         String reason) {
        String key = specKey(spec);
        if (skippedSpecKeys.add(key)) {
            String label = specificationLabel(spec);
            String safeReason = sanitizeReason(reason);
            checkLogWarnings.add("Generation warning [spec-skipped] " + label + ": " + safeReason);
            generationIssues.add(ModelGenerationIssueDto.builder()
                    .issueType("SPECIFICATION_SKIPPED")
                    .itemLabel(label)
                    .reasonCode(reasonCode)
                    .reason(safeReason)
                    .build());
        }
    }

    public synchronized void emittedSpec(SpecificationDto spec, String expression, String antecedentExpression) {
        emittedSpecs.add(new SmvGenerationContext.EmittedSpec(
                spec,
                specId(spec),
                sanitizeExpression(expression),
                antecedentExpression == null ? null : sanitizeExpression(antecedentExpression)
        ));
    }

    public synchronized List<String> checkLogWarnings() {
        return List.copyOf(checkLogWarnings);
    }

    public synchronized int disabledRuleCount() {
        return disabledRuleKeys.size();
    }

    public synchronized int skippedSpecCount() {
        return skippedSpecKeys.size();
    }

    public synchronized List<ModelGenerationIssueDto> generationIssues() {
        return List.copyOf(generationIssues);
    }

    public synchronized List<SmvGenerationContext.EmittedSpec> emittedSpecs() {
        return List.copyOf(emittedSpecs);
    }

    private String ruleKey(RuleDto rule) {
        if (rule == null) {
            return "rule:null";
        }
        if (rule.getId() != null) {
            return "rule:id:" + rule.getId();
        }
        return "rule:identity:" + System.identityHashCode(rule);
    }

    private String specKey(SpecificationDto spec) {
        if (spec == null) {
            return "spec:null";
        }
        if (spec.getId() != null && !spec.getId().isBlank()) {
            return "spec:id:" + spec.getId();
        }
        return "spec:identity:" + System.identityHashCode(spec);
    }

    private String specId(SpecificationDto spec) {
        if (spec != null && spec.getId() != null && !spec.getId().isBlank()) {
            return spec.getId();
        }
        return "unknown";
    }

    private String ruleLabel(RuleDto rule) {
        if (rule != null && rule.getRuleString() != null && !rule.getRuleString().isBlank()) {
            return rule.getRuleString().trim();
        }
        return "Automation rule";
    }

    private String specificationLabel(SpecificationDto spec) {
        if (spec != null && spec.getTemplateLabel() != null && !spec.getTemplateLabel().isBlank()) {
            return spec.getTemplateLabel().trim();
        }
        return "Formal specification";
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown reason";
        }
        return reason.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String sanitizeExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        return expression.replaceAll("[\\r\\n]+", " ").trim();
    }
}
