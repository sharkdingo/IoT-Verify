package cn.edu.nju.Iot_Verify.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task that has stopped must not publish a stage that says it is still working.
 *
 * Every member of {@code TaskProgressStage} names work in progress — {@code QUEUED},
 * {@code GENERATING_MODEL}, {@code EXECUTING_MODEL_CHECKER}, {@code RUNNING_SIMULATION},
 * {@code EXPLORING_CANDIDATES}, {@code PERSISTING_RESULT} — and there is no terminal member. So a terminal
 * transition that does not clear the column leaves whatever stage the worker was in when it stopped.
 *
 * Observed on a live run before the fix: {@code status: COMPLETED, progress: 100,
 * progressStage: PERSISTING_RESULT} — a finished simulation still claiming to be saving its result. Eleven of
 * the twelve terminal transitions across the three task repositories had the same gap.
 *
 * The web client hid it ({@code activeTaskProgressStage} returns null for terminal statuses), but six AI tools
 * publish the field raw, so an agent asked about a finished run reported it as still persisting. This is the
 * same class of defect as {@code progress} once being forced to 100 on a task that never finished: a field whose
 * value contradicts {@code status}.
 *
 * Asserted against the JPQL text because these are {@code @Modifying} queries with no return value to inspect —
 * a behavioural test would need a worker lease per repository, and this states the invariant directly.
 */
class TerminalTaskProgressStageContractTest {

    /** The four transitions that move a task into a terminal state. */
    private static final List<String> TERMINAL_TRANSITIONS = List.of(
            "completeTaskIfRunning",
            "failTaskIfActive",
            "failExpiredTask",
            "cancelTaskIfStillActive");

    private static final List<String> TASK_REPOSITORIES = List.of(
            "SimulationTaskRepository",
            "VerificationTaskRepository",
            "FuzzTaskRepository");

    @Test
    @DisplayName("every terminal transition clears progressStage")
    void terminalTransitionsClearProgressStage() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (String repository : TASK_REPOSITORIES) {
            String source = Files.readString(
                    Path.of("src/main/java/cn/edu/nju/Iot_Verify/repository/" + repository + ".java"),
                    StandardCharsets.UTF_8);

            for (String transition : TERMINAL_TRANSITIONS) {
                int signature = source.indexOf("int " + transition);
                if (signature < 0) continue;
                checked++;

                int queryStart = source.lastIndexOf("@Query(", signature);
                assertTrue(queryStart >= 0, repository + "." + transition + " should have a @Query");
                String query = source.substring(queryStart, signature);

                if (!query.contains("progressStage = NULL")) {
                    offenders.add(repository + "." + transition);
                }
            }
        }

        assertTrue(checked >= 12,
                "expected at least 12 terminal transitions across the three task repositories, found " + checked);
        assertTrue(offenders.isEmpty(),
                "these terminal transitions leave a stale in-progress stage: " + offenders);
    }

    @Test
    @DisplayName("no progress stage means completion, so none may be named terminal")
    void noStageClaimsToBeTerminal() throws IOException {
        // If a terminal member were ever added to the enum, clearing the column would stop being the right fix
        // and this contract would need rethinking rather than silently diverging.
        String stages = Files.readString(
                Path.of("src/main/java/cn/edu/nju/Iot_Verify/dto/model/TaskProgressStage.java"),
                StandardCharsets.UTF_8);

        Matcher members = Pattern.compile("^\\s{4}([A-Z_]+)[,;]?\\s*$", Pattern.MULTILINE).matcher(stages);
        List<String> terminalSounding = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        while (members.find()) {
            String member = members.group(1);
            seen.add(member);
            if (member.matches(".*(COMPLETED|FINISHED|DONE|FAILED|CANCELLED|TERMINAL).*")) {
                terminalSounding.add(member);
            }
        }

        // A coverage floor: the member pattern keys on a four-space indent, so a reformat would silently match
        // nothing and this check would pass by finding no members rather than by finding no terminal ones. Its
        // sibling above already asserts `checked >= 12` for exactly this reason.
        assertTrue(seen.size() >= 3,
                "expected to read at least 3 progress stages, found " + seen
                        + " — the member scan is probably broken, so an empty result proves nothing");
        assertTrue(terminalSounding.isEmpty(),
                "TaskProgressStage should only name work in progress; terminal state belongs to `status`. Found: "
                        + terminalSounding);
    }
}
