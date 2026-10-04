package com.acme.transfer.controller;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public Mono<ResponseEntity<ProblemDetail>> handleApiException(
      ApiException exception,
      org.springframework.web.server.ServerWebExchange exchange) {

    return Mono.just(buildProblem(
        exception.getStatus(),
        exception.getCode(),
        exception.getMessage(),
        exchange));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public Mono<ResponseEntity<ProblemDetail>> handleResponseStatusException(
      ResponseStatusException exception,
      org.springframework.web.server.ServerWebExchange exchange) {

    HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    String code = switch (status) {
      case NOT_FOUND -> "TRANSFER_NOT_FOUND";
      case CONFLICT -> "REQUEST_IN_PROGRESS";
      case BAD_REQUEST -> "VALIDATION_ERROR";
      default -> "INTERNAL_ERROR";
    };

    String detail = status.is5xxServerError()
        ? "An unexpected error occurred."
        : exception.getReason();

    if (detail == null || detail.isBlank()) {
      detail = status.getReasonPhrase();
    }

    if (status.is5xxServerError()) {
      log.error("Request failed with status {}", status.value(), exception);
    }

    return Mono.just(buildProblem(status, code, detail, exchange));
  }

  @ExceptionHandler(ServerWebInputException.class)
  public Mono<ResponseEntity<ProblemDetail>> handleInputException(
      ServerWebInputException exception,
      org.springframework.web.server.ServerWebExchange exchange) {

    return Mono.just(buildProblem(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Invalid request.",
        exchange));
  }

  @ExceptionHandler(Exception.class)
  public Mono<ResponseEntity<ProblemDetail>> handleUnexpectedException(
      Exception exception,
      org.springframework.web.server.ServerWebExchange exchange) {

    log.error("Unexpected request failure", exception);

    return Mono.just(buildProblem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "An unexpected error occurred.",
        exchange));
  }

  private ResponseEntity<ProblemDetail> buildProblem(
      HttpStatus status,
      String code,
      String detail,
      org.springframework.web.server.ServerWebExchange exchange) {

    String correlationId =
        exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");

    if (correlationId == null || correlationId.isBlank()) {
      correlationId = "unknown";
    }

    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(titleFor(status));
    problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId);

    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  private String titleFor(HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST -> "Invalid request";
      case NOT_FOUND -> "Transfer not found";
      case CONFLICT -> "Request in progress";
      case UNPROCESSABLE_ENTITY -> "Unprocessable request";
      default -> "Internal server error";
    };
  }
}
