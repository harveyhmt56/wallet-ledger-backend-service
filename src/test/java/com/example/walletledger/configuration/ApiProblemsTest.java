package com.example.walletledger.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.walletledger.wallet.domain.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(OutputCaptureExtension.class)
class ApiProblemsTest {
  private final MockMvc http =
      MockMvcBuilders.standaloneSetup(new FailingController())
          .setControllerAdvice(new ApiProblems())
          .build();

  @ParameterizedTest
  @CsvSource({
    "/business,409,ALREADY_REFUNDED,The transaction was already reversed",
    "/validation,400,INVALID_INPUT,Request fields are missing or outside allowed limits",
    "/forbidden,403,FORBIDDEN,You do not have permission for this operation",
    "/missing,404,NOT_FOUND,Resource not found",
    "/amount,400,INVALID_INPUT,'Invalid request shape, value, or method'"
  })
  void adviceSerializesTheExpectedProblemStatusAndCode(
      String path, int status, String code, String detail) throws Exception {
    http.perform(get(path))
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.detail").value(detail))
        .andExpect(jsonPath("$.instance").value(path));
  }

  @Test
  void transientDataAccessFailureProvidesDependencyCodeAndRetryGuidance() throws Exception {
    http.perform(get("/dependency"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
  }

  @ParameterizedTest
  @MethodSource("unavailableTransactions")
  void unavailableTransactionStartProvidesSafeSameKeyRetryGuidance(TransactionException failure)
      throws Exception {
    transactionHttp(failure)
        .perform(get("/transaction"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Database operation could not complete; retry with the same idempotency key"));
  }

  private static Stream<TransactionException> unavailableTransactions() {
    return Stream.of(
        new CannotCreateTransactionException(
            "pool details", new SQLTransientConnectionException("pool exhausted")),
        new CannotCreateTransactionException(
            "pool details",
            new SQLTransientConnectionException(
                "connection unavailable", new java.net.ConnectException("private database host"))),
        new CannotCreateTransactionException(
            "connection details", new SQLException("private database host", "08001")),
        new CannotCreateTransactionException(
            "connection preparation failed", new SQLException("private shutdown details", "57P01")),
        new CannotCreateTransactionException(
            "connection preparation failed", new SQLException("private crash details", "57P02")),
        new CannotCreateTransactionException(
            "connection preparation failed", new SQLException("private startup details", "57P03")),
        new CannotCreateTransactionException(
            "connection preparation failed",
            new IllegalStateException(new SQLException("connection lost", "08006"))));
  }

  @ParameterizedTest
  @MethodSource("unexpectedTransactions")
  void unexpectedTransactionFailureProvidesSafeInternalProblemWithoutRetryAfter(
      TransactionException failure, CapturedOutput output) throws Exception {
    transactionHttp(failure)
        .perform(get("/transaction"))
        .andExpect(status().isInternalServerError())
        .andExpect(header().doesNotExist("Retry-After"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value("Database transaction could not complete"));
    assertThat(output.getOut())
        .contains("Database transaction failed: " + failure.getClass().getSimpleName())
        .doesNotContain("private");
  }

  private static Stream<TransactionException> unexpectedTransactions() {
    return Stream.of(
        new CannotCreateTransactionException("private configuration details"),
        new CannotCreateTransactionException(
            "transaction setup failed", new IllegalStateException("private programming details")),
        new CannotCreateTransactionException(
            "transaction setup failed", new SQLException("private SQL without state")),
        new CannotCreateTransactionException(
            "transaction setup failed", new SQLException("private SQL syntax", "42601")),
        new CannotCreateTransactionException(
            "transaction setup failed", new SQLException("private cancellation details", "57014")),
        new CannotCreateTransactionException(
            "transaction setup failed", new SQLException("private credentials", "28P01")),
        new TransactionSystemException(
            "commit failed", new SQLException("private invariant details", "23514")),
        new TransactionSystemException(
            "commit outcome unknown", new SQLException("private host", "08006")),
        new TransactionSystemException(
            "commit outcome unknown", new SQLTransientConnectionException("private host")),
        new NestedTransactionNotSupportedException("private nesting details"),
        new TransactionSuspensionNotSupportedException("private suspension details"),
        new UnexpectedRollbackException("private rollback details"),
        new IllegalTransactionStateException("private propagation details"));
  }

  private MockMvc transactionHttp(TransactionException failure) {
    return MockMvcBuilders.standaloneSetup(new TransactionFailureController(failure))
        .setControllerAdvice(new ApiProblems())
        .build();
  }

  @RestController
  static class TransactionFailureController {
    private final TransactionException failure;

    TransactionFailureController(TransactionException failure) {
      this.failure = failure;
    }

    @GetMapping("/transaction")
    void transaction() {
      throw failure;
    }
  }

  @RestController
  static class FailingController {
    @GetMapping("/business")
    void business() {
      throw new BusinessException(409, "ALREADY_REFUNDED", "The transaction was already reversed");
    }

    @GetMapping("/validation")
    void validation() {
      throw new ConstraintViolationException(Set.of());
    }

    @GetMapping("/forbidden")
    void forbidden() {
      throw new AccessDeniedException("Denied");
    }

    @GetMapping("/dependency")
    void dependency() {
      throw new TransientDataAccessResourceException("Retryable database boundary failure");
    }

    @GetMapping("/amount")
    int amount(@RequestParam("value") int value) {
      return value;
    }
  }
}
