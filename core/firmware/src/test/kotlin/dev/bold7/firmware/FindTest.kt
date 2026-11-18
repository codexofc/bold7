package dev.bold7.firmware

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Where the official file is looked for in Download/: by its size, at the top and one folder down. */
class FindTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // Sparse: only the size counts here, the sha256 comes after.
    private fun sized(dir: File, name: String, size: Long) = File(dir, name).also { it.parentFile.mkdirs(); RandomAccessFile(it, "rw").use { f -> f.setLength(size) } }

    @Test fun theFileIsFoundAtTheTopAndInAFolderOfDownloadNotDeeper() {
        val download = tmp.newFolder("Download")
        val loader = Firmware.LOADER
        // The Drive zip unpacked on the phone: Download/Bold7-fichiers-9900-et-icones/<the .exe>.
        val inFolder = sized(File(download, "Bold7-fichiers-9900-et-icones"), loader.fileName, loader.size)
        sized(File(download, "a/b"), "deep.exe", loader.size)
        sized(download, "other.bin", 12)
        assertEquals(listOf(inFolder), candidates(download))
        val top = sized(download, loader.fileName, loader.size)
        // The top first, as before.
        assertEquals(listOf(top, inFolder), candidates(download))
    }

    @Test fun anIncompleteDownloadIsNamedInAFolderToo() {
        val download = tmp.newFolder("Download")
        val partial = sized(File(download, "Bold7-fichiers-9900-et-icones"), Firmware.LOADER.fileName, 1000)
        assertEquals(listOf(partial), misnamed(download))
    }
}
