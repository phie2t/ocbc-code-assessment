package com.acme.transfer.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.transfer.dto.TransferRequest;
import com.acme.transfer.dto.TransferResource;
import com.acme.transfer.repository.IdempotencyEntity;
import com.acme.transfer.repository.TransferEntity;
import com.acme.transfer.service.IdempotencyService;
import com.acme.transfer.service.TransferService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class TransferControllerTest {

  @Test
  void concurrentRequestsWithSameIdempotencyKeyCreateOnlyOneTransfer() {
    TransferService transferService = mock(TransferService.class);
    IdempotencyService idempotencyService = mock(IdempotencyService.class);

    TransferController controller =
        new TransferController(transferService, idempotencyService);

    String idempotencyKey = "test-key-105";

    TransferRequest request = new TransferRequest(
        "2000000001",
        "2000000002",
        "150.00",
        "USD",
        "INC-105 test");

    AtomicBoolean claimed = new AtomicBoolean(false);

    TransferEntity transfer = new TransferEntity(
        "transfer-1",
        "COMPLETED",
        null,
        request.sourceAccount(),
        request.destinationAccount(),
        new BigDecimal("150.00"),
        "USD",
        new BigDecimal("2437500.00"),
        "IDR",
        new BigDecimal("16250.00"),
        "CT1",
        "INC-105 test",
        Instant.now(),
        Instant.now());

    when(idempotencyService.claim(eq(idempotencyKey), eq(request)))
        .thenAnswer(invocation -> Mono.just(claimed.compareAndSet(false, true)));

    TransferResource transferResource = TransferResource.from(transfer);

    IdempotencyEntity existing = new IdempotencyEntity(
        idempotencyKey,
        Integer.toHexString(request.hashCode()),
        transfer.transferId(),
        201,
        null,
        Instant.now());

    when(idempotencyService.findExisting(idempotencyKey))
        .thenReturn(Mono.just(existing));

    when(idempotencyService.readResponse(existing))
        .thenReturn(transferResource);

    when(transferService.createTransfer(request))
        .thenReturn(Mono.just(transfer));

    when(idempotencyService.save(
        eq(idempotencyKey),
        eq(request),
        eq(201),
        any(TransferResource.class)))
        .thenReturn(Mono.empty());

    var responses = Flux.range(0, 5)
        .flatMap(i -> controller.createTransfer(idempotencyKey, request))
        .collectList()
        .block();

    assertEquals(5, responses.size());

    verify(transferService, times(1)).createTransfer(request);
  }

  @Test
  void reusedIdempotencyKeyWithDifferentRequestReturnsUnprocessableEntity() {
    TransferService transferService = mock(TransferService.class);
    IdempotencyService idempotencyService = mock(IdempotencyService.class);

    TransferController controller =
        new TransferController(transferService, idempotencyService);

    String idempotencyKey = "test-key-111";

    TransferRequest originalRequest = new TransferRequest(
        "2000000001",
        "2000000002",
        "150.00",
        "USD",
        "original request");

    TransferRequest differentRequest = new TransferRequest(
        "2000000001",
        "2000000002",
        "200.00",
        "USD",
        "different request");

    IdempotencyEntity existing = new IdempotencyEntity(
        idempotencyKey,
        Integer.toHexString(originalRequest.hashCode()),
        "transfer-1",
        201,
        "{\"transferId\":\"transfer-1\"}",
        Instant.now());

    when(idempotencyService.claim(eq(idempotencyKey), eq(differentRequest)))
        .thenReturn(Mono.just(false));

    when(idempotencyService.findExisting(idempotencyKey))
        .thenReturn(Mono.just(existing));

    ApiException exception = org.junit.jupiter.api.Assertions.assertThrows(
        ApiException.class,
        () -> controller.createTransfer(idempotencyKey, differentRequest).block());

    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatus());
    assertEquals("IDEMPOTENCY_KEY_REUSED", exception.getCode());
  }

  @Test
  void idempotencyRequestStillProcessingReturnsConflict() {
    TransferService transferService = mock(TransferService.class);
    IdempotencyService idempotencyService = mock(IdempotencyService.class);

    TransferController controller =
        new TransferController(transferService, idempotencyService);

    String idempotencyKey = "test-key-111-processing";

    TransferRequest request = new TransferRequest(
        "2000000001",
        "2000000002",
        "150.00",
        "USD",
        "still processing");

    IdempotencyEntity existing = new IdempotencyEntity(
        idempotencyKey,
        Integer.toHexString(request.hashCode()),
        null,
        null,
        null,
        Instant.now());

    when(idempotencyService.claim(eq(idempotencyKey), eq(request)))
        .thenReturn(Mono.just(false));

    when(idempotencyService.findExisting(idempotencyKey))
        .thenReturn(Mono.just(existing));

    ApiException exception = org.junit.jupiter.api.Assertions.assertThrows(
        ApiException.class,
        () -> controller.createTransfer(idempotencyKey, request).block());

    assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    assertEquals("REQUEST_IN_PROGRESS", exception.getCode());
  }
}
