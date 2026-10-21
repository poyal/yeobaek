package com.poyal.yeobaek.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Remove the retired field once, without rewriting unrelated or newer settings. */
internal class RemoveFolderFavorites : DataMigration<Preferences> {
    private val key = stringPreferencesKey("settings")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[key]?.let { "favorites" in Json.parseToJsonElement(it).jsonObject } ?: false

    override suspend fun migrate(currentData: Preferences): Preferences {
        val value = currentData[key] ?: return currentData
        val settings = Json.parseToJsonElement(value).jsonObject
        return currentData.toMutablePreferences().apply {
            this[key] = JsonObject(settings - "favorites").toString()
        }
    }

    override suspend fun cleanUp() = Unit
}
