package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record OrderRequest(
    List<Item> items,
    String currency
) {
    @Serdeable
    public record Item(
        @JsonProperty("product_id") String productId,
        String name,
        int quantity,
        double price
    ) {}
}
