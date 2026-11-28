package cn.edu.nju.Iot_Verify.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalChangeRateParserTest {

    @Test
    void parsesTheSchemaGrammarAndSingleValueShorthand() {
        assertEquals(new NaturalChangeRateParser.RateRange(0, 0),
                NaturalChangeRateParser.parse(null));
        assertEquals(new NaturalChangeRateParser.RateRange(0, 1),
                NaturalChangeRateParser.parse("1"));
        assertEquals(new NaturalChangeRateParser.RateRange(-2, 0),
                NaturalChangeRateParser.parse("-2"));
        assertEquals(new NaturalChangeRateParser.RateRange(-1, 1),
                NaturalChangeRateParser.parse("[-1, 1]"));
        assertEquals(new NaturalChangeRateParser.RateRange(2, 3),
                NaturalChangeRateParser.parse("[2,3]"));
    }

    @Test
    void rejectsSyntaxThatTheFrontendAndJsonSchemaReject() {
        for (String raw : new String[]{"", " 1", "1,2", "[1,2,3]", "[[1,2]]", "[1,]", "+1"}) {
            assertThrows(NaturalChangeRateParser.ParseException.class,
                    () -> NaturalChangeRateParser.parse(raw), raw);
        }
    }

    @Test
    void rejectsOverflowAndDescendingIntervalsWithoutReinterpretingThem() {
        NaturalChangeRateParser.ParseException overflow = assertThrows(
                NaturalChangeRateParser.ParseException.class,
                () -> NaturalChangeRateParser.parse("2147483648"));
        assertFalse(overflow.isDescending());

        NaturalChangeRateParser.ParseException descending = assertThrows(
                NaturalChangeRateParser.ParseException.class,
                () -> NaturalChangeRateParser.parse("[3,2]"));
        assertTrue(descending.isDescending());
    }

    @Test
    void canonicalFormMatchesEquivalentDeclarationsAndPreservesMalformedText() {
        assertEquals("0..1", NaturalChangeRateParser.canonical("1"));
        assertEquals("0..1", NaturalChangeRateParser.canonical("[0, 1]"));
        assertEquals("1,2", NaturalChangeRateParser.canonical("1,2"));
    }

    /**
     * A declared interval constrains {@code v' - v}, so the model must admit every integer in it.
     * Emitting only the endpoints let NuSMV prove {@code AG (v = 5 -> AX v != 6)} for a variable
     * declared {@code [-3, 3]}, which is an unsound SATISFIED verdict.
     */
    @Test
    void admissibleDeltasCoverTheWholeDeclaredIntervalNotJustItsEndpoints() {
        assertEquals(List.of(-3, -2, -1, 0, 1, 2, 3),
                NaturalChangeRateParser.parse("[-3, 3]").admissibleDeltas());
        assertEquals(List.of(-1, 0, 1),
                NaturalChangeRateParser.parse("[-1, 1]").admissibleDeltas());
        assertEquals(List.of(0, 1),
                NaturalChangeRateParser.parse("1").admissibleDeltas());
        assertEquals(List.of(0), NaturalChangeRateParser.parse("0").admissibleDeltas());
    }

    /**
     * The interval is the declaration's whole meaning, so an interval that excludes zero says the
     * value <em>always</em> changes. Injecting a stutter made "this tank always drains 2-4 per step"
     * unstatable: NuSMV then reported {@code AF (level = 0)} false and offered a trace where the
     * mandatory drain never happened, which the user cannot act on.
     */
    @Test
    void anIntervalExcludingZeroMeansTheValueAlwaysChanges() {
        assertEquals(List.of(2, 3, 4),
                NaturalChangeRateParser.parse("[2, 4]").admissibleDeltas());
        assertEquals(List.of(-4, -3, -2),
                NaturalChangeRateParser.parse("[-4, -2]").admissibleDeltas());
    }

    @Test
    void holdingStillIsExpressedByWritingAnIntervalThatContainsZero() {
        assertEquals(List.of(0, 1, 2, 3, 4),
                NaturalChangeRateParser.parse("[0, 4]").admissibleDeltas());
        assertEquals(List.of(-4, -3, -2, -1, 0),
                NaturalChangeRateParser.parse("[-4, 0]").admissibleDeltas());
    }

    @Test
    void spanReportsTheDeclarationWithoutOverflowing() {
        assertEquals(6L, NaturalChangeRateParser.parse("[-3, 3]").span());
        assertEquals((long) Integer.MAX_VALUE - Integer.MIN_VALUE,
                NaturalChangeRateParser.parse(
                        "[" + Integer.MIN_VALUE + ", " + Integer.MAX_VALUE + "]").span());
    }
}
