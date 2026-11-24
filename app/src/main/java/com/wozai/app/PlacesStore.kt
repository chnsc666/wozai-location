package com.wozai.app

import android.content.Context

/**
 * 常用位置存取：固定 5 个槽位，存本地 SharedPreferences。
 * 每个槽位 = 本地名称 + WGS-84 经纬度 + 地图实际名称（POI 名称）。
 * name 只用于本地列表显示；导航目的地名称用 poiName，不用用户自定义的名称。
 * 兼容旧格式（只有 3 段，没有 poiName）。
 * 没设置过的槽位 name 为空，列表和导航都不会显示它。
 */
object PlacesStore {

    const val SLOT_COUNT = 5
    private const val PREF = "places_store"
    private const val KEY_PREFIX = "place_"
    private const val SEP = "|"

    data class Place(
        val name: String,
        val lat: Double,
        val lng: Double,
        val poiName: String = ""
    ) {
        val isSet: Boolean get() = name.isNotBlank() && lat != 0.0 && lng != 0.0
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 读取某个槽位（1..5），未设置返回 null */
    fun get(context: Context, index: Int): Place? {
        if (index !in 1..SLOT_COUNT) return null
        val raw = prefs(context).getString("$KEY_PREFIX$index", null) ?: return null
        val parts = raw.split(SEP)
        if (parts.size !in 3..4) return null
        return try {
            Place(
                parts[0],
                parts[1].toDouble(),
                parts[2].toDouble(),
                if (parts.size == 4) parts[3] else ""
            ).takeIf { it.isSet }
        } catch (_: Exception) {
            null
        }
    }

    /** 读取全部 5 个槽位，未设置的位置为 null */
    fun all(context: Context): List<Place?> = (1..SLOT_COUNT).map { get(context, it) }

    /** 保存一个槽位：name 为本地显示名，poiName 为地图实际名称（导航用） */
    fun set(
        context: Context,
        index: Int,
        name: String,
        lat: Double,
        lng: Double,
        poiName: String = ""
    ) {
        if (index !in 1..SLOT_COUNT) return
        prefs(context).edit()
            .putString("$KEY_PREFIX$index", "$name$SEP$lat$SEP$lng$SEP$poiName")
            .apply()
    }

    /** 清空一个槽位 */
    fun clear(context: Context, index: Int) {
        if (index !in 1..SLOT_COUNT) return
        prefs(context).edit().remove("$KEY_PREFIX$index").apply()
    }
}
