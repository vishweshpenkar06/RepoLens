package com.vishwesh.repolens.api;

import com.vishwesh.repolens.answer.InvalidQuestionException;
import com.vishwesh.repolens.answer.LlmException;
import com.vishwesh.repolens.ingest.InvalidRepositoryUrlException;
import com.vishwesh.repolens.retrieve.InvalidSearchQueryException;
import com.vishwesh.repolens.retrieve.RetrievalException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(InvalidRepositoryUrlException.class)
  ProblemDetail invalidUrl(InvalidRepositoryUrlException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid repository URL", exception.getMessage());
  }

  @ExceptionHandler(RepositoryNotFoundException.class)
  ProblemDetail notFound(RepositoryNotFoundException exception) {
    return problem(HttpStatus.NOT_FOUND, "Repository not found", exception.getMessage());
  }

  @ExceptionHandler(DuplicateRepositoryException.class)
  ProblemDetail duplicate(DuplicateRepositoryException exception) {
    return problem(HttpStatus.CONFLICT, "Repository already registered", exception.getMessage());
  }

  @ExceptionHandler(IngestionConflictException.class)
  ProblemDetail conflict(IngestionConflictException exception) {
    return problem(HttpStatus.CONFLICT, "Ingestion conflict", exception.getMessage());
  }

  @ExceptionHandler(InvalidQuestionException.class)
  ProblemDetail invalidQuestion(InvalidQuestionException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid question", exception.getMessage());
  }

  @ExceptionHandler(LlmException.class)
  ProblemDetail llmFailure(LlmException exception) {
    return problem(HttpStatus.BAD_GATEWAY, "LLM request failed", exception.getMessage());
  }

  @ExceptionHandler(InvalidSearchQueryException.class)
  ProblemDetail invalidSearch(InvalidSearchQueryException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid search query", exception.getMessage());
  }

  @ExceptionHandler(RetrievalException.class)
  ProblemDetail retrieval(RetrievalException exception) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Retrieval failed", exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException exception) {
    List<Map<String, String>> errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> Map.of("field", error.getField(), "message", error.getDefaultMessage()))
            .toList();
    ProblemDetail detail =
        problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request body is invalid");
    detail.setProperty("errors", errors);
    return detail;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ProblemDetail unreadable(HttpMessageNotReadableException exception) {
    return problem(HttpStatus.BAD_REQUEST, "Malformed request", "Request body is not valid JSON");
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
