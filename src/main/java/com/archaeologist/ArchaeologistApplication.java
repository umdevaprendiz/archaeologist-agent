package com.archaeologist;

import com.archaeologist.cli.ArchaeologistCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Spring Boot application for the Code Archaeologist. Runs from the command line, with no web server. */
@SpringBootApplication
public class ArchaeologistApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ArchaeologistApplication.class);
        // The arguments belong to the command line only. Spring must not read them as properties:
        // "--debug", for example, would switch on Spring Boot's debug mode.
        application.setAddCommandLineProperties(false);

        int exitCode;
        try (ConfigurableApplicationContext context = application.run(args)) {
            exitCode = context.getBean(ArchaeologistCli.class).execute(args);
        }
        System.exit(exitCode);
    }
}
