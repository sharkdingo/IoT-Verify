package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;

import java.util.Locale;
import java.util.Objects;

/** Shared-environment domain resolution without implying device read capability. */
public final class EnvironmentDomainUtils {

    private EnvironmentDomainUtils() {
    }

    public static DeviceManifest.InternalVariable resolveImpactDomain(DeviceManifest manifest, String name) {
        if (manifest == null || name == null || name.isBlank()) {
            return null;
        }
        String target = name.trim();
        if (manifest.getInternalVariables() != null) {
            for (DeviceManifest.InternalVariable variable : manifest.getInternalVariables()) {
                if (variable != null
                        && target.equals(variable.getName())
                        && !Boolean.TRUE.equals(variable.getIsInside())) {
                    return variable;
                }
            }
        }
        return null;
    }

    /** Selects the declared default before callers normalize it for their own boundary. */
    public static String rawDefaultValue(DeviceManifest.InternalVariable variable) {
        if (variable == null) {
            return null;
        }
        if (hasValues(variable)) {
            return variable.getValues().get(0);
        }
        return hasBounds(variable) ? String.valueOf(variable.getLowerBound()) : null;
    }

    /** Returns a user-facing semantic mismatch, or {@code null} when domains are equivalent. */
    public static String incompatibility(DeviceManifest.InternalVariable left,
                                         DeviceManifest.InternalVariable right) {
        if (left == null || right == null) {
            return "one declaration has no domain";
        }
        boolean leftEnum = hasValues(left);
        boolean rightEnum = hasValues(right);
        boolean leftNumeric = hasBounds(left);
        boolean rightNumeric = hasBounds(right);
        if (leftEnum != rightEnum || leftNumeric != rightNumeric) {
            return "type mismatch (" + describeType(left) + " versus " + describeType(right) + ")";
        }
        if (leftNumeric && (!Objects.equals(left.getLowerBound(), right.getLowerBound())
                || !Objects.equals(left.getUpperBound(), right.getUpperBound()))) {
            return "range mismatch (" + left.getLowerBound() + ".." + left.getUpperBound()
                    + " versus " + right.getLowerBound() + ".." + right.getUpperBound() + ")";
        }
        if (leftEnum && !Objects.equals(left.getValues(), right.getValues())) {
            return "enum values/order mismatch (" + left.getValues() + " versus " + right.getValues() + ")";
        }
        String leftRate = canonicalNaturalChangeRate(left.getNaturalChangeRate());
        String rightRate = canonicalNaturalChangeRate(right.getNaturalChangeRate());
        if (!leftRate.equals(rightRate)) {
            return "natural-change-rate mismatch (" + leftRate + " versus " + rightRate + ")";
        }
        String leftTrust = normalizeLabel(left.getTrust(), "untrusted");
        String rightTrust = normalizeLabel(right.getTrust(), "untrusted");
        if (!leftTrust.equals(rightTrust)) {
            return "default trust mismatch (" + leftTrust + " versus " + rightTrust + ")";
        }
        String leftPrivacy = normalizeLabel(left.getPrivacy(), "public");
        String rightPrivacy = normalizeLabel(right.getPrivacy(), "public");
        if (!leftPrivacy.equals(rightPrivacy)) {
            return "default privacy mismatch (" + leftPrivacy + " versus " + rightPrivacy + ")";
        }
        return null;
    }

    private static boolean hasValues(DeviceManifest.InternalVariable variable) {
        return variable.getValues() != null && !variable.getValues().isEmpty();
    }

    private static boolean hasBounds(DeviceManifest.InternalVariable variable) {
        return variable.getLowerBound() != null && variable.getUpperBound() != null;
    }

    private static String describeType(DeviceManifest.InternalVariable variable) {
        if (hasValues(variable)) return "enum" + variable.getValues();
        if (hasBounds(variable)) return variable.getLowerBound() + ".." + variable.getUpperBound();
        return "boolean";
    }

    private static String canonicalNaturalChangeRate(String raw) {
        return NaturalChangeRateParser.canonical(raw);
    }

    private static String normalizeLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }
}
