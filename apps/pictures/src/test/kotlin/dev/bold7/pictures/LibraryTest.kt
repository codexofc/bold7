package dev.bold7.pictures

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryTest {
    private fun pic(id: Long, name: String, path: String, bucket: Long, taken: Long) =
        Picture(id, name, bucket, path.trimEnd('/').substringAfterLast('/'), path, taken)

    private val cam1 = pic(1, "IMG_2.jpg", "DCIM/Camera/", 10, 200)
    private val cam2 = pic(2, "IMG_1.jpg", "DCIM/Camera/", 10, 300)
    private val shot = pic(3, "Screenshot.png", "Pictures/Screenshots/", 20, 100)
    private val wall = pic(4, "arch.png", "Pictures/Bold7/", 30, 50)
    private val all = listOf(cam1, cam2, shot, wall)

    @Test fun cameraAndLibraryComeFirstThenTheOtherFoldersByName() {
        val c = categories(all, Sort.NAME, "Camera Pictures", "Picture Library")
        assertEquals(listOf("Camera Pictures", "Picture Library", "Bold7", "Screenshots"), c.map { it.title })
        assertEquals(listOf(Source.Camera, Source.Library, Source.Folder(30), Source.Folder(20)), c.map { it.source })
        assertEquals(listOf(2, 2, 1, 1), c.map { it.pictures.size })
    }

    @Test fun aFolderWithoutNameIsNoCategoryButStaysInTheLibrary() {
        val root = Picture(5, "x.png", 50, "", "", 10)
        val c = categories(all + root, Sort.NAME, "Camera Pictures", "Picture Library")
        assertEquals(listOf("Camera Pictures", "Picture Library", "Bold7", "Screenshots"), c.map { it.title })
        assertEquals(3, c[1].pictures.size)
    }

    @Test fun theQ25sCameraFolderIsCameraPictures() {
        assertEquals(true, isCamera(pic(9, "IMG.jpg", "Pictures/BenOSCamera/", 40, 0)))
        assertEquals(false, isCamera(pic(9, "IMG.jpg", "Pictures/Camera roll/", 40, 0)))
    }

    @Test fun cameraAndLibraryShowEvenEmpty() {
        val c = categories(emptyList(), Sort.NAME, "Camera Pictures", "Picture Library")
        assertEquals(listOf(0, 0), c.map { it.pictures.size })
    }

    @Test fun theCameraFolderIsNotAlsoAFolderRow() {
        val c = categories(listOf(cam1), Sort.NAME, "Camera Pictures", "Picture Library")
        assertEquals(2, c.size)
    }

    @Test fun byNameCaseIgnoredOrByDate() {
        // arch.png before IMG_1.jpg: the case is ignored.
        assertEquals(listOf(wall, cam2, cam1, shot), sorted(all, Sort.NAME))
        assertEquals(listOf(cam2, cam1, shot, wall), sorted(all, Sort.NEWEST))
        assertEquals(listOf(wall, shot, cam1, cam2), sorted(all, Sort.OLDEST))
    }

    @Test fun aSourceGivesItsPictures() {
        assertEquals(listOf(cam2, cam1), picturesOf(all, Source.Camera, Sort.NEWEST))
        assertEquals(listOf(shot), picturesOf(all, Source.Folder(20), Sort.NAME))
        assertEquals(listOf(wall, shot), picturesOf(all, Source.Library, Sort.NAME))
        assertEquals(emptyList(), picturesOf(all, Source.Folder(99), Sort.NAME))
    }

    @Test fun theDayIsWrittenWithoutZeros() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        assertEquals("1/3/2006", day(1141171200000, utc)) // 2006-03-01
        assertEquals("25/12/2009", day(1261699200000, utc)) // 2009-12-25
    }

    @Test fun theCountSaysItems() {
        assertEquals("0 Items", items(0))
        assertEquals("1 Item", items(1))
        assertEquals("14 Items", items(14))
    }
}
