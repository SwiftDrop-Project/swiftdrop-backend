package com.swiftdrop.tracking.internal.websocket;

import com.swiftdrop.tracking.internal.application.LiveUpdates;
import com.swiftdrop.tracking.internal.application.TrackingService;
import java.security.Principal;
import java.util.Set;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Checks every STOMP frame a client sends. Clients may only subscribe:
 * <ul>
 *   <li>{@code /topic/parcels/{trackingNumber}}: anyone, for a well-formed (normalised) tracking number;</li>
 *   <li>{@code /topic/live}: only a DISPATCHER or ADMIN, who connected through the staff endpoint with a valid
 *   cookie (the public endpoint never has a user);</li>
 *   <li>{@code /user/queue/parcels}: only a logged-in CUSTOMER or MERCHANT, on the same endpoint. Spring sends each
 *   user only their own parcels' updates there (by user id), so nobody can follow someone else's parcels.</li>
 * </ul>
 * Anything else (SEND, other destinations) is refused, and the client gets a STOMP ERROR frame.
 */
@Component
class SubscriptionGuard implements ChannelInterceptor {

    private static final Set<String> STAFF_ROLES = Set.of("ROLE_DISPATCHER", "ROLE_ADMIN");
    private static final Set<String> OWNER_ROLES = Set.of("ROLE_CUSTOMER", "ROLE_MERCHANT");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("Sending messages isn't supported.");
        }
        if (command == StompCommand.SUBSCRIBE) {
            check(accessor.getDestination(), accessor.getUser());
        }
        return message;
    }

    static void check(String destination, Principal user) {
        if (destination == null) {
            throw new MessageDeliveryException("Choose what to subscribe to.");
        }
        if (destination.startsWith(LiveUpdates.PARCEL_TOPIC)) {
            String number = destination.substring(LiveUpdates.PARCEL_TOPIC.length());
            if (!number.equals(TrackingService.normalize(number))) {
                throw new MessageDeliveryException("That isn't a tracking number.");
            }
            return;
        }
        if (destination.equals(LiveUpdates.LIVE_TOPIC)) {
            if (!hasRole(user, STAFF_ROLES)) {
                throw new MessageDeliveryException("Only dispatchers can follow all parcels.");
            }
            return;
        }
        if (destination.equals(LiveUpdates.OWNER_QUEUE_SUBSCRIPTION)) {
            if (!hasRole(user, OWNER_ROLES)) {
                throw new MessageDeliveryException("Log in as a customer or merchant to follow your parcels.");
            }
            return;
        }
        throw new MessageDeliveryException("You can't subscribe to that.");
    }

    private static boolean hasRole(Principal user, Set<String> roles) {
        if (!(user instanceof Authentication authentication) || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(roles::contains);
    }
}
