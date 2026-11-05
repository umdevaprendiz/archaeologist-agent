package com.archaeologist.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * Loads the {@link ModelCatalog}: the copy of {@code config/models.yaml} bundled with the agent, or a
 * file the user points to. Uses the same binding rules as {@code application.yml} (so {@code min-memory-gb}
 * and {@code min_memory_gb} both work), rejects unknown keys and checks every value.
 */
@Component
public class ModelCatalogLoader {

    /** Class path location of the bundled catalog; the build copies it from {@code config/models.yaml}. */
    static final String BUNDLED = "models.yaml";

    private final SpringValidatorAdapter validator;

    public ModelCatalogLoader(jakarta.validation.Validator validator) {
        this.validator = new SpringValidatorAdapter(validator);
    }

    /**
     * Loads the catalog from {@code file}, or the bundled one when {@code file} is {@code null}.
     * Messages show the path as given, never expanded to an absolute path.
     *
     * @throws ConfigurationException if the file is missing or the catalog is invalid
     */
    public ModelCatalog load(Path file) {
        if (file == null) {
            return load(new ClassPathResource(BUNDLED), "bundled model catalog");
        }
        if (!Files.exists(file)) {
            throw new ConfigurationException("Model catalog not found: " + file);
        }
        if (!Files.isRegularFile(file)) {
            throw new ConfigurationException("Model catalog is not a file: " + file);
        }
        return load(new PathResource(file), "model catalog '" + file + "'");
    }

    private ModelCatalog load(Resource resource, String description) {
        List<PropertySource<?>> sources = read(resource, description);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        // Same order as Spring Boot's own binder: values are checked after unknown keys are rejected
        BindHandler handler = new ValidationBindHandler(new NoUnboundElementsBindHandler(BindHandler.DEFAULT), validator);
        try {
            return binder.bind(ConfigurationPropertyName.EMPTY, Bindable.of(ModelCatalog.class), handler)
                    .orElseThrow(() -> new ConfigurationException("The " + description + " is empty"));
        } catch (BindException ex) {
            throw new ConfigurationException("Invalid " + description + ": " + ConfigurationErrors.describe(ex), ex);
        }
    }

    private static List<PropertySource<?>> read(Resource resource, String description) {
        try {
            return new YamlPropertySourceLoader().load(description, resource);
        } catch (MarkedYAMLException ex) {
            throw new ConfigurationException("Invalid " + description + ": not valid YAML, " + yamlProblem(ex), ex);
        } catch (IOException | RuntimeException ex) {
            throw new ConfigurationException("Cannot read the " + description + ": " + ex.getMessage(), ex);
        }
    }

    private static String yamlProblem(MarkedYAMLException ex) {
        Mark mark = ex.getProblemMark();
        String where = mark == null ? "" : " (line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1) + ")";
        return ex.getProblem() + where;
    }
}
