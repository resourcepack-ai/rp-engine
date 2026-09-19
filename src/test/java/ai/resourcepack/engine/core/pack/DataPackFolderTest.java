package ai.resourcepack.engine.core.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one thing that matters here: a generated datapack lands in the LEVEL
 * directory, whatever a world folder happens to be on the running server.
 */
class DataPackFolderTest {

    /**
     * The layout that broke this: a world folder is a dimension's own data,
     * kept three levels below the level directory the server reads datapacks
     * from.
     */
    @Test
    void findsTheLevelAboveADimensionFolder(@TempDir Path tmp) throws IOException {
        Path level = tmp.resolve("world");
        Path dimension = level.resolve("dimensions/minecraft/overworld");
        Files.createDirectories(dimension);
        Files.createDirectories(dimension.resolve("region"));
        Files.write(level.resolve("level.dat"), new byte[0]);

        assertEquals(level.resolve("datapacks/rpengine_dialogs"),
                DataPackFolder.of(dimension, "rpengine_dialogs"));
    }

    /** Every layout before it: the world folder IS the level directory. */
    @Test
    void writesBesideAWorldFolderThatIsItselfTheLevel(@TempDir Path tmp) throws IOException {
        Path level = tmp.resolve("world");
        Files.createDirectories(level);
        Files.write(level.resolve("level.dat"), new byte[0]);

        assertEquals(level.resolve("datapacks/rpengine_liquids"),
                DataPackFolder.of(level, "rpengine_liquids"));
    }

    /**
     * A world folder with no level above it stays where it is. Walking on
     * would put a datapack in whatever directory the server was started from,
     * which is worse than the folder nobody reads.
     */
    @Test
    void staysPutWhenThereIsNoLevelToFind(@TempDir Path tmp) throws IOException {
        Path odd = tmp.resolve("somewhere/else");
        Files.createDirectories(odd);

        assertEquals(odd.resolve("datapacks/rpengine_dialogs"),
                DataPackFolder.of(odd, "rpengine_dialogs"));
    }
}
