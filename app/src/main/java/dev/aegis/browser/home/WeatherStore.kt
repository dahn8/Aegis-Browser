package dev.aegis.browser.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.aegis.browser.ui.AppLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** 逐 3 小时的一格预报 */
data class HourSlot(val time: String, val tempC: Int, val code: Int)

/** 一天的预报 */
data class DaySlot(val date: String, val maxC: Int, val minC: Int, val code: Int)

/** 一份完整的天气快照（风向存角度，显示时再按语言格式化） */
data class WeatherData(
    val city: String,
    val code: Int,
    val tempC: Int,
    val feelsLike: Int,
    val humidity: Int,
    val windDegree: Int,
    val windSpeed: Int,
    val uvIndex: String,
    val sunrise: String,
    val sunset: String,
    val days: List<DaySlot>,
    val hours: List<HourSlot>
)

/**
 * 主页天气：开关、手动城市与最近一次快照都落在 aegis_home_weather 里。
 * 快照只存精简后的字段，显示时的文案按当前界面语言生成，切语言不会留下英文残值。
 */
class WeatherStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("aegis_home_weather", Context.MODE_PRIVATE)

    /** 是否在主页左上角显示天气 */
    var shown: Boolean
        get() = prefs.getBoolean(KEY_SHOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOWN, value).apply()
        }

    /** 手动城市；留空表示按 IP 自动定位 */
    var city: String
        get() = prefs.getString(KEY_CITY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_CITY, value.trim()).apply()
        }

    fun hasData(): Boolean = prefs.getString(KEY_DATA, null) != null

    fun data(): WeatherData? {
        val raw = prefs.getString(KEY_DATA, null) ?: return null
        return try {
            decode(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun save(weather: WeatherData) {
        prefs.edit()
            .putString(KEY_DATA, encode(weather))
            .putLong(KEY_AT, System.currentTimeMillis())
            .remove(KEY_FAIL)
            .apply()
    }

    fun markFailed() {
        prefs.edit().putLong(KEY_FAIL, System.currentTimeMillis()).apply()
    }

    /** 主页左上角的一行字：多云 24° */
    fun summary(context: Context): String {
        val weather = data() ?: return ""
        return "${WeatherLabels.label(context, weather.code)} ${weather.tempC}°"
    }

    /** 30 分钟内不重复请求；失败后 10 分钟内不再重试。force 时忽略缓存直接刷新 */
    fun needsRefresh(force: Boolean = false): Boolean {
        if (force) return true
        val now = System.currentTimeMillis()
        val failedAt = prefs.getLong(KEY_FAIL, 0L)
        if (failedAt > 0L && now - failedAt < FAIL_BACKOFF) return false
        return !hasData() || now - prefs.getLong(KEY_AT, 0L) > CACHE_TTL
    }

    private fun encode(weather: WeatherData): String = JSONObject().apply {
        put("city", weather.city)
        put("code", weather.code)
        put("temp", weather.tempC)
        put("feels", weather.feelsLike)
        put("humidity", weather.humidity)
        put("windDegree", weather.windDegree)
        put("windSpeed", weather.windSpeed)
        put("uv", weather.uvIndex)
        put("sunrise", weather.sunrise)
        put("sunset", weather.sunset)
        put("days", JSONArray().apply {
            weather.days.forEach { day ->
                put(JSONObject().apply {
                    put("date", day.date)
                    put("max", day.maxC)
                    put("min", day.minC)
                    put("code", day.code)
                })
            }
        })
        put("hours", JSONArray().apply {
            weather.hours.forEach { slot ->
                put(JSONObject().apply {
                    put("time", slot.time)
                    put("temp", slot.tempC)
                    put("code", slot.code)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): WeatherData {
        val json = JSONObject(raw)
        val days = mutableListOf<DaySlot>()
        val daysArray = json.optJSONArray("days") ?: JSONArray()
        for (index in 0 until daysArray.length()) {
            val item = daysArray.getJSONObject(index)
            days.add(
                DaySlot(
                    date = item.optString("date"),
                    maxC = item.optInt("max"),
                    minC = item.optInt("min"),
                    code = item.optInt("code")
                )
            )
        }
        val hours = mutableListOf<HourSlot>()
        val hoursArray = json.optJSONArray("hours") ?: JSONArray()
        for (index in 0 until hoursArray.length()) {
            val item = hoursArray.getJSONObject(index)
            hours.add(
                HourSlot(
                    time = item.optString("time"),
                    tempC = item.optInt("temp"),
                    code = item.optInt("code")
                )
            )
        }
        return WeatherData(
            city = json.optString("city"),
            code = json.optInt("code", 113),
            tempC = json.optInt("temp"),
            feelsLike = json.optInt("feels"),
            humidity = json.optInt("humidity"),
            windDegree = json.optInt("windDegree"),
            windSpeed = json.optInt("windSpeed"),
            uvIndex = json.optString("uv"),
            sunrise = json.optString("sunrise"),
            sunset = json.optString("sunset"),
            days = days,
            hours = hours
        )
    }

    private companion object {
        const val KEY_SHOWN = "weather_shown"
        const val KEY_CITY = "weather_city"
        const val KEY_DATA = "weather_data"
        const val KEY_AT = "weather_at"
        const val KEY_FAIL = "weather_fail_at"
        const val CACHE_TTL = 30 * 60 * 1000L
        const val FAIL_BACKOFF = 10 * 60 * 1000L
    }
}

/** 天气文案：按当前界面语言给出中文或英文描述 */
object WeatherLabels {
    fun label(context: Context, code: Int): String {
        val en = AppLanguage.current(context) == AppLanguage.EN
        return when (code) {
            113 -> if (en) "Sunny" else "晴"
            116 -> if (en) "Partly cloudy" else "多云"
            119, 122 -> if (en) "Cloudy" else "阴"
            143, 248, 260 -> if (en) "Mist" else "雾"
            149 -> if (en) "Haze" else "霾"
            200, 386, 389, 392, 395 -> if (en) "Thunder" else "雷雨"
            179, 227, 230, 323, 326, 329, 332, 335, 338,
            362, 365, 368, 371, 374, 377 -> if (en) "Snow" else "雪"
            182, 185, 281, 284, 311, 314, 317, 320, 350 ->
                if (en) "Sleet" else "雨夹雪"
            176, 263, 266, 293, 296, 353, 356 ->
                if (en) "Light rain" else "小雨"
            299, 302, 305, 308, 359 -> if (en) "Rain" else "雨"
            else -> if (en) "Weather" else "天气"
        }
    }

    /** 方位角转成 8 方位的中文/英文风向 */
    fun wind(context: Context, degree: Int): String {
        val en = AppLanguage.current(context) == AppLanguage.EN
        val names = if (en) {
            listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        } else {
            listOf("北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风")
        }
        val index = ((((degree % 360) + 360) % 360 + 22) / 45) % 8
        return names[index]
    }
}

/**
 * 极简天气请求：wttr.in 的 j1 接口，按 IP 自动定位或用用户指定的城市，无需 key。
 * 实况、三天预报、逐 3 小时预报、日出日落都来自同一份响应。结果回调在主线程。
 */
object WeatherFetcher {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var busy = false

    fun refresh(context: Context, force: Boolean, onDone: Runnable) {
        if (busy) return
        busy = true
        val appContext = context.applicationContext
        val store = WeatherStore(appContext)
        Thread {
            val weather = request(store.city)
            if (weather == null) {
                store.markFailed()
            } else {
                store.save(weather)
            }
            mainHandler.post {
                busy = false
                onDone.run()
            }
        }.start()
    }

    private fun request(city: String): WeatherData? = try {
        val conn = URL(endpoint(city)).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", "curl/8")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        parse(body, city)
    } catch (_: Exception) {
        null
    }

    private fun endpoint(city: String): String {
        val normalized = city.trim()
        val target = if (normalized.isEmpty()) "" else "/" + URLEncoder.encode(normalized, "UTF-8")
        return "https://wttr.in$target?format=j1"
    }

    private fun parse(body: String, requestedCity: String): WeatherData? = try {
        val root = JSONObject(body)
        val current = root.getJSONArray("current_condition").getJSONObject(0)
        val tempC = current.optString("temp_C").toDoubleOrNull()?.let { Math.round(it).toInt() } ?: return null
        val code = current.optString("weatherCode").toIntOrNull() ?: 113
        val feels = current.optString("FeelsLikeC").toDoubleOrNull()?.let { Math.round(it).toInt() } ?: tempC
        val humidity = current.optString("humidity").toIntOrNull() ?: 0
        val windSpeed = current.optString("windspeedKmph").toIntOrNull() ?: 0
        val windDegree = current.optString("winddirDegree").toIntOrNull() ?: 0

        val area = root.optJSONArray("nearest_area")
        val city = if (requestedCity.isNotBlank()) {
            requestedCity.trim()
        } else {
            area?.optJSONObject(0)
                ?.optJSONArray("areaName")
                ?.optJSONObject(0)
                ?.optString("value")
                .orEmpty()
        }

        val weatherArray = root.optJSONArray("weather") ?: JSONArray()
        val days = mutableListOf<DaySlot>()
        val hours = mutableListOf<HourSlot>()
        var uvIndex = ""
        var sunrise = ""
        var sunset = ""
        for (index in 0 until weatherArray.length()) {
            val day = weatherArray.getJSONObject(index)
            if (index == 0) {
                uvIndex = day.optString("uvIndex")
                val astronomy = day.optJSONArray("astronomy")?.optJSONObject(0)
                sunrise = to24h(astronomy?.optString("sunrise").orEmpty())
                sunset = to24h(astronomy?.optString("sunset").orEmpty())
            }
            val hourly = day.optJSONArray("hourly") ?: JSONArray()
            var representative = code
            for (h in 0 until hourly.length()) {
                val slot = hourly.getJSONObject(h)
                if (h == 4) representative = slot.optString("weatherCode").toIntOrNull() ?: code
                if (index == 0) {
                    hours.add(
                        HourSlot(
                            time = formatTime(slot.optString("time")),
                            tempC = slot.optString("tempC").toIntOrNull() ?: tempC,
                            code = slot.optString("weatherCode").toIntOrNull() ?: code
                        )
                    )
                }
            }
            days.add(
                DaySlot(
                    date = day.optString("date"),
                    maxC = day.optString("maxtempC").toIntOrNull() ?: tempC,
                    minC = day.optString("mintempC").toIntOrNull() ?: tempC,
                    code = representative
                )
            )
        }

        WeatherData(
            city = city,
            code = code,
            tempC = tempC,
            feelsLike = feels,
            humidity = humidity,
            windDegree = windDegree,
            windSpeed = windSpeed,
            uvIndex = uvIndex,
            sunrise = sunrise,
            sunset = sunset,
            days = days,
            hours = hours
        )
    } catch (_: Exception) {
        null
    }

    /** wttr.in 的日出日落是 12 小时制（07:06 AM），统一转成 24 小时制 */
    private fun to24h(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return value
        val upper = value.uppercase(Locale.US)
        val isPm = upper.endsWith("PM")
        val isAm = upper.endsWith("AM")
        if (!isPm && !isAm) return value
        val clock = upper.removeSuffix("AM").removeSuffix("PM").trim()
        val parts = clock.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return value
        var hour24 = hour % 12
        if (isPm) hour24 += 12
        val minute = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { "00" }
        return hour24.toString().padStart(2, '0') + ":" + minute
    }

    private fun formatTime(raw: String): String {
        val value = raw.toIntOrNull() ?: return raw
        val hh = (value / 100).toString().padStart(2, '0')
        return "$hh:00"
    }
}
