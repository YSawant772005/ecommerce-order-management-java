package com.ecommerce.ordermanagement.web;

import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;

/**
 * HTTP error shape preservation.
 *
 * <p>The frontend reads {@code .detail} from error bodies (a string for simple
 * errors, an array of {@code {msg}} for validation failures — see
 * {@code frontend/src/api/products.js}). Validation failures return 422,
 * matching the former pydantic {@code extra="forbid"} behaviour.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("detail", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> detail = ex.getBindingResult().getAllErrors().stream()
                .map(err -> Map.of("msg", messageOf(err)))
                .toList();
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", detail));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleParamValidation(HandlerMethodValidationException ex) {
        List<Map<String, String>> detail = ex.getAllValidationResults().stream()
                .flatMap(res -> res.getResolvableErrors().stream())
                .map(err -> Map.of("msg", err.getDefaultMessage() == null ? "invalid" : err.getDefaultMessage()))
                .toList();
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", detail));
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleConstraint(Exception ex) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("detail", List.of(Map.of("msg", ex.getMessage()))));
    }

    /**
     * Unknown request-body keys (Jackson {@code UnrecognizedPropertyException})
     * and malformed JSON both land here — 422, exactly as pydantic rejected them.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("detail", List.of(Map.of("msg", "invalid request body"))));
    }

    /**
     * Last-resort handler. It logs the exception: without this, a bug inside a
     * service surfaced only as an opaque "internal error", which is what made the
     * admin-search failure hard to diagnose.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("unhandled error serving request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("detail", "internal error"));
    }

    private static String messageOf(org.springframework.validation.ObjectError error) {
        if (error instanceof FieldError fieldError && fieldError.getDefaultMessage() != null) {
            return fieldError.getField() + ": " + fieldError.getDefaultMessage();
        }
        return error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage();
    }
}
