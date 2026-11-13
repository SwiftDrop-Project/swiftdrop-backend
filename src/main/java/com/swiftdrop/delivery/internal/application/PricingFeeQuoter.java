package com.swiftdrop.delivery.internal.application;

import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.ParcelSize;
import com.swiftdrop.delivery.internal.domain.ParcelPrice;
import com.swiftdrop.pricing.PriceQuote;
import com.swiftdrop.pricing.PricingApi;
import com.swiftdrop.pricing.QuoteRequest;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prices a new parcel with pricing's quote: the pickup and drop-off districts, the weight, the measurements and
 * whether cash is collected. The parcel keeps the fee with the vehicle, billable weight, rate card version and the
 * fee lines, so the price can be reproduced later.
 */
@Component
class PricingFeeQuoter implements FeeQuoter {

    private final PricingApi pricing;
    private final JsonMapper jsonMapper;

    PricingFeeQuoter(PricingApi pricing, JsonMapper jsonMapper) {
        this.pricing = pricing;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ParcelPrice quote(CreateDeliveryCommand command) {
        PriceQuote quote = pricing.quote(quoteRequest(command));
        if (quote.status() == PriceQuote.Status.MANUAL_QUOTE) {
            throw new BusinessRuleException(ErrorCode.PARCEL_NEEDS_MANUAL_QUOTE);
        }
        return new ParcelPrice(quote.total(), quote.currency(),
                quote.vehicle() == null ? null : quote.vehicle().name(), quote.billableWeightKg(),
                quote.rateCardVersion(), jsonMapper.writeValueAsString(quote.lines()));
    }

    /** Without measurements pricing can't choose the vehicle, so the quote has no local legs (provisional). */
    static QuoteRequest quoteRequest(CreateDeliveryCommand command) {
        ParcelSize size = command.size();
        return new QuoteRequest(command.pickup().district(), command.recipient().district(), command.weightKg(),
                size == null ? null : size.lengthCm(), size == null ? null : size.widthCm(),
                size == null ? null : size.heightCm(), size != null && size.keepUpright(),
                command.codAmount() != null);
    }
}
