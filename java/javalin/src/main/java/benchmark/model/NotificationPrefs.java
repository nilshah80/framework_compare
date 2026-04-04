package benchmark.model;

public class NotificationPrefs {
    private boolean email;
    private boolean sms;
    private boolean push;

    public NotificationPrefs() {}

    public boolean isEmail() { return email; }
    public void setEmail(boolean email) { this.email = email; }
    public boolean isSms() { return sms; }
    public void setSms(boolean sms) { this.sms = sms; }
    public boolean isPush() { return push; }
    public void setPush(boolean push) { this.push = push; }
}
