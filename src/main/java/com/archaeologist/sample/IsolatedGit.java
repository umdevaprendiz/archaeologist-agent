package com.archaeologist.sample;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs Git without anything from the user's setup: no global or system configuration (so no identity,
 * hooks, templates, signing or credential helpers of theirs), and no {@code GIT_*} variables from the
 * environment. Close it to delete the empty folder it uses as Git's home.
 */
final class IsolatedGit implements AutoCloseable {

    private static final long TIMEOUT_MINUTES = 5;

    /** Git's home: an empty folder, so Git finds no configuration of the user's. */
    private final Path home;

    IsolatedGit() {
        try {
            home = Files.createTempDirectory("archaeologist-git-");
        } catch (IOException ex) {
            throw new SampleException("Cannot create a temporary folder for Git: " + ex.getClass().getSimpleName(), ex);
        }
    }

    /**
     * Runs {@code git <args>} in {@code folder} and returns its standard output.
     *
     * @param input written to Git's standard input, or {@code null}
     * @throws SampleException if Git is missing or fails. The message never quotes Git's own output,
     *                         which may name absolute paths.
     */
    String run(Path folder, byte[] input, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "-c", "core.fsmonitor=false",
                "-c", "core.hooksPath=" + home.resolve("no-hooks").toString().replace('\\', '/'),
                "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile());
        isolate(builder.environment());
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new SampleException("Git is needed for this, but it was not found: install Git and make sure it is "
                    + "on the PATH", ex);
        }
        // Read both outputs while writing the input, so a full pipe never blocks Git
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null) {
                stdin.write(input);
            }
        } catch (IOException ex) {
            // Git stopped reading: its exit code tells why
        }
        try {
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new SampleException("Git did not finish 'git " + args[0] + "' within " + TIMEOUT_MINUTES + " minutes");
            }
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new SampleException("Interrupted while Git was running", ex);
        }
        stderr.join();
        if (process.exitValue() != 0) {
            throw new SampleException("Git failed: 'git " + args[0] + "' exited with code " + process.exitValue());
        }
        return new String(stdout.join(), UTF_8);
    }

    private void isolate(Map<String, String> environment) {
        environment.keySet().removeIf(name -> name.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("HOME", home.toString());
        environment.put("XDG_CONFIG_HOME", home.toString());
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
