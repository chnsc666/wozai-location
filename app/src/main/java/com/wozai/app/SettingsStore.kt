package com.wozai.app

import android.content.Context

/**
 * 应用设置：发短信时的默认收件人号码，保存在本机 SharedPreferences。
 * 没有设置过（或用户清空）时发短信不预填收件人，让用户在短信 App 里自己选。
 */
object SettingsStore {

    private const val PREF = "app_settings"
    // 地图 Key 单独存一个文件：系统云备份/换机迁移时整体排除该文件，
    // 号码与定位参数仍放在 app_settings 里，换机后照样能带过去。
    private const val PREF_KEYS = "app_keys"
    private const val KEY_SMS_TO = "sms_to"
    private const val KEY_SMS_NAME = "sms_to_name"
    private const val KEY_AMAP = "amap_web_key"
    private const val KEY_TENCENT = "tencent_web_key"
    private const val KEY_LOC_INTERVAL = "loc_interval_sec"
    private const val KEY_MIN_DISTANCE = "loc_min_distance_m"
    private const val KEY_LOC_TIMEOUT = "loc_timeout_sec"
    private const val KEY_SMS_PERM_ASKED = "sms_perm_asked"
    private const val KEY_LOC_PERM_ASKED = "loc_perm_asked"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 读取默认短信收件人；空字符串表示不预填 */
    fun getSmsTo(context: Context): String =
        prefs(context).getString(KEY_SMS_TO, "") ?: ""

    /** 保存默认短信收件人；传空字符串表示清空 */
    fun setSmsTo(context: Context, number: String) {
        prefs(context).edit().putString(KEY_SMS_TO, number).apply()
    }

    /** 读取默认短信收件人姓名；空字符串表示没填（提示里就只显示号码） */
    fun getSmsName(context: Context): String =
        prefs(context).getString(KEY_SMS_NAME, "") ?: ""

    /** 保存默认短信收件人姓名；传空字符串表示清空 */
    fun setSmsName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SMS_NAME, name.trim()).apply()
    }

    /**
     * 是否已经为短信权限弹过系统请求框。
     * 用来区分「从没申请过」和「拒绝过且勾了不再询问」——只有后者才该引导去系统设置。
     * （从没申请过时 shouldShowRequestPermissionRationale 同样返回 false，不能只靠它判断）
     */
    fun hasAskedSmsPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMS_PERM_ASKED, false)

    fun setAskedSmsPermission(context: Context, asked: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMS_PERM_ASKED, asked).apply()
    }

    /** 是否已经为定位权限弹过系统请求框（区分「从没申请」与「永久拒绝」） */
    fun hasAskedLocPermission(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOC_PERM_ASKED, false)

    fun setAskedLocPermission(context: Context, asked: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOC_PERM_ASKED, asked).apply()
    }

    /**
     * 丢弃旧版持久化的历史定位数据（地址/地标名、定位时间/来源均不再保存、不再显示）。
     * 仅清这四个旧key，不动短信收件人与地图 Key；每次启动调用，兼容从老版本升级上来的用户。
     */
    fun discardHistoricalLocation(context: Context) {
        prefs(context).edit()
            .remove("last_address")
            .remove("place_name")
            .remove("last_fix_time")
            .remove("address_source")
            .apply()
    }

    // 地图 Key：用户在设置里填入的值。空字符串表示未填，此时该家服务不可用，不回退到任何默认值。
    // 存放在独立的 app_keys 文件里，系统备份规则整体排除它，Key 不上云、不随换机迁移。

    private fun keyPrefs(context: Context) =
        context.getSharedPreferences(PREF_KEYS, Context.MODE_PRIVATE)

    /** 用户填入的高德 Web 服务 Key；未填返回空字符串 */
    fun getAmapKey(context: Context): String = getKeyWithMigration(context, KEY_AMAP)

    fun setAmapKey(context: Context, key: String) {
        keyPrefs(context).edit().putString(KEY_AMAP, key.trim()).apply()
        // 清掉老版本可能留在 app_settings 里的同一项，避免两份不一致
        prefs(context).edit().remove(KEY_AMAP).apply()
    }

    /** 用户填入的腾讯位置服务 WebService Key；未填返回空字符串 */
    fun getTencentKey(context: Context): String = getKeyWithMigration(context, KEY_TENCENT)

    fun setTencentKey(context: Context, key: String) {
        keyPrefs(context).edit().putString(KEY_TENCENT, key.trim()).apply()
        prefs(context).edit().remove(KEY_TENCENT).apply()
    }

    /**
     * 启动时调用一次：把老版本留在 app_settings 里的 Key 搬到 app_keys。
     * 早搬早安全——app_settings 仍在备份范围内，Key 留在里面就会随备份上云。
     */
    fun migrateMapKeys(context: Context) {
        getKeyWithMigration(context, KEY_AMAP)
        getKeyWithMigration(context, KEY_TENCENT)
    }

    /**
     * 读 Key 并兼容老版本：早期版本 Key 存在 app_settings 里，
     * 首次读到旧值时自动搬到 app_keys，并把旧值删掉（否则它仍会随 app_settings 被备份）。
     */
    private fun getKeyWithMigration(context: Context, prefKey: String): String {
        val kp = keyPrefs(context)
        val fromNew = kp.getString(prefKey, "") ?: ""
        if (fromNew.isNotEmpty()) return fromNew
        val legacy = prefs(context).getString(prefKey, "") ?: ""
        if (legacy.isNotEmpty()) {
            kp.edit().putString(prefKey, legacy).apply()
            prefs(context).edit().remove(prefKey).apply()
        }
        return legacy
    }

    // 定位间隔（秒）：用户在地图 Key 设置弹窗里填写，单位秒。
    // 有效正整数表示按该秒数周期性刷新；空字符串（或 0）表示「手动」——只在点刷新按钮/回到前台时定位一次。

    /** 定位间隔字符串；返回原样文本以便输入框回显。默认 15 秒 */
    fun getLocIntervalText(context: Context): String =
        prefs(context).getString(KEY_LOC_INTERVAL, "15") ?: "15"

    fun setLocIntervalText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LOC_INTERVAL, text.trim()).apply()
    }

    /**
     * 解析成毫秒：空/0/非法返回 0（手动模式）；1-4 秒按最小 5 秒处理。
     * 界面已禁止填 0 和 1-4 秒，这里钳制是为了兜住旧版本残留或绕过界面写入的值。
     */
    fun getLocIntervalMillis(context: Context): Long {
        val sec = getLocIntervalText(context).toLongOrNull() ?: 0L
        return when {
            sec <= 0L -> 0L
            sec < 5L -> 5_000L
            else -> sec * 1000L
        }
    }

    /** 定位距离字符串；返回原样文本以便输入框回显。默认 5 米 */
    fun getMinDistanceText(context: Context): String =
        prefs(context).getString(KEY_MIN_DISTANCE, "5") ?: "5"

    fun setMinDistanceText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_MIN_DISTANCE, text.trim()).apply()
    }

    /**
     * 解析成米：默认 5 米；界面禁止填 0，这里兜底把 0/负数/非法值回退为 5 米。
     * 返回 Float 供 LocationManager.requestLocationUpdates 的 minDistance 参数直接用。
     */
    fun getMinDistanceMeters(context: Context): Float {
        val meters = getMinDistanceText(context).toFloatOrNull() ?: 5f
        return if (meters < 1f) 5f else meters
    }

    // 定位超时（秒）：多久还没拿到定位结果就判定「这次没定上」，并弹出失败原因引导。
    // 留空 = 自动：按「定位间隔 + 5 秒」算，手动模式按 15 秒。

    /** 定位超时字符串；空字符串表示自动 */
    fun getLocTimeoutText(context: Context): String =
        prefs(context).getString(KEY_LOC_TIMEOUT, "") ?: ""

    fun setLocTimeoutText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LOC_TIMEOUT, text.trim()).apply()
    }

    /** 建议值：定位间隔 + 5 秒；手动模式（间隔为空/0）建议 15 秒 */
    fun suggestLocTimeoutSec(intervalText: String): Long {
        val sec = intervalText.trim().toLongOrNull() ?: 0L
        return if (sec < 5L) 15L else sec + 5L
    }

    /**
     * 解析成毫秒：留空或非法 → 自动取建议值（间隔+5秒，手动模式 15 秒）；
     * 填了数字则钳制在 5~180 秒，避免填 1 秒就误报、或填太大形同没有超时。
     */
    fun getLocTimeoutMillis(context: Context): Long {
        val sec = getLocTimeoutText(context).toLongOrNull()
        if (sec != null && sec > 0L) return sec.coerceIn(5L, 180L) * 1000L
        return suggestLocTimeoutSec(getLocIntervalText(context)) * 1000L
    }
}
