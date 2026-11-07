package com.archaeologist.conduct;

import static org.assertj.core.api.Assertions.assertThat;

import com.archaeologist.conduct.SensitiveDataScanner.Finding;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The scanner must find what it looks for, or the conduct tests prove nothing. The samples are put
 * together at run time, so this file holds none of them for the project scan to find.
 */
class SensitiveDataScannerTests {

    private static final SensitiveDataScanner SHAPES_ONLY = SensitiveDataScanner.builder().build();

    @TempDir
    Path tempDir;

    private static String join(String separator, String... parts) {
        return String.join(separator, parts);
    }

    private static List<String> kinds(List<Finding> findings) {
        return findings.stream().map(Finding::kind).distinct().toList();
    }

    @Test
    void ordinaryTextHasNoFindings() {
        assertThat(SHAPES_ONLY.scan("report", """
                Run 20261107T221334Z-4997: output in archaeologist-output/20261107T221334Z-4997
                hotspots on repository 'legacy-app'; settings: history.years=5, risk-weights.fixes=1.0
                corrige cálculo da ação em src/main/Pedido.java, see https://example.com/home/page
                Placeholders such as <repo>, ~/.m2/settings.xml and /home/<user> are fine.
                """)).isEmpty();
    }

    @Test
    void plantedValueIsFoundWhateverItsCase() {
        SensitiveDataScanner scanner = SensitiveDataScanner.builder().value("the planted password", "Canary-Pass-42").build();

        assertThat(scanner.scan("log", "line one\nretrying with canary-pass-42 now"))
                .containsExactly(new Finding("log", 2, "the planted password"));
    }

    @Test
    void valuesTooShortToBeTellingAreIgnored() {
        SensitiveDataScanner scanner = SensitiveDataScanner.builder().value("a user name", "dev").build();

        assertThat(scanner.scan("log", "the dev branch")).isEmpty();
    }

    @Test
    void folderIsFoundWithEitherSeparator() {
        Path home = tempDir.resolve("home").resolve("someone");
        SensitiveDataScanner scanner = SensitiveDataScanner.builder().path("the home folder", home).build();
        String slashes = home.toAbsolutePath().toString().replace('\\', '/');

        // On Windows the temporary folder is inside the user's own, so its shape is found as well
        assertThat(kinds(scanner.scan("log", "cache in " + home.resolve(".m2")))).contains("the home folder");
        assertThat(kinds(scanner.scan("log", "file://" + slashes + "/x"))).contains("the home folder");
        assertThat(kinds(scanner.scan("log", "cache in " + tempDir.resolve("elsewhere")))).doesNotContain("the home folder");
    }

    @Test
    void shallowFoldersAreTooGenericToLookFor() {
        SensitiveDataScanner scanner = SensitiveDataScanner.builder().path("a folder", tempDir.getRoot()).build();

        assertThat(kinds(scanner.scan("log", tempDir.toString()))).doesNotContain("a folder");
    }

    @Test
    void eMailAddressIsFound() {
        String email = "jane.doe" + "@" + "company.com.br";

        assertThat(SHAPES_ONLY.scan("log", "author: " + email)).containsExactly(new Finding("log", 1, "an e-mail address"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "mail.example.org", "example.net", "corp.example", "git.example.invalid",
            "host.test", "box.localhost"})
    void reservedExampleDomainsCanBeAllowed(String domain) {
        String email = "someone" + "@" + domain;
        SensitiveDataScanner allowing = SensitiveDataScanner.builder().allowReservedEmails().build();

        assertThat(allowing.scan("doc", email)).isEmpty();
        assertThat(kinds(SHAPES_ONLY.scan("log", email))).containsExactly("an e-mail address");
    }

    @Test
    void lookalikeOfAReservedDomainIsStillFound() {
        String email = "someone" + "@" + "example.com.attacker.io";
        SensitiveDataScanner allowing = SensitiveDataScanner.builder().allowReservedEmails().build();

        assertThat(kinds(allowing.scan("doc", email))).containsExactly("an e-mail address");
    }

    @Test
    void secretsAreFoundByTheirShape() {
        String dashes = "-".repeat(5);
        String text = String.join("\n",
                dashes + "BEGIN OPENSSH PRIVATE KEY" + dashes,
                "aws_access_key_id = " + "AKIA" + "ABCDEFGHIJKLMNOP",
                "token: " + "ghp_" + "a1".repeat(18),
                "token: " + "github_pat_" + "B2".repeat(11),
                "hook: " + "xoxb-" + "1234567890-abc");

        assertThat(SHAPES_ONLY.scan("log", text)).containsExactly(
                new Finding("log", 1, "a private key"),
                new Finding("log", 2, "an AWS access key"),
                new Finding("log", 3, "a GitHub token"),
                new Finding("log", 4, "a GitHub token"),
                new Finding("log", 5, "a Slack token"));
    }

    @Test
    void pathsInsideHomeFoldersAreFoundOnEverySystem() {
        String windows = join("\\", "C:", "Users", "someone", "project");
        String windowsSlashes = join("/", "D:", "Users", "someone");
        String linux = join("/", "", "home", "someone", "project");
        String mac = join("/", "", "Users", "someone");
        String fileUri = "file://" + join("/", "", "home", "someone");

        for (String path : List.of(windows, windowsSlashes, linux, mac, fileUri)) {
            assertThat(kinds(SHAPES_ONLY.scan("log", "cannot read " + path))).as(path)
                    .containsExactly("a path inside a home folder");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00, 0x07, 0x1B, 0x7F, 0x85, 0x9B, 0x2028, 0x2029, 0x061C, 0x200E, 0x200F, 0x202A, 0x202E,
            0x2066, 0x2069})
    void hiddenCharactersAreFound(int codePoint) {
        assertThat(SHAPES_ONLY.scan("log", "ok " + Character.toString(codePoint) + " ok"))
                .containsExactly(new Finding("log", 1, String.format("a hidden character (U+%04X)", codePoint)));
    }

    @Test
    void tabsLineBreaksAndAccentsAreNotHidden() {
        assertThat(SHAPES_ONLY.scan("log", "a\tb\r\nção 😀 — ünïcödé")).isEmpty();
    }

    @Test
    void maskReplacesEverythingSensitiveWithItsKind() {
        Path home = tempDir.resolve("home").resolve("canary-user");
        SensitiveDataScanner scanner = SensitiveDataScanner.builder()
                .value("the user name", "canary-user")
                .path("the home folder", home)
                .build();
        String text = "user canary-user, cache in " + home.resolve(".m2") + ", mail " + "jo" + "@" + "corp.com.br"
                + ", bell " + Character.toString(0x07);

        String masked = scanner.mask(text);

        assertThat(masked).isEqualTo("user [the user name], cache in [the home folder]"
                + home.getFileSystem().getSeparator() + ".m2, mail [an e-mail address], bell [U+0007]");
        assertThat(scanner.scan("masked", masked)).isEmpty();
    }

    @Test
    void findingNeverQuotesWhatItFound() {
        String secret = "Canary-Pass-42";
        SensitiveDataScanner scanner = SensitiveDataScanner.builder().value("the planted password", secret).build();

        List<Finding> findings = scanner.scan("log", "password=" + secret);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).toString()).isEqualTo("log:1: the planted password").doesNotContain(secret);
    }
}
