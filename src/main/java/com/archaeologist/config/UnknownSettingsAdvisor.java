package com.archaeologist.config;

import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Rejects unknown {@code archaeologist.*} settings in system properties and environment variables.
 *
 * <p>{@code ignoreUnknownFields = false} only covers the YAML files: Spring Boot skips system properties and
 * environment variables, because a machine may hold variables that belong to something else. Yet those two
 * are how users override settings, so without this check a typo such as {@code ARCHAEOLOGIST_RETRY_MAXATEMPTS}
 * would be silently ignored and the agent would run with the default.
 *
 * <p>Only names under {@code archaeologist.} are checked. A name is known when Spring read a setting from it,
 * so every spelling Spring accepts stays valid ({@code ARCHAEOLOGIST_RETRY_MAXATTEMPTS} and
 * {@code ARCHAEOLOGIST_RETRY_MAX_ATTEMPTS} both set {@code archaeologist.retry.max-attempts}).
 */
class UnknownSettingsAdvisor implements ConfigurationPropertiesBindHandlerAdvisor {

    private static final ConfigurationPropertyName PREFIX = ConfigurationPropertyName.of("archaeologist");

    /** The sources Spring Boot leaves out of its own unknown-settings check. */
    private static final Set<String> CHECKED_SOURCES = Set.of(
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);

    @Override
    public BindHandler apply(BindHandler bindHandler) {
        return new Handler(bindHandler);
    }

    /** Remembers the settings Spring bound, then compares them with what the user set. One instance per bind. */
    private static final class Handler extends AbstractBindHandler {

        private final Set<ConfigurationPropertyName> bound = new HashSet<>();

        Handler(BindHandler parent) {
            super(parent);
        }

        @Override
        public Object onSuccess(ConfigurationPropertyName name, Bindable<?> target, BindContext context,
                Object result) {
            bound.add(name);
            return super.onSuccess(name, target, context, result);
        }

        @Override
        public void onFinish(ConfigurationPropertyName name, Bindable<?> target, BindContext context, Object result)
                throws Exception {
            // Validation and Spring's own check run first
            super.onFinish(name, target, context, result);
            if (context.getDepth() == 0 && name.equals(PREFIX)) {
                rejectUnknown(context);
            }
        }

        private void rejectUnknown(BindContext context) {
            Set<ConfigurationProperty> unknown = new TreeSet<>();
            for (ConfigurationPropertySource source : context.getSources()) {
                if (source instanceof IterableConfigurationPropertySource iterable && isChecked(source)) {
                    Set<String> used = bound.stream().map(source::getConfigurationProperty).filter(Objects::nonNull)
                            .map(ConfigurationErrors::nameAsWritten).collect(toSet());
                    iterable.stream().filter(PREFIX::isAncestorOf).map(source::getConfigurationProperty)
                            .filter(Objects::nonNull)
                            .filter(property -> !used.contains(ConfigurationErrors.nameAsWritten(property)))
                            .forEach(unknown::add);
                }
            }
            if (!unknown.isEmpty()) {
                throw new UnboundConfigurationPropertiesException(unknown);
            }
        }

        private static boolean isChecked(ConfigurationPropertySource source) {
            return source.getUnderlyingSource() instanceof PropertySource<?> propertySource
                    && CHECKED_SOURCES.contains(propertySource.getName());
        }
    }
}
