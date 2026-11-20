package dev.llmgateway.admin;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import dev.llmgateway.api.openai.OpenAiError;
import dev.llmgateway.core.GatewayException;

/** Same error envelope for the admin API as for the OpenAI-compatible API. */
@RestControllerAdvice(assignableTypes = AdminController.class)
public class AdminExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<OpenAiError> gatewayError(GatewayException error) {
        return ResponseEntity.status(error.status())
                .body(OpenAiError.of(error.getMessage(), error.type(), error.code()));
    }

    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<OpenAiError> badInput(ServerWebInputException error) {
        return gatewayError(GatewayException.from(error));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<OpenAiError> conflict(DataIntegrityViolationException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(OpenAiError.of("A tenant with that name already exists.", "invalid_request_error", "conflict"));
    }
}
