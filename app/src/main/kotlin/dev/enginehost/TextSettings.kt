package dev.enginehost

import android.content.Context

/**
 * The person's choices for a game's text (docs/text-services.md). Read in the main process, when
 * a launch is planned, and handed to the runtime with the launch ([RuntimeActivity.EXTRA_READ_ALOUD]),
 * which reads no preferences of its own once its sandbox has closed.
 */
class TextSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("text_services", Context.MODE_PRIVATE)

    /** Read lines aloud with the device's own voices. Off until the person turns it on. */
    var readAloud: Boolean
        get() = prefs.getBoolean(READ_ALOUD, false)
        set(value) = prefs.edit().putBoolean(READ_ALOUD, value).apply()

    /** Translate the game's lines with the person's chosen service. Off until they turn it on. */
    var translate: Boolean
        get() = prefs.getBoolean(TRANSLATE, false)
        set(value) = prefs.edit().putBoolean(TRANSLATE, value).apply()

    /** The language to translate into, as the person named it ("English"); the device's language until they change it. */
    var translateInto: String
        get() = prefs.getString(TRANSLATE_INTO, null) ?: java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
        set(value) = prefs.edit().putString(TRANSLATE_INTO, value.trim()).apply()

    /** The OpenAI-compatible endpoint the person chose ([Translator]); empty until they set one. */
    var translatorEndpoint: String
        get() = prefs.getString(TRANSLATOR_ENDPOINT, "").orEmpty()
        set(value) = prefs.edit().putString(TRANSLATOR_ENDPOINT, value.trim()).apply()

    var translatorModel: String
        get() = prefs.getString(TRANSLATOR_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(TRANSLATOR_MODEL, value.trim()).apply()

    /** The service's key, if it needs one; a server on the person's own network may not. */
    val translatorKey = KeystoreSecret(context, "enginehost_translator_key", "translator-key-v1", "key")

    private companion object {
        const val READ_ALOUD = "read_aloud"
        const val TRANSLATE = "translate"
        const val TRANSLATE_INTO = "translate_into"
        const val TRANSLATOR_ENDPOINT = "translator_endpoint"
        const val TRANSLATOR_MODEL = "translator_model"
    }
}
