package com.credresolve.smartdialer.exception;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<?> missing(Exception e) {
    return error(404, "Record not found");
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return error(
        400,
        e instanceof MethodArgumentNotValidException
            ? "Invalid request: "
                + ((MethodArgumentNotValidException) e)
                    .getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + " " + f.getDefaultMessage())
                        .toList()
            : e.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e) {
    return error(409, "Operation conflicts with a database invariant or referenced record");
  }

  private ResponseEntity<?> error(int status, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "status",
                status,
                "error",
                message == null ? "Invalid request" : message,
                "timestamp",
                java.time.Instant.now().toString()));
  }
}
