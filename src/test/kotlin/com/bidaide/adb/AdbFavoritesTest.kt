package com.bidaide.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbFavoritesTest {
    private val candidates = listOf(
        FavoriteCandidate("Bidaide.Adb.Kill", "Android CLI: Matar app"),
        FavoriteCandidate("Bidaide.Adb.ClearCache", "Android CLI: Limpiar caché"),
        FavoriteCandidate("Bidaide.Adb.DarkMode", "Android CLI: Alternar modo oscuro"),
    )

    @Test
    fun `ids are parsed one per line, trimmed and without duplicates`() {
        assertEquals(listOf("A", "B"), AdbFavorites.parse("  A \n\nB\r\nA\n"))
        assertEquals(emptyList<String>(), AdbFavorites.parse(""))
    }

    @Test
    fun `serialised ids parse back to the same list`() {
        val ids = listOf("Bidaide.Adb.Kill", "Bidaide.Adb.DarkMode")
        assertEquals(ids, AdbFavorites.parse(AdbFavorites.serialize(ids)))
        assertEquals("", AdbFavorites.serialize(emptyList()))
    }

    @Test
    fun `ids of actions that no longer exist are dropped keeping the order`() {
        assertEquals(listOf("B", "A"), AdbFavorites.existing(listOf("B", "Gone", "A")) { it != "Gone" })
    }

    @Test
    fun `selection follows the order of the Android CLI group and ignores unknown ids`() {
        assertEquals(
            listOf("Bidaide.Adb.Kill", "Bidaide.Adb.DarkMode"),
            AdbFavorites.ordered(setOf("Bidaide.Adb.DarkMode", "Unknown", "Bidaide.Adb.Kill"), candidates),
        )
    }

    @Test
    fun `filter ignores case, accents, word order and the Android CLI prefix`() {
        val cache = candidates[1]
        assertTrue(AdbFavorites.matches(cache, "cache"))
        assertTrue(AdbFavorites.matches(cache, "CACHÉ limpiar"))
        assertTrue(AdbFavorites.matches(cache, "  "))
        assertFalse(AdbFavorites.matches(cache, "adb"))
        assertFalse(AdbFavorites.matches(cache, "oscuro"))
    }
}
