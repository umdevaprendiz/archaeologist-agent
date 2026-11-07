package com.archaeologist.cli;

import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/** The top-level {@code archaeologist} command. It does nothing on its own: the work is in the subcommands. */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(
        name = "archaeologist",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Finds the riskiest code of a legacy Java system, modernizes it on separate branches "
                + "and proves with tests that its behavior did not change. Runs 100%% locally.",
        subcommands = {HotspotsCommand.class, RunCommand.class, ScoutCommand.class, SampleCommand.class, HelpCommand.class})
public class ArchaeologistCommand implements Callable<Integer> {

    static final String STACKTRACE_OPTION = "--stacktrace";

    @Spec
    private CommandSpec spec;

    // Read by ErrorReporter from the parse result; inherited, so it also works after a subcommand
    @Option(names = STACKTRACE_OPTION, scope = ScopeType.INHERIT,
            description = "Print the full stack trace of unexpected errors.")
    private boolean stacktrace;

    /** Without a subcommand there is nothing to do: show the usage and fail. */
    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getErr());
        return ExitCode.USAGE.code();
    }
}
