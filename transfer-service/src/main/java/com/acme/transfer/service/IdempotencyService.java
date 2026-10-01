package com.acme.transfer.service;

import com.acme.transfer.dto.TransferRequest;
import com.acme.transfer.dto.TransferResource;
import com.acme.transfer.repository.IdempotencyEntity;
import com.acme.transfer.repository.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

  private final IdempotencyRepository idempotencyRepository;
  private final R2dbcEntityTemplate template;
  private final ObjectMapper objectMapper;

  /** The stored response for this key, if the request was already processed. */
  public Mono<IdempotencyEntity> findExisting(String idempotencyKey) {
    return idempotencyRepository.findByIdempotencyKey(idempotencyKey).next();
  }

  public Mono<IdempotencyEntity> save(String idempotencyKey, TransferRequest request, int status,
                                    TransferResource response) {
    String body;
    try {
      body = objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException e) {
      return Mono.error(e);
    }
    return template.update(new IdempotencyEntity(idempotencyKey,
        Integer.toHexString(request.hashCode()), response.transferId(), status, body, Instant.now()));
  }

  public TransferResource readResponse(IdempotencyEntity entity) {
    try {
      return objectMapper.readValue(entity.responseBody(), TransferResource.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored response is not readable", e);
    }
  }

  public Mono<Boolean> claim(String idempotencyKey, TransferRequest request) {
    return template.getDatabaseClient()
        .sql("""
            INSERT INTO idempotency_keys (
                idempotency_key,
                request_hash,
                created_at
            )
            VALUES (:key, :requestHash, :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
        .bind("key", idempotencyKey)
        .bind("requestHash", Integer.toHexString(request.hashCode()))
        .bind("createdAt", Instant.now())
        .fetch()
        .rowsUpdated()
        .map(rows -> rows == 1);
  }
}
