package com.archaeologist.sample;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sample repository carries every signal promised in docs/FEATURES.md (FND-07), and nothing of the user's. */
class SampleRepositoryTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    private static final String ORDER_MANAGER = "src/main/java/com/acme/shop/OrderManager.java";
    private static final String TAX_CALCULATOR = "src/main/java/com/acme/shop/TaxCalculator.java";
    private static final String MESSAGES = "src/main/java/com/acme/shop/Messages.java";
    private static final String REPORT = "src/main/java/com/acme/shop/ReportGenerator.java";
    private static final String DATE_UTILS = "src/main/java/com/acme/shop/common/DateUtils.java";

    private static final Pattern FIX = Pattern.compile("(?i)\\b(fix|bugfix|hotfix|corrige|erro)\\b");
    private static final Pattern RESERVED_EMAIL = Pattern.compile(
            "[^@]+@((.*\\.)?example\\.(com|org|net)|.+\\.(example|invalid|test|localhost))");

    @TempDir
    static Path tempDir;

    private static Path repo;
    private static IsolatedGit git;

    @BeforeAll
    static void createOnce() {
        repo = tempDir.resolve("legacy-shop");
        SampleRepository.create(repo, TODAY);
        git = new IsolatedGit();
    }

    @AfterAll
    static void closeGit() {
        git.close();
    }

    private static String git(String... args) {
        return git.run(repo, null, args);
    }

    private static List<String> lines(String text) {
        return text.lines().filter(line -> !line.isBlank()).toList();
    }

    /** For each file, how many commits changed it. */
    private static Map<String, Long> changesPerFile(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of("log", "--format="));
        args.addAll(List.of(extraArgs));
        args.add("--name-only");
        return lines(git(args.toArray(String[]::new))).stream()
                .collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
    }

    @Test
    void historyIsCompleteAndTheWorkingCopyClean() {
        assertThat(git("rev-list", "--count", "HEAD").strip()).isEqualTo("50");
        assertThat(git("branch", "--show-current").strip()).isEqualTo("main");
        assertThat(git("status", "--porcelain")).isEmpty();
    }

    @Test
    void sameDayGivesTheSameCommits() {
        Path again = tempDir.resolve("again");
        Path later = tempDir.resolve("later");
        SampleRepository.create(again, TODAY);
        SampleRepository.create(later, TODAY.plusDays(1));

        String head = git("rev-parse", "HEAD");
        assertThat(git.run(again, null, "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(git.run(later, null, "rev-parse", "HEAD")).isNotEqualTo(head);
    }

    @Test
    void historyCoversMoreThanFiveYearsAndEndsTwoWeeksBeforeToday() {
        List<Long> times = lines(git("log", "--reverse", "--format=%at")).stream().map(Long::valueOf).toList();
        LocalDate first = Instant.ofEpochSecond(times.get(0)).atOffset(ZoneOffset.ofHours(-3)).toLocalDate();
        LocalDate last = Instant.ofEpochSecond(times.get(times.size() - 1)).atOffset(ZoneOffset.ofHours(-3)).toLocalDate();

        assertThat(first).isBefore(TODAY.minusYears(6));
        assertThat(last).isEqualTo(TODAY.minusDays(14));
        assertThat(times).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void authorsAreFictionalPeopleAtReservedDomains() {
        List<String> emails = lines(git("log", "--format=%ae%n%ce"));
        List<String> identities = lines(git("log", "--format=%an <%ae>")).stream().distinct().toList();
        List<String> people = lines(git("log", "--use-mailmap", "--format=%aN <%aE>")).stream().distinct().toList();

        assertThat(emails).allMatch(email -> RESERVED_EMAIL.matcher(email).matches());
        assertThat(identities).hasSize(6);
        // One person changed e-mail: the .mailmap merges both identities
        assertThat(people).hasSize(5);
    }

    @Test
    void godClassIsTheFileChangedMostByEveryoneWithMostOfTheFixes() {
        Map<String, Long> changes = changesPerFile();
        String mostChanged = changes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        List<String> authors = lines(git("log", "--use-mailmap", "--format=%aE", "--", ORDER_MANAGER)).stream()
                .distinct().toList();
        long fixes = lines(git("log", "--format=%s", "--", ORDER_MANAGER)).stream()
                .filter(subject -> FIX.matcher(subject).find()).count();

        assertThat(mostChanged).isEqualTo(ORDER_MANAGER);
        assertThat(changes.get(ORDER_MANAGER)).isGreaterThanOrEqualTo(3 * changes.get(MESSAGES));
        assertThat(authors).hasSize(5);
        assertThat(fixes).isGreaterThanOrEqualTo(15);
    }

    @Test
    void complexClassIsStableAndSimpleClassChangesOften() {
        Map<String, Long> changes = changesPerFile();

        // Created, fixed once, and touched by the two bulk commits
        assertThat(changes.get(TAX_CALCULATOR)).isEqualTo(4);
        assertThat(changes.get(MESSAGES)).isGreaterThanOrEqualTo(8);
    }

    @Test
    void renamedFileKeepsItsHistory() {
        List<String> followed = lines(git("log", "--follow", "--format=%s", "--", DATE_UTILS));
        List<String> sinceRename = lines(git("log", "--format=%s", "--", DATE_UTILS));

        assertThat(followed.size()).isGreaterThan(sinceRename.size());
        assertThat(followed).last().isEqualTo("Initial import of the order system");
    }

    @Test
    void twoBulkCommitsTouchEverySourceFile() {
        String log = git("log", "--format=@%s", "--name-only");
        List<String> bulk = new ArrayList<>();
        for (String commit : log.split("@")) {
            List<String> parts = lines(commit);
            if (!parts.isEmpty() && parts.size() - 1 >= 11 && !parts.get(0).startsWith("Initial import")) {
                bulk.add(parts.get(0));
            }
        }

        assertThat(bulk).containsExactlyInAnyOrder("Add license header to all source files",
                "Reorganize packages (util is now common) and update the company name");
    }

    @Test
    void commitMessagesMixLanguagesIssueKeysAndVagueWording() {
        List<String> subjects = lines(git("log", "--format=%s"));

        assertThat(subjects).anyMatch(subject -> subject.startsWith("fix"))
                .anyMatch(subject -> subject.startsWith("corrige"))
                .anyMatch(subject -> subject.startsWith("erro"))
                .anyMatch(subject -> subject.startsWith("hotfix"))
                .anyMatch(subject -> subject.matches(".*\\bSHOP-\\d+.*"))
                .contains("ajustes", "wip", "misc changes", "melhorias");
        assertThat(subjects.stream().filter(subject -> FIX.matcher(subject).find()).count()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void messagesFileIsInIso88591AsOldProjectsOftenAre() throws IOException {
        byte[] bytes = Files.readAllBytes(repo.resolve(MESSAGES));

        assertThatExceptionOfType(CharacterCodingException.class).isThrownBy(() -> UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)));
        assertThat(new String(bytes, ISO_8859_1)).contains("Pedido não encontrado", "Cupom inválido");
    }

    @Test
    void reportFileHasWindowsLineEndings() throws IOException {
        String content = Files.readString(repo.resolve(REPORT), UTF_8);

        assertThat(content).contains("\r\n");
        assertThat(content.replace("\r\n", "")).doesNotContain("\n");
    }

    @Test
    void codeHasTheTraitsOfLegacyJava() throws IOException {
        String orderManager = Files.readString(repo.resolve(ORDER_MANAGER), UTF_8);

        assertThat(orderManager).contains("import java.sql.Connection;", "new Date()", "new Random()",
                "public static synchronized OrderManager getInstance()", "new Vector()", "new Long(");
        assertThat(Files.readString(repo.resolve(REPORT), UTF_8)).contains("getMethod(", "new FileWriter(");
        assertThat(Files.readString(repo.resolve("src/main/java/com/acme/shop/generated/StatusCodes.java"), UTF_8))
                .contains("DO NOT EDIT");
        assertThat(Files.readString(repo.resolve("src/main/java/com/acme/shop/PaymentService.java"), UTF_8))
                .contains("public interface PaymentService");
        assertThat(repo.resolve("src/test/java/com/acme/shop/TaxCalculatorTest.java")).exists();
        assertThat(Files.readString(repo.resolve("pom.xml"), UTF_8)).contains("<source>1.6</source>");
        assertThat(repo.resolve(".mailmap")).exists();
    }

    @Test
    void sourcesCompile() throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assumeTrue(javac != null, "this Java has no compiler");
        List<String> sources;
        try (Stream<Path> files = Files.walk(repo.resolve("src/main/java"))) {
            sources = files.filter(file -> file.toString().endsWith(".java")).map(Path::toString).toList();
        }
        List<String> args = new ArrayList<>(Arrays.asList("-nowarn", "-encoding", "ISO-8859-1", "--release", "8",
                "-d", tempDir.resolve("classes").toString()));
        args.addAll(sources);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = javac.run(null, null, errors, args.toArray(String[]::new));

        assertThat(result).as("javac: %s", errors.toString(UTF_8)).isZero();
    }

    @Test
    void noHooksAreInstalled() throws IOException {
        Path hooks = repo.resolve(".git").resolve("hooks");

        if (Files.isDirectory(hooks)) {
            try (Stream<Path> files = Files.list(hooks)) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void emptyFolderIsAccepted() throws IOException {
        Path empty = Files.createDirectory(tempDir.resolve("empty"));

        assertThat(SampleRepository.create(empty, TODAY).commits()).isEqualTo(50);
    }

    @Test
    void folderWithFilesIsRefused() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("busy"));
        Files.writeString(folder.resolve("notes.txt"), "mine");

        assertThatExceptionOfType(SampleException.class).isThrownBy(() -> SampleRepository.create(folder, TODAY))
                .withMessage("Not an empty folder: " + folder + " (the sample needs a new or empty one)");
        assertThat(folder.resolve("notes.txt")).hasContent("mine");
        assertThat(folder.resolve(".git")).doesNotExist();
    }

    @Test
    void fileInTheWayIsRefused() throws IOException {
        Path file = Files.writeString(tempDir.resolve("a-file"), "x");

        assertThatExceptionOfType(SampleException.class).isThrownBy(() -> SampleRepository.create(file, TODAY))
                .withMessage("Not a folder: " + file);
    }
}
