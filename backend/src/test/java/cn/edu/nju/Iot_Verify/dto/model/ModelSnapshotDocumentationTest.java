package cn.edu.nju.Iot_Verify.dto.model;

import org.junit.jupiter.api.Test;
import cn.edu.nju.Iot_Verify.dto.verification.SpecResultDto;
import cn.edu.nju.Iot_Verify.dto.verification.SpecificationSnapshotDto;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/api/verification.md} carries the normative field table for the run snapshot and its
 * per-value provenance. A reader treats that table as complete, so a field present in the response
 * but missing from the table is worse than an undocumented feature: it teaches the reader the
 * response has a shape it does not have.
 *
 * <p>This actually happened — {@code environmentProvenance} shipped in the API while the table still
 * described the snapshot as item counts plus a frozen-templates flag. Nothing connected the two, so
 * the only thing preventing drift was remembering to edit the document.
 *
 * <p>The check is deliberately one-directional: every serialized field must be documented. It does
 * not require the reverse, because the table legitimately explains derived behaviour and cross-field
 * invariants that are not fields.
 */
class ModelSnapshotDocumentationTest {

    private static final Path VERIFICATION_API_DOC = Path.of("../docs/api/verification.md");

    private static String doc() throws IOException {
        return Files.readString(VERIFICATION_API_DOC, StandardCharsets.UTF_8);
    }

    /** Serialized field names of a DTO, skipping synthetic and static members. */
    private static List<String> serializedFields(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            names.add(field.getName());
        }
        return names;
    }

    private static void assertEveryFieldDocumented(Class<?> type, String doc) {
        List<String> undocumented = new ArrayList<>();
        for (String field : serializedFields(type)) {
            // The table renders each field name in backticks, which is precise enough to avoid
            // matching the same word used in surrounding prose.
            if (!doc.contains("`" + field + "`")) {
                undocumented.add(field);
            }
        }
        assertTrue(undocumented.isEmpty(),
                type.getSimpleName() + " exposes field(s) that " + VERIFICATION_API_DOC
                        + " does not document: " + undocumented
                        + ". A reader treats that table as the complete response shape, so add each "
                        + "field with its type and meaning rather than leaving it discoverable only "
                        + "from a live response.");
    }

    @Test
    void everyRunSnapshotFieldAppearsInTheApiReference() throws IOException {
        assertEveryFieldDocumented(ModelRunSnapshotDto.class, doc());
    }

    @Test
    void everySpecificationResultFieldAppearsInTheApiReference() throws IOException {
        String doc = doc();
        assertEveryFieldDocumented(SpecResultDto.class, doc);
        assertEveryFieldDocumented(SpecificationSnapshotDto.class, doc);
        assertEveryFieldDocumented(SpecificationSnapshotDto.Device.class, doc);
    }

    @Test
    void everyProvenanceFieldAppearsInTheApiReference() throws IOException {
        String doc = doc();
        assertEveryFieldDocumented(EnvironmentValueProvenanceDto.class, doc);
        assertEveryFieldDocumented(EnvironmentValueProvenanceDto.DeviceWriter.class, doc);
        assertEveryFieldDocumented(EnvironmentValueProvenanceDto.DeviceReader.class, doc);
    }

    @Test
    void everyProvenanceEnumConstantAppearsInTheApiReference() throws IOException {
        String doc = doc();
        List<String> undocumented = new ArrayList<>();
        List<Class<? extends Enum<?>>> enums = List.of(
                EnvironmentValueProvenanceDto.ValueType.class,
                EnvironmentValueProvenanceDto.AuthorshipCategory.class,
                EnvironmentValueProvenanceDto.SemanticsTag.class);
        for (Class<? extends Enum<?>> type : enums) {
            for (Object constant : type.getEnumConstants()) {
                // A client switches on these names, so an undocumented one is an unhandled branch.
                if (!doc.contains(String.valueOf(constant))) {
                    undocumented.add(type.getSimpleName() + "." + constant);
                }
            }
        }
        assertTrue(undocumented.isEmpty(),
                "Provenance enum constant(s) missing from " + VERIFICATION_API_DOC + ": "
                        + undocumented + ". A client switches on these values, so an undocumented "
                        + "constant is a branch the client will not handle.");
    }
}
