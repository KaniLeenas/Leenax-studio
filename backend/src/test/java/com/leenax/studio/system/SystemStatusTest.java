package com.leenax.studio.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemStatusTest {

    private final SystemStatusService service = new SystemStatusService(null, null, null, new ObjectMapper(), "", "", "/nowhere");

    @Test
    void readsTheBackupServicesStatusFile() throws Exception {
        Path dir = Files.createTempDirectory("bk");
        assertNull(service.readBackup(dir));
        // exactly what deploy/backup-loop.sh writes
        Files.writeString(dir.resolve("latest.json"), """
                {"ok": true, "finishedAt": "2026-11-19T15:27:11Z", "folder": "20261119-152711", "databaseBytes": 70937, "mediaBytes": 164, "error": ""}
                """);
        SystemStatusService.Backup b = service.readBackup(dir);
        assertTrue(b.ok());
        assertEquals(Instant.parse("2026-11-19T15:27:11Z"), b.finishedAt());
        assertEquals(70937L, b.databaseBytes());
        Files.writeString(dir.resolve("latest.json"), """
                {"ok": false, "finishedAt": "2026-11-19T15:27:11Z", "folder": "x", "databaseBytes": null, "mediaBytes": null, "error": "pg_dump failed"}
                """);
        assertEquals("pg_dump failed", service.readBackup(dir).error());
        Files.writeString(dir.resolve("latest.json"), "not json");
        assertTrue(service.readBackup(dir).error().contains("could not be read"));
    }

    @Test
    void warnsAboutMissingFailedOldBackupsAndAFullDisk() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        assertTrue(SystemStatusService.backupWarnings(null, null, now).get(0).startsWith("No automatic backup"));
        var fresh = new SystemStatusService.Backup(now.minus(Duration.ofHours(5)), true, 1L, 1L, "f", null);
        assertEquals(List.of(), SystemStatusService.backupWarnings(fresh, new SystemStatusService.Disk("/b", 100, 50), now));
        var old = new SystemStatusService.Backup(now.minus(Duration.ofHours(40)), true, 1L, 1L, "f", null);
        assertEquals(1, SystemStatusService.backupWarnings(old, null, now).size());
        var failed = new SystemStatusService.Backup(now, false, null, null, "f", "pg_dump failed");
        assertTrue(SystemStatusService.backupWarnings(failed, null, now).get(0).contains("pg_dump failed"));
        assertEquals(1, SystemStatusService.backupWarnings(fresh, new SystemStatusService.Disk("/b", 100, 5), now).size());
    }
}
