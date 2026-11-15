package com.meapet.mobile.tts.text

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 日语汉字改写映射（由 JMdict 常用词生成，见 docs/tts/architecture.md）。
 *
 * - [word] 词形 → 假名读音（≥2 字，最长匹配用，上限 8 字）
 * - [charFull] 单字 → 整字读音（私は→わたし、何も→なに）
 * - [charStem] 单字 → 动词/形容词词干读音（知らない→し、行こう→い）
 */
data class JaReadingMap(
    val word: Map<String, String>,
    val charFull: Map<String, String>,
    val charStem: Map<String, String>,
) {
    companion object {
        val EMPTY = JaReadingMap(emptyMap(), emptyMap(), emptyMap())
    }
}

@Serializable
internal data class JaReadingMapDto(
    val word: Map<String, String> = emptyMap(),
    @SerialName("char_full") val charFull: Map<String, String> = emptyMap(),
    @SerialName("char_stem") val charStem: Map<String, String> = emptyMap(),
)

internal fun JaReadingMapDto.toMap() = JaReadingMap(word, charFull, charStem)

/** 从 APK 资产读取日语映射（首次调用约几十~几百毫秒，调用方必须在 IO 线程）。 */
object JaReadingMapLoader {
    private const val ASSET = "tts/ja_reading_map.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): JaReadingMap =
        context.assets.open(ASSET).bufferedReader().use { reader ->
            json.decodeFromString(JaReadingMapDto.serializer(), reader.readText()).toMap()
        }
}
