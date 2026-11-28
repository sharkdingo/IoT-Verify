package cn.edu.nju.Iot_Verify.component.nusmv.generator;

import cn.edu.nju.Iot_Verify.component.nusmv.generator.data.DeviceSmvData;
import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceTemplateDto.DeviceManifest.InternalVariable;
import cn.edu.nju.Iot_Verify.dto.device.DeviceVerificationDto;
import cn.edu.nju.Iot_Verify.dto.model.EnvironmentValueProvenanceDto;
import cn.edu.nju.Iot_Verify.dto.model.ModelTokenSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Collects per-value semantic provenance for environment variables at model generation time.
 *
 * <p>This metadata captures the run's ordinary evolution rules. It does not record which
 * transition, including an autonomous assignment, produced a particular value change.
 */
@Slf4j
@Component
public class EnvironmentProvenanceCollector {

    /**
     * Collects provenance for every environment variable in a verification/simulation run.
     *
     * @param environmentVariables the board's environment pool
     * @param devices device instances submitted for this run
     * @param deviceSmvMap NuSMV metadata for each device, keyed by device varName
     * @return per-value provenance, one entry per environment variable
     */
    public List<EnvironmentValueProvenanceDto> collectEnvironmentProvenance(
            List<BoardEnvironmentVariableDto> environmentVariables,
            List<DeviceVerificationDto> devices,
            Map<String, DeviceSmvData> deviceSmvMap) {

        if (environmentVariables == null || environmentVariables.isEmpty()) {
            return List.of();
        }

        List<EnvironmentValueProvenanceDto> result = new ArrayList<>();

        for (BoardEnvironmentVariableDto envVar : environmentVariables) {
            String varName = envVar.getName();
            if (varName == null || varName.isBlank()) {
                continue;
            }

            // Find the canonical domain declaration from any device that declares this value
            EnvironmentDomain domain = findEnvironmentDomain(varName, devices, deviceSmvMap);
            if (domain == null) {
                log.warn("Environment variable {} has no domain declaration in any device template", varName);
                continue;
            }

            // Collect writers (devices that declare ImpactedVariables for this value)
            List<EnvironmentValueProvenanceDto.DeviceWriter> writers = collectWriters(
                    varName, devices, deviceSmvMap);

            // Collect readers (devices that declare Reads=true for this value)
            List<EnvironmentValueProvenanceDto.DeviceReader> readers = collectReaders(
                    varName, devices, deviceSmvMap);

            // Determine authorship and semantics
            EnvironmentValueProvenanceDto.AuthorshipCategory authorship;
            EnvironmentValueProvenanceDto.SemanticsTag semantics;

            if (writers.isEmpty()) {
                authorship = EnvironmentValueProvenanceDto.AuthorshipCategory.EXOGENOUS;
                // Purely exogenous discrete is a deliberate abstraction (may jump freely);
                // purely exogenous numeric with natural rate is exact (declared evolution).
                semantics = domain.isDiscrete()
                        ? EnvironmentValueProvenanceDto.SemanticsTag.ABSTRACTION
                        : EnvironmentValueProvenanceDto.SemanticsTag.EXACT;
            } else if (writers.size() == 1) {
                authorship = EnvironmentValueProvenanceDto.AuthorshipCategory.DEVICE_CONTROLLED;
                semantics = EnvironmentValueProvenanceDto.SemanticsTag.EXACT;
            } else {
                authorship = EnvironmentValueProvenanceDto.AuthorshipCategory.COMPOSED;
                semantics = EnvironmentValueProvenanceDto.SemanticsTag.EXACT;
            }

            String evolutionSummary = buildEvolutionSummary(domain, authorship, writers);

            result.add(EnvironmentValueProvenanceDto.builder()
                    .name(varName)
                    .type(determineValueType(domain))
                    .lowerBound(domain.lowerBound)
                    .upperBound(domain.upperBound)
                    .naturalChangeRate(domain.naturalChangeRate)
                    .values(domain.values)
                    .authorship(authorship)
                    .writers(writers)
                    .readers(readers)
                    .semantics(semantics)
                    .evolutionSummary(evolutionSummary)
                    .build());
        }

        return result;
    }

    private record EnvironmentDomain(Integer lowerBound, Integer upperBound,
                                     String naturalChangeRate, List<String> values) {

        boolean isDiscrete() {
            return values != null && !values.isEmpty();
        }
    }

    /**
     * Finds the domain declaration for an environment variable from any device template.
     * All devices declaring the same variable must agree on its domain (enforced elsewhere).
     */
    private EnvironmentDomain findEnvironmentDomain(
            String varName,
            List<DeviceVerificationDto> devices,
            Map<String, DeviceSmvData> deviceSmvMap) {

        return devices.stream()
                .flatMap(device -> sharedDeclarations(deviceSmvMap.get(device.getVarName()), varName))
                .findFirst()
                .map(variable -> new EnvironmentDomain(variable.getLowerBound(), variable.getUpperBound(),
                        variable.getNaturalChangeRate(), variable.getValues()))
                .orElse(null);
    }

    private Stream<InternalVariable> sharedDeclarations(DeviceSmvData smv, String varName) {
        if (smv == null || smv.getManifest() == null || smv.getManifest().getInternalVariables() == null) {
            return Stream.empty();
        }
        return smv.getManifest().getInternalVariables().stream()
                .filter(variable -> varName.equals(variable.getName()))
                .filter(variable -> !Boolean.TRUE.equals(variable.getIsInside()));
    }

    private List<EnvironmentValueProvenanceDto.DeviceWriter> collectWriters(
            String varName,
            List<DeviceVerificationDto> devices,
            Map<String, DeviceSmvData> deviceSmvMap) {

        List<EnvironmentValueProvenanceDto.DeviceWriter> writers = new ArrayList<>();

        for (DeviceVerificationDto dev : devices) {
            DeviceSmvData smv = deviceSmvMap.get(dev.getVarName());
            if (smv == null || smv.getImpactedVariables() == null) {
                continue;
            }

            if (smv.getImpactedVariables().contains(varName)) {
                writers.add(EnvironmentValueProvenanceDto.DeviceWriter.builder()
                        .deviceVarName(dev.getVarName())
                        .templateName(smv.getTemplateName())
                        .templateSource(dev.getModelTokenSource())
                        .build());
            }
        }

        return writers;
    }

    private List<EnvironmentValueProvenanceDto.DeviceReader> collectReaders(
            String varName,
            List<DeviceVerificationDto> devices,
            Map<String, DeviceSmvData> deviceSmvMap) {

        List<EnvironmentValueProvenanceDto.DeviceReader> readers = new ArrayList<>();

        for (DeviceVerificationDto dev : devices) {
            if (sharedDeclarations(deviceSmvMap.get(dev.getVarName()), varName)
                    .anyMatch(variable -> Boolean.TRUE.equals(variable.getReads()))) {
                readers.add(EnvironmentValueProvenanceDto.DeviceReader.builder()
                        .deviceVarName(dev.getVarName())
                        .build());
            }
        }

        return readers;
    }

    private EnvironmentValueProvenanceDto.ValueType determineValueType(EnvironmentDomain domain) {
        if (domain.isDiscrete()) {
            // Distinguish boolean from enum by values list
            if (domain.values.size() == 2
                    && (domain.values.contains("true") || domain.values.contains("TRUE"))
                    && (domain.values.contains("false") || domain.values.contains("FALSE"))) {
                return EnvironmentValueProvenanceDto.ValueType.DISCRETE_BOOLEAN;
            }
            return EnvironmentValueProvenanceDto.ValueType.DISCRETE_ENUM;
        }
        return EnvironmentValueProvenanceDto.ValueType.NUMERIC;
    }

    private String buildEvolutionSummary(
            EnvironmentDomain domain,
            EnvironmentValueProvenanceDto.AuthorshipCategory authorship,
            List<EnvironmentValueProvenanceDto.DeviceWriter> writers) {

        StringBuilder sb = new StringBuilder("Ordinary evolution applies when no autonomous assignment is active. ");

        switch (authorship) {
            case EXOGENOUS:
                if (domain.isDiscrete()) {
                    sb.append("May change to any declared value each step (deliberate conservative abstraction).");
                } else {
                    if (domain.naturalChangeRate != null && !domain.naturalChangeRate.isBlank()) {
                        sb.append("Evolves naturally (").append(domain.naturalChangeRate).append(").");
                    } else {
                        sb.append("Holds its value.");
                    }
                }
                break;

            case DEVICE_CONTROLLED:
                EnvironmentValueProvenanceDto.DeviceWriter writer = writers.get(0);
                sb.append("Controlled by device ").append(writer.getDeviceVarName());
                sb.append(" (").append(writer.getTemplateName()).append("). ");
                if (domain.isDiscrete()) {
                    sb.append("Changes only when a declared effect applies; holds otherwise.");
                } else {
                    sb.append("Changes by declared effects");
                    if (domain.naturalChangeRate != null && !domain.naturalChangeRate.isBlank()) {
                        sb.append(" and natural evolution (").append(domain.naturalChangeRate).append(")");
                    }
                    sb.append("; holds when no cause applies.");
                }
                break;

            case COMPOSED:
                sb.append("Affected by ").append(writers.size()).append(" devices: ");
                for (int i = 0; i < writers.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(writers.get(i).getDeviceVarName());
                }
                sb.append(". ");
                if (domain.isDiscrete()) {
                    // These writers necessarily agree: board assembly rejects a scene whose declared
                    // effects assign different values to one discrete value, so there is no
                    // order-dependent resolution to describe here.
                    sb.append("These devices declare the same value, which applies while one of their "
                            + "declared effects is active; the value holds otherwise.");
                } else {
                    sb.append("Effects are summed");
                    if (domain.naturalChangeRate != null && !domain.naturalChangeRate.isBlank()) {
                        sb.append("; natural evolution (").append(domain.naturalChangeRate).append(") adds to the total");
                    }
                    sb.append(".");
                }
                break;
        }

        return sb.toString();
    }
}
