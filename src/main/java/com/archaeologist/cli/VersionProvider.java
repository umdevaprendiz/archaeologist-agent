package com.archaeologist.cli;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import picocli.CommandLine.IVersionProvider;

/**
 * Text of {@code --version}: the version comes from the build info written by Maven. Builds that
 * skip Maven (some IDE runs) have no build info and show "development build".
 */
@Component
public class VersionProvider implements IVersionProvider {

    private final ObjectProvider<BuildProperties> buildProperties;

    public VersionProvider(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Override
    public String[] getVersion() {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "development build";
        return new String[] {"Code Archaeologist " + version, "Java " + Runtime.version()};
    }
}
