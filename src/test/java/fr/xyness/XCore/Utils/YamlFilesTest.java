package fr.xyness.XCore.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlFilesTest {

    @TempDir
    Path folder;

    private File file(String text) throws Exception {
        Path path = folder.resolve("config.yml");
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path.toFile();
    }

    @Test
    void aValidFileHasNoProblem() throws Exception {
        assertNull(YamlFiles.problem(file("stacker:\n  excluded:\n    - VILLAGER\n    - HAPPY_GHAST\n")));
    }

    @Test
    void aMissingFileHasNoProblem() {
        assertNull(YamlFiles.problem(folder.resolve("absent.yml").toFile()));
        assertNull(YamlFiles.problem(null));
    }

    @Test
    void aTabIsReportedWithItsLine() throws Exception {
        String problem = YamlFiles.problem(file("stacker:\n  excluded:\n    - VILLAGER\n\t- HAPPY_GHAST\n"));
        assertNotNull(problem);
        assertTrue(problem.startsWith("line 4, column 1"), problem);
        assertTrue(problem.contains("TAB"), problem);
        assertFalse(problem.contains("\n"), "one line");
    }

    @Test
    void anEntryOutOfLineIsReportedWithItsLine() throws Exception {
        String problem = YamlFiles.problem(file("stacker:\n  excluded:\n    - VILLAGER\n   - HAPPY_GHAST\n"));
        assertNotNull(problem);
        assertTrue(problem.startsWith("line 4, column 4"), problem);
    }

    @Test
    void aComplaintReadsTheSameOnceRenderedByTheConsole() {
        String complaint = "line 4, column 4: expected <block end>, but found <red>";
        String rendered = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(YamlFiles.forConsole(complaint)));
        assertEquals(complaint, rendered);
    }

    @Test
    void aMistakeFoundAfterParsingIsAProblemToo() throws Exception {
        // An unknown tag parses, then fails while the values are built: outside the exception
        // the loader declares. It used to escape as a stack trace.
        String problem = YamlFiles.problem(file("prefix: !Attention wrong\nother: 1\n"));
        assertNotNull(problem);
        assertFalse(problem.contains("\n"), "one line");
    }

    @Test
    void anEmptyFileIsBlankAndAFileWithAKeyIsNot() throws Exception {
        assertTrue(YamlFiles.isBlank(file("")));
        assertTrue(YamlFiles.isBlank(file("\n  \n\r\n")));
        assertFalse(YamlFiles.isBlank(file("debug: false\n")));
        assertFalse(YamlFiles.isBlank(file("# only a comment\n")));
        assertFalse(YamlFiles.isBlank(folder.resolve("absent.yml").toFile()));
        assertNull(YamlFiles.problem(file("")), "an empty file parses");
    }
}
