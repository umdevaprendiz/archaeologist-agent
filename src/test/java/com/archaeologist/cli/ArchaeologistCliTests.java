package com.archaeologist.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

@SpringBootTest
class ArchaeologistCliTests {

    @Autowired
    private ArchaeologistCli cli;

    @TempDir
    Path tempDir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        CommandLine commandLine = cli.newCommandLine();
        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));
        commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(Ansi.OFF));
        return commandLine.execute(args);
    }

    private Path gitRepository() throws IOException {
        Path repo = Files.createDirectory(tempDir.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        return repo;
    }

    @Test
    void helpListsTheCommandsAndExitCodes() {
        assertThat(run("--help")).isEqualTo(0);
        assertThat(out.toString())
                .contains("Usage: archaeologist", "hotspots", "run", "scout", "help", "--stacktrace")
                .contains("Runs 100%").doesNotContain("%%")
                .contains("Exit codes:", "Invalid command line", "Invalid configuration");
    }

    @Test
    void subcommandHelpShowsItsParameterAndExitCodes() {
        assertThat(run("help", "hotspots")).isEqualTo(0);
        assertThat(out.toString()).contains("Usage: archaeologist hotspots", "<repo>", "Exit codes:");
    }

    @Test
    void versionShowsTheProductAndJava() {
        assertThat(run("--version")).isEqualTo(0);
        assertThat(out.toString()).contains("Code Archaeologist", "Java ");
    }

    @Test
    void noCommandShowsUsageAndFails() {
        assertThat(run()).isEqualTo(ExitCode.USAGE.code());
        assertThat(err.toString()).contains("Usage: archaeologist");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void unknownCommandIsAUsageError() {
        assertThat(run("hotspot")).isEqualTo(ExitCode.USAGE.code());
        assertThat(err.toString())
                .contains("Error: Unmatched argument", "hotspots", "Run 'archaeologist --help' for usage.");
    }

    @Test
    void missingRepositoryArgumentIsAUsageError() {
        assertThat(run("hotspots")).isEqualTo(ExitCode.USAGE.code());
        assertThat(err.toString()).contains("<repo>", "Run 'archaeologist hotspots --help' for usage.");
    }

    @Test
    void repositoryThatDoesNotExistIsRejected() {
        Path missing = tempDir.resolve("missing");

        assertThat(run("hotspots", missing.toString())).isEqualTo(ExitCode.INVALID_REPOSITORY.code());
        assertThat(err.toString()).contains("Error: Repository not found: " + missing);
    }

    @Test
    void fileInsteadOfFolderIsRejected() throws IOException {
        Path file = Files.createFile(tempDir.resolve("file.txt"));

        assertThat(run("run", file.toString())).isEqualTo(ExitCode.INVALID_REPOSITORY.code());
        assertThat(err.toString()).contains("Error: Not a folder: " + file);
    }

    @Test
    void folderWithoutGitIsRejected() {
        assertThat(run("hotspots", tempDir.toString())).isEqualTo(ExitCode.INVALID_REPOSITORY.code());
        assertThat(err.toString()).contains("Error: Not a Git repository (no .git found): " + tempDir);
    }

    @Test
    void expectedErrorsHaveNoStackTrace() {
        run("hotspots", tempDir.resolve("missing").toString());

        assertThat(err.toString()).doesNotContain("Exception", "\tat ", "--stacktrace");
    }

    @Test
    void errorMessagesEchoThePathAsTyped() {
        run("hotspots", "relative/missing");

        assertThat(err.toString()).contains("Repository not found: " + Path.of("relative", "missing"));
        assertThat(err.toString()).doesNotContain(Path.of("").toAbsolutePath().toString());
    }

    @Test
    void stacktraceOptionIsAcceptedAfterTheSubcommand() {
        assertThat(run("hotspots", "--stacktrace", tempDir.resolve("missing").toString()))
                .isEqualTo(ExitCode.INVALID_REPOSITORY.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hotspots", "run"})
    void validRepositoryReachesTheCommand(String command) throws IOException {
        assertThat(run(command, gitRepository().toString())).isEqualTo(ExitCode.FAILURE.code());
        assertThat(err.toString()).contains("Error: The '" + command + "' command is not implemented yet");
    }

    @Test
    void scoutIsNotImplementedYet() {
        assertThat(run("scout")).isEqualTo(ExitCode.FAILURE.code());
        assertThat(err.toString()).contains("Error: The 'scout' command is not implemented yet");
    }
}
