package com.wozai.app

/**
 * 日志脱敏：写日志时对地图 Key、手机号、联系人姓名打码。
 *
 * 为什么要做：日志文件可以「导出日志」另存到手机的下载目录，那里任何 App 都能读。
 * 老人把导出的 txt 发给别人排查问题时，完整 Key、号码和姓名就跟着散出去了，
 * 而排查定位问题其实只需要看前后几位。
 */
object Mask {

    /** 地图 Key：保留前 6 位与后 4 位，中间打码；空值显示「空」；太短则整段打码 */
    fun key(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "空"
        if (s.length <= 10) return "*".repeat(s.length)
        return s.take(6) + "*".repeat(s.length - 10) + s.takeLast(4)
    }

    /** 手机号：保留前 3 位与后 2 位，中间打码；空值显示「空」；太短则整段打码 */
    fun phone(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "空"
        if (s.length <= 5) return "*".repeat(s.length)
        return s.take(3) + "*".repeat(s.length - 5) + s.takeLast(2)
    }

    /** 联系人姓名：只保留第一个字，其余打码；空值显示「空」 */
    fun name(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "空"
        if (s.length == 1) return s
        return s.take(1) + "*".repeat(s.length - 1)
    }
}
