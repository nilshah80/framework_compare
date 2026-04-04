package benchmark.model;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record NotificationPrefs(boolean email, boolean sms, boolean push) {}
