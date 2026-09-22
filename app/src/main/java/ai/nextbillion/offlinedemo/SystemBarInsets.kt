package ai.nextbillion.offlinedemo

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat

/**
 * Enables edge-to-edge rendering while preserving the root view's authored padding.
 *
 * The initial padding is captured once so repeated inset dispatches (rotation, keyboard, display
 * cutout changes) do not accumulate padding. Call this once for the Activity's content root.
 */
fun Activity.applySystemBarInsets(view: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val initialLeft = view.paddingLeft
    val initialTop = view.paddingTop
    val initialRight = view.paddingRight
    val initialBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
        val systemBars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        target.setPadding(
            initialLeft + systemBars.left,
            initialTop + systemBars.top,
            initialRight + systemBars.right,
            initialBottom + systemBars.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(view)
}
