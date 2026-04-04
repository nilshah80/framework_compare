package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record UserProfile(
    @JsonProperty("user_id") String userId,
    String name,
    String email,
    String phone,
    Address address,
    Preferences preferences,
    @JsonProperty("payment_methods") List<PaymentMethod> paymentMethods,
    List<String> tags,
    Map<String, String> metadata,
    @JsonProperty("request_id") String requestId
) {}
