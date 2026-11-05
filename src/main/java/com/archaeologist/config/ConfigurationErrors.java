package com.archaeologist.config;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationErrors;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/**
 * Turns configuration errors into short messages for the user: the setting, by its name in the YAML
 * files (or as the user wrote it, for an unknown one), and what is wrong with it. Never shows where the
 * value came from: Spring's origins hold absolute paths.
 */
public final class ConfigurationErrors {

    private ConfigurationErrors() {
    }

    /** The message of the first configuration error in {@code failure} or its causes, if any. */
    public static Optional<String> find(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConfigurationException configuration) {
                return Optional.of(configuration.getMessage());
            }
            if (cause instanceof BindException bind) {
                return Optional.of("Invalid configuration: " + describe(bind));
            }
        }
        return Optional.empty();
    }

    /** What went wrong while binding settings, e.g. {@code archaeologist.history.years must be at least 1 (was 0)}. */
    static String describe(BindException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof BindValidationException validation) {
            ValidationErrors errors = validation.getValidationErrors();
            return errors.getAllErrors().stream()
                    .map(error -> describe(errors.getName().toString(), error))
                    .collect(Collectors.joining("; "));
        }
        if (cause instanceof UnboundConfigurationPropertiesException unbound) {
            return unknownSettings(unbound.getUnboundProperties());
        }
        String name = ex.getName().toString();
        if (cause instanceof ConversionFailedException failed) {
            // The failed value may be a map key (e.g. an unknown profile name), not the property's value
            return invalidValue(name, failed.getValue(), failed.getTargetType().getObjectType());
        }
        if (cause instanceof ConversionException) {
            // No conversion at all, e.g. a list where a single value belongs
            ConfigurationProperty property = ex.getProperty();
            return invalidValue(name, property == null ? null : property.getValue(),
                    ex.getTarget().getBoxedType().resolve(Object.class));
        }
        String reason = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        return name.isEmpty() ? reason : name + ": " + reason;
    }

    private static String invalidValue(String name, Object value, Class<?> expectedType) {
        String shown = value == null ? "" : " '" + value + "'";
        return name + " has an invalid value" + shown + " (expected " + expected(expectedType) + ")";
    }

    private static String describe(String objectName, ObjectError error) {
        if (!(error instanceof FieldError field)) {
            return objectName + " " + error.getDefaultMessage();
        }
        String name = join(objectName, DataObjectPropertyName.toDashedForm(field.getField()));
        Object value = field.getRejectedValue();
        String was = value instanceof Number || value instanceof Boolean ? " (was " + value + ")" : "";
        return name + " " + field.getDefaultMessage() + was;
    }

    private static String unknownSettings(Set<ConfigurationProperty> properties) {
        List<String> names = properties.stream().map(ConfigurationErrors::nameAsWritten).distinct().sorted().toList();
        return String.join(", ", names) + (names.size() == 1 ? " is not a known setting" : " are not known settings");
    }

    /**
     * The setting's name as the user wrote it, so the user can find it: {@code ARCHAEOLOGIST_RETRY_MAXATEMPTS}
     * for an environment variable, rather than Spring's {@code archaeologist.retry.maxatempts}. Only the name:
     * the rest of the origin may hold an absolute path.
     */
    static String nameAsWritten(ConfigurationProperty property) {
        return property.getOrigin() instanceof PropertySourceOrigin origin
                ? origin.getPropertyName()
                : property.getName().toString();
    }

    private static String expected(Class<?> type) {
        if (type == Integer.class || type == Long.class) {
            return "a whole number";
        }
        if (type == Double.class || type == Float.class) {
            return "a number";
        }
        if (type == Boolean.class) {
            return "true or false";
        }
        if (type == Path.class) {
            return "a file path";
        }
        if (type.isEnum()) {
            return "one of " + Arrays.stream(type.getEnumConstants())
                    .map(constant -> constant.toString().toLowerCase(Locale.ROOT))
                    .collect(Collectors.joining(", "));
        }
        return "a " + type.getSimpleName();
    }

    private static String join(String parent, String child) {
        return parent.isEmpty() ? child : parent + "." + child;
    }
}
