package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.dto.board.BoardBatchDto;
import cn.edu.nju.Iot_Verify.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Request boundary for {@code POST /api/board/scene}.
 *
 * <p>The body is {@code { impactToken, scene }}. The token stays outside {@code scene} because it is
 * request-only and must never become portable scene semantics — nesting the file verbatim is what
 * lets an exported file be uploaded unmodified.</p>
 */
@Component
@RequiredArgsConstructor
public class PortableSceneRequestParser {

    /** Provenance prefix for specification ids minted from an uploaded scene file. */
    private static final String FILE_SPEC_ID_PREFIX = "scene_spec_";

    private static final Set<String> ALLOWED_BODY_FIELDS = Set.of("impactToken", "scene");

    private final PortableSceneBatchMapper batchMapper;

    public BoardBatchDto parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BadRequestException(
                    "Scene import request must be a JSON object; no board data was changed.",
                    Map.of("request", "A JSON object containing impactToken and scene is required"));
        }
        for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            if (!ALLOWED_BODY_FIELDS.contains(field)) {
                throw new BadRequestException("Unknown field '" + field
                        + "' in scene import request; no board data was changed.",
                        Map.of(field, "Unknown request field; only impactToken and scene are accepted"));
            }
        }
        JsonNode scene = body.path("scene");
        if (!scene.isObject()) {
            throw new BadRequestException(
                    "Scene import request requires a 'scene' object; no board data was changed.",
                    Map.of("scene", "The exported portable scene must be supplied as a JSON object"));
        }
        String schema = scene.path("schema").isTextual() ? scene.path("schema").asText() : null;
        Integer version = scene.path("version").isInt() ? scene.path("version").asInt() : null;
        if (!PortableSceneFormat.isSupported(schema, version)) {
            // Named explicitly rather than reported as a generic parse failure: a version mismatch is
            // the one import error a user can act on, by re-exporting from the version that wrote it.
            throw new BadRequestException("Unsupported scene file: expected schema '"
                    + PortableSceneFormat.SCHEMA + "' version " + PortableSceneFormat.VERSION
                    + ", received schema '" + (schema == null ? "" : schema) + "' version "
                    + (version == null ? "" : version) + ". No board data was changed.",
                    Map.of("schema", "Expected scene schema '" + PortableSceneFormat.SCHEMA + "'",
                            "version", "Expected scene version " + PortableSceneFormat.VERSION
                                    + "; export the scene again with this version"));
        }
        String impactToken = body.path("impactToken").isTextual()
                ? body.path("impactToken").asText() : null;
        return batchMapper.toBatch(scene, impactToken, FILE_SPEC_ID_PREFIX);
    }
}
