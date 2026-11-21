package com.swiftdrop.delivery.internal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Removes proof photos that were uploaded but never attached to a delivery ({@link ProofPhotos#removeOrphans()}),
 * once an hour. On unless {@code swiftdrop.delivery.proof-photos.orphan-sweep-enabled=false}. The {@code @Scheduled}
 * method lives in this conditional class because {@code @EnableScheduling} is global (as in the webhooks module).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "swiftdrop.delivery.proof-photos", name = "orphan-sweep-enabled",
        havingValue = "true", matchIfMissing = true)
class ProofPhotoOrphanSweep {

    private static final Logger log = LoggerFactory.getLogger(ProofPhotoOrphanSweep.class);

    private final ProofPhotos proofPhotos;

    ProofPhotoOrphanSweep(ProofPhotos proofPhotos) {
        this.proofPhotos = proofPhotos;
    }

    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT1H")
    void sweep() {
        try {
            int removed = proofPhotos.removeOrphans();
            if (removed > 0) {
                log.info("Removed {} unattached proof photo(s).", removed);
            }
        } catch (RuntimeException ex) {
            log.warn("Proof photo clean-up skipped this round: {}", ex.getClass().getSimpleName());
        }
    }
}
