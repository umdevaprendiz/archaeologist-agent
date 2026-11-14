package com.archaeologist.excavator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.archaeologist.git.GitException;
import com.archaeologist.git.GitException.Reason;
import com.archaeologist.git.HostileRepository;
import com.archaeologist.git.IsolatedGit;
import com.archaeologist.sample.SampleRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads the sample repository, whose history is known commit by commit, and repositories made for a case. */
class GitLogReaderTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);
    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private static final String ORDER_MANAGER = "src/main/java/com/acme/shop/OrderManager.java";

    @TempDir
    static Path sharedDir;

    @TempDir
    Path tempDir;

    private static Path sample;
    private static IsolatedGit git;

    @BeforeAll
    static void createSample() {
        sample = sharedDir.resolve("legacy-shop");
        SampleRepository.create(sample, TODAY);
        git = new IsolatedGit();
    }

    @AfterAll
    static void closeGit() {
        git.close();
    }

    private static GitHistory read(Path repo, int years) {
        return new GitLogReader(years, CLOCK).read(repo);
    }

    private static List<String> lines(String text) {
        return text.lines().filter(line -> !line.isBlank()).toList();
    }

    @Test
    void readsTheLastFiveYearsNewestFirst() {
        GitHistory history = read(sample, 5);

        // 50 commits over six and a half years: the 12 made more than five years ago are left out
        assertThat(history.commits()).hasSize(38);
        assertThat(history.since()).isEqualTo(Instant.parse("2021-11-07T00:00:00Z"));
        assertThat(history.commits()).allSatisfy(commit -> assertThat(commit.date()).isAfterOrEqualTo(history.since()));
        assertThat(history.commits()).extracting(Commit::date).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(history.commits().get(0).message())
                .isEqualTo("corrige total quando cupom e frete expresso (SHOP-140)");
        assertThat(history.shallow()).isFalse();
    }

    @Test
    void sameCommitsAsGitLog() {
        GitHistory history = read(sample, 5);

        List<String> hashes = lines(git.run(sample, "log", "--format=%H", "--since=@" + (history.since().getEpochSecond() - 1)));

        assertThat(history.commits()).extracting(Commit::hash).containsExactlyElementsOf(hashes);
    }

    @Test
    void readsEveryCommitWhenThePeriodCoversTheWholeHistory() {
        GitHistory history = read(sample, 10);

        assertThat(history.commits()).hasSize(50);
        Commit first = history.commits().get(history.commits().size() - 1);
        assertThat(first.message()).isEqualTo("Initial import of the order system");
        // The first commit is compared with an empty tree: every line of every file is added
        assertThat(first.files()).isNotEmpty().allSatisfy(file -> {
            assertThat(file.linesAdded()).isPositive();
            assertThat(file.linesRemoved()).isZero();
        });
    }

    @Test
    void lineCountsMatchGitNumstat() {
        GitHistory history = read(sample, 10);

        int added = 0;
        int removed = 0;
        for (String line : lines(git.run(sample, "log", "--format=", "--numstat", "--no-renames", "--", ORDER_MANAGER))) {
            String[] counts = line.split("\t");
            added += Integer.parseInt(counts[0]);
            removed += Integer.parseInt(counts[1]);
        }
        int readAdded = history.commits().stream().flatMap(commit -> commit.files().stream())
                .filter(file -> file.path().equals(ORDER_MANAGER)).mapToInt(FileChange::linesAdded).sum();
        int readRemoved = history.commits().stream().flatMap(commit -> commit.files().stream())
                .filter(file -> file.path().equals(ORDER_MANAGER)).mapToInt(FileChange::linesRemoved).sum();

        assertThat(readAdded).isEqualTo(added).isPositive();
        assertThat(readRemoved).isEqualTo(removed).isPositive();
    }

    @Test
    void renamesAreDetected() {
        GitHistory history = read(sample, 5);

        Commit reorganize = history.commits().stream()
                .filter(commit -> commit.message().startsWith("Reorganize packages")).findFirst().orElseThrow();

        assertThat(reorganize.files()).filteredOn(FileChange::renamed).singleElement().satisfies(file -> {
            assertThat(file.previousPath()).isEqualTo("src/main/java/com/acme/shop/util/DateHelper.java");
            assertThat(file.path()).isEqualTo("src/main/java/com/acme/shop/common/DateUtils.java");
        });
    }

    @Test
    void messagesKeepTheirAccentsAndBody() {
        GitHistory history = read(sample, 5);

        assertThat(history.commits()).extracting(Commit::message)
                .contains("erro ao cancelar pedido já enviado", "corrige cálculo do frete para o Norte", "wip", "ajustes");
    }

    /** One person changed e-mail: the .mailmap makes both identities one author. */
    @Test
    void authorsAreCountedAfterTheMailmap() {
        GitHistory history = read(sample, 10);

        assertThat(history.authors()).isEqualTo(5);
    }

    @Test
    void historyKeepsNoNameOrEmail() {
        GitHistory history = read(sample, 10);

        assertThat(history.toString()).doesNotContain("Souza", "Bruno Lima", "Carla", "Rocha", "Martins", "example.");
    }

    @Test
    void binaryFilesAreRead() throws IOException {
        Path repo = Files.createDirectory(tempDir.resolve("binary"));
        git.run(repo, "init", "--quiet", "--template=");
        Files.write(repo.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13});
        Files.writeString(repo.resolve("README.txt"), "logo\n");
        commit(repo, "Add the logo", null);

        GitHistory history = read(repo, 5);

        assertThat(history.commits()).singleElement().satisfies(commit -> assertThat(commit.files())
                .containsExactlyInAnyOrder(new FileChange("logo.png", null, 0, 0, true),
                        new FileChange("README.txt", null, 1, 0, false)));
    }

    /** A commit rebased or imported today still counts at the date its author made the change. */
    @Test
    void periodIsMeasuredOnTheAuthorDate() throws IOException {
        Path repo = Files.createDirectory(tempDir.resolve("imported"));
        git.run(repo, "init", "--quiet", "--template=");
        Files.writeString(repo.resolve("Old.java"), "class Old {}\n");
        commit(repo, "Written long ago", "2015-03-01T10:00:00Z");
        Files.writeString(repo.resolve("New.java"), "class New {}\n");
        commit(repo, "Written lately", "2026-10-01T10:00:00Z");

        GitHistory history = read(repo, 5);

        assertThat(history.commits()).extracting(Commit::message).containsExactly("Written lately");
    }

    @Test
    void repositoryWithoutCommitsHasNoHistory() throws IOException {
        Path repo = Files.createDirectory(tempDir.resolve("new"));
        git.run(repo, "init", "--quiet", "--template=");

        GitHistory history = read(repo, 5);

        assertThat(history.commits()).isEmpty();
        assertThat(history.since()).isEqualTo(Instant.parse("2021-11-07T00:00:00Z"));
    }

    @Test
    void shallowClonesAreFlagged() {
        Path clone = tempDir.resolve("shallow");
        git.run(tempDir, "clone", "--quiet", "--depth", "1", sample.toUri().toString(), "shallow");

        GitHistory history = read(clone, 5);

        assertThat(history.shallow()).isTrue();
        assertThat(history.commits()).hasSize(1);
    }

    /** A damaged .git inside another repository: the history read is never the outer repository's. */
    @Test
    void damagedGitFolderIsNotARepository() throws IOException {
        Path nested = Files.createDirectories(tempDir.resolve("outer").resolve("nested").resolve(".git")).getParent();
        git.run(tempDir.resolve("outer"), "init", "--quiet", "--template=");

        assertThatExceptionOfType(GitException.class).isThrownBy(() -> read(nested, 5))
                .satisfies(ex -> assertThat(ex.reason()).isEqualTo(Reason.NOT_A_REPOSITORY));
    }

    @Test
    void readingChangesNothingInTheRepository() throws IOException {
        Map<String, String> before = snapshot(sample);

        read(sample, 10);

        assertThat(snapshot(sample)).isEqualTo(before);
    }

    /** The hostile repository's programs are real: plain Git runs them when it reads the history. */
    @Test
    void plainGitRunsTheProgramsOfAHostileRepository() throws IOException, InterruptedException {
        Path markers = tempDir.resolve("markers");
        Path repo = tempDir.resolve("hostile");
        HostileRepository.create(repo, markers, "Eva Martins", "eva@example.net");

        plainGit(repo, "log", "--numstat");
        plainGit(repo, "log", "-p");
        plainGit(repo, "log", "-p", "--ext-diff");

        assertThat(HostileRepository.markers(markers)).contains(HostileRepository.SIGNATURE_VERIFIER,
                HostileRepository.TEXT_CONVERTER, HostileRepository.EXTERNAL_DIFF);
    }

    @Test
    void readingAHostileRepositoryRunsNoneOfItsPrograms() {
        Path markers = tempDir.resolve("markers");
        Path repo = tempDir.resolve("hostile");
        HostileRepository.create(repo, markers, "Eva Martins", "eva@example.net");

        GitHistory history = new GitLogReader(5, Clock.systemUTC()).read(repo);

        assertThat(history.commits()).hasSize(HostileRepository.COMMITS);
        assertThat(history.commits().get(0).files()).containsExactly(new FileChange("src/Invoice.java", null, 1, 1, false));
        assertThat(HostileRepository.markers(markers)).as("programs of the repository that ran").isEmpty();
    }

    /** Commits what is in the working copy, by a fictional author; {@code date} is the author date, or now. */
    private void commit(Path repo, String message, String date) {
        List<String> args = new ArrayList<>(List.of("-c", "user.name=Eva Martins", "-c", "user.email=eva@example.net",
                "commit", "--quiet", "-m", message));
        if (date != null) {
            args.add("--date=" + date);
        }
        git.run(repo, "add", "--all");
        git.run(repo, args.toArray(String[]::new));
    }

    /** Git as a user would run it, without the agent's protections, but also without the user's configuration. */
    private void plainGit(Path repo, String... args) throws IOException, InterruptedException {
        Path home = Files.createDirectories(tempDir.resolve("plain-home"));
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("XDG_CONFIG_HOME", home.toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        process.getOutputStream().close();
        assertThat(process.waitFor(1, TimeUnit.MINUTES)).as("git %s finished", args[0]).isTrue();
    }

    /** Every file of the repository, its .git included, with a hash of its content. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).forEach(path -> snapshot.put(root.relativize(path).toString(), sha256(path)));
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
}
