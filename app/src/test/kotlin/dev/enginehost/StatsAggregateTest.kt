package dev.enginehost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StatsAggregateTest {
    private fun model(name: String, count: Int, average: Double, popular: String? = "visual_novels") =
        """{"kind":"translation","model":"$name","backend":"openai","count":$count,"average":$average${popular?.let { ",\"popularFor\":\"$it\"" } ?: ""}}"""

    @Test
    fun `fewer than ten ratings show nothing`() {
        val aggregate = StatsAggregate.parse(
            """{"schema":1,"models":[${model("few", 9, 4.0)},${model("Enough", 10, 4.24)}]}""",
        )
        assertNull(aggregate.lookup(StatsReport.Kind.TRANSLATION, "few", "openai"))
        val entry = aggregate.lookup(StatsReport.Kind.TRANSLATION, " enough ", "openai")!!
        assertEquals(10, entry.count)
        assertEquals(4.24, entry.average, 0.0001)
        assertEquals(StatsReport.Purpose.VISUAL_NOVELS, entry.popularFor)
        assertNull(aggregate.lookup(StatsReport.Kind.TRANSLATION, "enough", "local-network"))
        assertNull(aggregate.lookup(StatsReport.Kind.VOICE, "enough", "openai"))
    }

    @Test
    fun `what this build cannot read is no aggregate`() {
        assertSame(StatsAggregate.EMPTY, StatsAggregate.parse("""{"schema":2,"models":[${model("m", 50, 4.0)}]}"""))
        assertSame(StatsAggregate.EMPTY, StatsAggregate.parse("not json"))
        val odd = StatsAggregate.parse(
            """{"schema":1,"models":[${model("bad", 50, 7.0)},${model("odd", 50, 3.0, "everything")},{"kind":"sound","model":"x","backend":"openai","count":50,"average":3}]}""",
        )
        assertNull(odd.lookup(StatsReport.Kind.TRANSLATION, "bad", "openai"))
        assertNull(odd.lookup(StatsReport.Kind.TRANSLATION, "odd", "openai")!!.popularFor)
    }
}
