package dev.bold7.pictures

/**
 * Select Picture of Pictures (sim-387, C: Setup's Wallpaper opens it, then a category's grid,
 * sim-388): the same pages titled "Select Picture", a picture's click handing it back to the caller
 * (Options' Change Wallpaper), which writes it only at its Save (D). Exported to Bold7's signature only.
 */
class SelectPictureActivity : PicturesActivity() {
    override val selecting = true
}
