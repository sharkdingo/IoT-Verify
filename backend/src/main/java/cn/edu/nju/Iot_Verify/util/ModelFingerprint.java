package cn.edu.nju.Iot_Verify.util;

import cn.edu.nju.Iot_Verify.util.mapper.BoardDataConverter.ModelInputSnapshot;
import cn.edu.nju.Iot_Verify.dto.board.BoardModelFingerprintsDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** One canonical board identity for task acceptance, history, and exploration inputs. */
@Component
public final class ModelFingerprint {

    private static final List<String> PRESENTATION_FIELDS = List.of(
            "deviceLabel", "templateLabel", "formula", "ruleString", "createdAt",
            "Description", "Icon", "description", "icon");

    private final ObjectMapper mapper;
    private final ObjectWriter writer;

    public ModelFingerprint(ObjectMapper objectMapper) {
        mapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        mapper.setConfig(mapper.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
        writer = mapper.writer();
    }

    public String modelFingerprint(ModelInputSnapshot snapshot) {
        return fingerprint(snapshot, true, null);
    }

    /** Simulation consumes devices, environment, rules, and templates, but no specifications. */
    public String simulationFingerprint(ModelInputSnapshot snapshot) {
        return fingerprint(snapshot, false, null);
    }

    /** Both identities share canonicalization; only their specification collections differ. */
    public BoardModelFingerprintsDto boardFingerprints(ModelInputSnapshot snapshot) {
        ObjectNode canonical = canonicalModel(snapshot, true, null);
        String verification = hash(canonical);
        canonical.set("specifications", mapper.createArrayNode());
        return new BoardModelFingerprintsDto(verification, hash(canonical));
    }

    public String paperDomainFingerprint(ModelInputSnapshot snapshot, int pathLength) {
        if (pathLength < 1 || pathLength > 50) {
            throw new IllegalArgumentException("pathLength must be between 1 and 50");
        }
        return fingerprint(snapshot, true, pathLength);
    }

    private String fingerprint(ModelInputSnapshot snapshot, boolean includeSpecifications, Integer pathLength) {
        return hash(canonicalModel(snapshot, includeSpecifications, pathLength));
    }

    private ObjectNode canonicalModel(ModelInputSnapshot snapshot, boolean includeSpecifications, Integer pathLength) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, Object> semanticModel = new LinkedHashMap<>();
        semanticModel.put("devices", snapshot.devices());
        semanticModel.put("environmentVariables", sorted(
                snapshot.environmentVariables(), variable -> variable.getName()));
        semanticModel.put("rules", snapshot.rules());
        semanticModel.put("specifications", includeSpecifications ? snapshot.specifications() : List.of());
        semanticModel.put("templateManifests", snapshot.templateManifests());
        if (pathLength != null) semanticModel.put("pathLength", pathLength);
        ObjectNode canonicalModel = mapper.valueToTree(semanticModel);
        canonicalModel.fields().forEachRemaining(field -> {
            if ("templateManifests".equals(field.getKey())) {
                // Map keys are template identities, even when they match presentation field names.
                field.getValue().elements().forEachRemaining(this::removePresentationFields);
            } else {
                removePresentationFields(field.getValue());
            }
        });
        return canonicalModel;
    }

    private String hash(JsonNode canonicalModel) {
        try {
            byte[] canonical = writer.writeValueAsBytes(canonicalModel);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot fingerprint the frozen Board snapshot", exception);
        }
    }

    private static <T> List<T> sorted(List<T> values, Function<T, String> identity) {
        return values.stream()
                .sorted(Comparator.comparing(value -> value == null
                        ? ""
                        : Objects.toString(identity.apply(value), "")))
                .toList();
    }

    private void removePresentationFields(JsonNode node) {
        if (node == null) return;
        if (node instanceof ObjectNode object) {
            object.remove(PRESENTATION_FIELDS);
            object.elements().forEachRemaining(this::removePresentationFields);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(this::removePresentationFields);
        }
    }
}
