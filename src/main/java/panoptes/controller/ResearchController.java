package panoptes.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import panoptes.dto.ValidatedResult;
import panoptes.service.CitationFormatter;
import panoptes.service.ResearchPipelineService;
import panoptes.web.SseService;

/**
 * The primary REST Controller handling asynchronous research jobs and real-time frontend communication.
 * <p>
 * This controller serves as the main gateway between the Panoptes user interface and the backend 
 * multi-agent pipeline. It is responsible for:
 * <ul>
 *   <li><b>Initialization:</b> Accepting multimodal user input (text and audio atm) and triggering the asynchronous {@link panoptes.service.ResearchPipelineService}.</li>
 *   <li><b>Real-time Streaming:</b> Providing Server-Sent Events (SSE) endpoints so the frontend can receive live, granular status updates as the LLM agents ponder and investigate.</li>
 *   <li><b>Disaster Recovery:</b> Exposing a "rescue" endpoint capable of recovering and formatting a final report from raw artifacts in the event of a partial pipeline crash or formatting error.</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchPipelineService pipelineService;
    private final SseService sseService;

    public ResearchController(ResearchPipelineService pipelineService, SseService sseService) {
        this.pipelineService = pipelineService;
        this.sseService = sseService;
    }

    // 1. Text + Audio from frontend
    @PostMapping("/init")
    public ResponseEntity<Map<String, String>> initJob(@RequestParam(value = "idea-text", required = false) String text,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "language", defaultValue = "English") String language) {

        String jobId = UUID.randomUUID().toString();

        // Save data synchronously from the request
        List<ResearchPipelineService.UploadedFile> safeFiles = new java.util.ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                if (!f.isEmpty()) {
                    try {
                        safeFiles.add(new ResearchPipelineService.UploadedFile(
                            f.getOriginalFilename(),
                            f.getContentType(),
                            f.getBytes()
                        ));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        pipelineService.executeDeepResearchAsync(jobId, text, safeFiles, language);

        return ResponseEntity.ok(Map.of("jobId", jobId));
    }

    // 2.frontend subscribes to this SSE stream 
    @GetMapping(value = "/stream/{jobId}", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamUpdates(@PathVariable String jobId) {
        return sseService.createEmitter(jobId);
    }
    
    // x. Rescue endpoint. Regenerates the formatted result, in case something went wrong.
    @GetMapping("/rescue/{jobId}")
    public ResponseEntity<String> rescueJob(@PathVariable String jobId) {
        try {
            Path draftPath = Paths.get("results", jobId, "06_raw_draft_with_headers.md");
            Path dbPath = Paths.get("results", jobId, "bibliography_database.json");
            
            if (!Files.exists(draftPath) || !Files.exists(dbPath)) {
                return ResponseEntity.badRequest().body("Files missing! Can't rescue job: " + jobId);
            }
            
            // 1. read text and JSON from secondary memory
            String rawDraft = Files.readString(draftPath, StandardCharsets.UTF_8);
            String dbJson = Files.readString(dbPath, StandardCharsets.UTF_8);
            
            // 2. create a list of ValidatedResults JSON 
            ObjectMapper mapper = new ObjectMapper();
            List<ValidatedResult> dbList = mapper.readValue(
                dbJson, 
                new TypeReference<List<ValidatedResult>>(){}
            );
            
            // 3. convert back to a map for the formatter
            Map<String, ValidatedResult> paperDb = new HashMap<>();
            for (ValidatedResult vr : dbList) {
                paperDb.put(vr.originalResult().id(), vr);
            }
            
            // 4. Call the formatter to re-format
            String finalFormatted = CitationFormatter.formatReport(rawDraft, paperDb);
            
            // 5. Save the report.md	 
            Path reportPath = Paths.get("results", jobId, "report.md");
            Files.writeString(reportPath, finalFormatted, StandardCharsets.UTF_8);
            
            return ResponseEntity.ok("Successfully rescued job! You can now visit: <a href='/results/" + jobId + "'>View Report</a>");
            
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("Error rescuing job: " + e.getMessage());
        }
    }
}