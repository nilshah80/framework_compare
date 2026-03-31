package benchmark.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DeleteResponse {
    private String message;
    @JsonProperty("order_id")
    private String orderId;
    @JsonProperty("request_id")
    private String requestId;

    public DeleteResponse() {}

    public DeleteResponse(String message, String orderId, String requestId) {
        this.message = message;
        this.orderId = orderId;
        this.requestId = requestId;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
}
