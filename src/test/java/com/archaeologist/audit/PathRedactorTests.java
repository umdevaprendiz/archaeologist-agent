package com.archaeologist.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathRedactorTests {

    private static final String SEPARATOR = FileSystems.getDefault().getSeparator();

    @TempDir
    Path tempDir;

    private static PathRedactor redactor(Path repo) {
        return new PathRedactor(Map.of(repo, "<repo>"));
    }

    @Test
    void repositoryCurrentAndHomeFoldersBecomePlaceholders() {
        Path home = tempDir.resolve("home");
        Path current = home.resolve("work");
        Path repo = current.resolve("legacy");
        Map<Path, String> folders = new LinkedHashMap<>();
        folders.put(repo, "<repo>");
        folders.put(current, ".");
        folders.put(home, "~");
        PathRedactor redactor = new PathRedactor(folders);

        assertThat(redactor.redact("read " + repo.resolve("A.java") + ", wrote " + current.resolve("out")
                + ", cache in " + home.resolve(".m2")))
                .isEqualTo("read <repo>" + SEPARATOR + "A.java, wrote ." + SEPARATOR + "out, cache in ~"
                        + SEPARATOR + ".m2");
    }

    @Test
    void sameFolderTwiceKeepsTheFirstPlaceholder() {
        Map<Path, String> folders = new LinkedHashMap<>();
        folders.put(tempDir, "<repo>");
        folders.put(tempDir.resolve("x").resolve(".."), ".");

        assertThat(new PathRedactor(folders).redact(tempDir.toString())).isEqualTo("<repo>");
    }

    @Test
    void pathsAreReplacedOnlyAsAWhole() {
        Path app = tempDir.resolve("app");
        PathRedactor redactor = redactor(app);

        assertThat(redactor.redact(tempDir.resolve("application").toString()))
                .isEqualTo(tempDir.resolve("application").toString());
        assertThat(redactor.redact(tempDir.resolve("app.txt").toString())).isEqualTo(tempDir.resolve("app.txt").toString());
        assertThat(redactor.redact(tempDir.resolve("app-old").toString())).isEqualTo(tempDir.resolve("app-old").toString());
        assertThat(redactor.redact("x" + app)).isEqualTo("x" + app);
    }

    @Test
    void pathAtTheEndOfASentenceOrInQuotesIsReplaced() {
        Path repo = tempDir.resolve("legacy");

        assertThat(redactor(repo).redact("Cannot read " + repo + ". Retrying in '" + repo + "'"))
                .isEqualTo("Cannot read <repo>. Retrying in '<repo>'");
    }

    @Test
    void otherSpellingsOfThePathAreReplaced() {
        Path repo = tempDir.resolve("legacy");
        String slashes = repo.toString().replace('\\', '/');
        String uri = "file://" + (slashes.startsWith("/") ? "" : "/") + slashes;

        assertThat(redactor(repo).redact(repo.toString().toUpperCase(Locale.ROOT))).isEqualTo("<repo>");
        assertThat(redactor(repo).redact(slashes + "/src")).isEqualTo("<repo>/src");
        assertThat(redactor(repo).redact(uri)).isEqualTo("file://" + (slashes.startsWith("/") ? "" : "/") + "<repo>");
    }

    @Test
    void rootsAreNeverReplaced() {
        Path root = tempDir.getRoot();

        assertThat(redactor(root).redact(tempDir.toString())).isEqualTo(tempDir.toString());
    }

    @Test
    void runRedactsTheRepositoryTheCurrentFolderAndTheHomeFolder() {
        Path repo = tempDir.resolve("legacy");
        Path current = Path.of("").toAbsolutePath();
        Path home = Path.of(System.getProperty("user.home"));
        PathRedactor redactor = PathRedactor.forRun(repo);

        assertThat(redactor.redact(repo.resolve("A.java").toString())).isEqualTo("<repo>" + SEPARATOR + "A.java");
        assertThat(redactor.redact(current.resolve("pom.xml").toString())).isEqualTo("." + SEPARATOR + "pom.xml");
        // Unless the home folder is a root, or holds the current folder at the same place (then "." wins)
        Path inHome = home.resolve("archaeologist-redactor-test");
        if (home.getNameCount() > 0 && !inHome.startsWith(current)) {
            assertThat(redactor.redact(inHome.toString())).isEqualTo("~" + SEPARATOR + "archaeologist-redactor-test");
        }
    }
}
