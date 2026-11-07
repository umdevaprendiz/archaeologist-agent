package com.archaeologist.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.archaeologist.sample.SampleHistory.Add;
import com.archaeologist.sample.SampleHistory.Rename;
import com.archaeologist.sample.SampleHistory.Replace;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The script format, and how mistakes in it are reported. */
class SampleHistoryTests {

    private static final String AUTHOR = "author ana: Ana Souza <ana" + "@" + "example.com>\n";

    private static void assertInvalid(String script, String message) {
        assertThatExceptionOfType(SampleException.class).isThrownBy(() -> SampleHistory.parse(script))
                .withMessageContaining(message);
    }

    @Test
    void bundledHistoryIsValid() {
        SampleHistory history = SampleHistory.load();

        assertThat(history.commits).hasSize(50);
        assertThat(history.authors).hasSize(6);
        assertThat(history.fileRules).hasSize(2);
    }

    @Test
    void scriptIsParsed() {
        SampleHistory history = SampleHistory.parse(AUTHOR + """
                file A.java: encoding ISO-8859-1
                file B.java: line-endings CRLF

                commit 10 ana
                Initial import
                add A.java
                <<<
                class A {
                }
                >>>

                commit 5 ana
                fix: rename it
                replace A.java
                <<<
                class A
                ===
                class B
                >>>
                rename A.java -> B.java
                """);

        assertThat(history.fileRules.get("A.java").encoding()).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThat(history.fileRules.get("B.java").crlf()).isTrue();
        assertThat(history.commits.get(0).operations()).containsExactly(new Add("A.java", "class A {\n}\n"));
        assertThat(history.commits.get(1).message()).isEqualTo("fix: rename it");
        assertThat(history.commits.get(1).operations())
                .containsExactly(new Replace("A.java", "class A", "class B"), new Rename("A.java", "B.java"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gmail.com", "company.com.br", "example.com.attacker.io"})
    void authorsMustUseReservedExampleDomains(String domain) {
        assertInvalid("author bob: Bob <bob" + "@" + domain + ">\n", "reserved example domain");
    }

    @Test
    void commitsMustGoForwardInTime() {
        assertInvalid(AUTHOR + "commit 5 ana\nfirst\nadd A.java\n<<<\nx\n>>>\ncommit 9 ana\nsecond\nadd B.java\n<<<\ny\n>>>\n",
                "line 8: commits must go forward in time");
    }

    @Test
    void unknownAuthorIsReported() {
        assertInvalid(AUTHOR + "commit 5 bob\nfirst\n", "line 2: unknown author 'bob'");
    }

    @Test
    void unclosedBlockIsReported() {
        assertInvalid(AUTHOR + "commit 5 ana\nfirst\nadd A.java\n<<<\nx\n", "block not closed");
    }

    @Test
    void pathsMustStayInsideTheRepository() {
        assertInvalid(AUTHOR + "commit 5 ana\nfirst\nadd ../outside.txt\n<<<\nx\n>>>\n", "not a plain relative path");
    }

    @Test
    void textToReplaceMustOccurExactlyOnce() {
        SampleHistory history = SampleHistory.parse(AUTHOR + """
                commit 9 ana
                first
                add A.java
                <<<
                x x
                >>>
                commit 5 ana
                second
                replace A.java
                <<<
                x
                ===
                y
                >>>
                """);

        assertThatExceptionOfType(SampleException.class)
                .isThrownBy(() -> SampleRepository.fastImportStream(history, LocalDate.of(2026, 1, 1)))
                .withMessage("Invalid sample history, commit 'second': the text to replace must occur exactly once in A.java");
    }

    @Test
    void charactersTheFileEncodingCannotHoldAreReported() {
        SampleHistory history = SampleHistory.parse(AUTHOR + """
                file A.java: encoding ISO-8859-1
                commit 9 ana
                first
                add A.java
                <<<
                String s = "€";
                >>>
                """);

        assertThatExceptionOfType(SampleException.class)
                .isThrownBy(() -> SampleRepository.fastImportStream(history, LocalDate.of(2026, 1, 1)))
                .withMessageContaining("A.java has characters that ISO-8859-1 can't encode");
    }
}
