package fr.xyness.XCore.Integrations;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Answers "what rank is this player", whichever permission plugin is installed.
 *
 * <p>Three addons needed this and each found its own way there: XBans reads LuckPerms for staff
 * immunity, the auction house scans permissions for a tax rate, and rank prefixes come from Vault
 * elsewhere. The lookups are cached for a few seconds because they end up inside listeners.</p>
 *
 * <p>LuckPerms is reached by reflection, so nothing here needs it at compile time and everything
 * degrades to Vault, then to plain permission checks.</p>
 */
public class RankResolver {

    /** Group names, held briefly: a permission lookup in a listener is not free. */
    private final Cache<java.util.UUID, String> groupCache = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.SECONDS)
            .maximumSize(1_000)
            .build();

    private final boolean luckPerms;
    private final boolean vault;

    /** Resolved once: {@code LuckPermsProvider.get()}. */
    private Object luckPermsApi;

    /**
     * Detects what is available.
     */
    public RankResolver() {
        this.luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
        this.vault = Bukkit.getPluginManager().getPlugin("Vault") != null;
        if (luckPerms) {
            try {
                Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
                luckPermsApi = provider.getMethod("get").invoke(null);
            } catch (Throwable ignored) {
                luckPermsApi = null;
            }
        }
    }

    /** @return Whether a permission plugin able to name groups was found. */
    public boolean hasGroups() {
        return luckPermsApi != null || vault;
    }

    /**
     * The player's primary group.
     *
     * @param player The player.
     * @return The group name, or {@code "default"} when nothing can tell.
     */
    public String primaryGroup(Player player) {
        if (player == null) return "default";
        return groupCache.get(player.getUniqueId(), uuid -> {
            String fromLuckPerms = luckPermsGroup(player);
            if (fromLuckPerms != null) return fromLuckPerms;
            String fromVault = vaultGroup(player);
            return fromVault != null ? fromVault : "default";
        });
    }

    /**
     * The weight of the player's primary group, as LuckPerms defines it.
     *
     * @param player The player.
     * @return The weight, 0 when it is unknown.
     */
    public int weight(Player player) {
        if (luckPermsApi == null || player == null) return 0;
        try {
            Object user = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            Object loaded = user.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(user, player.getUniqueId());
            if (loaded == null) return 0;
            String groupName = (String) loaded.getClass().getMethod("getPrimaryGroup").invoke(loaded);

            Object groups = luckPermsApi.getClass().getMethod("getGroupManager").invoke(luckPermsApi);
            Object group = groups.getClass().getMethod("getGroup", String.class).invoke(groups, groupName);
            if (group == null) return 0;

            Object weight = group.getClass().getMethod("getWeight").invoke(group);
            if (weight instanceof java.util.OptionalInt optional) {
                return optional.orElse(0);
            }
            return 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * The highest number a player holds under a numbered permission family.
     *
     * <p>This is the shape behind {@code xbans.immunity.3} or {@code ah.limit.50}: the node exists in
     * as many variants as there are ranks, and what matters is the largest one the player has.</p>
     *
     * @param player The player.
     * @param prefix The permission prefix, dot included, e.g. {@code "xbans.immunity."}.
     * @return The highest number found, 0 when the player holds none.
     */
    public int level(Player player, String prefix) {
        if (player == null || prefix == null || prefix.isBlank()) return 0;
        int best = 0;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String node = info.getPermission();
            if (!node.startsWith(prefix)) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException ignored) {
                // Not a numbered node, nothing to compare.
            }
        }
        return best;
    }

    /**
     * The first name from a list the player holds as a permission.
     *
     * <p>For families where the suffix is a name rather than a number — {@code ah.tax.vip},
     * {@code kits.starter}. Pass the candidates in priority order.</p>
     *
     * @param player     The player.
     * @param prefix     The permission prefix, dot included.
     * @param candidates The suffixes to try, best first.
     * @return The first suffix the player holds, or {@code null}.
     */
    public String firstHeld(Player player, String prefix, Collection<String> candidates) {
        if (player == null || candidates == null) return null;
        for (String candidate : candidates) {
            if (player.hasPermission(prefix + candidate)) return candidate;
        }
        return null;
    }

    /** Forgets the cached group of a player, after a rank change. */
    public void forget(java.util.UUID player) {
        groupCache.invalidate(player);
    }

    private String luckPermsGroup(Player player) {
        if (luckPermsApi == null) return null;
        try {
            Object users = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            Object user = users.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(users, player.getUniqueId());
            if (user == null) return null;
            return (String) user.getClass().getMethod("getPrimaryGroup").invoke(user);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String vaultGroup(Player player) {
        if (!vault) return null;
        try {
            Class<?> permissionClass = Class.forName("net.milkbowl.vault.permission.Permission");
            var registration = Bukkit.getServicesManager().getRegistration(permissionClass);
            if (registration == null) return null;
            Object permission = registration.getProvider();
            Object group = permission.getClass()
                    .getMethod("getPrimaryGroup", Player.class)
                    .invoke(permission, player);
            return group instanceof String name && !name.isBlank() ? name : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
