package benchmark.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record Preferences(
    String language,
    String currency,
    String timezone,
    NotificationPrefs notifications,
    String theme
) {}
