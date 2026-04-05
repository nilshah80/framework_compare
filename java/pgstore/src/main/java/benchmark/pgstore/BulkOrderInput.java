package benchmark.pgstore;

import java.util.List;

public record BulkOrderInput(
    List<OrderItem> items,
    String currency
) {}
