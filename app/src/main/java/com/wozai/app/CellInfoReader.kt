package com.wozai.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * 基站信息（精简版）：只保留 运营商、制式（4G/5G）、全球小区标识。
 * 2G/3G 小区直接忽略，不做展示。
 */
data class CellSnapshot(
    val operator: String,      // 运营商名称（按实际 PLMN 动态识别，中文）
    val rat: String,           // 制式：4G / 5G
    val cellId: String,        // 原始 ECI/NCI，仅用于括号里的基站ID_小区标识拆分
    val globalCellId: String   // ECGI/NCGI（PLMN + ECI/NCI），用于主体显示
) {
    /**
     * 由原始 ECI/NCI 反算「基站ID_小区标识」（括号内容，保持不变）：
     * - 4G LTE：ECI = 基站ID(eNB ID, 20bit) << 8 | 小区标识(Cell ID, 8bit)
     * - 5G NR ：NCI = 基站ID(gNB ID, 24bit) << 12 | 小区标识(Cell ID, 12bit)
     */
    fun cellBreakdown(): String? {
        val cid = cellId.toLongOrNull() ?: return null
        if (cid <= 0) return null
        return when (rat) {
            "4G" -> "${cid shr 8}_${cid and 0xFF}"
            "5G" -> "${cid shr 12}_${cid and 0xFFF}"
            else -> null
        }
    }

    /** 显示用：主体为 ECGI/NCGI，后面括号给出 基站ID_小区标识。
     *  括号用英文半角 + 前面留一个空格，比全角括号窄约 20dp，
     *  配合 MainActivity.fitCellText() 的自动缩字号，保证整行不折到第二行。 */
    fun cidWithBreakdown(): String {
        val b = cellBreakdown() ?: return globalCellId
        return "$globalCellId ($b)"
    }

    fun detailText(): String =
        "运营商: $operator$rat\n基站: ${cidWithBreakdown()}"
}

object CellInfoReader {

    private fun hasPhonePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun read(context: Context): CellSnapshot? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val phoneOk = hasPhonePermission(context)

        // —— 锁定"当前上网卡"：系统默认数据卡的 subId ——
        // 实测（小米13/Android16）：createForSubscriptionId(subId).allCellInfo 并未按卡过滤，
        // 返回内容与全局 allCellInfo 完全相同，且 CellInfo.subId 反射被系统挡住（恒为 null）。
        // 唯一可靠的是「上网卡专属的 dataNetworkType」——实测切卡后读得很准（4G/5G）。
        // 因此：用上网卡的数据制式决定选 LTE 还是 NR 小区；PLMN/运营商用 SIM 的。
        val dataSubId = defaultDataSubId()

        var subPlmn: String? = null      // 上网卡 SIM 的 PLMN（可靠，如 46011）
        var subRat: String? = null       // 上网卡当前数据制式：5G / 4G（可靠）
        if (phoneOk && dataSubId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val subTm = tm.createForSubscriptionId(dataSubId)
                subPlmn = subTm.networkOperator?.let { digitsPlmn(it) }
                subRat = ratOf(subTm.dataNetworkType)
            } catch (_: Exception) { }
        }

        val allCells = try {
            tm.allCellInfo?.filterNotNull().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        // 只保留 4G LTE / 5G NR，2G/3G 忽略
        val candidates = allCells.filter { info ->
            info is CellInfoLte ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr)
        }
        if (candidates.isEmpty()) return null

        // 按上网卡制式选对应类型的注册小区：卡1(4G)→LTE小区，卡2(5G)→NR小区。
        // 只有拿不到上网卡制式（如权限被拒）时才退回"已注册NR优先"。
        val cell = selectCell(candidates, subRat) ?: return null

        // 制式显示以上网卡为准（可靠）；拿不到才用小区自身类型
        val rat = subRat ?: if (cell is CellInfoNr) "5G" else "4G"

        var cellId = "-"
        var cellPlmn: String? = null
        when (cell) {
            is CellInfoNr -> {
                val identity = cell.cellIdentity as? CellIdentityNr
                cellPlmn = identity?.let {
                    val mcc = it.mccString.orEmpty()
                    val mnc = it.mncString.orEmpty()
                    (mcc + mnc).takeIf { s -> s.length >= 5 }
                }
                val nci = identity?.getNci() ?: -1L
                cellId = if (nci > 0) nci.toString() else "-"
            }
            is CellInfoLte -> {
                val identity = cell.cellIdentity
                cellPlmn = plmnOfLte(identity)
                val ci = identity.ci
                cellId = if (ci > 0 && ci != Int.MAX_VALUE) ci.toString() else "-"
            }
        }
        if (cellId == "-") return null

        // PLMN 优先用上网卡 SIM 的（可靠），其次才用小区自身的（可能上报错误）
        val plmn = subPlmn ?: cellPlmn
        val globalCellId = if (plmn != null) plmn + cellId else cellId

        // 运营商：按实际 PLMN 动态映射中文，任何运营商组合都能识别
        val operator = operatorFromPlmn(plmn)
            ?: cleanOperator(cellOperator(cell))
            ?: operatorFromTm(tm)
            ?: "未知运营商"

        return CellSnapshot(operator = operator, rat = rat, cellId = cellId, globalCellId = globalCellId)
    }

    /** 数据网络类型 → 制式文本 */
    private fun ratOf(netType: Int): String? = when (netType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        else -> null
    }

    /** 按上网卡制式选出对应类型的注册小区 */
    private fun selectCell(candidates: List<CellInfo>, subRat: String?): CellInfo? {
        val nr = candidates.filter { it is CellInfoNr }
        val lte = candidates.filter { it is CellInfoLte }

        // 上网卡是 4G → 选 LTE 小区；是 5G → 选 NR 小区。优先已注册的。
        when (subRat) {
            "4G" -> lte.firstOrNull { it.isRegistered }?.let { return it }
            "5G" -> nr.firstOrNull { it.isRegistered }?.let { return it }
        }
        // 该制式下没有已注册小区时，退回该制式任意小区（仍不跨制式，避免串卡）
        when (subRat) {
            "4G" -> lte.firstOrNull()?.let { return it }
            "5G" -> nr.firstOrNull()?.let { return it }
        }

        // 兜底：拿不到上网卡制式时，已注册 NR > 已注册 LTE > 任意
        return nr.firstOrNull { it.isRegistered }
            ?: lte.firstOrNull { it.isRegistered }
            ?: candidates.firstOrNull { it.isRegistered }
            ?: candidates.firstOrNull()
    }

    /** 系统默认数据卡（上网卡）的 subId */
    private fun defaultDataSubId(): Int? = try {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
            subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
        ) null else subId
    } catch (_: Exception) {
        null
    }

    /** 小区自身的运营商文本字段（可能英文或错，仅兜底） */
    private fun cellOperator(cell: CellInfo): CharSequence? = when (cell) {
        is CellInfoNr -> (cell.cellIdentity as? CellIdentityNr)?.operatorAlphaLong
        is CellInfoLte -> cell.cellIdentity.operatorAlphaLong
        else -> null
    }

    /** TelephonyManager 的运营商名（最后兜底） */
    private fun operatorFromTm(tm: TelephonyManager): String? =
        tm.networkOperatorName
            ?.takeIf { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            ?.let { cleanOperator(it) }

    /** PLMN → 中文运营商名（按实际 PLMN 动态识别，覆盖中国大陆主要运营商多个 PLMN） */
    private fun operatorFromPlmn(plmn: String?): String? = when (plmn) {
        "46000", "46002", "46007", "46008" -> "中国移动"
        "46001", "46006", "46009" -> "中国联通"
        "46003", "46005", "46011", "46012", "46004" -> "中国电信"
        "46015" -> "中国广电"
        "46020" -> "中国铁通"
        else -> null
    }

    /** 只保留 5 位纯数字的 PLMN（如 "46011"） */
    private fun digitsPlmn(raw: String): String? =
        raw.takeIf { it.length >= 5 && it.all { c -> c.isDigit() } }?.substring(0, 5)

    /** 取 LTE 小区 PLMN（MCC+MNC）。高版本用字符串，低版本用数字补零 */
    @Suppress("DEPRECATION")
    private fun plmnOfLte(id: CellIdentityLte): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            id.mobileNetworkOperator?.takeIf { it.length >= 5 }?.let { return it }
            val mcc = id.mccString.orEmpty()
            val mnc = id.mncString.orEmpty()
            (mcc + mnc).takeIf { it.length >= 5 }?.let { return it }
        }
        val mcc = id.mcc
        val mnc = id.mnc
        return if (mcc > 0 && mcc != Int.MAX_VALUE && mnc >= 0 && mnc != Int.MAX_VALUE) {
            mcc.toString() + mnc.toString().padStart(2, '0')
        } else null
    }

    /** 清洗运营商名称：去掉末尾自带的 4G/5G/LTE/NR，纯数字 PLMN 视为无效 */
    private fun cleanOperator(raw: CharSequence?): String? {
        val s = raw?.toString()?.trim().orEmpty()
        if (s.isEmpty() || s.all { it.isDigit() }) return null
        return s.replace(Regex("""[\s　]*(4G|5G|LTE|NR)\s*$""", RegexOption.IGNORE_CASE), "").trim()
            .ifEmpty { null }
    }

}
