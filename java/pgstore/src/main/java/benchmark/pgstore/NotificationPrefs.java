package benchmark.pgstore;

public record NotificationPrefs(
    boolean email,
    boolean sms,
    boolean push
) {}
