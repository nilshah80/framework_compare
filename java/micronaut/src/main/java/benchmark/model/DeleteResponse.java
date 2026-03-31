package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record DeleteResponse(
    String message,
    @JsonProperty("order_id") String orderId,
    @JsonProperty("request_id") String requestId
) {}
