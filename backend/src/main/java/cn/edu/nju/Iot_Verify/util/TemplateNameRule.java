package cn.edu.nju.Iot_Verify.util;

/**
 * The one definition of a legal device-template name.
 *
 * <p>Previously three copies of a `^[\x20-\x7E]+$` pattern each carried the same comment, which is
 * how they could drift. The real constraint is narrower than "printable ASCII": `(user_id, name)`
 * uniqueness is case-insensitive, so {@code Locale.ROOT toLowerCase} and MySQL {@code LOWER()} must
 * agree on the name. Only *cased* letters can make them disagree (Turkish dotted I, U+1E9B, and
 * similar); a caseless script cannot, because lowercasing is the identity for it in both engines.
 *
 * <p>Rejecting all non-ASCII therefore refused legitimate names such as {@code 温度传感器} or
 * {@code Wärmepumpe} for no benefit, while device labels on the same board accept any Unicode and
 * the project is UTF-8 throughout.
 *
 * <p>This is <em>not</em> NuSMV identifier safety. Variable, mode, and state tokens must match
 * {@code ^[a-zA-Z_][a-zA-Z0-9_]*$} and are validated separately; a template name is display metadata
 * and never becomes an SMV identifier.
 */
public final class TemplateNameRule {

    private TemplateNameRule() {
    }

    /**
     * Why this name is illegal, or {@code null} when it is legal.
     *
     * <p>Callers must build their user-facing message from this rather than describing the rule
     * themselves: the constraint is "no control characters, no cased non-ASCII", so a fixed
     * "printable ASCII only" message misreports a name rejected for a tab and is simply false for the
     * accepted {@code 温度传感器}.
     */
    public static String rejectionReason(String name) {
        if (name == null || name.isEmpty()) {
            return "must not be empty";
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            // Control characters would corrupt logs, diagnostics, and generated file headers.
            if (character != ' ' && Character.isISOControl(character)) {
                return "must not contain control characters such as tabs or line breaks";
            }
            if (character > 0x7E && isCased(character)) {
                return "must not contain cased non-ASCII letters such as '" + character
                       + "', because case-insensitive name uniqueness would be ambiguous"
                       + " (uncased scripts, for example 温度传感器, are allowed)";
            }
        }
        return null;
    }

    /**
     * Whether case-folding this character could differ between Java and MySQL.
     *
     * <p>Also treats a character whose own case mapping changes it as cased, which catches cased
     * letters that report neither upper nor lower (for example U+1E9B).
     */
    private static boolean isCased(char character) {
        return Character.isUpperCase(character)
                || Character.isLowerCase(character)
                || Character.isTitleCase(character)
                || Character.toLowerCase(character) != character
                || Character.toUpperCase(character) != character;
    }
}
