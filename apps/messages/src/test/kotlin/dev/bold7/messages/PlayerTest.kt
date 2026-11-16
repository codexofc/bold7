package dev.bold7.messages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerTest {
    private val on = PlayerState().prepared(60_000)

    @Test
    fun opensPlayingWithItsBar() {
        assertTrue(on.playing && on.shown)
        assertEquals(Control.PLAY_PAUSE, on.focus)
    }

    @Test
    fun clickOnPlayPauseToggles() {
        val paused = on.key(PlayerKey.CLICK).first
        assertFalse(paused.playing)
        assertTrue(paused.key(PlayerKey.CLICK).first.playing)
    }

    @Test
    fun hiddenBarComesBackOnAnyKeyWithoutActing() {
        val hidden = on.idle()
        assertFalse(hidden.shown)
        val back = hidden.key(PlayerKey.CLICK).first
        assertTrue(back.shown && back.playing)
    }

    @Test
    fun idleKeepsTheBarWhilePausedOrSliding() {
        assertTrue(on.copy(playing = false).idle().shown)
        assertTrue(on.copy(seeking = true).idle().shown)
    }

    @Test
    fun focusSkipsNextAndProgressIsAboveTheBar() {
        val stop = on.key(PlayerKey.RIGHT).first
        assertEquals(Control.STOP, stop.focus)
        assertEquals(Control.STOP, stop.key(PlayerKey.RIGHT).first.focus)
        assertEquals(Control.PROGRESS, stop.key(PlayerKey.UP).first.focus)
        assertEquals(Control.PLAY_PAUSE, stop.key(PlayerKey.UP).first.key(PlayerKey.DOWN).first.focus)
        assertEquals(Control.PREVIOUS, on.key(PlayerKey.LEFT).first.focus)
    }

    @Test
    fun stopAndPreviousGoBackToTheStart() {
        val at = on.tick(30_000)
        val (stopped, e) = at.copy(focus = Control.STOP).key(PlayerKey.CLICK)
        assertEquals(PlayerState(false, 0, 60_000, true, Control.STOP), stopped)
        assertEquals(PlayerEffect.Seek(0), e)
        val (prev, e2) = at.copy(focus = Control.PREVIOUS).key(PlayerKey.CLICK)
        assertTrue(prev.playing)
        assertEquals(PlayerEffect.Seek(0), e2)
    }

    @Test
    fun clickOnProgressThenSlideSeeksAndClickLeaves() {
        val s = on.tick(5_000).copy(focus = Control.PROGRESS).key(PlayerKey.CLICK).first
        assertTrue(s.seeking)
        val (right, e) = s.key(PlayerKey.RIGHT)
        assertEquals(15_000, right.position)
        assertEquals(PlayerEffect.Seek(15_000), e)
        assertEquals(0, right.copy(position = 3_000).key(PlayerKey.LEFT).first.position)
        assertEquals(60_000, right.copy(position = 55_000).key(PlayerKey.RIGHT).first.position)
        assertFalse(right.key(PlayerKey.CLICK).first.seeking)
    }

    @Test
    fun escapeLeavesTheSlideThenCloses() {
        val s = on.copy(seeking = true)
        assertEquals(false to null, s.key(PlayerKey.BACK).let { it.first.seeking to it.second })
        assertEquals(PlayerEffect.Close, on.key(PlayerKey.BACK).second)
    }

    @Test
    fun endStopsAtTheStart() {
        val end = on.tick(60_000).completed()
        assertEquals(0, end.position)
        assertFalse(end.playing)
        assertTrue(end.shown)
        assertEquals(0.5f, on.tick(30_000).fraction)
        assertEquals(0f, PlayerState().fraction)
    }

    @Test
    fun textAndTypes() {
        assertEquals("0:09", timeText(9_400))
        assertEquals("1:05", timeText(65_000))
        assertEquals("1:01:01", timeText(3_661_000))
        assertTrue(plays("Video/MP4") && plays("audio/amr"))
        assertFalse(plays("text/x-vcard"))
        assertNull(on.key(PlayerKey.UP).second)
    }
}
