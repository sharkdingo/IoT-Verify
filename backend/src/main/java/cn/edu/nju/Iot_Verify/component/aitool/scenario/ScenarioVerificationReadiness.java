package cn.edu.nju.Iot_Verify.component.aitool.scenario;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reports whether a scene can be submitted to the current verification entry points. */
public final class ScenarioVerificationReadiness {

    private ScenarioVerificationReadiness() {
    }

    public static Status assess(JsonNode scene) {
        return assess(scene, 0, "en", ScenarioObjectiveTargets.baseline());
    }

    public static Status assess(JsonNode scene, int filteredCount, String language) {
        return assess(scene, filteredCount, language, ScenarioObjectiveTargets.baseline());
    }

    public static Status assess(JsonNode scene,
                                int filteredCount,
                                String language,
                                ScenarioObjectiveTargets objectiveTargets) {
        boolean chinese = "zh-CN".equals(language);
        List<Issue> issues = new ArrayList<>();
        if (scene == null || !scene.path("devices").isArray() || scene.path("devices").isEmpty()) {
            issues.add(new Issue("NO_DEVICES", "Add at least one device before verification."));
        }
        if (scene == null || !scene.path("specs").isArray() || scene.path("specs").isEmpty()) {
            issues.add(new Issue("NO_SPECIFICATIONS", "Add at least one valid specification before verification."));
        }
        List<Issue> objectiveIssues = objectiveIssues(scene, objectiveTargets, chinese);
        return new Status(
                objectiveIssues.isEmpty() ? "COMPLETE" : "PARTIAL",
                objectiveIssues,
                issues.isEmpty(),
                List.copyOf(issues),
                semanticWarnings(scene, Math.max(0, filteredCount), chinese));
    }

    private static List<Issue> objectiveIssues(JsonNode scene,
                                               ScenarioObjectiveTargets targets,
                                               boolean chinese) {
        List<Issue> issues = new ArrayList<>();
        int deviceCount = arraySize(scene, "devices");
        int ruleCount = arraySize(scene, "rules");
        int specCount = arraySize(scene, "specs");
        addMinimumIssue(
                issues, deviceCount, targets.minDevices(), "NO_DEVICES", "INSUFFICIENT_DEVICES",
                chinese ? "设备" : "device", chinese);
        addMinimumIssue(
                issues, ruleCount, targets.minRules(), "NO_AUTOMATION_RULES",
                "INSUFFICIENT_AUTOMATION_RULES", chinese ? "自动化规则" : "automation rule", chinese);
        addMinimumIssue(
                issues, specCount, targets.minSpecs(), "NO_SPECIFICATIONS",
                "INSUFFICIENT_SPECIFICATIONS", chinese ? "形式化规约" : "formal specification", chinese);
        return List.copyOf(issues);
    }

    private static int arraySize(JsonNode scene, String field) {
        if (scene == null || !scene.path(field).isArray()) {
            return 0;
        }
        return scene.path(field).size();
    }

    private static void addMinimumIssue(List<Issue> issues,
                                        int actual,
                                        int minimum,
                                        String missingCode,
                                        String insufficientCode,
                                        String itemName,
                                        boolean chinese) {
        if (actual >= minimum) {
            return;
        }
        String code = actual == 0 ? missingCode : insufficientCode;
        String message;
        if (chinese) {
            message = String.format("草案包含 %d 个%s，低于明确要求的至少 %d 个。", actual, itemName, minimum);
        } else {
            String displayedItemName = actual == 1 ? itemName : itemName + "s";
            message = String.format(
                    "The draft contains %d %s, below the explicit minimum of %d.",
                    actual, displayedItemName, minimum);
        }
        issues.add(new Issue(code, message));
    }

    private static List<Issue> semanticWarnings(JsonNode scene, int filteredCount, boolean chinese) {
        List<Issue> warnings = new ArrayList<>();
        if (filteredCount > 0) {
            warnings.add(new Issue(
                    "FILTERED_CANDIDATES",
                    chinese
                            ? String.format("有 %d 个生成候选未通过校验。最终草案可能不再覆盖生成设计或用户需求的全部内容。", filteredCount)
                            : String.format("%d generated candidate(s) failed validation. The final draft may no longer cover every part of the generated design or user requirement.", filteredCount)));
        }
        if (scene == null || !scene.path("devices").isArray() || scene.path("devices").isEmpty()) {
            return List.copyOf(warnings);
        }

        JsonNode rules = scene.path("rules");
        if (!rules.isArray() || rules.isEmpty()) {
            warnings.add(new Issue(
                    "NO_AUTOMATION_RULES",
                    chinese
                            ? "草案没有自动化规则。请核对设备自身行为是否已满足需求，或补充联动规则。"
                            : "The draft has no automation rules. Check whether device behavior alone meets your needs, or add automation rules."));
        }

        Set<String> referencedDeviceIds = referencedDeviceIds(scene);
        List<String> unreferencedLabels = new ArrayList<>();
        for (JsonNode device : scene.path("devices")) {
            String id = device.path("id").asText("").trim();
            if (id.isEmpty() || referencedDeviceIds.contains(id)) {
                continue;
            }
            String label = device.path("label").asText("").trim();
            unreferencedLabels.add(label.isEmpty() ? id : label);
        }
        if (!unreferencedLabels.isEmpty()) {
            String labels = String.join(", ", unreferencedLabels);
            warnings.add(new Issue(
                    "UNREFERENCED_DEVICES",
                    chinese
                            ? String.format("有 %d 个保留设备未被任何保留规则或规约引用：%s。它们可能是有意独立存在，但草案未证明其参与了用户要求的行为。", unreferencedLabels.size(), labels)
                            : String.format("%d retained device(s) are not referenced by any retained rule or specification: %s. They may be intentionally independent, but the draft does not establish their role in the requested behavior.", unreferencedLabels.size(), labels)));
        }
        return List.copyOf(warnings);
    }

    private static Set<String> referencedDeviceIds(JsonNode scene) {
        Set<String> references = new LinkedHashSet<>();
        JsonNode rules = scene.path("rules");
        if (rules.isArray()) {
            for (JsonNode rule : rules) {
                collectText(references, rule, "toId");
                collectText(references, rule, "contentDevice");
                JsonNode sources = rule.path("sources");
                if (sources.isArray()) {
                    for (JsonNode source : sources) {
                        collectText(references, source, "fromId");
                    }
                }
            }
        }
        JsonNode specs = scene.path("specs");
        if (specs.isArray()) {
            for (JsonNode spec : specs) {
                collectConditionDeviceIds(references, spec.path("aConditions"));
                collectConditionDeviceIds(references, spec.path("ifConditions"));
                collectConditionDeviceIds(references, spec.path("thenConditions"));
            }
        }
        return references;
    }

    private static void collectConditionDeviceIds(Set<String> references, JsonNode conditions) {
        if (!conditions.isArray()) {
            return;
        }
        for (JsonNode condition : conditions) {
            collectText(references, condition, "deviceId");
        }
    }

    private static void collectText(Set<String> values, JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (!value.isEmpty()) {
            values.add(value);
        }
    }

    public record Status(
            String objectiveStatus,
            List<Issue> objectiveIssues,
            boolean verificationReady,
            List<Issue> readinessIssues,
            List<Issue> semanticWarnings) {
    }

    public record Issue(String code, String message) {
    }
}
