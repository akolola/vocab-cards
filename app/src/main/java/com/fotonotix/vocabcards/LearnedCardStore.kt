package com.fotonotix.vocabcards

import android.content.Context

object LearnedCardStore {
    private const val PREFS = "learned_store"
    private const val KEY   = "learned_indices"

    fun load(ctx: Context): Set<Int> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return if (raw.isBlank()) emptySet()
        else raw.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    fun save(ctx: Context, indices: Set<Int>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, indices.joinToString(","))
            .apply()
    }

    fun clear(ctx: Context) = save(ctx, emptySet())
}
