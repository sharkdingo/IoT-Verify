package cn.edu.nju.Iot_Verify.util;

import java.util.List;

/** Preserve declared meaning before accepting an unambiguous normalized spelling. */
public final class EnumLiteralResolver {
    private EnumLiteralResolver() {
    }

    public static String resolve(List<String> allowedValues, String rawValue) {
        return resolve(allowedValues, rawValue, false);
    }

    public static String resolveIgnoringSpaces(List<String> allowedValues, String rawValue) {
        return resolve(allowedValues, rawValue, true);
    }

    private static String resolve(List<String> allowedValues, String rawValue, boolean ignoreSpaces) {
        if (allowedValues == null || rawValue == null) return null;
        String exact = rawValue.trim();
        for (String allowed : allowedValues) {
            if (exact.equals(allowed)) return allowed;
        }
        String normalized = ignoreSpaces ? clean(exact) : exact;
        String match = null;
        for (String allowed : allowedValues) {
            if (allowed != null && (ignoreSpaces ? clean(allowed) : allowed).equalsIgnoreCase(normalized)) {
                if (match != null && !match.equals(allowed)) return null;
                match = allowed;
            }
        }
        return match;
    }

    private static String clean(String value) {
        return value.trim().replace(" ", "");
    }
}
