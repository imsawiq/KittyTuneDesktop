package com.alananasss.kittytune.data.together

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TogetherPeerTimelineTest {
    @Test fun `oldest accepted peer activity is evicted when global capacity is reached`() {
        val timeline = TogetherPeerTimeline(monotonicMs = { 0L }, maxPeers = 2)
        assertTrue(timeline.acceptState("room", "a", 100))
        assertTrue(timeline.acceptState("room", "b", 100))
        assertTrue(timeline.acceptState("room", "a", 200))
        assertTrue(timeline.acceptState("another-room", "c", 100))

        assertFalse(timeline.acceptState("room", "a", 150))
        assertTrue(timeline.acceptState("room", "b", 50))
    }

    @Test fun `sender clock rollback is accepted after monotonic idle timeout`() {
        var now = 0L
        val timeline = TogetherPeerTimeline(monotonicMs = { now })
        assertTrue(timeline.acceptState("room", "host", 100_000))
        now = 59_999
        assertFalse(timeline.acceptState("room", "host", 10))
        now = 60_000
        assertTrue(timeline.acceptState("room", "host", 10))
        assertTrue(timeline.acceptDeparture("room", "host", 20))
    }

    @Test fun `heartbeats do not keep an old playback clock alive indefinitely`() {
        var now = 0L
        val timeline = TogetherPeerTimeline(monotonicMs = { now })
        assertTrue(timeline.acceptState("room", "host", 100_000))
        now = 50_000
        assertTrue(timeline.acceptHello("room", "host", 10))
        now = 60_000
        assertTrue(timeline.acceptState("room", "host", 20))
        assertTrue(timeline.acceptDeparture("room", "host", 30))
    }

    @Test fun `duplicate leave wins ties and expires without preventing later reconnect`() {
        var now = 0L
        val timeline = TogetherPeerTimeline(monotonicMs = { now })
        assertTrue(timeline.acceptDeparture("room", "host", 100))
        assertTrue(timeline.acceptDeparture("room", "host", 100))
        assertFalse(timeline.acceptHello("room", "host", 100))
        assertFalse(timeline.acceptState("room", "host", 100))
        now = 60_000
        assertTrue(timeline.acceptHello("room", "host", 1))
        assertTrue(timeline.acceptState("room", "host", 1))
    }

    @Test fun `forget and reset discard only the intended ordering history`() {
        val timeline = TogetherPeerTimeline(monotonicMs = { 0L })
        timeline.acceptState("one", "host", 100)
        timeline.acceptState("two", "host", 100)
        timeline.forget("one")
        assertTrue(timeline.acceptState("one", "host", 10))
        assertFalse(timeline.acceptState("two", "host", 10))
        timeline.clear()
        assertTrue(timeline.acceptState("two", "host", 10))
    }
}
