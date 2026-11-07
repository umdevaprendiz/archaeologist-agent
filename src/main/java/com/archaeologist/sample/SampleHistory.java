package com.archaeologist.sample;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scripted history of the sample repository, read from {@code sample/legacy-shop.history}: its
 * authors, the encoding and line endings of some files, and its commits. The format is described at the
 * top of that file. Anything the script gets wrong stops with the line number.
 */
final class SampleHistory {

    static final String RESOURCE = "sample/legacy-shop.history";

    record Author(String name, String email) {
    }

    /** How a file is written to the repository; in memory every file is a string with LF line breaks. */
    record FileRule(Charset encoding, boolean crlf) {

        static final FileRule DEFAULT = new FileRule(StandardCharsets.UTF_8, false);
    }

    sealed interface Operation permits Add, Replace, ReplaceAll, Prepend, Rename {
    }

    record Add(String path, String content) implements Operation {
    }

    record Replace(String path, String oldText, String newText) implements Operation {
    }

    record ReplaceAll(String extension, String oldText, String newText) implements Operation {
    }

    record Prepend(String extension, String text) implements Operation {
    }

    record Rename(String from, String to) implements Operation {
    }

    record Commit(int daysAgo, Author author, String message, List<Operation> operations) {
    }

    /** Never anyone's real address (RFC 2606 and RFC 6761). */
    private static final Pattern RESERVED_EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@((.*\\.)?(example\\.(com|org|net))|[^.@]+(\\.[^.@]+)*\\.(example|invalid|test|localhost))");

    private static final Pattern AUTHOR = Pattern.compile("author ([a-z0-9]+): (.+) <([^<>]+)>");
    private static final Pattern FILE = Pattern.compile("file (\\S+): (encoding (\\S+)|line-endings CRLF)");
    private static final Pattern COMMIT = Pattern.compile("commit (\\d+) ([a-z0-9]+)");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*");
    private static final Pattern EXTENSION = Pattern.compile("\\*(\\.[A-Za-z0-9]+)");

    private static final String OPEN = "<<<";
    private static final String SEPARATOR = "===";
    private static final String CLOSE = ">>>";

    final Map<String, Author> authors = new LinkedHashMap<>();
    final Map<String, FileRule> fileRules = new LinkedHashMap<>();
    final List<Commit> commits = new ArrayList<>();

    private final List<String> lines;
    private int next;

    private SampleHistory(List<String> lines) {
        this.lines = lines;
    }

    /** The history bundled with the agent. */
    static SampleHistory load() {
        try (InputStream in = SampleHistory.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new SampleException("The sample history is missing from the agent: " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new SampleException("Cannot read the sample history: " + ex.getMessage(), ex);
        }
    }

    static SampleHistory parse(String script) {
        SampleHistory history = new SampleHistory(script.lines().toList());
        history.parse();
        return history;
    }

    private void parse() {
        while (next < lines.size()) {
            String line = lines.get(next++);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher author = AUTHOR.matcher(line);
            Matcher file = FILE.matcher(line);
            Matcher commit = COMMIT.matcher(line);
            if (author.matches()) {
                parseAuthor(author);
            } else if (file.matches()) {
                parseFileRule(file);
            } else if (commit.matches()) {
                parseCommit(commit);
            } else {
                throw error("expected 'author', 'file' or 'commit'");
            }
        }
        if (commits.isEmpty()) {
            throw error("no commits");
        }
    }

    private void parseAuthor(Matcher author) {
        String email = author.group(3);
        // The sample is published with the agent: its authors must never be real people
        if (!RESERVED_EMAIL.matcher(email).matches()) {
            throw error("author e-mails must use a reserved example domain, such as example.com");
        }
        if (authors.putIfAbsent(author.group(1), new Author(author.group(2), email)) != null) {
            throw error("author '" + author.group(1) + "' is defined twice");
        }
    }

    private void parseFileRule(Matcher file) {
        String path = path(file.group(1));
        FileRule current = fileRules.getOrDefault(path, FileRule.DEFAULT);
        FileRule rule;
        if (file.group(3) != null) {
            try {
                rule = new FileRule(Charset.forName(file.group(3)), current.crlf());
            } catch (IllegalArgumentException ex) {
                throw error("unknown encoding " + file.group(3));
            }
        } else {
            rule = new FileRule(current.encoding(), true);
        }
        fileRules.put(path, rule);
    }

    private void parseCommit(Matcher header) {
        int daysAgo = Integer.parseInt(header.group(1));
        if (!commits.isEmpty() && daysAgo >= commits.get(commits.size() - 1).daysAgo()) {
            throw error("commits must go forward in time: fewer days ago than the previous one");
        }
        Author author = authors.get(header.group(2));
        if (author == null) {
            throw error("unknown author '" + header.group(2) + "'");
        }
        if (next >= lines.size() || lines.get(next).isBlank()) {
            throw error("the commit message must follow on the next line");
        }
        String message = lines.get(next++).strip();
        List<Operation> operations = new ArrayList<>();
        while (next < lines.size() && !COMMIT.matcher(lines.get(next)).matches()) {
            String line = lines.get(next++);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            operations.add(parseOperation(line));
        }
        if (operations.isEmpty()) {
            throw error("commit '" + message + "' changes nothing");
        }
        commits.add(new Commit(daysAgo, author, message, List.copyOf(operations)));
    }

    private Operation parseOperation(String line) {
        int space = line.indexOf(' ');
        String keyword = space < 0 ? line : line.substring(0, space);
        String argument = space < 0 ? "" : line.substring(space + 1).strip();
        return switch (keyword) {
            case "add" -> new Add(path(argument), block().get(0) + "\n");
            case "replace" -> {
                List<String> parts = blockWithSeparator();
                yield new Replace(path(argument), parts.get(0), parts.get(1));
            }
            case "replace-all" -> {
                List<String> parts = blockWithSeparator();
                yield new ReplaceAll(extension(argument), parts.get(0), parts.get(1));
            }
            case "prepend" -> new Prepend(extension(argument), block().get(0) + "\n");
            case "rename" -> {
                String[] paths = argument.split(" -> ");
                if (paths.length != 2) {
                    throw error("expected 'rename <from> -> <to>'");
                }
                yield new Rename(path(paths[0].strip()), path(paths[1].strip()));
            }
            default -> throw error("unknown operation '" + keyword + "'");
        };
    }

    /** The lines between {@code <<<} and {@code >>>}, as one text without the last line break. */
    private List<String> block() {
        List<String> parts = blockParts();
        if (parts.size() != 1) {
            throw error("unexpected '" + SEPARATOR + "' in this block");
        }
        return parts;
    }

    /** The old and the new text of a replacement: {@code <<<}, old, {@code ===}, new, {@code >>>}. */
    private List<String> blockWithSeparator() {
        List<String> parts = blockParts();
        if (parts.size() != 2 || parts.get(0).isEmpty()) {
            throw error("expected '" + OPEN + "', the old text, '" + SEPARATOR + "', the new text and '" + CLOSE + "'");
        }
        return parts;
    }

    private List<String> blockParts() {
        if (next >= lines.size() || !lines.get(next).equals(OPEN)) {
            throw error("expected '" + OPEN + "' on the next line");
        }
        next++;
        List<String> parts = new ArrayList<>();
        List<String> current = new ArrayList<>();
        while (true) {
            if (next >= lines.size()) {
                throw error("block not closed with '" + CLOSE + "'");
            }
            String line = lines.get(next++);
            if (line.equals(CLOSE)) {
                parts.add(String.join("\n", current));
                return parts;
            }
            if (line.equals(SEPARATOR)) {
                parts.add(String.join("\n", current));
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
    }

    private String path(String text) {
        if (!PATH.matcher(text).matches() || text.contains("..")) {
            throw error("not a plain relative path: " + text);
        }
        return text;
    }

    private String extension(String text) {
        Matcher matcher = EXTENSION.matcher(text);
        if (!matcher.matches()) {
            throw error("expected '*.<extension>', got " + text);
        }
        return matcher.group(1);
    }

    private SampleException error(String problem) {
        return new SampleException("Invalid sample history, line " + next + ": " + problem);
    }
}
