package com.example.walletledger.configuration;

import java.util.List;
import org.postgresql.PGProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/** Scratch-only characterization of the existing advice and resolved driver. */
public class ApiFactCheckProbe {
  public static void main(String[] args) throws Exception {
    var advice = new ApiProblems();
    var request = new ServletWebRequest(new MockHttpServletRequest());
    var method = advice.handleException(
        new HttpRequestMethodNotSupportedException("GET", List.of("POST")), request);
    require(method.getStatusCode().value() == 405, "405 status retained");
    require(method.getHeaders().getFirst(HttpHeaders.ALLOW) == null, "405 Allow header lost");
    require(((ProblemDetail) method.getBody()).getProperties().get("code").equals("INVALID_INPUT"),
        "405 application code is INVALID_INPUT");
    var media = advice.handleException(
        new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
        request);
    require(media.getStatusCode().value() == 415, "415 status retained");
    var resolver = new ExceptionHandlerMethodResolver(ApiProblems.class);
    require(resolver.resolveMethod(new CannotCreateTransactionException("probe")) == null,
        "CannotCreateTransactionException has no matching advice handler");
    require(!DataAccessException.class.isAssignableFrom(CannotCreateTransactionException.class),
        "CannotCreateTransactionException is outside DataAccessException hierarchy");
    var integrity = advice.database(new DataIntegrityViolationException("probe"));
    require(integrity.getStatusCode().value() == 503
        && "1".equals(integrity.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)),
        "nontransient integrity exception maps to 503 with Retry-After 1");
    require("0".equals(PGProperty.SOCKET_TIMEOUT.getDefaultValue()),
        "resolved pgJDBC socketTimeout default is zero");
  }

  private static void require(boolean condition, String description) {
    if (!condition) throw new AssertionError(description);
    System.out.println("PASS: " + description);
  }
}
