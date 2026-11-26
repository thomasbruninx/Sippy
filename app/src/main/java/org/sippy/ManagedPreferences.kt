package org.sippy

import android.content.SharedPreferences

/** Managed values overlay local preferences; writes cannot replace administrator-owned fields. */
internal class ManagedPreferences(private val local: SharedPreferences) : SharedPreferences by local {
    private fun values() = ManagedConfiguration.values
    private fun locked(key: String) = ManagedConfiguration.isManaged(key)
    override fun getAll(): Map<String, *> = local.all + values().filterKeys { it !in setOf("password", "ldapPassword") }
    override fun contains(key: String) = values().containsKey(key) || local.contains(key)
    override fun getString(key: String, defValue: String?): String? =
        if (values().containsKey(key)) values()[key] as String? else local.getString(key, defValue)
    override fun getBoolean(key: String, defValue: Boolean) = values()[key] as? Boolean ?: local.getBoolean(key, defValue)
    override fun getInt(key: String, defValue: Int) = values()[key] as? Int ?: local.getInt(key, defValue)
    override fun edit(): SharedPreferences.Editor = Editor(local.edit())
    private inner class Editor(private val delegate: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { if (!locked(key)) delegate.putString(key, value) }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { if (!locked(key)) delegate.putStringSet(key, values) }
        override fun putInt(key: String, value: Int) = apply { if (!locked(key)) delegate.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { if (!locked(key)) delegate.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = apply { if (!locked(key)) delegate.putFloat(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { if (!locked(key)) delegate.putBoolean(key, value) }
        override fun remove(key: String) = apply { if (!locked(key)) delegate.remove(key) }
        override fun clear() = apply { local.all.keys.filterNot(::locked).forEach(delegate::remove) }
        override fun commit() = delegate.commit()
        override fun apply() = delegate.apply()
    }
}
