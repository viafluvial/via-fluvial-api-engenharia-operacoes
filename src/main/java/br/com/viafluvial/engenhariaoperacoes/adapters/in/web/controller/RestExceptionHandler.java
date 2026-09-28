package br.com.viafluvial.engenhariaoperacoes.adapters.in.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage(), request, "BAD_REQUEST");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "Resource not found", request, "NOT_FOUND");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnhandled(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request, "INTERNAL_ERROR");
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status,
                                                   String message,
                                                   HttpServletRequest request,
                                                   String errorCode) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setType(URI.create("https://api-engenharia-operacoes.viafluvial.com.br/problems/" + errorCode));
        detail.setTitle(message);
        detail.setProperty("error", errorCode);
        detail.setProperty("path", request.getRequestURI());
        detail.setProperty("timestamp", OffsetDateTime.now());
        return ResponseEntity.status(status).body(detail);
    }
}
