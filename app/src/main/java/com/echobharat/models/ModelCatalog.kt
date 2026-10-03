package com.echobharat.models

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Reads `assets/models/manifest.json` — the declarative description of every language
 * pack. Keeping this out of Kotlin means newly exported models can be published by
 * editing one JSON file.
 */
object ModelCatalog {

    private const val TAG = "ModelCatalog"
    private const val ASSET = "models/manifest.json"

    private data class Manifest(
        val languages: List<LanguageModelSpec>,
        val translation: List<TranslationFamilySpec>
    )

    @Volatile
    private var cached: Manifest? = null

    private fun manifest(context: Context): Manifest {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val parsed = try {
                parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.e(TAG, "MANIFEST_LOAD_FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
                Manifest(emptyList(), emptyList())
            }
            cached = parsed
            Log.i(
                TAG,
                "Loaded ${parsed.languages.size} language specs and " +
                    "${parsed.translation.size} translation families from manifest"
            )
            return parsed
        }
    }

    fun languages(context: Context): List<LanguageModelSpec> = manifest(context).languages

    fun translationFamilies(context: Context): List<TranslationFamilySpec> = manifest(context).translation

    fun translationFamily(context: Context, id: String): TranslationFamilySpec? =
        translationFamilies(context).firstOrNull { it.id == id }

    fun byLang(context: Context, lang: String): LanguageModelSpec? =
        languages(context).firstOrNull { it.lang == lang }

    private fun parseSpecs(arr: org.json.JSONArray?): List<ModelSpec> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { j ->
            val m = arr.getJSONObject(j)
            val role = runCatching { ModelRole.valueOf(m.getString("role")) }.getOrNull()
            if (role == null) {
                Log.w(TAG, "Skipping model with unknown role: ${m.optString("role")}")
                return@mapNotNull null
            }
            ModelSpec(
                role = role,
                fileName = m.getString("fileName"),
                url = m.optString("url", ""),
                mirrorUrl = m.optString("mirrorUrl", ""),
                sha256 = m.optString("sha256", "").lowercase(),
                sizeBytes = m.optLong("sizeBytes", 0L)
            )
        }
    }

    private fun parse(json: String): Manifest {
        val root = JSONObject(json)
        val arr = root.getJSONArray("languages")
        val languages = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LanguageModelSpec(
                lang = o.getString("lang"),
                displayName = o.getString("displayName"),
                nativeName = o.getString("nativeName"),
                bundled = o.optBoolean("bundled", false),
                models = parseSpecs(o.getJSONArray("models")),
                packVersion = o.optInt("packVersion", 1).coerceAtLeast(1),
                legacyModels = parseSpecs(o.optJSONArray("legacyModels"))
            )
        }

        val translation = root.optJSONObject("translation")
        val tags = translation?.optJSONObject("tags")
        val families = translation?.optJSONArray("families")
        val parsedFamilies = (0 until (families?.length() ?: 0)).map { i ->
            val f = families!!.getJSONObject(i)
            val id = f.getString("id")
            val tagJson = tags?.optJSONObject(id)
            TranslationFamilySpec(
                id = id,
                title = f.optString("title", id),
                files = parseSpecs(f.optJSONArray("files")),
                fast = parseSpecs(f.optJSONArray("fast")),
                tags = tagJson?.keys()?.asSequence()?.associateWith { tagJson.getInt(it) }.orEmpty()
            )
        }
        return Manifest(languages, parsedFamilies)
    }
}
