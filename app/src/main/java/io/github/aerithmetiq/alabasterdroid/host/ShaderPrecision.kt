/*
 * From alabaster-android (https://github.com/moronigranja/alabaster-android), commit f1cedd5.
 * MIT License, Copyright (c) 2026 Moroni Granja. Changed: the package name, and the light, fog and combine shaders are raised as well
 * as the GUI's four (see the class comment).
 */
package io.github.aerithmetiq.alabasterdroid.host

/**
 * Serves the shaders whose maths needs it at `highp`, which is what the desktop build effectively runs.
 *
 * Many of the game's shaders declare `precision mediump float;`. That is invisible on the desktop
 * build: desktop GL has no real mediump, so it is promoted to highp and the same bytes render the
 * same. A mobile GPU honours it (fp16 storage and ALU), and maths written against desktop precision
 * then quantises visibly.
 *
 * alabaster-android raised the four GUI shaders, for a stripe pattern on shaded GUI fills (FINDINGS
 * §16); at that time the world shaders were all highp. In game 0.2.0-7, 35 of the 60 shader files are
 * mediump, among them the light, fog and combine shaders, which rebuild a world position from the
 * depth buffer (the combine pass for its shadow-map lookup): at fp16 that comes out in steps that
 * follow the screen rows, seen as horizontal streaks across lit and fogged ground. Measured on the
 * Retroid Pocket Nova (Adreno 740, WebView 109), same area, 960x540:
 *
 * | served at highp | streaks |
 * |---|---|
 * | the GUI's four (alabaster-android) | present |
 * | + the fog's three | present |
 * | + the two lights, both stages | present |
 * | + `combine.frag`, `cinematic-combine.frag` | **gone** |
 * | every shader | gone |
 *
 * So Alabaster Droid raises the GUI, light, fog and combine shaders ([RAISED]) and leaves the rest
 * as the game wrote them.
 *
 * A vertex and a fragment shader that share uniforms are always raised **together**: Adreno honours
 * a vertex file's own `mediump`, and two stages that disagree on a uniform's precision do not link
 * (FINDINGS §22.16). The combine fragments' vertex stage, `textured.vert`, declares no uniforms, so it
 * cannot disagree with them and is left alone; `fog-plane-pre.frag` shares `fog-plane.vert`. ES 3.0
 * guarantees `highp` in both stages.
 *
 * [GameAssetHandler] applies this after [ShaderArrays], whose own edits look for the game's
 * `mediump` line in the water shaders.
 */
object ShaderPrecision {

    private const val MEDIUMP = "precision mediump float;"
    private const val HIGHP = "precision highp float;"

    /** The shaders served raised: confirmed on the device to remove the streaks (see the table). */
    private val RAISED = setOf(
        "gui.frag", "gui.vert", "gui-bg.frag", "gui-blur.frag",
        "light.vert", "light.frag", "simple-light.vert", "simple-light.frag",
        "fog-plane.vert", "fog-plane.frag", "fog-plane-pre.frag",
        "combine.frag", "cinematic-combine.frag",
    )

    /** Whether [rel] is a shader this raises, so the response is worth caching. */
    fun rewrites(rel: String): Boolean = rel.substringAfterLast('/') in RAISED

    /** The served bytes of a shader: its own float precision declaration, raised. */
    fun rewrite(rel: String, body: ByteArray): ByteArray {
        if (!rewrites(rel)) return body
        val text = String(body, Charsets.UTF_8)
        val raised = text.replaceFirst(MEDIUMP, HIGHP)
        return if (raised == text) body else raised.toByteArray(Charsets.UTF_8)
    }
}
