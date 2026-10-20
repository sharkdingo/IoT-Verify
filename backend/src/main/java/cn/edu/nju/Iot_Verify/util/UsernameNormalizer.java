package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.RequestLimits;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;

import java.text.Normalizer;

/*
 * The credential rules come from RequestLimits, not from literals here.
 *
 * This class had its own copy of the phone pattern and its own 3/20 display bounds — exactly what the comment
 * on RequestLimits was written to prevent. It mattered more than an ordinary duplicate:
 * `credentialLimitsMirror.spec.ts` asserts the *frontend* agrees with MIN_USERNAME_DISPLAY_LENGTH,
 * MAX_USERNAME_DISPLAY_LENGTH and PHONE_PATTERN, and those two length constants had no other backend reader at
 * all — so the bound was mirrored into every layer except the validator that actually enforces it. Changing one
 * there would have moved the client and the mirror test while this validator kept the old numbers.
 */
public final class UsernameNormalizer {

    private UsernameNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        int start = 0;
        int end = normalized.length();
        while (start < end) {
            int codePoint = normalized.codePointAt(start);
            if (!isEdgeWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = normalized.codePointBefore(end);
            if (!isEdgeWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return normalized.substring(start, end);
    }

    public static String normalizeAndValidate(String value) {
        String normalized = normalize(value);
        if (!isValid(normalized)) {
            throw new BadRequestException(
                    "Username must be 3-20 Unicode characters after trimming and must not contain control or format characters.");
        }
        if (isPhoneNumber(normalized)) {
            throw new BadRequestException("Username cannot use the phone-number format.");
        }
        return normalized;
    }

    public static boolean isValid(String value) {
        if (value == null) return false;
        int length = value.codePointCount(0, value.length());
        if (length < RequestLimits.MIN_USERNAME_DISPLAY_LENGTH
                || length > RequestLimits.MAX_USERNAME_DISPLAY_LENGTH) return false;
        return value.codePoints().noneMatch(codePoint ->
                Character.isISOControl(codePoint)
                        || Character.getType(codePoint) == Character.FORMAT
                        || Character.getType(codePoint) == Character.LINE_SEPARATOR
                        || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
    }

    public static boolean isPhoneNumber(String value) {
        return value != null && value.matches(RequestLimits.PHONE_PATTERN);
    }

    private static boolean isEdgeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
