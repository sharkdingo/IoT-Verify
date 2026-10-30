package cn.edu.nju.Iot_Verify.component.aitool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks that tool argument schemas agree with their accepted top-level fields. */
class AiToolCatalogDocumentationTest {

    private static final Path TOOL_ROOT =
            Path.of("src/main/java/cn/edu/nju/Iot_Verify/component/aitool");

    private static List<Path> concreteTools() throws IOException {
        try (Stream<Path> paths = Files.walk(TOOL_ROOT)) {
            List<Path> tools = new ArrayList<>();
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                // A registered tool is a concrete Spring bean implementing AiTool. AbstractAiTool
                // itself implements the interface but is abstract, so it is not a catalog entry.
                boolean isTool = source.contains("extends AbstractAiTool")
                        || source.matches("(?s).*\\bimplements\\s+AiTool\\b.*");
                if (isTool && !source.contains("abstract class")) {
                    tools.add(path);
                }
            }
            return tools;
        }
    }

    @Test
    void everyAdvertisedArgumentIsAcceptedByTheToolThatAdvertisesIt() throws IOException {
        Pattern propsPut = Pattern.compile("(?:props|properties)\\.put\\(\\s*\"([^\"]+)\"");
        Pattern inlineSchema = Pattern.compile("new FunctionParameterSchema\\(\\s*\"object\"\\s*,\\s*Map\\.of\\(",
                Pattern.DOTALL);
        Pattern allowlist = Pattern.compile("requireOnlyFields\\([^;]*?Set\\.of\\(([^)]*)\\)", Pattern.DOTALL);
        Pattern quoted = Pattern.compile("\"([^\"]+)\"");

        List<String> drift = new ArrayList<>();
        int parsed = 0;

        for (Path tool : concreteTools()) {
            String source = Files.readString(tool);
            String name = tool.getFileName().toString().replace(".java", "");

            int definitionStart = source.indexOf("public LlmToolSpec getDefinition");
            if (definitionStart < 0) continue;
            int definitionEnd = source.indexOf("protected String doExecute", definitionStart);
            String definition = source.substring(definitionStart,
                    definitionEnd > 0 ? definitionEnd : source.length());

            List<String> advertised = new ArrayList<>();
            Matcher put = propsPut.matcher(definition);
            while (put.find()) advertised.add(put.group(1));
            Matcher inline = inlineSchema.matcher(definition);
            if (inline.find()) {
                advertised.addAll(topLevelKeys(definition.substring(inline.end())));
            }
            if (advertised.isEmpty()) continue;

            List<String> accepted = new ArrayList<>();
            Matcher allow = allowlist.matcher(source);
            boolean validates = false;
            while (allow.find()) {
                validates = true;
                Matcher field = quoted.matcher(allow.group(1));
                while (field.find()) accepted.add(field.group(1));
            }
            if (!validates) {
                drift.add(name + " declares " + advertised + " but never calls requireOnlyFields");
                continue;
            }
            parsed++;

            for (String field : advertised) {
                if (!accepted.contains(field)) {
                    drift.add(name + " advertises \"" + field + "\" but requireOnlyFields rejects it");
                }
            }
        }

        // A scan that matches nothing asserts nothing: if the declaration idiom changes and the parse
        // stops finding arguments, fail here rather than reporting a clean catalog.
        int toolsWithArguments = parsed;
        assertTrue(toolsWithArguments >= 40,
                () -> "expected most tools to declare arguments, parsed only " + toolsWithArguments
                        + " — the schema declaration idiom probably changed");
        assertTrue(drift.isEmpty(),
                () -> "a tool's schema and its requireOnlyFields allowlist disagree:\n"
                        + String.join("\n", drift));
    }

    /**
     * The reverse direction: an argument the tool accepts must be advertised, and one it rejects must not be.
     *
     * <p>The test above checks that every advertised argument is accepted, which stops the model wasting a
     * round on a guaranteed {@code VALIDATION_ERROR}. It cannot see the opposite drift — a field the
     * allowlist accepts but the schema never mentions is a capability the model has no way to discover, and
     * a field named in a *description* that no allowlist accepts is a guaranteed rejection dressed as an
     * instruction. The second shape is what shipped in {@code add_template}, which told the model that
     * "every EnvironmentDomain must define both labels" for an array the schema had removed.
     *
     * <p>Description prose is deliberately excluded here and covered by {@code AiPromptContractTest}, which
     * can compare it against the manifest schema that actually validates it. What this asserts is the
     * narrower, mechanical half: the schema's property set and the allowlist's field set are the same set.
     */
    @Test
    void everyAcceptedArgumentIsAdvertisedByTheToolThatAcceptsIt() throws IOException {
        Pattern propsPut = Pattern.compile("(?:props|properties)\\.put\\(\\s*\"([^\"]+)\"");
        // The same precise pattern as the forward check above. A looser `new FunctionParameterSchema\(`
        // matches before the `Map.of(`, so `topLevelKeys` reads the wrong substring and reports tools that
        // do declare their arguments — it flagged four inline-schema tools that were correct.
        Pattern inlineSchema = Pattern.compile("new FunctionParameterSchema\\(\\s*\"object\"\\s*,\\s*Map\\.of\\(",
                Pattern.DOTALL);
        /*
         * TOP-LEVEL allowlists only: `requireOnlyFields(args, "arguments", ...)`. A call whose path is
         * `arguments.command` or a per-element condition path validates a NESTED object with the same
         * helper, and those members are never top-level schema properties — the forward check's javadoc
         * records this, and including them reported every composite-argument tool (ManageRuleTool,
         * ManageSpecTool, AddNodeTool, EditDeviceTool) for being correctly structured.
         */
        Pattern allowlist = Pattern.compile(
                "requireOnlyFields\\(\\s*args\\s*,\\s*\"arguments\"\\s*,[^;]*?Set\\.of\\(([^)]*)\\)",
                Pattern.DOTALL);
        Pattern quoted = Pattern.compile("\"([^\"]+)\"");

        List<String> drift = new ArrayList<>();
        int parsed = 0;

        for (Path tool : concreteTools()) {
            String source = Files.readString(tool);
            String name = tool.getFileName().toString().replace(".java", "");

            int definitionStart = source.indexOf("public LlmToolSpec getDefinition");
            if (definitionStart < 0) continue;
            int definitionEnd = source.indexOf("protected String doExecute", definitionStart);
            String definition = definitionEnd > definitionStart
                    ? source.substring(definitionStart, definitionEnd)
                    : source.substring(definitionStart);

            Set<String> advertised = new LinkedHashSet<>();
            Matcher put = propsPut.matcher(definition);
            while (put.find()) advertised.add(put.group(1));
            Matcher inline = inlineSchema.matcher(definition);
            if (inline.find()) {
                advertised.addAll(topLevelKeys(definition.substring(inline.end())));
            }
            if (advertised.isEmpty()) continue;

            Set<String> accepted = new LinkedHashSet<>();
            Matcher list = allowlist.matcher(source);
            while (list.find()) {
                Matcher field = quoted.matcher(list.group(1));
                while (field.find()) accepted.add(field.group(1));
            }
            if (accepted.isEmpty()) continue;
            parsed++;

            for (String field : accepted) {
                if (advertised.contains(field)) continue;
                /*
                 * A field allowlisted only in order to be rejected with a specific message is the opposite
                 * of drift — it is a courtesy. `FixViolationTool` accepts `preferredRanges` so it can answer
                 * "preferredRanges is an internal locator map, use preferredRangeSelections instead"
                 * rather than the generic unknown-field error, which would leave the model guessing which
                 * name it should have used. Recognised by the tool returning an error that names the field.
                 */
                boolean rejectedByName = source.matches(
                        "(?s).*errorJson\\(\\s*\"" + Pattern.quote(field) + "\\b.*");
                if (rejectedByName) continue;
                drift.add(name + " accepts \"" + field + "\" but its schema never advertises it, "
                        + "so the model cannot discover it");
            }
        }

        // Effectively final for the lambda: `parsed` is incremented inside the loop above.
        final int parsedCount = parsed;
        assertTrue(parsedCount >= 20,
                () -> "only " + parsedCount + " tools were parsed for the reverse check"
                        + " — the schema or allowlist idiom probably changed");
        assertTrue(drift.isEmpty(),
                () -> "a tool accepts arguments it does not advertise:\n" + String.join("\n", drift));
    }

    /**
     * Property names of an inline {@code Map.of(...)} schema, i.e. the keys at depth 0. The nested
     * {@code Map.of("type", ..., "description", ...)} values sit deeper and are JSON-Schema keywords,
     * not argument names, so this counts parentheses instead of matching every quoted string.
     */
    private static List<String> topLevelKeys(String afterMapOf) {
        List<String> keys = new ArrayList<>();
        int depth = 0;
        boolean expectKey = true;
        for (int i = 0; i < afterMapOf.length(); i++) {
            char c = afterMapOf.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) break;
                depth--;
            } else if (c == ',' && depth == 0) {
                expectKey = true;
            } else if (c == '"') {
                int end = afterMapOf.indexOf('"', i + 1);
                if (end < 0) break;
                if (depth == 0 && expectKey) keys.add(afterMapOf.substring(i + 1, end));
                if (depth == 0) expectKey = false;
                i = end;
            }
        }
        return keys;
    }
}
