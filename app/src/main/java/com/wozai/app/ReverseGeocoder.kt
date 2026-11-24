package com.wozai.app

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 逆地理编码（经纬度 → 详细地址）。
 *
 * 解析顺序：
 * 1. 高德 Web 服务（主）—— 配置了 amap_web_key 就先走高德
 * 2. 腾讯位置服务 WebService API（备用）—— 高德失败/超时/额度用尽时自动切换
 * 3. 都失败 → 返回 null，由调用方回退到手机系统自带的 Geocoder
 *
 * 两家都使用 GCJ-02 坐标，所以 WGS-84 只需转换一次。
 *
 * Key 来源：App 内「设置」里填入并保存在本机（SettingsStore）；不填则该家不可用，
 * 只有系统自带的 Geocoder 兜底。不再使用编译进 APK 的默认 Key。
 */
object ReverseGeocoder {

    /**
     * 一次解析的结果：详细地址 + 地图地标名 + 地址来源。
     * 地标名用于分享链接显示实际地名（如小区/园区/POI），取不到时为空。
     * source 标明地址来自哪家："高德" / "腾讯"，两家都没给出地址时为空（由调用方走系统兜底）。
     */
    data class GeoResult(val address: String?, val placeName: String?, val source: String = "")

    /**
     * 单个来源的查询结果：成功时 address 有值；失败时 reason 写明原因
     * （未配置 Key / Key 无效 / 次数用完 / 网络失败 / 这个点没有地址数据），
     * 供「当前位置对比」直接显示，而不是只给一句「获取失败」。
     */
    data class SourceDetail(val address: String?, val reason: String = "")

    /**
     * 解析详细地址，全部失败时返回 null。
     * @return 格式化地址，如 "浙江省杭州市西湖区文三路100号"；失败返回 null
     */
    fun resolve(context: Context, lat: Double, lng: Double): String? =
        resolveFull(context, lat, lng).address

    /**
     * 一次请求同时取「详细地址 + 地标名」，避免原来地址一遍、地标名一遍串行多请求。
     *
     * 高德 extensions=all 的响应里既有 formatted_address，又有 aois/pois，
     * 所以一次请求就能拿到全部；失败再走腾讯 get_poi=1（同样一次拿全）。
     * 配合 httpGet 的 3 秒超时，最坏从原来 4 次×6s 降到 2 次×3s。
     *
     * @return GeoResult(address, placeName)，任一项失败为 null
     */
    fun resolveFull(context: Context, lat: Double, lng: Double): GeoResult {
        val (gcjLat, gcjLng) = GeoUtils.wgs84ToGcj02(lat, lng)

        // 优先高德：extensions=all 一次拿地址 + 地标
        val amapKey = SettingsStore.getAmapKey(context).trim()
        if (amapKey.isNotEmpty()) {
            val r = amapFull(amapKey, gcjLat, gcjLng)
            if (!r.address.isNullOrEmpty()) return r.copy(source = "高德")
            // 高德连通但只拿到地标名没拿到地址时，地标名也留着给分享链接用
            if (!r.placeName.isNullOrEmpty()) {
                val tencentKeyTmp = SettingsStore.getTencentKey(context).trim()
                val r2 = if (tencentKeyTmp.isNotEmpty()) tencentFull(tencentKeyTmp, gcjLat, gcjLng) else GeoResult(null, null)
                return GeoResult(r2.address, r.placeName, if (!r2.address.isNullOrEmpty()) "腾讯" else "")
            }
        }

        // 高德失败 → 腾讯备用：get_poi=1 一次拿地址 + 地标
        val tencentKey = SettingsStore.getTencentKey(context).trim()
        if (tencentKey.isNotEmpty()) {
            val r = tencentFull(tencentKey, gcjLat, gcjLng)
            if (!r.address.isNullOrEmpty() || !r.placeName.isNullOrEmpty()) {
                return r.copy(source = if (!r.address.isNullOrEmpty()) "腾讯" else "")
            }
        }

        return GeoResult(null, null)
    }

    /** 高德逆地理 extensions=all：一次请求同时解析 formatted_address 与 aois/pois 地标名 */
    private fun amapFull(key: String, lat: Double, lng: Double): GeoResult {
        val url = "https://restapi.amap.com/v3/geocode/regeo" +
                "?key=${enc(key)}" +
                "&location=$lng,$lat" +
                "&extensions=all&radius=800&batch=false"

        val body = httpGet(url) ?: return GeoResult(null, null)
        return try {
            val json = JSONObject(body)
            if (json.optString("status") != "1") return GeoResult(null, null)
            val regeo = json.optJSONObject("regeocode") ?: return GeoResult(null, null)
            val address = regeo.optString("formatted_address").trim()
                .takeIf { it.isNotEmpty() && !it.startsWith("[") }
            val placeName = firstPlaceName(regeo.optJSONArray("aois"))
                ?: firstPlaceName(regeo.optJSONArray("pois"))
            GeoResult(address, placeName)
        } catch (e: Exception) {
            GeoResult(null, null)
        }
    }

    /** 腾讯逆地理 get_poi=1：一次请求同时解析地址与 pois[0].title 地标名 */
    private fun tencentFull(key: String, lat: Double, lng: Double): GeoResult {
        val url = "https://apis.map.qq.com/ws/geocoder/v1/" +
                "?location=$lat,$lng" +
                "&key=${enc(key)}" +
                "&get_poi=1&poi_options=page_size=1"

        val body = httpGet(url) ?: return GeoResult(null, null)
        return try {
            val json = JSONObject(body)
            if (json.optInt("status", -1) != 0) return GeoResult(null, null)
            val result = json.optJSONObject("result") ?: return GeoResult(null, null)

            val formatted = result.optJSONObject("formatted_addresses")
            val standard = validAddress(formatted?.optString("standard_address"))
                ?: validAddress(result.optString("address"))
            val recommend = validAddress(formatted?.optString("recommend"))
            val address = when {
                standard != null && recommend != null -> mergeAddress(standard, recommend)
                standard != null -> standard
                recommend != null -> recommend
                else -> null
            }
            val placeName = result.optJSONArray("pois")?.optJSONObject(0)
                ?.optString("title")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("[") }
            GeoResult(address, placeName)
        } catch (e: Exception) {
            GeoResult(null, null)
        }
    }

    /**
     * 分别用高德、腾讯各解析一次，两家结果都返回（不做主备短路）。
     * 供「长按标题对比两家地址」使用。
     * @return Pair(高德地址, 腾讯地址)，失败的一方为 null
     */
    fun resolveBoth(context: Context, lat: Double, lng: Double): Pair<String?, String?> =
        resolveBothDetail(context, lat, lng).let { (a, t) -> a.address to t.address }

    /**
     * 同 [resolveBoth]，但失败时带上原因，给「当前位置对比」用：
     * 没配 Key、Key 无效、次数用完、网络不通都能各自说清楚，方便判断是设置问题还是信号问题。
     */
    fun resolveBothDetail(context: Context, lat: Double, lng: Double): Pair<SourceDetail, SourceDetail> {
        val (gcjLat, gcjLng) = GeoUtils.wgs84ToGcj02(lat, lng)
        val amapKey = SettingsStore.getAmapKey(context).trim()
        val tencentKey = SettingsStore.getTencentKey(context).trim()
        val amap = if (amapKey.isEmpty()) {
            SourceDetail(null, "未配置高德 Key")
        } else {
            amapReverseDetail(amapKey, gcjLat, gcjLng)
        }
        val tencent = if (tencentKey.isEmpty()) {
            SourceDetail(null, "未配置腾讯 Key")
        } else {
            tencentReverseDetail(tencentKey, gcjLat, gcjLng)
        }
        return amap to tencent
    }

    /** 高德逆地理：成功返回地址，失败返回具体原因 */
    @SuppressLint("SetTextI18n")
    private fun amapReverseDetail(key: String, lat: Double, lng: Double): SourceDetail {
        val url = "https://restapi.amap.com/v3/geocode/regeo" +
                "?key=${enc(key)}" +
                "&location=$lng,$lat" +
                "&extensions=base&radius=800&batch=false"

        val body = httpGet(url) ?: return SourceDetail(null, "网络请求失败，请检查手机网络")
        return try {
            val json = JSONObject(body)
            if (json.optString("status") != "1") {
                return SourceDetail(
                    null,
                    amapFailReason(json.optString("infocode"), json.optString("info"))
                )
            }
            val regeocode = json.optJSONObject("regeocode")
                ?: return SourceDetail(null, "高德没有返回地址数据")
            val address = regeocode.optString("formatted_address").trim()
            // 高德查不到时会返回空数组 []
            if (address.isEmpty() || address.startsWith("[")) {
                SourceDetail(null, "这个坐标点高德没有地址数据")
            } else {
                SourceDetail(address)
            }
        } catch (e: Exception) {
            SourceDetail(null, "高德返回的数据看不懂（解析失败）")
        }
    }

    /** 高德错误码 → 中文原因（只列常见几种，其余带上原始码，便于排查） */
    private fun amapFailReason(infocode: String, info: String): String = when (infocode) {
        "10001" -> "高德 Key 无效（填错或已被删除）"
        "10003" -> "高德 Key 今日调用次数已用完"
        "10004" -> "请求太频繁，请稍后再试"
        "10009" -> "高德 Key 类型不对（要「Web 服务」类型的 Key）"
        "10044" -> "高德 Key 今日调用量超出上限"
        "" -> "高德查询失败（未返回错误码）"
        else -> "高德查询失败（错误码 $infocode ${info.trim()}）".trim()
    }

    /**
     * 腾讯逆地理：成功返回地址，失败返回具体原因。
     * 注意：腾讯的 location 参数是「纬度,经度」，与高德的「经度,纬度」相反。
     */
    private fun tencentReverseDetail(key: String, lat: Double, lng: Double): SourceDetail {
        val url = "https://apis.map.qq.com/ws/geocoder/v1/" +
                "?location=$lat,$lng" +
                "&key=${enc(key)}" +
                "&get_poi=0"

        val body = httpGet(url) ?: return SourceDetail(null, "网络请求失败，请检查手机网络")
        return try {
            val json = JSONObject(body)
            val status = json.optInt("status", -1)
            // 腾讯：status == 0 表示成功
            if (status != 0) {
                return SourceDetail(
                    null,
                    tencentFailReason(status, json.optString("message"))
                )
            }
            val result = json.optJSONObject("result")
                ?: return SourceDetail(null, "腾讯没有返回地址数据")

            val formatted = result.optJSONObject("formatted_addresses")
            // 标准地址：含省市区街道，比 address 详细一级，作为拼接底座
            val standard = validAddress(formatted?.optString("standard_address"))
                ?: validAddress(result.optString("address"))
            // 推荐地址：可能带小区、院落等地标名，精度最高，但常省略省市
            val recommend = validAddress(formatted?.optString("recommend"))

            val address = when {
                standard != null && recommend != null -> mergeAddress(standard, recommend)
                standard != null -> standard
                recommend != null -> recommend
                else -> null
            }
            if (address == null) SourceDetail(null, "这个坐标点腾讯没有地址数据")
            else SourceDetail(address)
        } catch (e: Exception) {
            SourceDetail(null, "腾讯返回的数据看不懂（解析失败）")
        }
    }

    /** 腾讯错误码 → 中文原因 */
    private fun tencentFailReason(status: Int, message: String): String {
        val msg = message.trim()
        return when (status) {
            111, 112 -> "腾讯 Key 未获授权（签名或 IP 校验没通过）"
            120 -> "腾讯 Key 今日调用次数已用完"
            121 -> "腾讯 Key 没开通 WebService 服务"
            122 -> "腾讯 Key 已过期"
            123, 199 -> "腾讯 Key 无效或未激活"
            300, 306, 310, 311 -> "腾讯 Key 或请求参数有误"
            350 -> "腾讯 Key 今日调用量超出上限"
            351 -> "这个坐标点腾讯没有地址数据"
            -1 -> "腾讯查询失败（返回内容异常）"
            else -> "腾讯查询失败（错误码 $status ${msg}）".trim()
        }
    }

    /** 腾讯地址字段有效值：非空，且不是查无结果时的 "[]" */
    private fun validAddress(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        return s.takeIf { it.isNotEmpty() && !it.startsWith("[") }
    }

    /**
     * 把推荐地址接在标准地址后面，并去掉重复的地名。
     *
     * 推荐地址一般是「行政区 + POI」结构，而行政区可能已经出现在标准地址中间，
     * 例如标准「上海市黄浦区人民大道200号」+ 推荐「黄浦区上海市人民政府」，
     * 若直接相加会出现两个「黄浦区」。因此按「以行政区/道路后缀结尾、
     * 且标准地址里已包含」的最长前缀剥离，再拼接。
     */
    private fun mergeAddress(standard: String, recommend: String): String {
        if (recommend == standard) return standard
        if (recommend.startsWith(standard)) return recommend   // 推荐地址已自带完整标准地址
        if (standard.startsWith(recommend)) return standard    // 推荐地址更短，标准地址更全

        // 从长到短找推荐地址开头那段已在标准地址里出现过的地名
        for (n in recommend.length downTo 2) {
            val head = recommend.substring(0, n)
            if (!isPlaceName(head)) continue                    // 必须是完整地名，避免把「南京西路」切成「西路」
            if (standard.contains(head)) return standard + recommend.substring(n)
        }
        // 没有重复地名：说明推荐地址是另一处地标，直接接在后面
        return standard + recommend
    }

    /** 是否以行政区划或道路后缀结尾，用来确认切出来的是完整地名 */
    private fun isPlaceName(text: String): Boolean = PLACE_TAILS.any { text.endsWith(it) }

    private val PLACE_TAILS = listOf(
        "街道", "自治区", "自治州", "自治县", "大道", "路", "街",
        "区", "县", "市", "镇", "乡", "村", "巷", "弄"
    )

    /** 从 aois/pois 数组里取第一个有效名称（跳过空值和 []） */
    private fun firstPlaceName(arr: JSONArray?): String? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name")?.trim()
            if (!name.isNullOrEmpty() && !name.startsWith("[")) return name
        }
        return null
    }

    /** 从关键词里认出的城市 + 去掉城市前缀后的真正搜索词 */
    data class CityQuery(val city: String, val keyword: String)

    /**
     * 认关键词里第一个明确写出的「××市」，只认带「市」字的写法。
     * 这样「成都市南充」→ 成都市 + 南充，「南充市成都」→ 南充市 + 成都，
     * 不会因为后面又出现一个城市名就被带跑；「北京朝阳区」这类没写「市」的
     * 不认，交给调用方走「当前城市优先」的老逻辑。
     * 城市名里不允许含「省」，否则「四川省成都市」会被整串当成一个城市。
     */
    fun parseCity(keyword: String): CityQuery? {
        val kw = keyword.trim()
        val m = Regex("(?:(?!省)[\\u4e00-\\u9fa5]){2,8}市").find(kw) ?: return null
        val city = m.value
        val rest = kw.substring(m.range.endInclusive + 1).trim()
        return CityQuery(city, rest.ifBlank { city })
    }

    /** POI 搜索候选：名称 + 详细地址 + WGS-84 坐标 */
    data class PoiResult(val name: String, val address: String, val lat: Double, val lng: Double)

    /**
     * POI 搜索：按小区/地点名称找候选（供"按地址设定坐标"用）。
     * 用 POI 搜索而非地理编码 geocode/geo——后者不认小区名、会降级到"道路/乡镇"中心，
     * 导致偏差到隔壁。POI 搜索按名称返回真实地点，候选交由用户确认，避免匹配错。
     * 高德主、腾讯备；两家返回的都是 GCJ-02，这里转回 WGS-84 存储。
     * @param city 城市名；为空按全国搜（或由 API 自行排序）
     * @param strict 用户明确写了「××市」时为 true：高德按城市严格限定，避免模糊拆词搜到外地
     * @return 候选列表（最多 8 个），全部失败返回空列表
     */
    fun searchPoi(
        context: Context, keyword: String, city: String = "", strict: Boolean = false
    ): List<PoiResult> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val c = city.trim()

        // 优先高德
        val amapKey = SettingsStore.getAmapKey(context).trim()
        if (amapKey.isNotEmpty()) {
            val list = searchPoiAmap(amapKey, kw, c, strict)
            if (list.isNotEmpty()) return list
        }

        // 高德失败 → 腾讯备用
        val tencentKey = SettingsStore.getTencentKey(context).trim()
        if (tencentKey.isNotEmpty()) {
            val list = searchPoiTencent(tencentKey, kw, c)
            if (list.isNotEmpty()) return list
        }

        return emptyList()
    }

    /**
     * 取坐标所在的城市名，用于「地址搜索没写城市时优先本市」。
     * 高德 → 腾讯，直辖市 city 为空时退回 province；都拿不到返回空串（调用方按全国搜）。
     * 结果按坐标 2 位小数（约 1km）缓存，同一小区内连续搜索不重复请求。
     */
    fun cityName(context: Context, lat: Double, lng: Double): String {
        val key = String.format(java.util.Locale.US, "%.2f,%.2f", lat, lng)
        if (key == cityCacheKey) return cityCacheValue
        val (gcjLat, gcjLng) = GeoUtils.wgs84ToGcj02(lat, lng)
        var city = ""
        val amapKey = SettingsStore.getAmapKey(context).trim()
        if (amapKey.isNotEmpty()) city = amapCity(amapKey, gcjLat, gcjLng)
        if (city.isEmpty()) {
            val tencentKey = SettingsStore.getTencentKey(context).trim()
            if (tencentKey.isNotEmpty()) city = tencentCity(tencentKey, gcjLat, gcjLng)
        }
        cityCacheKey = key
        cityCacheValue = city
        return city
    }

    /** 城市名缓存：同一个坐标格内不重复请求 */
    private var cityCacheKey = ""
    private var cityCacheValue = ""

    /** 高德 regeo（extensions=base）：addressComponent.city，直辖市为空时用 province */
    private fun amapCity(key: String, lat: Double, lng: Double): String {
        val url = "https://restapi.amap.com/v3/geocode/regeo" +
                "?key=${enc(key)}&location=$lng,$lat&extensions=base"
        val body = httpGet(url) ?: return ""
        return try {
            val json = JSONObject(body)
            if (json.optString("status") != "1") return ""
            val ac = json.optJSONObject("regeocode")
                ?.optJSONObject("addressComponent") ?: return ""
            listOf("city", "province")
                .map { ac.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("[") }
                .orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    /** 腾讯 geocoder：result.address_component.city */
    private fun tencentCity(key: String, lat: Double, lng: Double): String {
        val url = "https://apis.map.qq.com/ws/geocoder/v1/" +
                "?location=$lat,$lng&key=${enc(key)}"
        val body = httpGet(url) ?: return ""
        return try {
            val json = JSONObject(body)
            if (json.optInt("status", -1) != 0) return ""
            val ac = json.optJSONObject("result")
                ?.optJSONObject("address_component") ?: return ""
            listOf("city", "province")
                .map { ac.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("[") }
                .orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    /** 高德 POI 搜索 place/text：返回名称/地址/GCJ-02 坐标 → 转 WGS-84 */
    private fun searchPoiAmap(key: String, kw: String, city: String, strict: Boolean): List<PoiResult> {
        // 用户写了「××市」→ citylimit=true 严格限定在这个城市；
        // 只靠当前坐标推断 → false 仅优先本市，外地同名地点仍能搜到
        val scope = if (city.isNotBlank()) "&city=${enc(city)}&citylimit=$strict" else ""
        val url = "https://restapi.amap.com/v3/place/text" +
                "?key=${enc(key)}&keywords=${enc(kw)}&offset=8&page=1$scope"
        val body = httpGet(url) ?: return emptyList()
        return try {
            val json = JSONObject(body)
            if (json.optString("status") != "1") return emptyList()
            val pois = json.optJSONArray("pois") ?: return emptyList()
            val out = mutableListOf<PoiResult>()
            for (i in 0 until pois.length()) {
                val p = pois.optJSONObject(i) ?: continue
                val name = p.optString("name")
                if (name.isEmpty()) continue
                val parts = p.optString("location").split(",")
                if (parts.size != 2) continue
                val gcjLng = parts[0].toDoubleOrNull() ?: continue
                val gcjLat = parts[1].toDoubleOrNull() ?: continue
                val (wgsLat, wgsLng) = GeoUtils.gcj02ToWgs84(gcjLat, gcjLng)
                // 地址 = 省市区 + 详细地址，让用户看清是哪个地点
                val address = listOf(
                    p.optString("cityname"), p.optString("adname"), p.optString("address")
                ).filter { it.isNotBlank() }.distinct().joinToString(" ")
                out.add(PoiResult(name, address, wgsLat, wgsLng))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 腾讯 POI 搜索 place/search：返回标题/地址/GCJ-02 坐标 → 转 WGS-84 */
    private fun searchPoiTencent(key: String, kw: String, city: String): List<PoiResult> {
        // 同高德：有关键词里没写城市，就把搜索范围优先限定在当前城市
        val region = if (city.isNotBlank()) enc(city) else "%E5%85%A8%E5%9B%BD"
        val url = "https://apis.map.qq.com/ws/place/v1/search" +
                "?keyword=${enc(kw)}&key=${enc(key)}&page_size=10&page_index=1" +
                "&boundary=region($region,0)"
        val body = httpGet(url) ?: return emptyList()
        return try {
            val json = JSONObject(body)
            if (json.optInt("status", -1) != 0) return emptyList()
            val data = json.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<PoiResult>()
            for (i in 0 until data.length()) {
                val d = data.optJSONObject(i) ?: continue
                val name = d.optString("title")
                if (name.isEmpty()) continue
                val loc = d.optJSONObject("location") ?: continue
                val gcjLat = loc.optDouble("lat", Double.NaN)
                val gcjLng = loc.optDouble("lng", Double.NaN)
                if (gcjLat.isNaN() || gcjLng.isNaN()) continue
                val (wgsLat, wgsLng) = GeoUtils.gcj02ToWgs84(gcjLat, gcjLng)
                out.add(PoiResult(name, d.optString("address"), wgsLat, wgsLng))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 校验地图 Key 是否可用：拿一个固定坐标（北京，GCJ-02）真实请求一次对应服务，
     * 按返回码判断 Key 是否有效。仅用于保存设置时探测，不影响正常定位。
     *
     * @param provider "amap" 或 "tencent"
     * @return 检测结果：是否可用 + 给用户的中文说明
     */
    fun checkKey(provider: String, key: String): KeyCheck {
        if (key.isBlank()) return KeyCheck(true, "未填写（该服务不启用）")
        return when (provider) {
            "amap" -> checkAmapKey(key)
            "tencent" -> checkTencentKey(key)
            else -> KeyCheck(false, "未知服务")
        }
    }

    data class KeyCheck(val ok: Boolean, val message: String)

    /** 高德 Key 校验：status=1 即可用；否则把 info 错误码翻成中文提示 */
    private fun checkAmapKey(key: String): KeyCheck {
        // 北京天安门 GCJ-02 坐标，仅作探测，与用户位置无关
        val url = "https://restapi.amap.com/v3/geocode/regeo" +
                "?key=${enc(key)}&location=116.397428,39.90923&extensions=base"
        val body = httpGet(url) ?: return KeyCheck(false, "无法连接高德服务器（网络异常或超时）")
        return try {
            val json = JSONObject(body)
            if (json.optString("status") == "1") {
                KeyCheck(true, "高德 Key 可用")
            } else {
                val info = json.optString("info").ifBlank { "未知错误" }
                val detail = when {
                    info.contains("INVALID_USER_KEY", true) -> "Key 无效或不存在"
                    info.contains("PLAT_NOMATCH", true) -> "Key 平台类型不匹配（需选「Web服务」类型）"
                    info.contains("SERVICE_NOT_AVAILABLE", true) -> "服务未开通或 Key 类型错误"
                    info.contains("DAILY_LIMIT", true) -> "当日调用量已超限"
                    else -> info
                }
                KeyCheck(false, "高德 Key 不可用：$detail（$info）")
            }
        } catch (e: Exception) {
            KeyCheck(false, "高德返回数据异常，请稍后重试")
        }
    }

    /** 腾讯 Key 校验：status=0 即可用；否则把错误码/消息翻成中文提示 */
    private fun checkTencentKey(key: String): KeyCheck {
        // 北京天安门 GCJ-02 坐标；注意腾讯 location 是「纬度,经度」顺序
        val url = "https://apis.map.qq.com/ws/geocoder/v1/" +
                "?location=39.90923,116.397428&key=${enc(key)}&get_poi=0"
        val body = httpGet(url) ?: return KeyCheck(false, "无法连接腾讯服务器（网络异常或超时）")
        return try {
            val json = JSONObject(body)
            val status = json.optInt("status", -1)
            if (status == 0) {
                KeyCheck(true, "腾讯 Key 可用")
            } else {
                val msg = json.optString("message").ifBlank { "未知错误" }
                val detail = when (status) {
                    100 -> "参数错误或 Key 无效"
                    101 -> "Key 已被禁用"
                    102, 103 -> "Key 未开通 WebServiceAPI 服务"
                    110 -> "请求来源未授权（检查域名/IP 白名单）"
                    112 -> "Key 未开通此服务"
                    else -> msg
                }
                KeyCheck(false, "腾讯 Key 不可用：$detail（$msg）")
            }
        } catch (e: Exception) {
            KeyCheck(false, "腾讯返回数据异常，请稍后重试")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                // 3 秒超时：弱网下快速失败/切换下一家，避免地址长时间卡在「正在获取地址…」
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
