using System.Text.Json.Serialization;

namespace PgStore;

public class OrderItem
{
    [JsonPropertyName("product_id")]
    public string ProductId { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("quantity")]
    public int Quantity { get; set; }

    [JsonPropertyName("price")]
    public double Price { get; set; }
}

public class Order
{
    [JsonPropertyName("order_id")]
    public string OrderId { get; set; } = "";

    [JsonPropertyName("user_id")]
    public string UserId { get; set; } = "";

    [JsonPropertyName("status")]
    public string Status { get; set; } = "";

    [JsonPropertyName("items")]
    public List<OrderItem> Items { get; set; } = [];

    [JsonPropertyName("total")]
    public double Total { get; set; }

    [JsonPropertyName("currency")]
    public string Currency { get; set; } = "";
}

public class Profile
{
    [JsonPropertyName("user_id")]
    public string UserId { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("email")]
    public string Email { get; set; } = "";

    [JsonPropertyName("phone")]
    public string Phone { get; set; } = "";

    [JsonPropertyName("address")]
    public Address Address { get; set; } = new();

    [JsonPropertyName("preferences")]
    public Preferences Preferences { get; set; } = new();

    [JsonPropertyName("payment_methods")]
    public List<PaymentMethod> PaymentMethods { get; set; } = [];

    [JsonPropertyName("tags")]
    public List<string> Tags { get; set; } = [];

    [JsonPropertyName("metadata")]
    public Dictionary<string, string> Metadata { get; set; } = new();
}

public class Address
{
    [JsonPropertyName("street")]
    public string Street { get; set; } = "";

    [JsonPropertyName("city")]
    public string City { get; set; } = "";

    [JsonPropertyName("state")]
    public string State { get; set; } = "";

    [JsonPropertyName("zip")]
    public string Zip { get; set; } = "";

    [JsonPropertyName("country")]
    public string Country { get; set; } = "";
}

public class Preferences
{
    [JsonPropertyName("language")]
    public string Language { get; set; } = "";

    [JsonPropertyName("currency")]
    public string Currency { get; set; } = "";

    [JsonPropertyName("timezone")]
    public string Timezone { get; set; } = "";

    [JsonPropertyName("notifications")]
    public NotificationPrefs Notifications { get; set; } = new();

    [JsonPropertyName("theme")]
    public string Theme { get; set; } = "";
}

public class NotificationPrefs
{
    [JsonPropertyName("email")]
    public bool Email { get; set; }

    [JsonPropertyName("sms")]
    public bool Sms { get; set; }

    [JsonPropertyName("push")]
    public bool Push { get; set; }
}

public class PaymentMethod
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "";

    [JsonPropertyName("last4")]
    public string Last4 { get; set; } = "";

    [JsonPropertyName("expiry_month")]
    public int ExpiryMonth { get; set; }

    [JsonPropertyName("expiry_year")]
    public int ExpiryYear { get; set; }

    [JsonPropertyName("is_default")]
    public bool IsDefault { get; set; }
}
