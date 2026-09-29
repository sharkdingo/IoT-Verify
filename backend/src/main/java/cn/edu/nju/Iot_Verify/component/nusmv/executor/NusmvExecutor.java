package cn.edu.nju.Iot_Verify.component.nusmv.executor;

import cn.edu.nju.Iot_Verify.component.nusmv.NusmvTempArtifactRegistry;
import cn.edu.nju.Iot_Verify.configure.NusmvConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NuSMV 执行器
 * 职责：执行 NuSMV 批处理验证 和 交互式随机模拟
 */
@Slf4j
@Service
public class NusmvExecutor {

    private final NusmvConfig nusmvConfig;
    private final NusmvTempArtifactRegistry tempArtifactRegistry;

    @Autowired
    public NusmvExecutor(NusmvConfig nusmvConfig, NusmvTempArtifactRegistry tempArtifactRegistry) {
        this.nusmvConfig = nusmvConfig;
        this.tempArtifactRegistry = tempArtifactRegistry;
    }

    public NusmvExecutor(NusmvConfig nusmvConfig) {
        this(nusmvConfig, new NusmvTempArtifactRegistry());
    }

    private static final int PROCESS_DESTROY_TIMEOUT_SECONDS = 5;
    private static final long READER_JOIN_TIMEOUT_MS = 5000;

    // NuSMV spec result patterns
    private static final Pattern SPEC_TRUE_PATTERN = Pattern.compile(
            "-- specification (.+?) is true", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPEC_FALSE_PATTERN = Pattern.compile(
            "-- specification (.+?) is false", Pattern.CASE_INSENSITIVE);
    /**
     * NuSMV's lasso marker. Must match {@code SmvTraceParser.LOOP_START_PATTERN}, which consumes it:
     * extraction only decides whether the line survives into the trace text, never what it means.
     */
    private static final Pattern LOOP_MARKER = Pattern.compile("^\\s*--\\s*Loop starts here\\s*$");

    private volatile Semaphore executionSemaphore;

    // ==================== 批处理验证 ====================

    public NusmvResult execute(File smvFile) throws InterruptedException {
        return executeInternal(smvFile, null, List.of());
    }

    /**
     * Execute an automatic-fix model within a caller-owned total budget. The budget includes waiting
     * for the global concurrency permit and process execution; the configured NuSMV timeout remains an
     * upper bound.
     *
     * <p>Runs with {@code -df}. Repair models leave their search variables (parameter and lambda
     * {@code FROZENVAR}s) unconstrained at init, so the forward reachable-state computation NuSMV does
     * by default enumerates every combination of them and stalls on a joint search over a few
     * thresholds. Without {@code FAIRNESS} (the generator emits none) that set is only an
     * optimisation: disabling it leaves every verdict unchanged, though a printed counterexample may
     * follow a different, equally valid path.
     */
    public NusmvResult executeRepairSearch(File smvFile, long totalBudgetMs) throws InterruptedException {
        if (totalBudgetMs <= 0) {
            return NusmvResult.error("NuSMV execution was not started because the caller deadline expired");
        }
        return executeInternal(smvFile, totalBudgetMs, List.of("-df"));
    }

    private NusmvResult executeInternal(File smvFile, Long totalBudgetMs, List<String> extraArgs)
            throws InterruptedException {
        if (smvFile == null || !smvFile.exists()) {
            return NusmvResult.error("NuSMV model file does not exist or is null");
        }

        try (var ignored = tempArtifactRegistry.activate(smvFile)) {
            if (!smvFile.exists()) {
                return NusmvResult.error("NuSMV model file does not exist or is null");
            }
            return executeActiveModel(smvFile, totalBudgetMs, extraArgs);
        } catch (IllegalStateException e) {
            return NusmvResult.error(e.getMessage());
        }
    }

    private NusmvResult executeActiveModel(File smvFile, Long totalBudgetMs, List<String> extraArgs)
            throws InterruptedException {
        long startedNanos = System.nanoTime();
        long configuredPermitTimeoutMs = Math.max(0, nusmvConfig.getAcquirePermitTimeoutMs());
        long permitTimeoutMs = totalBudgetMs == null
                ? configuredPermitTimeoutMs
                : Math.min(configuredPermitTimeoutMs, totalBudgetMs);
        boolean permitAcquired = acquireExecutionPermit(permitTimeoutMs);
        if (!permitAcquired) {
            if (totalBudgetMs != null && totalBudgetMs <= configuredPermitTimeoutMs) {
                return NusmvResult.error("NuSMV execution deadline expired while waiting for capacity");
            }
            return NusmvResult.busy("NuSMV execution is busy, please retry later");
        }

        List<String> command = buildCommand(smvFile, extraArgs);
        log.info("Executing NuSMV verification");
        log.debug("NuSMV command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = null;
        Thread outputThread = null;
        try {
            long timeout = effectiveProcessTimeout(totalBudgetMs, startedNanos);
            if (timeout <= 0) {
                return NusmvResult.error("NuSMV execution was not started because the caller deadline expired");
            }
            process = processBuilder.start();

            final Process finalProcess = process;
            BoundedOutputCollector outputBuilder = new BoundedOutputCollector(nusmvConfig.getMaxOutputBytes());
            outputThread = new Thread(() -> {
                try (InputStream output = finalProcess.getInputStream()) {
                    drainOutput(output, outputBuilder);
                } catch (IOException e) {
                    log.warn("Error reading NuSMV output: {}", e.getMessage());
                }
            }, "nusmv-batch-output");
            outputThread.start();

            boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);

            if (!finished) {
                log.warn("NuSMV execution timed out after {}ms, destroying process", timeout);
                if (!terminateProcessTree(process)) {
                    log.error("Failed to stop the complete NuSMV process tree after timeout");
                }
                reclaimReaderThread(outputThread, process.getInputStream(), "merged-output");
                return NusmvResult.error("NuSMV execution timed out after " + timeout + "ms");
            }

            // 进程已正常结束，流一定会关闭，无需超时限制
            if (!waitReaderThreadCompletion(outputThread, process.getInputStream(), "merged-output")) {
                return NusmvResult.error("NuSMV output reader did not finish in time");
            }

            int exitCode = process.exitValue();
            String output = outputBuilder.text();
            log.debug("NuSMV exit code: {}, output length: {}", exitCode, output.length());

            // 将 NuSMV 输出保存到 smv 文件同目录下的 output.txt
            saveOutputToFile(smvFile, output);

            if (exitCode != 0) {
                return NusmvResult.error("NuSMV exited with code " + exitCode + ": " + output);
            }
            if (outputBuilder.isTruncated()) {
                return NusmvResult.error(
                        "NuSMV output exceeded the configured retention limit; the result is incomplete");
            }

            // Parse per-spec results from output
            List<SpecCheckResult> specResults = parseSpecResults(output);
            return NusmvResult.success(output, specResults);

        } catch (IOException e) {
            log.error("Failed to execute NuSMV", e);
            if (process != null) {
                terminateProcessTree(process);
                reclaimReaderThread(outputThread, process.getInputStream(), "merged-output");
            }
            return NusmvResult.error("Failed to execute NuSMV: " + e.getMessage());
        } catch (InterruptedException e) {
            log.warn("NuSMV execution interrupted");
            if (process != null) {
                if (!terminateProcessTree(process)) {
                    log.error("Failed to stop the complete NuSMV process tree after interruption");
                }
                reclaimReaderThread(outputThread, process.getInputStream(), "merged-output");
            }
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            releaseExecutionPermit();
        }
    }

    // === Private methods ===

    private void saveOutputToFile(File smvFile, String output) {
        try {
            File outputFile = new File(smvFile.getParentFile(), "output.txt");
            try (java.io.PrintWriter writer = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(new java.io.FileOutputStream(outputFile), StandardCharsets.UTF_8))) {
                writer.print(output);
            }
            log.debug("NuSMV output saved to: {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to save NuSMV output to file: {}", e.getMessage());
        }
    }

    /**
     * 构建 NuSMV 命令行。
     *
     * @param smvFile   模型文件
     * @param extraArgs 额外参数（如 "-int"），插入在 nusmvPath 与 smvFile 之间
     */
    private List<String> buildCommand(File smvFile, List<String> extraArgs) {
        String nusmvPath = nusmvConfig.getPath();
        String commandPrefix = nusmvConfig.getCommandPrefix();
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

        // SECURITY: commandPrefix is passed to sh -c / cmd.exe /c and can execute arbitrary commands.
        // It MUST only come from trusted server-side configuration (application.yaml / env vars),
        // NEVER from user input.
        if (commandPrefix != null && !commandPrefix.isEmpty()) {
            StringBuilder fullCommand = new StringBuilder();
            fullCommand.append(commandPrefix).append(" ").append(quoteForShell(nusmvPath, isWindows));
            for (String arg : extraArgs) {
                fullCommand.append(" ").append(arg);
            }
            fullCommand.append(" ").append(quoteForShell(smvFile.getAbsolutePath(), isWindows));
            if (isWindows) {
                command.add("cmd.exe");
                command.add("/c");
                command.add(fullCommand.toString());
            } else {
                command.add("sh");
                command.add("-c");
                command.add(fullCommand.toString());
            }
            return command;
        }
        // No commandPrefix: invoke the executable directly via ProcessBuilder.
        // Do NOT wrap with cmd.exe /c — its quoting rules are fragile and can
        // cause the command to be misinterpreted (e.g. opening an interactive shell).
        command.add(nusmvPath);
        command.addAll(extraArgs);
        command.add(smvFile.getAbsolutePath());
        return command;
    }

    private String quoteForShell(String value, boolean isWindows) {
        if (value == null) return "";
        if (isWindows) {
            return '"' + value.replace("\"", "\\\"") + '"';
        }
        return '\'' + value.replace("'", "'\"'\"'") + '\'';
    }

    private long getTimeout() {
        return nusmvConfig.getTimeoutMs();
    }

    private boolean acquireExecutionPermit() throws InterruptedException {
        return acquireExecutionPermit(Math.max(0, nusmvConfig.getAcquirePermitTimeoutMs()));
    }

    private boolean acquireExecutionPermit(long timeoutMs) throws InterruptedException {
        return executionSemaphore().tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private long effectiveProcessTimeout(Long totalBudgetMs, long startedNanos) {
        long configuredTimeoutMs = Math.max(1, getTimeout());
        if (totalBudgetMs == null) {
            return configuredTimeoutMs;
        }
        long elapsedNanos = Math.max(0, System.nanoTime() - startedNanos);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        long remainingMs = Math.max(0, totalBudgetMs - elapsedMs);
        return Math.min(configuredTimeoutMs, remainingMs);
    }

    private void releaseExecutionPermit() {
        Semaphore semaphore = executionSemaphore;
        if (semaphore != null) {
            semaphore.release();
        }
    }

    private Semaphore executionSemaphore() {
        if (executionSemaphore == null) {
            synchronized (this) {
                if (executionSemaphore == null) {
                    int maxConcurrent = Math.max(1, nusmvConfig.getMaxConcurrent());
                    executionSemaphore = new Semaphore(maxConcurrent, true);
                    log.info("NuSMV global concurrency cap initialized: {}", maxConcurrent);
                }
            }
        }
        return executionSemaphore;
    }

    // ==================== 交互式模拟 ====================

    /** NuSMV 交互模式提示符，需要从输出中过滤 */
    private static final Pattern NUSMV_PROMPT_PATTERN = Pattern.compile("^\\s*NuSMV\\s*>.*$");
    private static final String TRACE_SIMULATION_MARKER = "Trace Type: Simulation";

    /**
     * 以交互模式启动 NuSMV，执行随机模拟 N 步，返回模拟轨迹文本。
     *
     * 流程：go → pick_state -r → simulate -r -k N → show_traces → quit
     */
    public SimulationOutput executeInteractiveSimulation(File smvFile, int steps) throws InterruptedException {
        if (steps <= 0) {
            return SimulationOutput.error("Simulation steps must be positive, got: " + steps);
        }
        if (smvFile == null || !smvFile.exists()) {
            return SimulationOutput.error("NuSMV model file does not exist or is null");
        }

        try (var ignored = tempArtifactRegistry.activate(smvFile)) {
            if (!smvFile.exists()) {
                return SimulationOutput.error("NuSMV model file does not exist or is null");
            }
            return executeActiveSimulation(smvFile, steps);
        } catch (IllegalStateException e) {
            return SimulationOutput.error(e.getMessage());
        }
    }

    private SimulationOutput executeActiveSimulation(File smvFile, int steps) throws InterruptedException {
        boolean permitAcquired = acquireExecutionPermit();
        if (!permitAcquired) {
            return SimulationOutput.busy("NuSMV simulation is busy, please retry later");
        }

        List<String> command = buildCommand(smvFile, List.of("-int"));
        log.info("Executing NuSMV interactive simulation");
        log.debug("NuSMV interactive simulation command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        // 不合并 stderr，以便区分错误信息
        processBuilder.redirectErrorStream(false);

        Process process = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        try {
            process = processBuilder.start();
            long timeout = getTimeout();

            final Process fp = process;
            BoundedOutputCollector stdoutBuilder = new BoundedOutputCollector(nusmvConfig.getMaxOutputBytes());
            BoundedOutputCollector stderrBuilder = new BoundedOutputCollector(nusmvConfig.getMaxOutputBytes());

            // 独立线程读 stdout，防止管道缓冲区满导致死锁
            stdoutThread = new Thread(() -> {
                try (InputStream output = fp.getInputStream()) {
                    drainOutput(output, stdoutBuilder);
                } catch (IOException e) {
                    log.warn("Error reading NuSMV stdout: {}", e.getMessage());
                }
            }, "nusmv-sim-stdout");

            // 独立线程读 stderr
            stderrThread = new Thread(() -> {
                try (InputStream output = fp.getErrorStream()) {
                    drainOutput(output, stderrBuilder);
                } catch (IOException e) {
                    log.warn("Error reading NuSMV stderr: {}", e.getMessage());
                }
            }, "nusmv-sim-stderr");

            stdoutThread.start();
            stderrThread.start();

            // 向 stdin 写入交互命令，然后关闭（NuSMV 收到 EOF 后会退出）
            try (OutputStream stdin = process.getOutputStream()) {
                String commands = "go\npick_state -r\nsimulate -r -k " + steps + "\nshow_traces\nquit\n";
                stdin.write(commands.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            boolean finished = process.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                log.warn("NuSMV simulation timed out after {}ms, destroying process", timeout);
                if (!terminateProcessTree(process)) {
                    log.error("Failed to stop the complete NuSMV simulation process tree after timeout");
                }
                reclaimReaderThread(stdoutThread, process.getInputStream(), "stdout");
                reclaimReaderThread(stderrThread, process.getErrorStream(), "stderr");
                return SimulationOutput.error("NuSMV simulation timed out after " + timeout + "ms");
            }

            boolean stdoutReady = waitReaderThreadCompletion(stdoutThread, process.getInputStream(), "stdout");
            boolean stderrReady = waitReaderThreadCompletion(stderrThread, process.getErrorStream(), "stderr");
            if (!stdoutReady || !stderrReady) {
                return SimulationOutput.error("NuSMV simulation output reader did not finish in time");
            }

            String rawOutput = stdoutBuilder.text();
            String stderrOutput = stderrBuilder.text();

            // 保存原始输出到文件（与批处理模式一致）
            saveOutputToFile(smvFile, rawOutput);

            if (!stderrOutput.isBlank()) {
                log.warn("NuSMV simulation stderr: {}", stderrOutput);
            }

            // 校验进程退出码：非 0 表示 NuSMV 执行异常
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("NuSMV simulation exited with code {}", exitCode);
                String summary = "NuSMV exited with code " + exitCode + ".";
                if (!stderrOutput.isBlank()) {
                    summary += " stderr: " + stderrOutput.substring(0, Math.min(stderrOutput.length(), 1000));
                }
                if (!rawOutput.isBlank()) {
                    summary += " stdout: " + rawOutput.substring(0, Math.min(rawOutput.length(), 1000));
                }
                return SimulationOutput.error(summary);
            }
            if (stdoutBuilder.isTruncated() || stderrBuilder.isTruncated()) {
                return SimulationOutput.error(
                        "NuSMV simulation output exceeded the configured retention limit; the trace is incomplete");
            }

            // 从输出中提取模拟轨迹
            String traceText = extractSimulationTrace(rawOutput);
            if (traceText == null || traceText.isBlank()) {
                log.warn("No simulation trace found in NuSMV output");
                return SimulationOutput.error(
                        "No simulation trace produced. Model may have errors. Raw output: "
                                + rawOutput.substring(0, Math.min(rawOutput.length(), 2000)));
            }

            return SimulationOutput.success(traceText, rawOutput);

        } catch (IOException e) {
            log.error("Failed to execute NuSMV simulation", e);
            if (process != null) {
                terminateProcessTree(process);
                reclaimReaderThread(stdoutThread, process.getInputStream(), "stdout");
                reclaimReaderThread(stderrThread, process.getErrorStream(), "stderr");
            }
            return SimulationOutput.error("Failed to execute NuSMV simulation: " + e.getMessage());
        } catch (InterruptedException e) {
            log.warn("NuSMV simulation interrupted");
            if (process != null) {
                if (!terminateProcessTree(process)) {
                    log.error("Failed to stop the complete NuSMV simulation process tree after interruption");
                }
                reclaimReaderThread(stdoutThread, process.getInputStream(), "stdout");
                reclaimReaderThread(stderrThread, process.getErrorStream(), "stderr");
            }
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            releaseExecutionPermit();
        }
    }

    /**
     * 带超时的线程回收：先 join(5s)，若仍存活则 interrupt + 关流 + 短暂重试，
     * 避免"主线程不卡但后台线程静默泄漏"。
     */
    private boolean waitReaderThreadCompletion(Thread thread, InputStream stream, String label) throws InterruptedException {
        thread.join(READER_JOIN_TIMEOUT_MS);
        if (!thread.isAlive()) {
            return true;
        }
        log.warn("NuSMV {} reader thread did not finish in {}ms, reclaiming", label, READER_JOIN_TIMEOUT_MS);
        reclaimReaderThread(thread, stream, label, true);
        return !thread.isAlive();
    }

    private void reclaimReaderThread(Thread thread, InputStream stream, String label) {
        reclaimReaderThread(thread, stream, label, false);
    }

    private void reclaimReaderThread(Thread thread, InputStream stream, String label, boolean skipInitialJoin) {
        if (thread == null) return;
        boolean interrupted = Thread.interrupted();
        try {
            if (!skipInitialJoin) {
                interrupted |= joinReaderUntil(thread, READER_JOIN_TIMEOUT_MS);
            }
            if (thread.isAlive()) {
                log.warn("NuSMV {} reader thread still alive after join timeout, reclaiming", label);
                thread.interrupt();
                if (stream != null) {
                    try { stream.close(); } catch (IOException ignored) { }
                }
                interrupted |= joinReaderUntil(thread, 2000);
                if (thread.isAlive()) {
                    log.warn("NuSMV {} reader thread could not be reclaimed, may leak", label);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Returns whether the caller was interrupted while waiting. */
    private boolean joinReaderUntil(Thread thread, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean interrupted = false;
        while (thread.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try {
                TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    /**
     * Stop the command wrapper and every discovered descendant, then wait before releasing
     * the global execution permit. Descendants are snapshotted before the wrapper is killed
     * so shell/Wine-launched NuSMV processes cannot be orphaned by their parent exiting first.
     */
    static boolean terminateProcessTree(Process process) {
        if (process == null) return true;

        List<ProcessHandle> descendants = new ArrayList<>();
        try {
            process.toHandle().descendants().forEach(descendants::add);
        } catch (RuntimeException e) {
            log.warn("Could not enumerate NuSMV process descendants: {}", e.toString());
        }
        List<ProcessHandle> handles = new ArrayList<>(descendants.size() + 1);
        handles.add(process.toHandle());
        for (int i = descendants.size() - 1; i >= 0; i--) {
            handles.add(descendants.get(i));
        }

        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) continue;
            try {
                handle.destroyForcibly();
            } catch (RuntimeException e) {
                log.warn("Could not destroy NuSMV process {}: {}", handle.pid(), e.toString());
            }
        }

        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(PROCESS_DESTROY_TIMEOUT_SECONDS);
        try {
            while (handles.stream().anyMatch(ProcessHandle::isAlive)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            closeProcessStreams(process);
            if (interrupted) Thread.currentThread().interrupt();
        }
        return handles.stream().noneMatch(ProcessHandle::isAlive);
    }

    private static void closeProcessStreams(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    /**
     * 从交互模式的完整输出中提取模拟轨迹文本。
     * 定位 "Trace Type: Simulation" 行，取其后所有内容，过滤掉 NuSMV 提示符行。
     */
    private String extractSimulationTrace(String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) return null;

        int markerIdx = rawOutput.indexOf(TRACE_SIMULATION_MARKER);
        if (markerIdx < 0) return null;

        String afterMarker = rawOutput.substring(markerIdx + TRACE_SIMULATION_MARKER.length());
        String[] lines = afterMarker.split("\n");
        StringBuilder trace = new StringBuilder();
        for (String line : lines) {
            if (NUSMV_PROMPT_PATTERN.matcher(line).matches()) continue;
            trace.append(line).append("\n");
        }
        String normalized = trace.toString().stripTrailing();
        return normalized.isBlank() ? null : normalized;
    }

    static void drainOutput(InputStream input, BoundedOutputCollector collector) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            collector.append(buffer, 0, read);
        }
    }

    /**
     * 交互式模拟输出
     */
    public static class SimulationOutput {
        private final String traceText;
        private final String rawOutput;
        private final boolean success;
        private final String errorMessage;
        private final boolean busy;

        private SimulationOutput(String traceText, String rawOutput, boolean success, String errorMessage, boolean busy) {
            this.traceText = traceText;
            this.rawOutput = rawOutput;
            this.success = success;
            this.errorMessage = errorMessage;
            this.busy = busy;
        }

        public static SimulationOutput success(String traceText, String rawOutput) {
            return new SimulationOutput(traceText, rawOutput, true, null, false);
        }

        public static SimulationOutput error(String errorMessage) {
            return new SimulationOutput(null, null, false, errorMessage, false);
        }

        public static SimulationOutput busy(String errorMessage) {
            return new SimulationOutput(null, null, false, errorMessage, true);
        }

        public String getTraceText() { return traceText; }
        public String getRawOutput() { return rawOutput; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public boolean isBusy() { return busy; }
    }

    /**
     * 解析 NuSMV 输出，提取每个 spec 的独立结果。
     *
     * NuSMV 输出格式：
     *   -- specification CTLSPEC ... is true
     *   -- specification LTLSPEC ... is false
     *   -- as demonstrated by the following execution sequence
     *   Trace Description: ...
     *   Trace Type: ...
     *   State 1.1: ...
     *   ...
     */
    private List<SpecCheckResult> parseSpecResults(String output) {
        List<SpecCheckResult> results = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return results;
        }

        String[] lines = output.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();

            // Check for "-- specification ... is true"
            Matcher trueMatcher = SPEC_TRUE_PATTERN.matcher(line);
            if (trueMatcher.find()) {
                results.add(new SpecCheckResult(trueMatcher.group(1).trim(), true, null));
                i++;
                continue;
            }

            // Check for "-- specification ... is false"
            Matcher falseMatcher = SPEC_FALSE_PATTERN.matcher(line);
            if (falseMatcher.find()) {
                String specExpr = falseMatcher.group(1).trim();
                // Collect the counterexample trace that follows
                i++;
                StringBuilder traceBuilder = new StringBuilder();
                while (i < lines.length) {
                    String traceLine = lines[i];
                    // Stop when we hit the next spec result line
                    if (traceLine.trim().startsWith("-- specification ")) {
                        break;
                    }
                    traceBuilder.append(traceLine).append("\n");
                    i++;
                }
                String counterexample = extractTraceFromBlock(traceBuilder.toString());
                results.add(new SpecCheckResult(specExpr, false, counterexample));
                continue;
            }

            i++;
        }

        return results;
    }

    /**
     * 从 trace block 中提取 State 行（跳过 Trace Description/Type 等元信息）
     */
    private String extractTraceFromBlock(String block) {
        if (block == null || block.isEmpty()) {
            return null;
        }
        StringBuilder trace = new StringBuilder();
        boolean inStates = false;
        String pendingLoopMarker = null;
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            // The lasso marker precedes the state that begins the cycle, and when that state is the *initial*
            // one it therefore precedes the first state line too — verified on NuSMV 2.7.1, where `AF x` over
            // a model that never sets `x` prints "-- Loop starts here" above "-> State: 1.1 <-". Held back
            // rather than appended immediately, so it is only kept when a state actually follows: the trace
            // handed to SmvTraceParser must start at a state line, and a marker with no trace behind it would
            // make an empty block look non-empty.
            if (!inStates && LOOP_MARKER.matcher(trimmed).matches()) {
                pendingLoopMarker = line;
                continue;
            }
            // 兼容 NuSMV 2.7.1 格式 "-> State: 1.1 <-" 和旧格式 "State 1.1:"
            if (trimmed.startsWith("-> State:") || trimmed.startsWith("State ") || inStates) {
                if (!inStates && pendingLoopMarker != null) {
                    trace.append(pendingLoopMarker).append("\n");
                    pendingLoopMarker = null;
                }
                inStates = true;
                trace.append(line).append("\n");
            }
        }
        String result = trace.toString().trim();
        return result.isEmpty() ? null : result;
    }

    static final class BoundedOutputCollector {
        private static final String TRUNCATION_MARKER = "\n... (NuSMV output truncated)\n";
        private static final int MARKER_BYTES = TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;

        private final int payloadLimit;
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        private boolean truncated;

        BoundedOutputCollector(int maxBytes) {
            this.payloadLimit = Math.max(0, maxBytes - MARKER_BYTES);
        }

        void appendLine(String line) {
            byte[] value = (line + '\n').getBytes(StandardCharsets.UTF_8);
            append(value, 0, value.length);
        }

        void append(byte[] value, int offset, int length) {
            if (truncated || length <= 0) return;
            int remaining = payloadLimit - content.size();
            int retained = Math.min(remaining, length);
            if (retained > 0) {
                content.write(value, offset, retained);
            }
            if (retained < length) truncated = true;
        }

        String text() {
            String decoded = decodeUtf8Prefix(content.toByteArray());
            return truncated ? decoded + TRUNCATION_MARKER : decoded;
        }

        boolean isTruncated() {
            return truncated;
        }

        private String decodeUtf8Prefix(byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.IGNORE)
                        .onUnmappableCharacter(CodingErrorAction.IGNORE)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new IllegalStateException("Could not decode retained NuSMV output", e);
            }
        }
    }

    /**
     * 单个 spec 的检查结果
     */
    public static class SpecCheckResult {
        private final String specExpression;
        private final boolean passed;
        private final String counterexample;

        public SpecCheckResult(String specExpression, boolean passed, String counterexample) {
            this.specExpression = specExpression;
            this.passed = passed;
            this.counterexample = counterexample;
        }

        public String getSpecExpression() { return specExpression; }
        public boolean isPassed() { return passed; }
        public String getCounterexample() { return counterexample; }
    }

    /**
     * NuSMV 执行结果
     */
    public static class NusmvResult {
        private final String output;
        private final boolean success;
        private final String errorMessage;
        private final List<SpecCheckResult> specResults;
        private final boolean busy;

        private NusmvResult(String output, boolean success, String errorMessage,
                            List<SpecCheckResult> specResults, boolean busy) {
            this.output = output;
            this.success = success;
            this.errorMessage = errorMessage;
            this.specResults = specResults != null ? specResults : List.of();
            this.busy = busy;
        }

        public static NusmvResult success(String output, List<SpecCheckResult> specResults) {
            return new NusmvResult(output, true, null, specResults, false);
        }

        public static NusmvResult error(String errorMessage) {
            return new NusmvResult(null, false, errorMessage, null, false);
        }

        public static NusmvResult busy(String errorMessage) {
            return new NusmvResult(null, false, errorMessage, null, true);
        }

        public String getOutput() { return output; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public List<SpecCheckResult> getSpecResults() { return specResults; }
        public boolean isBusy() { return busy; }

        public boolean hasAnyViolation() {
            return specResults.stream().anyMatch(r -> !r.isPassed());
        }
    }
}
