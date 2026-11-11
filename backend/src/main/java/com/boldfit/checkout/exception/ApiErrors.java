package com.boldfit.checkout.exception;

import com.boldfit.checkout.dto.ApiErrorResponse;
import com.boldfit.checkout.security.RequestCorrelation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  private ApiErrorResponse body(
      String code, String message, HttpServletRequest request, Map<String, String> fields) {
    return new ApiErrorResponse(code, message, RequestCorrelation.id(request), fields);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrorResponse> domain(ApiException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.status).body(body(ex.code, ex.getMessage(), request, Map.of()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> validation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> fields = new TreeMap<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
    return ResponseEntity.badRequest()
        .body(body("INVALID_REQUEST", "Check the highlighted request fields.", request, fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiErrorResponse> unreadable(Exception ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            body(
                "INVALID_JSON",
                "Send a valid JSON body with the documented fields and types.",
                request,
                Map.of()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ApiErrorResponse> type(
      MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            body(
                "INVALID_PARAMETER",
                "A request parameter has an invalid format.",
                request,
                Map.of(ex.getName(), "Invalid format")));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ResponseEntity<ApiErrorResponse> header(
      MissingRequestHeaderException ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(
            body(
                "MISSING_HEADER",
                "A required request header is missing.",
                request,
                Map.of(ex.getHeaderName(), "Required")));
  }

  @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
  ResponseEntity<ApiErrorResponse> retry(Exception ex, HttpServletRequest request) {
    log.warn(
        "Database unavailable or busy; requestId={} type={}",
        RequestCorrelation.id(request),
        ex.getClass().getSimpleName());
    return ResponseEntity.status(503)
        .header("Retry-After", "1")
        .body(
            body(
                "RETRY_REQUIRED",
                "The service is temporarily unavailable. Retry the same request with the same"
                    + " idempotency key.",
                request,
                Map.of()));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ApiErrorResponse> missing(Exception ex, HttpServletRequest request) {
    return ResponseEntity.status(404)
        .body(body("NOT_FOUND", "Endpoint not found.", request, Map.of()));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> method(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    return ResponseEntity.status(405)
        .header(
            "Allow",
            String.join(", ", Objects.requireNonNullElse(ex.getSupportedMethods(), new String[0])))
        .body(
            body(
                "METHOD_NOT_ALLOWED",
                "This endpoint does not support that HTTP method.",
                request,
                Map.of()));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiErrorResponse> media(Exception ex, HttpServletRequest request) {
    return ResponseEntity.status(415)
        .body(
            body(
                "UNSUPPORTED_MEDIA_TYPE",
                "Use Content-Type: application/json.",
                request,
                Map.of()));
  }

  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  ResponseEntity<Void> accept(Exception ex) {
    return ResponseEntity.status(406).build();
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
    // Do not expose SQL, request bodies, credentials, or exception messages.
    log.error(
        "Unexpected request failure; requestId={} type={}",
        RequestCorrelation.id(request),
        ex.getClass().getName());
    return ResponseEntity.status(500)
        .body(
            body(
                "INTERNAL_ERROR",
                "Something went wrong. Refresh the order status before retrying a payment, and keep"
                    + " the request reference if the problem continues.",
                request,
                Map.of()));
  }
}
