package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentMethod(
    String type,
    String last4,
    @JsonProperty("expiry_month") int expiryMonth,
    @JsonProperty("expiry_year") int expiryYear,
    @JsonProperty("is_default") boolean isDefault
) {}
