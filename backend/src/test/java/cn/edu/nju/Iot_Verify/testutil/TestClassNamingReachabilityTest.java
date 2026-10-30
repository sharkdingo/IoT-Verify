package cn.edu.nju.Iot_Verify.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test class whose name matches no surefire include pattern never runs, and nothing reports it.
 * The suite prints a large green total either way, so the class reads as covered while its
 * assertions have never executed once.
 *
 * <p>This is not hypothetical: {@code VerifyCorrectedScene} sat in
 * {@code component/nusmv/generator} carrying an {@code @Test} method for a day. It was a probe
 * script — no assertions, a live NuSMV binary required, 44 seconds — and its name matched none of
 * surefire's defaults, so CI never touched it. The only way it surfaced was a manual reconciliation
 * of the per-class result lines against the source file count.
 *
 * <p>Surefire's default includes are {@code **}{@code /Test*.java}, {@code **}{@code /*Test.java},
 * {@code **}{@code /*Tests.java} and {@code **}{@code /*TestCase.java}. This test pins that
 * contract from the source side, so a class carrying test methods under any other name fails here
 * rather than disappearing quietly. The pom sets no {@code <includes>}; if one is ever added, this
 * list must move with it.
 */
class TestClassNamingReachabilityTest {

    private static final Path TEST_SOURCES = Path.of("src/test/java/cn/edu/nju/Iot_Verify");

    /**
     * Support classes hold shared helpers and JUnit extensions rather than test methods. They are
     * matched by name only where they genuinely carry no {@code @Test} — the check below still
     * reads each file, so adding a test method to one of these reddens this test.
     */
    private static final Pattern TEST_ANNOTATION = Pattern.compile(
            "(?m)^\\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");

    private static boolean declaresTestMethods(String source) {
        // Anchored to the start of a line so that prose mentioning the annotation — a javadoc
        // {@code @Test}, or this class's own explanation above — does not read as a test method. A
        // bare substring match would flag support classes for describing what they are not.
        return TEST_ANNOTATION.matcher(source).find();
    }

    private static boolean surefireWouldRunIt(String className) {
        return className.startsWith("Test")
                || className.endsWith("Test")
                || className.endsWith("Tests")
                || className.endsWith("TestCase");
    }

    @Test
    void everyClassCarryingTestMethodsIsReachableBySurefire() throws IOException {
        List<String> unreachable = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!declaresTestMethods(source)) continue;
                scanned++;
                String className = file.getFileName().toString().replace(".java", "");
                if (!surefireWouldRunIt(className)) {
                    unreachable.add(file + " (class " + className + ")");
                }
            }
        }

        // An empty scan must not certify that every test is reachable.
        assertTrue(scanned > 100,
                "Only " + scanned + " test-bearing sources found under " + TEST_SOURCES.toAbsolutePath()
                        + "; the scan root is wrong, so the reachability check above proves nothing.");
        assertTrue(unreachable.isEmpty(),
                "These classes declare test methods but match no surefire include pattern, so they "
                        + "never run and the suite stays green without them. Rename to *Test, or "
                        + "delete the file if it was a probe script:\n  "
                        + String.join("\n  ", unreachable));
    }

}
