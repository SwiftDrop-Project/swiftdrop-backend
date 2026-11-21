package com.swiftdrop.webhooks.internal.application;

import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.ForbiddenException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.webhooks.internal.domain.WebhookEventType;
import com.swiftdrop.webhooks.internal.domain.WebhookSubscription;
import com.swiftdrop.webhooks.internal.dto.CreateWebhookRequest;
import com.swiftdrop.webhooks.internal.dto.UpdateWebhookRequest;
import com.swiftdrop.webhooks.internal.dto.WebhookResponse;
import com.swiftdrop.webhooks.internal.dto.WebhookWithSecretResponse;
import com.swiftdrop.webhooks.internal.persistence.WebhookSubscriptionRepository;
import com.swiftdrop.webhooks.internal.security.CallbackDestination;
import com.swiftdrop.webhooks.internal.security.CallbackUrlValidator;
import com.swiftdrop.webhooks.internal.security.SecretCipher;
import com.swiftdrop.webhooks.internal.security.WebhookSecretGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The merchant's webhook subscriptions (API.md "Webhooks"). Only approved merchants may subscribe; at most
 * {@value #MAX_ACTIVE} subscriptions are active per merchant. The plain secret leaves this class only in the create and
 * rotate responses; it is stored encrypted and never logged.
 */
@Service
public class WebhookSubscriptionService {

    static final int MAX_ACTIVE = 5;
    static final String EVENT_TYPES_FIELD = "eventTypes";
    /** Microsecond ticks, like PostgreSQL, so returned times equal the stored ones. */
    private static final Clock DATABASE_CLOCK = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

    private final WebhookSubscriptionRepository subscriptions;
    private final MerchantApi merchants;
    private final CallbackUrlValidator urlValidator;
    private final WebhookSecretGenerator secretGenerator;
    private final SecretCipher cipher;
    private final Clock clock;

    @Autowired
    public WebhookSubscriptionService(WebhookSubscriptionRepository subscriptions, MerchantApi merchants,
                                      CallbackUrlValidator urlValidator, WebhookSecretGenerator secretGenerator,
                                      SecretCipher cipher) {
        this(subscriptions, merchants, urlValidator, secretGenerator, cipher, DATABASE_CLOCK);
    }

    WebhookSubscriptionService(WebhookSubscriptionRepository subscriptions, MerchantApi merchants,
                               CallbackUrlValidator urlValidator, WebhookSecretGenerator secretGenerator,
                               SecretCipher cipher, Clock clock) {
        this.subscriptions = subscriptions;
        this.merchants = merchants;
        this.urlValidator = urlValidator;
        this.secretGenerator = secretGenerator;
        this.cipher = cipher;
        this.clock = clock;
    }

    /**
     * Checks run cheapest first: approval, limit, event types, the URL (which may do a DNS lookup), then whether
     * another active subscription of this merchant already uses that URL. The merchant lock taken by the limit check
     * covers the URL check too.
     */
    @Transactional
    public WebhookWithSecretResponse create(UUID merchantId, CreateWebhookRequest request) {
        requireApproved(merchantId);
        requireRoomForOneMore(merchantId);
        Set<WebhookEventType> types = toEventTypes(request.eventTypes());
        String url = request.url().trim();
        urlValidator.validateForRegistration(url);
        requireUrlFree(merchantId, url, null);

        String secret = secretGenerator.newSecret();
        WebhookSubscription saved = subscriptions.save(
                WebhookSubscription.create(merchantId, url, cipher.encrypt(secret), types, now()));
        return WebhookWithSecretResponse.from(saved, secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookResponse> list(UUID merchantId) {
        return subscriptions.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .map(WebhookResponse::from)
                .toList();
    }

    /**
     * Changes only the fields sent. When the subscription ends up active with a new URL, or is being turned on, no
     * other active subscription of this merchant may use the same URL (checked under the merchant lock, so two
     * requests at the same moment can't both pass). Changing only the events or turning it off needs no URL check.
     */
    @Transactional
    public WebhookResponse update(UUID merchantId, UUID id, UpdateWebhookRequest request) {
        WebhookSubscription subscription = owned(merchantId, id);
        Instant now = now();
        boolean turningOn = Boolean.TRUE.equals(request.active()) && !subscription.isActive();
        boolean endsActive = Boolean.TRUE.equals(request.active())
                || (request.active() == null && subscription.isActive());
        String url = request.url() == null ? null : request.url().trim();

        if (request.eventTypes() != null) {
            subscription.changeEventTypes(toEventTypes(request.eventTypes()), now);
        }
        if (url != null) {
            urlValidator.validateForRegistration(url);
        }
        if (turningOn) {
            requireRoomForOneMore(merchantId); // takes the merchant lock
        }
        if (endsActive && (turningOn || url != null)) {
            subscriptions.lockMerchant(merchantId); // a URL change alone wouldn't have taken it yet
            requireUrlFree(merchantId, url != null ? url : subscription.getUrl(), subscription.getId());
        }
        if (url != null) {
            subscription.changeUrl(url, now);
        }
        if (turningOn) {
            subscription.activate(now);
        } else if (Boolean.FALSE.equals(request.active())) {
            subscription.deactivate(now);
        }
        return WebhookResponse.from(subscriptions.saveAndFlush(subscription));
    }

    /** Turns it off; the row and its history stay. Calling it again is fine. */
    @Transactional
    public void deactivate(UUID merchantId, UUID id) {
        owned(merchantId, id).deactivate(now());
    }

    /** Whether the merchant's subscription is on now (404 for another merchant's or an unknown one). */
    @Transactional(readOnly = true)
    public boolean isActive(UUID merchantId, UUID id) {
        return owned(merchantId, id).isActive();
    }

    @Transactional
    public WebhookWithSecretResponse rotateSecret(UUID merchantId, UUID id) {
        WebhookSubscription subscription = owned(merchantId, id);
        String secret = secretGenerator.newSecret();
        subscription.rotateSecret(cipher.encrypt(secret), now());
        return WebhookWithSecretResponse.from(subscriptions.saveAndFlush(subscription), secret);
    }

    private void requireApproved(UUID merchantId) {
        boolean approved = merchants.findByUserId(merchantId)
                .map(merchant -> merchant.approvalStatus() == ApprovalStatus.APPROVED)
                .orElse(false);
        if (!approved) {
            throw new ForbiddenException(ErrorCode.MERCHANT_NOT_APPROVED);
        }
    }

    /** Locks this merchant's subscriptions until the transaction ends, then checks the limit. */
    private void requireRoomForOneMore(UUID merchantId) {
        subscriptions.lockMerchant(merchantId);
        if (subscriptions.countByMerchantIdAndActiveTrue(merchantId) >= MAX_ACTIVE) {
            throw new ConflictException(ErrorCode.WEBHOOK_LIMIT_REACHED);
        }
    }

    /**
     * One active subscription per destination, per merchant: another merchant's subscriptions are never looked at,
     * turned-off ones don't count, and {@code exceptId} (the subscription being changed) is skipped. "Same" follows
     * {@link CallbackDestination} (letter case of the host, default port and the like are ignored; path and query
     * are not). Call it while holding the merchant lock.
     */
    private void requireUrlFree(UUID merchantId, String url, UUID exceptId) {
        CallbackDestination destination = CallbackDestination.of(url);
        boolean taken = subscriptions.findByMerchantIdOrderByCreatedAtDesc(merchantId).stream()
                .filter(WebhookSubscription::isActive)
                .filter(other -> !other.getId().equals(exceptId))
                .anyMatch(other -> CallbackDestination.of(other.getUrl()).equals(destination));
        if (taken) {
            throw new ConflictException(ErrorCode.WEBHOOK_URL_IN_USE);
        }
    }

    /** Another merchant's subscription is "not found", never "forbidden" (ids are not revealed). */
    private WebhookSubscription owned(UUID merchantId, UUID id) {
        return subscriptions.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
    }

    private static Set<WebhookEventType> toEventTypes(Set<String> wireNames) {
        EnumSet<WebhookEventType> types = EnumSet.noneOf(WebhookEventType.class);
        for (String wireName : wireNames) {
            WebhookEventType type = WebhookEventType.fromWireName(wireName)
                    .filter(WebhookEventType::subscribable)
                    .orElseThrow(() -> new FieldValidationException(EVENT_TYPES_FIELD,
                            "'" + wireName + "' isn't a webhook event"));
            types.add(type);
        }
        return types;
    }

    private Instant now() {
        return clock.instant();
    }
}
