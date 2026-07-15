package com.example.data

import com.example.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

/**
 * Single shared Supabase client for the whole app.
 *
 * URL and anon/publishable key come from BuildConfig, which the Secrets
 * Gradle Plugin populates from the .env file at the repo root at build time.
 * There is no per-user runtime config anymore (that was the old Firebase
 * "paste your config JSON" pattern) — this project's Supabase backend is
 * fixed, so the client can just be created once.
 */
object SupabaseClientProvider {

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
            install(Storage)
        }
    }

    const val DOCUMENTS_BUCKET = "documents"
    const val DOCUMENTS_TABLE = "documents"
    const val TOMBSTONES_TABLE = "documents_tombstones"
}
