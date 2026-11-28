package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.rule.RuleDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecConditionDto;
import cn.edu.nju.Iot_Verify.dto.spec.SpecificationDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shipped scene in {@code docs/examples} must survive the real import request boundary.
 *
 * <p>This is the assertion the suite previously could not make. The scene NuSMV tests hand-built their
 * own DTOs from the file, and the frontend built the request body itself, so nothing checked that a
 * shipped scene actually passes {@code POST /api/board/scene}. A file could be valid to the exporter,
 * generate correct SMV in tests, and still be refused by the endpoint users reach.</p>
 *
 * <p>It runs at the request boundary, not the service, so it needs no database: what it pins is
 * mapping plus bean validation, which is exactly where the field-dropping defects lived.</p>
 */
class ShippedSceneImportTest {

    private static final Path EXAMPLES = Path.of("..", "docs", "examples");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<Path> scenePaths() throws IOException {
        try (var paths = Files.list(EXAMPLES)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private record AdmittedScene(String name, JsonNode source, BoardBatchDto batch) {}

    private void forEachAdmittedScene(List<Path> scenes, Consumer<AdmittedScene> check) throws IOException {
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var batchParser = new BoardBatchRequestParser(
                    objectMapper, validators.getValidator(), new DeviceTemplateSchemaValidator(objectMapper));
            var parser = new PortableSceneRequestParser(new PortableSceneBatchMapper(objectMapper, batchParser));
            for (Path scenePath : scenes) {
                JsonNode source = objectMapper.readTree(Files.readString(scenePath));
                ObjectNode body = objectMapper.createObjectNode().put("impactToken", "confirmed-preview-token");
                // Upload the scene verbatim beside its token, keeping the count witness independent.
                body.set("scene", source.deepCopy());
                check.accept(new AdmittedScene(scenePath.getFileName().toString(), source, parser.parse(body)));
            }
        }
    }

    @Test
    void everyShippedSceneIsAdmittedByTheImportEndpointBoundary() throws IOException {
        List<Path> scenes = scenePaths();
        assertFalse(scenes.isEmpty(), "No scene files found in " + EXAMPLES.toAbsolutePath());

        forEachAdmittedScene(scenes, admitted -> {
            String name = admitted.name();
            BoardBatchDto batch = admitted.batch();
            JsonNode scene = admitted.source();

            // Nothing silently lost between the file and the admitted command.
            assertEquals(scene.path("devices").size(), batch.getNodes().size(), name + ": devices");
            assertEquals(scene.path("rules").size(), batch.getRules().size(), name + ": rules");
            assertEquals(scene.path("specs").size(), batch.getSpecs().size(), name + ": specs");
            assertEquals(scene.path("environmentVariables").size(),
                    batch.getEnvironmentVariables().size(), name + ": environmentVariables");
            assertEquals(scene.path("templates").size(),
                    batch.getTemplateSnapshots().size(), name + ": templateSnapshots");
            assertEquals("confirmed-preview-token", batch.getImpactToken(), name + ": impactToken");
        });
    }

    /**
     * The field whose loss caused three separate production failures. Asserted against the shipped
     * files rather than a fixture, so a scene that actually uses it proves the path carries it.
     */
    @Test
    void variableConditionsInShippedScenesKeepTheirVariableSource() throws IOException {
        List<String> checked = new ArrayList<>();

        forEachAdmittedScene(scenePaths(), admitted -> {
            String name = admitted.name();
            for (SpecificationDto spec : admitted.batch().getSpecs()) {
                List<SpecConditionDto> all = new ArrayList<>();
                all.addAll(spec.getAConditions());
                all.addAll(spec.getIfConditions());
                all.addAll(spec.getThenConditions());
                for (SpecConditionDto condition : all) {
                    if (!"variable".equalsIgnoreCase(condition.getTargetType())) {
                        continue;
                    }
                    assertNotNull(condition.getVariableSource(),
                            name + ": a variable condition on '" + condition.getDeviceId()
                                    + "' lost its variableSource crossing the import boundary");
                    checked.add(name);
                }
            }
        });

        // Without a scene that exercises the field, the loop above asserts nothing.
        assertFalse(checked.isEmpty(),
                "No shipped scene has a variable specification condition, so this test proves nothing. "
                        + "Add one, or delete this test.");
    }

    /**
     * An {@code api} rule source is a signal event. Bean validation rejects one carrying a relation or
     * value, so emitting blanks instead of omitting them would fail admission on a shape no shipped
     * file expresses — the defect this asserts against.
     */
    @Test
    void apiRuleSourcesInShippedScenesCarryNoRelationOrValue() throws IOException {
        List<String> checked = new ArrayList<>();

        forEachAdmittedScene(scenePaths(), admitted -> {
            String name = admitted.name();
            for (RuleDto rule : admitted.batch().getRules()) {
                for (RuleDto.Condition condition : rule.getConditions()) {
                    if (!"api".equalsIgnoreCase(condition.getTargetType())) {
                        continue;
                    }
                    assertTrue(condition.getRelation() == null || condition.getRelation().isBlank(),
                            name + ": api source kept a relation");
                    assertTrue(condition.getValue() == null || condition.getValue().isBlank(),
                            name + ": api source kept a value");
                    checked.add(name);
                }
            }
        });

        assertFalse(checked.isEmpty(),
                "No shipped scene has an api rule source, so this test proves nothing.");
    }
}
