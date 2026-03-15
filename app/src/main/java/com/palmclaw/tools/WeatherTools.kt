package com.palmclaw.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

fun createWeatherToolSet(client: OkHttpClient): List<Tool> {
    return listOf(WeatherGetTool(client))
}

private class WeatherGetTool(
    private val client: OkHttpClient
) : Tool {
    override val name: String = "weather"
    override val description: String =
        "Get current weather and short forecast by location (no API key). Uses wttr.in, falls back to Open-Meteo."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"location\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "location":{"type":"string","description":"City/region/airport code, e.g. London, New York, JFK"},
                  "mode":{"type":"string","enum":["current","compact","full"]},
                  "unit":{"type":"string","enum":["metric","us"]},
                  "forecastDays":{"type":"integer","description":"1-7 days, mainly used in fallback"}
                }
                """.trimIndent()
            )
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        val location = args.location.trim()
        if (location.isBlank()) {
            return@withContext error("weather failed: location is empty")
        }

        val mode = (args.mode ?: "current").lowercase(Locale.US)
        if (mode !in MODE_ALLOWED) {
            return@withContext error("weather failed: mode must be one of current|compact|full")
        }
        val unit = (args.unit ?: "metric").lowercase(Locale.US)
        if (unit !in UNIT_ALLOWED) {
            return@withContext error("weather failed: unit must be metric or us")
        }
        val forecastDays = (args.forecastDays ?: if (mode == "full") 3 else 1).coerceIn(1, 7)

        val wttrResult = runCatching {
            fetchWttr(location = location, mode = mode, unit = unit)
        }
        if (wttrResult.isSuccess) {
            return@withContext wttrResult.getOrThrow()
        }

        val wttrError = wttrResult.exceptionOrNull()?.message ?: "unknown wttr error"
        val fallback = runCatching {
            fetchOpenMeteo(
                location = location,
                unit = unit,
                forecastDays = forecastDays,
                fallbackReason = wttrError
            )
        }.getOrElse { t ->
            return@withContext error(
                "weather failed: wttr.in=$wttrError; open-meteo=${t.message ?: t.javaClass.simpleName}"
            )
        }
        fallback
    }

    private fun fetchWttr(
        location: String,
        mode: String,
        unit: String
    ): ToolResult {
        val encodedLocation = URLEncoder.encode(location, "UTF-8")
        val unitFlag = if (unit == "us") "u" else "m"
        val url = when (mode) {
            "compact" -> "https://wttr.in/$encodedLocation?format=%25l:+%25c+%25t+%25h+%25w&$unitFlag"
            "full" -> "https://wttr.in/$encodedLocation?T&$unitFlag&1"
            else -> "https://wttr.in/$encodedLocation?format=3&$unitFlag"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val text = body.stripAnsi().normalizeWeatherText()
            if (text.isBlank()) throw IOException("empty response")
            if (looksLikeHtml(text)) throw IOException("unexpected html response")

            val content = if (text.length <= MAX_WTTR_CHARS) text else {
                text.take(MAX_WTTR_CHARS) + "\n...[truncated]"
            }
            return ToolResult(
                toolCallId = "",
                content = "source=wttr.in\nmode=$mode\nunit=$unit\n\n$content",
                isError = false,
                metadata = buildJsonObject {
                    put("source", "wttr.in")
                    put("mode", mode)
                    put("unit", unit)
                }
            )
        }
    }

    private fun fetchOpenMeteo(
        location: String,
        unit: String,
        forecastDays: Int,
        fallbackReason: String
    ): ToolResult {
        val encodedLocation = URLEncoder.encode(location, "UTF-8")
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedLocation&count=1&language=en&format=json"
        val geoRequest = Request.Builder()
            .url(geoUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val geoObj = client.newCall(geoRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("geocoding HTTP ${response.code}")
            }
            json.parseToJsonElement(body).jsonObject
        }
        val firstResult = geoObj["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IOException("location not found")

        val latitude = firstResult.double("latitude") ?: throw IOException("missing latitude")
        val longitude = firstResult.double("longitude") ?: throw IOException("missing longitude")
        val resolvedName = firstResult.string("name") ?: location
        val country = firstResult.string("country").orEmpty()

        val unitParams = if (unit == "us") {
            "&temperature_unit=fahrenheit&windspeed_unit=mph&precipitation_unit=inch"
        } else {
            ""
        }
        val dailyParams = "&daily=weathercode,temperature_2m_max,temperature_2m_min"
        val forecastUrl = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=$latitude&longitude=$longitude")
            append("&current_weather=true")
            append("&timezone=auto")
            append("&forecast_days=$forecastDays")
            append(dailyParams)
            append(unitParams)
        }
        val forecastReq = Request.Builder()
            .url(forecastUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val forecastObj = client.newCall(forecastReq).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("forecast HTTP ${response.code}")
            }
            json.parseToJsonElement(body).jsonObject
        }

        val current = forecastObj["current_weather"]?.jsonObject
            ?: throw IOException("missing current_weather")
        val temp = current.double("temperature")
        val wind = current.double("windspeed")
        val code = current.int("weathercode")
        val time = current.string("time")

        val tempUnit = if (unit == "us") "F" else "C"
        val windUnit = if (unit == "us") "mph" else "km/h"

        val dailyObj = forecastObj["daily"]?.jsonObject
        val dates = dailyObj?.get("time")?.jsonArray.orEmpty()
        val dailyCodes = dailyObj?.get("weathercode")?.jsonArray.orEmpty()
        val maxTemps = dailyObj?.get("temperature_2m_max")?.jsonArray.orEmpty()
        val minTemps = dailyObj?.get("temperature_2m_min")?.jsonArray.orEmpty()

        val forecastLines = mutableListOf<String>()
        val n = minOf(dates.size, dailyCodes.size, maxTemps.size, minTemps.size, forecastDays)
        for (i in 0 until n) {
            val date = dates[i].jsonPrimitive.contentOrNull ?: continue
            val dailyCode = dailyCodes[i].jsonPrimitive.intOrNull
            val max = maxTemps[i].jsonPrimitive.doubleOrNull
            val min = minTemps[i].jsonPrimitive.doubleOrNull
            val line = buildString {
                append(date)
                append(": ")
                append(dailyCode?.let(::weatherCodeText) ?: "Unknown")
                if (min != null && max != null) {
                    append(", ")
                    append(formatTemp(min))
                    append("~")
                    append(formatTemp(max))
                    append(tempUnit)
                }
            }
            forecastLines += line
        }

        val content = buildString {
            appendLine("source=open-meteo (fallback)")
            appendLine("fallback_reason=$fallbackReason")
            appendLine("location=$resolvedName${if (country.isBlank()) "" else ", $country"}")
            appendLine("unit=$unit")
            appendLine()
            appendLine(
                "current=" + listOfNotNull(
                    code?.let(::weatherCodeText),
                    temp?.let { "${formatTemp(it)}$tempUnit" },
                    wind?.let { "wind ${formatTemp(it)}$windUnit" }
                ).joinToString(", ")
            )
            if (!time.isNullOrBlank()) {
                appendLine("time=$time")
            }
            if (forecastLines.isNotEmpty()) {
                appendLine()
                appendLine("forecast:")
                forecastLines.forEach { appendLine("- $it") }
            }
        }.trim()

        return ToolResult(
            toolCallId = "",
            content = content,
            isError = false,
            metadata = buildJsonObject {
                put("source", "open-meteo")
                put("latitude", latitude)
                put("longitude", longitude)
                put("forecast_days", forecastDays)
            }
        )
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.double(key: String): Double? {
        return this[key]?.jsonPrimitive?.doubleOrNull
    }

    private fun JsonObject.int(key: String): Int? {
        return this[key]?.jsonPrimitive?.intOrNull
    }

    private fun weatherCodeText(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Rain showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Weather code $code"
        }
    }

    private fun formatTemp(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
    }

    private fun String.stripAnsi(): String {
        return replace(Regex("\u001B\\[[;\\d]*m"), "")
    }

    private fun String.normalizeWeatherText(): String {
        return replace("\r", "\n")
            .replace(Regex("\u0007"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(200).lowercase(Locale.US)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun error(message: String): ToolResult {
        return ToolResult(
            toolCallId = "",
            content = message,
            isError = true
        )
    }

    @Serializable
    private data class Args(
        val location: String,
        val mode: String? = null,
        val unit: String? = null,
        val forecastDays: Int? = null
    )

    companion object {
        private val MODE_ALLOWED = setOf("current", "compact", "full")
        private val UNIT_ALLOWED = setOf("metric", "us")
        private const val USER_AGENT = "palmclaw/1.0 (+android)"
        private const val MAX_WTTR_CHARS = 14_000
    }
}
