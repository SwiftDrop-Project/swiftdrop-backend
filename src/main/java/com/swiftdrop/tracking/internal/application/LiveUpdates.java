package com.swiftdrop.tracking.internal.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatusChanged;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.tracking.internal.dto.LiveEvent;
import com.swiftdrop.tracking.internal.dto.TrackingUpdate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns each {@link ParcelStatusChanged} into live updates: a public {@link TrackingUpdate} on
 * {@value #PARCEL_TOPIC}{trackingNumber} and a {@link LiveEvent} on {@value #LIVE_TOPIC} for the dispatchers' board.
 * Also keeps the last {@value #RECENT_SIZE} events in memory, so the board isn't empty when it opens, and writes the
 * parcel's latest status to the all-parcels index.
 *
 * <p>Events arrive at least once and may be late: one already seen, or older than the last one seen for its parcel,
 * is dropped.
 */
@Service
public class LiveUpdates {

    public static final String PARCEL_TOPIC = "/topic/parcels/";
    public static final String LIVE_TOPIC = "/topic/live";
    static final int RECENT_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(LiveUpdates.class);

    private final SimpMessagingTemplate messaging;
    private final DeliveryApi deliveries;
    private final ParcelIndexer indexer;
    private final Cache<UUID, Long> lastVersion = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(6))
            .maximumSize(100_000)
            .build();
    private final Deque<LiveEvent> recent = new ArrayDeque<>();

    public LiveUpdates(SimpMessagingTemplate messaging, DeliveryApi deliveries, ParcelIndexer indexer) {
        this.messaging = messaging;
        this.deliveries = deliveries;
        this.indexer = indexer;
    }

    public void handle(ParcelStatusChanged event) {
        if (event.parcelId() == null || event.trackingNumber() == null || event.toStatus() == null) {
            return;
        }
        if (!isNewer(event)) {
            log.debug("Skipped an old or repeated update for {}", event.trackingNumber());
            return;
        }
        FailureReason reason = failureReason(event.failureReason());
        messaging.convertAndSend(PARCEL_TOPIC + event.trackingNumber(), new TrackingUpdate(
                event.trackingNumber(), event.toStatus(), event.branch(), reason, event.occurredAt(),
                event.parcelVersion()));

        Optional<DeliverySnapshot> parcel = deliveries.findById(event.parcelId());
        District origin = parcel.map(p -> p.pickup() == null ? null : p.pickup().district()).orElse(null);
        District destination = parcel.map(p -> p.recipient() == null ? null : p.recipient().district()).orElse(null);
        LiveEvent live = new LiveEvent(event.eventId(), event.parcelId(), event.trackingNumber(), event.fromStatus(),
                event.toStatus(), event.branch(), origin, destination, reason, event.actorRole(), event.occurredAt(),
                event.parcelVersion());
        remember(live);
        messaging.convertAndSend(LIVE_TOPIC, live);
        parcel.ifPresent(indexer::index);
        log.debug("Pushed {} -> {}", event.trackingNumber(), event.toStatus());
    }

    /** The latest events, newest first, optionally only those that concern one branch. */
    public List<LiveEvent> recent(District branch, int limit) {
        synchronized (recent) {
            return recent.stream().filter(event -> event.concerns(branch)).limit(limit).toList();
        }
    }

    private boolean isNewer(ParcelStatusChanged event) {
        boolean[] newer = {false};
        lastVersion.asMap().compute(event.parcelId(), (id, seen) -> {
            if (seen != null && seen >= event.parcelVersion()) {
                return seen;
            }
            newer[0] = true;
            return event.parcelVersion();
        });
        return newer[0];
    }

    private void remember(LiveEvent event) {
        synchronized (recent) {
            recent.addFirst(event);
            while (recent.size() > RECENT_SIZE) {
                recent.removeLast();
            }
        }
    }

    private static FailureReason failureReason(String name) {
        if (name == null) {
            return null;
        }
        try {
            return FailureReason.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
