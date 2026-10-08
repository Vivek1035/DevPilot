package com.devPilot.backend.services.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.devPilot.backend.dto.ChatMessageResponse;
import com.devPilot.backend.dto.CitationDto;
import com.devPilot.backend.entity.ChatMessage;
import com.devPilot.backend.entity.MessageRole;
import com.devPilot.backend.repository.ChatMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.genai.errors.ServerException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.util.retry.Retry;

/**
 * Generation step: call Gemini via Spring AI ChatModel and stream tokens to the browser over SSE.
 *
 * <p>RAG retrieval (embedding lookup) and Gemini streaming are both performed off the
 * Tomcat request thread so that any failures are sent as SSE error events rather than
 * propagating to {@code GlobalExceptionHandler} after the response has been committed.
 *
 * <p>Transient 503 errors from the Gemini API are retried up to {@code MAX_RETRIES} times
 * with exponential backoff before a final SSE error event is sent to the client.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

    private static final int  MAX_RETRIES   = 1;
    private static final long RETRY_BASE_MS = 3_000L; // 3 s base delay to respect rate limits

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final ChatPromptBuilder chatPromptBuilder;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * @param contextSupplier lazily supplies {@link RetrievedContext}; called off the
     *                        request thread so embedding exceptions become SSE error events.
     */
    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            Supplier<RetrievedContext> contextSupplier,
            String repoFullName) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        emitter.onCompletion(() -> clientDisconnected.set(true));
        emitter.onTimeout(() -> clientDisconnected.set(true));
        emitter.onError(t -> clientDisconnected.set(true));

        executor.submit(() -> {
            try {
                // 1. Send saved user message as first SSE event
                emitter.send(SseEmitter.event()
                        .name("user_message")
                        .data(objectMapper.writeValueAsString(savedUserMessage)));

                // 2. RAG retrieval (embedding call — any error stays SSE-side)
                RetrievedContext context = contextSupplier.get();

                // 3. Build prompts
                String systemPrompt = chatPromptBuilder.systemPrompt(repoFullName);
                String userPrompt   = chatPromptBuilder.userPrompt(context.contextText(), savedUserMessage.content());

                StringBuilder fullReply = new StringBuilder();

                // 4. Start Gemini streaming with retry for transient 503 errors.
                //    takeWhile stops upstream if the client disconnected (e.g. browser refreshed or aborted).
                ChatClient.builder(chatModel)
                        .build()
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .stream()
                        .content()
                        // Reset accumulated reply on each retry attempt
                        .doOnSubscribe(s -> fullReply.setLength(0))
                        .takeWhile(token -> !clientDisconnected.get())
                        .doOnNext(token -> appendToken(emitter, fullReply, token, clientDisconnected))
                        .retryWhen(
                            Retry.backoff(MAX_RETRIES, Duration.ofMillis(RETRY_BASE_MS))
                                 .filter(ChatStreamHandler::isRetryable)
                                 .doBeforeRetry(rs -> log.warn(
                                     "Gemini 503 – retrying attempt {}/{}: {}",
                                     rs.totalRetries() + 1, MAX_RETRIES,
                                     rs.failure().getMessage()))
                        )
                        .doOnError(err -> {
                            if (!clientDisconnected.get() && completed.compareAndSet(false, true)) {
                                sendErrorEvent(emitter, err);
                            }
                        })
                        .doOnComplete(() -> {
                            if (completed.compareAndSet(false, true)) {
                                completeStream(emitter, sessionId, fullReply, context.citations(), clientDisconnected);
                            }
                        })
                        .subscribe(
                                token -> { /* handled in doOnNext */ },
                                err  -> {
                                    if (!clientDisconnected.get()) {
                                        log.debug("Secondary error handler (after retries exhausted): {}", err.getMessage());
                                    }
                                }
                        );

            } catch (Throwable ex) {
                if (!clientDisconnected.get()) {
                    log.error("Chat stream pipeline error (before streaming started)", ex);
                    if (completed.compareAndSet(false, true)) {
                        sendErrorEvent(emitter, ex);
                    }
                }
            }
        });

        return emitter;
    }

    // ── Retry predicate ──────────────────────────────────────────────────────

    /**
     * Returns {@code true} for transient errors that are safe to retry
     * (Gemini 503 overload, or a RuntimeException wrapping a ServerException).
     */
    private static boolean isRetryable(Throwable err) {
        if (err instanceof ServerException) {
            return true;
        }
        // Spring AI wraps it in RuntimeException
        if (err instanceof RuntimeException && err.getCause() instanceof ServerException) {
            return true;
        }
        // Message-based fallback for any future wrapper
        String msg = err.getMessage();
        return msg != null && msg.contains("503");
    }

    // ── SSE helpers ──────────────────────────────────────────────────────────

    /**
     * Sends an {@code event: error} SSE event then completes the emitter cleanly
     * so the chunked HTTP response always terminates with the final {@code 0\r\n\r\n} chunk.
     */
    private void sendErrorEvent(SseEmitter emitter, Throwable err) {
        log.error("Chat stream error: {}", err.getMessage(), err);
        try {
            Throwable root = err;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }

            String errorMessage;
            String msg = root.getMessage() != null ? root.getMessage() : "";
            if (msg.contains("429") || msg.toLowerCase().contains("quota") || msg.toLowerCase().contains("rate limit")) {
                errorMessage = "Gemini rate limit exceeded (Google free tier allows 5 requests/minute). Please wait about 30 seconds and try again.";
            } else if (msg.contains("503") || msg.toLowerCase().contains("high demand")) {
                errorMessage = "Gemini is currently experiencing high demand. Please try again in a few moments.";
            } else if (!msg.isBlank()) {
                errorMessage = msg;
            } else {
                errorMessage = "The AI model is unavailable. Please try again in a moment.";
            }

            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(Map.of("error", errorMessage))));
        } catch (Exception ex) {
            log.warn("Failed to send SSE error event: {}", ex.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignore) { /* already completed */ }
        }
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token, AtomicBoolean clientDisconnected) {
        if (clientDisconnected.get()) {
            return;
        }
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(objectMapper.writeValueAsString(token)));
        } catch (Exception ex) {
            clientDisconnected.set(true);
            log.info("Client disconnected from SSE stream: {}", ex.getMessage());
            try {
                emitter.complete();
            } catch (Exception ignore) {}
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations,
            AtomicBoolean clientDisconnected) {
        try {
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(citations))
                    .build());

            if (!clientDisconnected.get()) {
                emitter.send(SseEmitter.event()
                        .name("assistant_message")
                        .data(objectMapper.writeValueAsString(toMessageResponse(assistant))));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            }
        } catch (Exception ex) {
            if (!clientDisconnected.get()) {
                sendErrorEvent(emitter, ex);
            }
        }
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}