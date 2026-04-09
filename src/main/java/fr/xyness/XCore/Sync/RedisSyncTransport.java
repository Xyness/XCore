package fr.xyness.XCore.Sync;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis Pub/Sub based sync transport.
 * <p>
 * All channels are multiplexed over a single Redis Pub/Sub channel {@code xcore:sync}.
 * A daemon subscriber thread auto-reconnects on failure with a 5-second backoff.
 * </p>
 */
class RedisSyncTransport implements SyncManager.SyncTransport {

    private static final String REDIS_CHANNEL = "xcore:sync";

    private final JedisPool jedisPool;
    private final SyncManager.TransportCallback callback;
    private final SyncManager.LogCallback logDebug;
    private final SyncManager.LogCallback logWarning;
    private volatile Thread subscriberThread;
    private volatile JedisPubSub subscriber;

    RedisSyncTransport(JedisPool jedisPool, SyncManager.TransportCallback callback,
                       SyncManager.LogCallback logDebug, SyncManager.LogCallback logWarning) {
        this.jedisPool = jedisPool;
        this.callback = callback;
        this.logDebug = logDebug;
        this.logWarning = logWarning;
    }

    @Override
    public void start() {
        subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (!REDIS_CHANNEL.equals(channel)) return;
                callback.onRawMessage(message);
            }
        };

        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(subscriber, REDIS_CHANNEL);
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) break;
                    logWarning.log("Redis Pub/Sub disconnected, reconnecting in 5s : " + e.getMessage());
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }, "XCore-SyncPubSub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        logDebug.log("Redis sync subscriber started on channel '" + REDIS_CHANNEL + "'.");
    }

    @Override
    public void stop() {
        if (subscriber != null) {
            try { subscriber.unsubscribe(); } catch (Exception e) {
                logDebug.log("Failed to unsubscribe Redis sync subscriber: " + e.getMessage());
            }
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    @Override
    public void publish(String formattedMessage) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(REDIS_CHANNEL, formattedMessage);
        }
    }
}
