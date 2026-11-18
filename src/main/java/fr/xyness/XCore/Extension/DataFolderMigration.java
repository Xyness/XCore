package fr.xyness.XCore.Extension;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import fr.xyness.XCore.Utils.Logger;

/**
 * Brings the files of an addon that became a plugin to the plugin's own folder.
 *
 * <p>An addon kept its settings and data in {@code plugins/XCore/addons/<name>/}; the plugin it
 * became reads {@code plugins/<name>/}, the folder the server gives every plugin. Without this, the
 * first start of the 2.0 plugin found an empty folder, wrote its defaults there, and the server ran
 * on factory settings while the administrator's own sat one folder away, unread.</p>
 *
 * <p>The rules, in order:</p>
 * <ul>
 *   <li>no old folder, or an empty one: nothing to do;</li>
 *   <li>the new folder exists and holds anything: both are left as they are, and the console names
 *       the two paths — merging two sets of settings is a decision only the administrator can make;</li>
 *   <li>otherwise the old folder is moved, by a rename when the file system allows it. When it does
 *       not (another volume, a file held open), the folder is copied beside its destination, every
 *       file is compared with its original, the copy is renamed into place, and only then is the old
 *       folder deleted. Nothing is deleted before the copy has been verified.</li>
 * </ul>
 *
 * <p>Internal to XCore; {@link XPlugin} runs it before its {@code load()}.</p>
 */
public final class DataFolderMigration {

    private DataFolderMigration() {}

    /**
     * Moves {@code plugins/XCore/addons/<name>/} to the plugin's data folder, when that is safe.
     *
     * @param name      The plugin name, which was the addon name.
     * @param newFolder The plugin's data folder, {@code plugins/<name>/}.
     * @param logger    Where to say what was done.
     */
    public static void run(String name, File newFolder, Logger logger) {
        File pluginsDir = newFolder.getAbsoluteFile().getParentFile();
        if (pluginsDir == null) return;
        File oldFolder = new File(pluginsDir, "XCore" + File.separator + "addons" + File.separator + name);

        try {
            if (!oldFolder.isDirectory() || isEmptyDirectory(oldFolder.toPath())) return;

            Path from = oldFolder.toPath();
            Path to = newFolder.toPath();
            String oldShown = displayPath(oldFolder) + "/";
            String newShown = displayPath(newFolder) + "/";

            if (Files.isSymbolicLink(to)) {
                // A link the administrator set up on purpose, to keep the files elsewhere: it is
                // theirs, and replacing it with a folder would undo that.
                logger.sendWarning(newShown + " is a link, and " + oldShown + " still holds " + name
                        + "'s files. Nothing was moved: stop the server and move them into the link's target.");
                return;
            }
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(to) || !isEmptyDirectory(to)) {
                    logger.sendWarning("Both " + oldShown + " and " + newShown + " hold files. " + name
                            + " now reads " + newShown + " only; " + oldShown + " was left untouched.");
                    logger.sendWarning("If " + oldShown + " holds the settings and data you use, stop the server,"
                            + " move its content into " + newShown + ", then delete " + oldShown + ".");
                    return;
                }
                // Empty: nothing in it to lose, and it would stand in the way of the rename.
                Files.delete(to);
            }

            String why;
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                logger.sendInfo("Moved " + oldShown + " to " + newShown + ": " + name
                        + " is a plugin since XCore 2.0, and its settings and data come with it.");
                return;
            } catch (AtomicMoveNotSupportedException e) {
                why = "the two folders are not on the same file system";
            } catch (IOException e) {
                why = String.valueOf(e.getMessage());
            }

            logger.sendInfo("Could not move " + oldShown + " in one step (" + why + "): copying it instead.");
            Path staging = pluginsDir.toPath().resolve("." + name + ".migrating");
            try {
                copyVerifyAndSwap(from, to, staging);
            } catch (IOException | RuntimeException e) {
                // Only the copy is thrown away; the folder it was made from has not been touched.
                deleteTree(staging);
                throw e;
            }
            logger.sendInfo("Copied " + oldShown + " to " + newShown + ", every file checked: " + name
                    + " is a plugin since XCore 2.0, and its settings and data come with it.");

            List<Path> left = deleteTree(from);
            if (!left.isEmpty()) {
                logger.sendWarning(left.size() + " file(s) of " + oldShown + " could not be deleted"
                        + " (held open by another program?). " + name + " uses " + newShown
                        + "; delete " + oldShown + " once the server is stopped.");
            }
        } catch (IOException | RuntimeException e) {
            // Whatever went wrong, the old folder is still whole: the copy is verified before
            // anything is removed from it.
            logger.sendError("Could not bring " + displayPath(oldFolder) + "/ over to " + displayPath(newFolder)
                    + "/: " + e.getMessage() + ". Nothing was deleted, and " + name + " starts on default"
                    + " settings this time. Stop the server, move the content of the old folder into the new"
                    + " one by hand, and start it again.");
        }
    }

    /**
     * Copies {@code from} into a staging folder, checks every file byte for byte, then renames the
     * staging folder to {@code to}.
     *
     * <p>Staged rather than written straight to {@code to}: a server killed half-way through the
     * copy would otherwise leave a partly filled {@code plugins/<name>/}, which the next start
     * would take for the real one. A staging folder left behind is simply started over.</p>
     */
    private static void copyVerifyAndSwap(Path from, Path to, Path staging) throws IOException {
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            // Only ever a copy of the old folder, which is still intact.
            List<Path> left = deleteTree(staging);
            if (!left.isEmpty()) throw new IOException("an earlier copy in " + staging + " could not be cleared");
        }

        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(staging.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, staging.resolve(from.relativize(file).toString()),
                        StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });

        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path copy = staging.resolve(from.relativize(file).toString());
                if (!Files.exists(copy, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(from.relativize(file) + " is missing from the copy");
                }
                if (attrs.isRegularFile() && Files.mismatch(file, copy) != -1L) {
                    throw new IOException(from.relativize(file) + " differs in the copy");
                }
                return FileVisitResult.CONTINUE;
            }
        });

        try {
            Files.move(staging, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Same parent folder, so this is not expected; a plain rename is still a rename.
            Files.move(staging, to);
        }
    }

    /**
     * Deletes a folder and everything in it, going on past what cannot be deleted.
     *
     * @return What is left.
     */
    private static List<Path> deleteTree(Path root) {
        List<Path> left = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        left.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    left.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.delete(dir);
                    } catch (IOException e) {
                        // Not empty because something in it stayed: already counted.
                        if (left.isEmpty()) left.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            left.add(root);
        }
        return left;
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        }
    }

    /**
     * A path as an administrator would type it: relative to the server folder, forward slashes.
     *
     * @param file The file.
     * @return Its display form.
     */
    public static String displayPath(File file) {
        try {
            Path root = Path.of("").toAbsolutePath();
            Path path = file.toPath().toAbsolutePath().normalize();
            if (path.startsWith(root)) path = root.relativize(path);
            return path.toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.getPath().replace('\\', '/');
        }
    }
}
