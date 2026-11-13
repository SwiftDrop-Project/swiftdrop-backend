
package com.swiftdrop.notifications.internal.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import org.springframework.aop.framework.ProxyFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParcelNotificationListenerConfigTest {

    interface TestListener {
        void consume(Message message);
    }

    @Test
    void retriesThreeTimesThenRecoversOriginalMessage() {

        ParcelNotificationDeadLetterRecoverer recoverer =
                mock(ParcelNotificationDeadLetterRecoverer.class);

        AtomicInteger attempts = new AtomicInteger();

        TestListener target = message -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Processing failed");
        };

        RetryOperationsInterceptor interceptor =
                createInterceptor(recoverer);

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addInterface(TestListener.class);
        proxyFactory.addAdvice(interceptor);

        TestListener proxy =
                (TestListener) proxyFactory.getProxy();

        Message message = createMessage();

        assertDoesNotThrow(() -> proxy.consume(message));

        assertEquals(3, attempts.get());

        verify(recoverer, times(1)).recover(
                same(message),
                any(Throwable.class)
        );
    }

    @Test
    void recoveryFailurePropagatesRequeueException() {

        ParcelNotificationDeadLetterRecoverer recoverer =
                mock(ParcelNotificationDeadLetterRecoverer.class);

        doThrow(new ImmediateRequeueAmqpException(
                "DLQ publish failed"
        )).when(recoverer).recover(
                any(Message.class),
                any(Throwable.class)
        );

        AtomicInteger attempts = new AtomicInteger();

        TestListener target = message -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Processing failed");
        };

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addInterface(TestListener.class);
        proxyFactory.addAdvice(createInterceptor(recoverer));

        TestListener proxy =
                (TestListener) proxyFactory.getProxy();

        Message message = createMessage();

        assertThrows(
                ImmediateRequeueAmqpException.class,
                () -> proxy.consume(message)
        );

        assertEquals(3, attempts.get());
    }

    private RetryOperationsInterceptor createInterceptor(
            ParcelNotificationDeadLetterRecoverer recoverer) {

        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(0, 1.0, 0)
                .recoverer((args, cause) -> {

                    Message originalMessage = null;

                    for (Object arg : args) {
                        if (arg instanceof Message message) {
                            originalMessage = message;
                            break;
                        }
                    }

                    if (originalMessage == null) {
                        throw new ImmediateRequeueAmqpException(
                                "Original message unavailable",
                                cause
                        );
                    }

                    recoverer.recover(originalMessage, cause);

                    return null;
                })
                .build();
    }

    private Message createMessage() {
        return new Message(
                "{}".getBytes(StandardCharsets.UTF_8),
                new MessageProperties()
        );
    }
}
