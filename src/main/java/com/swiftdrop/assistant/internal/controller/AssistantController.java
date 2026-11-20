package com.swiftdrop.assistant.internal.controller;

import com.swiftdrop.assistant.internal.application.AssistantService;
import com.swiftdrop.assistant.internal.dto.ChatRequest;
import com.swiftdrop.assistant.internal.dto.ChatResponse;
import com.swiftdrop.assistant.internal.provider.ChatReply;
import com.swiftdrop.assistant.internal.provider.Turn;
import com.swiftdrop.assistant.internal.tools.Caller;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.web.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI delivery assistant's chat. Public chats can track parcels, estimate prices and list districts; a logged-in
 * customer's or merchant's chat can also list their own parcels. (Public URLs ignore the login cookie, hence two
 * endpoints.)
 */
@RestController
@RequestMapping("/assistant")
@Tag(name = "Assistant")
class AssistantController {

    private final AssistantService assistant;

    AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @Operation(summary = """
            Ask the delivery assistant (public). Send the conversation, oldest first, ending with the user's message \
            (at most 500 characters). It answers with the tools: track a parcel, estimate a price, list districts. \
            20 messages a minute per client.""")
    @PreAuthorize("permitAll()")
    @PostMapping("/chat")
    ChatResponse chat(@Valid @RequestBody ChatRequest request, HttpServletRequest http) {
        return answer(request, Caller.anonymous(http.getRemoteAddr()));
    }

    @Operation(summary = """
            Ask the delivery assistant as the logged-in user. Like /assistant/chat, and customers and merchants can \
            also ask about their own parcels.""")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/chat/me")
    ChatResponse chatAsUser(@Valid @RequestBody ChatRequest request, Authentication auth, HttpServletRequest http) {
        return answer(request, new Caller(userId(auth), role(auth), http.getRemoteAddr()));
    }

    private ChatResponse answer(ChatRequest request, Caller caller) {
        List<Turn> conversation = request.messages().stream()
                .map(message -> new Turn("user".equals(message.role()), message.text().trim()))
                .toList();
        ChatReply reply = assistant.chat(conversation, caller);
        return new ChatResponse(reply.text(), reply.cards(), reply.provider());
    }

    private static UUID userId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            return CurrentUser.id(jwt.getToken());
        }
        if (auth != null && auth.getPrincipal() instanceof UUID id) {
            return id;
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED);
    }

    private static String role(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(name -> name.startsWith("ROLE_"))
                .map(name -> name.substring("ROLE_".length()))
                .findFirst()
                .orElse(null);
    }
}
