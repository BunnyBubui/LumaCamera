package th.ogay.luma

import kotlin.math.pow

/**
 * Original Lumora v3 looks. This catalog is opt-in and intentionally does not alter
 * legacy Films.BASES, stored preference IDs, SR2/SR3, HDR, or the existing UI.
 *
 * Profiles are procedurally baked into 17^3 sRGB cubes. They are creative looks,
 * not calibrated reproductions of commercial film stocks.
 */
internal object FilmCollectionV3 {
    enum class Scene { DAYLIGHT, NIGHT, CLASSIC }
    internal data class Look(
        val id: String,
        val scene: Scene,
        val shadows: FloatArray,
        val highlights: FloatArray,
        val contrast: Float,
        val saturation: Float,
        val lift: Float,
        val gamma: Float
    )

    private fun look(
        id: String, scene: Scene, shadow: String, highlight: String,
        contrast: Float, saturation: Float, lift: Float = 0f, gamma: Float = 1f
    ) = Look(id, scene, rgb(shadow), rgb(highlight), contrast, saturation, lift, gamma)

    private fun rgb(hex: String): FloatArray {
        require(hex.length == 6)
        return FloatArray(3) { i -> (hex.substring(i * 2, i * 2 + 2).toInt(16) - 128) / 255f }
    }

    val looks: List<Look> = listOf(
        look("Golden Portrait 400",Scene.DAYLIGHT,"52758D","FFD3A1",1.07f,1.06f,.016f,.97f),
        look("Summer Gold 200",Scene.DAYLIGHT,"686E8A","FFE08B",1.13f,1.18f,.008f,.98f),
        look("Emerald 400",Scene.DAYLIGHT,"397E81","D9F3B5",1.16f,1.20f,.008f,1.02f),
        look("Sakura Diary",Scene.DAYLIGHT,"8473A3","FFD1E4",.92f,.94f,.035f,.94f),
        look("Ocean Chrome",Scene.DAYLIGHT,"286F99","B6E7FF",1.18f,1.17f,.004f,1.02f),
        look("Peach Bloom",Scene.DAYLIGHT,"8E8291","FFD0B7",.96f,1.04f,.026f,.94f),
        look("Retro Street 400",Scene.DAYLIGHT,"5B7567","FFC39C",1.25f,1.10f,.005f,1.04f),
        look("Vintage 1978",Scene.DAYLIGHT,"60705A","EAC68A",1.10f,.91f,.028f,1.02f),
        look("Clean Negative",Scene.DAYLIGHT,"778394","F7E8CF",1.02f,1.03f,.014f,.99f),
        look("Summer Pop",Scene.DAYLIGHT,"477E8D","FFE79E",1.20f,1.28f,.004f,.98f),
        look("Pastel Memory",Scene.DAYLIGHT,"9B8CA3","F9DCE1",.89f,.87f,.046f,.95f),
        look("Autumn 200",Scene.DAYLIGHT,"6C6C75","FFC18B",1.15f,1.09f,.012f,1.03f),
        look("Neon Midnight 800",Scene.NIGHT,"2864B5","F6A4CF",1.25f,1.27f,.003f,1.05f),
        look("Amber Night 800",Scene.NIGHT,"456F9D","FFD087",1.14f,1.12f,.008f,1.01f),
        look("Flashback 2005",Scene.NIGHT,"596581","FFD3B6",1.28f,1.23f,0f,1.06f),
        look("Cyber Chrome",Scene.NIGHT,"247F9E","F6A2FF",1.22f,1.28f,.005f,1.05f),
        look("Tokyo Afterdark",Scene.NIGHT,"357E8D","FFB57B",1.23f,1.15f,.006f,1.06f),
        look("Cinema 800T",Scene.NIGHT,"396CA5","F4B78C",1.07f,.97f,.022f,1.02f),
        look("Night Rose",Scene.NIGHT,"665A9E","FF9BC8",1.10f,1.15f,.016f,.99f),
        look("Noir City",Scene.NIGHT,"4C667B","D1C9BB",1.32f,.75f,.002f,1.08f),
        look("Silver Noir 1600",Scene.CLASSIC,"777777","888888",1.31f,0f,.004f,1.06f),
        look("Soft Mono 400",Scene.CLASSIC,"777777","888888",.93f,0f,.037f,.98f),
        look("Expired 1999",Scene.CLASSIC,"688B72","E7BEA4",.92f,.87f,.054f,1.05f),
        look("Instant 90",Scene.CLASSIC,"7C8C8A","F9D8B4",.91f,.88f,.056f,.95f)
    )

    fun byId(id: String): Look? = looks.firstOrNull { it.id == id }

    /** Bake in encoded sRGB; no external assets or network required. */
    fun bake(look: Look, size: Int = 17): FilmLut3D {
        require(size in 2..65)
        val data = FloatArray(size * size * size * 3)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val src = floatArrayOf(r.toFloat()/(size-1),g.toFloat()/(size-1),b.toFloat()/(size-1))
            val luma = .2126f*src[0]+.7152f*src[1]+.0722f*src[2]
            val shadowWeight = (1f-luma).pow(1.7f)
            val highlightWeight = luma.pow(1.7f)
            val skinLike = src[0] > src[1] && src[1] >= src[2] &&
                (src[0]-src[1]) in .025f..0.28f &&
                (src[1]-src[2]) in .015f..0.26f
            val chromaFactor = if (skinLike) .75f else 1f
            val idx = ((b*size+g)*size+r)*3
            for (c in 0..2) {
                val saturated = luma + (src[c]-luma)*look.saturation*chromaFactor
                val contrasted = ((saturated-.5f)*look.contrast+.5f+look.lift).coerceIn(0f,1f)
                val curved = contrasted.toDouble().pow(look.gamma.toDouble()).toFloat()
                val split = (look.shadows[c]*shadowWeight+look.highlights[c]*highlightWeight)*.22f
                data[idx+c] = (curved+split*chromaFactor).coerceIn(0f,1f)
            }
        }
        return FilmLut3D(size,data)
    }
}
