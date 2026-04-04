package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class UserProfile {
    @JsonProperty("user_id")
    private String userId;
    private String name;
    private String email;
    private String phone;
    private Address address;
    private Preferences preferences;
    @JsonProperty("payment_methods")
    private List<PaymentMethod> paymentMethods;
    private List<String> tags;
    private Map<String, String> metadata;
    @JsonProperty("request_id")
    private String requestId;

    public UserProfile() {}

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
    public Preferences getPreferences() { return preferences; }
    public void setPreferences(Preferences preferences) { this.preferences = preferences; }
    public List<PaymentMethod> getPaymentMethods() { return paymentMethods; }
    public void setPaymentMethods(List<PaymentMethod> paymentMethods) { this.paymentMethods = paymentMethods; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
