package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This class renders the formula a verdict displays as "what was checked", so a divergence between it and
 * {@code SmvSpecificationBuilder} is a report that misdescribes its own answer — the defect class the
 * {@code variableSource} work exists to close. It had no test at all before these.
 */
class SpecificationFormulaPreviewTest {

    @Test
    void preservesApiBooleanComparisonsRatherThanAlwaysShowingThePositiveEvent() {
        String[][] cases = {
                {"=", "TRUE", "= TRUE"},
                {"=", "FALSE", "= FALSE"},
                {"!=", "TRUE", "!= TRUE"},
                {"!=", "FALSE", "!= FALSE"},
                {"in", "FALSE|TRUE", "in {FALSE, TRUE}"},
                {"not in", "TRUE;FALSE", "not in {TRUE, FALSE}"}
        };
        for (String[] test : cases) {
            SpecConditionDto condition = new SpecConditionDto();
            condition.setDeviceId("sensor_1");
            condition.setSide("a");
            condition.setTargetType("api");
            condition.setKey("Open");
            condition.setRelation(test[0]);
            condition.setValue(test[1]);
            assertEquals("CTL AG(actionEvent(\"Hall sensor\", \"Open\") " + test[2] + ")",
                    SpecificationFormulaPreview.format(spec("1", condition), context()));
        }
    }

    private static SpecificationFormulaPreview.Context context() {
        DeviceNodeDto node = new DeviceNodeDto();
        node.setId("sensor_1");
        node.setLabel("Hall sensor");
        node.setTemplateName("Temperature Sensor");
        return SpecificationFormulaPreview.context(List.of(node), List.of());
    }

    private static SpecConditionDto variableCondition(String variableSource) {
        SpecConditionDto condition = new SpecConditionDto();
        condition.setSide("a");
        condition.setDeviceId("sensor_1");
        condition.setTargetType("variable");
        condition.setKey("temperature");
        condition.setVariableSource(variableSource);
        condition.setRelation(">");
        condition.setValue("30");
        return condition;
    }

    private static SpecificationDto spec(String templateId, SpecConditionDto condition) {
        SpecificationDto spec = new SpecificationDto();
        spec.setName("Safety requirement");
        spec.setTemplateId(templateId);
        spec.setAConditions(List.of(condition));
        spec.setIfConditions(List.of());
        spec.setThenConditions(List.of());
        return spec;
    }

    @Test
    void readsTheDeclaredQuestionRatherThanInferringItFromSharedness() {
        // Inferring from the manifest is what made a `reported` condition display as the pool value while
        // NuSMV checked the device's own reading.
        assertEquals("CTL AG(Environment.\"temperature\" > 30)",
                SpecificationFormulaPreview.format(spec("1", variableCondition("environment")), context()));
        assertEquals("CTL AG(\"Hall sensor\".\"temperature\" > 30)",
                SpecificationFormulaPreview.format(spec("1", variableCondition("reported")), context()));
    }

    @Test
    void aConditionThatNeverChoseRendersUnresolvedRatherThanPickingASide() {
        String formula = SpecificationFormulaPreview.format(spec("1", variableCondition(null)), context());

        assertTrue(formula.contains("<unresolved>"),
                () -> "an unanswered question must not render as a valid formula, got " + formula);
    }

    @Test
    void template7NamesTheDeviceWhoseTrustLabelIsCheckedEvenForAnEnvironmentReading() {
        /*
         * A trust label is emitted per device — the generator emits `<device>.trust_<key>` whatever the
         * reading, and no pool-level `trust_a_<key>` is ever declared. (Per device is not per scope: for a
         * shared value the pool writes them all the same label.) Reusing the VALUE target rendered this as
         * `controlSource(Environment."temperature")`, naming a label the model does not have, so the preview
         * asserted something about the home's own provenance while the check was against one device's label.
         * The value half stays the pool value, because that is what `environment` means.
         */
        String formula = SpecificationFormulaPreview.format(
                spec("7", variableCondition("environment")), context());

        assertTrue(formula.contains("Environment.\"temperature\" > 30"), formula);
        assertTrue(formula.contains("controlSource(\"Hall sensor\".\"temperature\") = untrusted"), formula);
        assertFalse(formula.contains("controlSource(Environment."),
                () -> "no pool-level trust label exists, so the preview must not name one: " + formula);
    }

    @Test
    void template7StateConditionKeepsItsOwnTarget() {
        // The narrowing above applies only to `variable`; a state condition's label target already names the
        // device, and rewriting it would break a formula that was correct.
        SpecConditionDto state = new SpecConditionDto();
        state.setSide("a");
        state.setDeviceId("sensor_1");
        state.setTargetType("state");
        state.setKey("state");
        state.setRelation("=");
        state.setValue("working");

        String formula = SpecificationFormulaPreview.format(spec("7", state), context());

        assertTrue(formula.contains("controlSource(\"Hall sensor\".state) = untrusted"), formula);
    }

    @Test
    void aModeConditionNamesTheDeviceAndIsNeverReportedAsUnresolved() {
        /*
         * A `mode` condition carries no reading and never can. Letting it fall through to the
         * variableSource logic rendered it `<unresolved>."FanMode"` on EVERY template — an unanswered
         * question reported for a condition nobody was ever asked. Template 7's label term additionally
         * has to name the mode's active state, because the generator emits `trust_<mode>_<value>`, a
         * state-property label rather than a value label.
         */
        SpecConditionDto mode = new SpecConditionDto();
        mode.setSide("a");
        mode.setDeviceId("sensor_1");
        mode.setTargetType("mode");
        mode.setKey("FanMode");
        mode.setRelation("=");
        mode.setValue("auto");

        assertEquals("CTL AG(\"Hall sensor\".\"FanMode\" = \"auto\")",
                SpecificationFormulaPreview.format(spec("1", mode), context()));

        String safety = SpecificationFormulaPreview.format(spec("7", mode), context());
        assertTrue(safety.contains("controlSource(\"Hall sensor\".current \"FanMode\" state) = untrusted"),
                safety);
        assertFalse(safety.contains("<unresolved>"),
                () -> "a mode condition has no reading to leave unresolved: " + safety);
    }


    @Test
    void template7NeverWrapsAnAlreadyWrappedLabelTarget() {
        /*
         * `trust`/`privacy` are refused as template-7 A conditions by admission — the control-source label is
         * what the template derives, not something an author asserts. They used to fall through to a target
         * that already returns `controlSource(...)`, so the caller wrapped it a second time into
         * `controlSource(controlSource(...))`. Unreachable, but the repo's rule is to fail readably rather
         * than render nonsense if it ever leaks.
         */
        SpecConditionDto trust = new SpecConditionDto();
        trust.setSide("a");
        trust.setDeviceId("sensor_1");
        trust.setTargetType("trust");
        trust.setPropertyScope("variable");
        trust.setKey("temperature");
        trust.setRelation("=");
        trust.setValue("untrusted");

        String formula = SpecificationFormulaPreview.format(spec("7", trust), context());

        assertFalse(formula.contains("controlSource(controlSource("), formula);
    }

    @Test
    void template7ApiConditionNamesTheEndStateTheActionLeadsTo() {
        // The generator resolves an API's untrusted source through the action's EndState label, not the
        // event itself, so `controlSource(actionEvent(...))` named something the model does not label.
        SpecConditionDto api = new SpecConditionDto();
        api.setSide("a");
        api.setDeviceId("sensor_1");
        api.setTargetType("api");
        api.setKey("Open");
        api.setRelation("=");
        api.setValue("TRUE");

        String formula = SpecificationFormulaPreview.format(spec("7", api), context());

        assertTrue(formula.contains("controlSource(\"Hall sensor\".state after \"Open\") = untrusted"), formula);
        assertFalse(formula.contains("controlSource(actionEvent("), formula);
    }
}
