package com.example.walletledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.walletledger.idempotency.CommandExecutor;
import com.example.walletledger.support.PostgresIntegrationTest;
import com.example.walletledger.wallet.application.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TransactionDeadlineIT extends PostgresIntegrationTest {
  @Autowired WalletService wallets;
  @Autowired CommandExecutor commands;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;

  @Test
  void statementTimeoutCancelsSqlButDoesNotBoundDeferredJdbcCommit() throws Exception {
    try (var connection = jdbc.getDataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET statement_timeout='100ms'");
      try {
        assertThatThrownBy(() -> statement.execute("SELECT pg_catalog.pg_sleep(2)"))
            .isInstanceOf(SQLException.class)
            .extracting(error -> ((SQLException) error).getSQLState())
            .isEqualTo("57014");
      } finally {
        statement.execute("RESET statement_timeout");
      }
    }
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    String key = UUID.randomUUID().toString();
    try (var delay = new CommitDelay(key, 0.6)) {
      long start = System.nanoTime();
      var result =
          commands.execute(
              "deadline",
              key,
              "credit",
              Map.of("player", player),
              () -> {
                jdbc.execute("SET LOCAL statement_timeout='100ms'");
                return wallets.credit(player, 10, "deadline", "commit delay", "deadline", key);
              });
      assertThat(result.status()).isEqualTo(200);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThan(Duration.ofMillis(500));
    }
    assertThat(wallets.balance(player)).containsEntry("balance", 10L).containsEntry("sequence", 1L);
  }

  @Test
  void transactionTimeoutTerminatesDeferredCommitRollsBackAndAllowsSameKeyRetry() {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    String key = UUID.randomUUID().toString();
    try (var delay = new CommitDelay(key, 2.0)) {
      long start = System.nanoTime();
      Throwable failure =
          catchThrowable(
              () ->
                  commands.execute(
                      "deadline",
                      key,
                      "credit",
                      Map.of("player", player),
                      () -> {
                        jdbc.execute("SET LOCAL transaction_timeout='500ms'");
                        return wallets.credit(
                            player, 10, "deadline", "commit timeout", "deadline", key);
                      }));
      assertThat(failure)
          .isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
          .hasMessageContaining("JDBC commit failed");
      assertThat(rootSqlState(failure)).isEqualTo("25P04");
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
    assertThat(wallets.balance(player)).containsEntry("balance", 0L).containsEntry("sequence", 0L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.journal_transaction where reference=?",
                Long.class,
                key))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.ledger_entry where wallet_id=?", Long.class, player))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.outbox_event where wallet_id=?", Long.class, player))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.idempotency_request where request_key=?",
                Long.class,
                key))
        .isZero();
    var result =
        commands.execute(
            "deadline",
            key,
            "credit",
            Map.of("player", player),
            () -> wallets.credit(player, 10, "deadline", "commit timeout", "deadline", key));
    assertThat(result.status()).isEqualTo(200);
    var replay =
        commands.execute(
            "deadline",
            key,
            "credit",
            Map.of("player", player),
            () -> {
              throw new AssertionError("same-key retry must replay");
            });
    assertThat(replay).isEqualTo(result);
    assertThat(wallets.balance(player)).containsEntry("balance", 10L).containsEntry("sequence", 1L);
  }

  @Test
  void lostCommitAcknowledgementRecoversStoredReceiptWithoutPostingAgain() {
    UUID player = UUID.randomUUID();
    wallets.provision(player);
    String key = UUID.randomUUID().toString();
    var loseAcknowledgement = new AtomicBoolean(true);
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "wallet_app", "wallet_app_local");
    var injected =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            Connection connection = source.getConnection();
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      try {
                        Object result = method.invoke(connection, args);
                        if (method.getName().equals("commit")
                            && loseAcknowledgement.getAndSet(false)) {
                          throw new SQLException(
                              "injected lost acknowledgement after real COMMIT", "08006");
                        }
                        return result;
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
          }

          @Override
          public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
          }
        };
    var client = JdbcClient.create(injected);
    var posting = new WalletService(client, json, Clock.systemUTC());
    var metrics = new SimpleMeterRegistry();
    try {
      var executor =
          new CommandExecutor(json, client, new DataSourceTransactionManager(injected), metrics);
      assertThatThrownBy(
              () ->
                  executor.execute(
                      "deadline",
                      key,
                      "credit",
                      Map.of("player", player),
                      () ->
                          posting.credit(
                              player, 10, "deadline", "lost commit ack", "deadline", key)))
          .hasRootCauseMessage("injected lost acknowledgement after real COMMIT");
      var recovered =
          executor.execute(
              "deadline",
              key,
              "credit",
              Map.of("player", player),
              () -> {
                throw new AssertionError("committed command must replay");
              });
      assertThat(recovered.status()).isEqualTo(200);
      assertThat(recovered.body().get("transactionId").asText())
          .isEqualTo(
              jdbc.queryForObject(
                  "select transaction_id::text from public.journal_transaction where reference=?",
                  String.class,
                  key));
    } finally {
      metrics.close();
    }
    assertThat(wallets.balance(player)).containsEntry("balance", 10L).containsEntry("sequence", 1L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from public.outbox_event where wallet_id=?", Long.class, player))
        .isEqualTo(1);
  }

  private String rootSqlState(Throwable failure) {
    String result = null;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) result = sql.getSQLState();
    }
    return result;
  }

  private static final class CommitDelay implements AutoCloseable {
    private final JdbcTemplate owner = migrationJdbc();
    private final String name =
        "test_commit_delay_" + UUID.randomUUID().toString().replace("-", "");

    CommitDelay(String reference, double seconds) {
      owner.execute(
          "CREATE FUNCTION public."
              + name
              + "() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$ BEGIN IF NEW.reference='"
              + reference
              + "' THEN PERFORM pg_catalog.pg_sleep("
              + seconds
              + "); END IF; RETURN NULL; END $$");
      owner.execute(
          "CREATE CONSTRAINT TRIGGER "
              + name
              + " AFTER INSERT ON public.journal_transaction DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public."
              + name
              + "()");
    }

    @Override
    public void close() {
      owner.execute("DROP TRIGGER " + name + " ON public.journal_transaction");
      owner.execute("DROP FUNCTION public." + name + "()");
    }
  }
}
