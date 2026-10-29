package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Formats a descriptive formula in user concepts; it never emits executable NuSMV. */
public final class SpecificationFormulaPreview {

    private SpecificationFormulaPreview() {
    }

    public static Context context(List<DeviceNodeDto> nodes, List<DeviceTemplateDto> templates) {
        Map<String, String> labelsById = new LinkedHashMap<>();
        for (DeviceNodeDto node : nodes == null ? List.<DeviceNodeDto>of() : nodes) {
            String id = node == null ? null : text(node.getId());
            if (id == null) {
                continue;
            }
            String label = text(node.getLabel());
            labelsById.put(id, label != null ? label : "Unknown device");
        }

        Map<String, DeviceTemplateDto> templatesByName = new LinkedHashMap<>();
        for (DeviceTemplateDto template : templates == null ? List.<DeviceTemplateDto>of() : templates) {
            String name = template == null ? null : text(template.getName());
            if (name != null) {
                templatesByName.putIfAbsent(name.toLowerCase(Locale.ROOT), template);
            }
        }

        return new Context(labelsById);
    }
    /** Builds a display context from the immutable model-boundary device/template snapshot. */
    public static Context modelContext(
            List<DeviceVerificationDto> devices,
            Map<String, DeviceTemplateDto.DeviceManifest> templateManifests) {
        Map<String, String> labelsById = new LinkedHashMap<>();
        Map<String, DeviceTemplateDto.DeviceManifest> manifestsByName = new LinkedHashMap<>();
        for (Map.Entry<String, DeviceTemplateDto.DeviceManifest> entry :
                templateManifests == null
                        ? Map.<String, DeviceTemplateDto.DeviceManifest>of().entrySet()
                        : templateManifests.entrySet()) {
            String name = text(entry.getKey());
            if (name != null && entry.getValue() != null) {
                manifestsByName.putIfAbsent(name.toLowerCase(Locale.ROOT), entry.getValue());
            }
        }

        for (DeviceVerificationDto device : devices == null
                ? List.<DeviceVerificationDto>of() : devices) {
            String id = device == null ? null : text(device.getVarName());
            if (id == null) {
                continue;
            }
            String label = text(device.getDeviceLabel());
            labelsById.put(id, label != null ? label : "Unknown device");
        }
        return new Context(labelsById);
    }

    public static String format(SpecificationDto spec, Context context) {
        Context safeContext = context == null ? Context.empty() : context;
        String a = conditionGroup(spec.getAConditions(), safeContext);
        String antecedent = conditionGroup(spec.getIfConditions(), safeContext);
        String consequence = conditionGroup(spec.getThenConditions(), safeContext);
        return switch (text(spec.getTemplateId()) == null ? "" : spec.getTemplateId().trim()) {
            case "1" -> "CTL AG(" + a + ")";
            case "2" -> "CTL AF(" + a + ")";
            case "3" -> "CTL AG NOT (" + a + ")";
            case "4" -> "CTL AG((" + antecedent + ") -> AX(" + consequence + "))";
            case "5" -> "CTL AG((" + antecedent + ") -> AF(" + consequence + "))";
            case "6" -> "LTL G((" + antecedent + ") -> F G(" + consequence + "))";
            case "7" -> "CTL AG NOT (" + a + " AND "
                    + untrustedSourcePreview(spec.getAConditions(), safeContext) + ")";
            default -> "Structured specification";
        };
    }

    public static String templateLabel(String templateId) {
        return switch (text(templateId) == null ? "" : templateId.trim()) {
            case "1" -> "Always";
            case "2" -> "Eventually";
            case "3" -> "Never";
            case "4" -> "Immediate response";
            case "5" -> "Eventual response";
            case "6" -> "Persistence";
            case "7" -> "Untrusted-source safety";
            default -> "Formal specification";
        };
    }

    private static String conditionGroup(List<SpecConditionDto> conditions, Context context) {
        List<String> parts = new ArrayList<>();
        for (SpecConditionDto condition : conditions == null ? List.<SpecConditionDto>of() : conditions) {
            if (condition != null) {
                parts.add(condition(condition, context));
            }
        }
        return parts.isEmpty() ? "TRUE" : String.join(" AND ", parts);
    }

    private static String condition(SpecConditionDto condition, Context context) {
        String target = target(condition, context);
        String relation = SmvRelationUtils.normalizeRelation(condition.getRelation());
        if (relation == null || relation.isBlank()) {
            relation = "=";
        }
        return target + " " + relation + " "
                + value(condition.getValue(), relation, condition.getTargetType());
    }

    private static String target(SpecConditionDto condition, Context context) {
        String device = quote(context.displayLabel(condition));
        String keyText = text(condition.getKey());
        String key = quote(keyText == null ? "property" : keyText);
        String targetType = text(condition.getTargetType());
        targetType = targetType == null ? "" : targetType.toLowerCase(Locale.ROOT);
        if ("state".equals(targetType)) {
            return device + ".state";
        }
        if ("api".equals(targetType)) {
            return "actionEvent(" + device + ", " + key + ")";
        }

        // A trust/privacy label is always the device's own (`<device>.trust_<key>`), whatever the value's
        // sharedness — asking about a label is not reading the value. Kept separate from the value target
        // below, which now depends on the question the author chose rather than on sharedness.
        String labelTarget = device + "." + key;
        if ("trust".equals(targetType)) {
            String source = "state".equalsIgnoreCase(condition.getPropertyScope())
                    ? device + ".current " + key + " state"
                    : labelTarget;
            return "controlSource(" + source + ")";
        }
        if ("privacy".equals(targetType)) {
            String source = "state".equalsIgnoreCase(condition.getPropertyScope())
                    ? device + ".current " + key + " state"
                    : labelTarget;
            return "sensitivity(" + source + ")";
        }
        // Everything that is not a `variable` keeps naming the device. Guarding this explicitly rather than
        // falling through: a `mode` condition carries no reading and never can, so letting it reach the
        // logic below rendered it `<unresolved>."FanMode"` — an unanswered question reported for a condition
        // that was never asked one, on every template, not just template 7.
        if (!"variable".equals(targetType)) {
            return labelTarget;
        }

        // Read the declared question, never re-derive it from sharedness. This preview is what a verdict
        // shows as the formula it answered, so inferring here reproduced the original defect one layer
        // later: a condition saved as `reported` on a shared value displayed as `Environment."temperature"`
        // while NuSMV had actually checked the device's own reading.
        String variableSource = condition.getVariableSource() == null
                ? null : condition.getVariableSource().trim().toLowerCase(java.util.Locale.ROOT);
        String variableTarget;
        if ("environment".equals(variableSource)) {
            variableTarget = "Environment." + key;
        } else if ("reported".equals(variableSource)) {
            variableTarget = device + "." + key;
        } else {
            // Never chosen: say so rather than picking a side and stating it as fact.
            variableTarget = "<unresolved>." + key;
        }
        return variableTarget;
    }

    /**
     * The subject of template 7's untrusted-label disjunct, matching what the generator resolves per target
     * type. A label is always emitted <em>per device</em> — there is no pool-level {@code trust_a_<key>} —
     * so no arm here may render {@code Environment.}. Emitted per device is not the same as scoped per
     * device: for a shared value the Environment Pool is the only writer, so every declaring device
     * carries the same label (see {@code docs/architecture/shared-value-semantics.md} §2):
     * <ul>
     *   <li>{@code variable} → {@code <device>."<key>"}, the device's own value label
     *       ({@code trust_<key>}). Reusing the <em>value</em> target here rendered an {@code environment}
     *       condition as {@code controlSource(Environment."<key>")}, naming a label the model never
     *       declares.</li>
     *   <li>{@code mode} → the mode's currently active state, since the generator emits
     *       {@code trust_<mode>_<value>}, a state-property label rather than a value label.</li>
     *   <li>{@code state} → {@code <device>.state}, its own target. The generator resolves this to one label
     *       per participating mode, disjoined; naming the state is the readable paraphrase of that set.</li>
     *   <li>{@code api} → the end state the action leads to, matching the generator resolving an API's
     *       untrusted source through its {@code EndState} label rather than the event itself.</li>
     * </ul>
     *
     * <p>{@code trust} and {@code privacy} are absent on purpose: admission refuses them as template-7 A
     * conditions ({@code NusmvRequestValidator.validateSafetyTemplateConditions}), because the control-source
     * label is what the template derives rather than something an author asserts. They previously fell
     * through to {@code target()}, which already returns {@code controlSource(...)}, so the caller wrapped it
     * twice into {@code controlSource(controlSource(...))}. Returning the plain device target instead keeps
     * the preview readable if one ever leaks past admission, rather than rendering a nonsense formula.
     */
    private static String untrustedLabelSource(SpecConditionDto condition, Context context) {
        String targetType = text(condition.getTargetType());
        targetType = targetType == null ? "" : targetType.toLowerCase(Locale.ROOT);
        String device = quote(context.displayLabel(condition));
        String keyText = text(condition.getKey());
        String key = quote(keyText == null ? "property" : keyText);
        if ("variable".equals(targetType)) {
            return device + "." + key;
        }
        if ("mode".equals(targetType)) {
            return device + ".current " + key + " state";
        }
        if ("api".equals(targetType)) {
            return device + ".state after " + key;
        }
        if ("trust".equals(targetType) || "privacy".equals(targetType)) {
            return device + "." + key;
        }
        return target(condition, context);
    }

    /**
     * The untrusted-source disjunct of template 7, naming the device whose label is actually checked.
     *
     * <p>Deliberately not {@code target(condition, context)}. A trust label is emitted per device — the
     * generator emits {@code <device>.trust_<key>} whatever the reading, and no pool-level
     * {@code trust_a_<key>} exists — so reusing the value target rendered an {@code environment} condition as
     * {@code controlSource(Environment."x")}, a label the model never declares. The preview must therefore
     * name the device whose label NuSMV actually checks. For a shared value that device is not a free
     * choice of subject: the pool writes every declaring device the same label, so the naming matters for
     * legibility, and for what is proved only when that instance is an attack compromise point.
     */
    private static String untrustedSourcePreview(List<SpecConditionDto> conditions, Context context) {
        List<String> sources = new ArrayList<>();
        for (SpecConditionDto condition : conditions == null ? List.<SpecConditionDto>of() : conditions) {
            if (condition != null) {
                sources.add("controlSource(" + untrustedLabelSource(condition, context) + ") = untrusted");
            }
        }
        if (sources.isEmpty()) {
            return "untrustedSource(TRUE)";
        }
        return sources.size() == 1 ? sources.get(0) : "(" + String.join(" OR ", sources) + ")";
    }

    private static String value(String rawValue, String relation, String targetType) {
        String value = rawValue == null ? "" : rawValue.trim();
        if ("in".equals(relation) || "not in".equals(relation)) {
            String separator = "state".equalsIgnoreCase(targetType) ? "[,|]" : "[,;|]";
            List<String> values = java.util.Arrays.stream(value.split(separator))
                    .map(String::trim)
                    .filter(item -> !item.isEmpty())
                    .map(SpecificationFormulaPreview::scalar)
                    .toList();
            return "{" + String.join(", ", values) + "}";
        }
        return scalar(value);
    }

    private static String scalar(String value) {
        if (value != null && value.matches("-?\\d+(?:\\.\\d+)?")) {
            return value;
        }
        if ("TRUE".equalsIgnoreCase(value) || "FALSE".equalsIgnoreCase(value)) {
            return value.toUpperCase(Locale.ROOT);
        }
        if ("trusted".equalsIgnoreCase(value) || "untrusted".equalsIgnoreCase(value)
                || "public".equalsIgnoreCase(value) || "private".equalsIgnoreCase(value)) {
            return value.toLowerCase(Locale.ROOT);
        }
        return quote(value);
    }

    private static String quote(String value) {
        String text = value == null ? "?" : value;
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Display labels only. It also carried a per-device set of shared variable names, whose sole purpose was
     * to infer whether a variable condition meant the pool value or the device's reading — the inference the
     * condition's own {@code variableSource} replaced. Keeping it meant walking every device's manifest on
     * every board read, spec write and verification result to populate a map nothing consulted.
     */
    public record Context(Map<String, String> labelsById) {
        private static Context empty() {
            return new Context(Map.of());
        }

        public String displayLabel(SpecConditionDto condition) {
            String id = condition == null ? null : text(condition.getDeviceId());
            if (id != null && text(labelsById.get(id)) != null) {
                return labelsById.get(id).trim();
            }
            String snapshot = condition == null ? null : text(condition.getDeviceLabel());
            return snapshot != null ? snapshot : "Unknown device";
        }

    }
}
