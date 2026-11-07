package com.archaeologist.audit;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.archaeologist.run.FileErrors;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

/**
 * The audit log of one run: {@code audit.jsonl} in the run's folder. Every agent records each action and
 * decision here, one JSON object per line (JSON Lines), so the team can find out later what the agent did
 * and why:
 *
 * <pre>
 * {"timestamp":"2026-11-07T22:00:00.123Z","agent":"judge","action":"verdict","input":"...","outcome":"..."}
 * </pre>
 *
 * <p>Append-only: events are only ever added at the end, never changed or removed. Each event is written
 * with a single write, straight to the file, so a crash loses at most the event being written. Opening a
 * log that already exists (a resumed run) adds to it.
 *
 * <p>The texts of an event may quote the repository under analysis (commit messages, class names), so
 * they are treated as data:
 * <ul>
 *   <li>line breaks, terminal control codes and characters that reorder text on screen are escaped
 *       ({@link AuditEscapes}): an event is always one line, and reads the same in a terminal as in an editor;</li>
 *   <li>absolute paths of the repository, the current folder and the home folder become {@code <repo>},
 *       {@code .} and {@code ~} ({@link PathRedactor}): the log never shows the user's folders;</li>
 *   <li>each text is cut at {@value #MAX_TEXT_LENGTH} characters: the log keeps summaries, not whole files
 *       or prompts.</li>
 * </ul>
 *
 * <p>Safe to use from several threads.
 */
public final class AuditLog implements AutoCloseable {

    /** Longest input or outcome kept, in characters; longer ones are cut and end with {@link #TRUNCATED}. */
    public static final int MAX_TEXT_LENGTH = 1000;

    static final String TRUNCATED = " [truncated]";

    /** Actions are names chosen in the code, in kebab-case, e.g. {@code run-started}. */
    private static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** Always the same width, in UTC: lines sort by time, and read the same on every machine. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private static final JsonFactory JSON = JsonFactory.builder().characterEscapes(new AuditEscapes()).build();

    private final Path file;
    private final OutputStream out;
    private final Clock clock;
    private final PathRedactor redactor;
    private boolean closed;

    private AuditLog(Path file, OutputStream out, Clock clock, PathRedactor redactor) {
        this.file = file;
        this.out = out;
        this.clock = clock;
        this.redactor = redactor;
    }

    /**
     * Opens the log for appending, and creates it if needed.
     *
     * @throws AuditLogException if the file can't be opened
     */
    static AuditLog open(Path file, Clock clock, PathRedactor redactor) {
        boolean partialLastLine;
        OutputStream out;
        try {
            partialLastLine = endsWithPartialLine(file);
            out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new AuditLogException("Cannot open the audit log " + file + ": " + FileErrors.reason(ex), ex);
        }
        AuditLog log = new AuditLog(file, out, clock, redactor);
        if (partialLastLine) {
            // A crash in the middle of an event: start the next one on its own line
            try {
                log.write("\n");
            } catch (AuditLogException ex) {
                try {
                    log.close();
                } catch (AuditLogException closing) {
                    ex.addSuppressed(closing);
                }
                throw ex;
            }
        }
        return log;
    }

    /**
     * Records an event.
     *
     * @param agent   who acted or decided
     * @param action  what it did, in kebab-case, e.g. {@code verdict}
     * @param input   a short summary of what the action worked on; empty if nothing
     * @param outcome what came out of it, or why it failed
     * @throws IllegalArgumentException if {@code action} is not in kebab-case
     * @throws IllegalStateException    if the log is closed
     * @throws AuditLogException        if the event can't be written
     */
    public synchronized void record(Agent agent, String action, String input, String outcome) {
        Objects.requireNonNull(agent, "agent");
        if (action == null || !ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("Not an action name in kebab-case: " + action);
        }
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outcome, "outcome");
        if (closed) {
            throw new IllegalStateException("The audit log is closed");
        }
        write(toJson(agent, action, clean(input), clean(outcome)) + "\n");
    }

    /** @throws AuditLogException if the file can't be closed */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
        } catch (IOException ex) {
            throw new AuditLogException("Cannot close the audit log " + file + ": " + FileErrors.reason(ex), ex);
        }
    }

    private String toJson(Agent agent, String action, String input, String outcome) {
        StringWriter json = new StringWriter();
        try (JsonGenerator generator = JSON.createGenerator(json)) {
            generator.writeStartObject();
            generator.writeStringProperty("timestamp", TIMESTAMP.format(clock.instant()));
            generator.writeStringProperty("agent", agent.id());
            generator.writeStringProperty("action", action);
            generator.writeStringProperty("input", input);
            generator.writeStringProperty("outcome", outcome);
            generator.writeEndObject();
        }
        return json.toString();
    }

    /** Paths first: a path cut in half would no longer be recognized. */
    private String clean(String text) {
        String redacted = redactor.redact(text);
        if (redacted.length() <= MAX_TEXT_LENGTH) {
            return redacted;
        }
        int end = MAX_TEXT_LENGTH;
        if (Character.isHighSurrogate(redacted.charAt(end - 1))) {
            // Never split a character outside the Basic Multilingual Plane, such as an emoji
            end--;
        }
        return redacted.substring(0, end) + TRUNCATED;
    }

    /** One write per event, so concurrent events never mix and a crash cuts at most one. */
    private void write(String text) {
        try {
            out.write(text.getBytes(UTF_8));
            out.flush();
        } catch (IOException ex) {
            throw new AuditLogException("Cannot write to the audit log " + file + ": " + FileErrors.reason(ex), ex);
        }
    }

    private static boolean endsWithPartialLine(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0) {
                return false;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(size - 1).read(last);
            return last.get(0) != '\n';
        } catch (NoSuchFileException ex) {
            return false;
        }
    }
}
