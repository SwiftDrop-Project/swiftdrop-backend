
package com.swiftdrop.notifications.internal.messaging;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
@ConditionalOnProperty(
        prefix = "swiftdrop.notifications.consumer",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ParcelNotificationListenerConfig {

    @Bean("parcelNotificationListenerFactory")
    public SimpleRabbitListenerContainerFactory
            parcelNotificationListenerFactory(
                    ConnectionFactory connectionFactory,
                    ParcelNotificationDeadLetterRecoverer deadLetterRecoverer) {

        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);

        RetryOperationsInterceptor retryInterceptor =
                RetryInterceptorBuilder.stateless()
                        .maxAttempts(3)
                        .backOffOptions(1000, 2.0, 5000)
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
                                        "Original AMQP message unavailable",
                                        cause
                                );
                            }

                            deadLetterRecoverer.recover(
                                    originalMessage,
                                    cause
                            );

                            return null;
                        })
                        .build();

        factory.setAdviceChain(retryInterceptor);
        return factory;
    }
}
