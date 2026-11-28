package cn.edu.nju.Iot_Verify.component.nusmv.generator.module;

import cn.edu.nju.Iot_Verify.util.NaturalChangeRateParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the small interval models against the actual NuSMV executable. */
record RateSemanticsTestSupport(String modelFilePrefix, long timeoutSeconds) {
    static String transitionCandidates(String rateDeclaration, String varRef, int lower, int upper) {
        return NaturalChangeRateParser.parse(rateDeclaration).admissibleDeltas().stream()
                .map(delta -> {
                    String shifted = delta == 0 ? varRef
                            : delta > 0 ? varRef + " + " + delta
                            : varRef + " - " + Math.abs(delta);
                    return "max(" + lower + ", min(" + upper + ", " + shifted + "))";
                })
                .collect(Collectors.joining(", "));
    }

    List<String> runNusmv(String nusmv, String model) throws Exception {
        Path modelFile = Files.createTempFile(modelFilePrefix, ".smv");
        try {
            Files.writeString(modelFile, model);
            Process process = new ProcessBuilder(nusmv, modelFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            List<String> specLines = new ArrayList<>();
            try (var reader = process.inputReader()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("specification")) {
                        specLines.add(line);
                    }
                }
            }
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "NuSMV did not terminate");
            assertFalse(specLines.isEmpty(), "NuSMV reported no specification results");
            return specLines;
        } finally {
            Files.deleteIfExists(modelFile);
        }
    }
}
