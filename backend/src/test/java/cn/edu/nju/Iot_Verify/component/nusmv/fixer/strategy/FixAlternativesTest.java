package cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy;

import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixContext;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.FixStrategy.StrategyOutcome;
import cn.edu.nju.Iot_Verify.component.nusmv.fixer.strategy.FixStrategyUtils.Verification;
import cn.edu.nju.Iot_Verify.dto.fix.FixSuggestionDto;
import cn.edu.nju.Iot_Verify.dto.fix.PreexistingViolationDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FixAlternativesTest {

    private final FixAlternatives alternatives = new FixAlternatives(context());

    @Test
    void listsOnlyMinimalRepairsWhateverOrderTheyArriveIn() {
        accept(Set.of("a", "b"), "a+b");
        accept(Set.of("a"), "a");        // replaces a+b, which only adds an unneeded change
        accept(Set.of("a", "c"), "a+c"); // contains a, so it is not a real alternative
        accept(Set.of("b"), "b");

        assertEquals(List.of("a", "b"), descriptions(alternatives.outcome(true)));
        assertEquals(List.of(Set.of("a"), Set.of("b")), alternatives.listedChanges());
        assertTrue(alternatives.covers(Set.of("a", "c")));
        assertFalse(alternatives.covers(Set.of("c")));
    }

    @Test
    void listsTheSmallestChangeFirst() {
        accept(Set.of("a", "b"), "a+b");
        accept(Set.of("c"), "c");

        assertEquals(List.of("c", "a+b"), descriptions(alternatives.outcome(true)));
    }

    /** An unchecked candidate might have been a repair, unless a listed repair already covers it. */
    @Test
    void theListingIsCompleteOnlyWhenTheSearchEndedAndEveryUncheckedCandidateIsCovered() {
        assertFalse(alternatives.outcome(false).alternativesComplete(), "a search cut short proves nothing");
        assertTrue(alternatives.outcome(true).alternativesComplete());

        alternatives.notAccepted(Set.of("a", "b"), Verification.inconclusive());
        assertFalse(alternatives.outcome(true).alternativesComplete(), "a+b was never checked");

        accept(Set.of("a"), "a");
        assertTrue(alternatives.outcome(true).alternativesComplete(), "a+b is not minimal next to a");
    }

    @Test
    void onlyARejectionCountsAsRejected() {
        alternatives.notAccepted(Set.of("a"), Verification.inconclusive());
        assertFalse(alternatives.anyRejected());

        alternatives.notAccepted(Set.of("b"), Verification.rejected());
        assertTrue(alternatives.anyRejected());
        assertFalse(alternatives.outcome(true).alternativesComplete(), "a is still unchecked");
    }

    @Test
    void refusesToListACandidateThatWasNotAcceptedOrChangesNothing() {
        FixSuggestionDto suggestion = suggestion("x");
        assertThrows(IllegalArgumentException.class,
                () -> alternatives.accept(Set.of("a"), suggestion, Verification.rejected()));
        assertThrows(IllegalArgumentException.class,
                () -> alternatives.accept(Set.of("a"), suggestion, Verification.inconclusive()));
        assertThrows(IllegalArgumentException.class,
                () -> alternatives.accept(Set.of(), suggestion, Verification.accepted(List.of())));
        assertTrue(alternatives.outcome(true).suggestions().isEmpty());
    }

    @Test
    void eachListedRepairNamesTheViolationsItsOwnCheckFoundAlreadyPresent() {
        alternatives.accept(Set.of("a"), suggestion("a"), Verification.accepted(List.of("other")));
        alternatives.accept(Set.of("b"), suggestion("b"), Verification.accepted(List.of()));

        List<FixSuggestionDto> listed = alternatives.outcome(true).suggestions();
        List<PreexistingViolationDto> violations = listed.get(0).getPreexistingViolations();
        assertEquals(1, violations.size());
        assertEquals("other", violations.get(0).getSpecId());
        assertEquals("1", violations.get(0).getTemplateId());
        assertEquals("CTL AG(TRUE)", violations.get(0).getFormulaPreview());
        assertEquals(List.of(), listed.get(1).getPreexistingViolations());
    }

    @Test
    void aPreexistingViolationOfAnUnknownSpecificationIsAnError() {
        assertThrows(IllegalStateException.class, () -> alternatives.accept(
                Set.of("a"), suggestion("a"), Verification.accepted(List.of("missing"))));
    }

    @Test
    void isFullAtTheListingLimit() {
        for (int i = 0; i < FixAlternatives.LIMIT; i++) {
            assertFalse(alternatives.full());
            accept(Set.of("change" + i), "change" + i);
        }
        assertTrue(alternatives.full());
    }

    private void accept(Set<String> changes, String description) {
        alternatives.accept(changes, suggestion(description), Verification.accepted(List.of()));
    }

    private static FixSuggestionDto suggestion(String description) {
        return FixSuggestionDto.builder().strategy("remove").description(description).build();
    }

    private static List<String> descriptions(StrategyOutcome outcome) {
        return outcome.suggestions().stream().map(FixSuggestionDto::getDescription).toList();
    }

    private static FixContext context() {
        SpecificationDto target = new SpecificationDto();
        target.setId("target");
        target.setTemplateId("1");
        SpecificationDto other = new SpecificationDto();
        other.setId("other");
        other.setTemplateId("1");
        return FixContext.builder().devices(List.of()).specs(List.of(target, other)).build();
    }
}
