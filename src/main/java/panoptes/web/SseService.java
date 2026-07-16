package panoptes.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A robust Server-Sent Events (SSE) manager for real-time frontend communication.
 * <p>
 * Because deep research jobs can take several minutes (or even hours), the backend must constantly 
 * stream status updates to the user interface. This service handles thread-safe SSE emitters and 
 * caches the entire event history for each job. If a user refreshes the page or loses connection, 
 * the cached history is instantly replayed upon reconnection, ensuring no updates are lost.
 * </p>
 */
@Service
public class SseService {
    private final Map<String, List<SseEmitter>> emittersMap = new ConcurrentHashMap<>();
    
    private final Map<String, List<String>> eventCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> jobCompleted = new ConcurrentHashMap<>();

    public SseEmitter createEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(1000 * 60 * 15L); 
        emittersMap.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        
        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));
        
        // 1. Send complete history to the client
        List<String> history = eventCache.get(jobId);
        if (history != null) {
            for (String msg : history) {
                try {
                	// Synchronize, so emitters don't crash
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("update").data(msg));
                    }
                } catch (Exception e) { 
                    removeEmitter(jobId, emitter);
                }
            }
        }
        
        // 2. If the job was already finished, send the 'DONE' Event.
        if (Boolean.TRUE.equals(jobCompleted.get(jobId))) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name("complete").data("DONE"));
                    emitter.complete();
                }
            } catch (Exception e) { 
                removeEmitter(jobId, emitter);
            }
        }
        
        return emitter;
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersMap.get(jobId);
        if (emitters != null) emitters.remove(emitter);
    }

    public void sendUpdate(String jobId, String message) {
        eventCache.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(message);
        
        List<SseEmitter> emitters = emittersMap.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("update").data(message));
                    }
                } catch (Exception e) {
                    removeEmitter(jobId, emitter);
                }
            }
        }
    }

    public void sendComplete(String jobId) {
        jobCompleted.put(jobId, true);
        
        List<SseEmitter> emitters = emittersMap.get(jobId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("complete").data("DONE"));
                        emitter.complete();
                    }
                } catch (Exception e) {
                    removeEmitter(jobId, emitter);
                }
            }
            emittersMap.remove(jobId);
        }
    }
}