package com.fotonotix.vocabcards

import android.content.Context

object WrongCardStore {
    private const val PREFS      = "vocab_prefs"
    private const val KEY        = "wrong_indices"
    private const val KEY_URI    = "last_file_uri"
    private const val KEY_NAME   = "last_file_name"

    fun load(ctx: Context): List<Int> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return if (raw.isBlank()) emptyList()
        else raw.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted()
    }

    fun save(ctx: Context, indices: Set<Int>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, indices.joinToString(","))
            .apply()
    }

    fun clear(ctx: Context) = save(ctx, emptySet())

    fun saveLastFile(ctx: Context, uri: android.net.Uri, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_NAME, name)
            .apply()
    }

    fun loadLastFileUri(ctx: Context): android.net.Uri? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URI, null) ?: return null
        return android.net.Uri.parse(raw)
    }

    fun loadLastFileName(ctx: Context): String {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, "") ?: ""
    }

    fun clearLastFile(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_URI).remove(KEY_NAME).apply()
    }
}
