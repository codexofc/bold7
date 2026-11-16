package dev.bold7.ui

/**
 * An app's pages, stacked: [top] is shown, Back
 * (the key) pops it. Pure and immutable: the screen's state holds it, its
 * reducer pushes and pops. Simplification: no peek of the page under the top (the drag to the right of
 * the Classic, D) until a source shows it.
 */
data class ScreenStack<S>(val screens: List<S>) {
    init {
        require(screens.isNotEmpty()) { "a screen stack has at least its root" }
    }

    constructor(root: S) : this(listOf(root))

    val top: S get() = screens.last()
    val atRoot: Boolean get() = screens.size == 1

    fun push(screen: S) = ScreenStack(screens + screen)

    /** The stack under the top; null on the root, where Back leaves the app. */
    fun pop(): ScreenStack<S>? = if (atRoot) null else ScreenStack(screens.dropLast(1))
}
