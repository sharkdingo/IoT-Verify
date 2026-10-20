package cn.edu.nju.Iot_Verify.component.template;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.SmvRelationUtils;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ManifestConditionSemanticsTest {
    @Test
    void numericDomainsAcceptBoundsButRejectOverflowFractionsAndOutsideValues() {
        var variable = DeviceManifest.InternalVariable.builder().name("temperature")
                .lowerBound(-2).upperBound(2).build();
        assertNull(ManifestConditionSemantics.firstInvalidVariableValue(variable, List.of(" -2 ", "+2", "0")));
        var outside = ManifestConditionSemantics.firstInvalidVariableValue(variable, List.of("0", "3"));
        assertNotNull(outside);
        assertEquals(ManifestConditionSemantics.ValueError.OUT_OF_RANGE, outside.error());
        assertEquals(ManifestConditionSemantics.ValueError.NOT_INTEGER,
                ManifestConditionSemantics.firstInvalidVariableValue(variable, List.of("2147483648")).error());
        assertEquals(ManifestConditionSemantics.ValueError.NOT_INTEGER,
                ManifestConditionSemantics.firstInvalidVariableValue(variable, List.of("1.5")).error());
    }

    @Test
    void enumDomainUsesModelLiteralNormalizationAndRetainsCaseSensitivity() {
        var variable = DeviceManifest.InternalVariable.builder().name("level")
                .values(List.of("low", "very high")).build();
        assertNull(ManifestConditionSemantics.firstInvalidVariableValue(variable, List.of(" low ", "veryhigh")));
        var issue = ManifestConditionSemantics.firstInvalidVariableValue(variable, List.of("low", "Low"));
        assertEquals(ManifestConditionSemantics.ValueError.UNKNOWN_ENUM, issue.error());
        assertEquals("Low", issue.candidate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"on;high", "_;high", "on;_", " ;high"})
    void stateConditionsMaySelectAPartialTuple(String candidate) {
        assertTrue(ManifestConditionSemantics.isLegalState(candidate, List.of("power", "level"),
                Map.of("power", List.of("on", "off"), "level", List.of("high", "low"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"_;_", ";", "on;unknown", "on;high;extra", "on"})
    void stateConditionsRejectMissingConcreteSegmentsAndAmbiguousBareStates(String candidate) {
        assertFalse(ManifestConditionSemantics.isLegalState(candidate, List.of("power", "level"),
                Map.of("power", List.of("on", "off"), "level", List.of("on", "high", "low"))));
    }

    @Test
    void commandApiExistenceDoesNotGrantConditionSignalCapability() {
        var command = DeviceManifest.API.builder().name("turnOn").signal(false).build();
        var signal = DeviceManifest.API.builder().name("opened").signal(true).build();
        var manifest = DeviceManifest.builder().apis(List.of(command, signal)).build();
        assertSame(command, ManifestConditionSemantics.findApi(manifest, " turnOn "));
        assertFalse(ManifestConditionSemantics.hasSignalApi(manifest, "turnOn"));
        assertSame(signal, ManifestConditionSemantics.findSignalApi(manifest, " opened "));
        assertTrue(ManifestConditionSemantics.hasSignalApi(manifest, "opened"));
        assertNull(ManifestConditionSemantics.findApi(manifest, "missing"));
    }

    @Test
    void enumRelationsAreUnorderedWhileSecurityLiteralsIgnoreCase() {
        assertTrue(SmvRelationUtils.isEnumRelation(SmvRelationUtils.normalizeRelation("NOT_IN")));
        assertFalse(SmvRelationUtils.isEnumRelation(">"));
        assertFalse(SmvRelationUtils.isEnumRelation(null));
        assertTrue(ManifestConditionSemantics.matchesLiteral(" TRUSTED ", Set.of("trusted", "untrusted")));
        assertFalse(ManifestConditionSemantics.matchesLiteral("unknown", Set.of("trusted", "untrusted")));
    }
}
