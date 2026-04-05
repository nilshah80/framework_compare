package benchmark.pgstore;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Order(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("user_id") String userId,
    String status,
    List<OrderItem> items,
    double total,
    String currency
) {}
