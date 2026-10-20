package cn.edu.nju.Iot_Verify.component.aitool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An AI tool must reach the database through the service layer, never a repository.
 *
 * <p>This is what makes an assistant-made board change as safe as a hand-made one, and it is entirely structural.
 * The services own the per-user write lock, the ownership check, and — critically — the
 * {@code BoardEditJournal.record} call that happens inside the mutation's own transaction. A tool that injected a
 * repository directly would bypass all three: the change would commit, it would be invisible to the journal, and
 * {@code /api/board/edits/availability} would correctly report that there is nothing to undo. The user's rule
 * would simply be gone, permanently, with no error anywhere.
 *
 * <p>{@code BoardStorageController} states the reason this cannot be patched client-side: "the journal on the
 * server is the authority for what is reversible, so the client sends no local history." There is no second place
 * to catch the omission.
 *
 * <p>Verified live before writing this: a rule created through the service path reported {@code canUndo=true},
 * undo removed it, redo restored it; a device rename was journalled and undo restored the *exact* previous label;
 * and a rename carrying a stale {@code expectedLabel} was refused with 409 while the newer human edit survived.
 * The layering is currently clean — 53 tools reference a service, zero reference a repository.
 *
 * <p>Source-level on purpose. The failure is an import plus a constructor parameter, which compiles, passes a
 * happy-path Mockito test, and works perfectly in manual testing. Nothing else in the suite would notice.
 */
class AiToolLayeringContractTest {

    private static final Path MAIN_SOURCE_ROOT =
            Paths.get("src", "main", "java", "cn", "edu", "nju", "Iot_Verify");
    private static final Path TOOL_ROOT = MAIN_SOURCE_ROOT.resolve("component").resolve("aitool");

    /** A field or constructor parameter whose type is a repository. */
    private static final Pattern REPOSITORY_DEPENDENCY = Pattern.compile(
            "(?:private\\s+final\\s+|,\\s*|\\(\\s*)([A-Z][A-Za-z0-9]*Repository)\\s+[a-z][A-Za-z0-9]*");

    private static List<Path> toolSources() throws IOException {
        try (Stream<Path> paths = Files.walk(TOOL_ROOT)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Tool.java"))
                    .toList();
        }
    }

    @Test
    @DisplayName("no AI tool injects a repository, so every AI mutation inherits journalling and ownership")
    void aiToolsGoThroughServices() throws IOException {
        List<Path> sources = toolSources();

        // Coverage first. If the directory moved, an empty offender list would be vacuously true and this test
        // would pass while reading nothing — the failure mode this audit has hit repeatedly.
        assertTrue(sources.size() >= 50,
                "expected at least 50 AI tool sources under " + TOOL_ROOT + ", found " + sources.size()
                        + " — the tool package probably moved, so an empty offender list proves nothing");

        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            // Strip comments so a javadoc line naming a repository is not mistaken for a dependency on one.
            String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            Matcher matcher = REPOSITORY_DEPENDENCY.matcher(code);
            while (matcher.find()) {
                offenders.add(source.getFileName() + " injects " + matcher.group(1));
            }
        }

        assertEquals(List.of(), offenders,
                "an AI tool reaching a repository directly bypasses the per-user write lock, the ownership check, "
                        + "and BoardEditJournal.record — the mutation would commit but could never be undone");
    }

    @Test
    @DisplayName("AI tools do depend on services, so the rule is not satisfied by tools that touch nothing")
    void aiToolsActuallyUseServices() throws IOException {
        // The mirror assertion. Without it, deleting every dependency from every tool would satisfy the rule above
        // and destroy the assistant — a rule that can be satisfied by removing the feature is not a rule.
        Pattern serviceDependency = Pattern.compile(
                "(?:private\\s+final\\s+|,\\s*|\\(\\s*)([A-Z][A-Za-z0-9]*Service)\\s+[a-z][A-Za-z0-9]*");

        int withService = 0;
        for (Path source : toolSources()) {
            String code = Files.readString(source, StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            if (serviceDependency.matcher(code).find()) withService++;
        }

        assertTrue(withService >= 40,
                "expected most AI tools to depend on a service; only " + withService + " do, which suggests the "
                        + "layering moved somewhere this test can no longer see");
    }
    /*
     * One argument, one description — because the model chooses arguments from that text.
     *
     * `attackPoints` was built by four private `attackPointsSchema()` methods, and three said "Required for
     * attackMode exact" while the fourth said "Required *only* for attackMode exact". `attackScenarioArg` in
     * `AbstractAiTool` rejects a non-empty `attackPoints` for both `none` and `exhaustive`, so "only" was the
     * accurate one and the other three understated the constraint — an LLM reading them would send an argument
     * that gets refused. `attackBudget` had the same split across the two verification tools.
     *
     * A divergence in tool-facing prose is a behavioural difference, not a wording preference, and nothing else
     * in the suite compares these strings. Both schemas now live beside the validator that enforces them.
     */
    @Test
    @DisplayName("attack-scenario argument schemas have exactly one owner")
    void attackScenarioSchemasAreNotDuplicatedPerTool() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(Paths.get("src/main/java/cn/edu/nju/Iot_Verify/component/aitool"))) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString();
                if (name.equals("AbstractAiTool.java")) continue;
                // `errorPreview`/`ERROR_PREVIEW_LIMIT` joined this list after the three dismiss tools (fuzz,
                // simulate, verify) were found carrying byte-identical copies. They agreed, but the model reads
                // these strings: one drifting would summarise the same failure at two different lengths
                // depending on which run kind the user dismissed, and nothing else compares them.
                for (String schema : List.of("attackPointsSchema", "attackBudgetSchema",
                        "errorPreview", "ERROR_PREVIEW_LIMIT")) {
                    if (body.contains("private Map<String, Object> " + schema)
                            || body.contains("private static Map<String, Object> " + schema)
                            || body.contains("private String " + schema + "(")
                            || body.contains("private static final int " + schema + " =")) {
                        offenders.add(name + " declares its own " + schema);
                    }
                }
                // A tool must not inline the description either — that is how the divergence started.
                if (body.contains("attackMode exact. Device ids")) {
                    offenders.add(name + " inlines the attackPoints description");
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "attack-scenario schemas belong on AbstractAiTool, next to attackScenarioArg: " + offenders);
    }

    /*
     * A tool must not describe a mode it rejects — the mirror of the divergence above.
     *
     * Consolidating the schema, I first wrote one description naming `exhaustive`. Simulation passes
     * `allowExhaustive=false` to `attackScenarioArg` and offers `enum ["none","exact"]`, so that text told the
     * model about a mode simulation refuses: the same defect as the original divergence, introduced from the
     * other side while fixing it. The schema now takes the capability as an argument, and this pins that each
     * tool passes the value matching its own `attackScenarioArg` call.
     */
    @Test
    @DisplayName("each tool's attackPoints description matches the modes it accepts")
    void attackPointsSchemaCapabilityMatchesTheValidator() throws IOException {
        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        try (Stream<Path> stream = Files.walk(Paths.get("src/main/java/cn/edu/nju/Iot_Verify/component/aitool"))) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                if (!body.contains("attackPointsSchema(")) continue;
                String name = file.getFileName().toString();
                if (name.equals("AbstractAiTool.java")) continue;
                inspected += 1;
                boolean schemaAllowsExhaustive = body.contains("attackPointsSchema(true)");
                boolean validatorAllowsExhaustive = body.contains("attackScenarioArg(args, true)");
                if (schemaAllowsExhaustive != validatorAllowsExhaustive) {
                    offenders.add(name + ": schema says exhaustive=" + schemaAllowsExhaustive
                            + " but attackScenarioArg says " + validatorAllowsExhaustive);
                }
            }
        }
        // A coverage floor, matching the sibling checks in this file. Without it an empty walk — a moved package, a
        // renamed suffix — reports success, which is the failure mode this suite has already produced elsewhere.
        assertTrue(inspected >= 4,
                "expected at least 4 tools calling attackScenarioArg, inspected " + inspected
                        + " — the scan is probably broken, so an empty offender list proves nothing");
        assertTrue(offenders.isEmpty(),
                "a tool described a mode it does not accept: " + offenders);
    }

    /**
     * A prompt's language instruction may only name fields the model actually authors.
     *
     * <p>Three of the four recommendation implementations told the model to write {@code message} in the requested language.
     * {@code message} is backend copy — every occurrence is {@code result.put("message", recommendationMessage(
     * language, key, count))} over a hardcoded switch — so the model has none to translate. Worse, the rule and
     * specification implementations validate candidates with a strict {@code hasOnlyFields} allowlist, so a model that obeyed
     * the instruction and emitted {@code message} had its whole candidate filtered as {@code unknownCandidateField}.
     * The specification implementation also named {@code reason}, which is its field for nothing — its candidates
     * carry {@code rationale}. The scenario implementation, which names only real fields, is what the other three drifted from.
     *
     * <p>Source-level because the defect is prose inside a correct-compiling string, and the existing implementation tests
     * assert only the {@code "输出语言: 简体中文"} prefix — they pass whatever field list follows it.
     */
    @Test
    @DisplayName("a language instruction names only fields the model authors, never backend-generated copy")
    void languageInstructionsNameOnlyModelAuthoredFields() throws IOException {
        // Backend-owned response keys. A prompt asking the model to localize one of these is asking for a field it
        // does not produce, and on the strict-allowlist implementations it costs the user the whole candidate.
        List<String> backendOwned = List.of("message", "filteredItems", "adjustedItems", "count");

        List<String> offenders = new ArrayList<>();
        int inspected = 0;
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
            sources = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        for (Path source : sources) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            int at = text.indexOf("languageInstruction(String language)");
            if (at < 0) continue;
            inspected += 1;
            /*
             * Only the instruction's own string literals, not a fixed-size window.
             * A 700-character window ran past the method's closing brace into the neighbouring
             * `message(String language, String key, ...)` helper, so every implementation falsely reported naming
             * `count` and `message` — a guard whose failures are all false positives gets deleted, not fixed.
             */
            int end = text.indexOf(System.lineSeparator() + "    }", at);
            if (end < 0) end = text.indexOf("\n    }", at);
            assertTrue(end > at, source.getFileName() + ": could not find the end of languageInstruction");
            String method = text.substring(at, end);
            List<String> instructions = new ArrayList<>();
            Matcher literals = Pattern.compile("return\\s+\"([^\"]*)\"").matcher(method);
            while (literals.find()) instructions.add(literals.group(1));
            assertTrue(instructions.size() >= 2,
                    source.getFileName() + ": expected both locale branches of languageInstruction, found "
                            + instructions.size() + " — the literal scan is broken, so a pass proves nothing");
            String instruction = String.join(" | ", instructions);
            for (String field : backendOwned) {
                if (instruction.contains(field)) {
                    offenders.add(source.getFileName() + " asks the model to write the backend-owned '" + field + "'");
                }
            }
        }

        // Coverage floor, matching the sibling checks in this file.
        assertTrue(inspected >= 4,
                "expected at least 4 implementations with a languageInstruction, inspected " + inspected
                        + " — the scan is probably broken, so an empty offender list proves nothing");
        assertEquals(List.of(), offenders,
                "a prompt named a field the model cannot author: " + offenders);
    }
}
