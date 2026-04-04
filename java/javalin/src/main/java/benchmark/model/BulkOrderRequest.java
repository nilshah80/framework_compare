package benchmark.model;

import java.util.List;

public class BulkOrderRequest {
    private List<OrderRequest> orders;

    public List<OrderRequest> getOrders() { return orders; }
    public void setOrders(List<OrderRequest> orders) { this.orders = orders; }
}
