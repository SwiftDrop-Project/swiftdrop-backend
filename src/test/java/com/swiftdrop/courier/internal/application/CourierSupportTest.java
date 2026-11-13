package com.swiftdrop.courier.internal.application;

import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Courier endpoints answer 503 SERVICE_UNAVAILABLE when an API they depend on (delivery, dispatch) has no
 * implementation. Both are implemented now, so this is checked on {@link CourierSupport#required} directly instead
 * of through a Spring context that lacks the bean.
 */
class CourierSupportTest {

    @Test
    void missingImplementationAnswers503ServiceUnavailable() {
        ObjectProvider<Runnable> missing = new StaticListableBeanFactory().getBeanProvider(Runnable.class);

        assertThatThrownBy(() -> CourierSupport.required(missing))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    assertThat(e.code().status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code().defaultMessage())
                            .isEqualTo("This isn't available yet. Please try again later.");
                });
    }

    @Test
    void presentImplementationIsReturned() {
        Runnable api = () -> { };
        ObjectProvider<Runnable> present =
                new StaticListableBeanFactory(Map.of("api", api)).getBeanProvider(Runnable.class);

        assertThat(CourierSupport.required(present)).isSameAs(api);
    }
}
