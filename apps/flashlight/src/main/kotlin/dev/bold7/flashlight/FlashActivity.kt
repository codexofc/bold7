package dev.bold7.flashlight

import android.app.Activity
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.bold7.ui.Menu
import dev.bold7.ui.MenuEntry
import dev.bold7.ui.MenuKey
import dev.bold7.ui.MenuResult
import dev.bold7.ui.MenuView
import dev.bold7.ui.SwitchApplication
import dev.bold7.ui.TitleBar
import dev.bold7.ui.status
import dev.bold7.ui.withSwitchApplication
import java.util.concurrent.Executors

private enum class Item { Toggle, Switch, Close }

/**
 * The Flashlight (D: the 9900 had none): the torch of the rear camera that has a flash,
 * switched by the trackpad's click, Enter or Space; Escape and Back close it, switching it off.
 * The camera service is the truth: [Lamp.on] only follows its [CameraManager.TorchCallback], so
 * another app taking the camera shows. Every camera call runs on one executor, never the main
 * thread. No service: the torch goes off in [onStop], whenever the app leaves the screen (D: the
 * 9900 had no flashlight to copy, and a torch left lit behind an app drains the battery and warms
 * the phone unseen).
 */
class FlashActivity : Activity() {
    private lateinit var view: FlashView
    private lateinit var menuView: MenuView<Item>
    private var lamp = Lamp()
    private var menu: Menu<Item>? = null
    private val camera = Executors.newSingleThreadExecutor()
    private var manager: CameraManager? = null
    private var id: String? = null
    private var registered = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == id) runOnUiThread { lamp = lamp.reported(enabled); render() }
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == id) runOnUiThread { lamp = lamp.unavailable(); render() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        view = FlashView(this) { toggle() }
        menuView = MenuView(this, glyph = { null }, label = ::label, onEntry = ::chosen, onExpand = {}, onClose = { show(null) })
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TitleBar(this@FlashActivity, getString(R.string.title)), LinearLayout.LayoutParams(match, TitleBar.HEIGHT))
            addView(view, LinearLayout.LayoutParams(match, 0, 1f))
        }
        setContentView(FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(match, match))
            addView(menuView)
        })
        manager = getSystemService(CameraManager::class.java)
        render()
    }

    override fun onStart() {
        super.onStart()
        camera.execute {
            val m = manager ?: return@execute
            if (id == null) {
                id = m.cameraIdList.firstOrNull { c ->
                    m.getCameraCharacteristics(c).let {
                        it.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                            it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    }
                }
                if (id == null) runOnUiThread { lamp = lamp.noFlash(); render() }
            }
            // Registering reports the current state at once, whatever another app did meanwhile.
            if (id != null && !registered) { m.registerTorchCallback(camera, callback); registered = true }
        }
    }

    /** Off and unregistered, queued behind onStart's work on the same executor. */
    override fun onStop() {
        camera.execute {
            val m = manager
            val c = id
            if (m != null && c != null) setTorch(m, c, false, quiet = true)
            if (m != null && registered) { m.unregisterTorchCallback(callback); registered = false }
        }
        lamp = lamp.copy(on = false, pending = false)
        super.onStop()
    }

    override fun onDestroy() {
        camera.shutdown()
        super.onDestroy()
    }

    private fun setTorch(m: CameraManager, c: String, on: Boolean, quiet: Boolean = false) {
        try {
            m.setTorchMode(c, on)
        } catch (e: android.hardware.camera2.CameraAccessException) {
            if (!quiet) runOnUiThread { lamp = lamp.unavailable(); render(); status(this, getString(R.string.state_in_use)) }
        }
    }

    private fun toggle() {
        val t = lamp.toggle()
        lamp = t.next
        val on = t.command ?: return
        camera.execute { setTorch(manager ?: return@execute, id ?: return@execute, on) }
    }

    /** Close switches off first, then leaves; onStop would do it too, this does not wait for it. */
    private fun close() {
        camera.execute { val c = id; if (manager != null && c != null) setTorch(manager!!, c, false, quiet = true) }
        finish()
    }

    private fun render() {
        view.bind(
            lamp,
            getString(when (lamp.status) {
                Lamp.Status.ON -> R.string.state_on
                Lamp.Status.OFF -> R.string.state_off
                Lamp.Status.IN_USE -> R.string.state_in_use
                Lamp.Status.NO_FLASH -> R.string.state_no_flash
            }),
        )
    }

    private fun label(i: Item): String = getString(when (i) {
        Item.Toggle -> if (lamp.on) R.string.act_turn_off else R.string.act_turn_on
        Item.Switch -> R.string.act_switch_application
        Item.Close -> R.string.act_close
    })

    private fun show(m: Menu<Item>?) {
        menu = m
        menuView.bind(m)
    }

    private fun chosen(index: Int) {
        val item = menu?.entries?.getOrNull(index)?.action ?: return
        show(null)
        when (item) {
            Item.Toggle -> toggle()
            Item.Switch -> SwitchApplication.start(this)
            Item.Close -> close()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val m = menu
        if (m != null) {
            if (e.action == KeyEvent.ACTION_UP) return true
            if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
            val click = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
            if (click && e.repeatCount > 0) return true
            val key = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuKey.BACK
                KeyEvent.KEYCODE_MENU -> MenuKey.MENU
                else -> if (click) MenuKey.CLICK else return true
            }
            when (val r = m.key(key)) {
                is MenuResult.Show -> show(r.menu)
                is MenuResult.Choose -> chosen(r.index)
                MenuResult.Close -> show(null)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        when (lampKey(code, e.repeatCount)) {
            LampKey.TOGGLE -> toggle()
            LampKey.CLOSE -> close()
            LampKey.MENU -> show(Menu(listOf(MenuEntry(Item.Toggle), MenuEntry(Item.Close)), highlighted = 0).withSwitchApplication(Item.Switch))
            null -> return super.dispatchKeyEvent(e)
        }
        return true
    }
}
