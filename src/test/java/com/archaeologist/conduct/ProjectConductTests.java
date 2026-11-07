package com.archaeologist.conduct;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.archaeologist.conduct.SensitiveDataScanner.Finding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Conduct of the project: none of its files (code, tests, docs, the backlog) holds local paths, user or
 * machine names, e-mail addresses, secrets or hidden characters. Runs on every build, so a leak fails the
 * build before it is pushed.
 *
 * <p>Scans what Git would publish: tracked files, plus new files that are not ignored yet. Docs and tests
 * may use addresses at reserved example domains, such as {@code example.com}.
 */
class ProjectConductTests {

    /** Files that are not text: their bytes would only produce noise. */
    private static final Set<String> BINARY = Set.of("jar", "class", "zip", "gz", "png", "jpg", "jpeg", "gif", "ico",
            "pdf");

    @Test
    void projectFilesHoldNothingPrivate() throws IOException, InterruptedException {
        List<String> files = projectFiles();
        SensitiveDataScanner scanner = SensitiveDataScanner.builder()
                .allowReservedEmails()
                .path("the real home folder", Path.of(System.getProperty("user.home")))
                .path("the project folder", Path.of("").toAbsolutePath())
                .value("the machine name", System.getenv("COMPUTERNAME"))
                .value("the machine name", System.getenv("HOSTNAME"))
                .build();

        List<Finding> findings = new ArrayList<>();
        for (String name : files) {
            Path file = Path.of(name);
            findings.addAll(scanner.scan(name + " (file name)", name));
            if (Files.isRegularFile(file) && !BINARY.contains(extension(name))) {
                findings.addAll(scanner.scan(name, new String(Files.readAllBytes(file), UTF_8)));
            }
        }

        assertThat(files).as("files scanned").contains("pom.xml", "README.md", "docs/FEATURES.md");
        assertThat(findings).as("private data in the project's files").isEmpty();
    }

    /** What Git would publish, relative to the project folder; the test is skipped outside a Git work tree. */
    private static List<String> projectFiles() throws IOException, InterruptedException {
        Process git;
        try {
            git = new ProcessBuilder("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ex) {
            assumeTrue(false, "Git is not installed: the project scan needs it");
            throw ex;
        }
        byte[] output = git.getInputStream().readAllBytes();
        boolean finished = git.waitFor(1, TimeUnit.MINUTES);
        assumeTrue(finished && git.exitValue() == 0, "not a Git work tree (a source archive?): nothing to scan");
        // -z ends each name with a NUL character, so names with spaces or quotes come out as they are
        return Arrays.stream(new String(output, UTF_8).split(String.valueOf((char) 0)))
                .filter(name -> !name.isEmpty()).toList();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
