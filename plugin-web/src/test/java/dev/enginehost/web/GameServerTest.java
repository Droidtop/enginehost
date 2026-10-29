package dev.enginehost.web;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GameServerTest {
    private static final long LENGTH = 1000;

    @Test public void aRangeFromStartToEnd() {
        assertArrayEquals(new long[] {0, 499}, GameServer.span("bytes=0-499", LENGTH));
    }

    @Test public void anOpenRangeRunsToTheEnd() {
        assertArrayEquals(new long[] {500, 999}, GameServer.span("bytes=500-", LENGTH));
    }

    @Test public void aRangeEndPastTheFileStopsAtItsLastByte() {
        assertArrayEquals(new long[] {900, 999}, GameServer.span("bytes=900-5000", LENGTH));
    }

    @Test public void aSuffixIsTheLastBytes() {
        assertArrayEquals(new long[] {500, 999}, GameServer.span("bytes=-500", LENGTH));
        assertArrayEquals(new long[] {0, 999}, GameServer.span("bytes=-5000", LENGTH));
    }

    @Test public void aStartPastTheEndCannotBeSatisfied() {
        assertEquals(0, GameServer.span("bytes=1000-", LENGTH).length);
        assertEquals(0, GameServer.span("bytes=600-500", LENGTH).length);
        assertEquals(0, GameServer.span("bytes=-0", LENGTH).length);
    }

    @Test public void aNumberTooLongForALongIsPastTheEndNotAnException() {
        assertEquals(0, GameServer.span("bytes=99999999999999999999999-", LENGTH).length);
        assertArrayEquals(new long[] {10, 999}, GameServer.span("bytes=10-99999999999999999999999", LENGTH));
        assertArrayEquals(new long[] {0, 999}, GameServer.span("bytes=-99999999999999999999999", LENGTH));
    }

    @Test public void anythingElseServesTheWholeFile() {
        assertNull(GameServer.span("bytes=-", LENGTH));
        assertNull(GameServer.span("bytes=0-1,5-9", LENGTH));
        assertNull(GameServer.span("items=0-1", LENGTH));
    }

    @Test public void theHeadScriptGoesInsideHeadThenHtmlThenFirst() {
        assertEquals("<html><HEAD lang=en>S<title>", GameServer.inject("<html><HEAD lang=en><title>", "S"));
        assertEquals("<html dir=ltr>S<body>", GameServer.inject("<html dir=ltr><body>", "S"));
        assertEquals("S<p>story", GameServer.inject("<p>story", "S"));
    }

    @Test public void mimeTypesIgnoreTheExtensionsCase() {
        assertEquals("audio/ogg", GameServer.mimeFor("Battle.OGG"));
        assertEquals("application/octet-stream", GameServer.mimeFor("data.rpgsave"));
    }
}
