package com.ilukezippo.lapfold

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ToggleTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val svc = LapFoldService.instance
        if (svc == null) {
            // not allowed yet: open the app so the user can grant Accessibility
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(i)
            }
            return
        }
        svc.toggle()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val svc = LapFoldService.instance
        t.state = when {
            svc == null -> Tile.STATE_UNAVAILABLE
            svc.isOn -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        t.subtitle = when { svc == null -> "Not set up"; svc.isOn -> "On"; else -> "Off" }
        t.updateTile()
    }
}
