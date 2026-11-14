package com.archaeologist.git;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A repository that tries to make Git run its programs, as a repository downloaded from a stranger could.
 * Its own configuration ({@code .git/config}) names a program for each thing Git can start by itself: a
 * signature verifier (its newest commit carries a signature), a text converter and an external diff for
 * {@code .java} files, a pager, a file system monitor and hooks. The programs are shell scripts in its
 * {@code .git} folder, as such a repository would bring them.
 *
 * <p>Each program, when it runs, adds a file named after it to a markers folder: a test can tell which ones
 * ran. Plain Git runs some of them on {@code git log} (see the Excavator's tests); the agent must run none.
 */
public final class HostileRepository {

    /** The programs, by marker name. */
    public static final String SIGNATURE_VERIFIER = "signature-verifier";
    public static final String TEXT_CONVERTER = "text-converter";
    public static final String EXTERNAL_DIFF = "external-diff";
    public static final String PAGER = "pager";
    public static final String FSMONITOR = "fsmonitor";
    public static final String HOOK = "hook";

    /** Commits in its history, all made in the last few days by the given author. */
    public static final int COMMITS = 2;

    private static final List<String> HOOKS = List.of("pre-commit", "post-commit", "post-checkout", "post-merge",
            "post-rewrite", "pre-push", "reference-transaction", "post-index-change", "pre-auto-gc");

    private HostileRepository() {
    }

    /**
     * Creates the repository in {@code folder}, which must not exist or be empty.
     *
     * @param markers where the programs leave their markers; created if needed
     */
    public static void create(Path folder, Path markers, String authorName, String authorEmail) {
        try {
            Files.createDirectories(folder);
            Files.createDirectories(markers);
            try (IsolatedGit git = new IsolatedGit()) {
                git.run(folder, "init", "--quiet", "--template=");
                git.run(folder, history(authorName, authorEmail), "fast-import", "--quiet", "--done");
                git.run(folder, "symbolic-ref", "HEAD", "refs/heads/main");
                signNewestCommit(git, folder);
                git.run(folder, "reset", "--hard", "--quiet");
            }
            Path programs = folder.resolve(".git").resolve("hostile");
            writePrograms(programs, markers);
            Files.writeString(folder.resolve(".git").resolve("config"), configuration(programs), UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The markers left so far: the names of the programs that ran. */
    public static List<String> markers(Path markers) {
        try (var files = Files.list(markers)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static byte[] history(String authorName, String authorEmail) {
        long now = Instant.now().getEpochSecond();
        StringBuilder stream = new StringBuilder();
        commit(stream, authorName, authorEmail, now - Duration.ofDays(2).toSeconds(), "Add invoices",
                "public class Invoice {\n    int total;\n}\n");
        commit(stream, authorName, authorEmail, now - Duration.ofDays(1).toSeconds(), "fix: invoice total",
                "public class Invoice {\n    long total;\n}\n");
        stream.append("done\n");
        return stream.toString().getBytes(UTF_8);
    }

    private static void commit(StringBuilder stream, String name, String email, long time, String message,
            String invoice) {
        String identity = name + " <" + email + "> " + time + " +0000\n";
        stream.append("commit refs/heads/main\n").append("author ").append(identity).append("committer ")
                .append(identity);
        data(stream, message + "\n");
        // Java files go through the repository's own diff driver
        stream.append("M 100644 inline .gitattributes\n");
        data(stream, "*.java diff=canary\n");
        stream.append("M 100644 inline src/Invoice.java\n");
        data(stream, invoice);
        stream.append('\n');
    }

    private static void data(StringBuilder stream, String content) {
        stream.append("data ").append(content.getBytes(UTF_8).length).append('\n').append(content).append('\n');
    }

    /** Git shows a signed commit's signature through the program in {@code gpg.program}. */
    private static void signNewestCommit(IsolatedGit git, Path folder) {
        String commit = git.run(folder, "cat-file", "commit", "HEAD");
        int headersEnd = commit.indexOf("\n\n");
        String signed = commit.substring(0, headersEnd) + "\ngpgsig " + "-".repeat(5) + "BEGIN PGP SIGNATURE"
                + "-".repeat(5) + "\n \n iQEzBAABCAAdFiEE\n " + "-".repeat(5) + "END PGP SIGNATURE" + "-".repeat(5)
                + commit.substring(headersEnd);
        String hash = git.run(folder, signed.getBytes(UTF_8), "hash-object", "-t", "commit", "-w", "--stdin").strip();
        git.run(folder, "update-ref", "HEAD", hash);
    }

    private static void writePrograms(Path programs, Path markers) throws IOException {
        Files.createDirectories(programs.resolve("hooks"));
        // The verifier reads what Git sends it; the converter must print the file it converts
        script(programs.resolve(SIGNATURE_VERIFIER + ".sh"), markers, SIGNATURE_VERIFIER, "cat > /dev/null\n");
        script(programs.resolve(TEXT_CONVERTER + ".sh"), markers, TEXT_CONVERTER, "cat \"$1\"\n");
        script(programs.resolve(EXTERNAL_DIFF + ".sh"), markers, EXTERNAL_DIFF, "");
        script(programs.resolve(PAGER + ".sh"), markers, PAGER, "cat\n");
        script(programs.resolve(FSMONITOR + ".sh"), markers, FSMONITOR, "");
        for (String hook : HOOKS) {
            script(programs.resolve("hooks").resolve(hook), markers, HOOK, "");
        }
    }

    private static void script(Path file, Path markers, String marker, String rest) throws IOException {
        String target = slashes(markers.resolve(marker));
        Files.writeString(file, "#!/bin/sh\necho ran >> '" + target + "'\n" + rest, UTF_8);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private static String configuration(Path programs) {
        String folder = slashes(programs);
        return "[log]\n\tshowSignature = true\n"
                + "[gpg]\n\tprogram = \"" + folder + "/" + SIGNATURE_VERIFIER + ".sh\"\n"
                + "[diff]\n\texternal = \"" + folder + "/" + EXTERNAL_DIFF + ".sh\"\n"
                + "[diff \"canary\"]\n\ttextconv = \"" + folder + "/" + TEXT_CONVERTER + ".sh\"\n"
                + "[core]\n\tpager = \"" + folder + "/" + PAGER + ".sh\"\n"
                + "\tfsmonitor = \"" + folder + "/" + FSMONITOR + ".sh\"\n"
                + "\thooksPath = \"" + folder + "/hooks\"\n";
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
