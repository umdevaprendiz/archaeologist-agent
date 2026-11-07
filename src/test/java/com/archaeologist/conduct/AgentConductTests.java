package com.archaeologist.conduct;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.archaeologist.ArchaeologistApplication;
import com.archaeologist.conduct.SensitiveDataScanner.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Conduct of the agent: whatever it does, it never shows the user's private data, and it writes only to
 * its output folder.
 *
 * <p>Runs the real command line in its own process, as a user would, with a fake home folder full of
 * planted credentials (Maven, Gradle, SSH, AWS, Git, Docker, npm), fake secrets in the environment and a
 * fake user name. Then it scans everything the agent printed or wrote for those values, for local paths,
 * e-mail addresses, secrets and hidden characters, and checks that nothing outside the output folders
 * changed.
 *
 * <p>Every planted value is fake and random. <b>A feature that adds a command, an option or a new kind of
 * output adds a scenario here.</b>
 */
class AgentConductTests {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String USER = "canary-user-" + hex(3);

    /** Planted values, by what they are. */
    private static final Map<String, String> PLANTED = new LinkedHashMap<>();

    private static final String MAVEN_PASSWORD = plant("the planted Maven password", "CANARY-MAVEN-" + hex(8));
    private static final String GRADLE_PASSWORD = plant("the planted Gradle password", "CANARY-GRADLE-" + hex(8));
    private static final String SSH_KEY = plant("the planted SSH key", "CANARY-SSH-" + hex(16));
    private static final String AWS_KEY_ID = plant("the planted AWS key id", "AKIA" + hex(8).toUpperCase(Locale.ROOT));
    private static final String AWS_SECRET = plant("the planted AWS secret", "CANARY-AWS-" + hex(12));
    private static final String GIT_PASSWORD = plant("the planted Git password", "CANARY-GIT-" + hex(8));
    private static final String GIT_EMAIL = plant("the planted Git e-mail", USER + "@mail.example.invalid");
    private static final String DOCKER_AUTH = plant("the planted Docker credential", "CANARY-DOCKER-" + hex(8));
    private static final String NPM_TOKEN = plant("the planted npm token", "CANARY-NPM-" + hex(8));
    private static final String NETRC_PASSWORD = plant("the planted netrc password", "CANARY-NETRC-" + hex(8));
    private static final String GITHUB_TOKEN = plant("the GitHub token in the environment", "ghp_" + hex(18));
    private static final String DATABASE_PASSWORD = plant("the database password in the environment",
            "CANARY-DB-" + hex(8));
    private static final String SETTING_SECRET = plant("the secret in an unknown setting", "CANARY-SETTING-" + hex(8));

    /** What one run of the agent should do, so a scan of its output is never vacuous. */
    private record Scenario(String name, List<String> args, Map<String, String> environment, int exitCode,
            String expectedText, boolean startsRun) {
    }

    private record Result(Scenario scenario, Path workspace, int exitCode, String stdout, String stderr) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("version", List.of("--version"), Map.of(), 0, "Code Archaeologist", false),
            new Scenario("help", List.of("--help"), Map.of(), 0, "Usage: archaeologist", false),
            new Scenario("hotspots", List.of("hotspots", "legacy-repo"), Map.of(), 1, "not implemented yet", true),
            new Scenario("run", List.of("run", "legacy-repo"), Map.of(), 1, "not implemented yet", true),
            new Scenario("scout", List.of("scout"), Map.of(), 1, "not implemented yet", false),
            new Scenario("usage-error", List.of("hotspot", "legacy-repo"), Map.of(), 2, "Unmatched argument", false),
            new Scenario("missing-repository", List.of("hotspots", "missing-repo"), Map.of(), 3,
                    "Repository not found", false),
            new Scenario("not-a-repository", List.of("run", "not-a-repo"), Map.of(), 3, "Not a Git repository", false),
            new Scenario("unknown-setting", List.of("hotspots", "legacy-repo"),
                    Map.of("ARCHAEOLOGIST_OLLAMA_TOKEN", SETTING_SECRET), 4, "is not a known setting", false),
            new Scenario("blocked-output-folder", List.of("hotspots", "legacy-repo"),
                    Map.of("ARCHAEOLOGIST_OUTPUT_FOLDER", "blocked"), 1, "a file with that name is in the way", false));

    /** Each scenario's output folder, relative to the fake home: the only place the agent may write. */
    private static final Pattern OUTPUT_FOLDER = Pattern.compile("projects/[^/]+/archaeologist-output(/.*)?");

    @TempDir
    static Path sandbox;

    private static Path home;
    private static SensitiveDataScanner scanner;
    private static Map<String, String> homeBefore;
    private static final Map<String, Result> RESULTS = new LinkedHashMap<>();

    private static String plant(String what, String value) {
        PLANTED.put(what, value);
        return value;
    }

    private static String hex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    @BeforeAll
    static void runEveryScenario() throws Exception {
        home = Files.createDirectories(sandbox.resolve("home").resolve(USER));
        Path temp = Files.createDirectories(sandbox.resolve("tmp"));
        plantCredentials();
        for (Scenario scenario : SCENARIOS) {
            prepareWorkspace(home.resolve("projects").resolve(scenario.name()));
        }
        scanner = scannerOfEverythingPrivate();
        homeBefore = snapshot(home);

        Path argFile = classpathArgFile();
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Result>> runs = new ArrayList<>();
            for (Scenario scenario : SCENARIOS) {
                runs.add(executor.submit(() -> run(scenario, argFile, temp)));
            }
            for (Future<Result> run : runs) {
                Result result = run.get();
                RESULTS.put(result.scenario().name(), result);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    static Stream<String> scenarios() {
        return SCENARIOS.stream().map(Scenario::name);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void behavesAsTheScenarioExpects(String name) throws IOException {
        Result result = RESULTS.get(name);
        String console = "stdout:\n" + scanner.mask(result.stdout()) + "\nstderr:\n" + scanner.mask(result.stderr());

        assertThat(result.exitCode()).as("exit code of '%s'%n%s", name, console).isEqualTo(result.scenario().exitCode());
        assertThat(result.stdout() + result.stderr()).as("console of '%s'%n%s", name, console)
                .contains(result.scenario().expectedText());
        if (result.scenario().startsRun()) {
            assertThat(filesIn(outputFolder(result))).as("files written by '%s'", name)
                    .anyMatch(file -> file.endsWith("audit.jsonl"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void consoleShowsNothingPrivate(String name) {
        Result result = RESULTS.get(name);

        List<Finding> findings = new ArrayList<>(scanner.scan(name + " (stdout)", result.stdout()));
        findings.addAll(scanner.scan(name + " (stderr)", result.stderr()));

        assertThat(findings).as("private data in the console of '%s'", name).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void outputFolderShowsNothingPrivate(String name) throws IOException {
        Result result = RESULTS.get(name);

        List<Finding> findings = new ArrayList<>();
        for (Path file : filesIn(outputFolder(result))) {
            String relative = slashes(result.workspace().relativize(file));
            String content = new String(Files.readAllBytes(file), UTF_8);
            findings.addAll(scanner.scan(relative + " (file name)", relative));
            findings.addAll(scanner.scan(relative, content));
            // JSON doubles backslashes, so a Windows path in the audit log would not match as written
            String singleBackslashes = content.replace("\\\\", "\\");
            if (!singleBackslashes.equals(content)) {
                findings.addAll(scanner.scan(relative + " (backslashes undone)", singleBackslashes));
            }
        }

        assertThat(findings).as("private data in the files written by '%s'", name).isEmpty();
    }

    /** The repository and the planted credentials are untouched, and nothing appears next to them. */
    @Test
    void writesNothingOutsideItsOutputFolder() throws IOException {
        Map<String, String> after = snapshot(home);
        after.keySet().removeIf(path -> OUTPUT_FOLDER.matcher(path).matches());

        List<String> changes = new ArrayList<>();
        homeBefore.forEach((path, hash) -> {
            if (!after.containsKey(path)) {
                changes.add("removed " + path);
            } else if (!after.get(path).equals(hash)) {
                changes.add("changed " + path);
            }
        });
        after.keySet().stream().filter(path -> !homeBefore.containsKey(path)).forEach(path -> changes.add("added " + path));

        assertThat(changes).as("changes in the fake home folder, outside the output folders").isEmpty();
    }

    private static Result run(Scenario scenario, Path argFile, Path temp) throws IOException, InterruptedException {
        Path workspace = home.resolve("projects").resolve(scenario.name());
        Path stdout = sandbox.resolve(scenario.name() + ".stdout");
        Path stderr = sandbox.resolve(scenario.name() + ".stderr");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + argFile,
                "-Duser.home=" + home,
                "-Duser.name=" + USER,
                "-Djava.io.tmpdir=" + temp,
                "-Dpicocli.ansi=false",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-XX:-UsePerfData",
                "-XX:TieredStopAtLevel=1",
                ArchaeologistApplication.class.getName()));
        command.addAll(scenario.args());

        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        environment(builder.environment(), temp, scenario.environment());
        Process process = builder.start();
        int exitCode = process.waitFor(2, TimeUnit.MINUTES) ? process.exitValue() : -1;
        if (exitCode == -1) {
            process.destroyForcibly();
        }
        return new Result(scenario, workspace, exitCode, Files.readString(stdout, UTF_8), Files.readString(stderr, UTF_8));
    }

    /**
     * Only what the agent needs, plus planted secrets: nothing from the real environment reaches it
     * except the program search path and, on Windows, the system folder.
     */
    private static void environment(Map<String, String> environment, Path temp, Map<String, String> extra) {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : List.of("PATH", "SystemRoot", "windir", "ComSpec", "PATHEXT", "LANG", "LC_ALL")) {
            String value = System.getenv(name);
            if (value != null) {
                kept.put(name, value);
            }
        }
        environment.clear();
        environment.putAll(kept);
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("APPDATA", home.resolve("AppData").resolve("Roaming").toString());
        environment.put("LOCALAPPDATA", home.resolve("AppData").resolve("Local").toString());
        environment.put("USER", USER);
        environment.put("USERNAME", USER);
        environment.put("LOGNAME", USER);
        environment.put("TEMP", temp.toString());
        environment.put("TMP", temp.toString());
        environment.put("TMPDIR", temp.toString());
        environment.put("GITHUB_TOKEN", GITHUB_TOKEN);
        environment.put("DATABASE_PASSWORD", DATABASE_PASSWORD);
        environment.put("AWS_SECRET_ACCESS_KEY", AWS_SECRET);
        environment.putAll(extra);
    }

    private static void plantCredentials() throws IOException {
        String dashes = "-".repeat(5);
        write(".m2/settings.xml", "<settings><servers><server><id>corporate</id><username>" + USER
                + "</username><password>" + MAVEN_PASSWORD + "</password></server></servers></settings>\n");
        write(".gradle/gradle.properties", "systemProp.http.proxyUser=" + USER + "\nsystemProp.http.proxyPassword="
                + GRADLE_PASSWORD + "\n");
        write(".ssh/id_ed25519", dashes + "BEGIN OPENSSH PRIVATE KEY" + dashes + "\n" + SSH_KEY + "\n" + dashes
                + "END OPENSSH PRIVATE KEY" + dashes + "\n");
        write(".aws/credentials", "[default]\naws_access_key_id = " + AWS_KEY_ID + "\naws_secret_access_key = "
                + AWS_SECRET + "\n");
        write(".git-credentials", "https://" + USER + ":" + GIT_PASSWORD + "@git.example.invalid\n");
        write(".gitconfig", "[user]\n\tname = Canary User\n\temail = " + GIT_EMAIL + "\n");
        write(".docker/config.json", "{\"auths\":{\"registry.example.invalid\":{\"auth\":\"" + DOCKER_AUTH + "\"}}}\n");
        write(".npmrc", "//registry.example.invalid/:_authToken=" + NPM_TOKEN + "\n");
        write(".netrc", "machine git.example.invalid login " + USER + " password " + NETRC_PASSWORD + "\n");
    }

    private static void write(String relative, String content) throws IOException {
        Path file = home.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, UTF_8);
    }

    /** A Git repository, a folder that is not one, and a file where an output folder could go. */
    private static void prepareWorkspace(Path workspace) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(workspace.resolve("legacy-repo"));
        Process git = new ProcessBuilder("git", "init", "--quiet", repo.toString()).redirectErrorStream(true).start();
        String output = new String(git.getInputStream().readAllBytes(), UTF_8);
        assertThat(git.waitFor(1, TimeUnit.MINUTES)).isTrue();
        assertThat(git.exitValue()).as("git init: %s", output).isZero();
        Files.writeString(repo.resolve("Invoice.java"), "public class Invoice {}\n", UTF_8);
        Files.writeString(Files.createDirectories(workspace.resolve("not-a-repo")).resolve("README.txt"), "notes\n");
        Files.writeString(workspace.resolve("blocked"), "a file where an output folder could go\n");
    }

    private static SensitiveDataScanner scannerOfEverythingPrivate() {
        SensitiveDataScanner.Builder builder = SensitiveDataScanner.builder()
                .value("the fake user name", USER)
                .path("the fake home folder", home)
                .path("the test's temporary folder", sandbox)
                .path("the real home folder", Path.of(System.getProperty("user.home")))
                .path("the project folder", Path.of("").toAbsolutePath())
                .path("the JDK folder", Path.of(System.getProperty("java.home")))
                .value("the machine name", System.getenv("COMPUTERNAME"))
                .value("the machine name", System.getenv("HOSTNAME"));
        PLANTED.forEach(builder::value);
        return builder.build();
    }

    /** The test's own class path, in an argument file: on Windows a long command line would not fit. */
    private static Path classpathArgFile() throws IOException {
        String classpath = System.getProperty("java.class.path").replace('\\', '/');
        return Files.writeString(sandbox.resolve("classpath.args"), "-cp \"" + classpath + "\"\n", UTF_8);
    }

    private static Path outputFolder(Result result) {
        return result.workspace().resolve("archaeologist-output");
    }

    private static List<Path> filesIn(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** Every file and folder under {@code root}, by relative path, with a hash of each file's content. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root)).forEach(path -> snapshot.put(slashes(root.relativize(path)),
                    Files.isDirectory(path) ? "folder" : sha256(path)));
        }
        return snapshot;
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String slashes(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
