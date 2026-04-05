package benchmark.pgstore;

public record Address(
    String street,
    String city,
    String state,
    String zip,
    String country
) {}
