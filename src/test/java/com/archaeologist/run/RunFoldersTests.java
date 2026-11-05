package com.archaeologist.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunFoldersTests {

    private static final Instant START = Instant.parse("2026-11-05T22:18:14Z");

    private static final RunId FIRST = RunId.of(START, 1);
    private static final RunId SECOND = RunId.of(START, 2);

    @TempDir
    Path tempDir;

    /** Hands out the given ids, in order. */
    private static RunFolders folders(Path root, RunId... ids) {
        Iterator<RunId> next = List.of(ids).iterator();
        return new RunFolders(root, next::next);
    }

    @Test
    void eachRunGetsItsOwnFolder() {
        Path root = tempDir.resolve("archaeologist-output");
        RunFolders folders = folders(root, FIRST, SECOND);

        RunFolder first = folders.create();
        RunFolder second = folders.create();

        assertThat(first.id()).isEqualTo(FIRST);
        assertThat(first.path()).isEqualTo(root.resolve(FIRST.value())).isDirectory();
        assertThat(second.path()).isEqualTo(root.resolve(SECOND.value())).isDirectory();
    }

    @Test
    void newOutputFolderGetsAGitignoreThatIgnoresEverything() throws IOException {
        Path root = tempDir.resolve(Path.of("a", "b", "output"));

        folders(root, FIRST).create();

        assertThat(Files.readString(root.resolve(".gitignore"))).isEqualTo(RunFolders.GITIGNORE);
        assertThat(RunFolders.GITIGNORE.lines()).contains("*");
    }

    @Test
    void existingOutputFolderIsLeftAsItIs() throws IOException {
        Path root = Files.createDirectory(tempDir.resolve("reports"));
        Files.writeString(root.resolve("notes.txt"), "the user's own file");

        folders(root, FIRST).create();

        assertThat(root.resolve(".gitignore")).doesNotExist();
        assertThat(root.resolve("notes.txt")).hasContent("the user's own file");
    }

    @Test
    void takenRunIdIsSkipped() throws IOException {
        Path root = tempDir.resolve("output");
        Files.createDirectories(root.resolve(FIRST.value()));

        assertThat(folders(root, FIRST, SECOND).create().id()).isEqualTo(SECOND);
    }

    @Test
    void givesUpWhenEveryIdTriedIsTaken() throws IOException {
        Path root = tempDir.resolve("output");
        Files.createDirectories(root.resolve(FIRST.value()));
        RunFolders folders = new RunFolders(root, () -> FIRST);

        assertThatExceptionOfType(RunFolderException.class).isThrownBy(folders::create)
                .withMessage("Cannot create a run folder in " + root + ": the last " + RunFolders.MAX_ATTEMPTS
                        + " run ids tried were all taken");
    }

    @Test
    void fileInTheWayIsReported() throws IOException {
        Path root = Files.createFile(tempDir.resolve("output"));

        assertThatExceptionOfType(RunFolderException.class).isThrownBy(() -> folders(root, FIRST).create())
                .withMessage("Cannot create the output folder " + root + ": a file with that name is in the way");
    }

    @Test
    void outputInsideTheRepositoryKeepsItsGitStatusClean() throws Exception {
        Path repo = Files.createDirectory(tempDir.resolve("legacy"));
        git(repo, "init", "--quiet");
        RunFolder run = folders(repo.resolve("archaeologist-output"), FIRST).create();
        Files.createDirectories(run.resolve("reports"));
        Files.writeString(run.resolve("reports/hotspots.md"), "# Hotspots");

        assertThat(git(repo, "status", "--porcelain", "--untracked-files=all")).isEmpty();
    }

    /** Runs git in {@code repo} and returns its output. Git is a requirement of the agent (see README). */
    private static String git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("git %s: %s", String.join(" ", args), output).isZero();
        return output;
    }
}
