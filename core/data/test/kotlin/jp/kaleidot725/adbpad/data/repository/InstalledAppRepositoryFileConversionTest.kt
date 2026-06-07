package jp.kaleidot725.adbpad.data.repository

import jp.kaleidot725.adbpad.domain.model.app.AppFileEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class InstalledAppRepositoryFileConversionTest {
    @Test
    fun `toPrivateDataPath converts data root to package and dot relative path`() {
        val result = "/data/data/com.example.app".toPrivateDataPath()

        assertEquals("com.example.app", result?.packageName)
        assertEquals(".", result?.relativePath)
    }

    @Test
    fun `toPrivateDataPath converts data child path to package and relative path`() {
        val result = "/data/data/com.example.app/shared_prefs/settings.xml".toPrivateDataPath()

        assertEquals("com.example.app", result?.packageName)
        assertEquals("shared_prefs/settings.xml", result?.relativePath)
    }

    @Test
    fun `toPrivateDataPath ignores sdcard path`() {
        val result = "/sdcard/Android/data/com.example.app/files".toPrivateDataPath()

        assertNull(result)
    }

    @Test
    fun `toAppFileEntriesFromLs converts directory output to app file entries`() {
        val result =
            """
            total 12
            drwx------ 3 u0_a123 u0_a123 4096 2026-05-29 10:00 .
            drwx------ 10 u0_a123 u0_a123 4096 2026-05-29 09:59 ..
            drwxrwx--x 2 u0_a123 u0_a123 4096 2026-05-29 10:01 files
            -rw-rw---- 1 u0_a123 u0_a123 128 2026-05-29 10:02 settings.xml
            lrwxrwxrwx 1 u0_a123 u0_a123 11 2026-05-29 10:03 current -> files/cache
            -rw-rw---- 1 u0_a123 u0_a123 256 2026-05-29 10:04 file with spaces.txt
            """.trimIndent()
                .toAppFileEntriesFromLs(directory = "/data/data/com.example.app")

        assertEquals(
            listOf(
                AppFileEntry.Directory(
                    name = "files",
                    path = "/data/data/com.example.app/files",
                    permissions = "drwxrwx--x",
                    size = 4096,
                    date = "2026-05-29",
                    time = "10:01",
                ),
                AppFileEntry.Link(
                    name = "current",
                    path = "/data/data/com.example.app/current",
                    permissions = "lrwxrwxrwx",
                    size = 11,
                    date = "2026-05-29",
                    time = "10:03",
                ),
                AppFileEntry.File(
                    name = "file with spaces.txt",
                    path = "/data/data/com.example.app/file with spaces.txt",
                    permissions = "-rw-rw----",
                    size = 256,
                    date = "2026-05-29",
                    time = "10:04",
                ),
                AppFileEntry.File(
                    name = "settings.xml",
                    path = "/data/data/com.example.app/settings.xml",
                    permissions = "-rw-rw----",
                    size = 128,
                    date = "2026-05-29",
                    time = "10:02",
                ),
            ),
            result,
        )
    }

    @Test
    fun `toAppFileEntriesFromLs resolves nested directory child paths`() {
        val result =
            "-rw-rw---- 1 u0_a123 u0_a123 64 2026-05-29 10:05 cache.db"
                .toAppFileEntriesFromLs(directory = "/data/data/com.example.app/files")

        val entry = assertInstanceOf(AppFileEntry.File::class.java, result.single())
        assertEquals("cache.db", entry.name)
        assertEquals("/data/data/com.example.app/files/cache.db", entry.path)
    }

    @Test
    fun `toShellArgument escapes single quotes`() {
        val result = "shared_prefs/user's settings.xml".toShellArgument()

        assertEquals("'shared_prefs/user'\\''s settings.xml'", result)
    }

    @Test
    fun `toSafeRemoteFileName keeps shell temp file names safe`() {
        val result = "user settings #1.xml".toSafeRemoteFileName()

        assertEquals("user_settings__1.xml", result)
    }

    @Test
    fun `normalizeFileName trims valid directory names`() {
        val result = AppFileOperationCommand.normalizeFileName("  cache  ")

        assertEquals("cache", result)
    }

    @Test
    fun `normalizeFileName rejects nested directory names`() {
        assertThrows(IOException::class.java) {
            AppFileOperationCommand.normalizeFileName("files/cache")
        }
    }

    @Test
    fun `mkdirCommand creates parents and quotes path`() {
        val result = AppFileOperationCommand.mkdirCommand("/sdcard/Android/data/com.example.app/user's cache")

        assertEquals("mkdir -p '/sdcard/Android/data/com.example.app/user'\\''s cache'", result)
    }
}
