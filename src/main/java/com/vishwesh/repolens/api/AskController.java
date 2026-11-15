package com.vishwesh.repolens.api;

import com.vishwesh.repolens.answer.AskService;
import com.vishwesh.repolens.api.dto.AskRequest;
import com.vishwesh.repolens.api.dto.AskResponse;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/repos")
public class AskController {

  private static final long SSE_TIMEOUT_MILLIS = 60_000;

  private final AskService askService;
  private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public AskController(AskService askService) {
    this.askService = askService;
  }

  @PostMapping("/{id}/ask")
  public AskResponse ask(@PathVariable long id, @Valid @RequestBody AskRequest request) {
    return AskResponse.from(askService.ask(id, request.question(), request.topK()));
  }

  @GetMapping(value = "/{id}/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable long id,
      @RequestParam(name = "question", required = false) String question,
      @RequestParam(name = "topK", defaultValue = "6") Integer topK) {
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
    streamExecutor.submit(() -> {
      try {
        askService.stream(
            id,
            question,
            topK,
            token -> send(emitter, "token", token),
            result -> send(emitter, "final", AskResponse.from(result)));
        emitter.complete();
      } catch (RuntimeException e) {
        emitter.completeWithError(e);
      }
    });
    return emitter;
  }

  private static void send(SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data));
    } catch (IOException e) {
      throw new IllegalStateException("SSE client disconnected", e);
    }
  }

  @PreDestroy
  void shutdown() {
    streamExecutor.shutdown();
    try {
      if (!streamExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        streamExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      streamExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
