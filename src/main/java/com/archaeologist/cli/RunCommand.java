package com.archaeologist.cli;

import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

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

    @Mixin
    private RepositoryParameter repository;

    @Override
    public Integer call() {
        repository.requireGitRepository();
        throw CliException.notImplemented("run", "feature ORC-04");
    }
}
