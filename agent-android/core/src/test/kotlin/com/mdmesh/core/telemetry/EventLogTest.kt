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
        override fun flush() {}
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

    @Test fun `what a check-in sends or confirms is on disk before it goes on`() {
        // drain/restore/delivered run off the main thread, so they wait for the disk: a process death right after
        // can then neither lose the batch nor send a confirmed one again.
        val writes = mutableListOf<String>()
        val store = object : FakeStore() {
            override fun put(vararg changes: Pair<String, String?>) { writes += "put"; super.put(*changes) }
            override fun flush() { writes += "flush" }
        }
        val log = EventLog(store)
        log.record("boot")
        val drained = log.drain()
        log.restore(drained)
        log.drain()
        log.delivered()
        assertEquals(listOf("put", "put", "flush", "put", "flush", "put", "flush", "put", "flush"), writes)
    }

    @Test fun `waiting for the disk does not hold up record() on another thread`() {
        // record() runs on the main thread (receivers, the kiosk); a slow disk under the check-in's drain, restore or
        // delivered must not block it.
        val ops: List<Pair<String, (EventLog) -> Unit>> = listOf(
            "drain" to { log -> log.drain() },
            "restore" to { log -> log.restore(listOf(com.mdmesh.proto.TelemetryEventDto("boot", 1))) },
            "delivered" to { log -> log.delivered() },
        )
        for ((name, op) in ops) {
            val slow = AtomicBoolean(false)
            val inDiskWait = CountDownLatch(1)
            val diskDone = CountDownLatch(1)
            val store = object : FakeStore() {
                override fun flush() {
                    if (!slow.get()) return
                    inDiskWait.countDown()
                    diskDone.await(2, TimeUnit.SECONDS)
                }
            }
            val log = EventLog(store)
            log.record("boot")
            slow.set(true)
            val worker = thread { op(log) }
            assertTrue(name, inDiskWait.await(2, TimeUnit.SECONDS))

            val recorded = CountDownLatch(1)
            thread { log.record("appInstalled"); recorded.countDown() }
            val quick = recorded.await(300, TimeUnit.MILLISECONDS)
            diskDone.countDown()
            worker.join()
            assertTrue("record() waited for $name's disk write", quick)
        }
    }

    @Test fun `nothing is lost when record() runs while a drain waits for the disk`() {
        val inDiskWait = CountDownLatch(1)
        val diskDone = CountDownLatch(1)
        val slow = AtomicBoolean(true)
        val store = object : FakeStore() {
            override fun flush() {
                if (!slow.getAndSet(false)) return
                inDiskWait.countDown()
                diskDone.await(2, TimeUnit.SECONDS)
            }
        }
        val log = EventLog(store)
        log.record("boot")
        val drainer = thread { log.drain() }
        assertTrue(inDiskWait.await(2, TimeUnit.SECONDS))
        thread { log.record("appInstalled") }.join(1_000)
        diskDone.countDown()
        drainer.join()
        log.delivered()
        assertEquals(listOf("appInstalled"), EventLog(store).drain().map { it.type })
    }
}
