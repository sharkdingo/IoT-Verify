package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.EnvironmentValueProvenanceDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frozen provenance lets trace tools explain the ordinary update rules even after the Board changes.
 *
 * <p>These tests pin the classification against
 * {@code docs/architecture/shared-value-semantics.md} §7, and specifically guard the composed
 * discrete case — an earlier revision of this collector described it as "last active effect wins",
 * which is the device-iteration-order behaviour the generator deliberately removed.
 */
class EnvironmentProvenanceCollectorTest {

    private final EnvironmentProvenanceCollector collector = new EnvironmentProvenanceCollector();

    @Test
    void aValueNoSubmittedDeviceWritesIsExogenousAndDiscloseditsAbstraction() {
        List<EnvironmentValueProvenanceDto> provenance = collect(
                discreteSensor("weather_1", "Weather", "weather", List.of("sunny", "rainy")));

        EnvironmentValueProvenanceDto weather = single(provenance);
        assertEquals(EnvironmentValueProvenanceDto.AuthorshipCategory.EXOGENOUS,
                weather.getAuthorship());
        // A discrete value nobody controls may take any declared value each step. That is a
        // deliberate over-approximation, so it must be reported as one rather than as exact.
        assertEquals(EnvironmentValueProvenanceDto.SemanticsTag.ABSTRACTION, weather.getSemantics());
        assertTrue(weather.getWriters().isEmpty(), "no submitted device declares it");
        assertEquals(EnvironmentValueProvenanceDto.ValueType.DISCRETE_ENUM, weather.getType());
    }

    @Test
    void anExogenousNumericValueIsExactBecauseItsIntervalIsDeclared() {
        List<EnvironmentValueProvenanceDto> provenance = collect(
                numericSensor("temp_1", "Temperature Sensor", "temperature", 15, 35, "[-1, 1]"));

        EnvironmentValueProvenanceDto temperature = single(provenance);
        assertEquals(EnvironmentValueProvenanceDto.AuthorshipCategory.EXOGENOUS,
                temperature.getAuthorship());
        // Unlike a discrete value, a numeric one carries a declared per-step interval, so its
        // evolution means exactly what the user wrote and nothing is being over-approximated.
        assertEquals(EnvironmentValueProvenanceDto.SemanticsTag.EXACT, temperature.getSemantics());
        assertEquals("[-1, 1]", temperature.getNaturalChangeRate());
        assertEquals(15, temperature.getLowerBound());
        assertEquals(35, temperature.getUpperBound());
    }

    @Test
    void theClockRateSummaryOnlyDescribesEvolutionOutsideItsReset() {
        DeviceSmvData clock = numericSensor("clock_1", "Clock", "time", 0, 23, "1");
        clock.getManifest().setTransitions(List.of(DeviceManifest.Transition.builder()
                .name("reset")
                .trigger(DeviceManifest.Trigger.builder().attribute("time").relation("=").value("23").build())
                .assignments(List.of(DeviceManifest.Assignment.builder().attribute("time").value("0").build()))
                .build()));

        String summary = single(collect(clock)).getEvolutionSummary();
        assertTrue(summary.contains("autonomous assignment"), summary);
        assertFalse(summary.contains("no device control"), summary);
    }

    @Test
    void oneWriterIsDeviceControlledAndNamesTheDeviceThatMayChangeIt() {
        DeviceSmvData light = discreteWriter("light_1", "Light", "illuminance",
                List.of("dim", "bright"));

        EnvironmentValueProvenanceDto illuminance = single(collect(light));
        assertEquals(EnvironmentValueProvenanceDto.AuthorshipCategory.DEVICE_CONTROLLED,
                illuminance.getAuthorship());
        assertEquals(EnvironmentValueProvenanceDto.SemanticsTag.EXACT, illuminance.getSemantics());
        assertEquals(1, illuminance.getWriters().size());
        assertEquals("light_1", illuminance.getWriters().get(0).getDeviceVarName());
        assertEquals("Light", illuminance.getWriters().get(0).getTemplateName());
        // The user needs to know which device to inspect, so the summary names it.
        assertTrue(illuminance.getEvolutionSummary().contains("light_1"),
                illuminance.getEvolutionSummary());
    }

    @Test
    void aComposedDiscreteValueIsNotDescribedAsOrderDependent() {
        // Two devices declaring the same discrete value only reach generation when their declared
        // effects agree -- board assembly rejects a genuine conflict. So the explanation must not
        // claim a winner is picked, which would describe the iteration-order behaviour the
        // generator removed and tell the user a verdict they can trust is arbitrary.
        List<EnvironmentValueProvenanceDto> provenance = collect(
                discreteWriter("purifier_1", "Air Purifier", "airQuality", List.of("poor", "good")),
                discreteWriter("fan_1", "Range Hood", "airQuality", List.of("poor", "good")));

        EnvironmentValueProvenanceDto airQuality = single(provenance);
        assertEquals(EnvironmentValueProvenanceDto.AuthorshipCategory.COMPOSED,
                airQuality.getAuthorship());
        assertEquals(EnvironmentValueProvenanceDto.SemanticsTag.EXACT, airQuality.getSemantics());
        assertEquals(2, airQuality.getWriters().size());

        String summary = airQuality.getEvolutionSummary().toLowerCase(Locale.ROOT);
        assertFalse(summary.contains("wins"),
                "a composed discrete value must not be explained as an order-dependent race: " + summary);
        assertFalse(summary.contains("last active effect"),
                "this wording described the removed first-writer-wins behaviour: " + summary);
    }

    @Test
    void aComposedNumericValueReportsAdditiveComposition() {
        List<EnvironmentValueProvenanceDto> provenance = collect(
                numericWriter("ac_1", "Air Conditioner", "temperature", 15, 35, "[-1, 1]"),
                numericWriter("heater_1", "Water Heater", "temperature", 15, 35, "[-1, 1]"));

        EnvironmentValueProvenanceDto temperature = single(provenance);
        assertEquals(EnvironmentValueProvenanceDto.AuthorshipCategory.COMPOSED,
                temperature.getAuthorship());
        // MEDIC's env.D.v is additive, so concurrent numeric effects sum and can never conflict.
        assertTrue(temperature.getEvolutionSummary().toLowerCase(Locale.ROOT).contains("summed"),
                temperature.getEvolutionSummary());
    }

    @Test
    void aDeviceReadingTheValueIsListedAsAReaderAndNotAsAWriter() {
        List<EnvironmentValueProvenanceDto> provenance = collect(
                numericSensor("temp_1", "Temperature Sensor", "temperature", 15, 35, "[-1, 1]"));

        EnvironmentValueProvenanceDto temperature = single(provenance);
        assertEquals(1, temperature.getReaders().size());
        assertEquals("temp_1", temperature.getReaders().get(0).getDeviceVarName());
        assertTrue(temperature.getWriters().isEmpty(),
                "reading a value is not writing it; conflating the two is what the Reads flag exists to prevent");
    }

    @Test
    void anAffectOnlyWriterIsNotReportedAsAReader() {
        // Reads=false means the device changes the value without observing it. Listing it as a
        // reader would claim its rules may use the value as a condition source, which the
        // generator refuses.
        List<EnvironmentValueProvenanceDto> provenance = collect(
                discreteWriter("light_1", "Light", "illuminance", List.of("dim", "bright")));

        EnvironmentValueProvenanceDto illuminance = single(provenance);
        assertTrue(illuminance.getReaders().isEmpty(),
                "an affect-only declaration must not appear as a reader");
        assertEquals(1, illuminance.getWriters().size());
    }

    @Test
    void aValueWithNoDeclaringTemplateIsOmittedRatherThanGuessedAt() {
        // The pool can outlive the device that required the value. Emitting a provenance entry with
        // an invented domain would put a rule in the frozen snapshot that no template ever stated.
        List<EnvironmentValueProvenanceDto> provenance = collector.collectEnvironmentProvenance(
                List.of(new BoardEnvironmentVariableDto("orphan", "1", "trusted", "public")),
                List.of(device("temp_1", "Temperature Sensor")),
                Map.of("temp_1", numericSensor("temp_1", "Temperature Sensor",
                        "temperature", 15, 35, "[-1, 1]")));

        assertTrue(provenance.isEmpty(), "a value no submitted template declares has no rule to report");
    }

    @Test
    void anEmptyEnvironmentPoolProducesNoProvenance() {
        assertTrue(collector.collectEnvironmentProvenance(List.of(), List.of(), Map.of()).isEmpty());
        assertTrue(collector.collectEnvironmentProvenance(null, List.of(), Map.of()).isEmpty());
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** Runs the collector over a pool derived from the shared values these devices declare. */
    private List<EnvironmentValueProvenanceDto> collect(DeviceSmvData... smvData) {
        List<DeviceVerificationDto> devices = new ArrayList<>();
        Map<String, DeviceSmvData> smvMap = new LinkedHashMap<>();
        List<BoardEnvironmentVariableDto> pool = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (DeviceSmvData smv : smvData) {
            devices.add(device(smv.getVarName(), smv.getTemplateName()));
            smvMap.put(smv.getVarName(), smv);
            for (DeviceManifest.InternalVariable variable : smv.getManifest().getInternalVariables()) {
                if (!Boolean.TRUE.equals(variable.getIsInside()) && !seen.contains(variable.getName())) {
                    seen.add(variable.getName());
                    pool.add(new BoardEnvironmentVariableDto(variable.getName(), null, "trusted", "public"));
                }
            }
        }
        return collector.collectEnvironmentProvenance(pool, devices, smvMap);
    }

    private static EnvironmentValueProvenanceDto single(List<EnvironmentValueProvenanceDto> provenance) {
        assertEquals(1, provenance.size(), "expected exactly one shared value: " + provenance);
        return provenance.get(0);
    }

    private static DeviceVerificationDto device(String varName, String templateName) {
        DeviceVerificationDto device = new DeviceVerificationDto();
        device.setVarName(varName);
        device.setTemplateName(templateName);
        device.setModelTokenSource(ModelTokenSource.BUNDLED);
        return device;
    }

    private static DeviceSmvData numericSensor(String varName, String templateName,
                                               String valueName, int lower, int upper, String rate) {
        return smv(varName, templateName, sharedNumeric(valueName, lower, upper, rate, true), List.of());
    }

    private static DeviceSmvData numericWriter(String varName, String templateName,
                                               String valueName, int lower, int upper, String rate) {
        return smv(varName, templateName, sharedNumeric(valueName, lower, upper, rate, false),
                List.of(valueName));
    }

    private static DeviceSmvData discreteSensor(String varName, String templateName,
                                                String valueName, List<String> values) {
        return smv(varName, templateName, sharedDiscrete(valueName, values, true), List.of());
    }

    private static DeviceSmvData discreteWriter(String varName, String templateName,
                                                String valueName, List<String> values) {
        return smv(varName, templateName, sharedDiscrete(valueName, values, false), List.of(valueName));
    }

    private static DeviceManifest.InternalVariable sharedNumeric(String name, int lower, int upper,
                                                                 String rate, boolean reads) {
        return DeviceManifest.InternalVariable.builder()
                .name(name).isInside(false).reads(reads).falsifiableWhenCompromised(reads)
                .lowerBound(lower).upperBound(upper).naturalChangeRate(rate)
                .trust("trusted").privacy("public").build();
    }

    private static DeviceManifest.InternalVariable sharedDiscrete(String name, List<String> values,
                                                                  boolean reads) {
        return DeviceManifest.InternalVariable.builder()
                .name(name).isInside(false).reads(reads).falsifiableWhenCompromised(reads)
                .values(values).trust("trusted").privacy("public").build();
    }

    private static DeviceSmvData smv(String varName, String templateName,
                                     DeviceManifest.InternalVariable variable,
                                     List<String> impacted) {
        DeviceSmvData smv = new DeviceSmvData();
        smv.setVarName(varName);
        smv.setTemplateName(templateName);
        smv.setModelTokenSource(ModelTokenSource.BUNDLED);
        smv.setVariables(List.of(variable));
        smv.getImpactedVariables().addAll(impacted);
        smv.setManifest(DeviceManifest.builder()
                .name(templateName)
                .internalVariables(List.of(variable))
                .impactedVariables(impacted)
                .build());
        return smv;
    }
}
