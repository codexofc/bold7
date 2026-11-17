package dev.bold7.sysui

import android.animation.Animator
import android.content.Context
import android.view.View
import com.android.systemui.plugins.`ToastPlugin$Toast`
import com.android.systemui.toast.SystemUIToast
import com.android.systemui.toast.ToastFactory

// What Toasts reaches by name, compiled against the Q25's SystemUI (stubs/systemui.jar), like
// HookTargets.kt. Never run.
@Suppress("unused", "UNUSED_VARIABLE")
private fun toastTargets(factory: ToastFactory, context: Context) {
    val toast: SystemUIToast = factory.createToast(context, context, "", "", 0, 0)
    val plugin: `ToastPlugin$Toast`? = toast.mPluginToast
    val view: View = toast.mToastView
    val fadeIn: Animator? = toast.mInAnimator
    val fadeOut: Animator? = toast.mOutAnimator
}
