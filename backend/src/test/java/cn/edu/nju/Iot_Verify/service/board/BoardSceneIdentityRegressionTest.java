package cn.edu.nju.Iot_Verify.service.board;

import cn.edu.nju.Iot_Verify.component.board.BoardBatchRequestParser;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneBatchMapper;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneRequestParser;
import cn.edu.nju.Iot_Verify.component.board.PortableSceneDiagnostics;
import cn.edu.nju.Iot_Verify.exception.BoardContentConflictException;
import cn.edu.nju.Iot_Verify.exception.GlobalExceptionHandler;
import cn.edu.nju.Iot_Verify.component.template.DeviceTemplateSchemaValidator;
import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceLayoutDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceNodeDto;
import cn.edu.nju.Iot_Verify.dto.device.DeviceRuntimeUpdateDto;
import cn.edu.nju.Iot_Verify.exception.ValidationException;
import cn.edu.nju.Iot_Verify.po.DeviceTemplatePo;
import cn.edu.nju.Iot_Verify.po.UserPo;
import cn.edu.nju.Iot_Verify.repository.BoardEditJournalRepository;
import cn.edu.nju.Iot_Verify.repository.BoardEnvironmentVariableRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceNodeRepository;
import cn.edu.nju.Iot_Verify.repository.DeviceTemplateRepository;
import cn.edu.nju.Iot_Verify.repository.RuleRepository;
import cn.edu.nju.Iot_Verify.repository.SpecificationRepository;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.service.impl.BoardStorageServiceImpl;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceNodeMapper;
import cn.edu.nju.Iot_Verify.util.mapper.DeviceTemplateMapper;
import cn.edu.nju.Iot_Verify.util.mapper.RuleMapper;
import cn.edu.nju.Iot_Verify.util.mapper.SpecificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.url=jdbc:h2:mem:boardSceneIdentity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BoardSceneIdentityRegressionTest {

    @Autowired private DeviceNodeRepository nodes;
    @Autowired private BoardEnvironmentVariableRepository environments;
    @Autowired private SpecificationRepository specs;
    @Autowired private RuleRepository rules;
    @Autowired private DeviceTemplateRepository templates;
    @Autowired private UserRepository users;
    @Autowired private BoardEditJournalRepository journalEntries;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private BoardStorageServiceImpl service;
    private Long userId;
    private ObjectNode scene;

    @BeforeEach
    void createBoardFixture() throws Exception {
        // H2 quotes Java strings in JSON columns; MySQL returns their JSON text unchanged.
        // This is the same fixture correction as BoardEditUndoIntegrationTest.
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            for (String column : java.util.List.of("variables_json", "privacies_json")) {
                statement.executeUpdate("ALTER TABLE device_node ALTER COLUMN " + column + " SET DATA TYPE CLOB");
            }
        }
        var journal = new BoardEditJournal(journalEntries, mapper);
        service = new BoardStorageServiceImpl(
                nodes, environments, specs, rules, null, templates, null,
                new TransactionTemplate(transactionManager), null,
                new SpecificationMapper(), new RuleMapper(), new DeviceNodeMapper(), null,
                new DeviceTemplateMapper(), null, users, journal);
        userId = users.saveAndFlush(UserPo.builder()
                .phone("13800138301").username("scene-identity-owner").password("hashed").build()).getId();
        String manifestJson;
        try (var manifest = getClass().getResourceAsStream("/deviceTemplate/Light.json")) {
            manifestJson = new String(Objects.requireNonNull(manifest).readAllBytes(), StandardCharsets.UTF_8);
        }
        templates.saveAndFlush(DeviceTemplatePo.builder()
                .userId(userId).name("Light").manifestJson(manifestJson).defaultTemplate(true).build());
        scene = (ObjectNode) mapper.readTree("""
                {
                  "schema":"iot-verify.board-scene","version":6,"templates":[],
                  "devices":[{
                    "id":"lamp_a","templateName":"Light","label":"Scene lamp",
                    "position":{"x":1,"y":2},"state":"off","width":176,"height":128
                  }],
                  "environmentVariables":[{
                    "name":"illuminance","value":"10","trust":"untrusted","privacy":"public"
                  }],
                  "rules":[],"specs":[]
                }
                """);
        scene.withArray("templates").addObject()
                .put("name", "Light").set("manifest", mapper.readTree(manifestJson));
    }

    @ParameterizedTest
    @ValueSource(strings = {" spaced_lamp ", "\tlamp", "lamp\n", "\u00a0lamp", "\ufefflamp", "lamp\u0085"})
    void rejectsWhitespaceIdentitiesAtPortableSceneAdmission(String id) {
        ((ObjectNode) scene.at("/devices/0")).put("id", id);

        ValidationException failure = assertThrows(ValidationException.class, this::parseScene);

        assertTrue(failure.getErrors().containsKey("devices[0].id"));
        assertTrue(nodes.findByUserId(userId).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {" spaced_lamp ", "\tlamp", "lamp\n", "\u00a0lamp", "\ufefflamp", "lamp\u0085"})
    void rejectsWhitespaceIdentitiesInDirectServiceWritesWithoutPartialPersistence(String id) {
        BoardBatchDto batch = parseScene();
        DeviceNodeDto device = batch.getNodes().get(0);
        device.setId(id);

        assertThrows(ValidationException.class, () -> service.saveBoardBatch(userId, batch));
        assertThrows(ValidationException.class, () -> service.addNodes(userId, java.util.List.of(device), null));
        assertThrows(ValidationException.class, () -> service.createNode(userId, current -> device));

        assertTrue(nodes.findByUserId(userId).isEmpty());
        assertTrue(environments.findByUserIdOrderByNameAsc(userId).isEmpty());
        assertEquals(0, journalEntries.countByUserId(userId));
        assertEquals(1, templates.findByUserId(userId).size());
    }

    @Test
    void legalSceneIdentityIsPreservedAndRemainsAddressable() {
        var imported = service.saveBoardBatch(userId, parseScene());
        assertEquals(1L, users.findSceneRevision(userId).orElseThrow());
        String acceptedId = imported.getNodes().get(0).getId();
        assertEquals("lamp_a", acceptedId);
        assertEquals(acceptedId, service.getNodes(userId).get(0).getId());

        var updated = assertDoesNotThrow(() -> service.updateNodeLayout(userId, acceptedId, layout()));
        assertEquals(acceptedId, updated.getCurrentDevice().getId());
        assertEquals(20.0, service.getNodes(userId).get(0).getPosition().getX());
        assertEquals(1L, users.findSceneRevision(userId).orElseThrow());
        service.renameNode(userId, acceptedId, "Renamed scene lamp", "Scene lamp");
        assertEquals(1L, users.findSceneRevision(userId).orElseThrow());
        var cleared = new BoardBatchDto(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        cleared.setImpactToken(service.previewBoardReplacement(userId).getImpactToken());
        service.saveBoardBatch(userId, cleared);
        assertEquals(2L, users.findSceneRevision(userId).orElseThrow());
    }

    @Test
    void unknownStateAttributesAreRejectedInsteadOfConvertedIntoStatePredicates() throws Exception {
        scene.set("rules", mapper.readTree("""
                [{"sources":[{
                  "fromId":"lamp_a","fromApi":"not-state","itemType":"state",
                  "relation":"=","value":"off"
                }],"toId":"lamp_a","toApi":"on"}]
                """));

        ValidationException failure = assertThrows(ValidationException.class,
                () -> service.saveBoardBatch(userId, parseScene()));

        assertTrue(failure.getErrors().containsKey("rules[0].conditions[0].attribute"), failure.getMessage());
        assertTrue(nodes.findByUserId(userId).isEmpty());
        assertTrue(rules.findByUserIdOrderByExecutionOrderAscIdAsc(userId).isEmpty());
    }

    @Test
    void targetedEndpointsRejectPaddedIdsInsteadOfAddressingAnotherIdentity() {
        service.saveBoardBatch(userId, parseScene());
        String paddedId = " lamp_a ";

        assertLookupRejected(() -> service.updateNodeLayout(userId, paddedId, layout()));
        assertLookupRejected(() -> service.updateNodeRuntime(userId, paddedId, new DeviceRuntimeUpdateDto()));
        assertLookupRejected(() -> service.renameNode(userId, paddedId, "Renamed", "Scene lamp"));
        assertLookupRejected(() -> service.previewNodeDeletion(userId, paddedId));
        assertLookupRejected(() -> service.deleteNodeCascade(userId, paddedId, "confirmed-impact"));

        DeviceNodeDto retained = service.getNodes(userId).get(0);
        assertEquals("lamp_a", retained.getId());
        assertEquals("Scene lamp", retained.getLabel());
        assertEquals(1.0, retained.getPosition().getX());
    }

    private void assertLookupRejected(Runnable action) {
        ValidationException failure = assertThrows(ValidationException.class, action::run);
        assertTrue(failure.getErrors().containsKey("nodeId"), failure.getMessage());
    }

    @Test
    void duplicateRulesReturnAnActionableTypedConflictWithoutWriting() throws Exception {
        scene.set("rules", mapper.readTree("""
                [{"sources":[{"fromId":"lamp_a","fromApi":"state","itemType":"state","relation":"=","value":"off"}],
                  "toId":"lamp_a","toApi":"on"},
                 {"sources":[{"fromId":"lamp_a","fromApi":"state","itemType":"state","relation":"=","value":"off"}],
                  "toId":"lamp_a","toApi":"on"}]
                """));
        BoardContentConflictException error = assertThrows(BoardContentConflictException.class,
                () -> service.saveBoardBatch(userId, parseScene()));
        assertEquals("DUPLICATE_RULE", error.getReasonCode());
        assertTrue(error.getErrors().containsKey("rules[1]"));
        assertTrue(error.getMessage().contains("Change the trigger or action"));
        assertRejectedSceneUntouched();
    }

    @Test
    void duplicateSpecificationsReturnAnActionableTypedConflictWithoutWriting() throws Exception {
        scene.set("specs", mapper.readTree("""
                [{"name": "Safety requirement", "templateId":"1","aConditions":[{"deviceId":"lamp_a","targetType":"state","key":"state","relation":"=","value":"off"}],
                  "ifConditions":[],"thenConditions":[]},
                 {"name": "Safety requirement", "templateId":"1","aConditions":[{"deviceId":"lamp_a","targetType":"state","key":"state","relation":"=","value":"off"}],
                  "ifConditions":[],"thenConditions":[]}]
                """));
        BoardContentConflictException error = assertThrows(BoardContentConflictException.class,
                () -> service.saveBoardBatch(userId, parseScene()));
        assertEquals("DUPLICATE_SPECIFICATION", error.getReasonCode());
        assertTrue(error.getErrors().containsKey("specs[1]"));
        assertRejectedSceneUntouched();
    }

    @Test
    void templateMismatchRetainsItsNameAndPortableFieldInTheConflictEnvelope() {
        ((ObjectNode) scene.at("/templates/0/manifest")).put("InitState", "off");
        BoardContentConflictException error = assertThrows(BoardContentConflictException.class,
                () -> service.saveBoardBatch(userId, parseScene()));
        JsonNode response = mapper.valueToTree(new GlobalExceptionHandler()
                .handleBoardContentConflictException(PortableSceneDiagnostics.portable(error)).getBody());
        assertEquals(409, response.path("code").asInt());
        assertEquals("SCENE_TEMPLATE_MISMATCH", response.path("data").path("reasonCode").asText());
        assertEquals("Light", response.path("data").path("templateName").asText());
        assertTrue(response.path("data").path("errors").has("templates[0].manifest"));
        assertRejectedSceneUntouched();
    }

    private void assertRejectedSceneUntouched() {
        assertTrue(nodes.findByUserId(userId).isEmpty());
        assertTrue(rules.findByUserIdOrderByExecutionOrderAscIdAsc(userId).isEmpty());
        assertEquals(0, journalEntries.countByUserId(userId));
    }

    private DeviceLayoutDto layout() {
        DeviceLayoutDto layout = new DeviceLayoutDto();
        DeviceNodeDto.Position position = new DeviceNodeDto.Position();
        position.setX(20.0);
        position.setY(30.0);
        layout.setPosition(position);
        layout.setWidth(176);
        layout.setHeight(128);
        return layout;
    }

    private BoardBatchDto parseScene() {
        ObjectNode body = mapper.createObjectNode()
                .put("impactToken", service.previewBoardReplacement(userId).getImpactToken());
        body.set("scene", scene);

        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var batchParser = new BoardBatchRequestParser(
                    mapper, validators.getValidator(), new DeviceTemplateSchemaValidator(mapper));
            var parser = new PortableSceneRequestParser(new PortableSceneBatchMapper(mapper, batchParser));
            return parser.parse(body);
        }
    }
}
