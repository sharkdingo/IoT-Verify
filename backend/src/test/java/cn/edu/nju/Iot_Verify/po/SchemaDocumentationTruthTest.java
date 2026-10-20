package cn.edu.nju.Iot_Verify.po;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documented schema must be the schema the code builds.
 *
 * {@code backend/CLAUDE.md} is what an agent or a new contributor reads before touching persistence, and it makes
 * specific, checkable claims: how many tables exist, which carry composite primary keys for user isolation, which
 * require ownership constraints. Acting on a stale claim here produces a query or a migration that is wrong in
 * a way ordinary tests will not catch — and the repo's own rule is that code wins and the doc is fixed in the same
 * change. This test is what makes that rule enforceable rather than aspirational.
 *
 * <p>Verified at the time of writing: 18 tables (17 {@code @Table} plus one {@code @CollectionTable}), every table
 * the doc names exists, all three claimed composite primary keys are implemented, and no table is unreferenced.
 *
 * <p>The counting subtlety is the reason this is a test and not a one-off check. An {@code @Entity} carries
 * {@code @Table}, but {@code chat_session_pre_admission_stop} is an {@code @ElementCollection} whose
 * {@code @CollectionTable} puts {@code name =} on a *later line* than the annotation. A single-line search finds
 * 17 and concludes the doc overstates by one — which is how I first read it. The doc was right.
 */
class SchemaDocumentationTruthTest {

    private static final Path MAIN_ROOT =
            Paths.get("src", "main", "java", "cn", "edu", "nju", "Iot_Verify");
    private static final Path PO_DIR = MAIN_ROOT.resolve("po");
    private static final Path DOC = Paths.get("CLAUDE.md");

    /** `@Table(name = "x")` — one per entity. */
    private static final Pattern ENTITY_TABLE = Pattern.compile("@Table\\(\\s*name\\s*=\\s*\"([a-z_]+)\"");

    /**
     * `@CollectionTable(...)` with `name =` possibly several lines down.
     *
     * <p>The bounded `[\s\S]{0,120}?` span is deliberate: it crosses newlines to reach the name, but cannot run
     * on into an unrelated annotation further down the file.
     */
    private static final Pattern COLLECTION_TABLE =
            Pattern.compile("@CollectionTable\\(\\s*[\\s\\S]{0,120}?name\\s*=\\s*\"([a-z_]+)\"");

    private static List<String> poSources() throws IOException {
        try (Stream<Path> paths = Files.list(PO_DIR)) {
            List<String> out = new ArrayList<>();
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.add(Files.readString(p, StandardCharsets.UTF_8));
            }
            return out;
        }
    }

    private static Set<String> tablesFromCode(List<String> sources) {
        Set<String> tables = new LinkedHashSet<>();
        for (String text : sources) {
            for (Pattern p : List.of(ENTITY_TABLE, COLLECTION_TABLE)) {
                Matcher m = p.matcher(text);
                while (m.find()) tables.add(m.group(1));
            }
        }
        return tables;
    }

    @Test
    @DisplayName("the table count in CLAUDE.md equals the number of tables the code declares")
    void documentedTableCountIsTruthful() throws IOException {
        List<String> sources = poSources();
        // Coverage guard: if the PO directory moved, zero tables would make every comparison below meaningless.
        assertTrue(sources.size() >= 15,
                "expected at least 15 PO sources, found " + sources.size() + " — the package probably moved");

        Set<String> tables = tablesFromCode(sources);
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        Matcher claim = Pattern.compile("(\\d+)\\s+tables").matcher(doc);
        assertTrue(claim.find(), "CLAUDE.md should state a table count");

        assertEquals(Integer.parseInt(claim.group(1)), tables.size(),
                "CLAUDE.md states a table count that the code no longer builds. Tables found: " + tables);
    }

    @Test
    @DisplayName("every table CLAUDE.md names actually exists")
    void everyDocumentedTableExists() throws IOException {
        Set<String> tables = tablesFromCode(poSources());
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);

        // Only backticked identifiers that carry a domain prefix are judged as table names, so ordinary prose and
        // Java identifiers in the same document are not mistaken for schema.
        Pattern candidate = Pattern.compile(
                "`((?:app_|board_|chat_|device_|fuzz_|simulation_|verification_|ai_)[a-z_]+)`");
        Matcher m = candidate.matcher(doc);
        List<String> named = new ArrayList<>();
        while (m.find()) if (!named.contains(m.group(1))) named.add(m.group(1));

        assertTrue(named.size() >= 10,
                "expected the doc to name at least 10 tables, found " + named.size()
                        + " — the extraction is probably broken, so an empty offender list proves nothing");

        List<String> absent = named.stream().filter(n -> !tables.contains(n)).toList();
        assertEquals(List.of(), absent,
                "CLAUDE.md names tables that do not exist; a reader would look for schema that was renamed or removed");
    }

    @Test
    @DisplayName("the AI tool count in its owning API document matches the concrete tool classes")
    void documentedAiToolCountIsTruthful() throws IOException {
        // The interface and abstract base are not registered capabilities. The API document owns their count.
        Path toolRoot = MAIN_ROOT.resolve("component").resolve("aitool");
        List<String> concrete = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(toolRoot)) {
            for (Path f : paths.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                if (!name.endsWith("Tool.java")) continue;
                if (name.equals("AbstractAiTool.java") || name.equals("AiTool.java")) continue;
                concrete.add(name);
            }
        }

        assertTrue(concrete.size() >= 40,
                "expected at least 40 concrete AI tools, found " + concrete.size()
                        + " — the package probably moved, so a count comparison proves nothing");

        String doc = Files.readString(Paths.get("../docs/api/ai-tools.md"), StandardCharsets.UTF_8);
        Matcher claim = Pattern.compile("All (\\d+) tools").matcher(doc);
        assertTrue(claim.find(), "ai-tools.md should state the registered tool count");
        assertEquals(Integer.parseInt(claim.group(1)), concrete.size(),
                "ai-tools.md states a tool count that no longer matches the concrete tool classes");
    }

    @Test
    @DisplayName("the composite primary keys the doc claims for user isolation are implemented")
    void compositePrimaryKeysExist() throws IOException {
        /*
         * Each claim is checked against the table it names, not against a count.
         *
         * This used to count doc claims (3) and count entities carrying `@IdClass`/`@EmbeddedId` (3), then assert
         * `implemented >= claimed`. Nothing tied a claim to its table, so dropping the composite key from
         * `board_environment_variable` while adding one anywhere else stayed green — and the doc would keep
         * describing a per-user isolation guarantee that no longer held, which is the one thing this class exists
         * to prevent.
         */
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        /*
         * Deliberately tolerant of the connecting words: the claim being checked is "this table has this composite
         * key", and an earlier pattern hard-coded `has a\s*composite PK`, so editing the sentence for brevity
         * silently dropped the claim from the scan instead of failing — a documentation test must not dictate the
         * documentation's phrasing.
         *
         * The bounded `[^.;]{0,40}?` reaches across any short connector ("has a", "uses a", "carries a") and across
         * a line wrap, while excluding `.` and `;` so it cannot leave the claim it started in and pair one table
         * with a later table's key. The `assertTrue` below is what catches an edit that outruns this pattern: if a
         * rewrite drops a claim from the scan, the count fails rather than silently checking fewer facts.
         */
        Matcher m = Pattern.compile("`([a-z_]+)`[^.;]{0,40}?composite PK `\\(([^)]+)\\)`").matcher(doc);

        Map<String, String> claims = new LinkedHashMap<>();
        while (m.find()) {
            claims.put(m.group(1), m.group(2).replace(" ", ""));
        }
        assertTrue(claims.size() >= 3,
                "the doc should claim at least 3 composite PKs by table name, found " + claims);

        // These composite keys *are* the per-user isolation mechanism, so a doc naming one the code dropped would
        // describe a guarantee that no longer holds.
        List<String> unmet = new ArrayList<>();
        for (Map.Entry<String, String> claim : claims.entrySet()) {
            String table = claim.getKey();
            String source = poSources().stream()
                    .filter(text -> text.contains("\"" + table + "\""))
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                unmet.add(table + ": no entity maps this table");
                continue;
            }
            if (!source.contains("@IdClass") && !source.contains("@EmbeddedId")) {
                unmet.add(table + ": entity declares no composite key, but the doc claims ("
                        + claim.getValue() + ")");
                continue;
            }
            for (String column : claim.getValue().split(",")) {
                if (!source.contains("\"" + column + "\"") && !source.contains(toCamel(column))) {
                    unmet.add(table + ": composite key is missing the claimed column " + column);
                }
            }
        }

        assertTrue(unmet.isEmpty(), "CLAUDE.md describes composite keys the code does not implement: " + unmet);
    }

    /** `user_id` as the field name an entity would use for it. */
    private static String toCamel(String column) {
        String[] parts = column.trim().split("_");
        StringBuilder out = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            out.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return out.toString();
    }
}
