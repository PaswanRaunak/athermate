package io.ather.pro.domain.model

data class ScooterColour(val label: String, val drawable: String)

data class ScooterDrawing(val drawable: String, val colourLabel: String)

object ScooterArtwork {
    private val cosmicBlack450X = ScooterColour("Cosmic Black", "scooter_450x_cosmic_black")
    private val cosmicBlack450S = ScooterColour("Cosmic Black", "scooter_450s_cosmic_black")
    private val indiumBlue = ScooterColour("Indium Blue", "scooter_apex_indium_blue")
    private val pangongBlueDuo = ScooterColour("Pangong Blue Duo", "scooter_rizta_pangong_blue_duo")
    private val quartzWhite = ScooterColour("Quartz White", "scooter_konarc_quartz_white")

    private val colours450X = listOf(
        cosmicBlack450X,
        ScooterColour("True Red", "scooter_450x_true_red"),
        ScooterColour("Space Grey", "scooter_450x_space_grey"),
        ScooterColour("Lunar Grey", "scooter_450x_lunar_grey"),
        ScooterColour("Still White", "scooter_450x_still_white"),
        ScooterColour("Hyper Sand", "scooter_450x_hyper_sand"),
        ScooterColour("Stealth Blue", "scooter_450x_stealth_blue")
    )

    private val colours450S = listOf(
        cosmicBlack450S,
        ScooterColour("Hyper Sand", "scooter_450s_hyper_sand"),
        ScooterColour("Stealth Blue", "scooter_450s_stealth_blue"),
        ScooterColour("True Red", "scooter_450s_true_red"),
        ScooterColour("Still White", "scooter_450s_still_white")
    )

    private val coloursApex = listOf(indiumBlue)

    private val coloursRizta = listOf(
        ScooterColour("Alphonso Yellow Duo", "scooter_rizta_alphonso_yellow_duo"),
        ScooterColour("Cardamom Green Duo", "scooter_rizta_cardamom_green_duo"),
        ScooterColour("Deccan Grey Duo", "scooter_rizta_deccan_grey_duo"),
        ScooterColour("Deccan Grey Mono", "scooter_rizta_deccan_grey_mono"),
        pangongBlueDuo,
        ScooterColour("Pangong Blue Super Matte", "scooter_rizta_pangong_blue_super_matte"),
        ScooterColour("Siachen White Mono", "scooter_rizta_siachen_white_mono"),
        ScooterColour("Terracotta Red Duo", "scooter_rizta_terracotta_red_duo"),
        ScooterColour("Terracotta Red Super Matte", "scooter_rizta_terracotta_red_super_matte")
    )

    private val coloursKonarc = listOf(
        quartzWhite,
        ScooterColour("Majestic Grey", "scooter_konarc_majestic_grey"),
        ScooterColour("Celestial Gold", "scooter_konarc_celestial_gold"),
        ScooterColour("Lumen Copper", "scooter_konarc_lumen_copper"),
        ScooterColour("Steel Blue", "scooter_konarc_steel_blue"),
        ScooterColour("Claret Red", "scooter_konarc_claret_red")
    )

    private val catalog: Map<ScooterModel, List<ScooterColour>> = mapOf(
        ScooterModel.ATHER_450X_3_7 to colours450X,
        ScooterModel.ATHER_450X_2_9 to colours450X,
        ScooterModel.ATHER_450S to colours450S,
        ScooterModel.ATHER_APEX to coloursApex,
        ScooterModel.ATHER_RIZTA_3_7 to coloursRizta,
        ScooterModel.ATHER_RIZTA_2_9 to coloursRizta,
        ScooterModel.ATHER_KONARC_2_1 to coloursKonarc,
        ScooterModel.ATHER_KONARC_2_7 to coloursKonarc,
        ScooterModel.ATHER_KONARC_3_5 to coloursKonarc
    )

    private val defaults: Map<ScooterModel, ScooterColour> = mapOf(
        ScooterModel.ATHER_450X_3_7 to cosmicBlack450X,
        ScooterModel.ATHER_450X_2_9 to cosmicBlack450X,
        ScooterModel.ATHER_450S to cosmicBlack450S,
        ScooterModel.ATHER_APEX to indiumBlue,
        ScooterModel.ATHER_RIZTA_3_7 to pangongBlueDuo,
        ScooterModel.ATHER_RIZTA_2_9 to pangongBlueDuo,
        ScooterModel.ATHER_KONARC_2_1 to quartzWhite,
        ScooterModel.ATHER_KONARC_2_7 to quartzWhite,
        ScooterModel.ATHER_KONARC_3_5 to quartzWhite
    )

    fun colours(model: ScooterModel): List<ScooterColour> = catalog[model].orEmpty()

    fun drawing(model: ScooterModel, colour: String?): ScooterDrawing? = drawing(model, colour, catalog)

    /** Canonical catalogue label, or null when [saved] is blank, unknown, or ambiguous. */
    fun matchingLabel(model: ScooterModel, saved: String?): String? =
        catalog[model]?.let { match(it, saved)?.label }

    fun accountTitle(displayName: String?): String =
        displayName?.trim()?.takeIf(String::isNotEmpty) ?: "Your AtherMate account"

    internal fun drawing(
        model: ScooterModel,
        colour: String?,
        catalog: Map<ScooterModel, List<ScooterColour>>
    ): ScooterDrawing? {
        val colours = catalog[model]?.takeIf { it.isNotEmpty() } ?: return null
        val chosen = match(colours, colour) ?: defaultColour(model, colours)
        return ScooterDrawing(drawable = chosen.drawable, colourLabel = chosen.label)
    }

    private fun defaultColour(model: ScooterModel, colours: List<ScooterColour>): ScooterColour =
        colours.firstOrNull { it == defaults[model] } ?: colours.first()

    private fun match(colours: List<ScooterColour>, saved: String?): ScooterColour? {
        val query = normalize(saved)
        if (query.isEmpty()) return null
        colours.firstOrNull { normalize(it.label) == query }?.let { return it }
        return colours.filter { entry ->
            val label = normalize(entry.label)
            label.isNotEmpty() && (label.contains(query) || query.contains(label))
        }.singleOrNull()
    }

    private fun normalize(value: String?): String =
        value.orEmpty().lowercase().replace(nonAlphanumeric, " ").trim()

    private val nonAlphanumeric = Regex("[^a-z0-9]+")
}
