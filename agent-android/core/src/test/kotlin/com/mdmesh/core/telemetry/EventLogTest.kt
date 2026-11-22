package com.mdmesh.core.telemetry

import com.mdmesh.proto.EventType
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
        override fun flush() = Unit
    }

    @Test fun encodeDecodeRoundTrip() {
        val list = listOf(TelemetryEventDto("boot", 1), TelemetryEventDto("appInstalled", 2, "com.x"))
        assertEquals(list, EventLog.decode(EventLog.encode(list)))
    }

    private fun events(range: IntRange) = range.map { TelemetryEventDto("e", it.toLong()) }

    /** Successful check-ins until nothing is left. */
    private fun sendAll(log: EventLog): List<TelemetryEventDto> =
        generateSequence { log.drain().takeIf { it.isNotEmpty() }?.also { log.delivered() } }.flatten().toList()

    @Test fun `a full buffer drops the oldest and says how many`() {
        val capped = EventLog.cap(events(1..600))
        assertEquals(EventLog.CAP, capped.size)
        assertEquals(600L, capped.last().ts) // newest kept
        assertEquals(102L, capped[1].ts) // the oldest 101 make room for the marker
        val marker = capped.first()
        assertEquals(EventType.EVENTS_DROPPED, marker.type)
        assertEquals(101, EventLog.droppedCount(marker))
        assertEquals(101L, marker.ts) // when the newest dropped one happened
    }

    @Test fun `drops keep adding up in one marker`() {
        val store = FakeStore()
        val log = EventLog(store)
        for (ts in 1..EventLog.CAP + 10) log.record("e$ts")

        val sent = sendAll(log)
        assertEquals(EventLog.CAP, sent.size)
        assertEquals(1, sent.count { it.type == EventType.EVENTS_DROPPED })
        assertEquals(11, EventLog.droppedCount(sent.first()))
        assertEquals("e12", sent[1].type)
        assertEquals("e${EventLog.CAP + 10}", sent.last().type)
    }

    @Test fun `the marker's text keeps its count readable`() {
        val marker = EventLog.droppedMarker(3, 42)
        assertEquals(
            "3 older events dropped: the device keeps at most 500 while it couldn't deliver them",
            marker.detail,
        )
        assertEquals(listOf(marker), EventLog.decode(EventLog.encode(listOf(marker))))
        assertEquals(3, EventLog.droppedCount(EventLog.decode(EventLog.encode(listOf(marker))).single()))
        assertEquals("1 older event dropped", EventLog.droppedMarker(1, 0).detail?.substringBefore(':'))
    }

    @Test fun `a marker whose detail gives no count counts as one`() {
        for (detail in listOf(null, "", "many dropped", "-4 dropped", "0 dropped", "99999999999 dropped")) {
            val marker = TelemetryEventDto(EventType.EVENTS_DROPPED, 1, detail)
            assertEquals(detail.toString(), 1, EventLog.droppedCount(marker))
        }
    }

    @Test fun `a record() while a batch is in flight overflows on its own, and a restore merges both markers`() {
        val store = FakeStore().apply { values["events"] = EventLog.encode(events(1..600)) }
        val log = EventLog(store)
        val batch = log.drain(EventLog.CAP) // the oldest 101 already dropped behind a marker
        assertEquals(EventLog.CAP, batch.size)
        assertEquals(101, EventLog.droppedCount(batch.first()))

        for (i in 1..600) log.record("w$i") // the check-in is slow: the waiting events overflow too
        val waiting = log.buffered().drop(batch.size)
        val second = waiting.first()
        assertEquals(EventType.EVENTS_DROPPED, second.type)
        assertEquals(101, EventLog.droppedCount(second))

        log.restore(batch) // the check-in failed
        val kept = log.buffered()
        assertEquals(EventLog.CAP, kept.size)
        assertEquals(1, kept.count { it.type == EventType.EVENTS_DROPPED })
        // Both markers' counts plus the 499 events of the batch that no longer fit; dated like the newest dropped.
        assertEquals(101 + 499 + 101, EventLog.droppedCount(kept.first()))
        assertEquals(second.ts, kept.first().ts)
        assertEquals("w102", kept[1].type)
        assertEquals("w600", kept.last().type)
    }

    @Test fun `an unreadable buffer becomes a marker instead of vanishing`() {
        val store = FakeStore().apply { values["events"] = "{not json" }
        val log = EventLog(store)
        log.record("boot")
        val sent = log.drain()
        assertEquals(listOf(EventType.EVENTS_DROPPED, "boot"), sent.map { it.type })
        assertEquals(EventLog.UNREADABLE_DETAIL, sent.first().detail)
        assertEquals(1, EventLog.droppedCount(sent.first()))
    }

    @Test fun `delivered keeps the marker for an in-flight batch it can't read, which was never sent`() {
        // The batch on disk was damaged after it went out: what the server took can't be told apart, so the gap is
        // reported rather than an unsent marker counted as delivered.
        val store = FakeStore().apply { values["events_in_flight"] = "{not json" }
        val log = EventLog(store)
        log.delivered(accepted = 5)
        val kept = log.buffered()
        assertEquals(listOf(EventType.EVENTS_DROPPED), kept.map { it.type })
        assertEquals(EventLog.UNREADABLE_DETAIL, kept.single().detail)
    }

    @Test fun `delivered drops what the server consumed and keeps the rest in front`() {
        val store = FakeStore().apply { values["events"] = EventLog.encode(events(1..300)) }
        val log = EventLog(store)
        assertEquals(300, log.drain(EventLog.CAP).size)
        log.record("new")
        log.delivered(accepted = 120)
        val rest = log.buffered()
        assertEquals((121L..300L).toList(), rest.dropLast(1).map { it.ts })
        assertEquals("new", rest.last().type)
    }

    @Test fun `a server that claims more than the batch takes the whole batch and nothing waiting`() {
        val store = FakeStore().apply { values["events"] = EventLog.encode(events(1..150)) }
        val log = EventLog(store)
        log.drain() // 1..100
        log.delivered(accepted = 1_000)
        assertEquals((101L..150L).toList(), log.buffered().map { it.ts })
    }

    @Test fun `a negative count takes nothing, so the whole batch goes out again`() {
        val store = FakeStore().apply { values["events"] = EventLog.encode(events(1..150)) }
        val log = EventLog(store)
        log.drain()
        log.delivered(accepted = -5)
        assertEquals((1L..150L).toList(), log.buffered().map { it.ts })
    }

    @Test fun `an older server's answer counts as the first 100 taken`() {
        // A server rolled back below protocol 1.12 keeps the first 100 and drops the rest without saying so.
        val store = FakeStore().apply { values["events"] = EventLog.encode(events(1..300)) }
        val log = EventLog(store)
        log.drain(EventLog.CAP)
        log.delivered(accepted = null)
        assertEquals((101L..300L).toList(), log.buffered().map { it.ts })
    }

    @Test fun `a backlog goes out in batches the server takes, oldest first, one check-in after another`() {
        // Servers take at most 100 events per check-in and ignore the rest; a bigger batch marked delivered was lost.
        val store = FakeStore().apply { values["events"] = EventLog.encode(events(1..250)) }
        val log = EventLog(store)

        val first = log.drain()
        assertEquals((1L..100L).toList(), first.map { it.ts })
        log.delivered()
        assertEquals((101L..200L).toList(), log.drain().map { it.ts })
        log.delivered()
        assertEquals((201L..250L).toList(), log.drain().map { it.ts })
        log.delivered()
        assertTrue(log.drain().isEmpty())
    }

    @Test fun `a failed check-in puts its batch back in front of the rest of the backlog`() {
        val store = FakeStore().apply { values["events"] = EventLog.encode(events(1..150)) }
        val log = EventLog(store)
        log.restore(log.drain())
        assertEquals((1L..100L).toList(), log.drain().map { it.ts })
        log.delivered()
        assertEquals((101L..150L).toList(), EventLog(store).drain().map { it.ts })
    }

    @Test fun `what an older agent left in flight goes out in batches too`() {
        // Agents before 0.5.2 drained up to 500 at once; one that died mid-check-in left them all in flight.
        val store = FakeStore().apply {
            values["events_in_flight"] = EventLog.encode(events(1..450))
            values["events"] = EventLog.encode(events(451..500))
        }
        val log = EventLog(store)
        assertEquals((1L..100L).toList(), log.drain().map { it.ts })
        log.delivered()
        assertEquals((101L..500L).toList(), sendAll(log).map { it.ts })
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
