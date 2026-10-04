package com.acme.transfer.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.acme.transfer.config.CorrelationIdWebFilter;
import com.acme.transfer.dto.TransferRequest;
import com.acme.transfer.service.IdempotencyService;
import com.acme.transfer.service.TransferService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class ApiExceptionHandlerTest {

  @Test
  void validationErrorReturnsProblemJsonWithCorrelationId() {
    TransferController controller = new TransferController(
        org.mockito.Mockito.mock(TransferService.class),
        org.mockito.Mockito.mock(IdempotencyService.class));

    String correlationId = "test-correlation-111";

    WebTestClient client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .build();

    client.post()
        .uri("/api/v1/transfers")
        .header("X-Correlation-Id", correlationId)
        .header("Idempotency-Key", "test-key-111-http")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "sourceAccount": "invalid",
              "destinationAccount": "2000000002",
              "amount": "150.00",
              "currency": "USD",
              "description": "INC-111 validation test"
            }
            """)
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader().valueEquals("X-Correlation-Id", correlationId)
        .expectBody()
        .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
        .jsonPath("$.title").isEqualTo("Invalid request")
        .jsonPath("$.detail").isEqualTo("Invalid source account")
        .jsonPath("$.correlationId").isEqualTo(correlationId)
        .consumeWith(result -> {
          String body = new String(
              result.getResponseBody(), StandardCharsets.UTF_8);
          assertFalse(body.contains("Exception"));
          assertFalse(body.contains("stackTrace"));
        });
  }

  @Test
  void unexpectedExceptionReturnsGenericProblemWithoutInternalDetails() {
    TransferService transferService = org.mockito.Mockito.mock(TransferService.class);
    IdempotencyService idempotencyService =
        org.mockito.Mockito.mock(IdempotencyService.class);

    TransferController controller =
        new TransferController(transferService, idempotencyService);

    String correlationId = "test-correlation-500";

    TransferRequest request = new TransferRequest(
        "2000000001",
        "2000000002",
        "150.00",
        "USD",
        "INC-111 unexpected error test");

    org.mockito.Mockito.when(
        idempotencyService.claim("test-key-111-500", request))
        .thenReturn(reactor.core.publisher.Mono.just(true));

    org.mockito.Mockito.when(transferService.createTransfer(request))
        .thenReturn(reactor.core.publisher.Mono.error(
            new IllegalStateException("SECRET_INTERNAL_DATABASE_DETAILS")));

    WebTestClient client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .build();

    client.post()
        .uri("/api/v1/transfers")
        .header("X-Correlation-Id", correlationId)
        .header("Idempotency-Key", "test-key-111-500")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
                "sourceAccount": "2000000001",
                "destinationAccount": "2000000002",
                "amount": "150.00",
                "currency": "USD",
                "description": "INC-111 unexpected error test"
            }
            """)
        .exchange()
        .expectStatus().is5xxServerError()
        .expectHeader().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader().valueEquals("X-Correlation-Id", correlationId)
        .expectBody()
        .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
        .jsonPath("$.detail").isEqualTo("An unexpected error occurred.")
        .jsonPath("$.correlationId").isEqualTo(correlationId)
        .consumeWith(result -> {
            String body = new String(
                result.getResponseBody(), StandardCharsets.UTF_8);
            assertFalse(body.contains("SECRET_INTERNAL_DATABASE_DETAILS"));
            assertFalse(body.contains("IllegalStateException"));
            assertFalse(body.contains("stackTrace"));
        });
  }

  @Test
  void missingCorrelationIdGeneratesAndReturnsCorrelationId() {
    TransferController controller = new TransferController(
        org.mockito.Mockito.mock(TransferService.class),
        org.mockito.Mockito.mock(IdempotencyService.class));

    WebTestClient client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .build();

    client.post()
        .uri("/api/v1/transfers")
        .header("Idempotency-Key", "test-key-111-generated")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "sourceAccount": "invalid",
              "destinationAccount": "2000000002",
              "amount": "150.00",
              "currency": "USD",
              "description": "INC-111 generated correlation test"
            }
            """)
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader().valueMatches(
            "X-Correlation-Id",
            "^[0-9a-fA-F-]{36}$")
        .expectBody()
        .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
        .jsonPath("$.correlationId").isNotEmpty()
        .jsonPath("$.correlationId").value(value ->
            org.junit.jupiter.api.Assertions.assertEquals(
                value.toString().length(), 36));
  }

  @Test
  void transferNotFoundReturnsProblemJsonWithCorrelationId() {
    TransferService transferService =
        org.mockito.Mockito.mock(TransferService.class);
    IdempotencyService idempotencyService =
        org.mockito.Mockito.mock(IdempotencyService.class);

    org.mockito.Mockito.when(transferService.getTransfer("missing-transfer-111"))
        .thenReturn(reactor.core.publisher.Mono.empty());

    TransferController controller =
        new TransferController(transferService, idempotencyService);

    String correlationId = "test-correlation-404";

    WebTestClient client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .build();

    client.get()
        .uri("/api/v1/transfers/missing-transfer-111")
        .header("X-Correlation-Id", correlationId)
        .exchange()
        .expectStatus().isNotFound()
        .expectHeader().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader().valueEquals("X-Correlation-Id", correlationId)
        .expectBody()
        .jsonPath("$.code").isEqualTo("TRANSFER_NOT_FOUND")
        .jsonPath("$.title").isEqualTo("Transfer not found")
        .jsonPath("$.detail")
        .isEqualTo("Transfer missing-transfer-111 not found")
        .jsonPath("$.correlationId").isEqualTo(correlationId);
  }

  @Test
  void idempotencyKeyStillProcessingReturnsProblemJson() {
    TransferService transferService =
        org.mockito.Mockito.mock(TransferService.class);
    IdempotencyService idempotencyService =
        org.mockito.Mockito.mock(IdempotencyService.class);

    TransferRequest request = new TransferRequest(
        "2000000001",
        "2000000002",
        "150.00",
        "USD",
        "INC-111 processing test");

    org.mockito.Mockito.when(
        idempotencyService.claim("test-key-111-processing", request))
        .thenReturn(reactor.core.publisher.Mono.just(false));

    String requestHash = Integer.toHexString(request.hashCode());

    org.mockito.Mockito.when(
        idempotencyService.findExisting("test-key-111-processing"))
        .thenReturn(reactor.core.publisher.Mono.just(
            new com.acme.transfer.repository.IdempotencyEntity(
                "test-key-111-processing",
                requestHash,
                null,
                null,
                null,
                java.time.Instant.now())));

    TransferController controller =
        new TransferController(transferService, idempotencyService);

    String correlationId = "test-correlation-409";

    WebTestClient client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .build();

    client.post()
        .uri("/api/v1/transfers")
        .header("X-Correlation-Id", correlationId)
        .header("Idempotency-Key", "test-key-111-processing")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "sourceAccount": "2000000001",
              "destinationAccount": "2000000002",
              "amount": "150.00",
              "currency": "USD",
              "description": "INC-111 processing test"
            }
            """)
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectHeader().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader().valueEquals("X-Correlation-Id", correlationId)
        .expectBody()
        .jsonPath("$.code").isEqualTo("REQUEST_IN_PROGRESS")
        .jsonPath("$.title").isEqualTo("Request in progress")
        .jsonPath("$.detail")
        .isEqualTo("Idempotency request is still being processed")
        .jsonPath("$.correlationId").isEqualTo(correlationId);
  }

  @Test
  void reusedIdempotencyKeyReturnsProblemJson() {
    TransferService transferService =
        org.mockito.Mockito.mock(TransferService.class);
    IdempotencyService idempotencyService =
        org.mockito.Mockito.mock(IdempotencyService.class);

    TransferRequest originalRequest = new TransferRequest(
        "2000000001",
        "2000000002",
        "150.00",
        "USD",
        "INC-111 original request");

    TransferRequest differentRequest = new TransferRequest(
        "2000000001",
        "2000000003",
        "150.00",
        "USD",
        "INC-111 different request");

    String requestHash = Integer.toHexString(originalRequest.hashCode());

    org.mockito.Mockito.when(
        idempotencyService.claim("test-key-111-reused", differentRequest))
        .thenReturn(reactor.core.publisher.Mono.just(false));

    org.mockito.Mockito.when(
        idempotencyService.findExisting("test-key-111-reused"))
        .thenReturn(reactor.core.publisher.Mono.just(
            new com.acme.transfer.repository.IdempotencyEntity(
                "test-key-111-reused",
                requestHash,
                "transfer-111",
                201,
                "{\"transferId\":\"transfer-111\"}",
                java.time.Instant.now())));

    TransferController controller =
        new TransferController(transferService, idempotencyService);

    String correlationId = "test-correlation-422";

    WebTestClient client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .build();

    client.post()
        .uri("/api/v1/transfers")
        .header("X-Correlation-Id", correlationId)
        .header("Idempotency-Key", "test-key-111-reused")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "sourceAccount": "2000000001",
              "destinationAccount": "2000000003",
              "amount": "150.00",
              "currency": "USD",
              "description": "INC-111 different request"
            }
            """)
        .exchange()
        .expectStatus().isEqualTo(422)
        .expectHeader().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader().valueEquals("X-Correlation-Id", correlationId)
        .expectBody()
        .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REUSED")
        .jsonPath("$.title").isEqualTo("Unprocessable request")
        .jsonPath("$.detail")
        .isEqualTo(
            "Idempotency key has already been used for a different request.")
        .jsonPath("$.correlationId").isEqualTo(correlationId);
  }

  @Test
  void malformedJsonReturnsProblemJson() {
    TransferController controller = new TransferController(
        org.mockito.Mockito.mock(TransferService.class),
        org.mockito.Mockito.mock(IdempotencyService.class));

    String correlationId = "test-correlation-malformed";

    WebTestClient client = WebTestClient.bindToController(controller)
        .controllerAdvice(new ApiExceptionHandler())
        .webFilter(new CorrelationIdWebFilter())
        .build();

    client.post()
        .uri("/api/v1/transfers")
        .header("X-Correlation-Id", correlationId)
        .header("Idempotency-Key", "test-key-111-malformed")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("""
            {
              "sourceAccount": "2000000001",
              "destinationAccount":
            """)
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentTypeCompatibleWith(
            MediaType.APPLICATION_PROBLEM_JSON)
        .expectHeader().valueEquals("X-Correlation-Id", correlationId)
        .expectBody()
        .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
        .jsonPath("$.correlationId").isEqualTo(correlationId);
  }
}
