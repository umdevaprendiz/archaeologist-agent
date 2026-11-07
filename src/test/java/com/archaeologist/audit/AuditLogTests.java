package com.archaeologist.audit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuditLogTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T22:00:00.123Z"), ZoneOffset.UTC);

    private static final PathRedactor NO_PATHS = new PathRedactor(Map.of());

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("audit.jsonl");
    }

    private AuditLog open() {
        return AuditLog.open(file(), CLOCK, NO_PATHS);
    }

    /** Lines as JSON Lines defines them: split on \n only, so a stray U+2028 or \r would show up. */
    private List<String> lines() throws IOException {
        String content = Files.readString(file(), UTF_8);
        assertThat(content).endsWith("\n");
        return List.of(content.substring(0, content.length() - 1).split("\n", -1));
    }

    private static JsonNode parse(String line) {
        return JsonMapper.shared().readTree(line);
    }

    /** Records one event with this input and returns the input as read back from the file. */
    private String roundTrip(String input) throws IOException {
        try (AuditLog log = open()) {
            log.record(Agent.EXCAVATOR, "classify-commit", input, "fix");
        }
        List<String> lines = lines();
        assertThat(lines).hasSize(1);
        return parse(lines.get(0)).get("input").asString();
    }

    @Test
    void eventIsOneJsonObjectWithItsFieldsInOrder() throws IOException {
        try (AuditLog log = open()) {
            log.record(Agent.JUDGE, "verdict", "target Invoice, attempt 2", "approved");
        }

        assertThat(Files.readString(file(), UTF_8)).isEqualTo("{\"timestamp\":\"2026-11-07T22:00:00.123Z\","
                + "\"agent\":\"judge\",\"action\":\"verdict\",\"input\":\"target Invoice, attempt 2\","
                + "\"outcome\":\"approved\"}\n");
    }

    @Test
    void timestampAlwaysHasMilliseconds() throws IOException {
        Clock onTheSecond = Clock.fixed(Instant.parse("2026-11-07T22:00:00Z"), ZoneOffset.UTC);
        try (AuditLog log = AuditLog.open(file(), onTheSecond, NO_PATHS)) {
            log.record(Agent.SCOUT, "detect-hardware", "", "8 cores");
        }

        assertThat(parse(lines().get(0)).get("timestamp").asString()).isEqualTo("2026-11-07T22:00:00.000Z");
    }

    @Test
    void eventsAreAddedInOrder() throws IOException {
        try (AuditLog log = open()) {
            log.record(Agent.ORCHESTRATOR, "run-started", "hotspots", "started");
            log.record(Agent.ORCHESTRATOR, "run-finished", "", "succeeded");
        }

        assertThat(lines()).extracting(line -> parse(line).get("action").asString())
                .containsExactly("run-started", "run-finished");
    }

    @Test
    void lineBreaksAndTerminalCodesStayOnOneLine() throws IOException {
        String message = "first\nsecond\r\n\tthird \u001B[31mred\u001B[0m \u0000";

        assertThat(roundTrip(message)).isEqualTo(message);
        assertThat(Files.readString(file(), UTF_8)).doesNotContain("\r", "\t", "\u001B", "\u0000");
    }

    /** Given as numbers: written as characters, they would hide in this file the way they hide in a log. */
    @ParameterizedTest
    @ValueSource(ints = {0x7F, 0x85, 0x9B, 0x2028, 0x2029, 0x061C, 0x200E, 0x200F, 0x202A, 0x202E, 0x2066, 0x2069})
    void charactersThatChangeHowTextShowsAreEscaped(int codePoint) throws IOException {
        String character = Character.toString(codePoint);
        String message = "if (isAdmin) " + character + " return;";

        assertThat(roundTrip(message)).isEqualTo(message);
        assertThat(Files.readString(file(), UTF_8)).doesNotContain(character)
                .contains(String.format("\\u%04X", codePoint));
    }

    @Test
    void accentedTextAndSlashesStayReadable() throws IOException {
        String message = "corrige cálculo da ação em src/main/Pedido.java — 😀";

        assertThat(roundTrip(message)).isEqualTo(message);
        assertThat(Files.readString(file(), UTF_8)).contains(message);
    }

    @Test
    void longTextsAreCut() throws IOException {
        try (AuditLog log = open()) {
            log.record(Agent.GUARDIAN, "generate-tests", "a".repeat(5000), "b".repeat(AuditLog.MAX_TEXT_LENGTH + 1));
        }

        JsonNode event = parse(lines().get(0));
        assertThat(event.get("input").asString()).isEqualTo("a".repeat(AuditLog.MAX_TEXT_LENGTH) + AuditLog.TRUNCATED);
        assertThat(event.get("outcome").asString())
                .isEqualTo("b".repeat(AuditLog.MAX_TEXT_LENGTH) + AuditLog.TRUNCATED);
    }

    @Test
    void textAtTheLimitIsKept() throws IOException {
        String message = "a".repeat(AuditLog.MAX_TEXT_LENGTH);

        assertThat(roundTrip(message)).isEqualTo(message);
    }

    @Test
    void cutNeverSplitsACharacter() throws IOException {
        // The emoji takes two Java chars: the limit falls between them
        String message = "a".repeat(AuditLog.MAX_TEXT_LENGTH - 1) + "😀" + "more";

        assertThat(roundTrip(message)).isEqualTo("a".repeat(AuditLog.MAX_TEXT_LENGTH - 1) + AuditLog.TRUNCATED);
    }

    @Test
    void absolutePathsBecomePlaceholders() throws IOException {
        Path repo = tempDir.resolve("legacy");
        String separator = FileSystems.getDefault().getSeparator();
        try (AuditLog log = AuditLog.open(file(), CLOCK, new PathRedactor(Map.of(repo, "<repo>")))) {
            log.record(Agent.CARTOGRAPHER, "parse", "file " + repo.resolve("src").resolve("A.java"),
                    "failed: cannot read " + repo);
        }

        JsonNode event = parse(lines().get(0));
        assertThat(event.get("input").asString()).isEqualTo("file <repo>" + separator + "src" + separator + "A.java");
        assertThat(event.get("outcome").asString()).isEqualTo("failed: cannot read <repo>");
    }

    @Test
    void openingAnExistingLogAddsToIt() throws IOException {
        try (AuditLog log = open()) {
            log.record(Agent.ORCHESTRATOR, "run-started", "run", "started");
        }
        String first = lines().get(0);

        try (AuditLog log = open()) {
            log.record(Agent.ORCHESTRATOR, "run-resumed", "run", "resumed");
        }

        assertThat(lines()).hasSize(2).startsWith(first);
        assertThat(parse(lines().get(1)).get("action").asString()).isEqualTo("run-resumed");
    }

    @Test
    void eventCutByACrashLeavesTheNextOneOnItsOwnLine() throws IOException {
        Files.writeString(file(), "{\"timestamp\":\"2026-11", UTF_8);

        try (AuditLog log = open()) {
            log.record(Agent.ORCHESTRATOR, "run-resumed", "run", "resumed");
        }

        List<String> lines = lines();
        assertThat(lines).hasSize(2).startsWith("{\"timestamp\":\"2026-11");
        assertThat(parse(lines.get(1)).get("action").asString()).isEqualTo("run-resumed");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Verdict", "run started", "run_started", "-run", "run-", "run--started", "1st-run"})
    void actionMustBeInKebabCase(String action) {
        try (AuditLog log = open()) {
            assertThatIllegalArgumentException().isThrownBy(() -> log.record(Agent.JUDGE, action, "", ""))
                    .withMessageContaining("kebab-case");
        }
    }

    @Test
    void recordingAfterCloseFails() {
        AuditLog log = open();
        log.close();
        log.close();

        assertThatIllegalStateException().isThrownBy(() -> log.record(Agent.JUDGE, "verdict", "", ""));
    }

    @Test
    void concurrentEventsNeverMix() throws Exception {
        int threads = 8;
        int eventsPerThread = 250;
        try (AuditLog log = open()) {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int thread = 0; thread < threads; thread++) {
                    String input = "thread " + thread + " " + "x".repeat(200);
                    tasks.add(executor.submit(() -> {
                        for (int event = 0; event < eventsPerThread; event++) {
                            log.record(Agent.GUARDIAN, "run-tests", input, "event " + event);
                        }
                    }));
                }
                for (Future<?> task : tasks) {
                    task.get();
                }
            } finally {
                executor.shutdown();
            }
        }

        List<String> lines = lines();
        assertThat(lines).hasSize(threads * eventsPerThread);
        assertThat(lines).allSatisfy(line -> assertThat(parse(line).get("agent").asString()).isEqualTo("guardian"));
    }

    @Test
    void folderThatDoesNotExistIsReported() {
        Path missing = tempDir.resolve("missing").resolve("audit.jsonl");

        assertThatExceptionOfType(AuditLogException.class)
                .isThrownBy(() -> AuditLog.open(missing, CLOCK, NO_PATHS))
                .withMessage("Cannot open the audit log " + missing + ": no such file or folder");
    }
}
