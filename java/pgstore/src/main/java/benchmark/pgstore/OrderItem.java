package benchmark.pgstore;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OrderItem(
    @JsonProperty("product_id") String productId,
    String name,
    int quantity,
    double price
) {}
