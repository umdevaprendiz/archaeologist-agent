package com.archaeologist.audit;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces absolute paths that would show the user's folders with placeholders. Texts recorded in the
 * audit log can hold them without the caller knowing: an exception message, the output of Git.
 *
 * <p>A path is replaced only as a whole: {@code /work/app} is not replaced inside {@code /work/application}
 * or {@code /srv/work/app}. Matching ignores case, since Windows paths do, and accepts both {@code \} and
 * {@code /} as the separator on Windows. Roots, such as {@code /} or {@code C:\}, are never replaced: they
 * would match every path.
 */
final class PathRedactor {

    /** Not right after a character that could be part of a file name. */
    private static final String START = "(?<![\\w.-])";

    /** Not followed by more of a file name, e.g. {@code /app} in {@code /application} or {@code /app.txt}. */
    private static final String END = "(?!\\.?[\\w-])";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private record Rule(Pattern pattern, String replacement) {
    }

    private final List<Rule> rules;

    /**
     * @param folders each folder with its placeholder; when two folders are the same, the first one wins
     */
    PathRedactor(Map<Path, String> folders) {
        Map<String, Rule> byPath = new LinkedHashMap<>();
        folders.forEach((folder, placeholder) -> {
            for (Path form : forms(folder)) {
                if (form.getNameCount() == 0) {
                    continue;
                }
                String text = form.toString();
                for (String spelling : List.of(text, text.replace('\\', '/'))) {
                    byPath.putIfAbsent(spelling.toLowerCase(Locale.ROOT), new Rule(
                            Pattern.compile(START + Pattern.quote(spelling) + END, FLAGS),
                            Matcher.quoteReplacement(placeholder)));
                }
            }
        });
        // Longest first: the repository is replaced before the home folder that contains it
        List<Map.Entry<String, Rule>> entries = new ArrayList<>(byPath.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, Rule> entry) -> entry.getKey().length()).reversed());
        this.rules = entries.stream().map(Map.Entry::getValue).toList();
    }

    /**
     * For the texts of a run: the repository under analysis becomes {@code <repo>}, the current folder
     * {@code .} and the home folder {@code ~}.
     */
    static PathRedactor forRun(Path repository) {
        Map<Path, String> folders = new LinkedHashMap<>();
        folders.put(repository, "<repo>");
        folders.put(Path.of(""), ".");
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            try {
                folders.put(Path.of(home), "~");
            } catch (InvalidPathException ex) {
                // Not a usable path, so it can't show up as one either
            }
        }
        return new PathRedactor(folders);
    }

    String redact(String text) {
        String result = text;
        for (Rule rule : rules) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    /** The absolute path, and the real one when links make it differ (e.g. {@code /tmp} on macOS). */
    private static Set<Path> forms(Path folder) {
        Set<Path> forms = new LinkedHashSet<>();
        Path absolute = folder.toAbsolutePath().normalize();
        forms.add(absolute);
        try {
            forms.add(absolute.toRealPath());
        } catch (IOException | SecurityException ex) {
            // A folder that doesn't exist yet has no real path: the absolute one is enough
        }
        return forms;
    }
}
