package dev.bold7.theme

/**
 * Where each image of the extracted folder is, by its resource name: `<block>/res/drawable-nodpi/
 * <name>(.9).png` (or .gif), the layout of assets-gen and of core/firmware's gen/. Names are the
 * resource names the APKs compile them under, so one name opens the same image from the folder or
 * from the APK. A name in two blocks (only bold7_button, in the android and permissioncontroller
 * RROs' blocks) is the first block's, in sorted order.
 */
object ImageIndex {
    private val NAME = Regex("[a-z0-9_]+")
    private val FILE = Regex("""([^/]+)/res/drawable-nodpi/([a-z0-9_]+)(\.9\.png|\.png|\.gif)""")

    /** Only a resource name is ever looked up: nothing a caller passes can leave the folder. */
    fun isName(name: String) = NAME.matches(name)

    /** Name to relative path, from the folder's relative [paths]. */
    fun of(paths: List<String>): Map<String, String> {
        val index = HashMap<String, String>()
        for (p in paths.sorted()) {
            val m = FILE.matchEntire(p) ?: continue
            index.putIfAbsent(m.groupValues[2], p)
        }
        return index
    }
}
