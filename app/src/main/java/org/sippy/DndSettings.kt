package org.sippy

import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

internal enum class DndServerSync(val label: String) {
    DISABLED("Disabled"), PUBLISH("SIP PUBLISH (presence)"), FAC("Feature Access Codes (FAC)"), AS_FEATURE_EVENT("As-Feature-Event (SUBSCRIBE / NOTIFY)")
}
internal enum class DndFacMethod { INVITE, MESSAGE }
internal enum class DndFeatureSchema(val label: String, val namespace: String) {
    BROADSOFT("BroadSoft (CSTA ed3)", "http://www.ecma-international.org/standards/ecma-323/csta/ed3"),
    THREE_CX("3CX (CSTA ed5)", "http://www.ecma-international.org/standards/ecma-323/csta/ed5")
}
internal data class DndSettings(
    val show: Boolean = true,
    val reasonHeader: Boolean = false,
    val serverSync: DndServerSync = DndServerSync.DISABLED,
    val facOn: String = "*78",
    val facOff: String = "*79",
    val facMethod: DndFacMethod = DndFacMethod.INVITE,
    val featureSchema: DndFeatureSchema = DndFeatureSchema.BROADSOFT,
    val priorityException: Boolean = false,
) {
    fun declines(enabled: Boolean, priority: String?): Boolean = show && enabled &&
        !(priorityException && priority?.trim()?.equals("emergency", ignoreCase = true) == true)

    fun validate() {
        require(listOf(facOn, facOff).all { it.length <= 128 && it.none(Char::isISOControl) }) { "Enter valid DND feature codes." }
        if (show && serverSync == DndServerSync.FAC) require(dialTarget(facOn) != null && dialTarget(facOff) != null) { "Enter the DND enable and disable feature codes." }
    }
    fun json() = JSONObject().put("show", show).put("reasonHeader", reasonHeader).put("serverSync", serverSync.name)
        .put("facOn", facOn).put("facOff", facOff).put("facMethod", facMethod.name).put("featureSchema", featureSchema.name).put("priorityException", priorityException)
    companion object {
        val keys = setOf("dndShow", "dndReasonHeader", "dndServerSync", "dndFacOn", "dndFacOff", "dndFacMethod", "dndFeatureSchema", "dndPriorityException")
        fun decode(json: JSONObject?): DndSettings {
            if (json == null) return DndSettings()
            fun b(key: String, fallback: Boolean) = if (json.has(key)) json.get(key) as? Boolean ?: error("Invalid DND $key.") else fallback
            fun s(key: String, fallback: String) = if (json.has(key)) json.get(key) as? String ?: error("Invalid DND $key.") else fallback
            return DndSettings(b("show", true), b("reasonHeader", false), DndServerSync.valueOf(s("serverSync", "DISABLED")),
                s("facOn", "*78"), s("facOff", "*79"), DndFacMethod.valueOf(s("facMethod", "INVITE")),
                DndFeatureSchema.valueOf(s("featureSchema", "BROADSOFT")), b("priorityException", false)).also { it.validate() }
        }
    }
}

internal object DndXml {
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun presence(identity: String, busy: Boolean): String = """<?xml version="1.0" encoding="UTF-8"?>
<presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" entity="${escape(identity)}"><tuple id="sippy"><status><basic>open</basic></status><note>${if (busy) "Do Not Disturb" else "Available"}</note></tuple><dm:person id="sippy-person">${if (busy) "<rpid:activities><rpid:busy/></rpid:activities>" else ""}</dm:person></presence>"""
    fun setFeature(identity: String, busy: Boolean, schema: DndFeatureSchema) = """<?xml version="1.0" encoding="UTF-8"?><SetDoNotDisturb xmlns="${schema.namespace}"><device>${escape(identity)}</device><doNotDisturbOn>$busy</doNotDisturbOn></SetDoNotDisturb>"""
    fun featureEvent(identity: String, busy: Boolean) = """<?xml version="1.0" encoding="UTF-8"?><DoNotDisturbEvent xmlns="${DndFeatureSchema.THREE_CX.namespace}"><monitorCrossRefID>0</monitorCrossRefID><device><deviceIdentifier>${escape(identity)}</deviceIdentifier></device><doNotDisturbOn>$busy</doNotDisturbOn></DoNotDisturbEvent>"""
    fun featureState(body: String): Boolean? {
        if (body.length > 65536 || body.contains("<!DOCTYPE", true) || body.contains("<!ENTITY", true)) return null
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(body))
            var validRoot = false
            var rootNamespace = ""
            var result: Boolean? = null
            var found = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (parser.depth == 1) {
                        validRoot = parser.name == "DoNotDisturbEvent" && DndFeatureSchema.entries.any { it.namespace == parser.namespace }
                        if (!validRoot) return null
                        rootNamespace = parser.namespace
                    }
                    if (validRoot && parser.depth == 2 && parser.name == "doNotDisturbOn" && parser.namespace == rootNamespace) {
                        if (found) return null
                        found = true
                        result = when (parser.nextText().trim().lowercase(java.util.Locale.ROOT)) {
                            "true", "1" -> true
                            "false", "0" -> false
                            else -> return null
                        }
                    }
                }
                parser.next()
            }
            result
        }.getOrNull()
    }
}
