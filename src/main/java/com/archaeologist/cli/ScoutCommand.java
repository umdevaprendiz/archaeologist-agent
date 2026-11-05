package com.archaeologist.cli;

import java.util.concurrent.Callable;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** {@code archaeologist scout}: hardware detection and model choice, on their own. */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
@Command(
        name = "scout",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Detects the hardware, then picks, downloads and calibrates the local model that fits it.")
public class ScoutCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        throw CliException.notImplemented("scout", "features SCT-01 to SCT-05");
    }
}
