package com.acme.transfer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acme.transfer.dto.TransferRequest;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "spring.r2dbc.pool.enabled=false"
})
@ActiveProfiles("test")
class IdempotencyServiceIntegrationTest {
  @Autowired
private Environment environment;
  @Autowired
  private ConnectionFactory connectionFactory;
  @Autowired
  private IdempotencyService idempotencyService;
  @Autowired
  private DatabaseClient databaseClient;

  @Test
  void canConnectToPostgres() {
    Integer result = databaseClient.sql("SELECT 1")
        .map(row -> row.get(0, Integer.class))
        .one()
        .block();

    assertEquals(1, result);
  }

  @Test
  void inspectConnectionFactory() {
    ConnectionFactoryMetadata metadata = connectionFactory.getMetadata();

    System.out.println("R2DBC ConnectionFactory: " + connectionFactory.getClass().getName());
    System.out.println("R2DBC driver: " + metadata.getName());
  }

  @Test
  void directR2dbcConnection() {
    ConnectionFactory connectionFactory =
      ConnectionFactories.get(
          "r2dbc:postgresql://transfers:transfers@127.0.0.1:15432/transfers");

    Integer result = Mono.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(connection.createStatement("SELECT 1").execute())
                .flatMap(resultSet ->
                    resultSet.map((row, metadata) -> row.get(0, Integer.class)))
                .single(),
            connection -> connection.close())
        .block();

    assertEquals(1, result);
  }

  @Test
  void inspectR2dbcConfiguration() {
    System.out.println("R2DBC URL = " +
        environment.getProperty("spring.r2dbc.url"));

    System.out.println("R2DBC username = " +
        environment.getProperty("spring.r2dbc.username"));

    System.out.println("R2DBC password configured = " +
        (environment.getProperty("spring.r2dbc.password") != null));
  }

  @Test
  void concurrentClaimsWithSameKeyOnlyOneSucceeds() throws Exception {
    TransferRequest request = new TransferRequest(
        "2000000001",
        "2000000002",
        "150.00",
        "USD",
        "INC-105 integration test");

    String key = "integration-test-105-" + System.nanoTime();

    int numberOfRequests = 5;

    var executor = Executors.newFixedThreadPool(numberOfRequests);
    var start = new CountDownLatch(1);

    var futures = List.of(
        executor.submit(() -> claim(key, request, start)),
        executor.submit(() -> claim(key, request, start)),
        executor.submit(() -> claim(key, request, start)),
        executor.submit(() -> claim(key, request, start)),
        executor.submit(() -> claim(key, request, start)));

    start.countDown();

    var results = futures.stream()
        .map(future -> {
          try {
            return future.get();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        })
        .toList();

    executor.shutdown();

    assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    assertEquals(4, results.stream().filter(result -> !result).count());
  }

  private boolean claim(
      String key,
      TransferRequest request,
      CountDownLatch start) {

    try {
      start.await();
      return Boolean.TRUE.equals(
          idempotencyService.claim(key, request).block());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
void directR2dbcConcurrentConnections() throws Exception {
  ConnectionFactory connectionFactory =
      ConnectionFactories.get(
          "r2dbc:postgresql://transfers:transfers@127.0.0.1:15432/transfers");

  int numberOfRequests = 5;

  var executor = Executors.newFixedThreadPool(numberOfRequests);
  var start = new CountDownLatch(1);

  var futures = List.of(
      executor.submit(() -> directQuery(connectionFactory, start)),
      executor.submit(() -> directQuery(connectionFactory, start)),
      executor.submit(() -> directQuery(connectionFactory, start)),
      executor.submit(() -> directQuery(connectionFactory, start)),
      executor.submit(() -> directQuery(connectionFactory, start)));

  start.countDown();

  var results = futures.stream()
      .map(future -> {
        try {
          return future.get();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      })
      .toList();

  executor.shutdown();

  assertEquals(5, results.size());
  assertEquals(List.of(1, 1, 1, 1, 1), results.stream().sorted().toList());
}

private int directQuery(
    ConnectionFactory connectionFactory,
    CountDownLatch start) {

  try {
    start.await();

    return Mono.usingWhen(
            connectionFactory.create(),
            connection -> Flux.from(
                    connection.createStatement("SELECT 1").execute())
                .flatMap(result ->
                    result.map((row, metadata) ->
                        row.get(0, Integer.class)))
                .single(),
            connection -> connection.close())
        .block();
  } catch (Exception e) {
    throw new RuntimeException(e);
  }
}
}