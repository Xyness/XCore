package fr.xyness.XCore.Web.Icons;

import java.io.IOException;

/**
 * Where the game's own files are read: models, item definitions, textures.
 *
 * <p>The renderer asks for paths under {@code assets/minecraft/} — {@code items/oak_stairs.json},
 * {@code models/block/stairs.json}, {@code textures/block/oak_planks.png} — and does not care
 * whether they come from a mirror, a resource pack or a folder on disk.</p>
 *
 * @since 2.1
 */
@FunctionalInterface
public interface AssetSource {

    /**
     * Reads one file.
     *
     * @param path The path under {@code assets/minecraft/}, with forward slashes and no leading one.
     * @return The file's bytes, or {@code null} when the source has no such file.
     * @throws IOException When the source could not be reached or asked to slow down: the file may
     *                     well exist, and the caller should try again later rather than conclude
     *                     that it does not.
     */
    byte[] read(String path) throws IOException;
}
