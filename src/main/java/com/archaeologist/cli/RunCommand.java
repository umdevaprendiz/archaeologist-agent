package com.archaeologist.cli;

import com.archaeologist.run.RunFolders;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code archaeologist run <repo>}: the whole cycle, with no human input. */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Runs the whole cycle on a repository with no human input: finds the hotspots, "
                + "writes characterization tests, modernizes the code on archaeologist/* branches and "
                + "verifies it. The main branch is never touched.")
public class RunCommand implements Callable<Integer> {

    private final RunFolders runFolders;

    @Spec
    private CommandSpec spec;

    @Mixin
    private RepositoryParameter repository;

    public RunCommand(RunFolders runFolders) {
        this.runFolders = runFolders;
    }

    @Override
    public Integer call() {
        repository.requireGitRepository();
        RunStarter.start(runFolders, spec.commandLine().getOut());
        throw CliException.notImplemented("run", "feature ORC-04");
    }
}
