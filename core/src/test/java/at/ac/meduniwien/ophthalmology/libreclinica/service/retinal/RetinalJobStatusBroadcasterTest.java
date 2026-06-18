/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Wave 1B unit tests for {@link RetinalJobStatusBroadcaster}: subscribe
 * adds an emitter, publish fans out, and a failing emitter is removed
 * so future publishes don't keep paying for it.
 */
public class RetinalJobStatusBroadcasterTest {

    @Test
    public void subscribe_addsEmitterToRegistry() {
        RetinalJobStatusBroadcaster b = new RetinalJobStatusBroadcaster();
        SseEmitter e = new SseEmitter();
        SseEmitter returned = b.subscribe(42L, e);
        assertNotNull(returned);
        assertEquals(1, b.subscriberCount(42L));
        assertEquals(0, b.subscriberCount(99L));
    }

    @Test
    public void publish_callsEveryEmitterForTheJob() throws IOException {
        RetinalJobStatusBroadcaster b = new RetinalJobStatusBroadcaster();
        AtomicInteger sendCount = new AtomicInteger();
        SseEmitter e1 = new CountingEmitter(sendCount);
        SseEmitter e2 = new CountingEmitter(sendCount);
        b.subscribe(42L, e1);
        b.subscribe(42L, e2);

        b.publish(42L, "segmenting");

        assertEquals(2, sendCount.get());
    }

    @Test
    public void publish_removesEmittersThatThrow() {
        RetinalJobStatusBroadcaster b = new RetinalJobStatusBroadcaster();
        SseEmitter bad = new FailingEmitter();
        SseEmitter ok = new SseEmitter();
        b.subscribe(7L, bad);
        b.subscribe(7L, ok);
        assertEquals(2, b.subscriberCount(7L));

        b.publish(7L, "done");

        // Failing emitter is dropped; healthy one is kept.
        assertEquals(1, b.subscriberCount(7L));
    }

    @Test
    public void publish_isNoopWhenNoSubscribers() {
        RetinalJobStatusBroadcaster b = new RetinalJobStatusBroadcaster();
        b.publish(123L, "done");
        assertEquals(0, b.subscriberCount(123L));
    }

    @Test
    public void heartbeat_isSafeWithEmptyRegistry() {
        RetinalJobStatusBroadcaster b = new RetinalJobStatusBroadcaster();
        b.heartbeat();
        // no throw + nothing to subscribe → 0
        assertEquals(0, b.subscriberCount(1L));
    }

    /** SseEmitter that counts {@code send} invocations instead of writing. */
    private static final class CountingEmitter extends SseEmitter {
        private final AtomicInteger sends;
        CountingEmitter(AtomicInteger sends) {
            this.sends = sends;
        }
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
        }
    }

    /** SseEmitter that always throws on send — exercises the eviction path. */
    private static final class FailingEmitter extends SseEmitter {
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client gone");
        }
    }
}
