/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Wave 1B — keeps a per-jobId fan-out registry of {@link SseEmitter} clients
 * so the {@code RetinalJobStatusSseController} can push job-status
 * transitions to all browser tabs that have an open subscription.
 *
 * <p>The broadcaster is the single point of contact for
 * {@code RetinalInferenceApiController.updateStatus(...)}: every successful
 * DB status flip calls {@link #publish(long, String)} which iterates the
 * registered emitters and best-effort writes the event. Send failures
 * (client disconnect, write timeout, idle-evicted emitter) remove the
 * offending emitter from the registry so the next publish does not pay
 * the lookup cost.
 *
 * <p>Heartbeats every 15 s keep the connection alive across proxies
 * that close idle TCP streams; same code path removes dead emitters
 * so the registry self-cleans even without explicit publishes.
 *
 * <p>Idle eviction: the controller hands each new emitter a 5-minute
 * timeout via the {@link SseEmitter#SseEmitter(Long)} constructor so a
 * client that drops without sending a FIN still gets reaped by Spring's
 * SseEmitter machinery.
 */
@Component
public class RetinalJobStatusBroadcaster {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalJobStatusBroadcaster.class);

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    /**
     * Subscribe an emitter to the supplied job's status fan-out. Returns
     * the same emitter so callers can chain
     * {@code return broadcaster.subscribe(...)} from a controller method.
     *
     * <p>The emitter's completion / timeout / error callbacks all unhook
     * it from the registry so a client disconnect doesn't leak slots.
     */
    public SseEmitter subscribe(long jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list =
                emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> remove(jobId, emitter));
        emitter.onTimeout(() -> remove(jobId, emitter));
        emitter.onError(t -> remove(jobId, emitter));
        return emitter;
    }

    /**
     * Best-effort: push a status string to every emitter subscribed for
     * the supplied job id. Emitters whose write throws are removed —
     * they are gone for good (the next request will resubscribe).
     */
    public void publish(long jobId, String status) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("status")
                        .data(Map.of("jobId", jobId, "status", status)));
            } catch (IOException | IllegalStateException ex) {
                LOG.debug("SSE publish failed for job {} — removing emitter: {}",
                        jobId, ex.getMessage());
                remove(jobId, emitter);
            }
        }
    }

    /**
     * Scheduled keepalive — every 15 s every emitter receives an
     * {@code event: heartbeat} comment so reverse proxies don't close
     * the idle stream. Send failures evict.
     */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> e : emitters.entrySet()) {
            long jobId = e.getKey();
            for (SseEmitter emitter : e.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                } catch (IOException | IllegalStateException ex) {
                    remove(jobId, emitter);
                }
            }
        }
    }

    /** Test seam — number of emitters currently registered for a job. */
    public int subscriberCount(long jobId) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(jobId);
        return list == null ? 0 : list.size();
    }

    private void remove(long jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(jobId);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) {
            // Drop the (now empty) bucket so the map doesn't bloat with
            // dead job ids. Compute-if-present + remove is racy but safe:
            // the worst case is a transient empty list that the next
            // publish recreates.
            emitters.remove(jobId, list);
        }
    }
}
