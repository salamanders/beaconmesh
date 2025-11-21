package info.benjaminhill.beaconmesh.domain

import android.content.Context
import androidx.core.content.edit
import kotlin.random.Random

class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("mesh_prefs", Context.MODE_PRIVATE)

    val sourceId: Int by lazy {
        val saved = prefs.getInt(KEY_SOURCE_ID, 0)
        if (saved != 0) {
            saved
        } else {
            val newId = Random.nextInt()
            prefs.edit { putInt(KEY_SOURCE_ID, newId) }
            newId
        }
    }

    companion object {
        private const val KEY_SOURCE_ID = "source_id"
    }
}
