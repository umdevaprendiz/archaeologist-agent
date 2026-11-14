package com.archaeologist.excavator;

import com.archaeologist.config.ArchaeologistProperties;
import com.archaeologist.git.GitException;
import com.archaeologist.git.IsolatedGit;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads the Git history of the last {@code archaeologist.history.years} years: every commit of the current
 * branch, with its author (as a number), date, message, and the lines added and removed in each file.
 *
 * <p>Read-only: it runs {@code git log} and {@code git rev-parse}, which never change the repository, with
 * nothing from the user's setup and no command from the repository ({@link IsolatedGit}). Renamed files are
 * detected ({@code -M}) and the first commit is compared with an empty tree ({@code --root}) whatever the
 * repository's configuration says, so the same history always gives the same numbers.
 *
 * <p>The period starts at midnight UTC, {@code years} years before today, and covers the commits whose
 * author date falls in it. A commit rebased or imported later keeps the date its author made the change.
 */
@Component
public class GitLogReader {

    /** A large legacy repository can take minutes: only a Git that hangs should hit this. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final int years;
    private final Clock clock;

    @Autowired
    public GitLogReader(ArchaeologistProperties properties) {
        this(properties.history().years(), Clock.systemUTC());
    }

    GitLogReader(int years, Clock clock) {
        if (years < 1) {
            throw new IllegalArgumentException("years must be at least 1");
        }
        this.years = years;
        this.clock = clock;
    }

    /** How many years of history are read. */
    public int years() {
        return years;
    }

    /**
     * Reads the history of {@code repository}, the root folder of a Git repository.
     *
     * @throws GitException if Git is missing, finds no repository there, refuses to read it or fails
     */
    public GitHistory read(Path repository) {
        Instant since = LocalDate.now(clock).minusYears(years).atStartOfDay(ZoneOffset.UTC).toInstant();
        try (IsolatedGit git = new IsolatedGit()) {
            // A repository with no commits yet has no history, and 'git log' would fail on it
            if (!git.test(repository, "rev-parse", "--verify", "--quiet", "HEAD^{commit}")) {
                return new GitHistory(since, false, List.of());
            }
            boolean shallow = Boolean.parseBoolean(git.run(repository, "rev-parse", "--is-shallow-repository").strip());
            List<Commit> commits = git.read(repository, TIMEOUT, output -> new GitLogParser(output).parse(),
                    "log", "--no-merges", "--root", "-M", "-z", "--numstat", "--no-ext-diff", "--no-textconv",
                    "--no-color", "--encoding=UTF-8", "--format=" + GitLogParser.FORMAT,
                    // Git filters by commit date, which normally comes on or after the author date. A second
                    // earlier keeps the commits made at the very start; the exact filter, on the author date,
                    // comes next
                    "--since=@" + (since.getEpochSecond() - 1), "HEAD", "--");
            return new GitHistory(since, shallow,
                    commits.stream().filter(commit -> !commit.date().isBefore(since)).toList());
        }
    }
}
