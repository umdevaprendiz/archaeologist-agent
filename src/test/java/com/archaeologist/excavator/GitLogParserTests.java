package com.archaeologist.excavator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.archaeologist.git.GitException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The output of {@code git log -z --numstat}, written by hand: every shape Git produces, and broken ones. */
class GitLogParserTests {

    private static final String HASH_1 = "1111111111111111111111111111111111111111";
    private static final String HASH_2 = "2222222222222222222222222222222222222222";

    /** One commit header, as {@link GitLogParser#FORMAT} makes Git write it. */
    private static String header(String hash, long time, String name, String email, String message) {
        return "\u0001" + hash + "\0" + time + "\0" + name + "\0" + email + "\0" + message + "\0";
    }

    private static List<Commit> parse(String output) throws IOException {
        return parse(new ByteArrayInputStream(output.getBytes(UTF_8)));
    }

    private static List<Commit> parse(InputStream output) throws IOException {
        return new GitLogParser(output).parse();
    }

    @Test
    void readsEachCommitWithItsFiles() throws IOException {
        String output = header(HASH_2, 1_700_000_100, "Ana", "ana@example.com", "fix: total\n\nSHOP-7\n")
                + "\n3\t1\tsrc/Order.java\0" + "0\t2\tREADME.txt\0"
                + header(HASH_1, 1_700_000_000, "Bruno", "bruno@example.com", "Initial import\n")
                + "\n10\t0\tsrc/Order.java\0";

        List<Commit> commits = parse(output);

        assertThat(commits).hasSize(2);
        Commit newest = commits.get(0);
        assertThat(newest.hash()).isEqualTo(HASH_2);
        assertThat(newest.date()).isEqualTo(Instant.ofEpochSecond(1_700_000_100));
        assertThat(newest.message()).isEqualTo("fix: total\n\nSHOP-7");
        assertThat(newest.files()).containsExactly(new FileChange("src/Order.java", null, 3, 1, false),
                new FileChange("README.txt", null, 0, 2, false));
        assertThat(commits.get(1).files()).containsExactly(new FileChange("src/Order.java", null, 10, 0, false));
        assertThat(newest.authorId()).isNotEqualTo(commits.get(1).authorId());
    }

    @Test
    void binaryFilesHaveNoLineCounts() throws IOException {
        List<Commit> commits = parse(header(HASH_1, 1, "Ana", "ana@example.com", "logo\n") + "\n-\t-\tlogo.png\0");

        assertThat(commits.get(0).files()).containsExactly(new FileChange("logo.png", null, 0, 0, true));
    }

    @Test
    void renamedFilesKeepTheirOldPath() throws IOException {
        List<Commit> commits = parse(header(HASH_1, 1, "Ana", "ana@example.com", "move\n")
                + "\n1\t1\t\0util/DateHelper.java\0common/DateUtils.java\0" + "2\t0\tsrc/Order.java\0");

        assertThat(commits.get(0).files()).containsExactly(
                new FileChange("common/DateUtils.java", "util/DateHelper.java", 1, 1, false),
                new FileChange("src/Order.java", null, 2, 0, false));
        assertThat(commits.get(0).files().get(0).renamed()).isTrue();
    }

    @Test
    void commitsWithoutFilesAreKept() throws IOException {
        List<Commit> commits = parse(header(HASH_2, 2, "Ana", "ana@example.com", "empty\n")
                + header(HASH_1, 1, "Ana", "ana@example.com", "first\n") + "\n1\t0\ta.txt\0");

        assertThat(commits).extracting(Commit::message).containsExactly("empty", "first");
        assertThat(commits.get(0).files()).isEmpty();
    }

    /** Paths come as written: spaces, accents, tabs and a leading mark are part of the name. */
    @Test
    void pathsAreReadVerbatim() throws IOException {
        List<Commit> commits = parse(header(HASH_1, 1, "Ana", "ana@example.com", "names\n")
                + "\n1\t0\tdocs/relatório final.txt\0" + "1\t0\twith\ttab.txt\0" + "1\t1\t\0\u0001old\0\u0001new\0");

        assertThat(commits.get(0).files()).extracting(FileChange::path)
                .containsExactly("docs/relatório final.txt", "with\ttab.txt", "\u0001new");
    }

    /** A message can hold anything but a NUL, the mark included. */
    @Test
    void messagesAreReadVerbatim() throws IOException {
        String message = "corrige cálculo\n\u0001" + HASH_2 + "\nnot a commit\n";

        List<Commit> commits = parse(header(HASH_1, 1, "Ana", "ana@example.com", message) + "\n1\t0\ta.txt\0");

        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).message()).isEqualTo(message.stripTrailing());
    }

    @Test
    void sameEmailIsTheSameAuthorWhateverItsCaseOrName() throws IOException {
        List<Commit> commits = parse(header(HASH_2, 2, "Ana Souza", "Ana@Example.com", "b\n")
                + header(HASH_1, 1, "Ana", "ana@example.com", "a\n"));

        assertThat(commits.get(0).authorId()).isEqualTo(commits.get(1).authorId());
    }

    @Test
    void authorsWithoutEmailAreToldApartByName() throws IOException {
        List<Commit> commits = parse(header(HASH_2, 3, "Ana", "", "c\n") + header(HASH_1, 2, "Bruno", "", "b\n")
                + header("3".repeat(40), 1, "Ana", "", "a\n"));

        assertThat(commits.get(0).authorId()).isEqualTo(commits.get(2).authorId())
                .isNotEqualTo(commits.get(1).authorId());
    }

    /** Names and e-mail addresses are used to count authors, then dropped. */
    @Test
    void commitsKeepNoNameOrEmail() throws IOException {
        List<Commit> commits = parse(header(HASH_1, 1, "Ana Souza", "ana.souza@example.com", "a\n")
                + "\n1\t0\ta.txt\0");

        assertThat(commits.toString()).doesNotContain("Ana", "Souza", "example.com");
    }

    @Test
    void sha256HashesAreAccepted() throws IOException {
        String hash = "ab".repeat(32);

        assertThat(parse(header(hash, 1, "Ana", "ana@example.com", "a\n"))).extracting(Commit::hash).containsExactly(hash);
    }

    @Test
    void emptyOutputIsNoCommits() throws IOException {
        assertThat(parse("")).isEmpty();
    }

    @Test
    void readsOutputLongerThanItsBuffer() throws IOException {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < 5_000; index++) {
            output.append(header(String.format("%040x", index), index, "Author " + (index % 7),
                    "author" + (index % 7) + "@example.com", "change " + index + "\n" + "x".repeat(index % 50)));
            output.append("\n").append(index).append("\t1\tsrc/File").append(index % 13).append(".java\0");
        }

        List<Commit> commits = parse(output.toString());

        assertThat(commits).hasSize(5_000);
        assertThat(commits.get(4_999).files()).containsExactly(new FileChange("src/File7.java", null, 4_999, 1, false));
        assertThat(commits.stream().mapToInt(Commit::authorId).distinct().count()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a commit\0",
            "\u0001not-a-hash\0" + "1\0Ana\0ana@example.com\0a\n\0",
            "\u0001" + HASH_1 + "\0yesterday\0Ana\0ana@example.com\0a\n\0",
            "\u0001" + HASH_1 + "\0001\0Ana\0",
            "\u0001" + HASH_1 + "\0001\0Ana\0ana@example.com\0a\n\0\nthree\tlines\tfile.txt\0",
            "\u0001" + HASH_1 + "\0001\0Ana\0ana@example.com\0a\n\0\n-\t3\tfile.txt\0",
            "\u0001" + HASH_1 + "\0001\0Ana\0ana@example.com\0a\n\0\n1\t1\t\0old.txt\0",
            "\u0001" + HASH_1 + "\0001\0Ana\0ana@example.com\0a\n\0\n99999999999\t1\tfile.txt\0"})
    void unexpectedOutputIsRejectedWithoutQuotingIt(String output) {
        assertThatExceptionOfType(GitException.class).isThrownBy(() -> parse(output))
                .withMessageStartingWith("Unexpected output from 'git log': expected ")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Ana", "file.txt", "yesterday"));
    }
}
