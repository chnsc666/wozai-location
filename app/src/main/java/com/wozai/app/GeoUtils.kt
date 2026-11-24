package com.wozai.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换工具。
 * - 手机 GPS 返回 WGS-84（原始坐标）
 * - 高德 使用 GCJ-02（火星坐标），本工程仅使用高德，调用前自动转换
 */
object GeoUtils {

    private const val PI = 3.1415926535897932384626
    private const val AXIS = 6378245.0            // 长半轴
    private const val EE = 0.00669342162296594323 // 偏心率平方

    /** WGS-84 → GCJ-02（高德可直接使用） */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng

        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)

        dLat = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (AXIS / sqrtMagic * cos(radLat) * PI)

        return (lat + dLat) to (lng + dLng)
    }

    /** GCJ-02 → WGS-84（高德地理编码返回的是 GCJ-02，存本地前转回原始坐标） */
    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return lat to lng
        // 用迭代反解：先拿 GCJ 坐标当近似 WGS，反复修正直到收敛
        var wgsLat = lat
        var wgsLng = lng
        repeat(5) {
            val (gLat, gLng) = wgs84ToGcj02(wgsLat, wgsLng)
            wgsLat += lat - gLat
            wgsLng += lng - gLng
        }
        return wgsLat to wgsLng
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
