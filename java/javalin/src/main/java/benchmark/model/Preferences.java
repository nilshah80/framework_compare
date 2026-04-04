package benchmark.model;

public class Preferences {
    private String language;
    private String currency;
    private String timezone;
    private NotificationPrefs notifications;
    private String theme;

    public Preferences() {}

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public NotificationPrefs getNotifications() { return notifications; }
    public void setNotifications(NotificationPrefs notifications) { this.notifications = notifications; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
}
