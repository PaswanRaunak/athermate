package io.ather.pro.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScooterArtworkTest {
    @Test fun supplied450STrueRedUsesThatDrawing() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_450S, "True Red")
        assertEquals("scooter_450s_true_red", drawing!!.drawable)
        assertEquals("True Red", drawing.colourLabel)
    }

    @Test fun dashedAndCaseDifferenceStillMatches() {
        val dashed = ScooterArtwork.drawing(ScooterModel.ATHER_450S, "true-red")
        val upper = ScooterArtwork.drawing(ScooterModel.ATHER_450S, "TRUE RED")
        assertEquals("scooter_450s_true_red", dashed!!.drawable)
        assertEquals("True Red", dashed.colourLabel)
        assertEquals("scooter_450s_true_red", upper!!.drawable)
        assertEquals("True Red", upper.colourLabel)
    }

    @Test fun unknownColourKeepsTheModelsDefaultDrawing() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_450S, "Space Grey")
        assertEquals("scooter_450s_cosmic_black", drawing!!.drawable)
        assertEquals("Cosmic Black", drawing.colourLabel)
    }

    @Test fun spaceGreyIsA450XDrawing() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_450X_2_9, "Space Grey")
        assertEquals("scooter_450x_space_grey", drawing!!.drawable)
        assertEquals("Space Grey", drawing.colourLabel)
    }

    @Test fun both450XPacksShareTheSameDefault() {
        val large = ScooterArtwork.drawing(ScooterModel.ATHER_450X_3_7, null)
        val small = ScooterArtwork.drawing(ScooterModel.ATHER_450X_2_9, null)
        assertEquals("scooter_450x_cosmic_black", large!!.drawable)
        assertEquals("Cosmic Black", large.colourLabel)
        assertEquals("scooter_450x_cosmic_black", small!!.drawable)
        assertEquals("Cosmic Black", small.colourLabel)
    }

    @Test fun apexIndiumBlue() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_APEX, "Indium Blue")
        assertEquals("scooter_apex_indium_blue", drawing!!.drawable)
        assertEquals("Indium Blue", drawing.colourLabel)
    }

    @Test fun riztaSiachenWhiteMono() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_RIZTA_3_7, "Siachen White Mono")
        assertEquals("scooter_rizta_siachen_white_mono", drawing!!.drawable)
        assertEquals("Siachen White Mono", drawing.colourLabel)
    }

    @Test fun konarcClaretRed() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_KONARC_2_1, "Claret Red")
        assertEquals("scooter_konarc_claret_red", drawing!!.drawable)
        assertEquals("Claret Red", drawing.colourLabel)
    }

    @Test fun konarcNullColourIsQuartzWhite() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_KONARC_3_5, null)
        assertEquals("scooter_konarc_quartz_white", drawing!!.drawable)
        assertEquals("Quartz White", drawing.colourLabel)
    }

    @Test fun ambiguousPangongBlueKeepsTheDefault() {
        val drawing = ScooterArtwork.drawing(ScooterModel.ATHER_RIZTA_2_9, "Pangong Blue")
        assertEquals("scooter_rizta_pangong_blue_duo", drawing!!.drawable)
        assertEquals("Pangong Blue Duo", drawing.colourLabel)
    }

    @Test fun modelWithNoSuppliedDrawingReturnsNull() {
        assertNull(ScooterArtwork.drawing(ScooterModel.ATHER_450S, "True Red", emptyMap()))
    }

    @Test fun matchingLabelAcceptsLunarGreyAndRejectsUnknownOrAmbiguousColours() {
        assertEquals("Lunar Grey", ScooterArtwork.matchingLabel(ScooterModel.ATHER_450X_3_7, "lunar grey"))
        assertNull(ScooterArtwork.matchingLabel(ScooterModel.ATHER_450X_3_7, "Indigo"))
        assertNull(ScooterArtwork.matchingLabel(ScooterModel.ATHER_RIZTA_3_7, "pangong blue"))
        assertEquals(
            "Pangong Blue Duo",
            ScooterArtwork.matchingLabel(ScooterModel.ATHER_RIZTA_3_7, "Pangong Blue Duo")
        )
    }

    @Test fun accountTitleUsesNicknameOrAccountFallback() {
        assertEquals("Blue Bolt", ScooterArtwork.accountTitle("  Blue Bolt  "))
        assertEquals("Your Athr+ account", ScooterArtwork.accountTitle(null))
        assertEquals("Your Athr+ account", ScooterArtwork.accountTitle(""))
        assertEquals("Your Athr+ account", ScooterArtwork.accountTitle("   "))
    }

    @Test fun coloursFor450SAreListedInOrder() {
        assertEquals(
            listOf("Cosmic Black", "Hyper Sand", "Stealth Blue", "True Red", "Still White"),
            ScooterArtwork.colours(ScooterModel.ATHER_450S).map { it.label }
        )
    }

    @Test fun coloursForApexIsOnlyIndiumBlue() {
        assertEquals(listOf("Indium Blue"), ScooterArtwork.colours(ScooterModel.ATHER_APEX).map { it.label })
    }

    @Test fun everySuppliedDrawingHasAVectorAndAnSvg() {
        val root = listOf(File("src/main"), File("android/app/src/main")).first { it.isDirectory }
        ScooterModel.entries.flatMap { ScooterArtwork.colours(it) }.map { it.drawable }.distinct().forEach { name ->
            assertTrue(File(root, "res/drawable/$name.xml").isFile)
            assertTrue(File(root, "assets/scooter-art/$name.svg").isFile)
        }
    }
}
