package com.itantra.app.modelhub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One language pack entry from the remote catalogue (or the built-in
 * fallback copy of it).
 */
data class CatalogueLanguage(
    val languageTag: String,
    val name: String,
    val script: String,
    val iso: String,
    val archive: String,
    val sizeBytes: Long,
    val sizeMb: Double,
    val sha256: String,
    val tested: Boolean,
    val published: Boolean
)

/**
 * Dedicated catalogue entry for the cross-lingual Neural Machine Translation (NMT) engine.
 */
data class CatalogueTranslationModel(
    val id: String = "nmt-hi-en",
    val name: String = "Google ML Kit Neural NMT (hi ↔ en)",
    val description: String = "Google ML Kit On-Device Neural Machine Translation (Hindi <-> English)",
    val archive: String = "nmt-hi-en-1.0.0.itantra",
    val sizeBytes: Long = 50_855_936L,
    val sizeMb: Double = 48.5,
    val sha256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
) {
    fun toCatalogueLanguage() = CatalogueLanguage(
        languageTag = id,
        name = name,
        script = "Devanagari/Latin",
        iso = "hi-en",
        archive = archive,
        sizeBytes = sizeBytes,
        sizeMb = sizeMb,
        sha256 = sha256,
        tested = true,
        published = true
    )
}

/**
 * The iTantra offline model catalogue.
 *
 * The full catalogue is compiled into the app so the model hub works with
 * zero network access. [fetchRemoteCatalogue] opportunistically refreshes it
 * from the HuggingFace repo and gracefully falls back to the built-in copy
 * on any failure.
 */
object ModelCatalogue {

    const val CATALOGUE_URL =
        "https://huggingface.co/helo-ayush/itantra-models/raw/main/catalogue.json"

    const val DOWNLOAD_BASE_URL =
        "https://huggingface.co/helo-ayush/itantra-models/resolve/main/"

    const val NMT_CATALOGUE_URL =
        "https://huggingface.co/helo-ayush/itantra-models/raw/main/nmt-catalogue.json"

    /**
     * Fetches `nmt-catalogue.json` (OPUS pivot translator packs). Same entry
     * shape as the STT catalogue, separate file so translation packs never
     * pollute the speech-model list. Empty on any failure.
     */
    suspend fun fetchRemoteNmtCatalogue(): List<CatalogueLanguage> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(NMT_CATALOGUE_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode !in 200..299) return@withContext emptyList()
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val packs = JSONObject(body).getJSONArray("packs")
                val result = ArrayList<CatalogueLanguage>(packs.length())
                for (i in 0 until packs.length()) {
                    val entry = packs.getJSONObject(i)
                    result += CatalogueLanguage(
                        languageTag = entry.getString("languageTag"),
                        name = entry.getString("name"),
                        script = entry.getString("script"),
                        iso = entry.getString("iso"),
                        archive = entry.getString("archive"),
                        sizeBytes = entry.getLong("sizeBytes"),
                        sizeMb = entry.optDouble("sizeMB", entry.optDouble("sizeMb", 0.0)),
                        sha256 = entry.getString("sha256"),
                        tested = entry.optBoolean("tested", false),
                        published = entry.optBoolean("published", true)
                    )
                }
                result
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val translationModel = CatalogueTranslationModel()

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "iTantra/2.4.0 (offline-first disaster mesh)"

    /**
     * Built-in copy of `catalogue.json` — exact mirror of the hosted file so
     * the app is fully functional with zero network.
     */
    val fallbackLanguages: List<CatalogueLanguage> = listOf(
        CatalogueLanguage(
            languageTag = "hi-IN", name = "Hindi", script = "Devanagari", iso = "hi",
            archive = "hi-IN-1.0.0.itantra", sizeBytes = 281805965L, sizeMb = 268.75,
            sha256 = "1958d445f77c7b2dfaf325ee78ec1968f69fbeec3b552b3dc7f2baefb2d6b871",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "gu-IN", name = "Gujarati", script = "Gujarati", iso = "gu",
            archive = "gu-IN-1.0.0.itantra", sizeBytes = 281863680L, sizeMb = 268.81,
            sha256 = "476d3908e4d9ab7bfca8310b04bdfec8cacad9c0e866470d5b1767ff335d32d7",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "mr-IN", name = "Marathi", script = "Devanagari", iso = "mr",
            archive = "mr-IN-1.0.0.itantra", sizeBytes = 281642223L, sizeMb = 268.59,
            sha256 = "c30ccd6673ed23a4558eabf37db150b975e757cf54064af959d1b6b332799da3",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "kn-IN", name = "Kannada", script = "Kannada", iso = "kn",
            archive = "kn-IN-1.0.0.itantra", sizeBytes = 281742969L, sizeMb = 268.69,
            sha256 = "29ede6df9a4bc6360f030becbb7b11583184fac6300d7a74ed5fa110181f0cd0",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "ml-IN", name = "Malayalam", script = "Malayalam", iso = "ml",
            archive = "ml-IN-1.0.0.itantra", sizeBytes = 281145001L, sizeMb = 268.12,
            sha256 = "7936a4255ed0c9147c4e2e250e2f1fcc35f68b19b6318b404175385a3f6781a1",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "ta-IN", name = "Tamil", script = "Tamil", iso = "ta",
            archive = "ta-IN-1.0.0.itantra", sizeBytes = 281701230L, sizeMb = 268.65,
            sha256 = "1a1b4a04d1787ebd1bc78024d6e7a3c1822231d19cc52b94cfaebde8be744620",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "te-IN", name = "Telugu", script = "Telugu", iso = "te",
            archive = "te-IN-1.0.0.itantra", sizeBytes = 280580175L, sizeMb = 267.58,
            sha256 = "79fa256cac907c2e86a8093f9c1d1bcab14d9ee1c7cc1f0b4b578386c07bf68b",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "or-IN", name = "Odia", script = "Odia", iso = "or",
            archive = "or-IN-1.0.0.itantra", sizeBytes = 281051863L, sizeMb = 268.03,
            sha256 = "5dce160fc11ad97a5ebeb3a155d1aa530a9f3a2da3c7a4cdbb76be318ef7a59f",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "bn-IN", name = "Bengali", script = "Bengali", iso = "bn",
            archive = "bn-IN-1.0.0.itantra", sizeBytes = 279647725L, sizeMb = 266.69,
            sha256 = "1aa96c7c687c2e2e274fbd77d9fc2eaeb3aa48b1ee461a255e0c45d372e35b54",
            tested = true, published = true
        ),
        CatalogueLanguage(
            languageTag = "en-IN", name = "English", script = "Latin", iso = "en",
            archive = "en-IN-1.0.0.itantra", sizeBytes = 162445655L, sizeMb = 154.92,
            sha256 = "9f8e54cef6a17145105c078d5f0aac60ed40324470faf9a029d41e61546b6bb5",
            tested = true, published = true
        )
    )

    /**
     * Fetches `catalogue.json` from HuggingFace and merges it over the
     * built-in fallback. Remote entries replace same-tag fallback entries;
     * fallback entries with no remote counterpart are kept. Any network or
     * parse failure returns the untouched fallback list.
     */
    suspend fun fetchRemoteCatalogue(): List<CatalogueLanguage> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(CATALOGUE_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode !in 200..299) {
                    return@withContext fallbackLanguages
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                merge(parseCatalogue(body))
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            fallbackLanguages
        }
    }

    private fun parseCatalogue(json: String): List<CatalogueLanguage> {
        val languages = JSONObject(json).getJSONArray("languages")
        val result = ArrayList<CatalogueLanguage>(languages.length())
        for (i in 0 until languages.length()) {
            val entry = languages.getJSONObject(i)
            result += CatalogueLanguage(
                languageTag = entry.getString("languageTag"),
                name = entry.getString("name"),
                script = entry.getString("script"),
                iso = entry.getString("iso"),
                archive = entry.getString("archive"),
                sizeBytes = entry.getLong("sizeBytes"),
                sizeMb = entry.optDouble("sizeMB", entry.optDouble("sizeMb", 0.0)),
                sha256 = entry.getString("sha256"),
                tested = entry.optBoolean("tested", false),
                published = entry.optBoolean("published", true)
            )
        }
        return result
    }

    private fun merge(remote: List<CatalogueLanguage>): List<CatalogueLanguage> {
        if (remote.isEmpty()) return fallbackLanguages
        val merged = ArrayList(fallbackLanguages)
        for (entry in remote) {
            val index = merged.indexOfFirst { it.languageTag == entry.languageTag }
            if (index >= 0) merged[index] = entry else merged += entry
        }
        return merged
    }
}
