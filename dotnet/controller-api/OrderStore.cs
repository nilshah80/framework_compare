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

    public List<OrderResponse> GetByUser(string userId)
    {
        var prefix = $"{userId}:";
        var results = new List<OrderResponse>();
        foreach (var kvp in _store)
        {
            if (kvp.Key.StartsWith(prefix))
                results.Add(kvp.Value);
        }
        return results;
    }
}

public class ProfileStore
{
    private readonly ConcurrentDictionary<string, UserProfile> _store = new();

    public void Set(string userId, UserProfile profile) => _store[userId] = profile;

    public bool TryGet(string userId, out UserProfile? profile) => _store.TryGetValue(userId, out profile);
}
