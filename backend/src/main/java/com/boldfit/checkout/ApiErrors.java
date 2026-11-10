package com.boldfit.checkout;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.dao.TransientDataAccessException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiDtos.Error> domain(ApiException ex) {
        return ResponseEntity.status(ex.status).body(new ApiDtos.Error(ex.code,ex.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,MissingRequestHeaderException.class})
    ResponseEntity<ApiDtos.Error> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(new ApiDtos.Error("INVALID_REQUEST","Check the request fields and required headers."));
    }
    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<ApiDtos.Error> retry(Exception ex) {
        return ResponseEntity.status(503).header("Retry-After","1").body(new ApiDtos.Error("RETRY_REQUIRED","Please retry with the same idempotency key."));
    }
}
