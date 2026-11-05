package com.archaeologist.config;

import static com.archaeologist.config.MachineProfile.MEDIUM;
import static com.archaeologist.config.MachineProfile.STRONG;
import static com.archaeologist.config.MachineProfile.WEAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelCatalogLoaderTests {

    private static final String VALID = """
            fixed-model: model-a
            profiles:
              strong:
                min-memory-gb: 24
                candidates: [model-c]
              medium:
                min-memory-gb: 12
                candidates: [model-b]
              weak:
                min-memory-gb: 0
                candidates: [model-a, model-a-small]
            calibration:
              min-tokens-per-second: 8
              task: examples/RefactorLoop.java
              system-reserve-gb: 3
              context-reserve-gb: 2
            """;

    private static ValidatorFactory validatorFactory;

    private static ModelCatalogLoader loader;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void createLoader() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        loader = new ModelCatalogLoader(validatorFactory.getValidator());
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private Path catalog(String yaml) throws IOException {
        return Files.writeString(tempDir.resolve("models.yaml"), yaml);
    }

    private void assertError(Path file, String message) {
        assertThatThrownBy(() -> loader.load(file)).isInstanceOf(ConfigurationException.class).hasMessage(message);
    }

    @Test
    void bundledCatalogIsValid() {
        ModelCatalog catalog = loader.load(null);

        assertThat(catalog.fixedModel()).isNotBlank();
        assertThat(catalog.profiles()).containsOnlyKeys(MachineProfile.values());
        // A stronger profile needs more memory
        assertThat(catalog.profiles().get(WEAK).minMemoryGb()).isLessThan(catalog.profiles().get(MEDIUM).minMemoryGb());
        assertThat(catalog.profiles().get(MEDIUM).minMemoryGb()).isLessThan(catalog.profiles().get(STRONG).minMemoryGb());
    }

    @Test
    void bundledCatalogIsACopyOfConfigModelsYaml() {
        // The build copies config/models.yaml into the jar: this catches a stale copy
        assertThat(loader.load(null)).isEqualTo(loader.load(Path.of("config", "models.yaml")));
    }

    @Test
    void fileIsBoundToTypedValues() throws IOException {
        ModelCatalog catalog = loader.load(catalog(VALID));

        assertThat(catalog.fixedModel()).isEqualTo("model-a");
        assertThat(catalog.profiles().keySet()).containsExactly(WEAK, MEDIUM, STRONG);
        assertThat(catalog.profiles().get(WEAK).candidates()).containsExactly("model-a", "model-a-small");
        assertThat(catalog.profiles().get(STRONG).minMemoryGb()).isEqualTo(24.0);
        assertThat(catalog.calibration().minTokensPerSecond()).isEqualTo(8.0);
        assertThat(catalog.calibration().task()).isEqualTo("examples/RefactorLoop.java");
        assertThat(catalog.calibration().systemReserveGb()).isEqualTo(3.0);
        assertThat(catalog.calibration().contextReserveGb()).isEqualTo(2.0);
    }

    @Test
    void snakeCaseKeysAreAccepted() throws IOException {
        ModelCatalog catalog = loader.load(catalog(VALID.replace('-', '_')));

        assertThat(catalog.fixedModel()).isEqualTo("model_a");
        assertThat(catalog.calibration().systemReserveGb()).isEqualTo(3.0);
    }

    @Test
    void missingFileIsReported() {
        Path missing = tempDir.resolve("missing.yaml");

        assertError(missing, "Model catalog not found: " + missing);
    }

    @Test
    void folderIsReported() {
        assertError(tempDir, "Model catalog is not a file: " + tempDir);
    }

    @Test
    void unknownKeyIsReported() throws IOException {
        Path file = catalog(VALID.replace("    candidates: [model-b]", "    candidates: [model-b]\n    notes: fast"));

        assertError(file, "Invalid model catalog '" + file + "': profiles.medium.notes is not a known setting");
    }

    @Test
    void missingValueIsReported() throws IOException {
        Path file = catalog(VALID.replace("  system-reserve-gb: 3\n", ""));

        assertError(file, "Invalid model catalog '" + file + "': calibration.system-reserve-gb is missing");
    }

    @Test
    void emptyCandidateListIsReported() throws IOException {
        Path file = catalog(VALID.replace("[model-b]", "[]"));

        assertError(file, "Invalid model catalog '" + file + "': profiles.medium.candidates must list at least one model");
    }

    @Test
    void outOfRangeValueIsReported() throws IOException {
        Path file = catalog(VALID.replace("min-tokens-per-second: 8", "min-tokens-per-second: 0"));

        assertError(file, "Invalid model catalog '" + file
                + "': calibration.min-tokens-per-second must be greater than 0 (was 0.0)");
    }

    @Test
    void valueOfTheWrongTypeIsReported() throws IOException {
        Path file = catalog(VALID.replace("min-tokens-per-second: 8", "min-tokens-per-second: fast"));

        assertError(file, "Invalid model catalog '" + file
                + "': calibration.min-tokens-per-second has an invalid value 'fast' (expected a number)");
    }

    @Test
    void missingProfileIsReported() throws IOException {
        Path file = catalog(VALID.replace("  medium:\n    min-memory-gb: 12\n    candidates: [model-b]\n", ""));

        assertError(file, "Invalid model catalog '" + file
                + "': profiles must define weak, medium and strong (missing: medium)");
    }

    @Test
    void unknownProfileIsReported() throws IOException {
        Path file = catalog(VALID.replace("  medium:", "  huge:"));

        assertError(file, "Invalid model catalog '" + file
                + "': profiles has an invalid value 'huge' (expected one of weak, medium, strong)");
    }

    @Test
    void invalidYamlIsReported() throws IOException {
        Path file = catalog("fixed-model: [unclosed\nprofiles:\n");

        assertThatThrownBy(() -> loader.load(file)).isInstanceOf(ConfigurationException.class)
                .hasMessageStartingWith("Invalid model catalog '" + file + "': not valid YAML, ")
                .hasMessageContaining("(line ");
    }

    @Test
    void emptyFileIsReported() throws IOException {
        Path file = catalog("");

        assertError(file, "The model catalog '" + file + "' is empty");
    }
}
