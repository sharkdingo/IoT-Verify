package cn.edu.nju.Iot_Verify.component.board;

import cn.edu.nju.Iot_Verify.dto.board.BoardEnvironmentVariableDto;
import cn.edu.nju.Iot_Verify.dto.recommendation.PortableSceneDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the portable scene format's two declarations — {@link PortableSceneDto} and the frontend's
 * {@code types/scene.ts} — against each other.
 *
 * <p>Nothing else ties them together, and drift between them is silent in both directions: a field
 * one side does not declare is dropped rather than rejected. That is not hypothetical. The
 * {@code variableSource} field was omitted from three separate hand-written copies of this contract
 * and produced three separate user-visible failures — a 502 on the whole recommendation response, and
 * two "field is required" rejections naming a field the scene actually carried. Each was found in
 * production use, not by a test.</p>
 *
 * <p>This asserts field-name parity only, which is what the failures turned on. Types are not
 * compared: the two languages spell them differently, and a mismatch there fails loudly at
 * deserialization rather than silently.</p>
 */
class PortableSceneContractTest {

    private static final Path SCENE_TYPES =
            Path.of("..", "frontend", "src", "types", "scene.ts");

    /** TypeScript interface name → the Java type declaring the same portable shape. */
    private static final Map<String, Class<?>> SHAPES = Map.of(
            "PortableSceneFile", PortableSceneDto.class,
            "PortableSceneTemplate", PortableSceneDto.PortableTemplateDto.class,
            "PortableSceneDevice", PortableSceneDto.PortableDeviceDto.class,
            "PortableSceneEnvironmentVariable", BoardEnvironmentVariableDto.class,
            "PortableSceneRule", PortableSceneDto.PortableRuleDto.class,
            "PortableSceneRuleSource", PortableSceneDto.PortableRuleSourceDto.class,
            "PortableSceneSpecification", PortableSceneDto.PortableSpecificationDto.class,
            "PortableSceneCondition", PortableSceneDto.PortableSpecConditionDto.class);

    @Test
    void everyPortableShapeDeclaresTheSameFieldsOnBothSides() throws IOException {
        String source = Files.readString(SCENE_TYPES);

        for (Map.Entry<String, Class<?>> shape : SHAPES.entrySet()) {
            Set<String> typeScriptFields = typeScriptFields(source, shape.getKey());
            Set<String> javaFields = javaFields(shape.getValue());

            assertEquals(javaFields, typeScriptFields,
                    shape.getKey() + " and " + shape.getValue().getSimpleName()
                            + " declare different fields. A field on only one side is dropped silently,"
                            + " so add it to both or to neither.");
        }
    }

    private Set<String> typeScriptFields(String source, String interfaceName) {
        Matcher block = Pattern.compile(
                        "export\\s+interface\\s+" + Pattern.quote(interfaceName) + "\\s*\\{(.*?)\\n\\}",
                        Pattern.DOTALL)
                .matcher(source);
        if (!block.find()) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        // Top-level members only: `position` is an inline object on this side and a named DTO on the
        // Java side, so its nested `x`/`y` must not be collected as siblings of `position` itself.
        Matcher member = Pattern.compile("^\\s{2}(\\w+)\\??\\s*:", Pattern.MULTILINE)
                .matcher(block.group(1));
        while (member.find()) {
            fields.add(member.group(1));
        }
        return fields;
    }

    private Set<String> javaFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
