package fr.xyness.XCore.Integrations;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * Helper for sending Floodgate/Cumulus forms to Bedrock players.
 * <p>
 * All Floodgate API calls are done via reflection to avoid a hard dependency.
 * If Floodgate is not installed, all methods silently do nothing.
 * </p>
 */
public class FloodgateFormHelper {

    /**
     * Returns whether the player is a Bedrock player AND Floodgate is available
     * (meaning we can send forms).
     */
    public static boolean canSendForm(Player player) {
        return FloodgateHook.isAvailable() && FloodgateHook.isBedrockPlayer(player);
    }

    /**
     * Sends a SimpleForm (button-based menu) to a Bedrock player.
     *
     * @param player         The Bedrock player.
     * @param title          The form title.
     * @param content        The form description/content text.
     * @param buttons        List of button labels.
     * @param onResult       Callback with the clicked button index (0-based), or -1 if closed.
     */
    public static void sendSimpleForm(Player player, String title, String content,
                                       List<String> buttons, Consumer<Integer> onResult) {
        try {
            // Get FloodgateApi instance
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object floodgatePlayer = apiClass.getMethod("getPlayer", UUID.class)
                .invoke(api, player.getUniqueId());
            if (floodgatePlayer == null) return;

            // Build SimpleForm
            Class<?> simpleFormClass = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Object builder = simpleFormClass.getMethod("builder").invoke(null);
            Class<?> builderClass = builder.getClass();

            builderClass.getMethod("title", String.class).invoke(builder, title);
            if (content != null && !content.isEmpty()) {
                builderClass.getMethod("content", String.class).invoke(builder, content);
            }

            for (String btn : buttons) {
                builderClass.getMethod("button", String.class).invoke(builder, btn);
            }

            // Set valid result handler
            Class<?> resultHandlerClass = Class.forName("org.geysermc.cumulus.response.SimpleFormResponse");
            builderClass.getMethod("validResultHandler", Consumer.class).invoke(builder, (Consumer<Object>) response -> {
                try {
                    int clickedId = (int) resultHandlerClass.getMethod("clickedButtonId").invoke(response);
                    onResult.accept(clickedId);
                } catch (Throwable e) {
                    onResult.accept(-1);
                }
            });

            builderClass.getMethod("invalidResultHandler", Runnable.class).invoke(builder, (Runnable) () -> onResult.accept(-1));

            // Build and send
            Object form = builderClass.getMethod("build").invoke(builder);
            floodgatePlayer.getClass().getMethod("sendForm", Class.forName("org.geysermc.cumulus.form.Form"))
                .invoke(floodgatePlayer, form);

        } catch (Throwable e) {
            // Floodgate not available or API changed, fall back silently
        }
    }

    /**
     * Sends a ModalForm (2-button confirmation) to a Bedrock player.
     *
     * @param player    The Bedrock player.
     * @param title     The form title.
     * @param content   The description text.
     * @param button1   The confirm button text.
     * @param button2   The cancel button text.
     * @param onResult  Callback: true = button1 clicked, false = button2 or closed.
     */
    public static void sendModalForm(Player player, String title, String content,
                                      String button1, String button2, Consumer<Boolean> onResult) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object floodgatePlayer = apiClass.getMethod("getPlayer", UUID.class)
                .invoke(api, player.getUniqueId());
            if (floodgatePlayer == null) return;

            Class<?> modalFormClass = Class.forName("org.geysermc.cumulus.form.ModalForm");
            Object builder = modalFormClass.getMethod("builder").invoke(null);
            Class<?> builderClass = builder.getClass();

            builderClass.getMethod("title", String.class).invoke(builder, title);
            builderClass.getMethod("content", String.class).invoke(builder, content);
            builderClass.getMethod("button1", String.class).invoke(builder, button1);
            builderClass.getMethod("button2", String.class).invoke(builder, button2);

            Class<?> resultClass = Class.forName("org.geysermc.cumulus.response.ModalFormResponse");
            builderClass.getMethod("validResultHandler", Consumer.class).invoke(builder, (Consumer<Object>) response -> {
                try {
                    int clickedId = (int) resultClass.getMethod("clickedButtonId").invoke(response);
                    onResult.accept(clickedId == 0);
                } catch (Throwable e) {
                    onResult.accept(false);
                }
            });

            builderClass.getMethod("invalidResultHandler", Runnable.class).invoke(builder, (Runnable) () -> onResult.accept(false));

            Object form = builderClass.getMethod("build").invoke(builder);
            floodgatePlayer.getClass().getMethod("sendForm", Class.forName("org.geysermc.cumulus.form.Form"))
                .invoke(floodgatePlayer, form);

        } catch (Throwable e) {
            // Fall back silently
        }
    }

    /**
     * Sends a CustomForm (with inputs, toggles, dropdowns) to a Bedrock player.
     *
     * @param player    The Bedrock player.
     * @param title     The form title.
     * @param builder   A consumer that receives the form builder to add fields.
     * @param onResult  Callback with the form response object, or null if closed.
     */
    public static void sendCustomForm(Player player, String title,
                                       Consumer<Object> builder, Consumer<Object> onResult) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object floodgatePlayer = apiClass.getMethod("getPlayer", UUID.class)
                .invoke(api, player.getUniqueId());
            if (floodgatePlayer == null) return;

            Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
            Object formBuilder = customFormClass.getMethod("builder").invoke(null);
            Class<?> builderClass = formBuilder.getClass();

            builderClass.getMethod("title", String.class).invoke(formBuilder, title);
            builder.accept(formBuilder);

            builderClass.getMethod("validResultHandler", Consumer.class).invoke(formBuilder, (Consumer<Object>) response -> {
                onResult.accept(response);
            });

            builderClass.getMethod("invalidResultHandler", Runnable.class).invoke(formBuilder, (Runnable) () -> onResult.accept(null));

            Object form = builderClass.getMethod("build").invoke(formBuilder);
            floodgatePlayer.getClass().getMethod("sendForm", Class.forName("org.geysermc.cumulus.form.Form"))
                .invoke(floodgatePlayer, form);

        } catch (Throwable e) {
            // Fall back silently
        }
    }

    /**
     * Strips MiniMessage tags and special Unicode characters for Bedrock display.
     *
     * @param text The MiniMessage formatted text.
     * @return Plain text safe for Bedrock forms.
     */
    public static String stripForBedrock(String text) {
        if (text == null) return "";
        // Remove MiniMessage tags
        String stripped = text.replaceAll("<[^>]+>", "");
        // Replace common Unicode chars
        stripped = stripped.replace("★", "*").replace("▸", ">").replace("➲", ">");
        stripped = stripped.replace("▪", "-").replace("■", "#").replace("▶", ">");
        stripped = stripped.replace("✔", "[v]").replace("✗", "[x]").replace("⚠", "[!]");
        return stripped.trim();
    }
}
