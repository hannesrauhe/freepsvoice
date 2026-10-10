package eu.rauhe.freepsvoice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WavTest {
    @Test fun `header is 44 bytes and little endian`() {
        val pcm = ByteArray(100)
        val wav = Wav.encode(pcm)
        assertEquals(144, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        // RIFF chunk size = 36 + data
        assertEquals(136, le32(wav, 4))
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        // PCM format = 1
        assertEquals(1, le16(wav, 20))
        assertEquals(1, le16(wav, 22))                 // mono
        assertEquals(16000, le32(wav, 24))             // sample rate
        assertEquals(32000, le32(wav, 28))             // byte rate
        assertEquals(2, le16(wav, 32))                 // block align
        assertEquals(16, le16(wav, 34))                // bits
        assertEquals('d'.code.toByte(), wav[36])
        assertEquals(100, le32(wav, 40))               // data size
    }

    @Test fun `empty pcm still yields a valid header`() {
        assertEquals(44, Wav.encode(ByteArray(0)).size)
    }

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
}

class FlowPickerTest {
    @Test fun `plain json parses`() {
        val o = FlowPicker.extractJson("""{"flow":"LichtAn","reply_ok":"Ok"}""")!!
        assertEquals("LichtAn", o.getString("flow"))
    }

    @Test fun `code fence is stripped`() {
        val o = FlowPicker.extractJson("```json\n{\"flow\":\"A\"}\n```")!!
        assertEquals("A", o.getString("flow"))
    }

    @Test fun `prose around json is ignored`() {
        val o = FlowPicker.extractJson("Klar! {\"flow\":\"B\"} Gerne.")!!
        assertEquals("B", o.getString("flow"))
    }

    @Test fun `non json yields null`() {
        assertNull(FlowPicker.extractJson("Ich weiß nicht"))
        assertNull(FlowPicker.extractJson(""))
        assertNull(FlowPicker.extractJson("{unclosed"))
    }

    @Test fun `catalogue lists name kind and one-line description`() {
        val flows = mapOf(
            "B" to JSONObject("""{"Kind":"manual","Description":"a\n  b"}"""),
            "A" to JSONObject("""{}"""),
        )
        assertEquals("A | ? | \nB | manual | a b", FlowPicker.catalogue(flows))
    }
}

class AlertsTest {
    private fun alert(name: String, sev: Int, cat: String = "Fritz", desc: String = "",
                      sinceLastMs: Long = 0, silenceMs: Long = 0, counter: Int = 1) =
        Alert(name, cat, desc, sev, counter, sinceLastMs, silenceMs)

    @Test fun `severity tiers split at two and three`() {
        assertTrue(Alerts.isUrgent(alert("a", 1)))
        assertTrue(Alerts.isUrgent(alert("a", 2)))
        assertTrue(Alerts.isNotice(alert("a", 3)))
        assertTrue(Alerts.isQuiet(alert("a", 4)))
        assertTrue(Alerts.isQuiet(alert("a", 5)))
    }

    @Test fun `group puts each alert in exactly one tier`() {
        val g = Alerts.group(
            listOf(alert("a", 5), alert("b", 2), alert("c", 3), alert("d", 4), alert("e", 1))
        )
        assertEquals(listOf("e", "b"), g.urgent.map { it.name })
        assertEquals(listOf("c"), g.notice.map { it.name })
        assertEquals(listOf("d", "a"), g.quiet.map { it.name })
        assertEquals(5, g.total)
    }

    @Test fun `device numbers are dropped for clustering`() {
        assertEquals("BatteryLow", Alerts.label(alert("BatteryLow09295 0994096", 5)))
        assertEquals("Tür", Alerts.label(alert("Tür", 3)))
    }

    @Test fun `summary clusters and counts the noisy ones`() {
        val quiet = listOf(
            alert("BatteryLow1 11", 5), alert("BatteryLow2 22", 5), alert("DeviceAlert9", 4),
        )
        assertEquals("BatteryLow x2 · DeviceAlert", Alerts.quietSummary(quiet))
    }

    @Test fun `age is coarse enough to read from a distance`() {
        assertEquals("gerade eben", Alerts.age(30_000))
        assertEquals("14 Min", Alerts.age(14 * 60_000L))
        assertEquals("2 Std", Alerts.age(2 * 3_600_000L))
        assertEquals("3 Tage", Alerts.age(3 * 86_400_000L))
    }

    @Test fun `sev 1 to 3 wake the panel`() {
        assertTrue(Alerts.wakes(alert("a", 1)))
        assertTrue(Alerts.wakes(alert("b", 3)))
        assertTrue(!Alerts.wakes(alert("c", 4)))
        assertTrue(!Alerts.wakes(alert("d", 5)))
    }

    @Test fun `text falls back to the full name without a description`() {
        assertEquals("Fritz.BatteryLow1", alert("BatteryLow1", 5).text)
        assertEquals("vorhanden", alert("x", 5, desc = "vorhanden").text)
        assertEquals("ohneKategorie", alert("ohneKategorie", 5, cat = "").fullName)
    }

    @Test fun `silenced alerts are recognised by remaining time`() {
        assertTrue(alert("a", 2, silenceMs = 60_000).silenceMs > 0)
        assertTrue(alert("a", 2).silenceMs == 0L)
    }
}

class SensorsTest {
    private val data = mapOf(
        "Badezimmer" to mapOf("temperature" to 20.5, "battery" to 1),
        "Kueche" to mapOf("temperature" to 20.0, "battery" to 55),
        "Badtür 3F771" to mapOf("battery" to 56),
        "Bad Steckdose" to mapOf("temperature" to 21.5, "state" to true),
    )

    @Test fun `sensors reporting the same properties share a card`() {
        val groups = Sensors.groups(listOf("temperature", "battery"), data)
        assertEquals(
            listOf("Badezimmer" to listOf("20.5", "1"), "Kueche" to listOf("20", "55")),
            groups.first { it.props.size == 2 }.rows,
        )
    }

    @Test fun `sensors with a different property set get their own card`() {
        val groups = Sensors.groups(listOf("temperature", "battery"), data)
        assertEquals(3, groups.size)
        assertEquals(listOf("temperature", "battery"), groups[0].props)
        assertEquals(listOf("battery"), groups[1].props)
        assertEquals(listOf("Badtür 3F771"), groups[1].rows.map { it.first })
        assertEquals(listOf("temperature"), groups[2].props)
    }

    @Test fun `values follow the selected property order`() {
        val groups = Sensors.groups(listOf("battery", "temperature"), data)
        assertEquals(listOf("battery", "temperature"), groups.first { it.props.size == 2 }.props)
        assertEquals(listOf("1", "20.5"), groups.first { it.props.size == 2 }.rows.first().second)
    }

    @Test fun `unselected properties are never shown`() {
        val groups = Sensors.groups(listOf("temperature", "state"), data)
        assertEquals(listOf("temperature", "state"), groups.first { it.props.size == 2 }.props)
        assertEquals("An", groups.first { it.props.size == 2 }.rows.single().second[1])
    }

    @Test fun `empty selection yields no cards`() {
        assertTrue(Sensors.groups(emptyList(), data).isEmpty())
    }

    @Test fun `switches read as An and Aus`() {
        assertEquals("An", Sensors.render(true))
        assertEquals("Aus", Sensors.render(false))
    }

    @Test fun `whole numbers do not read as floats`() {
        assertEquals("21", Sensors.render(21.0))
        assertEquals("21.5", Sensors.render(21.5))
    }

    @Test fun `missing value renders as a dash`() {
        assertEquals("–", Sensors.render(null))
        assertEquals("–", Sensors.render(JSONObject.NULL))
    }

    @Test fun `nested objects flatten to key pairs`() {
        val o = JSONObject("""{"temp":20.5,"humidity":40}""")
        val s = Sensors.render(o)
        assertTrue(s.contains("temp: 20.5"))
        assertTrue(s.contains("humidity: 40"))
    }

    @Test fun `editor sorts by how many sensors have the property`() {
        val order = Sensors.editorOrder(mapOf("temperature" to 11, "Active" to 126, "hue" to 3))
        assertEquals(listOf("Active", "temperature", "hue"), order)
    }

    @Test fun `ties in count sort by name`() {
        val order = Sensors.editorOrder(mapOf("Beta" to 2, "alpha" to 2))
        assertEquals(listOf("alpha", "Beta"), order)
    }

    @Test fun `search is case-insensitive substring`() {
        val names = listOf("temperature", "battery", "main.temp")
        assertEquals(listOf("temperature", "main.temp"), Sensors.filter(names, "TEMP"))
        assertEquals(names, Sensors.filter(names, "  "))
    }
}
