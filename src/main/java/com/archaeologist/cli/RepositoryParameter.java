package com.archaeologist.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Parameters;

/** The {@code <repo>} parameter shared by the commands that work on a repository. */
public class RepositoryParameter {

    @Parameters(index = "0", paramLabel = "<repo>",
            description = "Path to the root folder of the Git repository.")
    private Path path;

    /**
     * Returns the repository path after checking that it is the root of a Git repository.
     * Messages echo the path as the user typed it, never expanded to an absolute path.
     */
    public Path requireGitRepository() {
        if (!Files.exists(path)) {
            throw invalid("Repository not found: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw invalid("Not a folder: " + path);
        }
        // ".git" is a folder in a normal clone and a file in a linked worktree
        if (!Files.exists(path.resolve(".git"))) {
            throw invalid("Not a Git repository (no .git found): " + path);
        }
        return path;
    }

    private static CliException invalid(String message) {
        return new CliException(ExitCode.INVALID_REPOSITORY, message);
    }
}
