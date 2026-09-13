package com.example.walletledger.configuration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.walletledger.wallet.domain.BusinessException;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
