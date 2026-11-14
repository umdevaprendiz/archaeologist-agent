package com.archaeologist.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.archaeologist.git.GitException.Reason;
import com.archaeologist.sample.SampleRepository;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IsolatedGitTests {

    @TempDir
    Path tempDir;

    private IsolatedGit git;

    @BeforeEach
    void openGit() {
        git = new IsolatedGit();
    }

    @AfterEach
    void closeGit() {
        git.close();
    }

    private Path sample() {
        Path repo = tempDir.resolve("legacy-shop");
        SampleRepository.create(repo, LocalDate.of(2026, 11, 7));
        return repo;
    }

    @Test
    void runReturnsTheOutput() {
        Path repo = sample();

        assertThat(git.run(repo, "rev-list", "--count", "HEAD").strip()).isEqualTo("50");
    }

    @Test
    void testAnswersWithTheExitCode() throws IOException {
        Path repo = sample();
        Path empty = Files.createDirectory(tempDir.resolve("empty"));
        git.run(empty, "init", "--quiet", "--template=");

        assertThat(git.test(repo, "rev-parse", "--verify", "--quiet", "HEAD^{commit}")).isTrue();
        assertThat(git.test(empty, "rev-parse", "--verify", "--quiet", "HEAD^{commit}")).isFalse();
    }

    @Test
    void failuresNameTheCommandButNeverQuoteGitsOutput() {
        Path repo = sample();

        assertThatExceptionOfType(GitException.class)
                .isThrownBy(() -> git.run(repo, "log", "no-such-revision", "--"))
                .withMessage("Git failed: 'git log' exited with code 128")
                .satisfies(ex -> assertThat(ex.reason()).isEqualTo(Reason.FAILED));
    }

    /** A damaged .git inside another repository: Git must not read the repository around it. */
    @Test
    void neverLooksForARepositoryAboveTheFolder() throws IOException {
        Path repo = sample();
        Path nested = Files.createDirectories(repo.resolve("nested").resolve(".git")).getParent();

        assertThatExceptionOfType(GitException.class)
                .isThrownBy(() -> git.test(nested, "rev-parse", "--verify", "--quiet", "HEAD^{commit}"))
                .withMessage("Git finds no repository here: the .git folder is missing or damaged")
                .satisfies(ex -> assertThat(ex.reason()).isEqualTo(Reason.NOT_A_REPOSITORY));
    }

    @Test
    void readHandsTheOutputOverAsItComes() {
        Path repo = sample();

        long lines = git.read(repo, Duration.ofMinutes(1), output -> new String(output.readAllBytes()).lines().count(),
                "log", "--format=%H");

        assertThat(lines).isEqualTo(50);
    }

    @Test
    void outputTheReaderLeavesIsSkipped() {
        Path repo = sample();

        int first = git.read(repo, Duration.ofMinutes(1), output -> output.read(), "log", "--format=%H");

        assertThat(first).isNotNegative();
    }

    /** Git blocks once the pipe is full, since nothing reads it: the time limit stops it. */
    @Test
    void gitThatRunsPastItsTimeLimitIsStopped() {
        Path repo = sample();

        assertThatExceptionOfType(GitException.class).isThrownBy(() -> git.read(repo, Duration.ofSeconds(1), output -> {
            try {
                Thread.sleep(Duration.ofSeconds(3));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            return null;
        }, "log", "-p", "-U100000", "--no-ext-diff", "--no-textconv"))
                .withMessage("Git did not finish 'git log' within 1 second")
                .satisfies(ex -> assertThat(ex.reason()).isEqualTo(Reason.TIMED_OUT));
    }
}
