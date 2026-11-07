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
        return new String[] {product(), java()};
    }

    /** On one line, for the audit log: {@code Code Archaeologist 0.1.0 on Java 21.0.5+11-LTS}. */
    public String summary() {
        return product() + " on " + java();
    }

    private String product() {
        BuildProperties build = buildProperties.getIfAvailable();
        return "Code Archaeologist " + (build != null ? build.getVersion() : "development build");
    }

    private static String java() {
        return "Java " + Runtime.version();
    }
}
