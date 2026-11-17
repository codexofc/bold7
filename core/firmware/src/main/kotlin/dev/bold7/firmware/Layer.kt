package dev.bold7.firmware

import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * An extraction's gen/ laid out under the module's layer/, as the development zip carries it,
 * before build-image.sh puts layer/ into layer.img:
 *
 * - the images, every `<bloc>/res` tree, under product/media/bold7/ (core/theme reads them there);
 * - the 9900's wallpapers under product/media/bold7/wallpapers/, which the Setup Wizard offers;
 * - OS 7's part of Typing's lists under product/media/bold7/saisie/assets/ (Typing reads it there);
 * - the help of the 9900, decoded, under product/media/bold7/help/ (the launcher's Help reads it there);
 * - the sounds of sounds.tsv under product/media/audio/;
 * - BBAlpha and the 9900's fallback fonts under system/fonts/;
 * - system/etc/fonts.xml derived from the phone's stock one ([FontsXml]);
 * - product/media/bootanimation.zip ([BootAnimation], with the logo of [BootScreen] when the extraction has it).
 *
 * Returns what it laid, for the user: "2,341 images, 48 sounds, 23 fonts".
 */
internal object Layer {
    fun lay(gen: File, stockFontsXml: String, layer: File): String {
        fun from(rel: String) = File(gen, rel).takeIf { it.isDirectory } ?: throw IOException("no $rel in $gen")
        val blocks = gen.listFiles { f -> File(f, "res").isDirectory }.orEmpty()
        if (blocks.isEmpty()) throw IOException("no image in $gen")
        for (b in blocks) File(b, "res").copyRecursively(File(layer, "product/media/bold7/${b.name}/res"), overwrite = true)
        File(gen, "wallpapers").takeIf { it.isDirectory }?.copyRecursively(File(layer, "product/media/bold7/wallpapers"), overwrite = true)
        File(gen, "saisie/assets").takeIf { it.isDirectory }?.copyRecursively(File(layer, "product/media/bold7/saisie/assets"), overwrite = true)
        File(gen, "help").takeIf { it.isDirectory }?.copyRecursively(File(layer, "product/media/bold7/help"), overwrite = true)
        from("module/product/media/audio").copyRecursively(File(layer, "product/media/audio"), overwrite = true)
        val fonts = File(layer, "system/fonts")
        from("module/system/fonts").copyRecursively(fonts, overwrite = true)
        from("module/system/fonts-os7").copyRecursively(fonts, overwrite = true)
        val missing = FontsXml.FILES.filterNot { File(fonts, it).isFile }
        if (missing.isNotEmpty()) throw IOException("fonts missing from the extraction: ${missing.joinToString()}")
        put(layer, "system/etc/fonts.xml", FontsXml.derive(stockFontsXml).toByteArray())
        val logo = File(gen, BootScreen.LOGO).takeIf { it.isFile }?.let { Png.decode(it.readBytes()) }
        put(layer, "product/media/bootanimation.zip", BootAnimation.build(logo))
        fun count(vararg rel: String) = rel.sumOf { r -> File(gen, r).walkTopDown().count { it.isFile } }
        val images = blocks.sumOf { b -> File(b, "res").walkTopDown().count { it.isFile } }
        return "%,d images, %,d sounds, %,d fonts".format(Locale.US, images, count("module/product/media/audio"),
            count("module/system/fonts", "module/system/fonts-os7"))
    }
}
