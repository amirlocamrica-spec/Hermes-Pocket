package com.hermes.android.di

import com.hermes.android.runtime.remote.ServerProfileStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Access point for the profiles screen (reached via navigation, not a
 * Hilt-injected ViewModel).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServerProfileEntryPoint {
    fun serverProfileStore(): ServerProfileStore
}