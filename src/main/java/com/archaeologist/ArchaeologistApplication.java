package com.archaeologist;

import com.archaeologist.cli.ArchaeologistCli;
import com.archaeologist.cli.ErrorReporter;
import java.io.PrintWriter;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Spring Boot application for the Code Archaeologist. Runs from the command line, with no web server. */
@SpringBootApplication
public class ArchaeologistApplication {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** Starts the application, runs the command line and returns the process exit code. */
    static int run(String... args) {
        ConfigurableApplicationContext context;
        try {
            context = newApplication().run(args);
        } catch (RuntimeException ex) {
            // The settings and the model catalog are checked here: report like any other command error
            return ErrorReporter.reportStartupFailure(ex, args, new PrintWriter(System.err, true));
        }
        try (context) {
            return context.getBean(ArchaeologistCli.class).execute(args);
        }
    }

    static SpringApplication newApplication() {
        SpringApplication application = new SpringApplication(ArchaeologistApplication.class);
        // The arguments belong to the command line only. Spring must not read them as properties:
        // "--debug", for example, would switch on Spring Boot's debug mode.
        application.setAddCommandLineProperties(false);
        // Settings come from the bundled application.yml, environment variables and system properties,
        // never from an application.yml in the working folder: that folder may be the repository under
        // analysis, and its files are data, not settings.
        application.setDefaultProperties(Map.of("spring.config.location", "classpath:/"));
        return application;
    }
}
