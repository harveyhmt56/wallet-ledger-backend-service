package com.example.walletledger.configuration;

import com.example.walletledger.wallet.domain.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiProblems extends ResponseEntityExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Object> business(BusinessException error) {
    return problem(error.status(), error.code(), error.getMessage());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> validation(ConstraintViolationException error) {
    return problem(400, "INVALID_INPUT", "Request fields are missing or outside allowed limits");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<Object> forbidden(AccessDeniedException error) {
    return problem(403, "FORBIDDEN", "You do not have permission for this operation");
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Object> database(DataAccessException error) {
    return dependencyUnavailable();
  }

  @ExceptionHandler(TransactionException.class)
  ResponseEntity<Object> transaction(TransactionException error) {
    // Transaction setup also wraps programming errors; only known connection failures are 503.
    if (error instanceof CannotCreateTransactionException && connectionUnavailable(error)) {
      return dependencyUnavailable();
    }
    // Keep an error signal without exposing SQL, exception messages or connection details.
    logger.error("Database transaction failed: " + error.getClass().getSimpleName());
    return problem(500, "INTERNAL_ERROR", "Database transaction could not complete");
  }

  private boolean connectionUnavailable(Throwable error) {
    for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLTransientConnectionException) return true;
      if (cause instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null
            && (state.startsWith("08")
                || state.equals("57P01")
                || state.equals("57P02")
                || state.equals("57P03"))) return true;
      }
    }
    return false;
  }

  private ResponseEntity<Object> dependencyUnavailable() {
    var response =
        problem(
            503,
            "DEPENDENCY_UNAVAILABLE",
            "Database operation could not complete; retry with the same idempotency key");
    return ResponseEntity.status(503)
        .headers(response.getHeaders())
        .header(HttpHeaders.RETRY_AFTER, "1")
        .body(response.getBody());
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception error,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problem(
        status.value(),
        status.value() == 404 ? "NOT_FOUND" : "INVALID_INPUT",
        status.value() == 404 ? "Resource not found" : "Invalid request shape, value, or method");
  }

  private ResponseEntity<Object> problem(int status, String code, String detail) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
    problem.setProperty("code", code);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
