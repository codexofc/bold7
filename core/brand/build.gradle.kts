plugins { id("com.android.library") }

// The Bold7 logo (docs/logo/bold7.svg, our own drawing), for Bold7's own settings only: the
// launcher (Options, Setup, its entry in Android's Settings) and SystemUI's module. Every other app
// gets mipmap/app_icon through this manifest: its OS 7 icon in the development build, Android's
// own otherwise. The launcher's grid keeps OS 7's icons of the firmware.
android { namespace = "dev.bold7.brand" }
