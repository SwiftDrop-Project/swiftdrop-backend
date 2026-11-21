package com.swiftdrop.assistant.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /assistant/chat}: the conversation so far, oldest first, ending with the user's new message. Nothing is
 * stored on the server, so the client sends the recent messages each time.
 */
public record ChatRequest(
        @NotNull(message = "Send at least one message.")
        @Size(min = 1, max = 20, message = "Send between 1 and 20 messages.")
        List<@NotNull @Valid Message> messages) {

    /**
     * @param role {@code user} or {@code assistant}
     * @param text the user's text (at most 500 characters for the new message) or an earlier answer
     */
    public record Message(
            @NotNull(message = "Say who wrote the message.")
            @Pattern(regexp = "user|assistant", message = "A message's role is user or assistant.")
            String role,
            @NotBlank(message = "Type a message.")
            @Size(max = 4000, message = "A message can't be longer than 4000 characters.")
            String text) {
    }
}
