package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerator;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvDataFactory;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvNamespace;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import cn.edu.nju.Iot_Verify.exception.InternalServerException;
import cn.edu.nju.Iot_Verify.exception.SmvGenerationException;
import cn.edu.nju.Iot_Verify.dto.model.AttackScenarioDto;
import cn.edu.nju.Iot_Verify.util.DeviceNameNormalizer;
import cn.edu.nju.Iot_Verify.util.EnvironmentDomainUtils;
import cn.edu.nju.Iot_Verify.util.NaturalChangeRateParser;
import cn.edu.nju.Iot_Verify.util.SmvConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

/**
 * Validates that a device template can be turned into a legal NuSMV module.
 *
 * <p>This is the fail-closed gate in front of template persistence. It rejects manifests whose
 * identifiers, value domains, dynamics or generated SMV tokens would collide or produce an
 * invalid model, so one bad template cannot break every later verification. The final step
 * generates a throwaway model for a probe device, because some collisions only surface in
 * generator output.</p>
 *
 * <p>Extracted verbatim from {@code BoardStorageServiceImpl}, where it was ~570 lines that used
 * only the SMV generator and called nothing else in that class.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceTemplateNuSmvValidator {

    private final SmvGenerator smvGenerator;

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final java.util.regex.Pattern SAFE_SMV_TOKEN =
            java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static final int MAX_TEMPLATE_ICON_LENGTH = 262_144;
    private static final java.util.regex.Pattern SAFE_TEMPLATE_ICON =
            java.util.regex.Pattern.compile(
                    "^data:image/(svg\\+xml|png|jpe?g|webp|gif)(;[^,]+)?,.+$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    public void validateTemplateManifestForNuSmv(String templateName, DeviceManifest manifest) {
        validateTemplateIcon(templateName, manifest.getIcon());

        // ── Validate InternalVariable / ImpactedVariable names FIRST ──
        // These apply to ALL templates (including no-mode sensors), because the NuSMV
        // generation pipeline uses raw variable names (DeviceSmvDataFactory:83, :267).
        if (manifest.getInternalVariables() != null) {
            for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
                validateSmvIdentifier(templateName, "InternalVariable", iv.getName());

                // Guard against three key-shape vulnerabilities found in adversarial audit round 9:
                // 1. `a_` prefix collision — user declares `a_temperature`, generator prepends again → `a_a_temperature`
                //    collides with another device's shared `temperature` whose pool is `a_temperature`.
                //    Case-insensitive check (round 11): 'A_temp' normalizes to 'a_a_temp', same collision.
                if (iv.getName().toLowerCase(Locale.ROOT).startsWith(SmvConstants.ENVIRONMENT_POOL_PREFIX)) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': InternalVariable name '" + iv.getName()
                            + "' must not start with '" + SmvConstants.ENVIRONMENT_POOL_PREFIX
                            + "' in any letter case (reserved for environment pool identifiers).");
                }

                // 2. Reserved-word collision — a variable named `INIT` or `case` breaks SMV parse.
                if (DeviceNameNormalizer.NUSMV_RESERVED_WORDS.contains(iv.getName())
                        || DeviceNameNormalizer.NUSMV_RESERVED_WORDS.contains(iv.getName().toUpperCase(Locale.ROOT))) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': InternalVariable name '" + iv.getName()
                                    + "' is a NuSMV reserved word.");
                }

                validateTemplateVariableDomain(templateName, "InternalVariable", iv.getName(),
                        iv.getValues(), iv.getLowerBound(), iv.getUpperBound(), iv.getNaturalChangeRate(),
                        !Boolean.TRUE.equals(iv.getIsInside()));
                if (!Boolean.TRUE.equals(iv.getIsInside())
                        && (!hasText(iv.getTrust()) || !hasText(iv.getPrivacy()))) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': shared environment InternalVariable '"
                                    + iv.getName() + "' must explicitly define Trust and Privacy.");
                }
                // Read capability is explicit for the same reason Trust/Privacy and IsInside are: a
                // capability inferred from a missing field is what the removed EnvironmentDomains array
                // encoded implicitly. The JSON schema also rejects both shapes, but its message is a
                // schema path; this one names the concept so a template author can act on it.
                if (!Boolean.TRUE.equals(iv.getIsInside()) && iv.getReads() == null) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': shared environment InternalVariable '"
                                    + iv.getName() + "' must declare Reads explicitly. Use Reads=true "
                                    + "when this device observes the value, so its rules and "
                                    + "specifications may use it as a condition source, or Reads=false "
                                    + "when it only affects the value.");
                }
                if (Boolean.TRUE.equals(iv.getIsInside()) && iv.getReads() != null) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': device-local InternalVariable '"
                                    + iv.getName() + "' must not declare Reads. Read capability applies "
                                    + "only to a shared value (IsInside=false); a device always reads "
                                    + "its own local variable.");
                }
            }

            // 3. Mode-variable name collision — if a variable matches a mode name, `device.<name>` is ambiguous
            //    (the generator emits both mode state and variable in the device module namespace).
            Set<String> modeNames = new HashSet<>();
            if (manifest.getModes() != null) {
                for (String modeName : manifest.getModes()) {
                    if (modeName != null) {
                        modeNames.add(modeName);
                    }
                }
            }
            if (!modeNames.isEmpty() && manifest.getInternalVariables() != null) {
                for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
                    if (modeNames.contains(iv.getName())) {
                        throw new BadRequestException(
                                "Template '" + templateName + "': InternalVariable name '" + iv.getName()
                                        + "' collides with a mode name (both emit device." + iv.getName()
                                        + " in the same namespace).");
                    }
                }
            }
        }
        if (manifest.getImpactedVariables() != null) {
            for (String impacted : manifest.getImpactedVariables()) {
                validateSmvIdentifier(templateName, "ImpactedVariable", impacted);
            }
        }
        if (manifest.getContents() != null) {
            // A content name is concatenated verbatim into `privacy_<name>` by
            // `SmvDeviceModuleBuilder.appendContentPrivacyVariables`, so it is an SMV identifier just like
            // the two above — but it was the only one of the three that nothing checked, on either side.
            // Measured: a content named "my photo" emitted `privacy_my photo: {public, private};` and
            // NuSMV refused the model with `at token "photo": syntax error`. Rejecting it here turns a
            // run-time generation failure into an import-time rejection that names the field.
            for (DeviceManifest.Content content : manifest.getContents()) {
                if (content != null) {
                    validateSmvIdentifier(templateName, "Content", content.getName());
                }
            }
        }

        // ── Mode-related validation ──
        boolean hasModes = manifest.getModes() != null && !manifest.getModes().isEmpty();
        boolean hasInitState = manifest.getInitState() != null && !manifest.getInitState().isBlank();
        boolean hasWorkingStates = manifest.getWorkingStates() != null && !manifest.getWorkingStates().isEmpty();

        if (manifest.getApis() != null && !manifest.getApis().isEmpty()) {
            if (!hasModes) {
                throw new BadRequestException("Template '" + templateName
                        + "': APIs require at least one Mode because API commands are modeled as state changes.");
            }
        }
        validateTemplateDynamics(templateName, manifest);

        if (!hasModes && !hasInitState && !hasWorkingStates) {
            // No-mode device template (pure sensor) — collision check among variables only
            checkVariableCollisions(templateName, manifest, Collections.emptyList());
            return;
        }

        // If any mode-related field is present, all three must be present
        if (!hasModes) {
            throw new BadRequestException("Template '" + templateName + "' must contain non-empty Modes.");
        }
        if (!hasInitState) {
            throw new BadRequestException("Template '" + templateName + "' must contain InitState.");
        }
        if (!hasWorkingStates) {
            throw new BadRequestException("Template '" + templateName + "' must contain non-empty WorkingStates.");
        }

        // Validate mode names are legal NuSMV identifiers (after stripping spaces)
        for (String mode : manifest.getModes()) {
            String cleaned = mode == null ? "" : mode.replace(" ", "");
            if (!SAFE_SMV_TOKEN.matcher(cleaned).matches()) {
                throw new BadRequestException(
                        "Template '" + templateName + "': mode name '" + mode
                                + "' contains invalid characters. Only letters, digits and underscores are allowed.");
            }
        }

        // Validate working-state names are legal NuSMV identifiers
        for (DeviceManifest.WorkingState ws : manifest.getWorkingStates()) {
            if (ws.getName() == null) continue;
            // Multi-mode states can be semicolon-separated; validate each segment
            String[] segments = ws.getName().split(";", -1);
            for (String seg : segments) {
                String cleaned = seg.trim().replace(" ", "");
                if (cleaned.isEmpty()) continue; // empty segment in ";cool" is allowed
                if (!SAFE_SMV_TOKEN.matcher(cleaned).matches()) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': state name '" + ws.getName()
                                    + "' contains invalid characters. Only letters, digits and underscores are allowed.");
                }
            }
        }

        // Check for identifier collisions (modes + variables)
        checkVariableCollisions(templateName, manifest, manifest.getModes());
    }

    private void validateTemplateVariableDomain(String templateName,
                                                String kind,
                                                String name,
                                                List<String> values,
                                                Integer lowerBound,
                                                Integer upperBound,
                                                String naturalChangeRate,
                                                boolean sharedEnvironment) {
        if (lowerBound != null && upperBound != null && lowerBound > upperBound) {
            throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                    + name + "' has LowerBound " + lowerBound + " greater than UpperBound " + upperBound + ".");
        }
        // A domain of exactly one value is not a variable to NuSMV. It stores such a declaration as a
        // *constant*, and a constant cannot be the left-hand side of an assignment — while generation
        // always emits `init(<name>) := <value>`. Measured on 2.7.1:
        //
        //     VAR level: 5..5;  ASSIGN init(level) := 5;
        //       WARNING: single-value variable 'level' has been stored as a constant
        //       line 5: A variable is expected in left-hand-side of assignment      (exit 1)
        //
        // `5..6` on the identical model is clean, so cardinality is the whole difference. Only
        // `lowerBound > upperBound` was checked, so `5 == 5` passed all four template gates and the
        // template persisted; every later verification of any board using it then died in the engine.
        // `runTemplateNuSmvPrecheck` cannot catch it — it generates text without invoking NuSMV, so a
        // parse failure is invisible to it, and it runs after `saveAndFlush` regardless.
        //
        // No bundled template or example scene declares a single-value domain, verified before adding
        // this, since a check that refuses a shipped template would be worse than the gap.
        // An enormous domain does not merely slow the engine down — it makes it give up silently.
        // Measured on NuSMV 2.7.1: `v: 0..300000` produces the banner and then nothing, rc=127, zero
        // verdicts, deterministic across repeat runs in batch and `-int` mode. `0..100000` still answers
        // in 0.37 s. Since the template persisted, every later verification of any board using it
        // returned no result and no diagnosis.
        //
        // Bounded here rather than in the schema because the message can name the field and the count.
        // The 45 bundled templates and 6 example scenes top out at 101 values across 30 numeric domains,
        // so this rejects nothing that ships. `MAX_NATURAL_CHANGE_RATE_SPAN` is the precedent: the same
        // quantity — a span — already capped for the same stated reason.
        if (lowerBound != null && upperBound != null) {
            long declaredValues = (long) upperBound - (long) lowerBound + 1L;
            if (declaredValues > RequestLimits.MAX_NUMERIC_DOMAIN_VALUES) {
                throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                        + name + "' declares " + declaredValues + " possible values ("
                        + lowerBound + ".." + upperBound + "), above the limit of "
                        + RequestLimits.MAX_NUMERIC_DOMAIN_VALUES + ". A domain this wide makes NuSMV "
                        + "abort without producing any verdict. Narrow the range.");
            }
        }
        if (lowerBound != null && upperBound != null && lowerBound.equals(upperBound)) {
            throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                    + name + "' has LowerBound equal to UpperBound (" + lowerBound + "). NuSMV stores a "
                    + "single-value domain as a constant, which cannot be assigned an initial value, so "
                    + "the generated model would be rejected by the engine. Widen the range, or model "
                    + "the value as a fixed enumeration with at least two members.");
        }
        if (values != null) {
            Set<String> normalizedValues = new LinkedHashSet<>();
            for (String rawValue : values) {
                String value = rawValue == null ? "" : rawValue.replace(" ", "");
                if (value.isEmpty() || !normalizedValues.add(value)) {
                    throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                            + name + "' contains empty or duplicate enum values after model normalization.");
                }
                if (values.size() < 2) {
                    // Same engine limitation as the numeric single-value domain above: `{detected}` is
                    // stored as a constant and `init(a_smoke) := detected` is then rejected. Counted
                    // *after* the duplicate check, so `["on", "on"]` reports as duplicates rather than
                    // as a one-member domain — the more specific message for the same authored mistake.
                    throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                            + name + "' declares a single enum value. NuSMV stores a one-member domain as "
                            + "a constant, which cannot be assigned an initial value, so the generated "
                            + "model would be rejected by the engine. Declare at least two values.");
                }
                // An enum value is emitted as a bare SMV token — inside the `{...}` domain and on the
                // right-hand side of every comparison against it — so it is an identifier, and nothing
                // checked it. The space-stripping above is cosmetic (its comment says "match sample.smv"),
                // and it happens to remove the one character NuSMV tolerates while leaving every character
                // NuSMV rejects. Measured: `Values: ["hot!", "ok"]` passed the schema and all four
                // validators, emitted `authState: {hot!, ok};`, and NuSMV refused the model with
                // `at token "!": syntax error`. The template persisted, so every later verification of any
                // board using it died in the engine.
                //
                // Validated after space removal, matching how mode and state names are handled: the
                // bundled `Door RFID` ("not authorized") and `Thermostat` ("pending cool", …) rely on that
                // allowance, and all 58 bundled values pass.
                if (!SAFE_SMV_TOKEN.matcher(value).matches()) {
                    throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                            + name + "' has enum value '" + rawValue + "' which is not a legal NuSMV token. "
                            + "After spaces are removed it must start with a letter or underscore and "
                            + "contain only letters, digits and underscores.");
                }
                // A reserved word is a legal *token* and an illegal *value*, so the pattern above cannot
                // catch it. `SAFE_SMV_TOKEN` alone left `Values: ["next"]` admitted, emitting
                // `authState: {next, ok};` — measured, NuSMV then refused the model with
                // `at token "next": syntax error` (`TRUE`/`FALSE` fail as `Invalid enumerative value`).
                // Same class as the punctuation case this check was written for.
                //
                // Case-**sensitive**, unlike `validateSmvIdentifier`'s three-way fold for *names*, and the
                // difference is not an oversight: NuSMV's lexer is case-sensitive here. Measured on 2.7.1,
                // `{Next, ok}` and `{NEXT, ok}` compile while `{next, ok}` does not, so folding case would
                // reject values the engine accepts. Names can afford to over-reject because they are
                // `.equals()`-matched and never rescued; a value is emitted verbatim, so the rule must be
                // exactly what the engine enforces.
                //
                // All 58 bundled enum values pass under either policy, so this cannot break template
                // loading — verified rather than assumed, since a check that refuses a bundled template
                // would be worse than the gap it closes.
                if (DeviceSmvDataFactory.NUSMV_RESERVED_WORDS.contains(value)) {
                    throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                            + name + "' has enum value '" + rawValue + "' which is a NuSMV reserved word. "
                            + "NuSMV cannot parse it as an enumeration constant, so the generated model "
                            + "would be rejected by the engine. Rename the value.");
                }
            }
        }
        boolean numeric = lowerBound != null && upperBound != null;
        boolean hasRateDeclaration = naturalChangeRate != null;
        if (numeric && sharedEnvironment && !hasRateDeclaration) {
            throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                    + name + "' is a shared numeric environment variable and must explicitly define "
                    + "NaturalChangeRate ('[-1, 1]' for the MEDIC baseline disturbance, or '0' "
                    + "for no natural change).");
        }
        if (hasRateDeclaration && !numeric) {
            throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                    + name + "' declares NaturalChangeRate, but only numeric ranges can change by a rate.");
        }
        if (hasRateDeclaration) {
            try {
                NaturalChangeRateParser.parse(naturalChangeRate);
            } catch (NaturalChangeRateParser.ParseException exception) {
                if (exception.isDescending()) {
                    throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                            + name + "' has invalid or descending NaturalChangeRate '" + naturalChangeRate + "'.");
                }
                throw new BadRequestException("Template '" + templateName + "': " + kind + " '"
                        + name + "' has invalid NaturalChangeRate '" + naturalChangeRate + "'.");
            }
        }
    }

    private void validateTemplateDynamics(String templateName, DeviceManifest manifest) {
        if (manifest.getWorkingStates() == null) {
            return;
        }
        Map<String, DeviceManifest.InternalVariable> writableDomains = new LinkedHashMap<>();
        if (manifest.getInternalVariables() != null) {
            for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
                if (variable != null && Boolean.TRUE.equals(variable.getIsInside())) {
                    writableDomains.putIfAbsent(variable.getName(), variable);
                }
            }
        }
        if (manifest.getImpactedVariables() != null) {
            for (String impacted : manifest.getImpactedVariables()) {
                DeviceManifest.InternalVariable domain = EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted);
                if (domain != null) {
                    writableDomains.putIfAbsent(impacted, domain);
                }
            }
        }
        for (DeviceManifest.WorkingState state : manifest.getWorkingStates()) {
            if (state == null || state.getDynamics() == null) {
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                String variableName = dynamic == null ? null : dynamic.getVariableName();
                if (!hasText(variableName)) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + state.getName() + "' Dynamics requires VariableName.");
                }
                if (!seen.add(variableName)) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + state.getName() + "' defines Dynamics for '" + variableName + "' more than once.");
                }
                DeviceManifest.InternalVariable domain = writableDomains.get(variableName);
                if (domain == null) {
                    throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                            + state.getName() + "' has Dynamics for unknown or non-writable variable '"
                            + variableName + "'.");
                }
                boolean numeric = domain.getLowerBound() != null && domain.getUpperBound() != null;
                if (numeric) {
                    if (!hasText(dynamic.getChangeRate()) || dynamic.getValue() != null) {
                        throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                                + state.getName() + "' must use ChangeRate for numeric Dynamics target '"
                                + variableName + "'.");
                    }
                    try {
                        Integer.parseInt(dynamic.getChangeRate().trim());
                    } catch (NumberFormatException exception) {
                        throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                                + state.getName() + "' has non-integer ChangeRate '"
                                + dynamic.getChangeRate() + "' for '" + variableName + "'.");
                    }
                } else {
                    if (!hasText(dynamic.getValue()) || dynamic.getChangeRate() != null) {
                        throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                                + state.getName() + "' must use Value for enum/boolean Dynamics target '"
                                + variableName + "'.");
                    }
                    DeviceManifest.Assignment assignment = DeviceManifest.Assignment.builder()
                            .attribute(variableName).value(dynamic.getValue()).build();
                    validateTemplateDiscreteValue(templateName, state.getName(), assignment, domain);
                }
            }
        }
    }

    private void validateTemplateDiscreteValue(String templateName,
                                               String stateName,
                                               DeviceManifest.Assignment assignment,
                                               DeviceManifest.InternalVariable domain) {
        String value = assignment.getValue().replace(" ", "");
        if (domain.getValues() != null && !domain.getValues().isEmpty()) {
            boolean allowed = domain.getValues().stream()
                    .filter(Objects::nonNull)
                    .map(candidate -> candidate.replace(" ", ""))
                    .anyMatch(value::equals);
            if (!allowed) {
                throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                        + stateName + "' sets Dynamics target '" + assignment.getAttribute()
                        + "' outside enum domain " + domain.getValues() + ".");
            }
        } else if (!"TRUE".equalsIgnoreCase(value) && !"FALSE".equalsIgnoreCase(value)) {
            throw new BadRequestException("Template '" + templateName + "': WorkingState '"
                    + stateName + "' sets boolean Dynamics target '" + assignment.getAttribute()
                    + "' to '" + assignment.getValue() + "'; use TRUE or FALSE.");
        }
    }

    private void validateTemplateIcon(String templateName, String icon) {
        if (icon == null || icon.isBlank()) {
            return;
        }
        String trimmed = icon.trim();
        if (trimmed.length() > MAX_TEMPLATE_ICON_LENGTH) {
            throw new BadRequestException("Template '" + templateName
                    + "' Icon is too large. Use a self-contained data:image URI under 256 KB.");
        }
        if (!SAFE_TEMPLATE_ICON.matcher(trimmed).matches()) {
            throw new BadRequestException("Template '" + templateName
                    + "' Icon must be a self-contained data:image URI (svg/png/jpeg/webp/gif).");
        }
    }

    /**
     * Check that mode names, internal variable names, environment domains, and impacted
     * variable names do not
     * collide after case-insensitive normalization. An ImpactedVariable may share a name
     * with an environment InternalVariable (IsInside=false/null), because that means the
     * device can read and affect the same shared environment value. It must not share a
     * name with a local InternalVariable (IsInside=true), which would make a device-private
     * state look like a board-level environment variable.
     */
    /*
     * Collisions are judged on the token that generation will actually emit.
     *
     * This compared the *raw* cleaned name, which is not what ends up in the model.
     * `DeviceSmvDataFactory.sanitizeSmvToken` additionally replaces every non-word character with `_`, prefixes a
     * leading digit, and — the case that mattered — prefixes a NuSMV reserved word. So modes `next` and `_next`
     * passed here as two distinct names and both generated `_next`. Verified against NuSMV 2.7.1: the resulting
     * model is rejected with "TYPE ERROR: duplicate constants in the enum type of variable", so the user's
     * verification died with an engine type error instead of a message naming the template they can fix.
     *
     * Modes and states are the only identifier kinds that skip `validateSmvIdentifier` (which rejects reserved
     * words outright for variables), because generation deliberately rescues them by prefixing. That rescue is
     * fine; comparing pre-rescue names for uniqueness is not.
     */
    private String generatedToken(String raw) {
        String cleaned = raw == null ? "" : raw.replace(" ", "");
        if (cleaned.isEmpty()) return "";
        return DeviceSmvDataFactory.sanitizeSmvToken(cleaned).toLowerCase(Locale.ROOT);
    }

    private void checkVariableCollisions(String templateName, DeviceManifest manifest, List<String> modes) {
        // Track modes separately - they must not collide with each other
        Set<String> modeNames = new HashSet<>();
        for (String mode : modes) {
            String token = generatedToken(mode);
            if (!token.isEmpty() && !modeNames.add(token)) {
                throw new BadRequestException(
                        "Template '" + templateName + "': mode name '" + mode
                                + "' collides with another mode once normalized for NuSMV (both become '"
                                + token + "'). Rename one of them.");
            }
        }

        // Track internal variables - they must not collide with modes or each other
        Set<String> internalVarNames = new HashSet<>();
        Map<String, Boolean> localInternalVars = new HashMap<>();
        if (manifest.getInternalVariables() != null) {
            for (DeviceManifest.InternalVariable iv : manifest.getInternalVariables()) {
                String cleaned = iv.getName() == null ? "" : iv.getName().replace(" ", "");
                if (cleaned.isEmpty()) continue;

                String normalized = generatedToken(iv.getName());
                localInternalVars.put(normalized, Boolean.TRUE.equals(iv.getIsInside()));
                if (modeNames.contains(normalized)) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': InternalVariable '" + iv.getName()
                            + "' collides with mode name.");
                }
                if (!internalVarNames.add(normalized)) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': duplicate InternalVariable name after normalization: '"
                            + iv.getName() + "'.");
                }
            }
        }

        // Track impacted variables. They may share a name only with environment
        // InternalVariables, never with local InternalVariables.
        Set<String> impactedVarNames = new HashSet<>();
        if (manifest.getImpactedVariables() != null) {
            for (String impacted : manifest.getImpactedVariables()) {
                String cleaned = impacted == null ? "" : impacted.replace(" ", "");
                if (cleaned.isEmpty()) continue;

                String normalized = generatedToken(impacted);
                if (modeNames.contains(normalized)) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': ImpactedVariable '" + impacted
                            + "' collides with mode name.");
                }
                if (!impactedVarNames.add(normalized)) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': duplicate ImpactedVariable name after normalization: '"
                            + impacted + "'.");
                }
                if (Boolean.TRUE.equals(localInternalVars.get(normalized))) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': ImpactedVariable '" + impacted
                            + "' cannot share a name with a local InternalVariable. "
                            + "Use WorkingStates.Dynamics for device-local state changes, and reserve "
                            + "ImpactedVariables for shared environment variables.");
                }
                if (EnvironmentDomainUtils.resolveImpactDomain(manifest, impacted) == null) {
                    throw new BadRequestException(
                            "Template '" + templateName + "': ImpactedVariable '" + impacted
                                    + "' has no domain in this manifest. Declare an InternalVariable named '" + impacted
                                    + "' with IsInside=false carrying its type and domain, and Reads=false if this "
                                    + "device only affects the value without observing it.");
                }
            }
        }

        DeviceSmvNamespace.validate(manifest, reason ->
                new BadRequestException("Template '" + templateName + "': " + reason));
        validateModeDomainSizes(templateName, manifest);
        checkLocalEnumVariablesHaveDrivers(templateName, manifest);
    }

    /**
     * Reject local enum variables without driver mechanisms to prevent them from being
     * frozen at their nondeterministically chosen initial value throughout benign runs.
     * A local enum variable must be driven by WorkingState Dynamics, Transition Assignment,
     * or (for numeric variables) NaturalChangeRate.
     *
     * <p><b>Current implementation checks for at least one WorkingState with Dynamics.</b>
     * Partial coverage (some WorkingStates without Dynamics for this variable) is permitted.
     * This means a variable may be driven in state A but hold a stale value in state B,
     * which can still lead to incorrect behavior but is less severe than complete freezing.
     *
     * <p>Example: A thermostat with 15 WorkingStates where only 10 bind {@code operatingState}
     * will pass this check, but the 5 unbound states will report stale values. This is a
     * known limitation of the "at least one" approach. Future enhancement: require full
     * coverage or emit a warning on partial coverage.
     */
    private void checkLocalEnumVariablesHaveDrivers(String templateName, DeviceManifest manifest) {
        if (manifest.getInternalVariables() == null) {
            return;
        }

        for (DeviceManifest.InternalVariable var : manifest.getInternalVariables()) {
            if (var == null || var.getName() == null) {
                continue;
            }

            // Only check local enum variables
            if (!Boolean.TRUE.equals(var.getIsInside())) {
                continue; // Shared variables are environment inputs, no driver needed
            }
            if (var.getValues() == null || var.getValues().isEmpty()) {
                continue; // Numeric variables can use NaturalChangeRate
            }

            // Check for driver mechanisms
            boolean hasDynamics = hasWorkingStateDynamics(manifest, var.getName());
            boolean hasTransition = DeviceManifestModes.hasTransitionAssignment(manifest, var.getName());

            if (!hasDynamics && !hasTransition) {
                throw new BadRequestException(
                        "Template '" + templateName + "': local enum variable '" + var.getName()
                                + "' has no driver mechanism (no WorkingState Dynamics, no Transition Assignment). "
                                + "It will hold its nondeterministically chosen initial value throughout benign runs. "
                                + "Either: (1) add WorkingState Dynamics to bind it to device state, "
                                + "(2) convert it to a Signal API if it represents an event, "
                                + "or (3) make it shared (IsInside=false) if it is an environmental input.");
            }
        }
    }

    private boolean hasWorkingStateDynamics(DeviceManifest manifest, String variableName) {
        if (manifest.getWorkingStates() == null) {
            return false;
        }
        for (DeviceManifest.WorkingState state : manifest.getWorkingStates()) {
            if (state == null || state.getDynamics() == null) {
                continue;
            }
            for (DeviceManifest.Dynamic dynamic : state.getDynamics()) {
                if (dynamic != null && variableName.equals(dynamic.getVariableName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validateModeDomainSizes(String templateName, DeviceManifest manifest) {
        // NuSMV stores one-value variables as constants, which cannot be assigned an initial value.
        DeviceManifestModes.modeStates(manifest).forEach((mode, states) -> {
            if (states.size() == 1) {
                throw new BadRequestException("Template '" + templateName + "': mode '" + mode
                        + "' has only one distinct working state ('" + states.get(0)
                        + "'). NuSMV stores a single-value mode as a constant, which cannot be assigned an "
                        + "initial value, so the generated model would be rejected by the engine. Give "
                        + "this mode at least two states, or remove it.");
            }
        });
    }

    /**
     * Validate that a name is a legal NuSMV identifier: matches [a-zA-Z_][a-zA-Z0-9_]*
     * and is not a NuSMV reserved word (case-insensitive).
     * IMPORTANT: Does NOT strip spaces — validates the raw name to ensure it's used as-is in NuSMV generation.
     */
    private void validateSmvIdentifier(String templateName, String fieldType, String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException(
                    "Template '" + templateName + "': " + fieldType + " name must not be blank.");
        }
        // Reject leading/trailing whitespace and common space character
        // (tab/newline will be caught by regex below as "invalid characters")
        if (name.trim().length() != name.length() || name.contains(" ")) {
            throw new BadRequestException(
                    "Template '" + templateName + "': " + fieldType + " name '" + name
                            + "' contains whitespace. Only letters, digits and underscores are allowed.");
        }
        // Validate against NuSMV identifier pattern
        if (!SAFE_SMV_TOKEN.matcher(name).matches()) {
            throw new BadRequestException(
                    "Template '" + templateName + "': " + fieldType + " name '" + name
                            + "' contains invalid characters. Only letters, digits and underscores are allowed, and must start with a letter or underscore.");
        }
        // Check against NuSMV reserved words (case-insensitive)
        if (DeviceSmvDataFactory.NUSMV_RESERVED_WORDS.contains(name)
                || DeviceSmvDataFactory.NUSMV_RESERVED_WORDS.contains(name.toUpperCase(Locale.ROOT))
                || DeviceSmvDataFactory.NUSMV_RESERVED_WORDS.contains(name.toLowerCase(Locale.ROOT))) {
            throw new BadRequestException(
                    "Template '" + templateName + "': " + fieldType + " name '" + name
                            + "' is a NuSMV reserved word and cannot be used as an identifier.");
        }
    }

    public void runTemplateNuSmvPrecheck(Long userId, String templateName, DeviceManifest manifest) {
        DeviceVerificationDto probe = new DeviceVerificationDto();
        probe.setVarName("__template_probe_device__");
        probe.setTemplateName(templateName);
        probe.setState(manifest.getInitState());

        SmvGenerator.GenerateResult generated = null;
        try {
            generated = smvGenerator.generate(
                    userId,
                    List.of(probe),
                    List.of(),
                    List.of(),
                    AttackScenarioDto.none(),
                    false,
                    SmvGenerator.GeneratePurpose.VERIFICATION
            );
        } catch (SmvGenerationException e) {
            if (SmvGenerationException.ErrorCategories.TEMPLATE_LOAD_ERROR.equals(e.getErrorCategory())
                    || SmvGenerationException.ErrorCategories.MANIFEST_PARSE_ERROR.equals(e.getErrorCategory())
                    || SmvGenerationException.ErrorCategories.MULTIPLE_DEVICES_FAILED.equals(e.getErrorCategory())) {
                throw new InternalServerException(
                        "NuSMV precheck failed for template '" + templateName + "'.", e);
            }
            String reason = (e.getMessage() == null || e.getMessage().isBlank())
                    ? e.getErrorCategory()
                    : "[" + e.getErrorCategory() + "] " + e.getMessage();
            throw new BadRequestException("Template '" + templateName
                    + "' cannot be used in NuSMV flow: " + reason);
        } catch (Exception e) {
            throw new InternalServerException(
                    "NuSMV precheck failed for template '" + templateName + "'.", e);
        } finally {
            cleanupGeneratedSmvFile(generated);
        }
    }

    private void cleanupGeneratedSmvFile(SmvGenerator.GenerateResult generated) {
        if (generated == null || generated.smvFile() == null) {
            return;
        }
        Path smvPath = generated.smvFile().toPath();
        try {
            Files.deleteIfExists(smvPath);
            Path parent = smvPath.getParent();
            if (parent != null) {
                Files.deleteIfExists(parent);
            }
        } catch (Exception e) {
            log.debug("Failed to cleanup template precheck file: {}", smvPath, e);
        }
    }
}
