package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OrderRequest(
    List<Item> items,
    String currency
) {
    public record Item(
        @JsonProperty("product_id") String productId,
        String name,
        int quantity,
        double price
    ) {}
}
