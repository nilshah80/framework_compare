package benchmark.pgstore;

import java.util.List;

public record BulkResult(
    List<Order> orders,
    double totalSum
) {}
