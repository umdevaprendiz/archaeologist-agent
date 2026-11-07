package com.archaeologist.conduct;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds sensitive data in text: known values (planted fake secrets, local paths, user and machine names)
 * and well-known shapes (e-mail addresses, private keys, access tokens, paths inside home folders, hidden
 * characters).
 *
 * <p>A finding never quotes what it found, only its kind and line: test reports end up in build logs,
 * and a report that repeats a leaked value would leak it again.
 *
 * <p>Some patterns below are written in an unusual way (slashes escaped, characters given as numbers) so
 * this file doesn't match its own rules when the project scan reads it.
 */
final class SensitiveDataScanner {

    /** Where sensitive data was found, and what kind. Never the data itself. */
    record Finding(String source, int line, String kind) {

        @Override
        public String toString() {
            return source + ":" + line + ": " + kind;
        }
    }

    private record Rule(String kind, Pattern pattern) {
    }

    /** Domains reserved for documentation and tests (RFC 2606 and RFC 6761): never anyone's real address. */
    private static final Pattern RESERVED_EMAIL_DOMAIN = Pattern.compile(
            "(?i)(.*\\.)?(example\\.(com|org|net)|[^.]+\\.(example|invalid|test|localhost))");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})");

    private static final List<Rule> SHAPES = List.of(
            new Rule("a private key", Pattern.compile("-{5}BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-{5}")),
            new Rule("an AWS access key", Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("a GitHub token", Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})")),
            new Rule("a Slack token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
            // A folder inside a home folder names its user. Placeholders such as /home/<user> don't match,
            // and neither do web addresses (example.com/home/page).
            new Rule("a path inside a home folder", Pattern.compile(
                    "(?<![\\w])[A-Za-z]:[\\\\\\/]+Users[\\\\\\/]+[^\\\\\\/\\s\"'<>:*?|]+"
                            + "|(?<![\\w.-])\\/(?:home|Users)\\/[^\\/\\s\"'<>]+")));

    private final List<Rule> values;
    private final boolean allowReservedEmails;

    private SensitiveDataScanner(Map<String, List<String>> knownValues, boolean allowReservedEmails) {
        List<String[]> pairs = new ArrayList<>();
        knownValues.forEach((kind, spellings) -> spellings.forEach(value -> pairs.add(new String[] {kind, value})));
        // Longest first, so a folder is masked as a whole before the user name inside it
        pairs.sort((a, b) -> Integer.compare(b[1].length(), a[1].length()));
        this.values = pairs.stream().map(pair -> new Rule(pair[0],
                Pattern.compile(Pattern.quote(pair[1]), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))).toList();
        this.allowReservedEmails = allowReservedEmails;
    }

    /**
     * The text with everything this scanner looks for replaced by its kind in brackets, e.g.
     * {@code [the real home folder]}: safe to show in a test report, to explain a failure.
     */
    String mask(String text) {
        String result = text;
        for (Rule rule : values) {
            result = rule.pattern().matcher(result).replaceAll(Matcher.quoteReplacement("[" + rule.kind() + "]"));
        }
        for (Rule rule : SHAPES) {
            result = rule.pattern().matcher(result).replaceAll(Matcher.quoteReplacement("[" + rule.kind() + "]"));
        }
        result = EMAIL.matcher(result).replaceAll("[an e-mail address]");
        StringBuilder masked = new StringBuilder(result.length());
        result.codePoints().forEach(codePoint -> {
            if (isHidden(codePoint)) {
                masked.append(String.format("[U+%04X]", codePoint));
            } else {
                masked.appendCodePoint(codePoint);
            }
        });
        return masked.toString();
    }

    static Builder builder() {
        return new Builder();
    }

    /**
     * Scans one text.
     *
     * @param source where the text comes from, for the findings (e.g. a file name)
     */
    List<Finding> scan(String source, String text) {
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : values) {
            find(rule, source, text, findings);
        }
        for (Rule rule : SHAPES) {
            find(rule, source, text, findings);
        }
        Matcher email = EMAIL.matcher(text);
        while (email.find()) {
            if (!(allowReservedEmails && RESERVED_EMAIL_DOMAIN.matcher(email.group(1)).matches())) {
                findings.add(new Finding(source, line(text, email.start()), "an e-mail address"));
            }
        }
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (isHidden(codePoint)) {
                findings.add(new Finding(source, line(text, index),
                        String.format("a hidden character (U+%04X)", codePoint)));
            }
            index += Character.charCount(codePoint);
        }
        return findings;
    }

    /**
     * Characters that change how text shows instead of showing themselves: control codes other than tab
     * and line breaks, DEL, the C1 controls, the Unicode line and paragraph separators, and the
     * bidirectional controls that reorder text on screen.
     */
    static boolean isHidden(int codePoint) {
        return (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n' && codePoint != '\r')
                || (codePoint >= 0x7F && codePoint <= 0x9F)
                || codePoint == 0x2028 || codePoint == 0x2029
                || codePoint == 0x061C
                || codePoint == 0x200E || codePoint == 0x200F
                || (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static void find(Rule rule, String source, String text, List<Finding> findings) {
        Matcher matcher = rule.pattern().matcher(text);
        while (matcher.find()) {
            findings.add(new Finding(source, line(text, matcher.start()), rule.kind()));
        }
    }

    private static int line(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static final class Builder {

        /** Shorter values would match ordinary words. */
        static final int MIN_VALUE_LENGTH = 4;

        private final Map<String, List<String>> values = new LinkedHashMap<>();
        private boolean allowReservedEmails;

        private Builder() {
        }

        /** A value that must never show up, e.g. a planted fake password. Ignored if too short to be telling. */
        Builder value(String kind, String value) {
            if (value != null && value.strip().length() >= MIN_VALUE_LENGTH) {
                values.computeIfAbsent(kind, key -> new ArrayList<>()).add(value.strip());
            }
            return this;
        }

        /**
         * A folder that must never show up, in either separator. Ignored if it is shallow enough to be
         * generic (a root, or a folder right under it, such as {@code /root} or {@code /tmp}).
         */
        Builder path(String kind, Path path) {
            Path absolute = path.toAbsolutePath().normalize();
            if (absolute.getNameCount() < 2) {
                return this;
            }
            String text = absolute.toString();
            value(kind, text);
            if (text.indexOf('\\') >= 0) {
                value(kind, text.replace('\\', '/'));
            }
            return this;
        }

        /** E-mail addresses at reserved example domains are fine (docs and test data use them). */
        Builder allowReservedEmails() {
            allowReservedEmails = true;
            return this;
        }

        SensitiveDataScanner build() {
            return new SensitiveDataScanner(values, allowReservedEmails);
        }
    }

}
