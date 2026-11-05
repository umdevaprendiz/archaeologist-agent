package com.archaeologist.cli;

import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

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

    @Mixin
    private RepositoryParameter repository;

    @Override
    public Integer call() {
        repository.requireGitRepository();
        throw CliException.notImplemented("hotspots", "feature PRI-02");
    }
}
