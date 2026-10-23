package cn.edu.nju.Iot_Verify.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnumLiteralResolverTest {
    @Test
    void exactSpellingWinsAndAmbiguousCaseIsRejected() {
        assertEquals("On", EnumLiteralResolver.resolve(List.of("on", "On"), " On "));
        assertNull(EnumLiteralResolver.resolve(List.of("on", "On"), "ON"));
        assertEquals("on", EnumLiteralResolver.resolve(List.of("on"), "ON"));
        assertEquals("on", EnumLiteralResolver.resolve(List.of("on", "on"), "ON"));
    }

    @Test
    void callSitesKeepTheirExistingSpacePolicyWithoutGuessingBetweenLiterals() {
        assertNull(EnumLiteralResolver.resolve(List.of("on"), "o n"));
        assertEquals("on", EnumLiteralResolver.resolveIgnoringSpaces(List.of("on"), "o n"));
        assertNull(EnumLiteralResolver.resolveIgnoringSpaces(List.of("on", "On"), "O N"));
        assertEquals("On", EnumLiteralResolver.resolveIgnoringSpaces(List.of("on", "On"), "On"));
        assertNull(EnumLiteralResolver.resolveIgnoringSpaces(List.of("on", "On"), "o n"));
    }
}
