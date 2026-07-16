package panoptes.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;

import panoptes.config.LlmProperties;
import panoptes.web.SseService;

/**
 * The core communication layer bridging the Panoptes agents with the Google Gemini API.
 * <p>
 * This client is designed for high-concurrency, fault-tolerant LLM interactions. It abstracts away 
 * the complexities of network requests and provides robust failure handling, including:
 * <ul>
 *   <li><b>Key Rotation:</b> Distributes requests across multiple API keys to prevent quota exhaustion.</li>
 *   <li><b>Resilience:</b> Implements exponential backoff for rate limits (429) and server overloads (503).</li>
 *   <li><b>Timeout Management:</b> Uses Virtual Threads to enforce strict API timeouts, preventing the 
 *       pipeline from hanging indefinitely on dead connections.</li>
 *   <li><b>Structured Output:</b> Enforces strict JSON schemas when requested by the agents.</li>
 * </ul>
 * </p>
 */
@Service
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private final LlmProperties properties;
    private final SseService sseService; 
    private final AtomicInteger keyCounter = new AtomicInteger(0);
    private final ExecutorService timeoutExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, AtomicInteger> jobErrorCounts = new ConcurrentHashMap<>();

    private final Map<String, AtomicInteger> jobCallCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> jobRetryCounts = new ConcurrentHashMap<>();
    
    public GeminiClient(LlmProperties properties, SseService sseService) {
        this.properties = properties;
        this.sseService = sseService;
    }

    public String ponder(String systemInstruction, String userPrompt, List<Part> files, Schema jsonSchema) {
        List<String> keys = properties.getApiKeys();
        List<String> models = properties.getModels();

        int maxRetries = 30;
        Exception lastException = null;

        // Wir laufen den Call-Stack rückwärts durch und suchen den eigentlichen Agenten
        // Go through the call-stack to fine the agent; this is solely for Logging.
        String callerAgent = StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .filter(className -> !className.contains("GeminiClient") && !className.contains("AbstractAgent"))
                .findFirst()
                .map(className -> className.substring(className.lastIndexOf('.') + 1)) // Just the class name
                .orElse("UnknownCaller"));

        log.debug("LLM REQUEST FROM: {}", callerAgent);
        String jobId = MDC.get("jobId");
        if (jobId != null) {
            jobCallCounts.computeIfAbsent(jobId, k -> new AtomicInteger(0)).incrementAndGet();
        }
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            
            int currentIndex = keyCounter.getAndIncrement();
            String currentKey = keys.get(currentIndex % keys.size());
            String currentModel = models.get(attempt % models.size());

            try (Client client = Client.builder().apiKey(currentKey).build()) {
                
                List<Part> parts = new ArrayList<>();
                if (userPrompt != null && !userPrompt.isBlank()) {
                    parts.add(Part.fromText(userPrompt));
                }
                if (files != null) {
                    parts.addAll(files);
                }

                Content content = Content.fromParts(parts.toArray(Part[]::new));

                GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                        .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)));

                if (jsonSchema != null) {
                    configBuilder.responseMimeType("application/json")
                                 .responseSchema(jsonSchema);
                }

                CompletableFuture<String> llmCall = CompletableFuture.supplyAsync(() -> {
                    try {
                        GenerateContentResponse response = client.models.generateContent(
                                currentModel, content, configBuilder.build()
                        );
                        return response.text();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, timeoutExecutor);

                String text;
                try {
                    text = llmCall.get(100, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    llmCall.cancel(true);
                    throw new RuntimeException("API HANG DETECTED (Timeout exceeded). Forcing retry...", te);
                }
                
                if (jsonSchema != null && text != null) {
                    text = text.trim();
                    if (text.startsWith("```json")) text = text.substring(7);
                    else if (text.startsWith("```")) text = text.substring(3);
                    if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
                    text = text.trim();
                }

                return text;

            } catch (Exception e) {
                lastException = e;
                String errorMsg = e.getMessage() != null ? e.getMessage().toUpperCase() : e.toString();
                
                log.error("Gemini API Error (" + (attempt + 1) + "/" + maxRetries + ") with " + currentModel + ". Retrying... [" + errorMsg + "]");

                // Count errors and send status updated to the frontend
                if (jobId != null) {
                	// Count errors
                    jobRetryCounts.computeIfAbsent(jobId, k -> new AtomicInteger(0)).incrementAndGet();
                    
                    int fails = jobErrorCounts.computeIfAbsent(jobId, k -> new AtomicInteger(0)).incrementAndGet();
                    
                    // Only alarm the frontend after several (5) errors, no panicking.
                    if (fails >= 5) {
                        sseService.sendUpdate(jobId, "[API_DELAY_WARNING]:" + fails);
                        log.error("Gemini API Error (" + (attempt + 1) + "/" + maxRetries + ") with " + currentModel + ". Retrying... [" + errorMsg+ "]");
                    }
                }

                // Find dead API keys
                if (errorMsg.contains("400") && errorMsg.contains("API KEY NOT VALID")) {
                    log.error("[GRRRR] DEAD API KEY DETECTED! Remove this exact key from properties: {}", currentKey);
                    
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }

                // RATE LIMIT (429)
                if (errorMsg.contains("429") || errorMsg.contains("RESOURCE_EXHAUSTED") || errorMsg.contains("QUOTA")) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue; 
                } 
                
                // SERVER OVERLOAD (503)
                if (errorMsg.contains("503") || errorMsg.contains("TIMEOUT") || errorMsg.contains("DEADLINE_EXCEEDED")) {
                    try { Thread.sleep(2000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                
                // DEFAULT FALLBACK
                try { Thread.sleep(1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        
        log.error("Fatal Error: All {} retries failed. Last exception: {}", maxRetries, lastException.getMessage());
        throw new RuntimeException("All LLM retries failed in ponder().", lastException);
    }
    
    public int getCallCount(String jobId) {
        return jobCallCounts.getOrDefault(jobId, new AtomicInteger(0)).get();
    }
    
    public int getRetryCount(String jobId) {
        return jobRetryCounts.getOrDefault(jobId, new AtomicInteger(0)).get();
    }
    
    public void cleanupStats(String jobId) {
        jobCallCounts.remove(jobId);
        jobRetryCounts.remove(jobId);
        jobErrorCounts.remove(jobId);
    }
}