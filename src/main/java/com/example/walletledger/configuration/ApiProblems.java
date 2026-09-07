package com.example.walletledger.configuration;

import com.example.walletledger.wallet.domain.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
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
    var response =
        problem(
            503,
            "DEPENDENCY_UNAVAILABLE",
            "Database operation could not complete; retry with the same idempotency key");
    return ResponseEntity.status(503).header(HttpHeaders.RETRY_AFTER, "1").body(response.getBody());
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
