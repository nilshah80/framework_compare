package benchmark.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Address(String street, String city, String state, String zip, String country) {}
