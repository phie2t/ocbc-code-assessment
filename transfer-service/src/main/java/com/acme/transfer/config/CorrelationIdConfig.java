package com.acme.transfer.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

@Configuration
public class CorrelationIdConfig {

  @PostConstruct
  void configureContextPropagation() {
    ContextRegistry.getInstance()
        .registerThreadLocalAccessor(new Slf4jThreadLocalAccessor("correlationId"));
    Hooks.enableAutomaticContextPropagation();
  }
}
