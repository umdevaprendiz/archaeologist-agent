package com.archaeologist.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.archaeologist.audit.Agent;
import com.archaeologist.audit.AuditLogs;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class RunLauncherTests {

    @TempDir
    static Path outputParent;

    @DynamicPropertySource
    static void outputFolder(DynamicPropertyRegistry registry) {
        registry.add("archaeologist.output.folder", () -> outputParent.resolve("archaeologist-output").toString());
    }

    @Autowired
    private RunLauncher launcher;

    @TempDir
    Path tempDir;

    private final StringWriter out = new StringWriter();

    private final AtomicReference<StartedRun> started = new AtomicReference<>();

    /** Launches the work on a repository named "legacy" and keeps the run it was given. */
    private int launch(ToIntFunction<StartedRun> work) {
        return launcher.launch("hotspots", tempDir.resolve("legacy"), new PrintWriter(out, true), run -> {
            started.set(run);
            return work.applyAsInt(run);
        });
    }

    private List<JsonNode> events() throws IOException {
        Path log = started.get().folder().resolve(AuditLogs.FILE_NAME);
        return Files.readAllLines(log, UTF_8).stream().map(JsonMapper.shared()::readTree).toList();
    }

    private String lastOutcome() throws IOException {
        List<JsonNode> events = events();
        JsonNode last = events.get(events.size() - 1);
        assertThat(last.get("action").asString()).isEqualTo(RunLauncher.RUN_FINISHED);
        return last.get("outcome").asString();
    }

    @Test
    void startIsRecordedWithTheCommandRepositoryVersionAndSettings() throws IOException {
        launch(run -> 0);

        JsonNode start = events().get(0);
        assertThat(start.get("agent").asString()).isEqualTo("orchestrator");
        assertThat(start.get("action").asString()).isEqualTo(RunLauncher.RUN_STARTED);
        assertThat(start.get("input").asString())
                .startsWith("hotspots on repository 'legacy'; Code Archaeologist ")
                .contains(" on Java ", "; settings: history.years=5, risk-weights.fixes=1.0");
        assertThat(start.get("outcome").asString()).isEqualTo("run " + started.get().folder().id() + " started");
        assertThat(out.toString().strip()).isEqualTo("Run " + started.get().folder().id() + ": output in "
                + started.get().folder().path());
    }

    @Test
    void successIsRecorded() throws IOException {
        assertThat(launch(run -> 0)).isZero();

        assertThat(events()).hasSize(2);
        assertThat(lastOutcome()).isEqualTo("succeeded");
    }

    @Test
    void failingExitCodeIsRecorded() throws IOException {
        assertThat(launch(run -> 3)).isEqualTo(3);

        assertThat(lastOutcome()).isEqualTo("failed (exit code 3)");
    }

    @Test
    void expectedErrorIsRecordedAndRethrown() throws IOException {
        CliException error = new CliException(ExitCode.USAGE, "Unknown option");

        assertThatExceptionOfType(CliException.class).isThrownBy(() -> launch(run -> {
            throw error;
        })).isSameAs(error);
        assertThat(lastOutcome()).isEqualTo("failed (exit code 2): Unknown option");
    }

    @Test
    void unexpectedErrorIsRecordedAndRethrown() throws IOException {
        assertThatIllegalStateException().isThrownBy(() -> launch(run -> {
            throw new IllegalStateException("boom");
        })).withMessage("boom");
        assertThat(lastOutcome()).isEqualTo("failed with an unexpected error: IllegalStateException: boom");
    }

    @Test
    void workRecordsItsOwnEventsBetweenStartAndEnd() throws IOException {
        Path repo = tempDir.resolve("legacy");

        launch(run -> {
            run.audit().record(Agent.EXCAVATOR, "read-history", "last 5 years of " + repo, "120 commits");
            return 0;
        });

        assertThat(events()).extracting(event -> event.get("action").asString())
                .containsExactly(RunLauncher.RUN_STARTED, "read-history", RunLauncher.RUN_FINISHED);
        assertThat(events().get(1).get("input").asString()).isEqualTo("last 5 years of <repo>");
        assertThat(started.get().folder().path()).isDirectory();
    }

    @Test
    void auditLogIsClosedWhenTheRunEnds() {
        launch(run -> 0);

        assertThatIllegalStateException()
                .isThrownBy(() -> started.get().audit().record(Agent.JUDGE, "verdict", "", "late"));
    }
}
