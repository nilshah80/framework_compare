using System.Text.Json.Serialization;

namespace controller_api;

public class CreateOrderReq
{
    [JsonPropertyName("items")]
    public List<OrderItem> Items { get; set; } = [];

    [JsonPropertyName("currency")]
    public string Currency { get; set; } = "";
}

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

public class OrderResponse
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

    [JsonPropertyName("fields")]
    public string Fields { get; set; } = "";

    [JsonPropertyName("request_id")]
    public string RequestId { get; set; } = "";
}
