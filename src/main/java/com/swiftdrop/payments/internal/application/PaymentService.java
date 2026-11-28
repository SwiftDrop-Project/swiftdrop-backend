package com.swiftdrop.payments.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.PaymentMethod;
import com.swiftdrop.delivery.RecordOnlinePaymentCommand;
import com.swiftdrop.payments.internal.domain.Payment;
import com.swiftdrop.payments.internal.domain.PaymentStatus;
import com.swiftdrop.payments.internal.dto.CardCheckResponse;
import com.swiftdrop.payments.internal.dto.CardPaymentRequest;
import com.swiftdrop.payments.internal.dto.PaymentResponse;
import com.swiftdrop.payments.internal.dto.PaymentSummaryResponse;
import com.swiftdrop.payments.internal.persistence.PaymentRepository;
import com.swiftdrop.payments.internal.provider.MockCardGateway;
import com.swiftdrop.shared.error.ApiException;
import com.swiftdrop.shared.error.ConflictException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The logged-in customer's payments. Every read first brings the records up to date with the parcels
 * ({@link PaymentSync}); if a card payment changes a record at the same moment, the update is simply tried again.
 * Someone else's payment is 404 NOT_FOUND, like an unknown id.
 */
@Service
public class PaymentService {

    /** The external reference delivery keeps for a mock card payment: "payments:" + this module's payment id. */
    static final String REFERENCE_PREFIX = "payments:";

    private final PaymentRepository payments;
    private final PaymentSync sync;
    private final DeliveryApi deliveries;
    private final MockCardGateway cards;
    private final TransactionTemplate transaction;

    PaymentService(PaymentRepository payments, PaymentSync sync, DeliveryApi deliveries, MockCardGateway cards,
                   PlatformTransactionManager transactions) {
        this.payments = payments;
        this.sync = sync;
        this.deliveries = deliveries;
        this.cards = cards;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Newest booking first. */
    public Page<PaymentResponse> list(UUID customerId, int page, int size) {
        upToDate(customerId);
        return payments.findByCustomerIdOrderByBookedAtDescIdDesc(customerId, PageRequest.of(page, size))
                .map(PaymentResponse::from);
    }

    public PaymentSummaryResponse summary(UUID customerId) {
        upToDate(customerId);
        return PaymentSummaryResponse.of(payments.findByCustomerId(customerId));
    }

    /**
     * Pays an online delivery's fee with a mock card. Only an ONLINE payment that is DUE can be paid (else 409
     * PAYMENT_NOT_PAYABLE); a test card that is declined changes nothing. An approved card is recorded in delivery
     * ({@link DeliveryApi#recordOnlinePayment}, reference {@code payments:<payment id>}) and here in one transaction,
     * so both are kept or neither; delivery's {@code feePaymentStatus} then says ONLINE_VERIFIED everywhere (receipts,
     * courier screens). Two payments at once: the second is 409.
     */
    public PaymentResponse payByCard(UUID customerId, UUID paymentId, CardPaymentRequest card) {
        return payInTransaction(card, () -> {
            Payment payment = payments.findByIdAndCustomerId(paymentId, customerId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
            DeliverySnapshot parcel = deliveries.findById(payment.getParcelId())
                    .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
            sync.follow(parcel, payment);
            return payment;
        });
    }

    /**
     * The same payment, found by the delivery: for "Create delivery" with an online payment, which books the parcel
     * and then pays its fee with the card checked in the payment gateway ({@link #checkCard}). The payment record is
     * made here if the Payments page hasn't made it yet. Someone else's delivery, a merchant order and an unknown id
     * are 404 NOT_FOUND.
     */
    public PaymentResponse payParcelByCard(UUID customerId, UUID parcelId, CardPaymentRequest card) {
        return payInTransaction(card, () -> {
            DeliverySnapshot parcel = deliveries.findById(parcelId)
                    .filter(found -> customerId.equals(found.customerId()))
                    .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
            return sync.recordFor(customerId, parcel);
        });
    }

    /**
     * The payment gateway step of "Create delivery": checks a mock card (test numbers only, not expired, not the
     * decline card) without charging or storing anything, so a declined card stops the customer before the delivery
     * is booked. Only the brand and last 4 digits come back.
     */
    public CardCheckResponse checkCard(CardPaymentRequest card) {
        MockCardGateway.Charge checked = cards.charge(card.cardNumber(), card.expiry());
        return new CardCheckResponse(checked.brand(), checked.last4());
    }

    /** Pays the payment the finder returns (already brought up to date with its parcel), all in one transaction. */
    private PaymentResponse payInTransaction(CardPaymentRequest card, Supplier<Payment> find) {
        try {
            return transaction.execute(status -> {
                Payment payment = find.get();
                if (payment.getMethod() != PaymentMethod.ONLINE || payment.getStatus() != PaymentStatus.DUE) {
                    throw new ConflictException(ErrorCode.PAYMENT_NOT_PAYABLE);
                }
                MockCardGateway.Charge charge = cards.charge(card.cardNumber(), card.expiry());
                DeliverySnapshot paid = recordInDelivery(payment);
                payment.payByCard(charge.brand(), charge.last4(), Instant.now());
                sync.follow(paid, payment);
                return PaymentResponse.from(payments.saveAndFlush(payment));
            });
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException alreadyPaid) {
            // Two payments at once: the version check here, or delivery's one-payment-per-parcel constraint.
            throw new ConflictException(ErrorCode.PAYMENT_NOT_PAYABLE);
        }
    }

    /** Delivery's refusals (already paid, or not an online fee) are this API's 409 PAYMENT_NOT_PAYABLE. */
    private DeliverySnapshot recordInDelivery(Payment payment) {
        try {
            return deliveries.recordOnlinePayment(
                    new RecordOnlinePaymentCommand(payment.getParcelId(), REFERENCE_PREFIX + payment.getId()));
        } catch (ApiException refused) {
            if (refused.code() == ErrorCode.FEE_ALREADY_COLLECTED
                    || refused.code() == ErrorCode.FEE_COLLECTION_NOT_ALLOWED) {
                throw new ConflictException(ErrorCode.PAYMENT_NOT_PAYABLE);
            }
            throw refused;
        }
    }

    private void upToDate(UUID customerId) {
        try {
            sync.syncCustomer(customerId);
        } catch (OptimisticLockingFailureException changedMeanwhile) {
            sync.syncCustomer(customerId);
        }
    }
}
