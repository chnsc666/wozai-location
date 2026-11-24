package com.wozai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件：把地图 Key、联系人姓名与号码、常用位置（导航地址）导出成一个 JSON 文本，
 * 换机或重装后可导入恢复。
 *
 * 只备份「填了要重填很麻烦」的设置，不含运行日志和历史位置。
 * 文件里带 app/type 标记，导入时先校验，避免把别的 txt/json 误当备份。
 *
 * 格式版本：
 * - 1：Key + 联系人
 * - 2：在 1 的基础上增加 places（常用位置，最多 5 个槽位）
 * 导入兼容旧版：没有 places 字段就当作空列表。
 */
object Backup {

    /** 文件标记：不是这两个值的文件一律拒绝导入 */
    private const val APP_TAG = "wo-zai-na"
    private const val TYPE_TAG = "backup"
    private const val FORMAT = 2

    /** 备份里的一个常用位置（slot 为 1..5 的槽位号，保持导入后顺序不变） */
    data class SlotPlace(
        val slot: Int,
        val name: String,
        val lat: Double,
        val lng: Double,
        val poiName: String
    )

    /** 备份内容 */
    data class Data(
        val amapKey: String,
        val tencentKey: String,
        val smsName: String,
        val smsNumber: String,
        val places: List<SlotPlace> = emptyList()
    )

    /** 从当前设置生成备份文本（缩进 2 空格，方便用户自己打开看） */
    fun buildJson(context: Context): String {
        val o = JSONObject()
        o.put("app", APP_TAG)
        o.put("type", TYPE_TAG)
        o.put("format", FORMAT)
        o.put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
        o.put("amapKey", SettingsStore.getAmapKey(context))
        o.put("tencentKey", SettingsStore.getTencentKey(context))
        o.put("smsName", SettingsStore.getSmsName(context))
        o.put("smsNumber", SettingsStore.getSmsTo(context))
        val arr = JSONArray()
        PlacesStore.all(context).forEachIndexed { i, place ->
            if (place != null) {
                arr.put(
                    JSONObject().apply {
                        put("slot", i + 1)
                        put("name", place.name)
                        put("lat", place.lat)
                        put("lng", place.lng)
                        put("poiName", place.poiName)
                    }
                )
            }
        }
        o.put("places", arr)
        return o.toString(2)
    }

    /**
     * 解析备份文本；格式不对或不是本 App 的备份就抛异常，由调用方提示用户。
     * 号码为空允许（用户可能只设了 Key），非空则校格式，与界面填写规则一致。
     * 位置里非法/越界的条目跳过，不让一处坏数据毁掉整份备份。
     */
    fun parse(text: String): Data {
        val o = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw Exception("不是 JSON 格式")
        }
        if (o.optString("app") != APP_TAG || o.optString("type") != TYPE_TAG) {
            throw Exception("不是「我在哪」的备份文件")
        }
        val number = o.optString("smsNumber").trim().replace(Regex("[\\s-]"), "")
        if (number.isNotEmpty() && !number.matches(Regex("\\+?\\d{5,20}"))) {
            throw Exception("备份里的手机号格式不对")
        }
        return Data(
            amapKey = o.optString("amapKey").trim(),
            tencentKey = o.optString("tencentKey").trim(),
            smsName = o.optString("smsName").trim(),
            smsNumber = number,
            places = parsePlaces(o.optJSONArray("places"))
        )
    }

    private fun parsePlaces(arr: JSONArray?): List<SlotPlace> {
        if (arr == null) return emptyList()
        val out = ArrayList<SlotPlace>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val slot = p.optInt("slot", 0)
            val name = p.optString("name").trim()
            val lat = p.optDouble("lat", Double.NaN)
            val lng = p.optDouble("lng", Double.NaN)
            if (slot !in 1..PlacesStore.SLOT_COUNT) continue
            if (name.isEmpty()) continue
            if (lat.isNaN() || lng.isNaN()) continue
            if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) continue
            if (lat == 0.0 && lng == 0.0) continue
            out.add(SlotPlace(slot, name, lat, lng, p.optString("poiName").trim()))
        }
        return out
    }

    /**
     * 写入设置：整体覆盖当前值。
     * 常用位置先清空 5 个槽位再写入备份里的，保证导入后与备份完全一致（不会残留旧地址）。
     */
    fun apply(context: Context, data: Data) {
        SettingsStore.setAmapKey(context, data.amapKey)
        SettingsStore.setTencentKey(context, data.tencentKey)
        SettingsStore.setSmsName(context, data.smsName)
        SettingsStore.setSmsTo(context, data.smsNumber)
        for (slot in 1..PlacesStore.SLOT_COUNT) PlacesStore.clear(context, slot)
        for (p in data.places) {
            PlacesStore.set(context, p.slot, p.name, p.lat, p.lng, p.poiName)
        }
    }
}
