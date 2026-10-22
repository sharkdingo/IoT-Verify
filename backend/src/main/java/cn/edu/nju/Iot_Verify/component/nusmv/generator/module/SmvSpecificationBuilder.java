package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext;
import cn.edu.nju.Iot_Verify.dto.model.ModelGenerationIssueReasonCode;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceReferenceResolver;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SmvSpecificationBuilder {

    private static final String PERSISTENCE_TEMPLATE_ID = "6";
    private static final String CONDITION_SEPARATOR = " & ";

    /**
     * Build specifications with one spec negated (for ActFeedback §5 fix strategies).
     * Only the negated spec is emitted; all others are omitted to reduce NuSMV solve complexity.
     *
     * @param specs              full spec list
     * @param negatedSpecIndex   index of the spec to negate (¬ρ)
     * @param deviceSmvMap       device SMV data
     * @param enablePrivacy      privacy flag
     * @return SMV specification section containing only the negated spec
     */
    public String buildNegated(List<SpecificationDto> specs, int negatedSpecIndex,
                               Map<String, DeviceSmvData> deviceSmvMap, boolean enablePrivacy) {
        return buildNegated(specs, negatedSpecIndex,
                deviceSmvMap, enablePrivacy, SmvGenerationContext.noop());
    }

    public String buildNegated(List<SpecificationDto> specs, int negatedSpecIndex,
                               Map<String, DeviceSmvData> deviceSmvMap, boolean enablePrivacy,
                               SmvGenerationContext context) {
        if (specs == null || specs.isEmpty() || negatedSpecIndex < 0 || negatedSpecIndex >= specs.size()) {
            log.warn("buildNegated: invalid negatedSpecIndex={} for specs size={}", negatedSpecIndex,
                    specs == null ? 0 : specs.size());
            throw SmvGenerationException.specificationError(String.valueOf(negatedSpecIndex),
                    "the violated specification index is outside the submitted snapshot");
        }

        SpecificationDto spec = specs.get(negatedSpecIndex);
        if (spec == null) {
            throw SmvGenerationException.specificationError(String.valueOf(negatedSpecIndex),
                    "the violated specification snapshot is null");
        }

        StringBuilder content = new StringBuilder();
        content.append("\n-- Negated specification (index ").append(negatedSpecIndex).append(")");

        try {
            String negatedSpec = generateNegatedSpec(spec, deviceSmvMap);
            content.append("\n\t").append(negatedSpec);
            recordEmittedSpec(context, spec, negatedSpec);
        } catch (InvalidConditionException e) {
            log.warn("Failed to negate spec at index {}: {}", negatedSpecIndex, e.getMessage());
            throw SmvGenerationException.specificationError(
                    spec.getId() != null ? spec.getId() : String.valueOf(negatedSpecIndex),
                    e.getMessage() != null ? e.getMessage() : "negation failed");
        }

        return content.toString();
    }

    /**
     * Generate the negated form of a specification.
     * Negation mapping (from Salus paper §5):
     * <ul>
     *   <li>Template 1 (always):      AG(p)           → CTLSPEC EF(!(p))</li>
     *   <li>Template 2 (eventually):   AF(p)           → CTLSPEC EG(!(p))</li>
     *   <li>Template 3 (never):        AG(!(p))        → CTLSPEC EF(p)</li>
     *   <li>Template 4 (immediate):    AG(a→AX(b))     → CTLSPEC EF((a) & EX(!(b)))</li>
     *   <li>Template 5 (response):     AG(a→AF(b))     → CTLSPEC EF((a) & EG(!(b)))</li>
     *   <li>Template 6 (persistence):  G(a→FG(b)) [LTL] → LTLSPEC F((a) & GF(!(b)))</li>
     *   <li>Template 7 (safety):       AG !(body)      → CTLSPEC EF(body)  (body includes trust/attack expressions)</li>
     * </ul>
     */
    String generateNegatedSpec(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        validateTemplateShape(spec);
        String templateId = spec.getTemplateId();
        if (PERSISTENCE_TEMPLATE_ID.equals(templateId)) {
            return generateNegatedLtlSpec(spec, deviceSmvMap);
        }
        return generateNegatedCtlSpec(spec, deviceSmvMap);
    }

    private String generateNegatedCtlSpec(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        String templateId = spec.getTemplateId();
        String aPart = buildConditionGroup(spec.getAConditions(), deviceSmvMap);
        String ifPart = buildConditionGroup(spec.getIfConditions(), deviceSmvMap);
        String thenPart = buildConditionGroup(spec.getThenConditions(), deviceSmvMap);

        switch (templateId) {
            case "1": // always: AG(A) → EF(!A)
                return "CTLSPEC EF(!(" + aPart + "))";
            case "2": // eventually: AF(A) → EG(!A)
                return "CTLSPEC EG(!(" + aPart + "))";
            case "3": // never: AG(!A) → EF(A)
                return "CTLSPEC EF(" + aPart + ")";
            case "4": // immediate: AG(IF→AX(THEN)) → EF(IF & EX(!THEN))
                return "CTLSPEC EF((" + ifPart + ") & EX(!(" + thenPart + ")))";
            case "5": // response: AG(IF→AF(THEN)) → EF(IF & EG(!THEN))
                return "CTLSPEC EF((" + ifPart + ") & EG(!(" + thenPart + ")))";
            case "7": // safety: AG !(body) → EF(body), where body includes trust/attack expressions
                return "CTLSPEC EF(" + buildSafetyBody(spec, deviceSmvMap) + ")";
            default:
                // Symmetric with generateCtlSpec: fail closed instead of guessing AG(A).
                throw new InvalidConditionException("unsupported templateId '" + templateId
                        + "'; allowed values are 1, 2, 3, 4, 5, 6, 7");
        }
    }

    private String generateNegatedLtlSpec(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        // Template 6 (persistence): G(IF→FG(THEN)) → F(IF & GF(!THEN))
        String ifPart = buildConditionGroup(spec.getIfConditions(), deviceSmvMap);
        String thenPart = buildConditionGroup(spec.getThenConditions(), deviceSmvMap);
        return "LTLSPEC F((" + ifPart + ") & G F(!(" + thenPart + ")))";
    }

    public String build(java.util.List<SpecificationDto> specs,
                       Map<String, DeviceSmvData> deviceSmvMap, boolean enablePrivacy) {
        return build(specs, deviceSmvMap, enablePrivacy, SmvGenerationContext.noop());
    }

    public String build(java.util.List<SpecificationDto> specs,
                       Map<String, DeviceSmvData> deviceSmvMap, boolean enablePrivacy,
                       SmvGenerationContext context) {
        StringBuilder content = new StringBuilder();

        if (specs == null || specs.isEmpty()) {
            log.debug("No specifications provided - skipping SPECIFICATION section");
            return content.toString();
        }

        content.append("\n-- Specifications");

        int generatedSpecs = 0;
        for (SpecificationDto spec : specs) {
            if (spec == null) continue;
            if ((spec.getAConditions() == null || spec.getAConditions().isEmpty()) &&
                (spec.getIfConditions() == null || spec.getIfConditions().isEmpty())) {
                recordSkippedSpec(context, spec,
                        ModelGenerationIssueReasonCode.SPEC_NO_CHECKABLE_CONDITIONS,
                        "The specification has no checkable conditions.");
                continue;
            }

            // Defense-in-depth: privacy specs should have been caught upstream by SmvGenerator.validateNoPrivacySpecs
            if (!enablePrivacy && hasAnyPrivacyCondition(spec)) {
                log.warn("Privacy spec '{}' encountered with enablePrivacy=false — should have been caught upstream, skipping", spec.getId());
                recordSkippedSpec(context, spec,
                        ModelGenerationIssueReasonCode.SPEC_PRIVACY_MODELING_DISABLED,
                        "Privacy-state modeling was not enabled for this run.");
                content.append("\n\t-- specification skipped: privacy-state modeling disabled");
                continue;
            }

            try {
                String specString = generateSpecString(spec, deviceSmvMap);
                String antecedentExpression = switch (spec.getTemplateId()) {
                    // NuSMV checks a property at every initial state. Negating EF means a false
                    // verdict witnesses reachability from any initial state, including attack choices.
                    case "4", "5", "6" -> "CTLSPEC !EF("
                            + buildConditionGroup(spec.getIfConditions(), deviceSmvMap) + ")";
                    default -> null;
                };
                content.append("\n\t").append(specString);
                if (antecedentExpression != null) {
                    content.append("\n\t").append(antecedentExpression);
                }
                if (context != null) {
                    context.emittedSpec(spec, specString, antecedentExpression);
                }
                generatedSpecs++;
            } catch (InvalidConditionException e) {
                // Invalid condition makes this spec invalid; skip and log warning.
                log.warn("Skipping spec '{}': {}", spec.getId(), e.getMessage());
                TranslationFailure failure = userFacingTranslationFailure(e);
                recordSkippedSpec(context, spec, failure.reasonCode(), failure.reason());
                String safeMsg = e.getMessage() != null ? e.getMessage().replaceAll("[\\r\\n]+", " ") : "unknown";
                content.append("\n\t-- specification skipped: ").append(safeMsg);
            }
        }

        log.debug("Generated {} specifications", generatedSpecs);
        return content.toString();
    }

    private TranslationFailure userFacingTranslationFailure(InvalidConditionException exception) {
        String detail = exception != null && exception.getMessage() != null
                ? exception.getMessage().toLowerCase(Locale.ROOT)
                : "";
        // First, because this classifier matches on substrings and the message below necessarily talks
        // about values. Left to fall through, it matched the `contains("value")` arm and told the user to
        // check a value domain that was never the problem.
        // Matches the *unanswered* case only, by the phrase unique to it. Matching bare "variablesource"
        // also caught the device-local rejection below, whose message ends "use variableSource=reported" —
        // telling a user who DID choose to go and choose. Both arms must precede the `contains("value")` one
        // further down, since either message necessarily talks about values.
        if (detail.contains("does not say which value it means")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_VARIABLE_SOURCE_REQUIRED,
                    "A condition does not say whether it checks the actual value in the home or what the "
                            + "device reported. Edit the specification and choose one.");
        }
        if (detail.contains("no value in the home to compare")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_INVALID_VALUE,
                    "A condition asks about the value in the home for a property that exists only inside "
                            + "one device, so there is nothing in the home to compare against.");
        }
        if (detail.contains("unsupported relation") || detail.contains("only supports")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_UNSUPPORTED_RELATION,
                    "A condition uses a comparison that is not supported for the selected field.");
        }
        if (detail.contains("ambiguous across modes") || detail.contains("multiple candidates")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_AMBIGUOUS_STATE,
                    "A selected state matches more than one device mode; choose an unambiguous mode and state.");
        }
        if (detail.contains("property") || detail.contains("state-label")
                || detail.contains("trust") || detail.contains("privacy")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_UNDECLARED_SECURITY_PROPERTY,
                    "A trust or privacy condition references a property that the device type does not define.");
        }
        if (detail.contains("not found") && detail.contains("device")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_UNKNOWN_DEVICE,
                    "A referenced device is missing or no longer matches this specification.");
        }
        if (detail.contains("template")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_TEMPLATE_SHAPE_MISMATCH,
                    "The selected specification template does not match its configured condition groups.");
        }
        if (detail.contains("value") || detail.contains("candidate")) {
            return translationFailure(
                    ModelGenerationIssueReasonCode.SPEC_INVALID_VALUE,
                    "A condition contains a value that is not valid for the selected device field.");
        }
        return translationFailure(
                ModelGenerationIssueReasonCode.SPEC_UNSUPPORTED_CONDITION,
                "One or more conditions are not supported by the selected device types.");
    }

    private TranslationFailure translationFailure(ModelGenerationIssueReasonCode reasonCode,
                                                  String reason) {
        return new TranslationFailure(reasonCode, reason);
    }

    private void recordSkippedSpec(SmvGenerationContext context,
                                   SpecificationDto spec,
                                   ModelGenerationIssueReasonCode reasonCode,
                                   String reason) {
        if (context != null) {
            context.skippedSpec(spec, reasonCode, reason);
        }
    }

    private record TranslationFailure(ModelGenerationIssueReasonCode reasonCode, String reason) {
    }

    private void recordEmittedSpec(SmvGenerationContext context, SpecificationDto spec, String expression) {
        if (context != null) {
            context.emittedSpec(spec, expression);
        }
    }

    private boolean hasAnyPrivacyCondition(SpecificationDto spec) {
        return hasPrivacyConditions(spec.getAConditions())
                || hasPrivacyConditions(spec.getIfConditions())
                || hasPrivacyConditions(spec.getThenConditions());
    }

    /**
     * Canonicalizes a spec condition's target type for comparison.
     *
     * <p>Two of the four sites in this class trimmed before lowercasing and two did not, so a target
     * type with surrounding whitespace was recognized by the safety-shape check and then fell through
     * the expression builder's dispatch to an unrelated branch. {@code Locale.ROOT} is required
     * because a Turkish default locale maps {@code I} to {@code ı}, which matches no keyword here.
     */
    private static String normalizeSpecTargetType(String targetType) {
        return targetType == null ? null : targetType.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasPrivacyConditions(List<SpecConditionDto> conditions) {
        if (conditions == null) return false;
        return conditions.stream().anyMatch(c ->
                c != null && "privacy".equals(normalizeSpecTargetType(c.getTargetType())));
    }

    /**
     * Generate one specification string (deviceSmvMap is required to resolve trust/privacy variables).
     */
    public String generateSpecString(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        validateTemplateShape(spec);
        String templateId = spec.getTemplateId();
        if (PERSISTENCE_TEMPLATE_ID.equals(templateId)) {
            return generateLtlSpec(spec, deviceSmvMap);
        }
        return generateCtlSpec(spec, deviceSmvMap);
    }

    private String generateLtlSpec(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        String ifPart = buildConditionGroup(spec.getIfConditions(), deviceSmvMap);
        String thenPart = buildConditionGroup(spec.getThenConditions(), deviceSmvMap);

        return "LTLSPEC G((" + ifPart + ") -> F G(" + thenPart + "))";
    }

    private String generateCtlSpec(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        String templateId = spec.getTemplateId();
        String aPart = buildConditionGroup(spec.getAConditions(), deviceSmvMap);
        String ifPart = buildConditionGroup(spec.getIfConditions(), deviceSmvMap);
        String thenPart = buildConditionGroup(spec.getThenConditions(), deviceSmvMap);

        switch (templateId) {
            case "1": // always: AG(A)
                return "CTLSPEC AG(" + aPart + ")";
            case "2": // eventually
                return "CTLSPEC AF(" + aPart + ")";
            case "3": // never
                return "CTLSPEC AG !(" + aPart + ")";
            case "4": // immediate
                return "CTLSPEC AG((" + ifPart + ") -> AX(" + thenPart + "))";
            case "5": // response
                return "CTLSPEC AG((" + ifPart + ") -> AF(" + thenPart + "))";
            case "7": // safety: untrusted -> !A
                return buildSafetySpec(spec, deviceSmvMap);
            default:
                throw new InvalidConditionException("unsupported templateId '" + templateId
                        + "'; allowed values are 1, 2, 3, 4, 5, 6, 7");
        }
    }

    private String genConditionSpec(SpecConditionDto cond, Map<String, DeviceSmvData> deviceSmvMap) {
        if (cond == null || !hasDeviceRef(cond)) {
            throw new InvalidConditionException("condition deviceId is null");
        }

        DeviceSmvData smv = findDeviceSmvDataForSpec(cond, deviceSmvMap);
        if (smv == null) {
            throw new InvalidConditionException("device '" + describeDeviceRef(cond) + "' not found in deviceSmvMap");
        }
        String varName = smv.getVarName();
        String targetType = normalizeSpecTargetType(cond.getTargetType());

        if ("api".equals(targetType)) {
            if (cond.getKey() == null) {
                throw new InvalidConditionException("api condition key is null for device " + cond.getDeviceId());
            }
            String apiSignal = DeviceSmvDataFactory.formatApiSignalName(cond.getKey());
            if (apiSignal == null) {
                throw new InvalidConditionException("api signal name resolved to null for key '" + cond.getKey() + "' on device " + cond.getDeviceId());
            }
            // Validate API exists and is a signal.
            validateApiSignalExists(smv, cond.getKey(), cond.getDeviceId());
            // API signals are boolean; only allow =, !=, IN, NOT_IN.
            validateApiBooleanRelation(cond);
            return buildSimpleCondition(varName + "." + apiSignal, cond);
        }

        if ("state".equals(targetType)) {
            return buildStateCondition(varName, smv, cond);
        }

        if ("mode".equals(targetType)) {
            return buildModeCondition(varName, smv, cond);
        }

        if ("variable".equals(targetType)) {
            return buildVariableCondition(varName, smv, cond);
        }

        if ("trust".equals(targetType)) {
            return buildPropertyCondition(varName, smv, cond, "trust_");
        }

        if ("privacy".equals(targetType)) {
            return buildPropertyCondition(varName, smv, cond, "privacy_");
        }

        // Unknown targetType: fail-closed, do not guess concatenation.
        throw new InvalidConditionException("unsupported targetType '" + targetType
                + "' for device " + cond.getDeviceId() + "; allowed: state, mode, variable, api, trust, privacy");
    }
    
    private String buildConditionGroup(List<SpecConditionDto> conditions, Map<String, DeviceSmvData> deviceSmvMap) {
        if (conditions == null || conditions.isEmpty()) {
            return "TRUE";
        }
        List<String> parts = new ArrayList<>();
        for (SpecConditionDto cond : conditions) {
            parts.add(genConditionSpec(cond, deviceSmvMap));
        }
        return String.join(CONDITION_SEPARATOR, parts);
    }

    private DeviceSmvData findDeviceSmvDataForSpec(SpecConditionDto cond, Map<String, DeviceSmvData> deviceSmvMap) {
        if (cond == null || deviceSmvMap == null) {
            return null;
        }
        try {
            return DeviceReferenceResolver.resolve(cond.getDeviceId(), deviceSmvMap);
        } catch (SmvGenerationException e) {
            if (SmvGenerationException.ErrorCategories.AMBIGUOUS_DEVICE_REFERENCE.equals(e.getErrorCategory())) {
                throw e;
            }
            throw new InvalidConditionException(e.getMessage());
        }
    }

    private void validateTemplateShape(SpecificationDto spec) {
        if (spec == null) {
            throw new InvalidConditionException("specification is null");
        }
        String templateId = spec.getTemplateId();
        int aCount = sizeOf(spec.getAConditions());
        int ifCount = sizeOf(spec.getIfConditions());
        int thenCount = sizeOf(spec.getThenConditions());
        if ("1".equals(templateId) || "2".equals(templateId) || "3".equals(templateId) || "7".equals(templateId)) {
            if (aCount == 0) {
                throw new InvalidConditionException("template " + templateId
                        + " requires at least one A condition");
            }
            if (ifCount > 0 || thenCount > 0) {
                throw new InvalidConditionException("template " + templateId
                        + " uses A conditions only");
            }
            if ("7".equals(templateId)) {
                validateSafetyConditions(spec.getAConditions());
            }
            return;
        }
        if ("4".equals(templateId) || "5".equals(templateId) || "6".equals(templateId)) {
            if (aCount > 0) {
                throw new InvalidConditionException("template " + templateId
                        + " uses IF/THEN conditions only");
            }
            if (ifCount == 0 || thenCount == 0) {
                throw new InvalidConditionException("template " + templateId
                        + " requires both IF and THEN conditions");
            }
        }
    }

    private void validateSafetyConditions(List<SpecConditionDto> conditions) {
        if (conditions == null) {
            return;
        }
        for (SpecConditionDto condition : conditions) {
            if (condition == null) {
                continue;
            }
            String normalized = normalizeSpecTargetType(condition.getTargetType());
            String targetType = normalized == null ? "" : normalized;
            if ("trust".equals(targetType) || "privacy".equals(targetType)) {
                throw new InvalidConditionException(
                        "safety conditions must describe a protected event or value, not an explicit trust/privacy predicate");
            }
            String relation = normalizeRelation(condition.getRelation());
            if (("state".equals(targetType) || "mode".equals(targetType)) && !"=".equals(relation)) {
                throw new InvalidConditionException(
                        "safety state and mode conditions require '=' so the matching source label is unambiguous");
            }
            if ("api".equals(targetType)
                    && (!"=".equals(relation) || !"TRUE".equalsIgnoreCase(condition.getValue()))) {
                throw new InvalidConditionException("safety API conditions must use '= TRUE'");
            }
        }
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private String describeDeviceRef(SpecConditionDto cond) {
        if (cond == null) return "";
        return cond.getDeviceId() != null ? cond.getDeviceId() : "";
    }

    private boolean hasDeviceRef(SpecConditionDto cond) {
        return cond != null && cond.getDeviceId() != null && !cond.getDeviceId().isBlank();
    }

    private String buildStateCondition(String varName, DeviceSmvData smv, SpecConditionDto cond) {
        String relation = normalizeRelation(cond.getRelation());
        String value = cond.getValue();
        if (relation == null) {
            throw new InvalidConditionException("state condition relation is null for device " + cond.getDeviceId());
        }
        if (!isSupportedRelation(relation)) {
            throw new InvalidConditionException("unsupported relation '" + cond.getRelation()
                    + "' for state condition on device " + cond.getDeviceId());
        }
        if (value == null || value.isBlank()) {
            throw new InvalidConditionException("state condition value is null/blank for device " + cond.getDeviceId());
        }
        if (smv == null || smv.getModes() == null || smv.getModes().isEmpty()) {
            throw new InvalidConditionException("device '" + varName + "' has no modes, cannot generate state condition");
        }
        if (!"=".equals(relation)
                && !"!=".equals(relation)
                && !"in".equals(relation)
                && !"not in".equals(relation)) {
            throw new InvalidConditionException("state condition only supports =, !=, IN, NOT_IN relations, got '"
                    + cond.getRelation() + "' for device " + cond.getDeviceId());
        }

        List<String> rawCandidates = splitStateConditionCandidates(value, relation, smv);
        if (rawCandidates.isEmpty()) {
            throw new InvalidConditionException("state condition has empty candidate list for device " + cond.getDeviceId());
        }

        List<String> tupleExprs = new ArrayList<>();
        for (String rawCandidate : rawCandidates) {
            Map<String, String> tuple = resolveStateConditionTuple(smv, rawCandidate, cond.getDeviceId());
            tupleExprs.add(buildStateTupleExpr(varName, smv, tuple));
        }

        if ("=".equals(relation)) {
            if (tupleExprs.size() != 1) {
                throw new InvalidConditionException("state '=' condition resolved to multiple candidates for device "
                        + cond.getDeviceId());
            }
            return tupleExprs.get(0);
        }
        if ("!=".equals(relation)) {
            if (tupleExprs.size() != 1) {
                throw new InvalidConditionException("state '!=' condition resolved to multiple candidates for device "
                        + cond.getDeviceId());
            }
            return "!(" + tupleExprs.get(0) + ")";
        }
        if ("in".equals(relation)) {
            return tupleExprs.size() == 1 ? tupleExprs.get(0) : "(" + String.join(" | ", tupleExprs) + ")";
        }
        List<String> negated = new ArrayList<>();
        for (String tupleExpr : tupleExprs) {
            negated.add("!(" + tupleExpr + ")");
        }
        return negated.size() == 1 ? negated.get(0) : "(" + String.join(" & ", negated) + ")";
    }

    private List<String> splitStateConditionCandidates(String value, String relation, DeviceSmvData smv) {
        if (value == null) {
            return List.of();
        }
        if ("in".equals(relation) || "not in".equals(relation)) {
            String splitRegex = (smv.getModes() != null && smv.getModes().size() > 1) ? "[,|]" : "[,;|]";
            return Arrays.stream(value.split(splitRegex))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .collect(Collectors.toList());
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed);
    }

    private Map<String, String> resolveStateConditionTuple(DeviceSmvData smv, String rawCandidate, String deviceId) {
        if (rawCandidate == null || rawCandidate.isBlank()) {
            throw new InvalidConditionException("state candidate is blank for device " + deviceId);
        }
        List<String> modes = smv.getModes();
        String candidate = rawCandidate.trim();

        if (candidate.contains(";")) {
            String[] segments = candidate.split(";", -1);
            if (segments.length != modes.size()) {
                throw new InvalidConditionException("state tuple '" + candidate + "' segment count (" + segments.length
                        + ") does not match mode count (" + modes.size() + ") for device " + deviceId);
            }
            Map<String, String> tuple = new LinkedHashMap<>();
            for (int i = 0; i < modes.size(); i++) {
                if (DeviceSmvDataFactory.isWildcardStateSegment(segments[i])) {
                    continue;
                }
                String cleanSeg = DeviceSmvDataFactory.cleanStateName(segments[i]);
                if (cleanSeg == null || cleanSeg.isBlank()) {
                    continue;
                }
                String mode = modes.get(i);
                List<String> legalStates = smv.getModeStates().get(mode);
                if (legalStates == null || !legalStates.contains(cleanSeg)) {
                    throw new InvalidConditionException("state tuple '" + candidate + "' has illegal segment '" + cleanSeg
                            + "' for mode '" + mode + "' on device " + deviceId);
                }
                tuple.put(mode, cleanSeg);
            }
            if (tuple.isEmpty()) {
                throw new InvalidConditionException("state tuple '" + candidate + "' has no concrete mode segment for device " + deviceId);
            }
            return tuple;
        }

        String cleanState = DeviceSmvDataFactory.cleanStateName(candidate);
        if (cleanState == null || cleanState.isBlank()) {
            throw new InvalidConditionException("state value is blank after normalization for device " + deviceId);
        }

        if (modes.size() == 1) {
            String mode = modes.get(0);
            List<String> legalStates = smv.getModeStates().get(mode);
            if (legalStates == null || !legalStates.contains(cleanState)) {
                throw new InvalidConditionException("state value '" + cleanState + "' not legal in mode '" + mode
                        + "' for device " + deviceId);
            }
            Map<String, String> tuple = new LinkedHashMap<>();
            tuple.put(mode, cleanState);
            return tuple;
        }

        List<String> matchedModes = new ArrayList<>();
        for (String mode : modes) {
            List<String> legalStates = smv.getModeStates().get(mode);
            if (legalStates != null && legalStates.contains(cleanState)) {
                matchedModes.add(mode);
            }
        }
        if (matchedModes.isEmpty()) {
            throw new InvalidConditionException("state value '" + cleanState + "' not found in any mode on device " + deviceId);
        }
        if (matchedModes.size() > 1) {
            throw new InvalidConditionException("state value '" + cleanState + "' is ambiguous across modes "
                    + matchedModes + " on device " + deviceId);
        }
        Map<String, String> tuple = new LinkedHashMap<>();
        tuple.put(matchedModes.get(0), cleanState);
        return tuple;
    }

    private String buildStateTupleExpr(String varName, DeviceSmvData smv, Map<String, String> tuple) {
        List<String> parts = new ArrayList<>();
        for (String mode : smv.getModes()) {
            String state = tuple.get(mode);
            if (state == null || state.isBlank()) {
                continue;
            }
            parts.add(varName + "." + mode + "=" + state);
        }
        if (parts.isEmpty()) {
            throw new InvalidConditionException("state tuple resolved to empty expression on device " + varName);
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(" & ", parts) + ")";
    }

    private String buildModeCondition(String varName, DeviceSmvData smv, SpecConditionDto cond) {
        String mode = resolveModeName(smv, cond.getKey(), cond.getDeviceId());
        String relation = normalizeRelation(cond.getRelation());
        String value = cond.getValue();
        if (relation == null) {
            throw new InvalidConditionException("mode condition relation is null for device " + cond.getDeviceId());
        }
        if (!"=".equals(relation)
                && !"!=".equals(relation)
                && !"in".equals(relation)
                && !"not in".equals(relation)) {
            throw new InvalidConditionException("mode condition only supports =, !=, IN, NOT_IN relations, got '"
                    + cond.getRelation() + "' for device " + cond.getDeviceId());
        }
        if (value == null || value.isBlank()) {
            throw new InvalidConditionException("mode condition value is null/blank for device " + cond.getDeviceId());
        }

        List<String> cleanedValues = splitModeConditionCandidates(value, relation).stream()
                .map(DeviceSmvDataFactory::cleanStateName)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.toList());
        if (cleanedValues.isEmpty()) {
            throw new InvalidConditionException("mode condition has empty candidate list for device " + cond.getDeviceId());
        }
        List<String> legalValues = smv.getModeStates() != null ? smv.getModeStates().get(mode) : null;
        if (legalValues == null || legalValues.isEmpty()) {
            throw new InvalidConditionException("mode '" + mode + "' has no legal values on device " + cond.getDeviceId());
        }
        for (String cleanedValue : cleanedValues) {
            if (!legalValues.contains(cleanedValue)) {
                throw new InvalidConditionException("mode value '" + cleanedValue + "' is not legal for mode '" + mode
                        + "' on device " + cond.getDeviceId());
            }
        }
        if (("=".equals(relation) || "!=".equals(relation)) && cleanedValues.size() != 1) {
            throw new InvalidConditionException("mode relation '" + relation + "' requires exactly one value on device "
                    + cond.getDeviceId());
        }

        return buildRelationExpr(varName + "." + mode, relation, String.join(",", cleanedValues));
    }

    private List<String> splitModeConditionCandidates(String value, String relation) {
        if (value == null) {
            return List.of();
        }
        if ("in".equals(relation) || "not in".equals(relation)) {
            return Arrays.stream(value.split("[,;|]"))
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .collect(Collectors.toList());
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed);
    }

    private String resolveModeName(DeviceSmvData smv, String key, String deviceId) {
        if (smv == null || smv.getModes() == null || smv.getModes().isEmpty()) {
            throw new InvalidConditionException("device '" + deviceId + "' has no modes, cannot generate mode condition");
        }
        if (key == null || key.isBlank()) {
            throw new InvalidConditionException("mode condition key is null/blank for device " + deviceId);
        }
        String trimmed = key.trim();
        for (String mode : smv.getModes()) {
            if (mode.equals(trimmed) || mode.equalsIgnoreCase(trimmed)
                    || mode.equals(DeviceSmvDataFactory.cleanStateName(trimmed))) {
                return mode;
            }
        }
        throw new InvalidConditionException("mode key '" + key + "' not found on device " + deviceId);
    }

    private String buildSimpleCondition(String left, SpecConditionDto cond) {
        if (left == null || left.isBlank()) {
            throw new InvalidConditionException("invalid left-hand side: " + left);
        }
        String relation = normalizeRelation(cond.getRelation());
        String value = cond.getValue();
        if (relation == null) {
            throw new InvalidConditionException("simple condition relation is null for device " + cond.getDeviceId());
        }
        return buildSimpleCondition(left, relation, value, cond.getDeviceId(), cond.getRelation());
    }

    private String buildSimpleCondition(String left,
                                        String relation,
                                        String value,
                                        String deviceId,
                                        String rawRelation) {
        if (!isSupportedRelation(relation)) {
            throw new InvalidConditionException("unsupported relation '" + rawRelation
                    + "' for device " + deviceId);
        }
        if (value == null || value.isBlank()) {
            throw new InvalidConditionException("simple condition value is null/blank for device " + deviceId);
        }
        return buildRelationExpr(left, relation, value);
    }

    private String buildVariableCondition(String varName, DeviceSmvData smv, SpecConditionDto cond) {
        String key = cond.getKey();
        if (key == null || key.isBlank()) {
            throw new InvalidConditionException("variable condition key is null/blank for device " + cond.getDeviceId());
        }
        String normalizedKey = key.trim();

        // Which of two different questions this condition asks is declared, never inferred. It used to be
        // decided by `envVariables` membership alone, which dropped the `varName` the author had picked: a
        // condition on a shared variable always compiled to the pool value, so "temperature never exceeds
        // 30" was reported SATISFIED while a compromised device reported 40 and the rule fired. The device
        // the user selected in the editor appeared nowhere in the formula. Read-capability handling for each
        // branch is explained at the branch itself, below.
        String source = cond.getVariableSource() == null
                ? null
                : cond.getVariableSource().trim().toLowerCase(Locale.ROOT);
        if (source == null || (!"environment".equals(source) && !"reported".equals(source))) {
            throw new InvalidConditionException("variable condition on '" + normalizedKey + "' for device "
                    + cond.getDeviceId() + " does not say which value it means: variableSource must be "
                    + "environment (the value in the home) or reported (what this device said). There is no "
                    + "default, because the two differ exactly when a device is compromised");
        }

        DeviceManifest.InternalVariable envVariable = smv.getEnvVariables() != null
                ? smv.getEnvVariables().get(normalizedKey)
                : null;
        DeviceManifest.InternalVariable internalVariable = internalVariable(smv, normalizedKey);

        if ("environment".equals(source)) {
            // `getEnvVariables()` is Reads-gated, and that is the right gate to inherit rather than work
            // around. An affect-only shared declaration (Reads=false) is refused on the *key* by both writer
            // boundaries, so it cannot legitimately arrive here; a capability-blind lookup existed to
            // resolve it anyway, which would have compiled a condition against a pool value this device
            // never observes instead of refusing it. Failing closed on the throw below is the safer half of
            // that choice, and it costs nothing that admission allows.
            DeviceManifest.InternalVariable shared = envVariable;
            if (shared == null) {
                // Distinguish device-local (IsInside=true) from affect-only shared (Reads=false).
                // internalVariable contains all InternalVariables; envVariable is Reads-gated.
                boolean isDeviceLocal = internalVariable != null && Boolean.TRUE.equals(internalVariable.getIsInside());
                boolean isAffectOnly = internalVariable != null
                        && !Boolean.TRUE.equals(internalVariable.getIsInside())
                        && Boolean.FALSE.equals(internalVariable.getReads());

                if (isDeviceLocal) {
                    throw new InvalidConditionException("variable '" + normalizedKey + "' on device "
                            + cond.getDeviceId() + " is device-local (IsInside=true), so it has no value in "
                            + "the home to compare against; use variableSource=reported");
                } else if (isAffectOnly) {
                    throw new InvalidConditionException("variable '" + normalizedKey + "' on device "
                            + cond.getDeviceId() + " is affect-only (Reads=false), so this device cannot observe "
                            + "the environment value; use variableSource=reported to check what the device itself "
                            + "tracks, or add a sensor device with Reads=true for this variable");
                } else {
                    throw new InvalidConditionException("variable '" + normalizedKey + "' on device "
                            + cond.getDeviceId() + " is not declared in this device's InternalVariables");
                }
            }
            return buildVariableConditionExpr("a_" + normalizedKey, cond, shared);
        }

        if (internalVariable != null) {
            return buildVariableConditionExpr(varName + "." + normalizedKey, cond, internalVariable);
        }

        throw new InvalidConditionException("variable key '" + normalizedKey + "' not found as internal/env variable on device "
                + cond.getDeviceId());
    }

    private String buildVariableConditionExpr(String left, SpecConditionDto cond, DeviceManifest.InternalVariable variable) {
        String relation = normalizeRelation(cond.getRelation());
        String value = cond.getValue();
        if (variable != null && variable.getValues() != null && !variable.getValues().isEmpty()) {
            value = cleanEnumValueByRelation(relation, value);
        }
        return buildSimpleCondition(left, relation, value, cond.getDeviceId(), cond.getRelation());
    }

    private boolean hasInternalVariable(DeviceSmvData smv, String name) {
        return internalVariable(smv, name) != null;
    }

    private DeviceManifest.InternalVariable internalVariable(DeviceSmvData smv, String name) {
        if (smv == null || smv.getVariables() == null || name == null) return null;
        for (DeviceManifest.InternalVariable var : smv.getVariables()) {
            if (var != null && name.equals(var.getName())) {
                return var;
            }
        }
        return null;
    }

    private String buildSafetySpec(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        String body = buildSafetyBody(spec, deviceSmvMap);
        return "CTLSPEC AG !(" + body + ")";
    }

    private String buildSafetyBody(SpecificationDto spec, Map<String, DeviceSmvData> deviceSmvMap) {
        List<String> conditionParts = new ArrayList<>();
        List<String> untrustedSourceParts = new ArrayList<>();
        if (spec.getAConditions() != null) {
            for (SpecConditionDto cond : spec.getAConditions()) {
                String aExpr = genConditionSpec(cond, deviceSmvMap);
                String untrustedSourceExpr = buildUntrustedSourceForCondition(cond, deviceSmvMap);
                if (!isTrueLiteral(aExpr)) {
                    conditionParts.add(aExpr);
                }
                if (untrustedSourceExpr == null || untrustedSourceExpr.isBlank()) {
                    throw new InvalidConditionException("cannot resolve the MEDIC control-source label for safety condition on device "
                            + describeDeviceRef(cond) + " (targetType=" + cond.getTargetType()
                            + ", key=" + cond.getKey() + ")");
                }
                untrustedSourceParts.add(untrustedSourceExpr);
            }
        }

        if (untrustedSourceParts.isEmpty()) {
            return conditionParts.isEmpty() ? "TRUE" : String.join(CONDITION_SEPARATOR, conditionParts);
        }
        String untrustedSources = untrustedSourceParts.size() == 1
                ? untrustedSourceParts.get(0)
                : "(" + String.join(" | ", untrustedSourceParts) + ")";
        if (conditionParts.isEmpty()) {
            return untrustedSources;
        }
        return String.join(CONDITION_SEPARATOR, conditionParts)
                + CONDITION_SEPARATOR + untrustedSources;
    }

    private String buildUntrustedSourceForCondition(SpecConditionDto cond,
                                                    Map<String, DeviceSmvData> deviceSmvMap) {
        if (!hasDeviceRef(cond)) return null;
        DeviceSmvData smv = findDeviceSmvDataForSpec(cond, deviceSmvMap);
        String varName = smv != null ? smv.getVarName() : DeviceSmvDataFactory.toVarName(describeDeviceRef(cond));
        String targetType = normalizeSpecTargetType(cond.getTargetType());

        if ("variable".equals(targetType)) {
            if (cond.getKey() == null || cond.getKey().isBlank()) {
                return null;
            }
            String normalizedKey = cond.getKey().trim();
            if (smv != null) {
                boolean exists = (smv.getEnvVariables() != null && smv.getEnvVariables().containsKey(normalizedKey))
                        || hasInternalVariable(smv, normalizedKey);
                if (!exists) {
                    return null;
                }
            }
            return varName + ".trust_" + normalizedKey + "=untrusted";
        }

        if ("state".equals(targetType)) {
            return resolveStateUntrustedSource(varName, smv, cond);
        }

        if ("mode".equals(targetType)) {
            return resolveModeUntrustedSource(varName, smv, cond);
        }

        if ("api".equals(targetType)) {
            return resolveApiUntrustedSource(varName, smv, cond);
        }

        return null;
    }

    private String resolveModeUntrustedSource(String varName, DeviceSmvData smv, SpecConditionDto cond) {
        if (smv == null) {
            return null;
        }
        String relation = normalizeRelation(cond.getRelation());
        if (!"=".equals(relation)) {
            return null;
        }
        String mode = resolveModeName(smv, cond.getKey(), cond.getDeviceId());
        String value = cond.getValue() != null ? DeviceSmvDataFactory.cleanStateName(cond.getValue()) : null;
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> legalValues = smv.getModeStates() != null ? smv.getModeStates().get(mode) : null;
        if (legalValues == null || !legalValues.contains(value)) {
            return null;
        }
        return varName + ".trust_" + mode + "_" + value + "=untrusted";
    }

    private String resolveStateUntrustedSource(String varName, DeviceSmvData smv, SpecConditionDto cond) {
        if (smv == null || smv.getModes() == null || smv.getModes().isEmpty()
                || !"=".equals(normalizeRelation(cond.getRelation()))) {
            return null;
        }
        String value = cond.getValue();
        if (value == null || value.isBlank()) {
            return null;
        }
        Map<String, String> tuple = resolveStateConditionTuple(smv, value, cond.getDeviceId());
        return buildUntrustedStateTuple(varName, smv, tuple);
    }

    private String resolveApiUntrustedSource(String varName, DeviceSmvData smv, SpecConditionDto cond) {
        if (smv == null || smv.getManifest() == null || smv.getManifest().getApis() == null) {
            return null;
        }
        // Trimmed for the same reason as validateApiSignalExists.
        String normalizedKey = cond.getKey() == null ? null : cond.getKey().trim();
        for (DeviceManifest.API api : smv.getManifest().getApis()) {
            if (api != null && Boolean.TRUE.equals(api.getSignal())
                    && api.getName() != null && api.getName().equals(normalizedKey)) {
                String endState = api.getEndState();
                if (endState == null || endState.isBlank()
                        || smv.getModes() == null || smv.getModes().isEmpty()) {
                    return null;
                }
                Map<String, String> tuple = resolveStateConditionTuple(smv, endState, cond.getDeviceId());
                return buildUntrustedStateTuple(varName, smv, tuple);
            }
        }
        return null;
    }

    private String buildUntrustedStateTuple(String varName,
                                            DeviceSmvData smv,
                                            Map<String, String> tuple) {
        List<String> labels = new ArrayList<>();
        for (String mode : smv.getModes()) {
            String state = tuple.get(mode);
            if (state != null && !state.isBlank()) {
                labels.add(varName + ".trust_" + mode + "_" + state + "=untrusted");
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        // A composite state is tainted when any participating mode-state label is untrusted.
        return labels.size() == 1 ? labels.get(0) : "(" + String.join(" | ", labels) + ")";
    }

    private String buildPropertyCondition(String varName,
                                          DeviceSmvData smv,
                                          SpecConditionDto cond,
                                          String prefix) {
        String scope = cond.getPropertyScope() == null
                ? null
                : cond.getPropertyScope().trim().toLowerCase(Locale.ROOT);
        String key = cond.getKey() == null ? null : cond.getKey().trim();
        if (scope == null || scope.isBlank()) {
            throw new InvalidConditionException("propertyScope is required for " + cond.getTargetType()
                    + " condition on device " + cond.getDeviceId());
        }
        if (key == null || key.isBlank()) {
            throw new InvalidConditionException("property key is null/blank for device " + cond.getDeviceId());
        }

        String relation = normalizeRelation(cond.getRelation());
        if (!API_ALLOWED_NORMALIZED_RELATIONS.contains(relation)) {
            throw new InvalidConditionException(cond.getTargetType()
                    + " condition only supports =, !=, IN, NOT_IN relations, got '"
                    + cond.getRelation() + "' for device " + cond.getDeviceId());
        }
        String normalizedValue = cleanPropertyValue(relation, cond.getValue());
        if (normalizedValue == null || normalizedValue.isBlank()) {
            throw new InvalidConditionException("property value is null/blank for device " + cond.getDeviceId());
        }

        if ("variable".equals(scope)) {
            DeviceManifest.InternalVariable variable = internalVariable(smv, key);
            if (variable == null) {
                throw new InvalidConditionException("property variable '" + key
                        + "' not found on device " + cond.getDeviceId());
            }
            return buildSimpleCondition(varName + "." + prefix + variable.getName(), relation,
                    normalizedValue, cond.getDeviceId(), cond.getRelation());
        }
        if (!"state".equals(scope)) {
            throw new InvalidConditionException("unsupported propertyScope '" + cond.getPropertyScope()
                    + "' for device " + cond.getDeviceId());
        }

        String mode = resolveModeName(smv, key, cond.getDeviceId());
        List<String> values = ("in".equals(relation) || "not in".equals(relation))
                ? splitValues(normalizedValue)
                : List.of(normalizedValue);
        if (values.isEmpty()) {
            throw new InvalidConditionException("empty property value list for device " + cond.getDeviceId());
        }
        List<String> predicates = values.stream()
                .map(value -> currentModePropertyPredicate(varName, smv, mode, prefix, value))
                .toList();
        String matched = predicates.size() == 1
                ? predicates.get(0)
                : "(" + String.join(" | ", predicates) + ")";
        return ("!=".equals(relation) || "not in".equals(relation))
                ? "!(" + matched + ")"
                : matched;
    }

    private String currentModePropertyPredicate(String varName,
                                                DeviceSmvData smv,
                                                String mode,
                                                String prefix,
                                                String value) {
        List<String> states = smv.getModeStates().get(mode);
        if (states == null || states.isEmpty()) {
            throw new InvalidConditionException("mode '" + mode + "' has no states on device " + varName);
        }
        List<String> activeStateLabels = states.stream()
                .map(state -> "(" + varName + "." + mode + "=" + state
                        + " & " + varName + "." + prefix + mode + "_" + state + "=" + value + ")")
                .toList();
        return activeStateLabels.size() == 1
                ? activeStateLabels.get(0)
                : "(" + String.join(" | ", activeStateLabels) + ")";
    }

    private String cleanPropertyValue(String relation, String value) {
        if (value == null) {
            return null;
        }
        if ("in".equals(relation) || "not in".equals(relation)) {
            return splitValues(value).stream()
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(","));
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void validateApiSignalExists(DeviceSmvData smv, String apiKey, String deviceId) {
        if (smv.getManifest() == null || smv.getManifest().getApis() == null) {
            throw new InvalidConditionException("device '" + deviceId + "' has no APIs defined");
        }
        // Trimmed to match buildVariableCondition and resolveModeName: admission stores the key
        // trimmed, and a raw comparison here rejected a spec those paths had already resolved.
        String normalizedKey = apiKey == null ? null : apiKey.trim();
        for (DeviceManifest.API api : smv.getManifest().getApis()) {
            if (api != null && api.getName() != null && api.getName().equals(normalizedKey)
                    && api.getSignal() != null && api.getSignal()) {
                return;
            }
        }
        throw new InvalidConditionException("api '" + apiKey + "' not found or not a signal on device '" + deviceId + "'");
    }

    private static final List<String> API_ALLOWED_NORMALIZED_RELATIONS = List.of("=", "!=", "in", "not in");

    private void validateApiBooleanRelation(SpecConditionDto cond) {
        String rel = cond.getRelation();
        String normalized = normalizeRelation(rel);
        if (normalized == null || !API_ALLOWED_NORMALIZED_RELATIONS.contains(normalized)) {
            throw new InvalidConditionException("api condition only supports =, !=, IN, NOT_IN relations, got '"
                    + rel + "' for device " + cond.getDeviceId());
        }
        // Values must be boolean literals (TRUE/FALSE), or IN/NOT_IN boolean lists.
        String value = cond.getValue();
        if (value != null) {
            if ("in".equals(normalized) || "not in".equals(normalized)) {
                for (String v : splitValues(value)) {
                    if (!"TRUE".equalsIgnoreCase(v) && !"FALSE".equalsIgnoreCase(v)) {
                        throw new InvalidConditionException("api condition value must be TRUE or FALSE, got '" + v
                                + "' for device " + cond.getDeviceId());
                    }
                }
            } else if (!"TRUE".equalsIgnoreCase(value.trim()) && !"FALSE".equalsIgnoreCase(value.trim())) {
                throw new InvalidConditionException("api condition value must be TRUE or FALSE, got '" + value
                        + "' for device " + cond.getDeviceId());
            }
        }
    }

    private String buildRelationExpr(String left, String relation, String value) {
        if ("in".equals(relation) || "not in".equals(relation)) {
            List<String> values = splitValues(value);
            if (values.isEmpty()) {
                throw new InvalidConditionException("empty value list for '" + relation + "' relation on " + left);
            }
            String op = "in".equals(relation) ? "=" : "!=";
            String join = "in".equals(relation) ? " | " : " & ";
            return "(" + values.stream()
                    .map(v -> left + op + v)
                    .collect(Collectors.joining(join)) + ")";
        }
        return left + relation + value;
    }

    private List<String> splitValues(String value) {
        if (value == null) return List.of();
        return Arrays.stream(value.split("[,;|]"))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toList());
    }

    private String cleanEnumValueByRelation(String normalizedRelation, String value) {
        if (value == null) return null;
        if ("in".equals(normalizedRelation) || "not in".equals(normalizedRelation)) {
            return splitValues(value).stream()
                    .map(this::cleanEnumLiteral)
                    .collect(Collectors.joining(","));
        }
        return cleanEnumLiteral(value);
    }

    private String cleanEnumLiteral(String value) {
        return value == null ? "" : value.trim().replace(" ", "");
    }


    private String normalizeRelation(String relation) {
        if (relation == null) return null;
        return SmvRelationUtils.normalizeRelation(relation);
    }

    private boolean isSupportedRelation(String relation) {
        return SmvRelationUtils.isSupportedRelation(relation);
    }

    private boolean isTrueLiteral(String s) {
        return s == null || s.trim().isEmpty() || "TRUE".equalsIgnoreCase(s.trim());
    }

    /** Signals invalid condition data that prevents correct spec generation. */
    static class InvalidConditionException extends RuntimeException {
        InvalidConditionException(String message) {
            super(message);
        }
    }
}
