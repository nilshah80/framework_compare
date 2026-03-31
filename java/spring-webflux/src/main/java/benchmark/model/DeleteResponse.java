package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteResponse(
    String message,
    @JsonProperty("order_id") String orderId,
    @JsonProperty("request_id") String requestId
) {}
