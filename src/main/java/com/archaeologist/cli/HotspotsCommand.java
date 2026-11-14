package com.archaeologist.cli;

import com.archaeologist.audit.Agent;
import com.archaeologist.excavator.GitHistory;
import com.archaeologist.excavator.GitLogReader;
import com.archaeologist.git.GitException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code archaeologist hotspots <repo>}: the ranked hotspot report. Read-only, with no LLM. */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(
        name = "hotspots",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Ranks the riskiest classes of a repository from its Git history and code metrics. "
                + "Read-only: the repository is never changed.")
public class HotspotsCommand implements Callable<Integer> {

    static final String HISTORY_READ = "history-read";

    private final RunLauncher runLauncher;
    private final GitLogReader gitLogReader;

    @Spec
    private CommandSpec spec;

    @Mixin
    private RepositoryParameter repository;

    public HotspotsCommand(RunLauncher runLauncher, GitLogReader gitLogReader) {
        this.runLauncher = runLauncher;
        this.gitLogReader = gitLogReader;
    }

    @Override
    public Integer call() {
        Path repo = repository.requireGitRepository();
        PrintWriter out = spec.commandLine().getOut();
        return runLauncher.launch(spec.name(), repo, out, run -> {
            readHistory(repo, run, out);
            throw CliException.notImplemented(spec.name(), "feature PRI-02");
        });
    }

    private GitHistory readHistory(Path repo, StartedRun run, PrintWriter out) {
        GitHistory history;
        try {
            history = gitLogReader.read(repo);
        } catch (GitException ex) {
            if (ex.reason() == GitException.Reason.NOT_A_REPOSITORY) {
                throw new CliException(ExitCode.INVALID_REPOSITORY,
                        "Not a Git repository (its .git is missing or damaged): " + repo);
            }
            throw new CliException(ExitCode.FAILURE, ex.getMessage());
        }
        String since = DateTimeFormatter.ISO_LOCAL_DATE.format(history.since().atOffset(ZoneOffset.UTC));
        String found = count(history.commits().size(), "commit") + " by " + count(history.authors(), "author");
        String shallow = history.shallow() ? "; shallow clone: older commits may be missing" : "";
        run.audit().record(Agent.EXCAVATOR, HISTORY_READ,
                "Git history of the current branch since " + since + " (last " + count(gitLogReader.years(), "year")
                        + "), merge commits left out",
                found + ", " + count(history.fileChanges(), "file change") + shallow);
        out.println("Excavator: " + found + " since " + since + " (last " + count(gitLogReader.years(), "year") + ").");
        if (history.shallow()) {
            spec.commandLine().getErr().println("Warning: the repository is a shallow clone, so older commits may "
                    + "be missing. To read the whole history, run 'git fetch --unshallow' in it first.");
        }
        return history;
    }

    private static String count(long number, String noun) {
        return number + " " + noun + (number == 1 ? "" : "s");
    }
}
