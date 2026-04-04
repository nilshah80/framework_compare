package benchmark.model;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
public record BulkOrderRequest(List<OrderRequest> orders) {}
