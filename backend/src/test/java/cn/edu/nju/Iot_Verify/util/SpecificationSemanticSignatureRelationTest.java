package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A specification's identity must not depend on how its relation was spelled.
 *
 * <p>`exactlyMatches` gates "delete this specification only if it has not changed" and duplicate-spec detection,
 * so a signature that disagrees with itself over an alias lets a delete land on a specification the user edited.
 *
 * <p>This class exists because the relation normaliser here was a **private copy** of the switch in
 * {@code SmvRelationUtils}, and the copy had drifted: its {@code NEQ} case omitted the {@code "!="} alias the
 * canonical version carries. The two agreed only through the passthrough default, so adding an alias to the
 * generator alone would have split them silently — and the suite had no direct test of this class at all, only
 * indirect coverage through {@code BoardStorageServiceImpl}'s delete paths. The normaliser now delegates to
 * {@code SmvRelationUtils}; these assertions pin the property that matters rather than the delegation itself.
 */
class SpecificationSemanticSignatureRelationTest {

    private static SpecificationDto specWithRelation(String relation) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setTargetType("variable");
        condition.setDeviceId("thermostat_1");
        condition.setKey("temperature");
        condition.setRelation(relation);
        condition.setValue("30");

        SpecificationDto spec = new SpecificationDto();
        spec.setName("Safety requirement");
        spec.setTemplateId("1");
        spec.setAConditions(List.of(condition));
        return spec;
    }

    @Test
    @DisplayName("every spelling of one relation yields the same signature")
    void aliasesOfOneRelationAreOneIdentity() {
        // The pairs the canonical normaliser folds together. `NEQ`/`!=` is the pair the drifted copy missed.
        for (String[] pair : new String[][] {
                { "EQ", "=" }, { "EQ", "==" },
                { "NEQ", "!=" },
                { "GT", ">" }, { "GTE", ">=" },
                { "LT", "<" }, { "LTE", "<=" },
                { "IN", "in" }, { "NOT_IN", "not in" }
        }) {
            assertTrue(
                    SpecificationSemanticSignature.exactlyMatches(
                            specWithRelation(pair[0]), specWithRelation(pair[1])),
                    "'" + pair[0] + "' and '" + pair[1] + "' are the same relation and must share one signature");
        }
    }

    private static SpecificationDto specWithVariableSource(String variableSource) {
        SpecificationDto spec = specWithRelation(">");
        spec.getAConditions().get(0).setVariableSource(variableSource);
        return spec;
    }

    @Test
    @DisplayName("variableSource participates in specification identity")
    void variableSourceIsPartOfAuthoredIdentity() {
        /*
         * The same key asked two ways is two questions: `environment` compiles to the shared pool value,
         * `reported` to the device's own reading, and they diverge once that device is compromised. The
         * signature omitted the field when it was added, so these compared as identical — meaning "delete
         * only if unchanged" and the undo conflict check would have accepted one as the other and landed a
         * delete on a specification the user never reviewed.
         */
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                        specWithVariableSource("environment"), specWithVariableSource("reported")),
                "asking about the home and asking what a device reported are different specifications");
        assertTrue(SpecificationSemanticSignature.exactlyMatches(
                        specWithVariableSource("environment"), specWithVariableSource("environment")),
                "the same question must still be one identity");
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                        specWithVariableSource(null), specWithVariableSource("reported")),
                "a specification that never chose its question is not one that chose reported");
    }

    @Test
    @DisplayName("different relations stay different identities")
    void distinctRelationsDoNotCollapse() {
        // The negative half: folding aliases must not fold *operators*, or a delete would land on an edited spec.
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                specWithRelation(">="), specWithRelation("<=")));
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                specWithRelation("="), specWithRelation("!=")));
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                specWithRelation("in"), specWithRelation("not in")));
    }

    @Test
    @DisplayName("a blank relation is not the same as an absent one being ignored")
    void blankRelationKeepsTheEmptyStringContract() {
        /*
         * The delegation maps `SmvRelationUtils`' `null` return to `""`, and that is load-bearing: a signature is a
         * string key, so a null inside one would compare unequal to an absent relation rather than equal to it.
         * A spec with no relation and a spec with a blank relation are the same specification.
         */
        assertTrue(SpecificationSemanticSignature.exactlyMatches(
                specWithRelation(null), specWithRelation("   ")));
        assertFalse(SpecificationSemanticSignature.exactlyMatches(
                specWithRelation(null), specWithRelation("=")));
    }
}
