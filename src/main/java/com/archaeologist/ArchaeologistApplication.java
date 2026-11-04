package com.archaeologist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot application for the Code Archaeologist. Runs from the command line, with no web server. */
@SpringBootApplication
public class ArchaeologistApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchaeologistApplication.class, args);
    }
}
