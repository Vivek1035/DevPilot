package com.devPilot.backend.services.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Generation step: call Gemini via Spring AI ChatModel and stream tokens to the browser over SSE.
 *
 * <p>RAG retrieval (embedding lookup) and Gemini streaming are both performed off the
 * Tomcat request thread so that any failures are sent as SSE error events rather than
 * propagating to {@code GlobalExceptionHandler} after the response has been committed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

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

        executor.submit(() -> {
            try {
                // 1. Send saved user message as first SSE event
                emitter.send(SseEmitter.event()
                        .name("user_message")
                        .data(objectMapper.writeValueAsString(savedUserMessage)));

                // 2. RAG retrieval (embedding call happens here - any error stays SSE-side)
                RetrievedContext context = contextSupplier.get();

                // 3. Build prompts
                String systemPrompt = chatPromptBuilder.systemPrompt(repoFullName);
                String userPrompt   = chatPromptBuilder.userPrompt(context.contextText(), savedUserMessage.content());

                StringBuilder fullReply = new StringBuilder();

                // 4. Start Gemini streaming; subscribe() is non-blocking — doOnError/doOnComplete
                //    callbacks own the SseEmitter lifecycle from here.
                ChatClient.builder(chatModel)
                        .build()
                        .prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(token -> appendToken(emitter, fullReply, token))
                        .doOnError(err -> sendErrorAndComplete(emitter, err))
                        .doOnComplete(() -> completeStream(emitter, sessionId, fullReply, context.citations()))
                        .subscribe(
                                token -> { /* handled in doOnNext */ },
                                err -> log.error("Chat stream subscription error (secondary handler)", err)
                        );

            } catch (Throwable ex) {
                log.error("Chat stream pipeline error (before streaming started)", ex);
                sendErrorAndComplete(emitter, ex);
            }
        });

        return emitter;
    }

    private void sendErrorAndComplete(SseEmitter emitter, Throwable err) {
        log.error("Chat stream error encountered: {}", err.getMessage(), err);
        try {
            String errorMessage = err.getMessage() != null ? err.getMessage() : "Error generating AI response";
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(Map.of("error", errorMessage))));
        } catch (Exception ex) {
            log.warn("Failed to send error SSE event (emitter may already be complete): {}", ex.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // Ignore: emitter may already be completed
            }
        }
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(objectMapper.writeValueAsString(token)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations) {
        try {
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(citations))
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(objectMapper.writeValueAsString(toMessageResponse(assistant))));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            // Use sendErrorAndComplete so chunked encoding terminates cleanly
            sendErrorAndComplete(emitter, ex);
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