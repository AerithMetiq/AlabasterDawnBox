package io.github.aerithmetiq.alabasterdawnbox.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AlabasterDawnBox's own checks of the payload [GamepadState] writes for the shim (the layout itself is
 * [GamepadStateTest]'s): the text, a controller event's changes published together, and numbers JSON
 * cannot carry.
 */
class GamepadPayloadTest {

    private fun neutralButtons(pressed: Int = -1) = (0 until GamepadState.BUTTONS).joinToString(",") {
        if (it == pressed) "{\"pressed\":true,\"value\":1.0}" else "{\"pressed\":false,\"value\":0.0}"
    }

    @Test
    fun `the payload is the shim's JSON`() {
        val state = GamepadState()
        state.press(0)
        assertEquals("{\"axes\":[0.0,0.0,0.0,0.0],\"buttons\":[${neutralButtons(0)}]}", state.json())
    }

    @Test
    fun `numbers are the float values widened, as org json wrote them`() {
        val state = GamepadState()
        state.setAxes(leftX = 0.4f, leftY = -1f, rightX = 0f, rightY = 0.25f)
        state.setTrigger(7, 0.3f)
        val json = JSONObject(state.json())
        assertEquals(0.4f.toDouble(), json.getJSONArray("axes").getDouble(0), 0.0)
        assertEquals(-1.0, json.getJSONArray("axes").getDouble(1), 0.0)
        assertEquals(0.25, json.getJSONArray("axes").getDouble(3), 0.0)
        assertEquals(0.3f.toDouble(), json.getJSONArray("buttons").getJSONObject(7).getDouble("value"), 0.0)
    }

    @Test
    fun `a batch publishes the same pad as the changes one by one`() {
        val single = GamepadState()
        single.setAxes(0.5f, -0.5f, 0.1f, 0.2f)
        single.setTrigger(6, 0.7f)
        single.setTrigger(7, 0.2f)
        single.setHat(up = false, down = true, left = false, right = true)
        val batched = GamepadState()
        batched.batch {
            setAxes(0.5f, -0.5f, 0.1f, 0.2f)
            setTrigger(6, 0.7f)
            setTrigger(7, 0.2f)
            setHat(up = false, down = true, left = false, right = true)
        }
        assertEquals(single.json(), batched.json())
    }

    @Test
    fun `nothing inside a batch is published before it ends`() {
        val state = GamepadState()
        state.press(0)
        val before = state.json()
        var during = ""
        state.batch {
            setAxes(1f, 0f, 0f, 0f)
            during = json()
        }
        assertEquals(before, during)
        assertEquals(1.0, JSONObject(state.json()).getJSONArray("axes").getDouble(0), 0.0)
    }

    @Test
    fun `a value JSON cannot carry is sent as zero`() {
        val state = GamepadState()
        state.setAxes(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f)
        val axes = JSONObject(state.json()).getJSONArray("axes")
        assertEquals(0.0, axes.getDouble(0), 0.0)
        assertEquals(0.0, axes.getDouble(1), 0.0)
        assertEquals(0.0, axes.getDouble(2), 0.0)
        assertEquals(0.5, axes.getDouble(3), 0.0)
    }
}
