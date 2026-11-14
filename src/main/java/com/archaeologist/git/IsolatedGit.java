package com.archaeologist.git;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.archaeologist.git.GitException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Runs Git in a folder the agent does not trust, with nothing from the user's setup.
 *
 * <p><b>Nothing from the user's setup.</b> Git gets an empty folder as its home and no system
 * configuration, so the user's identity, aliases, hooks, templates, signing keys and credential helpers are
 * never used. Its environment holds only what it needs to start (the program search path and, on Windows,
 * the system folders): no {@code GIT_*} variables of the user's, and none of their tokens or passwords.
 *
 * <p><b>Nothing from the repository runs.</b> A repository's own configuration ({@code .git/config}) can
 * name commands that Git runs by itself: hooks, a file system monitor, a signature verifier, a pager,
 * credential helpers. A repository that was downloaded or copied, rather than cloned, brings that
 * configuration along. Every command runs with them switched off (see {@link #protections()}). Diff drivers
 * are switched off per command: callers add {@code --no-ext-diff} and {@code --no-textconv} to commands that
 * compare files.
 *
 * <p><b>Only the given folder, and only this machine.</b> Git never looks for a repository above the folder
 * it runs in, so a folder with a damaged {@code .git} inside another repository is never taken for that
 * repository. It never asks for a password, takes no optional locks and, with Git 2.44 or newer, never
 * fetches over the network the objects a partial clone left out.
 *
 * <p>Error messages never quote Git's output, which may name absolute paths. Close it to delete the empty
 * folder it uses as Git's home.
 */
public final class IsolatedGit implements AutoCloseable {

    /** Time limit of {@link #run} and {@link #test}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /** Reads what a command writes to its standard output, as it comes. */
    @FunctionalInterface
    public interface OutputReader<T> {

        /** Reads {@code output} to the end, or as far as needed: the rest is skipped. */
        T read(InputStream output) throws IOException;
    }

    /** The only variables Git gets from the agent's environment, compared ignoring case as Windows does. */
    private static final List<String> KEPT_VARIABLES =
            List.of("PATH", "PATHEXT", "SystemRoot", "windir", "ComSpec", "TEMP", "TMP", "TMPDIR");

    /** Reads and writes Git's standard streams while the caller reads its output. */
    private static final Executor STREAMS = task -> {
        Thread thread = new Thread(task, "git-streams");
        thread.setDaemon(true);
        thread.start();
    };

    /** Stops the commands that run past their time limit. */
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    private record Completed<T>(int exitCode, T output, String errors) {
    }

    /** Git's home: an empty folder, so Git finds no configuration of the user's. */
    private final Path home;

    public IsolatedGit() {
        try {
            home = Files.createTempDirectory("archaeologist-git-");
        } catch (IOException ex) {
            throw new GitException(Reason.FAILED,
                    "Cannot create a temporary folder for Git: " + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * Runs {@code git <args>} in {@code folder} and returns its standard output.
     *
     * @throws GitException if Git is missing, fails or runs past {@link #DEFAULT_TIMEOUT}
     */
    public String run(Path folder, String... args) {
        return run(folder, null, args);
    }

    /**
     * Runs {@code git <args>} in {@code folder} and returns its standard output.
     *
     * @param input written to Git's standard input, or {@code null}
     * @throws GitException if Git is missing, fails or runs past {@link #DEFAULT_TIMEOUT}
     */
    public String run(Path folder, byte[] input, String... args) {
        Completed<byte[]> completed = execute(folder, input, DEFAULT_TIMEOUT, InputStream::readAllBytes, args);
        if (completed.exitCode() != 0) {
            throw failure(args, completed);
        }
        return new String(completed.output(), UTF_8);
    }

    /**
     * Runs a command that answers yes or no with its exit code, such as {@code rev-parse --verify --quiet}.
     *
     * @return {@code true} if it exits with 0, {@code false} if it exits with 1
     * @throws GitException if Git is missing, exits with any other code or runs past {@link #DEFAULT_TIMEOUT}
     */
    public boolean test(Path folder, String... args) {
        Completed<byte[]> completed = execute(folder, null, DEFAULT_TIMEOUT, InputStream::readAllBytes, args);
        return switch (completed.exitCode()) {
            case 0 -> true;
            case 1 -> false;
            default -> throw failure(args, completed);
        };
    }

    /**
     * Runs {@code git <args>} in {@code folder} and hands its standard output to {@code reader} as it comes,
     * so a long output is never held in memory as a whole.
     *
     * @return what {@code reader} returned
     * @throws GitException if Git is missing, fails or runs past {@code timeout}, or {@code reader} fails
     */
    public <T> T read(Path folder, Duration timeout, OutputReader<T> reader, String... args) {
        Completed<T> completed = execute(folder, null, timeout, reader, args);
        if (completed.exitCode() != 0) {
            throw failure(args, completed);
        }
        return completed.output();
    }

    /**
     * Configuration given on the command line, which wins over the repository's own: no command from the
     * repository runs, nothing is written in the background, and the output is plain and in UTF-8.
     */
    private List<String> protections() {
        return List.of(
                // Hooks are programs from the repository: point Git to a folder that doesn't exist
                "core.hooksPath=" + home.resolve("no-hooks").toString().replace('\\', '/'),
                // A file system monitor is a program that Git starts and keeps running
                "core.fsmonitor=false",
                // Showing signatures runs the program in gpg.program, which the repository can set
                "log.showSignature=false",
                "core.pager=cat",
                // An empty helper clears the list: no credential helper runs, ever
                "credential.helper=",
                // No garbage collection or maintenance in the background: reading never writes
                "gc.auto=0",
                "maintenance.auto=false",
                "color.ui=false",
                "i18n.logOutputEncoding=UTF-8",
                // For writing (the sample repository): no line ending conversion, no signing, a known branch
                "core.autocrlf=false",
                "commit.gpgSign=false",
                "tag.gpgSign=false",
                "init.defaultBranch=main");
    }

    private <T> Completed<T> execute(Path folder, byte[] input, Duration timeout, OutputReader<T> reader,
            String... args) {
        List<String> command = new ArrayList<>(List.of("git"));
        for (String setting : protections()) {
            command.add("-c");
            command.add(setting);
        }
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile());
        isolate(builder.environment(), folder);
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new GitException(Reason.NOT_INSTALLED,
                    "Git is needed for this, but it was not found: install Git and make sure it is on the PATH", ex);
        }
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> alarm = WATCHDOG.schedule(() -> {
            // A Git that already finished is not late, even if its output is still being read
            if (process.isAlive()) {
                timedOut.set(true);
                process.destroyForcibly();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            // Errors and input go on other threads, so a full pipe never blocks Git
            CompletableFuture<byte[]> errors = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()), STREAMS);
            CompletableFuture.runAsync(() -> write(process.getOutputStream(), input), STREAMS);
            T output;
            try (InputStream stdout = process.getInputStream()) {
                output = reader.read(stdout);
                stdout.transferTo(OutputStream.nullOutputStream());
            } catch (IOException | RuntimeException ex) {
                process.destroyForcibly();
                if (timedOut.get()) {
                    throw timeout(args, timeout);
                }
                if (ex instanceof GitException gitException) {
                    throw gitException;
                }
                throw new GitException(Reason.FAILED, "Cannot read the output of " + describe(args) + ": "
                        + ex.getClass().getSimpleName(), ex);
            }
            process.waitFor();
            if (timedOut.get()) {
                throw timeout(args, timeout);
            }
            return new Completed<>(process.exitValue(), output, new String(join(errors), UTF_8));
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException(Reason.FAILED, "Interrupted while Git was running", ex);
        } finally {
            alarm.cancel(false);
        }
    }

    private void isolate(Map<String, String> environment, Path folder) {
        environment.keySet().removeIf(name -> KEPT_VARIABLES.stream().noneMatch(name::equalsIgnoreCase));
        environment.put("HOME", home.toString());
        environment.put("XDG_CONFIG_HOME", home.toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_NO_LAZY_FETCH", "1");
        environment.put("GIT_OPTIONAL_LOCKS", "0");
        // Messages in English whatever the machine's language, so failures can be recognized
        environment.put("LC_ALL", "C");
        Path ceiling = ceiling(folder);
        if (ceiling != null) {
            environment.put("GIT_CEILING_DIRECTORIES", ceiling.toString());
        }
    }

    /** The folder above {@code folder}, as Git sees it: links resolved. */
    private static Path ceiling(Path folder) {
        Path real;
        try {
            real = folder.toRealPath();
        } catch (IOException ex) {
            real = folder.toAbsolutePath().normalize();
        }
        return real.getParent();
    }

    private static GitException failure(String[] args, Completed<?> completed) {
        String errors = completed.errors().toLowerCase(Locale.ROOT);
        if (errors.contains("dubious ownership")) {
            return new GitException(Reason.NOT_OWNED, "Git refuses to read this repository: its folder belongs to "
                    + "another user. Run the agent as the folder's owner, or copy the repository to a folder you own");
        }
        if (errors.contains("not a git repository")) {
            return new GitException(Reason.NOT_A_REPOSITORY,
                    "Git finds no repository here: the .git folder is missing or damaged");
        }
        return new GitException(Reason.FAILED, "Git failed: " + describe(args) + " exited with code "
                + completed.exitCode());
    }

    private static GitException timeout(String[] args, Duration timeout) {
        long minutes = timeout.toMinutes();
        String limit = minutes > 0 ? count(minutes, "minute") : count(Math.max(1, timeout.toSeconds()), "second");
        return new GitException(Reason.TIMED_OUT, "Git did not finish " + describe(args) + " within " + limit);
    }

    private static String count(long number, String unit) {
        return number + " " + unit + (number == 1 ? "" : "s");
    }

    /** The command and its subcommand only, e.g. {@code 'git log'}: arguments may hold paths. */
    private static String describe(String[] args) {
        return args.length == 0 ? "'git'" : "'git " + args[0] + "'";
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void write(OutputStream stdin, byte[] input) {
        try (stdin) {
            if (input != null) {
                stdin.write(input);
            }
        } catch (IOException ex) {
            // Git stopped reading: its exit code tells why
        }
    }

    private static byte[] join(CompletableFuture<byte[]> errors) {
        try {
            return errors.join();
        } catch (CompletionException ex) {
            // Lost error output only makes a failure harder to recognize
            return new byte[0];
        }
    }

    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "git-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Override
    public void close() {
        try (Stream<Path> paths = Files.walk(home)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            // A leftover empty temporary folder is harmless
        }
    }
}
