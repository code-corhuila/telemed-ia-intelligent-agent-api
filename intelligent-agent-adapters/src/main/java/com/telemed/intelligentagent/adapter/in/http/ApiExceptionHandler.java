package com.telemed.intelligentagent.adapter.in.http;

import com.telemed.intelligentagent.adapter.in.http.ApiErrorResponse.FieldError;
import com.telemed.intelligentagent.application.port.out.AgentModelUnavailableException;
import com.telemed.intelligentagent.application.port.out.AppointmentVerificationUnavailableException;
import com.telemed.intelligentagent.domain.model.AgentDomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Every error in the common envelope with the request's traceId. Domain
 * reasons keep their own stable code; internal details never leave the service.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AgentDomainException.class)
    ResponseEntity<ApiErrorResponse> domainRule(AgentDomainException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.reason()) {
            case APPOINTMENT_NOT_FOUND, CONVERSATION_NOT_FOUND, SUMMARY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case APPOINTMENT_NOT_ELIGIBLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PRECONSULTATION_ALREADY_EXISTS, ACTIVE_PRECONSULTATION_EXISTS, CONVERSATION_FINISHED,
                 QUESTION_LIMIT_REACHED, NOTHING_TO_SUMMARIZE, IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
            case INVALID_MESSAGE, MESSAGE_TOO_LONG, INVALID_IDEMPOTENCY_KEY -> HttpStatus.BAD_REQUEST;
            case INVALID_CONVERSATION, INVALID_SUMMARY -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            return internalError(exception, request);
        }
        return error(status, exception.reason().name(), exception.getMessage(), List.of(), request);
    }

    @ExceptionHandler({AgentModelUnavailableException.class, AppointmentVerificationUnavailableException.class})
    ResponseEntity<ApiErrorResponse> unavailable(RuntimeException exception, HttpServletRequest request) {
        LOG.warn("Dependency unavailable: {}", exception.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The pre-consultation is not available right now. Try again in a moment.", List.of(), request);
    }

    @ExceptionHandler(TokenSubject.InvalidSubjectException.class)
    ResponseEntity<ApiErrorResponse> invalidSubject(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid credentials.", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> internalError(Exception exception, HttpServletRequest request) {
        LOG.error("Unexpected error [traceId={}]", TraceIds.of(request), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong.", List.of(),
                request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> details = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage()))
                .toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request has invalid fields.", details,
                request);
    }

    /** Spring MVC's own errors (unreadable body, missing header, unknown path...) in the common envelope. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object ignoredBody,
                                                             HttpHeaders headers, HttpStatusCode status,
                                                             WebRequest request) {
        if (status.value() == HttpStatus.BAD_REQUEST.value()) {
            List<FieldError> details = exception instanceof MissingRequestHeaderException missing
                    ? List.of(new FieldError(missing.getHeaderName(), "is required"))
                    : List.of();
            return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "The request is malformed or incomplete.",
                    details, request);
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved == null ? "ERROR" : resolved.name();
        return body(status, code, resolved == null ? "Request failed." : resolved.getReasonPhrase() + ".",
                List.of(), request);
    }

    private ResponseEntity<Object> body(HttpStatusCode status, String code, String message, List<FieldError> details,
                                        WebRequest request) {
        HttpServletRequest servletRequest = ((NativeWebRequest) request).getNativeRequest(HttpServletRequest.class);
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(code, message, details, TraceIds.of(servletRequest)));
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message,
                                                          List<FieldError> details, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, details, TraceIds.of(request)));
    }
}
