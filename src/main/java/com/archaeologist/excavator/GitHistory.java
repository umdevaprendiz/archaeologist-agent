package com.archaeologist.excavator;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The commits the Excavator read: those of the current branch made since a given moment, newest first.
 * Merge commits are left out: what they bring in is already counted in the commits they merge.
 *
 * @param since   the start of the period read
 * @param shallow whether the repository is a shallow clone, so older commits may be missing
 * @param commits the commits, newest first
 */
public record GitHistory(Instant since, boolean shallow, List<Commit> commits) {

    public GitHistory {
        Objects.requireNonNull(since, "since");
        commits = List.copyOf(commits);
    }

    /** How many file changes the commits hold in all. */
    public int fileChanges() {
        return commits.stream().mapToInt(commit -> commit.files().size()).sum();
    }

    /** How many different people made the commits. */
    public long authors() {
        return commits.stream().mapToInt(Commit::authorId).distinct().count();
    }
}
