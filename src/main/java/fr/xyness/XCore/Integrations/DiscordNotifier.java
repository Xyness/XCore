package fr.xyness.XCore.Integrations;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import fr.xyness.XCore.XCore;

/**
 * Sends messages to Discord webhooks, one queue for the whole installation.
 *
 * <p>Posts are queued and sent from a background task. A webhook that answers 429 is left alone for
 * as long as Discord asks, then its messages go out.</p>
 *
 * <pre>{@code
 * core().discord().send(url, DiscordNotifier.embed()
 *         .title("Ban")
 *         .description(player + " was banned by " + staff)
 *         .color(0xC0362C)
 *         .field("Reason", reason, false)
 *         .build());
 * }</pre>
 */
public class DiscordNotifier {

    /** How many posts leave per pass, so one addon cannot monopolise the queue. */
    private static final int PER_PASS = 5;

    /** Dropped after this many failed attempts. */
    private static final int MAX_ATTEMPTS = 3;

    private final XCore main;
    private final Queue<Post> queue = new ConcurrentLinkedQueue<>();

    /** When each webhook may be used again, in milliseconds. */
    private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();

    private final LongAdder sent = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();

    private Object task;
    private volatile String username;
    private volatile String avatarUrl;

    /**
     * @param main The plugin instance.
     */
    public DiscordNotifier(XCore main) {
        this.main = main;
        reload();
    }

    /** Re-reads the identity used when an addon does not set one. */
    public final void reload() {
        this.username = main.getConfig().getString("integrations.discord.username", "XCore");
        this.avatarUrl = main.getConfig().getString("integrations.discord.avatar-url", "");
    }

    /** Starts the sender. */
    public void start() {
        if (task != null) return;
        task = main.schedulerAdapter().runAsyncTaskTimer(this::drain, 40L, 20L);
    }

    /** Stops the sender and tries to flush what is left. */
    public void stop() {
        if (task != null) {
            main.schedulerAdapter().cancelTask(task);
            task = null;
        }
        for (int i = 0; i < 3 && !queue.isEmpty(); i++) drain();
    }

    /**
     * Queues an embed.
     *
     * @param webhookUrl The webhook to post to. Ignored when blank.
     * @param embed      The embed to send.
     */
    public void send(String webhookUrl, Embed embed) {
        if (webhookUrl == null || webhookUrl.isBlank() || embed == null) return;
        JsonObject body = new JsonObject();
        applyIdentity(body);
        JsonArray embeds = new JsonArray();
        embeds.add(embed.toJson());
        body.add("embeds", embeds);
        queue.add(new Post(webhookUrl, body.toString(), 0));
    }

    /**
     * Queues a plain message.
     *
     * @param webhookUrl The webhook to post to.
     * @param content    The text, trimmed to Discord's 2000 character limit.
     */
    public void sendText(String webhookUrl, String content) {
        if (webhookUrl == null || webhookUrl.isBlank() || content == null || content.isBlank()) return;
        JsonObject body = new JsonObject();
        applyIdentity(body);
        body.addProperty("content", content.length() > 2000 ? content.substring(0, 2000) : content);
        queue.add(new Post(webhookUrl, body.toString(), 0));
    }

    /**
     * Queues a payload an addon has already assembled.
     *
     * <p>For addons that build their own embeds — with their own language keys and colours — but
     * want the queue, the retries and the rate-limit handling.</p>
     *
     * @param webhookUrl The webhook to post to.
     * @param jsonBody   The complete Discord payload.
     */
    public void sendRaw(String webhookUrl, String jsonBody) {
        if (webhookUrl == null || webhookUrl.isBlank() || jsonBody == null || jsonBody.isBlank()) return;
        queue.add(new Post(webhookUrl, jsonBody, 0));
    }

    private void applyIdentity(JsonObject body) {
        if (username != null && !username.isBlank()) body.addProperty("username", username);
        if (avatarUrl != null && !avatarUrl.isBlank()) body.addProperty("avatar_url", avatarUrl);
    }

    /** @return How many posts are still waiting. */
    public int queueSize() {
        return queue.size();
    }

    /** @return How many posts have gone through since startup. */
    public long getSentCount() {
        return sent.sum();
    }

    /** @return How many were given up on. */
    public long getFailedCount() {
        return failed.sum();
    }

    /** @return How many times Discord asked us to slow down. */
    public long getRateLimitCount() {
        return rateLimited.sum();
    }

    /** Sends up to {@link #PER_PASS} posts, skipping webhooks that are still rate limited. */
    private void drain() {
        if (queue.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Post> postponed = new ArrayList<>();
        int handled = 0;

        Post post;
        while (handled < PER_PASS && (post = queue.poll()) != null) {
            Long until = blockedUntil.get(post.url());
            if (until != null && until > now) {
                postponed.add(post);
                continue;
            }
            handled++;
            deliver(post);
        }
        queue.addAll(postponed);
    }

    private void deliver(Post post) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(post.url()).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "XCore");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);

            byte[] payload = post.body().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            if (code == 429) {
                rateLimited.increment();
                long retryAfter = connection.getHeaderFieldLong("Retry-After", 5);
                // The header is in seconds on webhooks, but some proxies answer in milliseconds.
                long waitMs = retryAfter > 1000 ? retryAfter : retryAfter * 1000L;
                blockedUntil.put(post.url(), System.currentTimeMillis() + Math.min(waitMs, 60_000L));
                requeue(post);
                return;
            }
            if (code >= 200 && code < 300) {
                sent.increment();
                return;
            }
            main.logger().sendDebug("Discord webhook answered " + code + ".");
            requeue(post);
        } catch (Exception e) {
            main.logger().sendDebug("Discord webhook failed : " + e.getMessage());
            requeue(post);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void requeue(Post post) {
        if (post.attempts() + 1 >= MAX_ATTEMPTS) {
            failed.increment();
            return;
        }
        queue.add(new Post(post.url(), post.body(), post.attempts() + 1));
    }

    /** @return A new embed builder. */
    public static Embed.Builder embed() {
        return new Embed.Builder();
    }

    /** One post waiting to be sent. */
    private record Post(String url, String body, int attempts) {}

    /** A Discord embed. Build one with {@link DiscordNotifier#embed()}. */
    public static final class Embed {

        private final JsonObject json;

        private Embed(JsonObject json) {
            this.json = json;
        }

        JsonObject toJson() {
            return json;
        }

        /** Assembles an embed field by field. */
        public static final class Builder {

            private final JsonObject json = new JsonObject();
            private final JsonArray fields = new JsonArray();

            /**
             * @param title The embed title.
             * @return This builder.
             */
            public Builder title(String title) {
                if (title != null) json.addProperty("title", title);
                return this;
            }

            /**
             * @param description The body text.
             * @return This builder.
             */
            public Builder description(String description) {
                if (description != null) json.addProperty("description", description);
                return this;
            }

            /**
             * @param rgb The bar colour, as {@code 0xRRGGBB}.
             * @return This builder.
             */
            public Builder color(int rgb) {
                json.addProperty("color", rgb);
                return this;
            }

            /**
             * @param url A link on the title.
             * @return This builder.
             */
            public Builder url(String url) {
                if (url != null && !url.isBlank()) json.addProperty("url", url);
                return this;
            }

            /**
             * @param url The small image on the right.
             * @return This builder.
             */
            public Builder thumbnail(String url) {
                if (url != null && !url.isBlank()) {
                    JsonObject thumb = new JsonObject();
                    thumb.addProperty("url", url);
                    json.add("thumbnail", thumb);
                }
                return this;
            }

            /**
             * @param text The footer line.
             * @return This builder.
             */
            public Builder footer(String text) {
                if (text != null && !text.isBlank()) {
                    JsonObject footer = new JsonObject();
                    footer.addProperty("text", text);
                    json.add("footer", footer);
                }
                return this;
            }

            /**
             * Adds a field.
             *
             * @param name   The field name.
             * @param value  The field value.
             * @param inline Whether it sits next to the previous one.
             * @return This builder.
             */
            public Builder field(String name, String value, boolean inline) {
                if (name == null || value == null) return this;
                JsonObject field = new JsonObject();
                field.addProperty("name", name);
                field.addProperty("value", value);
                field.addProperty("inline", inline);
                fields.add(field);
                return this;
            }

            /** Stamps the embed with the current time. */
            public Builder timestamp() {
                json.addProperty("timestamp", java.time.OffsetDateTime.now().toString());
                return this;
            }

            /** @return The finished embed. */
            public Embed build() {
                if (!fields.isEmpty()) json.add("fields", fields);
                return new Embed(json);
            }
        }
    }
}
