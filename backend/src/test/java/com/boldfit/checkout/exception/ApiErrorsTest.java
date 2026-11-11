package com.boldfit.checkout.exception;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiErrorsTest {
  @Test
  void unexpectedErrorsDoNotExposeSensitiveDetails() {
    var response =
        new ApiErrors()
            .unexpected(
                new IllegalStateException("SQL password sensitive-details"),
                new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(500);
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().message()).doesNotContain("SQL", "password", "sensitive-details");
  }

  @Test
  void databaseFailuresHaveSafeRetryGuidance() {
    var response =
        new ApiErrors()
            .retry(
                new DataAccessResourceFailureException("connection password"),
                new MockHttpServletRequest());
    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    assertThat(response.getBody().message())
        .contains("same idempotency key")
        .doesNotContain("password");
  }
}
