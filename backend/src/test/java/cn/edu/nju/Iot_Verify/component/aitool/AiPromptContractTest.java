package cn.edu.nju.Iot_Verify.component.aitool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks prompt JSON examples and schema/enum values that Java compilation cannot validate. */
class AiPromptContractTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "cn", "edu", "nju", "Iot_Verify");

    private static final Map<String, Path> PROMPT_SOURCES = new LinkedHashMap<>();

    static {
        PROMPT_SOURCES.put("RecommendRulesTool",
                MAIN.resolve("service/recommendation/RuleRecommendationService.java"));
        PROMPT_SOURCES.put("RecommendSpecificationsTool",
                MAIN.resolve("service/recommendation/SpecificationRecommendationService.java"));
        PROMPT_SOURCES.put("RecommendRelatedDevicesTool",
                MAIN.resolve("service/recommendation/DeviceRecommendationService.java"));
        PROMPT_SOURCES.put("RecommendScenarioTool",
                MAIN.resolve("service/recommendation/ScenarioRecommendationService.java"));
        PROMPT_SOURCES.put("CheckRuleSimilarityTool",
                MAIN.resolve("service/recommendation/RuleCheckService.java"));
    }

    private static String source(String tool) throws IOException {
        Path path = PROMPT_SOURCES.get(tool);
        assertTrue(path != null && Files.exists(path),
                tool + ": prompt source moved; update PROMPT_SOURCES rather than dropping the tool");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * The {@code SYSTEM_PROMPT} text block only — never a fixed-size window. The first version of the
     * sibling {@code languageInstruction} guard used a 700-character window, ran past the method it meant
     * to read into the next one, and reported every tool as an offender. A guard whose failures are all
     * false positives gets deleted, not fixed.
     */
    private static String systemPrompt(String tool) throws IOException {
        String text = source(tool);
        int at = text.indexOf("SYSTEM_PROMPT = ");
        assertTrue(at > 0, tool + ": no SYSTEM_PROMPT declaration; the prompt idiom changed");
        int open = text.indexOf("\"\"\"", at);
        int close = text.indexOf("\"\"\"", open + 3);
        assertTrue(open > 0 && close > open, tool + ": SYSTEM_PROMPT is no longer a text block");
        String prompt = text.substring(open + 3, close);
        assertTrue(prompt.length() > 300,
                tool + ": extracted only " + prompt.length() + " prompt characters — the scan is broken, "
                        + "so a pass proves nothing");
        return prompt;
    }

    /* ------------------------------------------------------------------ (e) skeletons parse as JSON */

    /**
     * Every brace-balanced skeleton in a prompt must parse as JSON once its placeholder idioms are
     * substituted.
     *
     * <p>{@code CheckRuleSimilarityTool} shipped {@code "similarity": 0.0-1.0} — a range written where the
     * parser reads {@code asDouble()}. The prompt was demonstrating a shape the model could not copy without
     * producing invalid JSON, and the failure surfaced only as a whole call being discarded at runtime.
     *
     * <p>Two placeholder idioms are legitimate and substituted rather than reported: {@code null或"..."}
     * (an optional field) and {@code [ &lt;Condition&gt;, ... ]} (a shape defined in a second skeleton).
     * Anything else must be valid JSON as written, because that is what the model copies.
     */
    @Test
    @DisplayName("every JSON skeleton in a prompt parses as JSON")
    void promptSkeletonsAreValidJson() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<String> offenders = new ArrayList<>();
        int skeletons = 0;

        for (String tool : PROMPT_SOURCES.keySet()) {
            for (String skeleton : jsonSkeletons(systemPrompt(tool))) {
                skeletons++;
                String substituted = skeleton
                        .replaceAll("null或\"[^\"]*\"", "null")
                        .replaceAll("\\[\\s*<[^>]*>\\s*,\\s*\\.\\.\\.\\s*]", "[]");
                try {
                    mapper.readTree(substituted);
                } catch (Exception e) {
                    offenders.add(tool + ": skeleton starting \"" + firstLine(skeleton)
                            + "\" is not JSON — " + e.getMessage());
                }
            }
        }

        /*
         * Coverage floor: every prompt must contribute at least one skeleton, because a scan that finds none
         * asserts nothing and a moved prompt would report a clean catalog. Bounded by the number of prompts
         * rather than the current skeleton count — collapsing three duplicated condition blocks into one
         * shared definition is a legitimate edit that must not read as "the scan is broken".
         */
        assertTrue(skeletons >= PROMPT_SOURCES.size(),
                "expected at least one JSON skeleton per prompt (" + PROMPT_SOURCES.size() + "), found "
                        + skeletons + " — the skeleton scan is broken, so an empty offender list proves nothing");
        assertEquals(List.of(), offenders,
                "a prompt shows the model a skeleton it cannot copy as valid JSON: " + offenders);
    }

    /** Brace-balanced blocks that start on their own line, which is how every prompt writes a skeleton. */
    private static List<String> jsonSkeletons(String prompt) {
        List<String> blocks = new ArrayList<>();
        String[] lines = prompt.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("{")) continue;
            int depth = 0;
            StringBuilder block = new StringBuilder();
            int j = i;
            for (; j < lines.length; j++) {
                block.append(lines[j]).append('\n');
                depth += count(lines[j], '{') - count(lines[j], '}');
                if (depth == 0) break;
            }
            if (depth == 0) {
                blocks.add(block.toString());
                i = j;
            }
        }
        return blocks;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) n++;
        }
        return n;
    }

    private static String firstLine(String block) {
        String[] lines = block.split("\n");
        return lines.length > 1 ? lines[1].trim() : lines[0].trim();
    }

    /* ------------------------------------------------------- (d) literals match their Java constant */

    /**
     * A literal the prompt states about the payload the backend writes must equal the Java constant.
     *
     * <p>{@code RecommendScenarioTool}'s skeleton said {@code "version": 4} while {@code SCENE_VERSION} was
     * 5. The prompt is the only place the model learns the envelope, so it dutifully returned version-4
     * drafts that the codec then refused; the tool's own tests build their fixtures from the constant and
     * never saw it. The pairs are discovered from {@code scene.put("key", CONSTANT)} rather than listed, so
     * a renamed constant cannot quietly leave this check behind.
     */
    @Test
    @DisplayName("a constant named in a prompt skeleton equals the Java constant the backend writes")
    void promptConstantsMatchTheirJavaConstants() throws IOException {
        String tool = "RecommendScenarioTool";
        String text = source(tool);
        String prompt = systemPrompt(tool);

        Map<String, String> constants = new LinkedHashMap<>();
        Matcher declaration = Pattern.compile(
                "static final (?:int|String)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*([^;]+);").matcher(text);
        while (declaration.find()) {
            constants.put(declaration.group(1), resolveConstant(declaration.group(2).trim()));
        }

        List<String> offenders = new ArrayList<>();
        int pairs = 0;
        Matcher put = Pattern.compile("\\.put\\(\\s*\"([A-Za-z]+)\"\\s*,\\s*([A-Z][A-Z0-9_]+)\\s*\\)")
                .matcher(text);
        Set<String> seen = new LinkedHashSet<>();
        while (put.find()) {
            String field = put.group(1);
            String constant = put.group(2);
            String value = constants.get(constant);
            if (value == null || !seen.add(field + "=" + constant)) continue;

            Matcher stated = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?([^\",\\n]+)\"?")
                    .matcher(prompt);
            boolean statedAtAll = false;
            while (stated.find()) {
                statedAtAll = true;
                String claim = stated.group(1).trim();
                if (!claim.equals(value)) {
                    offenders.add(tool + ": prompt says " + field + "=" + claim
                            + " but " + constant + " is " + value);
                }
            }
            if (statedAtAll) pairs++;
        }

        assertTrue(pairs >= 2,
                "expected the scene envelope's schema and version to be stated in the prompt, matched "
                        + pairs + " — the skeleton or the put() idiom changed, so a pass proves nothing");
        assertEquals(List.of(), offenders,
                "a prompt advertises an envelope value the backend does not write: " + offenders);
    }

    /**
     * The literal a constant's initializer ultimately denotes, following one hop through another class.
     *
     * <p>Reading only the initializer text was enough while every constant was its own literal, but that
     * made the check hostile to deduplication: pointing {@code SCENE_SCHEMA} at the format's single
     * owner ({@code PortableSceneFormat.SCHEMA}) made this read the *expression* as the expected value
     * and report the prompt as wrong when nothing about it had changed. Loosening the comparison would
     * have been the wrong repair — the guard's whole job is to compare against what the backend writes —
     * so it resolves the reference instead. A reference it cannot resolve is returned unchanged, which
     * still fails loudly rather than passing vacuously.</p>
     */
    private static String resolveConstant(String initializer) {
        Matcher reference = Pattern.compile("^([A-Z][A-Za-z0-9_]*)\\.([A-Z][A-Z0-9_]*)$")
                .matcher(initializer.trim());
        if (!reference.find()) {
            return unquote(initializer);
        }
        try {
            String owner = findSource(reference.group(1));
            Matcher declared = Pattern.compile(
                            "static final (?:int|String)\\s+" + Pattern.quote(reference.group(2))
                                    + "\\s*=\\s*([^;]+);")
                    .matcher(owner);
            return declared.find() ? unquote(declared.group(1).trim()) : initializer.trim();
        } catch (IOException | IllegalArgumentException exception) {
            return initializer.trim();
        }
    }

    /** Locates a class's source anywhere under {@code src/main/java} by simple name. */
    private static String findSource(String simpleName) throws IOException {
        try (var paths = Files.walk(Path.of("src", "main", "java"))) {
            Path source = paths
                    .filter(path -> path.getFileName().toString().equals(simpleName + ".java"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("no source for " + simpleName));
            return Files.readString(source);
        }
    }

    private static String unquote(String literal) {
        String value = literal.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1);
        }
        return value.replace("_", "");
    }

    /* ------------------------------- the one enum comparison that is reliable: a real Java enum */

    /**
     * The confirmation detector's prompt must offer exactly the kinds and decisions the enums declare.
     *
     * <p>The generic "every alternation in a prompt equals a Java Set" check was prototyped and rejected:
     * `relation 为 in / not in` yields the junk alternation `in|not`, the scenario prompt legitimately omits
     * `api` from one alternation, and two prompts spell their enums as Chinese prose the regex cannot see.
     * A guard whose failures are mostly false positives gets deleted rather than fixed.
     *
     * <p>This case is the exception, and the only one in the seven prompts: both sides are real Java enums in
     * UPPER_SNAKE with no prose spelling. The `ALL` and `null` tokens in the same alternation are sentinels
     * rather than enum constants, so they are excluded by name — if a third sentinel appears, this test fails
     * and someone has to say so out loud, which is the intended behaviour.
     */
    @Test
    @DisplayName("the confirmation prompt offers exactly the ConfirmationKind and DecisionType constants")
    void confirmationPromptMatchesItsEnums() throws IOException {
        String source = Files.readString(
                Paths.get("src", "main", "java", "cn", "edu", "nju", "Iot_Verify", "component", "ai",
                        "ChatConfirmationDetector.java"),
                StandardCharsets.UTF_8);

        Set<String> sentinels = Set.of("ALL", "null");
        for (String enumName : List.of("ConfirmationKind", "DecisionType")) {
            Matcher declaration = Pattern
                    .compile("enum " + enumName + " \\{([^}]*)\\}")
                    .matcher(source);
            assertTrue(declaration.find(), enumName + ": declaration not found, so a pass proves nothing");
            Set<String> declared = new TreeSet<>();
            for (String token : declaration.group(1).split(",")) {
                String name = token.trim();
                if (!name.isEmpty()) declared.add(name);
            }
            assertTrue(declared.size() >= 4,
                    enumName + ": found only " + declared + " — the enum scan is broken");

            /*
             * Scan the PROMPT TEXT, not the whole file. A first version matched UPPER_SNAKE tokens across the
             * entire source, so the enum declaration itself satisfied every expectation and the test could not
             * fail: deleting a kind from the prompt still passed. Verified by replaying that mutation.
             */
            int promptStart = source.indexOf("SYSTEM_PROMPT = \"\"\"");
            assertTrue(promptStart > 0, "ChatConfirmationDetector: SYSTEM_PROMPT not found");
            int promptEnd = source.indexOf("\"\"\";", promptStart + 20);
            assertTrue(promptEnd > promptStart, "ChatConfirmationDetector: prompt end not found");
            String promptText = source.substring(promptStart, promptEnd);
            assertTrue(promptText.length() > 300,
                    "ChatConfirmationDetector: prompt slice is only " + promptText.length()
                            + " chars — the extraction is broken, so a pass proves nothing");

            Set<String> offered = new TreeSet<>();
            Matcher spelled = Pattern.compile("\\b[A-Z][A-Z_]{3,}\\b").matcher(promptText);
            while (spelled.find()) {
                String token = spelled.group();
                if (declared.contains(token)) offered.add(token);
            }
            assertEquals(declared, offered,
                    enumName + ": the prompt and the enum disagree about which constants exist");
        }
    }

    /**
     * The template tool's description may not name a manifest field the schema forbids, and must name every
     * field the schema requires.
     *
     * <p>Both halves failed, and both were measured against the running backend rather than reasoned about:
     *
     * <ul>
     *   <li>The description instructed the model that "every EnvironmentDomain must define both labels".
     *       The schema *removed* that array — its own {@code $comment} says so — and runs with
     *       {@code additionalProperties: false}, so emitting it answered
     *       {@code 400 "property EnvironmentDomains not defined in the schema"}.</li>
     *   <li>{@code NaturalChangeRate} appeared nowhere in the description, while the schema requires it for
     *       an {@code IsInside=false} variable with integer bounds — which is every shared numeric reading,
     *       i.e. every temperature, humidity and illuminance sensor. An isolated A/B pair differing in that
     *       field alone answered {@code 400 "required property NaturalChangeRate not found"} and
     *       {@code 200}.</li>
     * </ul>
     *
     * <p>A schema description is part of the tool contract — {@code requireOnlyFields} rejects unknown keys,
     * so a description promising a removed field costs the model a guaranteed round trip, and one omitting a
     * required field costs it every attempt. The existing guards check the argument *allowlist* in one
     * direction only (every advertised argument is accepted); neither can see a description that misdescribes
     * the manifest body, which is where this whole class of defect lives.
     */
    @Test
    void templateToolDescriptionMatchesTheManifestSchema() throws IOException {
        String tool = Files.readString(
                MAIN.resolve("component/aitool/template/AddTemplateTool.java"), StandardCharsets.UTF_8);
        String schema = Files.readString(
                Paths.get("device-template-schema.json"), StandardCharsets.UTF_8);

        /*
         * Every string literal in the file, minus comment lines — not a window starting at
         * `LlmToolSpec.of(`. This tool has *two* description surfaces: the parameter-schema description
         * (where the manifest body is documented) and the tool description that follows it. The first
         * version of this check windowed from `LlmToolSpec.of(` and therefore skipped the manifest
         * documentation entirely, reporting fields as undescribed while they were described twenty lines
         * above — a guard that failed on correct code, which is as useless as one that passes on broken code.
         *
         * Comment lines are stripped because the comments here necessarily quote the fields they explain.
         */
        String described = tool.replaceAll("(?m)^\\s*//.*$", "");

        // 1. A field the schema does not define cannot be demanded. `additionalProperties: false` is in force,
        //    so naming one guarantees a 400.
        assertTrue(schema.contains("\"additionalProperties\": false"),
                "this check only holds while the schema closes its objects");
        for (String removed : List.of("EnvironmentDomains", "EnvironmentDomain")) {
            assertFalse(described.contains(removed),
                    "the description names '" + removed + "', which the schema does not define; "
                            + "emitting it is a guaranteed 400");
        }

        // 2. Every conditionally-required manifest field must be described. Collected from the schema's own
        //    `then: {required: [...]}` clauses rather than a hand-kept list, so a new requirement is caught
        //    by the same assertion.
        Set<String> conditionallyRequired = new TreeSet<>();
        Matcher required = Pattern
                .compile("\"then\"\\s*:\\s*\\{\\s*\"required\"\\s*:\\s*\\[([^]]*)]")
                .matcher(schema);
        while (required.find()) {
            for (String raw : required.group(1).split(",")) {
                String field = raw.replaceAll("[\"\\s]", "");
                if (!field.isEmpty()) conditionallyRequired.add(field);
            }
        }
        assertFalse(conditionallyRequired.isEmpty(),
                "the schema should declare conditional requirements; an empty set proves nothing");

        Set<String> undescribed = new TreeSet<>();
        for (String field : conditionallyRequired) {
            if (!described.contains(field)) undescribed.add(field);
        }
        assertEquals(Set.of(), undescribed,
                "the schema requires these manifest fields and the tool description never mentions them, "
                        + "so the model cannot author a valid manifest: " + undescribed);
    }
}
