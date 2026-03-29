using System.Collections.Concurrent;

namespace controller_api;

public class OrderStore
{
    private readonly ConcurrentDictionary<string, OrderResponse> _store = new();
    private int _counter;

    public string NextOrderId() => Interlocked.Increment(ref _counter).ToString();

    public static string Key(string userId, string orderId) => $"{userId}:{orderId}";

    public void Set(string key, OrderResponse order) => _store[key] = order;

    public bool TryGet(string key, out OrderResponse? order) => _store.TryGetValue(key, out order);

    public bool Contains(string key) => _store.ContainsKey(key);

    public bool TryRemove(string key) => _store.TryRemove(key, out _);
}
