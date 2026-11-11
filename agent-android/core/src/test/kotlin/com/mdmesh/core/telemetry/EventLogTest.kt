package com.mdmesh.core.telemetry

import com.mdmesh.proto.TelemetryEventDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class EventLogTest {

    /** Stands in for SharedPreferences, which is one object per file for the whole process. */
    private open class FakeStore : EventLog.Store {
        val values = ConcurrentHashMap<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(vararg changes: Pair<String, String?>) {
            for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
        }
    }

    @Test fun encodeDecodeRoundTrip() {
        val list = listOf(TelemetryEventDto("boot", 1), TelemetryEventDto("appInstalled", 2, "com.x"))
        assertEquals(list, EventLog.decode(EventLog.encode(list)))
    }

    @Test fun capKeepsMostRecent() {
        val list = (1..600).map { TelemetryEventDto("e", it.toLong()) }
        val capped = EventLog.cap(list)
        assertEquals(500, capped.size)
        assertEquals(600L, capped.last().ts) // newest kept
        assertEquals(101L, capped.first().ts) // oldest 100 dropped
    }

    @Test fun decodeNullAndGarbageYieldEmpty() {
        assertTrue(EventLog.decode(null).isEmpty())
        assertTrue(EventLog.decode("not json").isEmpty())
    }

    @Test fun `separate instances never interleave on the shared store`() {
        // The receivers build their own EventLog while the check-in drains the injected one.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val parkFirst = AtomicBoolean(true)
        val store = object : FakeStore() {
            override fun get(key: String): String? {
                if (parkFirst.compareAndSet(true, false)) {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                return super.get(key)
            }
        }
        val first = EventLog(store)
        val second = EventLog(store)

        val a = thread { first.record("boot") }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val secondDone = CountDownLatch(1)
        val b = thread { second.record("appInstalled"); secondDone.countDown() }
        val overlapped = secondDone.await(300, TimeUnit.MILLISECONDS)
        release.countDown()
        a.join(); b.join()

        assertFalse("the second instance wrote inside the first one's read-modify-write", overlapped)
        assertEquals(listOf("boot", "appInstalled"), EventLog(store).drain().map { it.type })
    }

    @Test fun `drained events survive a process death until the check-in is delivered`() {
        val store = FakeStore()
        EventLog(store).record("boot")
        EventLog(store).drain() // the process dies while this check-in is in flight

        val after = EventLog(store)
        after.record("appInstalled")
        assertEquals(listOf("boot", "appInstalled"), after.drain().map { it.type })
        after.delivered()
        assertTrue(EventLog(store).drain().isEmpty())
    }

    @Test fun `restore puts drained events back in front, once`() {
        val store = FakeStore()
        val log = EventLog(store)
        log.record("boot")
        val drained = log.drain()
        log.record("appInstalled")
        log.restore(drained)

        assertEquals(listOf("boot", "appInstalled"), EventLog(store).drain().map { it.type })
    }

    @Test fun `events buffered by the previous agent version are still sent`() {
        val store = FakeStore().apply {
            values["events"] = EventLog.encode(listOf(TelemetryEventDto("boot", 1)))
        }
        assertEquals(listOf("boot"), EventLog(store).drain().map { it.type })
    }
}
