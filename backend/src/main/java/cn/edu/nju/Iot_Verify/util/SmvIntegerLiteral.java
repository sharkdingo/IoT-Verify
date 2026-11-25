package cn.edu.nju.Iot_Verify.util;

import java.util.regex.Pattern;

/** Numeric literals rendered directly in NuSMV models. */
public final class SmvIntegerLiteral {
    // NuSMV lexes the magnitude before applying the sign, so -2147483648 is not a range literal.
    public static final int MIN_RANGE_BOUND = -Integer.MAX_VALUE;

    private static final Pattern SIGNED_DECIMAL = Pattern.compile("[+-]?[0-9]+");

    private SmvIntegerLiteral() {}

    public static int parse(String value) {
        String literal = value.trim();
        if (!SIGNED_DECIMAL.matcher(literal).matches()) {
            throw new NumberFormatException("Expected signed decimal digits 0-9");
        }
        return Integer.parseInt(literal);
    }
}
