package com.acme.transfer.config;

import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

  public static final String HEADER_NAME = "X-Correlation-Id";
  public static final String CONTEXT_KEY = "correlationId";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String requestCorrelationId = exchange.getRequest().getHeaders().getFirst(HEADER_NAME);

    final String correlationId =
        requestCorrelationId == null || requestCorrelationId.isBlank()
            ? UUID.randomUUID().toString()
            : requestCorrelationId;

    ServerHttpRequest request = exchange.getRequest().mutate()
        .header(HEADER_NAME, correlationId)
        .build();

    ServerWebExchange mutatedExchange = exchange.mutate()
        .request(request)
        .build();

    mutatedExchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);

    return chain.filter(mutatedExchange)
        .contextWrite(context -> context.put(CONTEXT_KEY, correlationId));
  }
}
