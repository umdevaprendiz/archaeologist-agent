package com.archaeologist.cli;

import java.nio.file.Path;
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

    private final RunLauncher runLauncher;

    @Spec
    private CommandSpec spec;

    @Mixin
    private RepositoryParameter repository;

    public HotspotsCommand(RunLauncher runLauncher) {
        this.runLauncher = runLauncher;
    }

    @Override
    public Integer call() {
        Path repo = repository.requireGitRepository();
        return runLauncher.launch(spec.name(), repo, spec.commandLine().getOut(), run -> {
            throw CliException.notImplemented(spec.name(), "feature PRI-02");
        });
    }
}
