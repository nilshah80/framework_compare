package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class OrderResponse {
    @JsonProperty("order_id")
    private String orderId;
    @JsonProperty("user_id")
    private String userId;
    private String status;
    private List<OrderRequest.Item> items;
    private double total;
    private String currency;
    private String fields;
    @JsonProperty("request_id")
    private String requestId;

    public OrderResponse() {}

    public OrderResponse(String orderId, String userId, String status, List<OrderRequest.Item> items,
                         double total, String currency, String fields, String requestId) {
        this.orderId = orderId;
        this.userId = userId;
        this.status = status;
        this.items = items;
        this.total = total;
        this.currency = currency;
        this.fields = fields;
        this.requestId = requestId;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<OrderRequest.Item> getItems() { return items; }
    public void setItems(List<OrderRequest.Item> items) { this.items = items; }
    public double getTotal() { return total; }
    public void setTotal(double total) { this.total = total; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getFields() { return fields; }
    public void setFields(String fields) { this.fields = fields; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
