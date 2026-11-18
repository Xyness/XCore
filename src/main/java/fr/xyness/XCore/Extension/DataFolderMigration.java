package fr.xyness.XCore.Extension;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
 *   <li>no old folder, or an empty one: nothing to do. It is looked for ignoring case, as the addon
 *       loader matches names, the exact spelling first;</li>
 *   <li>the new folder exists and holds anything: both are left as they are, and the console names
 *       the two paths — merging two sets of settings is a decision only the administrator can make;</li>
 *   <li>the old folder is reached through a link ({@code plugins/XCore}, its {@code addons} folder or
 *       the folder itself is one), or holds links: it may be another server's as well. Its content is
 *       copied, and nothing is deleted from it;</li>
 *   <li>otherwise it is moved, by a rename when the file system allows it. When it does not (another
 *       volume, a file held open), the folder is copied beside its destination, every file is written
 *       through to the disk and compared with its original, the copy is renamed into place, and only
 *       then is the old folder deleted. What cannot be deleted is set aside under
 *       {@code <name>.migrated-<date>}, so no later start mistakes it for current files; when even
 *       that is refused, a note in it says so, and the next start sets it aside.</li>
 * </ul>
 *
 * <p>When the files cannot be brought over, nothing is deleted from the old folder, nothing is
 * written to the new one, and the result says so: the plugin then stays stopped rather than start
 * on its defaults (see {@link XPlugin#startFailed(String)}).</p>
 *
 * <p>Internal to XCore; {@link XPlugin} runs it before its {@code load()}.</p>
 */
public final class DataFolderMigration {

    /**
     * Written into what is left of an old folder that could be neither deleted nor set aside after
     * its files were copied: it says, to the administrator who opens it and to the next start, that
     * this is an older copy and not a second set of settings.
     */
    private static final String LEFTOVER_NOTE = "MOVED.txt";

    private DataFolderMigration() {}

    /** What became of the folder the addon used. */
    public enum Outcome {
        /** There was nothing to bring over: no old folder, or an empty one. */
        NOTHING,
        /** Its files are in the plugin's folder now. */
        MOVED,
        /** Both folders hold files: the plugin reads its own, and the old one was left as it was. */
        KEPT_BOTH,
        /**
         * Its files could not be brought over. Nothing was deleted from the old folder, nothing was
         * written to the new one, and the plugin must not start: it would run on default settings.
         */
        FAILED
    }

    /**
     * What a run did.
     *
     * @param outcome   What became of the old folder.
     * @param oldFolder That folder as an administrator would type it, or {@code null} when there was none.
     */
    public record Result(Outcome outcome, String oldFolder) {

        /** @return Whether the plugin must stay stopped. */
        public boolean failed() { return outcome == Outcome.FAILED; }
    }

    /**
     * Moves {@code plugins/XCore/addons/<name>/} to the plugin's data folder, when that is safe.
     *
     * @param name      The plugin name, which was the addon name.
     * @param newFolder The plugin's data folder, {@code plugins/<name>/}.
     * @param logger    Where to say what was done.
     * @return What happened; {@link Result#failed()} means the plugin must not start.
     */
    public static Result run(String name, File newFolder, Logger logger) {
        File pluginsDir = newFolder.getAbsoluteFile().getParentFile();
        if (pluginsDir == null) return new Result(Outcome.NOTHING, null);
        File addonsDir = new File(pluginsDir, "XCore" + File.separator + "addons");
        String newShown = displayPath(newFolder) + "/";

        File oldFolder;
        try {
            oldFolder = findOldFolder(addonsDir, name, logger);
        } catch (IOException e) {
            logger.sendError("Could not look for " + name + "'s files in " + displayPath(addonsDir) + "/ ("
                    + describe(e) + "). " + name + " does not start, rather than run on default settings while"
                    + " its own may be there. Nothing was written to " + newShown + ".");
            return new Result(Outcome.FAILED, displayPath(addonsDir) + "/" + name + "/");
        }
        if (oldFolder == null) return new Result(Outcome.NOTHING, null);

        String oldShown = displayPath(oldFolder) + "/";
        Path from = oldFolder.toPath().toAbsolutePath().normalize();
        Path to = newFolder.toPath().toAbsolutePath().normalize();
        // An empty plugins/<name>/ is removed to make way; put back if nothing takes its place.
        boolean removedEmpty = false;

        try {
            if (isEmptyDirectory(from)) return new Result(Outcome.NOTHING, oldShown);

            // The same folder under two names: plugins/<name> made a link to the old one, so that both
            // lead to the same files. Nothing to move, and nothing to warn about.
            if (Files.exists(to) && Files.isSameFile(from, to)) return new Result(Outcome.NOTHING, oldShown);

            // Through a link, or holding links: the folder may be another server's too — a network
            // sharing one plugins/XCore. Taking it away (a rename) or emptying it (a delete, which on
            // Windows goes through a junction into the folder it points to) would take that server's
            // files with it. Copied, then, and left as it is.
            boolean shared = reachedThroughLink(pluginsDir.toPath(), from) || holdsLinks(from);

            if (isLink(to)) {
                // A link the administrator set up on purpose, to keep the files elsewhere: it is
                // theirs, and replacing it with a folder would undo that.
                if (Files.isDirectory(to) && !isEmptyDirectory(to)) {
                    keptBoth(name, oldShown, newShown, shared, logger);
                    return new Result(Outcome.KEPT_BOTH, oldShown);
                }
                logger.sendError(newShown + " is a link, and " + oldShown + " holds " + name + "'s files. Nothing was"
                        + " moved, and " + name + " does not start rather than run on default settings in the link's"
                        + " target: stop the server, move the files into that target, and start it again.");
                return new Result(Outcome.FAILED, oldShown);
            }
            if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(newShown + " is a file, not a folder");
                }
                if (!isEmptyDirectory(to)) {
                    if (!shared && Files.isRegularFile(from.resolve(LEFTOVER_NOTE), LinkOption.NOFOLLOW_LINKS)) {
                        return clearLeftover(name, from, oldShown, newShown, logger);
                    }
                    keptBoth(name, oldShown, newShown, shared, logger);
                    return new Result(Outcome.KEPT_BOTH, oldShown);
                }
                // Empty: nothing in it to lose, and it would stand in the way of the rename.
                if (!delete(to)) throw new IOException(newShown + " is empty but could not be removed to make way");
                removedEmpty = true;
            }

            Path staging = pluginsDir.toPath().resolve("." + name + ".migrating");
            if (shared) {
                copyVerifyAndSwap(from, to, staging, true);
                logger.sendInfo("Copied " + oldShown + " to " + newShown + ", every file checked: " + name
                        + " is a plugin since XCore 2.0, and its settings and data come with it.");
                logger.sendInfo(oldShown + " is reached through a link, or holds links: another server may be"
                        + " using it, so nothing was deleted from it. " + name + " reads " + newShown
                        + " from now on; delete the old folder yourself once nothing else does.");
                return new Result(Outcome.MOVED, oldShown);
            }

            String why;
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                logger.sendInfo("Moved " + oldShown + " to " + newShown + ": " + name
                        + " is a plugin since XCore 2.0, and its settings and data come with it.");
                return new Result(Outcome.MOVED, oldShown);
            } catch (AtomicMoveNotSupportedException e) {
                why = "the two folders are not on the same file system";
            } catch (IOException e) {
                why = describe(e);
            }

            logger.sendInfo("Could not move " + oldShown + " in one step (" + why + "): copying it instead.");
            copyVerifyAndSwap(from, to, staging, false);
            logger.sendInfo("Copied " + oldShown + " to " + newShown + ", every file checked: " + name
                    + " is a plugin since XCore 2.0, and its settings and data come with it.");

            List<Path> left = deleteTree(from);
            if (!left.isEmpty()) {
                // Left under its own name, it would be taken for current files at the next start, and
                // the console would ask which of the two folders is the real one. It is the old copy.
                Path aside = setAside(from);
                if (aside != null) {
                    logger.sendWarning(left.size() + " file(s) of " + oldShown + " could not be deleted (held open"
                            + " by another program?). What is left of it is now " + displayPath(aside.toFile())
                            + "/, an older copy of what " + newShown + " holds: delete it once the server is stopped.");
                } else {
                    // Marked instead, so the next start knows it for what it is and tries again.
                    noteLeftover(from, name, newShown);
                    logger.sendWarning(left.size() + " file(s) of " + oldShown + " could not be deleted or set aside"
                            + " (held open by another program?). " + name + " uses " + newShown + ", which holds the"
                            + " current files: delete " + oldShown + " once the server is stopped, and never copy it"
                            + " back — it is the older copy.");
                }
            }
            return new Result(Outcome.MOVED, oldShown);
        } catch (IOException | RuntimeException e) {
            // Whatever went wrong, the old folder is still whole: the copy is verified before
            // anything is removed from it, and the new folder only appears once the copy is complete.
            if (removedEmpty && !Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(to);
                } catch (IOException | RuntimeException ignored) {
                    // It was empty: nothing of it is lost.
                }
            }
            logger.sendError("Could not bring " + oldShown + " over to " + newShown + ": " + describe(e)
                    + ". Nothing was deleted from it and nothing was written to " + newShown + ": " + name
                    + " does not start, rather than run on default settings. Stop the server, move the content of "
                    + oldShown + " into " + newShown + " by hand, and start it again.");
            return new Result(Outcome.FAILED, oldShown);
        }
    }

    /**
     * Finds the folder an addon of this name used: in {@code plugins/XCore/addons/}, its name
     * compared ignoring case, the way the addon loader compares names.
     *
     * @param pluginsDir The server's {@code plugins/} folder.
     * @param name       The addon name.
     * @return The folder, the exact spelling first; {@code null} when there is none.
     */
    public static File oldFolder(File pluginsDir, String name) {
        try {
            return findOldFolder(new File(pluginsDir, "XCore" + File.separator + "addons"), name, null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The folder an addon of this name wrote to, compared ignoring case: an {@code addon.yml} that
     * spelt its name {@code xbans} was refused as XBans, and kept its files in {@code addons/xbans/}.
     * The exact spelling wins, then the first that holds anything.
     *
     * @param logger Where to warn when several match, {@code null} to stay quiet.
     */
    private static File findOldFolder(File addonsDir, String name, Logger logger) throws IOException {
        if (!addonsDir.isDirectory()) return null;
        File[] entries = addonsDir.listFiles(File::isDirectory);
        if (entries == null) throw new IOException("the folder cannot be listed");

        List<File> matches = new ArrayList<>();
        for (File entry : entries) {
            if (entry.getName().equalsIgnoreCase(name)) matches.add(entry);
        }
        if (matches.isEmpty()) return null;
        matches.sort(Comparator.comparing((File folder) -> !folder.getName().equals(name)).thenComparing(File::getName));

        File chosen = matches.get(0);
        for (File match : matches) {
            if (!isEmptyDirectory(match.toPath())) {
                chosen = match;
                break;
            }
        }
        if (matches.size() > 1 && logger != null) {
            List<String> shown = new ArrayList<>();
            for (File match : matches) shown.add(displayPath(match) + "/");
            logger.sendWarning(matches.size() + " folders of " + displayPath(addonsDir) + "/ carry the name " + name
                    + " in some spelling: " + String.join(", ", shown) + ". Only " + displayPath(chosen)
                    + "/ is looked at; the others were left as they are.");
        }
        return chosen;
    }

    /**
     * Marks what is left of a copied folder as the older copy.
     *
     * <p>Whatever kept it from being deleted — a file held open, typically — is usually gone at the
     * next start, which then sets it aside (see {@link #clearLeftover}) instead of finding two
     * folders full and asking which one is the real one.</p>
     */
    private static void noteLeftover(Path folder, String name, String newShown) {
        try {
            Files.writeString(folder.resolve(LEFTOVER_NOTE), name + " moved to " + newShown + " on " + LocalDate.now()
                    + ", every file copied and checked.\nWhat is left here is an older copy that could not be deleted"
                    + " then. Delete this folder once the server is stopped; never copy it back over " + newShown + ".\n");
        } catch (IOException | RuntimeException e) {
            // The warning in the console says the same.
        }
    }

    /**
     * Another try at setting aside what an earlier move left behind, marked by {@link #noteLeftover}.
     *
     * <p>Renamed, never deleted: the note is only a reason to believe the files are the old copy.
     * And only when nothing in the folder changed after the note was written — a 1.x addon run
     * there in between would have written to it, and that folder is then a second set of files the
     * administrator has to look at, like any other.</p>
     */
    private static Result clearLeftover(String name, Path from, String oldShown, String newShown, Logger logger) {
        boolean changed;
        try {
            changed = changedSince(from, Files.getLastModifiedTime(from.resolve(LEFTOVER_NOTE)).toMillis());
        } catch (IOException | RuntimeException e) {
            changed = true;
        }
        if (changed) {
            keptBoth(name, oldShown, newShown, false, logger);
            return new Result(Outcome.KEPT_BOTH, oldShown);
        }
        Path aside = setAside(from);
        if (aside != null) {
            logger.sendInfo("What was left of " + name + "'s move to " + newShown + " is now "
                    + displayPath(aside.toFile()) + "/: an older copy, to delete once the server is stopped.");
            return new Result(Outcome.NOTHING, oldShown);
        }
        logger.sendWarning(oldShown + " is what is left of " + name + "'s move to " + newShown + ", and still cannot"
                + " be set aside: delete it once the server is stopped. " + newShown + " holds the current files.");
        return new Result(Outcome.KEPT_BOTH, oldShown);
    }

    /** Whether a file in the folder, other than the note, was written after the given time. */
    private static boolean changedSince(Path folder, long millis) throws IOException {
        boolean[] changed = {false};
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals(LEFTOVER_NOTE) && file.getParent().equals(folder)) {
                    return FileVisitResult.CONTINUE;
                }
                // A second of slack: some file systems keep coarse times.
                if (attrs.lastModifiedTime().toMillis() > millis + 1000L) {
                    changed[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return changed[0];
    }

    /** Says that both folders hold files, and what to do about it without losing either. */
    private static void keptBoth(String name, String oldShown, String newShown, boolean shared, Logger logger) {
        if (shared) {
            // The usual state after a copy from a shared folder: nothing to decide, only to know.
            logger.sendInfo(oldShown + " still holds files; " + name + " reads " + newShown + " only. The old folder"
                    + " is reached through a link, or holds links, and is left to you: delete it once nothing uses it.");
            return;
        }
        logger.sendWarning("Both " + oldShown + " and " + newShown + " hold files. " + name
                + " reads " + newShown + " only; " + oldShown + " was left untouched.");
        logger.sendWarning("If " + oldShown + " holds the settings and data you use, stop the server, rename " + newShown
                + " to keep it aside, put " + oldShown + " in its place, and start it again. Never copy one folder"
                + " into the other: the files of the older one would replace the newer ones.");
    }

    /**
     * Copies {@code from} into a staging folder, writes every file through to the disk, checks every
     * file byte for byte, then renames the staging folder to {@code to}.
     *
     * <p>Staged rather than written straight to {@code to}: a server killed half-way through the
     * copy would otherwise leave a partly filled {@code plugins/<name>/}, which the next start
     * would take for the real one. A staging folder left behind is simply started over.</p>
     *
     * @param followLinks Whether to copy what links point to, for a folder that holds links: what the
     *                    addon read through them is what the plugin must find.
     */
    private static void copyVerifyAndSwap(Path from, Path to, Path staging, boolean followLinks) throws IOException {
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            // Only ever a copy of the old folder, which is still intact.
            List<Path> left = deleteTree(staging);
            if (!left.isEmpty()) throw new IOException("an earlier copy in " + staging + " could not be cleared");
        }

        Set<FileVisitOption> options = followLinks
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS) : EnumSet.noneOf(FileVisitOption.class);
        try {
            Files.walkFileTree(from, options, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(staging.resolve(from.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    // A link that leads nowhere, a device or a pipe: nothing a plugin reads its settings from.
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    Path copy = staging.resolve(from.relativize(file).toString());
                    Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
                    forceToDisk(copy);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    // A loop of links among them: copying it would never end.
                    throw exc;
                }
            });

            Files.walkFileTree(from, options, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    Path copy = staging.resolve(from.relativize(file).toString());
                    if (!Files.isRegularFile(copy, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException(from.relativize(file) + " is missing from the copy");
                    }
                    if (Files.mismatch(file, copy) != -1L) {
                        throw new IOException(from.relativize(file) + " differs in the copy");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    throw exc;
                }
            });

            try {
                Files.move(staging, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Same parent folder, so this is not expected; a plain rename is still a rename.
                Files.move(staging, to);
            }
        } catch (IOException | RuntimeException e) {
            // Only the copy is thrown away; the folder it was made from has not been touched.
            deleteTree(staging);
            throw e;
        }
    }

    /**
     * Writes a copied file through to the disk itself. The originals are deleted once the copy is
     * checked, and a check that reads the copy back from the system's cache proves nothing about a
     * power cut that comes before the cache reaches the disk.
     */
    private static void forceToDisk(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
            return;
        } catch (AccessDeniedException readOnly) {
            // The copy kept its original's read-only flag or mode.
        }
        DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
        if (dos != null && isReadOnly(dos)) {
            // Windows flushes only through a handle open for writing — through any other it reports
            // success and flushes nothing — and the flag withholds one: lifted for the flush, put back.
            dos.setReadOnly(false);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            } finally {
                dos.setReadOnly(true);
            }
            return;
        }
        // A read-only mode on Linux, which flushes through a descriptor opened for reading.
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static boolean isReadOnly(DosFileAttributeView dos) {
        try {
            return dos.readAttributes().isReadOnly();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Deletes a folder and everything in it, going on past what cannot be deleted.
     *
     * <p>Never through a link: the walk does not follow them, and the folders it is used on were
     * checked to hold none (or are its own staging copies).</p>
     *
     * @return What is left.
     */
    private static List<Path> deleteTree(Path root) {
        List<Path> left = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!delete(file)) left.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    left.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    // Not empty because something in it stayed: already counted.
                    if (!delete(dir) && left.isEmpty()) left.add(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            left.add(root);
        }
        return left;
    }

    /**
     * Deletes one file or empty folder.
     *
     * <p>Windows refuses to delete anything marked read-only — a flag that files copied from a
     * download, an archive or another disk often carry — and the old folder then stayed, half
     * emptied. The flag is lifted and the delete tried once more.</p>
     *
     * @return {@code true} when it is gone.
     */
    private static boolean delete(Path path) {
        try {
            Files.delete(path);
            return true;
        } catch (AccessDeniedException denied) {
            try {
                DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (dos == null || !isReadOnly(dos)) return false;
                dos.setReadOnly(false);
                Files.delete(path);
                return true;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Renames what is left of a folder to {@code <name>.migrated-<yyyyMMdd>} beside it.
     *
     * @return Where it went, or {@code null} when it could not be renamed either.
     */
    private static Path setAside(Path folder) {
        String base = folder.getFileName() + ".migrated-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int i = 1; i <= 20; i++) {
            Path aside = folder.resolveSibling(i == 1 ? base : base + "-" + i);
            if (Files.exists(aside, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.move(folder, aside);
                return aside;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Whether {@code plugins/XCore}, its {@code addons} folder or the folder itself is a link or a
     * junction: the real path then differs from the one the folder is named by.
     *
     * <p>Compared below the real {@code plugins/} folder, so a link further up — the whole server
     * folder elsewhere, a short Windows name — does not count: everything under it moves together.</p>
     */
    private static boolean reachedThroughLink(Path pluginsDir, Path from) throws IOException {
        Path expected = pluginsDir.toRealPath().resolve("XCore").resolve("addons")
                .resolve(from.getFileName().toString());
        return !from.toRealPath().equals(expected);
    }

    /**
     * Whether anything in the folder is a link, a junction or some other special entry.
     *
     * <p>A junction reads as a folder and as "other" at once, and a walk goes into it as into a
     * folder: deleting through it empties the folder it points to.</p>
     */
    private static boolean holdsLinks(Path root) throws IOException {
        boolean[] found = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (attrs.isOther()) {
                    found[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    found[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }
        });
        return found[0];
    }

    /** Whether a path is a link or a junction rather than a plain folder or file. */
    private static boolean isLink(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isSymbolicLink() || attrs.isOther();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isEmptyDirectory(Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            return !entries.iterator().hasNext();
        }
    }

    /** An exception as a reader can act on it: a file system error alone is just a path. */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
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
