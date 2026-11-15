package com.swiftdrop.tracking.internal.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which STOMP frames a client may send. */
class SubscriptionGuardTest {

    private final SubscriptionGuard guard = new SubscriptionGuard();

    @Test
    void anyoneMaySubscribeToAWellFormedParcelTopic() {
        assertThatCode(() -> guard.preSend(subscribe("/topic/parcels/SD2611004817", null), null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.preSend(subscribe("/topic/parcels/sd 2611 004817", null), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> guard.preSend(subscribe("/topic/parcels/*", null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void onlyDispatchersAndAdminsMayFollowTheLiveBoard() {
        assertThatThrownBy(() -> guard.preSend(subscribe("/topic/live", null), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> guard.preSend(subscribe("/topic/live", user("ROLE_CUSTOMER")), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatCode(() -> guard.preSend(subscribe("/topic/live", user("ROLE_DISPATCHER")), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.preSend(subscribe("/topic/live", user("ROLE_ADMIN")), null))
                .doesNotThrowAnyException();
    }

    @Test
    void onlyCustomersAndMerchantsMayFollowTheirOwnParcels() {
        assertThatThrownBy(() -> guard.preSend(subscribe("/user/queue/parcels", null), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> guard.preSend(subscribe("/user/queue/parcels", user("ROLE_COURIER")), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatCode(() -> guard.preSend(subscribe("/user/queue/parcels", user("ROLE_CUSTOMER")), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.preSend(subscribe("/user/queue/parcels", user("ROLE_MERCHANT")), null))
                .doesNotThrowAnyException();
        // Another user's resolved queue, or the queue without the /user prefix, is never allowed.
        assertThatThrownBy(() -> guard.preSend(subscribe("/queue/parcels", user("ROLE_CUSTOMER")), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> guard.preSend(subscribe("/queue/parcels-userabc", user("ROLE_CUSTOMER")), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> guard.preSend(subscribe("/user/someone/queue/parcels", user("ROLE_CUSTOMER")), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void otherDestinationsAndSendingAreRefused() {
        assertThatThrownBy(() -> guard.preSend(subscribe("/topic/anything", user("ROLE_ADMIN")), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> guard.preSend(subscribe("/topic", user("ROLE_ADMIN")), null))
                .isInstanceOf(MessageDeliveryException.class);
        StompHeaderAccessor send = StompHeaderAccessor.create(StompCommand.SEND);
        send.setDestination("/topic/live");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], send.getMessageHeaders());
        assertThatThrownBy(() -> guard.preSend(message, null)).isInstanceOf(MessageDeliveryException.class);
    }

    private static Message<byte[]> subscribe(String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-0");
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Principal user(String role) {
        return UsernamePasswordAuthenticationToken.authenticated("user", null, AuthorityUtils.createAuthorityList(role));
    }
}
