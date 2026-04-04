package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PaymentMethod {
    private String type;
    private String last4;
    @JsonProperty("expiry_month")
    private int expiryMonth;
    @JsonProperty("expiry_year")
    private int expiryYear;
    @JsonProperty("is_default")
    private boolean isDefault;

    public PaymentMethod() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }
    public int getExpiryMonth() { return expiryMonth; }
    public void setExpiryMonth(int expiryMonth) { this.expiryMonth = expiryMonth; }
    public int getExpiryYear() { return expiryYear; }
    public void setExpiryYear(int expiryYear) { this.expiryYear = expiryYear; }
    public boolean isIsDefault() { return isDefault; }
    public void setIsDefault(boolean isDefault) { this.isDefault = isDefault; }
}
