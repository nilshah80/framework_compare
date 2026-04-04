package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record BulkOrderResponse(
    @JsonProperty("user_id") String userId,
    int count,
    List<OrderResponse> orders,
    @JsonProperty("total_sum") double totalSum,
    @JsonProperty("request_id") String requestId
) {}
