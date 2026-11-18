package io.github.yuxiximyxx.shadeclock.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 荣耀天气数据出口（做法参照开源项目 HonorDuo 的 WeatherManager，
 * 按行为重写）：锁屏天气插件的 ContentProvider
 * content://com.hihonor.android.weather/weatherInfo
 * 用 call("query_home_city_weather", city_type=10, dayAndNightWeather=1)
 * 返回 Bundle，键 key_home_city_weather 是按天预报的 JSON。
 * 无天气权限要求，SystemUI 身份可读。call 失败时兜底走同 URI 的
 * 表格 cursor（temperature/weather_text/weather_icon 列）。
 * 刷新触发：WEATHER_CHANGE 广播 + ContentObserver + 30 分钟缓存。
 *
 * mintemp/maxtemp/winddirDes 的确切 JSON 键名未被公开代码证实：
 * 本类在 day 对象里按候选键名 + 语义启发式尽力找；每进程首次拿到
 * JSON 时把原文（截断约 4KB）以 "weather json dump: " 打一行日志，
 * 供真机定案键名。找不到的键不进结果表，对应文字渲染为空。
 */
object WeatherSource {

    private val WEATHER_URI: Uri = Uri.parse("content://com.hihonor.android.weather/weatherInfo")
    private const val ACTION_WEATHER_CHANGE = "com.hihonor.android.action.WEATHER_CHANGE"
    private const val CACHE_MS = 30 * 60 * 1000L
    private const val EVENT_MIN_INTERVAL_MS = 10 * 1000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ShadeClock-Weather").apply { isDaemon = true }
    }

    @Volatile
    private var started = false

    @Volatile
    private var currentVars: Map<String, EVal> = emptyMap()

    @Volatile
    private var lastFetchMs = 0L

    @Volatile
    var version: Long = 0
        private set

    private val dumpDone = AtomicBoolean(false)
    private var logFn: ((String) -> Unit)? = null

    /** 当前天气变量快照（Weather.today.* 命名空间），无数据时为空表。 */
    fun vars(): Map<String, EVal> = currentVars

    fun start(context: Context, log: (String) -> Unit) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            logFn = log
        }
        val app = context.applicationContext ?: context
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    fetch(app, fromEvent = true)
                }
            }
            app.contentResolver.registerContentObserver(WEATHER_URI, true, observer)
        } catch (t: Throwable) {
            log("weather observer register failed: ${t.javaClass.name}: ${t.message}")
        }
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == ACTION_WEATHER_CHANGE &&
                        intent.getIntExtra("status", 0) != 1
                    ) {
                        return
                    }
                    fetch(app, fromEvent = true)
                }
            }
            app.registerReceiver(receiver, IntentFilter(ACTION_WEATHER_CHANGE), Context.RECEIVER_EXPORTED)
        } catch (t: Throwable) {
            log("weather receiver register failed: ${t.javaClass.name}: ${t.message}")
        }
        fetch(app, fromEvent = false)
    }

    /** 覆盖层每秒 tick 调用：超过缓存期才真正查询。 */
    fun maybeRefresh(context: Context) {
        if (!started) return
        if (System.currentTimeMillis() - lastFetchMs < CACHE_MS) return
        fetch(context.applicationContext ?: context, fromEvent = false)
    }

    private fun fetch(context: Context, fromEvent: Boolean) {
        val now = System.currentTimeMillis()
        if (fromEvent && now - lastFetchMs < EVENT_MIN_INTERVAL_MS) return
        lastFetchMs = now
        executor.execute {
            try {
                val result = queryFromCall(context) ?: queryFromCursor(context)
                if (result != null && result != currentVars) {
                    currentVars = result
                    version++
                    logFn?.invoke("weather updated: keys=${result.keys}")
                }
            } catch (t: Throwable) {
                logFn?.invoke("weather fetch failed: ${t.javaClass.name}: ${t.message}")
            }
        }
    }

    private fun queryFromCall(context: Context): Map<String, EVal>? {
        val cr = context.contentResolver ?: return null
        val extras = Bundle().apply {
            putInt("city_type", 10)
            putInt("dayAndNightWeather", 1)
        }
        val bundle = try {
            cr.call(WEATHER_URI, "query_home_city_weather", null, extras)
        } catch (t: Throwable) {
            logFn?.invoke("weather call failed: ${t.javaClass.name}: ${t.message}")
            return null
        } ?: return null
        val json = bundle.getString("key_home_city_weather")?.takeIf { it.isNotEmpty() } ?: return null
        if (dumpDone.compareAndSet(false, true)) {
            logFn?.invoke("weather json dump: " + json.take(4000))
        }
        return parseHomeCityJson(json)
    }

    private fun parseHomeCityJson(json: String): Map<String, EVal>? {
        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return null
        }
        val days = root.optJSONArray("weather") ?: return null
        var today: JSONObject? = null
        for (i in 0 until days.length()) {
            val item = days.optJSONObject(i) ?: continue
            if (today == null) today = item
            if (item.optInt("day_index", -1) == 1) {
                today = item
                break
            }
        }
        val day = today ?: return null
        val fahrenheit = root.optString("temp_unit").equals("F", true)
        fun tempOf(v: Double): Double =
            if (fahrenheit) (v - 32.0) * 5.0 / 9.0 else v

        val now = System.currentTimeMillis()
        val sunrise = day.optLong("sunrise_time", 0L)
        val sunset = day.optLong("sunset_time", 0L)
        val isNight = sunrise > 0L && sunset > 0L && (now < sunrise || now > sunset)
        val prefix = if (isNight) "night_" else ""

        val out = HashMap<String, EVal>()

        jsonDouble(day, "${prefix}current_temperature")?.let {
            out["Weather.today.currentTem"] = EVal.Num(tempOf(it).roundToInt().toDouble())
        }
        val icon = jsonInt(day, "${prefix}weather_icon")
        if (icon != null) out["Weather.today.weatherIcon"] = EVal.Num(icon.toDouble())
        val des = day.optString("${prefix}weather_native_des", "").trim()
        if (des.isNotEmpty()) out["Weather.today.weatherIconDes"] = EVal.Str(des)

        findTemperature(day, isMax = true)?.let {
            out["Weather.today.maxtemp"] = EVal.Num(tempOf(it).roundToInt().toDouble())
        }
        findTemperature(day, isMax = false)?.let {
            out["Weather.today.mintemp"] = EVal.Num(tempOf(it).roundToInt().toDouble())
        }
        findWindDirection(day)?.let {
            out["Weather.today.winddirDes"] = EVal.Str(it)
        }
        return if (out.isEmpty()) null else out
    }

    /** 在 day 对象里找最高/最低温：候选键名直查 → 语义启发式扫键。 */
    private fun findTemperature(day: JSONObject, isMax: Boolean): Double? {
        val exact = if (isMax) {
            listOf(
                "day_temperature", "max_temperature", "temperature_max",
                "day_max_temperature", "high_temperature", "maxtemp",
            )
        } else {
            listOf(
                "night_temperature", "min_temperature", "temperature_min",
                "night_min_temperature", "low_temperature", "mintemp",
            )
        }
        for (key in exact) {
            jsonDouble(day, key)?.let { return it }
        }
        val keys = day.keys()
        var dayNightFallback: Double? = null
        while (keys.hasNext()) {
            val key = keys.next()
            val lk = key.lowercase()
            if (!lk.contains("temp")) continue
            val v = jsonDouble(day, key) ?: continue
            if (isMax && lk.contains("max")) return v
            if (!isMax && lk.contains("min")) return v
            if (isMax && lk.contains("day") && !lk.contains("night")) dayNightFallback = v
            if (!isMax && lk.contains("night")) dayNightFallback = v
        }
        return dayNightFallback
    }

    /** 在 day 对象里找风向描述：候选键名直查 → 第一个含 wind 的字符串值。 */
    private fun findWindDirection(day: JSONObject): String? {
        val exact = listOf(
            "wind_direction_des", "wind_dir_des", "winddirection_des",
            "wind_native_des", "wind_direction", "wind_dir", "winddirDes",
            "day_wind_direction_des", "day_wind_direction",
        )
        for (key in exact) {
            val v = day.optString(key, "").trim()
            if (v.isNotEmpty() && v.toDoubleOrNull() == null) return v
        }
        val keys = day.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.lowercase().contains("wind")) continue
            val v = day.optString(key, "").trim()
            if (v.isNotEmpty() && v.toDoubleOrNull() == null) return v
        }
        return null
    }

    private fun jsonDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        if (!v.isNaN()) return v
        return obj.optString(key, "").trim().toDoubleOrNull()
    }

    private fun jsonInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optInt(key, Int.MIN_VALUE)
        if (v != Int.MIN_VALUE) return v
        return obj.optString(key, "").trim().toDoubleOrNull()?.toInt()
    }

    /** call 无结果时的兜底：同 URI 表格 cursor（只有当前温度/天气文字/图标）。 */
    private fun queryFromCursor(context: Context): Map<String, EVal>? {
        val cr = context.contentResolver ?: return null
        return try {
            cr.query(
                WEATHER_URI,
                arrayOf("temperature", "weather_text", "weather_icon"),
                null, null,
                "update_time DESC LIMIT 1",
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val out = HashMap<String, EVal>()
                val tempIdx = cursor.getColumnIndex("temperature")
                if (tempIdx >= 0 && !cursor.isNull(tempIdx)) {
                    var t = cursor.getFloat(tempIdx).toDouble()
                    if (t > 45.0) t = (t - 32.0) * 5.0 / 9.0
                    out["Weather.today.currentTem"] = EVal.Num(t.roundToInt().toDouble())
                }
                val textIdx = cursor.getColumnIndex("weather_text")
                if (textIdx >= 0 && !cursor.isNull(textIdx)) {
                    val s = cursor.getString(textIdx)?.trim()
                    if (!s.isNullOrEmpty()) out["Weather.today.weatherIconDes"] = EVal.Str(s)
                }
                val iconIdx = cursor.getColumnIndex("weather_icon")
                if (iconIdx >= 0 && !cursor.isNull(iconIdx)) {
                    out["Weather.today.weatherIcon"] = EVal.Num(cursor.getInt(iconIdx).toDouble())
                }
                if (out.isEmpty()) null else out
            }
        } catch (t: Throwable) {
            logFn?.invoke("weather cursor query failed: ${t.javaClass.name}: ${t.message}")
            null
        }
    }
}
