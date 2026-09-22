package ai.nextbillion.offlinedemo

import android.app.Activity
import android.content.Intent
import android.os.Build
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Keeps the three root demo pages aligned with the iOS Regions / Route / Diagnostics tabs. */
object DemoTabs {
    fun open(activity: Activity, itemId: Int) {
        val destination = when (itemId) {
            R.id.tab_regions -> RegionDownloadActivity::class.java
            R.id.tab_route -> MainActivity::class.java
            R.id.tab_diagnostics -> DiagnosticsActivity::class.java
            else -> return
        }
        if (activity.javaClass == destination) return
        activity.startActivity(
            Intent(activity, destination).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    fun bind(activity: Activity, navigation: BottomNavigationView, selectedItemId: Int) {
        navigation.selectedItemId = selectedItemId
        navigation.setOnItemSelectedListener { item ->
            if (item.itemId !in setOf(R.id.tab_regions, R.id.tab_route, R.id.tab_diagnostics)) {
                return@setOnItemSelectedListener false
            }
            if (item.itemId == selectedItemId) return@setOnItemSelectedListener true
            open(activity, item.itemId)
            true
        }
    }
}
