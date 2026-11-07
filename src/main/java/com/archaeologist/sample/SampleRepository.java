package com.archaeologist.sample;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.archaeologist.run.FileErrors;
import com.archaeologist.sample.SampleHistory.Add;
import com.archaeologist.sample.SampleHistory.Commit;
import com.archaeologist.sample.SampleHistory.FileRule;
import com.archaeologist.sample.SampleHistory.Operation;
import com.archaeologist.sample.SampleHistory.Prepend;
import com.archaeologist.sample.SampleHistory.Rename;
import com.archaeologist.sample.SampleHistory.Replace;
import com.archaeologist.sample.SampleHistory.ReplaceAll;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Creates the sample legacy repository: a small Java 6/7-style order system whose Git history (about fifty
 * commits over six years, by five fictional people) carries the signals the agent looks for. The history
 * is scripted in {@code sample/legacy-shop.history}, which lists those signals.
 *
 * <p>Commit dates count back from a given day, so the history always ends a couple of weeks before it and
 * the default five years of history always cover the same commits. The same day gives the same commits,
 * hashes included. Git runs with nothing from the user's setup ({@link IsolatedGit}): their name and
 * e-mail never end up in the sample, and their hooks never run.
 */
public final class SampleRepository {

    /** What was created. */
    public record Summary(int commits, int years) {
    }

    /** Commit times are in the authors' time zone: Brazil, three hours behind UTC. */
    private static final ZoneOffset AUTHORS_ZONE = ZoneOffset.ofHours(-3);

    private SampleRepository() {
    }

    /**
     * Creates the sample repository in {@code folder}, which must not exist or be empty.
     *
     * @param today the day the history counts back from
     * @throws SampleException if the folder is in the way, or Git is missing or fails. Messages show the
     *                         folder as given, never expanded to an absolute path.
     */
    public static Summary create(Path folder, LocalDate today) {
        SampleHistory history = SampleHistory.load();
        byte[] stream = fastImportStream(history, today);
        prepare(folder);
        try (IsolatedGit git = new IsolatedGit()) {
            git.run(folder, null, "init", "--quiet", "--template=");
            git.run(folder, stream, "fast-import", "--quiet", "--done");
            git.run(folder, null, "symbolic-ref", "HEAD", "refs/heads/main");
            git.run(folder, null, "reset", "--hard", "--quiet");
        }
        int oldest = history.commits.get(0).daysAgo();
        return new Summary(history.commits.size(), oldest / 365);
    }

    private static void prepare(Path folder) {
        if (Files.exists(folder)) {
            if (!Files.isDirectory(folder)) {
                throw new SampleException("Not a folder: " + folder);
            }
            try (Stream<Path> entries = Files.list(folder)) {
                if (entries.findAny().isPresent()) {
                    throw new SampleException("Not an empty folder: " + folder + " (the sample needs a new or empty one)");
                }
            } catch (IOException ex) {
                throw new SampleException("Cannot read the folder " + folder + ": " + FileErrors.reason(ex), ex);
            }
            return;
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException ex) {
            throw new SampleException("Cannot create the folder " + folder + ": " + FileErrors.reason(ex), ex);
        }
    }

    /**
     * The whole history as a {@code git fast-import} stream: every commit lists every file, so Git sees
     * renames, additions and changes on its own.
     */
    static byte[] fastImportStream(SampleHistory history, LocalDate today) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Map<String, String> files = new TreeMap<>();
        for (int index = 0; index < history.commits.size(); index++) {
            Commit commit = history.commits.get(index);
            for (Operation operation : commit.operations()) {
                apply(operation, files, commit);
            }
            String identity = commit.author().name() + " <" + commit.author().email() + "> "
                    + epochSecond(today, commit.daysAgo(), index) + " -0300";
            write(stream, "commit refs/heads/main\n");
            write(stream, "author " + identity + "\n");
            write(stream, "committer " + identity + "\n");
            data(stream, (commit.message() + "\n").getBytes(UTF_8));
            write(stream, "deleteall\n");
            for (Map.Entry<String, String> file : files.entrySet()) {
                write(stream, "M 100644 inline " + file.getKey() + "\n");
                data(stream, encode(file.getKey(), file.getValue(), history.fileRules));
            }
            write(stream, "\n");
        }
        write(stream, "done\n");
        return stream.toByteArray();
    }

    private static void apply(Operation operation, Map<String, String> files, Commit commit) {
        switch (operation) {
            case Add add -> {
                if (files.putIfAbsent(add.path(), add.content()) != null) {
                    throw invalid(commit, add.path() + " already exists");
                }
            }
            case Replace replace -> {
                String content = existing(files, replace.path(), commit);
                int first = content.indexOf(replace.oldText());
                if (first < 0 || content.indexOf(replace.oldText(), first + 1) >= 0) {
                    throw invalid(commit, "the text to replace must occur exactly once in " + replace.path());
                }
                files.put(replace.path(), content.replace(replace.oldText(), replace.newText()));
            }
            case ReplaceAll replaceAll -> {
                boolean found = false;
                for (Map.Entry<String, String> file : files.entrySet()) {
                    if (file.getKey().endsWith(replaceAll.extension()) && file.getValue().contains(replaceAll.oldText())) {
                        file.setValue(file.getValue().replace(replaceAll.oldText(), replaceAll.newText()));
                        found = true;
                    }
                }
                if (!found) {
                    throw invalid(commit, "no *" + replaceAll.extension() + " file holds the text to replace");
                }
            }
            case Prepend prepend -> {
                boolean found = false;
                for (Map.Entry<String, String> file : files.entrySet()) {
                    if (file.getKey().endsWith(prepend.extension())) {
                        file.setValue(prepend.text() + file.getValue());
                        found = true;
                    }
                }
                if (!found) {
                    throw invalid(commit, "no *" + prepend.extension() + " file to prepend to");
                }
            }
            case Rename rename -> {
                String content = existing(files, rename.from(), commit);
                if (files.containsKey(rename.to())) {
                    throw invalid(commit, rename.to() + " already exists");
                }
                files.remove(rename.from());
                files.put(rename.to(), content);
            }
        }
    }

    private static String existing(Map<String, String> files, String path, Commit commit) {
        String content = files.get(path);
        if (content == null) {
            throw invalid(commit, path + " does not exist");
        }
        return content;
    }

    /** The file as stored: in its encoding, with CRLF line breaks if its rule says so. */
    private static byte[] encode(String path, String content, Map<String, FileRule> rules) {
        FileRule rule = rules.getOrDefault(path, FileRule.DEFAULT);
        String text = rule.crlf() ? content.replace("\n", "\r\n") : content;
        try {
            ByteBuffer bytes = rule.encoding().newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] result = new byte[bytes.remaining()];
            bytes.get(result);
            return result;
        } catch (CharacterCodingException ex) {
            throw new SampleException("Invalid sample history: " + path + " has characters that "
                    + rule.encoding().name() + " can't encode", ex);
        }
    }

    /** A different time of day for each commit, between 09:00 and 18:00 in the authors' time zone. */
    private static long epochSecond(LocalDate today, int daysAgo, int index) {
        LocalTime time = LocalTime.of(9, 0).plusMinutes((index * 97L) % 540);
        return today.minusDays(daysAgo).atTime(time).toEpochSecond(AUTHORS_ZONE);
    }

    private static void data(ByteArrayOutputStream stream, byte[] content) {
        write(stream, "data " + content.length + "\n");
        stream.writeBytes(content);
        write(stream, "\n");
    }

    private static void write(ByteArrayOutputStream stream, String text) {
        stream.writeBytes(text.getBytes(UTF_8));
    }

    private static SampleException invalid(Commit commit, String problem) {
        return new SampleException("Invalid sample history, commit '" + commit.message() + "': " + problem);
    }
}
