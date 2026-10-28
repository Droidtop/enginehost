package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEventsTest {
    @Test
    fun `a line keeps its speaker, kind and flags`() {
        val line = TextEvents.parse(
            """{"v":1,"t":"line","id":"day1_3f2a","seq":4,"kind":"dialogue","speaker":{"id":"e","name":"Eileen"},
               "text":"Hello there!","raw":"{b}Hello{/b} there!","voiced":true,"window":"adv","future":"ignored"}""",
        ) as TextEvent.Line
        assertEquals("day1_3f2a", line.id)
        assertEquals(TextEvent.Speaker("e", "Eileen"), line.speaker)
        assertEquals("Hello there!", line.text)
        assertTrue(line.voiced)
        assertFalse(line.append)
        assertEquals("adv", line.window)
    }

    @Test
    fun `narration has no speaker and an unknown kind reads as dialogue`() {
        val narration = TextEvents.parse("""{"v":1,"t":"line","id":"n1","kind":"narration","text":"It rained."}""") as TextEvent.Line
        assertNull(narration.speaker)
        assertEquals("narration", narration.kind)
        val unknown = TextEvents.parse("""{"v":1,"t":"line","id":"n2","kind":"shout","text":"Hey"}""") as TextEvent.Line
        assertEquals("dialogue", unknown.kind)
    }

    @Test
    fun `another major version, an unknown type or a missing field is dropped`() {
        assertNull(TextEvents.parse("""{"v":2,"t":"line","id":"a","text":"x"}"""))
        assertNull(TextEvents.parse("""{"t":"line","id":"a","text":"x"}"""))
        assertNull(TextEvents.parse("""{"v":1,"t":"explode"}"""))
        assertNull(TextEvents.parse("""{"v":1,"t":"line","text":"no id"}"""))
        assertNull(TextEvents.parse("""{"v":1,"t":"line","id":"a"}"""))
        assertNull(TextEvents.parse("""{"v":1,"t":"state","mode":"turbo"}"""))
        assertNull(TextEvents.parse("not json"))
        assertNull(TextEvents.parse("""{"v":1,"t":"choices","id":"m","items":[]}"""))
    }

    @Test
    fun `an oversized event is dropped and long text is cut`() {
        assertNull(TextEvents.parse("""{"v":1,"t":"line","id":"a","text":"${"x".repeat(TextEvents.MAX_EVENT)}"}"""))
        val long = TextEvents.parse("""{"v":1,"t":"line","id":"a","text":"${"y".repeat(TextEvents.MAX_TEXT + 50)}"}""") as TextEvent.Line
        assertEquals(TextEvents.MAX_TEXT, long.text.length)
    }

    @Test
    fun `control characters go, a line break stays in text but not in a name`() {
        val line = TextEvents.parse(
            """{"v":1,"t":"line","id":"a","speaker":{"name":"Ei\nleen\u0007"},"text":"one\ntwo\u001b[2J\tthree"}""",
        ) as TextEvent.Line
        assertEquals("one\ntwo[2J three", line.text)
        assertEquals("Eileen", line.speaker?.name)
    }

    @Test
    fun `a menu keeps its choices in order, capped`() {
        val items = (1..80).joinToString(",") { """{"id":"$it","text":"Choice $it","enabled":${it != 2}}""" }
        val menu = TextEvents.parse("""{"v":1,"t":"choices","id":"m1","items":[$items]}""") as TextEvent.Choices
        assertEquals(TextEvents.MAX_CHOICES, menu.items.size)
        assertEquals("Choice 1", menu.items[0].text)
        assertFalse(menu.items[1].enabled)
    }

    @Test
    fun `the rate cap passes a burst, then the average`() {
        val rate = TextRate(perSecond = 10, burst = 20)
        assertEquals(20, (1..50).count { rate.take(1_000) })
        assertFalse(rate.take(1_000))
        // Half a second later, five more have come due.
        assertEquals(5, (1..50).count { rate.take(1_500) })
    }
}
