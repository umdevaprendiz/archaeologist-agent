package com.archaeologist.cli;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * Lets picocli create commands through Spring, so they can receive the agents as dependencies.
 * Classes that are not beans (picocli's help command, mixins, collections) use picocli's default factory.
 */
@Component
class SpringCommandFactory implements IFactory {

    private final ApplicationContext context;

    SpringCommandFactory(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public <K> K create(Class<K> type) throws Exception {
        K bean = context.getBeanProvider(type).getIfUnique();
        return bean != null ? bean : CommandLine.defaultFactory().create(type);
    }
}
