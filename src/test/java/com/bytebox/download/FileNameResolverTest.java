package com.bytebox.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNameResolverTest {
    @TempDir
    Path directory;

    @Test
    void sanitizesTraversalAndWindowsReservedNames() {
        String traversal = FileNameResolver.fileNameFrom(URI.create("https://example.test/%2e%2e%2fCON.txt"));

        assertFalse(traversal.contains("/"));
        assertFalse(traversal.contains("\\"));
        assertNotEquals("CON.txt", traversal);
    }

    @Test
    void createsDistinctNamesWhenFileAlreadyExistsOrIsReserved() throws Exception {
        Path first = directory.resolve("report.txt");
        java.nio.file.Files.createFile(first);

        Path second = FileNameResolver.allocate(URI.create("https://example.test/report.txt"), directory, Set.of());
        Path third = FileNameResolver.allocate(URI.create("https://example.test/report.txt"), directory, Set.of(first, second));

        assertEquals("report (1).txt", second.getFileName().toString());
        assertEquals("report (2).txt", third.getFileName().toString());
    }

    @Test
    void suppliesAUsefulNameWhenUrlHasNoFileName() {
        assertTrue(FileNameResolver.fileNameFrom(URI.create("https://example.test/?id=4")).startsWith("download-"));
    }
}
