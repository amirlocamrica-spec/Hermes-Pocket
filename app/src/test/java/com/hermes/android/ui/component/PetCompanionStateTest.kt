package com.hermes.android.ui.component

import com.hermes.android.ui.viewmodel.ChatConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class PetCompanionStateTest {

    @Test
    fun connected_maps_to_paw_online() {
        val v = mapConnectionToPetVisual(ChatConnectionState.Connected)
        assertEquals("\uD83D\uDC3E", v.emoji)
        assertEquals("آنلاین", v.label)
    }

    @Test
    fun connecting_maps_to_search() {
        val v = mapConnectionToPetVisual(ChatConnectionState.Connecting)
        assertEquals("\uD83D\uDD0D", v.emoji)
    }

    @Test
    fun reconnecting_maps_to_refresh() {
        val v = mapConnectionToPetVisual(ChatConnectionState.Reconnecting)
        assertEquals("\uD83D\uDD04", v.emoji)
    }

    @Test
    fun disconnected_maps_to_sleepy() {
        val v = mapConnectionToPetVisual(ChatConnectionState.Disconnected)
        assertEquals("\uD83D\uDE34", v.emoji)
        assertEquals("آفلاین", v.label)
    }

    @Test
    fun failed_maps_to_dizzy() {
        val v = mapConnectionToPetVisual(ChatConnectionState.Failed)
        assertEquals("\uD83D\uDE35", v.emoji)
        assertEquals("خطا!", v.label)
    }

    @Test
    fun all_states_produce_distinct_emojis() {
        val states = ChatConnectionState.entries
        val emojis = states.map { mapConnectionToPetVisual(it).emoji }.toSet()
        assertEquals(states.size, emojis.size)
    }
}
