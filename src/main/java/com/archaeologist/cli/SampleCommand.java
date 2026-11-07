package com.archaeologist.cli;

import com.archaeologist.sample.SampleException;
import com.archaeologist.sample.SampleRepository;
import com.archaeologist.sample.SampleRepository.Summary;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code archaeologist sample <folder>}: a small legacy repository to try the agent on. */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(
        name = "sample",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Creates a small legacy Java repository to try the agent on: an order system in the style of "
                + "Java 6/7 with six years of scripted Git history (a god class, bug fixes, renamed files, JDBC, "
                + "Date and Random). Its authors are fictional.")
public class SampleCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<folder>",
            description = "Folder to create the repository in. It must not exist yet, or be empty.")
    private Path folder;

    @Override
    public Integer call() {
        Summary summary;
        try {
            summary = SampleRepository.create(folder, LocalDate.now(ZoneOffset.UTC));
        } catch (SampleException ex) {
            throw new CliException(ExitCode.FAILURE, ex.getMessage());
        }
        PrintWriter out = spec.commandLine().getOut();
        out.println("Created a sample legacy repository in " + folder + ": " + summary.commits() + " commits over "
                + summary.years() + " years.");
        out.println("Try it: archaeologist hotspots " + folder);
        return ExitCode.OK.code();
    }
}
