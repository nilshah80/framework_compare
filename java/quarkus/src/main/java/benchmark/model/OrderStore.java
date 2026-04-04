package benchmark.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class OrderStore {
    private static final OrderStore INSTANCE = new OrderStore();
    private final ConcurrentHashMap<String, OrderResponse> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserProfile> profileStore = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(0);

    private OrderStore() {}

    public static OrderStore getInstance() {
        return INSTANCE;
    }

    public long nextId() {
        return counter.incrementAndGet();
    }

    public void put(String userId, String orderId, OrderResponse order) {
        store.put(userId + ":" + orderId, order);
    }

    public OrderResponse get(String userId, String orderId) {
        return store.get(userId + ":" + orderId);
    }

    public OrderResponse remove(String userId, String orderId) {
        return store.remove(userId + ":" + orderId);
    }

    public List<OrderResponse> listByUser(String userId) {
        String prefix = userId + ":";
        List<OrderResponse> results = new ArrayList<>();
        store.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                results.add(value);
            }
        });
        return results;
    }

    public void putProfile(String userId, UserProfile profile) {
        profileStore.put(userId, profile);
    }

    public UserProfile getProfile(String userId) {
        return profileStore.get(userId);
    }
}
