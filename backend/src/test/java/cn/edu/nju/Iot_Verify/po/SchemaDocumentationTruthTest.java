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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks that documented composite keys still protect user-scoped identity. */
class SchemaDocumentationTruthTest {

    private static final Path MAIN_ROOT =
            Paths.get("src", "main", "java", "cn", "edu", "nju", "Iot_Verify");
    private static final Path PO_DIR = MAIN_ROOT.resolve("po");
    private static final Path DOC = Paths.get("CLAUDE.md");

    private static List<String> poSources() throws IOException {
        try (Stream<Path> paths = Files.list(PO_DIR)) {
            List<String> out = new ArrayList<>();
            for (Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                out.add(Files.readString(p, StandardCharsets.UTF_8));
            }
            return out;
        }
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
