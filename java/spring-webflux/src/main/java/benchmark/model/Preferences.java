package benchmark.model;

public record Preferences(
    String language,
    String currency,
    String timezone,
    NotificationPrefs notifications,
    String theme
) {}
