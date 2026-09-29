package cn.edu.nju.Iot_Verify.component.nusmv;

import cn.edu.nju.Iot_Verify.component.nusmv.executor.NusmvExecutor.SpecCheckResult;
import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvGenerationContext.EmittedSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecResultAlignmentTest {

    private static EmittedSpec emitted(String id, String expression) {
        return new EmittedSpec(null, id, expression);
    }

    @Test
    void reorderedResultsAreMatchedToTheirSpecificationByExpression() {
        SpecCheckResult b = new SpecCheckResult("AG b", false, null);
        SpecCheckResult a = new SpecCheckResult("AG a", true, null);

        SpecResultAlignment.Alignment alignment = SpecResultAlignment.align(
                List.of(b, a), List.of(emitted("s1", "CTLSPEC AG(a)"), emitted("s2", "CTLSPEC AG(b)")));

        assertSame(a, alignment.aligned().get(0));
        assertSame(b, alignment.aligned().get(1));
        assertTrue(alignment.reordered());
        assertEquals(0, alignment.backFilled());
    }

    @Test
    void aResultNoExpressionMatchesIsAssignedByPositionAndCounted() {
        SpecCheckResult a = new SpecCheckResult("AG a", true, null);
        SpecCheckResult changedEcho = new SpecCheckResult("AG something_else", false, null);

        SpecResultAlignment.Alignment alignment = SpecResultAlignment.align(
                List.of(a, changedEcho), List.of(emitted("s1", "CTLSPEC AG(a)"), emitted("s2", "CTLSPEC AG(b)")));

        assertSame(changedEcho, alignment.aligned().get(1));
        assertEquals(1, alignment.backFilled(), "a positional guess must be reported, not passed off as a match");
        assertFalse(alignment.reordered());
    }
}
