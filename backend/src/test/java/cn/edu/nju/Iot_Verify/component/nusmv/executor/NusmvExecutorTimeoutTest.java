package cn.edu.nju.Iot_Verify.component.nusmv.executor;

import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that NuSMV execution timeout is sourced exclusively from NusmvConfig
 * (which is bound to application.yaml / environment variables via Spring).
 */
class NusmvExecutorTimeoutTest {

    private long invokeGetTimeout(NusmvExecutor executor) throws Exception {
        Method method = NusmvExecutor.class.getDeclaredMethod("getTimeout");
        method.setAccessible(true);
        return (long) method.invoke(executor);
    }

    @Test
    void getTimeout_returnsConfigValue() throws Exception {
        NusmvConfig config = new NusmvConfig();
        config.setTimeoutMs(60000);

        NusmvExecutor executor = new NusmvExecutor(config);

        long timeout = invokeGetTimeout(executor);
        assertEquals(60000, timeout);
    }

    @ParameterizedTest
    @ValueSource(longs = {100, 5000, 120000, 300000})
    void getTimeout_reflectsConfigChanges(long configuredTimeout) throws Exception {
        NusmvConfig config = new NusmvConfig();
        config.setTimeoutMs(configuredTimeout);

        NusmvExecutor executor = new NusmvExecutor(config);

        assertEquals(configuredTimeout, invokeGetTimeout(executor));
    }

    @Test
    void configDefault_isPositive() {
        NusmvConfig config = new NusmvConfig();
        assertTrue(config.getTimeoutMs() > 0,
                "NusmvConfig default timeout must be positive");
    }

    @Test
    void expiredCallerBudget_doesNotStartNuSMVOrChangePermitCount() throws Exception {
        NusmvConfig config = new NusmvConfig();
        config.setMaxConcurrent(1);
        NusmvExecutor executor = new NusmvExecutor(config);
        File model = Files.createTempFile("expired-fix", ".smv").toFile();

        NusmvExecutor.NusmvResult result = executor.executeRepairSearch(model, 0);

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("deadline expired"));
        Field semaphoreField = NusmvExecutor.class.getDeclaredField("executionSemaphore");
        semaphoreField.setAccessible(true);
        assertNull(semaphoreField.get(executor), "an expired call must not initialize or acquire the semaphore");
        assertTrue(model.delete());
    }

    @Test
    void failedPermitAcquisition_doesNotReleaseAnUnacquiredPermit() throws Exception {
        NusmvConfig config = new NusmvConfig();
        config.setMaxConcurrent(1);
        config.setAcquirePermitTimeoutMs(1);
        NusmvExecutor executor = new NusmvExecutor(config);
        Field semaphoreField = NusmvExecutor.class.getDeclaredField("executionSemaphore");
        semaphoreField.setAccessible(true);
        Semaphore occupied = new Semaphore(0, true);
        semaphoreField.set(executor, occupied);
        File model = Files.createTempFile("busy-fix", ".smv").toFile();

        NusmvExecutor.NusmvResult result = executor.executeRepairSearch(model, 1);

        assertFalse(result.isSuccess());
        assertEquals(0, occupied.availablePermits(), "a busy call must not create a phantom permit");
        assertTrue(model.delete());
    }

    /**
     * Repair models leave their search variables unconstrained, and without {@code -df} NuSMV's
     * reachability pass enumerates them. The JVM stands in for NuSMV: it names any option it rejects.
     */
    @Test
    void repairSearchPassesDfWhileOrdinaryVerificationDoesNot(@TempDir Path dir) throws Exception {
        NusmvConfig config = new NusmvConfig();
        config.setPath(javaExecutable());
        NusmvExecutor executor = new NusmvExecutor(config);
        // Its own directory: the executor writes output.txt beside the model.
        File model = Files.createFile(dir.resolve("model.smv")).toFile();

        NusmvExecutor.NusmvResult repair = executor.executeRepairSearch(model, 30_000);
        NusmvExecutor.NusmvResult ordinary = executor.execute(model);

        assertTrue(repair.getErrorMessage().contains("-df"), repair.getErrorMessage());
        assertFalse(ordinary.getErrorMessage().contains("-df"), ordinary.getErrorMessage());
    }

    private static String javaExecutable() {
        return new File(System.getProperty("java.home"),
                "bin" + File.separator + (System.getProperty("os.name").toLowerCase().contains("windows")
                        ? "java.exe" : "java")).getAbsolutePath();
    }

    @Test
    void terminateProcessTreeStopsWrapperAndDiscoveredDescendant() throws Exception {
        String javaExecutable = javaExecutable();
        Process wrapper = new ProcessBuilder(
                javaExecutable,
                "-cp", System.getProperty("java.class.path"),
                ProcessTreeFixture.class.getName(), "parent")
                .redirectErrorStream(true)
                .start();
        ProcessHandle descendant = null;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    wrapper.getInputStream(), StandardCharsets.UTF_8));
            String pidLine = reader.readLine();
            assertNotNull(pidLine, "fixture parent must report its child PID");
            descendant = ProcessHandle.of(Long.parseLong(pidLine)).orElseThrow();
            assertTrue(descendant.isAlive());

            assertTrue(NusmvExecutor.terminateProcessTree(wrapper));

            assertTrue(wrapper.waitFor(2, TimeUnit.SECONDS));
            assertFalse(descendant.isAlive());
        } finally {
            if (descendant != null && descendant.isAlive()) descendant.destroyForcibly();
            if (wrapper.isAlive()) wrapper.destroyForcibly();
        }
    }

    public static final class ProcessTreeFixture {
        private ProcessTreeFixture() { }

        public static void main(String[] args) throws Exception {
            if (args.length > 0 && "parent".equals(args[0])) {
                String javaExecutable = new File(System.getProperty("java.home"),
                        "bin" + File.separator + (System.getProperty("os.name").toLowerCase().contains("windows")
                                ? "java.exe" : "java")).getAbsolutePath();
                Process child = new ProcessBuilder(
                        javaExecutable,
                        "-cp", System.getProperty("java.class.path"),
                        ProcessTreeFixture.class.getName(), "child")
                        .start();
                System.out.println(child.pid());
                System.out.flush();
            }
            Thread.sleep(TimeUnit.MINUTES.toMillis(1));
        }
    }
}
