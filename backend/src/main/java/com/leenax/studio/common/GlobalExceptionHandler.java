package com.leenax.studio.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Converts exceptions into one JSON error shape. Request bodies and rejected
 * values are never echoed back, so passwords and keys cannot leak through errors.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String code, String message, Instant timestamp) {
        static ResponseEntity<ErrorResponse> of(ErrorCode code, String message) {
            return ResponseEntity.status(code.status()).body(new ErrorResponse(code.name(), message, Instant.now()));
        }
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ErrorResponse.of(ex.code(), ex.getMessage());
    }

    /** Errors from AI providers keep their normalised kind (e.g. INVALID_CREDENTIALS) as the code. */
    @ExceptionHandler(com.leenax.studio.ai.provider.ProviderException.class)
    public ResponseEntity<ErrorResponse> handleProvider(com.leenax.studio.ai.provider.ProviderException ex) {
        org.springframework.http.HttpStatus status = switch (ex.kind()) {
            case INVALID_CREDENTIALS, INVALID_REQUEST, CONTENT_POLICY_REJECTED, UNSUPPORTED_CAPABILITY ->
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
            case INSUFFICIENT_CREDITS -> org.springframework.http.HttpStatus.PAYMENT_REQUIRED;
            case RATE_LIMIT_EXCEEDED -> org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
            case GENERATION_TIMEOUT -> org.springframework.http.HttpStatus.GATEWAY_TIMEOUT;
            default -> org.springframework.http.HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(ex.kind().name(), ex.getMessage(), Instant.now()));
    }

    /** FFmpeg could not read or process a file (corrupt upload, no audio track, …). */
    @ExceptionHandler(com.leenax.studio.audio.Ffmpeg.FfmpegException.class)
    public ResponseEntity<ErrorResponse> handleFfmpeg(com.leenax.studio.audio.Ffmpeg.FfmpegException ex) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("MEDIA_PROCESSING_FAILED", "Could not process this file. " + ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ErrorResponse.of(ErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleUnreadable(Exception ex) {
        return ErrorResponse.of(ErrorCode.INVALID_REQUEST, "Malformed request");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ErrorResponse.of(ErrorCode.PAYLOAD_TOO_LARGE, "File is larger than the upload limit");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleDenied(AccessDeniedException ex) {
        return ErrorResponse.of(ErrorCode.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ErrorResponse.of(ErrorCode.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException ex) {
        var known = SqlStates.translate(ex);
        if (known.isPresent()) return handleApi(known.get());
        return ErrorResponse.of(ErrorCode.CONFLICT, "The request conflicts with existing data");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        var known = SqlStates.translate(ex);
        if (known.isPresent()) return handleApi(known.get());
        if (SqlStates.isStale(ex)) {
            // An update that touched no row: changed by someone else, or not yours to edit (row-level security).
            return ErrorResponse.of(ErrorCode.CONFLICT, "This changed, or you can't edit it here. Reload and try again.");
        }
        log.error("Unhandled error", ex);
        return ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred");
    }
}
