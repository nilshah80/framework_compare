package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OrderResponse(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("user_id") String userId,
    String status,
    List<OrderRequest.Item> items,
    double total,
    String currency,
    String fields,
    @JsonProperty("request_id") String requestId
) {}
