package com.archaeologist.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The settings, bound from the real application.yml, and how invalid values are reported. */
class ArchaeologistPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(SettingsConfiguration.class, ModelCatalogLoader.class);

    private final List<String> systemProperties = new ArrayList<>();

    @TempDir
    Path tempDir;

    @Test
    void defaultsComeFromApplicationYml() {
        runner.run(context -> {
            ArchaeologistProperties properties = context.getBean(ArchaeologistProperties.class);
            assertThat(properties.history().years()).isEqualTo(5);
            assertThat(properties.riskWeights().fixes()).isEqualTo(1.0);
            assertThat(properties.riskWeights().authors()).isEqualTo(0.5);
            assertThat(properties.retry().maxAttempts()).isEqualTo(3);
            assertThat(properties.quality().minLineCoveragePercent()).isEqualTo(80);
            assertThat(properties.quality().minMutationScorePercent()).isEqualTo(60);
            assertThat(properties.output().folder()).isEqualTo(Path.of("archaeologist-output"));
            assertThat(properties.modelCatalog()).isNull();
        });
    }

    @Test
    void settingsCanBeOverridden() {
        runner.withPropertyValues("archaeologist.history.years=3", "archaeologist.risk-weights.fixes=2.5")
                .run(context -> {
                    ArchaeologistProperties properties = context.getBean(ArchaeologistProperties.class);
                    assertThat(properties.history().years()).isEqualTo(3);
                    assertThat(properties.riskWeights().fixes()).isEqualTo(2.5);
                });
    }

    @Test
    void bundledModelCatalogIsLoadedByDefault() {
        runner.run(context -> assertThat(context.getBean(ModelCatalog.class).profiles())
                .containsOnlyKeys(MachineProfile.values()));
    }

    @Test
    void modelCatalogCanBeAFile() throws IOException {
        Path file = Files.writeString(tempDir.resolve("models.yaml"),
                Files.readString(Path.of("config", "models.yaml")).replace("fixed-model: ", "fixed-model: custom-"));

        runner.withPropertyValues("archaeologist.model-catalog=" + file)
                .run(context -> assertThat(context.getBean(ModelCatalog.class).fixedModel()).startsWith("custom-"));
    }

    @Test
    void pathsStayAsWritten() {
        runner.withPropertyValues("archaeologist.output.folder=reports").run(context -> assertThat(
                context.getBean(ArchaeologistProperties.class).output().folder()).isEqualTo(Path.of("reports")));
    }

    @Test
    void modelCatalogPathIsNotLookedUpOnTheClassPath() {
        // "models.yaml" also names the bundled catalog on the class path: it must be read as a plain file path
        assertError("archaeologist.model-catalog=models.yaml", "Model catalog not found: models.yaml");
    }

    @Test
    void valueBelowTheMinimumIsReported() {
        assertError("archaeologist.history.years=0",
                "Invalid configuration: archaeologist.history.years must be at least 1 (was 0)");
    }

    @Test
    void percentAbove100IsReported() {
        assertError("archaeologist.quality.min-line-coverage-percent=101",
                "Invalid configuration: archaeologist.quality.min-line-coverage-percent must be between 0 and 100 (was 101)");
    }

    @Test
    void negativeWeightIsReported() {
        assertError("archaeologist.risk-weights.authors=-1",
                "Invalid configuration: archaeologist.risk-weights.authors must be 0 or more (was -1.0)");
    }

    @Test
    void valueOfTheWrongTypeIsReported() {
        assertError("archaeologist.retry.max-attempts=three",
                "Invalid configuration: archaeologist.retry.max-attempts has an invalid value 'three' (expected a whole number)");
    }

    @Test
    void unknownSettingIsReported() {
        assertError("archaeologist.history.yaers=3",
                "Invalid configuration: archaeologist.history.yaers is not a known setting");
    }

    // Users override settings with system properties and environment variables. Spring Boot's own
    // unknown-settings check skips both sources, so these tests go through them, not withPropertyValues.

    @Test
    void systemPropertiesOverrideSettings() {
        systemProperty("archaeologist.history.years", "2");
        systemProperty("archaeologist.retry.maxAttempts", "4");

        runner.run(context -> {
            ArchaeologistProperties properties = context.getBean(ArchaeologistProperties.class);
            assertThat(properties.history().years()).isEqualTo(2);
            assertThat(properties.retry().maxAttempts()).isEqualTo(4);
        });
    }

    @Test
    void unknownSystemPropertiesAreReported() {
        systemProperty("archaeologist.histroy.years", "3");
        systemProperty("archaeologist.retry.max-atempts", "5");

        assertError(runner, "Invalid configuration: archaeologist.histroy.years, archaeologist.retry.max-atempts "
                + "are not known settings");
    }

    @Test
    void environmentVariablesOverrideSettings() {
        runner(environment("ARCHAEOLOGIST_RETRY_MAXATTEMPTS=4", "ARCHAEOLOGIST_RISKWEIGHTS_FIXES=2.5"))
                .run(context -> {
                    ArchaeologistProperties properties = context.getBean(ArchaeologistProperties.class);
                    assertThat(properties.retry().maxAttempts()).isEqualTo(4);
                    assertThat(properties.riskWeights().fixes()).isEqualTo(2.5);
                });
    }

    @Test
    void environmentVariablesWithUnderscoresForDashesStayValid() {
        // Spring also accepts this spelling, so the check must not reject it
        runner(environment("ARCHAEOLOGIST_RETRY_MAX_ATTEMPTS=4", "ARCHAEOLOGIST_RISK_WEIGHTS_FIXES=2.5"))
                .run(context -> {
                    ArchaeologistProperties properties = context.getBean(ArchaeologistProperties.class);
                    assertThat(properties.retry().maxAttempts()).isEqualTo(4);
                    assertThat(properties.riskWeights().fixes()).isEqualTo(2.5);
                });
    }

    @Test
    void unknownEnvironmentVariableIsReportedAsWritten() {
        assertError(runner(environment("ARCHAEOLOGIST_RETRY_MAXATEMPTS=5")),
                "Invalid configuration: ARCHAEOLOGIST_RETRY_MAXATEMPTS is not a known setting");
    }

    @Test
    void otherEnvironmentVariablesAreIgnored() {
        runner(environment("JAVA_HOME=jdk", "MAVEN_OPTS=-Xmx1g", "SPRING_TASK_EXECUTION_POOL_SIZE=2"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void messagesAreInEnglishWhateverTheLocale() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("pt-BR"));
        try {
            assertError("archaeologist.history.years=0",
                    "Invalid configuration: archaeologist.history.years must be at least 1 (was 0)");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private void assertError(String property, String message) {
        assertError(runner.withPropertyValues(property), message);
    }

    private static void assertError(ApplicationContextRunner runner, String message) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(ConfigurationErrors.find(context.getStartupFailure())).contains(message);
        });
    }

    /** Sets a real system property, removed after the test. */
    private void systemProperty(String name, String value) {
        System.setProperty(name, value);
        systemProperties.add(name);
    }

    @AfterEach
    void clearSystemProperties() {
        systemProperties.forEach(System::clearProperty);
    }

    /** Environment variables in place of the machine's, under the name Spring Boot gives that source. */
    private static SystemEnvironmentPropertySource environment(String... variables) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String variable : variables) {
            String[] parts = variable.split("=", 2);
            values.put(parts[0], parts[1]);
        }
        return new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, values);
    }

    private ApplicationContextRunner runner(SystemEnvironmentPropertySource environment) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .replace(environment.getName(), environment));
    }
}
