package benchmark.model;

import java.util.List;

public record BulkOrderRequest(List<OrderRequest> orders) {}
