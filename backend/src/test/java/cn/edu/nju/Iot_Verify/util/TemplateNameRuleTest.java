package cn.edu.nju.Iot_Verify.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The template-name rule exists to keep case-insensitive uniqueness comparable between Java and
 * MySQL — not to restrict the alphabet a user may name a device template in.
 */
class TemplateNameRuleTest {

    @Test
    void acceptsOrdinaryAsciiNames() {
        assertNull(TemplateNameRule.rejectionReason("Smoke Sensor"));
        assertNull(TemplateNameRule.rejectionReason("AC-2 (living room)"));
    }

    @Test
    void acceptsCaselessScripts() {
        // These have no case, so lowercasing is the identity in both engines. Rejecting them was a
        // pure usability loss: device labels on the same board already accept any Unicode.
        assertNull(TemplateNameRule.rejectionReason("温度传感器"), "Chinese template names are legitimate");
        assertNull(TemplateNameRule.rejectionReason("エアコン"), "Japanese template names are legitimate");
        assertNull(TemplateNameRule.rejectionReason("스모크 센서"), "Korean template names are legitimate");
    }

    @Test
    void stillRejectsCasedNonAsciiLetters() {
        // The actual parity risk: a cased letter outside ASCII may fold differently in MySQL.
        assertNotNull(TemplateNameRule.rejectionReason("Wärmepumpe"));
        assertNotNull(TemplateNameRule.rejectionReason("İstanbul"));
        assertNotNull(TemplateNameRule.rejectionReason("ẛ"));
    }

    @Test
    void rejectsControlCharactersAndEmptyNames() {
        assertNotNull(TemplateNameRule.rejectionReason(null));
        assertNotNull(TemplateNameRule.rejectionReason(""));
        assertNotNull(TemplateNameRule.rejectionReason("Heater\tunit"));
        assertNotNull(TemplateNameRule.rejectionReason("Heater\nunit"));
        assertNull(TemplateNameRule.rejectionReason("Heater unit"), "a plain space is allowed");
    }

    @Test
    void namesTheConstraintThatActuallyFailed() {
        // Callers build their user-facing message from this, so a tab must not be reported as a
        // non-ASCII problem and vice versa.
        assertTrue(TemplateNameRule.rejectionReason("Heater\tunit").contains("control characters"));
        assertTrue(TemplateNameRule.rejectionReason("Wärmepumpe").contains("cased non-ASCII"));
        assertTrue(TemplateNameRule.rejectionReason("").contains("empty"));
        assertEquals(null, TemplateNameRule.rejectionReason("温度传感器"));
    }

    @Test
    void everyAcceptedNameLowercasesIdempotentlyUnderRootLocale() {
        // The invariant the rule is actually protecting: for an accepted name, Java's Locale.ROOT
        // lowercase must equal a per-character lowercase, which is what MySQL LOWER() approximates.
        for (String name : new String[]{"Smoke Sensor", "温度传感器", "エアコン", "AC-2 (living room)"}) {
            StringBuilder perCharacter = new StringBuilder();
            for (char character : name.toCharArray()) {
                perCharacter.append(Character.toLowerCase(character));
            }
            assertEquals(perCharacter.toString(), name.toLowerCase(Locale.ROOT),
                    "case folding must agree for accepted name: " + name);
        }
    }
}
