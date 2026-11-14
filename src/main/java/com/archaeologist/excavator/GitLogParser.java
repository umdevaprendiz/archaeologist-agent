package com.archaeologist.excavator;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.archaeologist.git.GitException;
import com.archaeologist.git.GitException.Reason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of {@code git log -z --numstat --format=}{@value #FORMAT}, as it comes.
 *
 * <p>With {@code -z}, every field ends with a NUL byte, the one byte a commit message or a path can't hold.
 * So each commit is read field by field, in order: its header (a {@code U+0001} mark and the hash, the
 * author date, the author's name and e-mail after the {@code .mailmap}, the message), then one field per
 * file changed ({@code added<TAB>removed<TAB>path}, with {@code -} for the counts of a binary file). A renamed
 * file has an empty path, followed by two more fields: the old path and the new one. The next commit's
 * mark ends the list of files.
 *
 * <p>Names and e-mail addresses are only used to tell authors apart: each author gets a number, and the
 * text is dropped. Messages about unexpected output never quote it.
 */
final class GitLogParser {

    /** Header of each commit: mark, hash, author date (seconds since 1970), name, e-mail, message. */
    static final String FORMAT = "%x01%H%x00%at%x00%aN%x00%aE%x00%B";

    private static final char MARK = '\u0001';

    /** SHA-1 or, in repositories that use it, SHA-256. */
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private static final Pattern FILE = Pattern.compile("(\\d+|-)\t(\\d+|-)\t(.*)", Pattern.DOTALL);

    private final InputStream in;
    private final byte[] chunk = new byte[64 * 1024];
    private int position;
    private int limit;
    private final ByteArrayOutputStream field = new ByteArrayOutputStream();

    /** Each author's number, by e-mail address (or by name, for an author without one). */
    private final Map<String, Integer> authors = new HashMap<>();

    GitLogParser(InputStream in) {
        this.in = in;
    }

    /**
     * @return every commit in the output, in its order
     * @throws GitException if the output is not what {@value #FORMAT} produces
     */
    List<Commit> parse() throws IOException {
        List<Commit> commits = new ArrayList<>();
        String next = next();
        while (next != null) {
            if (next.isEmpty() || next.charAt(0) != MARK) {
                throw unexpected("the start of a commit");
            }
            String hash = next.substring(1);
            if (!HASH.matcher(hash).matches()) {
                throw unexpected("a commit hash");
            }
            Instant date = date(required("the author date"));
            int author = author(required("the author's name"), required("the author's e-mail"));
            String message = required("the message").stripTrailing();
            List<FileChange> files = new ArrayList<>();
            next = next();
            while (next != null && (next.isEmpty() || next.charAt(0) != MARK)) {
                // Git puts a line break between the message and the list of files
                String entry = next.stripLeading();
                if (!entry.isEmpty()) {
                    files.add(fileChange(entry));
                }
                next = next();
            }
            commits.add(new Commit(hash, author, date, message, files));
        }
        return commits;
    }

    private FileChange fileChange(String entry) throws IOException {
        Matcher matcher = FILE.matcher(entry);
        if (!matcher.matches()) {
            throw unexpected("the lines added and removed in a file");
        }
        boolean binary = matcher.group(1).equals("-");
        if (binary != matcher.group(2).equals("-")) {
            throw unexpected("the lines added and removed in a file");
        }
        String path = matcher.group(3);
        String previousPath = null;
        if (path.isEmpty()) {
            previousPath = required("the old path of a renamed file");
            path = required("the new path of a renamed file");
            if (previousPath.isEmpty() || path.isEmpty()) {
                throw unexpected("the paths of a renamed file");
            }
        }
        return binary ? new FileChange(path, previousPath, 0, 0, true)
                : new FileChange(path, previousPath, count(matcher.group(1)), count(matcher.group(2)), false);
    }

    private int author(String name, String email) {
        // A NUL can't be part of an e-mail address read from Git: the two kinds of key never clash
        String key = email.isBlank() ? "\0" + name.strip() : email.strip().toLowerCase(Locale.ROOT);
        Integer id = authors.get(key);
        if (id == null) {
            id = authors.size();
            authors.put(key, id);
        }
        return id;
    }

    private static Instant date(String seconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(seconds));
        } catch (NumberFormatException | DateTimeException ex) {
            throw unexpected("the author date");
        }
    }

    private static int count(String lines) {
        try {
            return Integer.parseInt(lines);
        } catch (NumberFormatException ex) {
            throw unexpected("the lines added and removed in a file");
        }
    }

    private String required(String what) throws IOException {
        String value = next();
        if (value == null) {
            throw unexpected(what);
        }
        return value;
    }

    /** The next field, without its NUL; {@code null} at the end of the output. */
    private String next() throws IOException {
        field.reset();
        while (true) {
            if (position == limit && !fill()) {
                // The output ends with a NUL: anything after the last one is a field cut short
                return field.size() == 0 ? null : field.toString(UTF_8);
            }
            int start = position;
            while (position < limit && chunk[position] != 0) {
                position++;
            }
            field.write(chunk, start, position - start);
            if (position < limit) {
                position++;
                return field.toString(UTF_8);
            }
        }
    }

    private boolean fill() throws IOException {
        int read = in.read(chunk);
        position = 0;
        limit = Math.max(read, 0);
        return read > 0;
    }

    private static GitException unexpected(String what) {
        return new GitException(Reason.FAILED, "Unexpected output from 'git log': expected " + what);
    }
}
