package fr.xyness.XCore.Commands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.mojang.brigadier.suggestion.SuggestionProvider;

import fr.xyness.XCore.XCore;
import fr.xyness.XCore.Models.PlayerData;

import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * The bits of command plumbing every addon was rewriting: suggestions, target resolution and the
 * silent flag.
 */
@SuppressWarnings("UnstableApiUsage")
public final class CommandHelpers {

    /** Durations offered when a command asks for one. */
    private static final String[] COMMON_DURATIONS = {"10m", "30m", "1h", "6h", "12h", "1d", "7d", "30d"};

    private CommandHelpers() {}

    /**
     * Suggests the names of players currently connected.
     *
     * @return A suggestion provider.
     */
    public static SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(input)) builder.suggest(player.getName());
            }
            return builder.buildFuture();
        };
    }

    /**
     * Suggests connected players, and offline names once at least three letters are typed.
     *
     * <p>The offline half only looks at what is already cached, so it costs nothing: suggestions run
     * on the server thread and have no business waiting on a query.</p>
     *
     * @param core The plugin instance.
     * @return A suggestion provider.
     */
    public static SuggestionProvider<CommandSourceStack> knownPlayers(XCore core) {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            List<String> seen = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(input)) {
                    builder.suggest(player.getName());
                    seen.add(player.getName().toLowerCase());
                }
            }
            if (input.length() >= 3) {
                for (org.bukkit.OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
                    String name = offline.getName();
                    if (name == null) continue;
                    String lower = name.toLowerCase();
                    if (!lower.startsWith(input) || seen.contains(lower)) continue;
                    builder.suggest(name);
                    seen.add(lower);
                    if (seen.size() > 40) break;
                }
            }
            return builder.buildFuture();
        };
    }

    /**
     * Suggests the usual durations, plus {@code permanent}.
     *
     * @return A suggestion provider.
     */
    public static SuggestionProvider<CommandSourceStack> durations() {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            for (String value : COMMON_DURATIONS) {
                if (value.startsWith(input)) builder.suggest(value);
            }
            if ("permanent".startsWith(input)) builder.suggest("permanent");
            return builder.buildFuture();
        };
    }

    /**
     * Suggests a fixed list.
     *
     * @param values What to offer.
     * @return A suggestion provider.
     */
    public static SuggestionProvider<CommandSourceStack> of(Collection<String> values) {
        return (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            for (String value : values) {
                if (value.toLowerCase().startsWith(input)) builder.suggest(value);
            }
            return builder.buildFuture();
        };
    }

    /**
     * Finds a player by name, online or not.
     *
     * <p>Goes through the cache, then the database, and only then asks Mojang — so a name that has
     * ever been on the server is answered without a network call.</p>
     *
     * @param core The plugin instance.
     * @param name The name typed by the sender.
     * @return A future completing with the player's data, empty when nobody matches.
     */
    public static CompletableFuture<Optional<PlayerData>> resolve(XCore core, String name) {
        if (name == null || !isValidName(name)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return core.playerCache().getPlayer(name);
    }

    /**
     * Finds the UUID behind a name.
     *
     * @param core The plugin instance.
     * @param name The name typed by the sender.
     * @return A future completing with the UUID, empty when nobody matches.
     */
    public static CompletableFuture<Optional<UUID>> resolveUuid(XCore core, String name) {
        return resolve(core, name).thenApply(opt -> opt.map(PlayerData::getUuid));
    }

    /**
     * Checks a name against Minecraft's own rules before it reaches a query.
     *
     * @param name The name to test.
     * @return Whether it could be a player name at all.
     */
    public static boolean isValidName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_]{1,16}");
    }

    /**
     * Pulls the {@code -s} flag out of a command's arguments.
     *
     * @param arguments The raw arguments.
     * @return The flag, and the arguments without it.
     */
    public static Parsed parseFlags(String[] arguments) {
        if (arguments == null || arguments.length == 0) return new Parsed(false, new String[0]);
        List<String> kept = new ArrayList<>(arguments.length);
        boolean silent = false;
        for (String argument : arguments) {
            if ("-s".equalsIgnoreCase(argument) || "--silent".equalsIgnoreCase(argument)) {
                silent = true;
                continue;
            }
            kept.add(argument);
        }
        return new Parsed(silent, kept.toArray(new String[0]));
    }

    /**
     * The result of {@link #parseFlags(String[])}.
     *
     * @param silent    Whether the sender asked for no broadcast.
     * @param arguments What is left once the flags are removed.
     */
    public record Parsed(boolean silent, String[] arguments) {

        /**
         * Joins the remaining arguments from an index, for a trailing reason.
         *
         * @param from        The first index to take.
         * @param fallback    What to return when there is nothing left.
         * @return The joined text.
         */
        public String join(int from, String fallback) {
            if (from >= arguments.length) return fallback;
            return String.join(" ", java.util.Arrays.copyOfRange(arguments, from, arguments.length));
        }
    }
}
