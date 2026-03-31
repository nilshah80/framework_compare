package benchmark.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ErrorResponse(String error) {}
