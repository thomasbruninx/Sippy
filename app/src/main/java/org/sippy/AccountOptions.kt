package org.sippy

import org.json.JSONArray
import org.json.JSONObject

internal data class AccountOptions(
    val codecs: List<Codec> = Preferences.defaultCodecs,
    val ringtoneUri: String? = null,
    val ringtoneName: String = "Default ringtone",
    val transferMode: TransferMode = TransferMode.BOTH,
) {
    fun json() = JSONObject().put("codecs", codecJson(codecs)).put("ringtoneUri", ringtoneUri ?: JSONObject.NULL)
        .put("ringtoneName", ringtoneName).put("transferMode", transferMode.name)
    companion object {
        val keys = setOf("codecs", "ringtoneUri", "ringtoneName", "transferMode")
        fun codecJson(codecs: List<Codec>) = JSONArray(codecs.map { JSONObject().put("id", it.id).put("enabled", it.enabled) })
        fun decodeCodecs(items: JSONArray): List<Codec> {
            val result = (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                val id = item.get("id") as? String ?: error("Invalid codec ID.")
                val enabled = item.get("enabled") as? Boolean ?: error("Invalid codec state.")
                (Preferences.defaultCodecs.firstOrNull { it.id == id } ?: error("Unknown audio codec.")).copy(enabled = enabled)
            }
            require(result.size == Preferences.defaultCodecs.size && result.map { it.id }.distinct().size == result.size && result.any { it.enabled }) { "Invalid codec list; keep at least one codec enabled." }
            return result
        }
        fun codecOrder(order: String): List<Codec> {
            val ids = order.split(',').map { it.trim().lowercase(java.util.Locale.ROOT) }
            require(ids.distinct().size == ids.size && ids.all { id -> Preferences.defaultCodecs.any { it.id == id } }) { "Invalid codec order." }
            return ids.map { id -> Preferences.defaultCodecs.first { it.id == id }.copy(enabled = true) } +
                Preferences.defaultCodecs.filter { it.id !in ids }.map { it.copy(enabled = false) }
        }
        fun decode(json: JSONObject?, fallback: AccountOptions = AccountOptions()): AccountOptions {
            if (json == null) return fallback
            val uri = if (!json.has("ringtoneUri")) fallback.ringtoneUri else if (json.isNull("ringtoneUri")) null else json.getString("ringtoneUri").ifEmpty { null }
            require(uri == null || runCatching { java.net.URI(uri).scheme in setOf("file", "content", "android.resource") }.getOrDefault(false)) { "Invalid ringtone URI." }
            val name = if (json.has("ringtoneName")) json.get("ringtoneName") as? String ?: error("Invalid ringtone name.") else fallback.ringtoneName
            require(name.length <= 256 && name.none(Char::isISOControl)) { "Invalid ringtone name." }
            return AccountOptions(if (json.has("codecs")) decodeCodecs(json.getJSONArray("codecs")) else fallback.codecs,
                uri, if (uri == null) "Default ringtone" else name,
                if (json.has("transferMode")) TransferMode.valueOf(json.getString("transferMode")) else fallback.transferMode)
        }
    }
}
