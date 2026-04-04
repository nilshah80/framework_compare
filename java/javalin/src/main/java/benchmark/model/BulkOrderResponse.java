package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class BulkOrderResponse {
    @JsonProperty("user_id")
    private String userId;
    private int count;
    private List<OrderResponse> orders;
    @JsonProperty("total_sum")
    private double totalSum;
    @JsonProperty("request_id")
    private String requestId;

    public BulkOrderResponse() {}

    public BulkOrderResponse(String userId, int count, List<OrderResponse> orders, double totalSum, String requestId) {
        this.userId = userId;
        this.count = count;
        this.orders = orders;
        this.totalSum = totalSum;
        this.requestId = requestId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public List<OrderResponse> getOrders() { return orders; }
    public void setOrders(List<OrderResponse> orders) { this.orders = orders; }
    public double getTotalSum() { return totalSum; }
    public void setTotalSum(double totalSum) { this.totalSum = totalSum; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
