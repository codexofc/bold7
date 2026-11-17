package dev.bold7.design

/**
 * The bolt the 9900 animates over the battery while it charges (docs/BBOS7-VISUEL.md §1), the
 * same in the home's, the lock screen's and the apps' banners (homescreen-banner.pme,
 * lockscreen.pme, banner.pme, A; sim-470, sim-471, C). Images 1 to 5 are lightning_bolt_01 to _05
 * (18x29, the bolt fading in), 0 none (lightning_bolt_00 is 1x1 and empty).
 */
object ChargingBolt {
    /** The pme's dur, 1800 ms (A; 1.80 s fitted on 200 frames of the simulator, C). */
    const val PERIOD_MS = 1800L

    /** Its place from the battery's frame: (20, 0) for the frame at (3, 2) in the three pme (A, sim-470). */
    const val X = 17
    const val Y = -2

    // The pme's discrete animation: l00 to l11 show images 0 1 2 3 4 5 4 3 2 1 0 0 from keyTimes 0,
    // .05, .1, .15, .2, .3, .75, .8, .85, .9, .95 and 1 (l11 never stays: the next cycle starts).
    private val STARTS = longArrayOf(0, 90, 180, 270, 360, 540, 1350, 1440, 1530, 1620, 1710)
    private val IMAGES = intArrayOf(0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0)

    /**
     * Charging under 100 % ([status] BatteryManager's, 2 charging): `batteryStatus_isCharging`
     * starts the animation. Android's level says when it is full, not its status: the Q25 stays
     * Charging at 100 % while its charger says Full.
     */
    fun shows(status: Int, percent: Int): Boolean = status == 2 && percent in 0..99

    /** The image [ms] after the animation began. */
    fun image(ms: Long): Int {
        val t = ms.mod(PERIOD_MS)
        var k = STARTS.size - 1
        while (STARTS[k] > t) k--
        return IMAGES[k]
    }

    /** Milliseconds from [ms] to the next change of image: one redraw each, none in between. */
    fun untilNext(ms: Long): Long {
        val t = ms.mod(PERIOD_MS)
        val k = STARTS.indexOfFirst { it > t }
        return if (k < 0) PERIOD_MS + STARTS[1] - t else STARTS[k] - t
    }
}
