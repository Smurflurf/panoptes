package panoptes.web;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A dedicated file management service for persisting research job data and artifacts.
 * <p>
 * The Panoptes pipeline generates a massive amount of intermediate data (JSON plans, raw drafts, 
 * QA feedback logs, and bibliography databases). This service writes these artifacts to the local 
 * filesystem under a unique {@code jobId} directory. This persistent storage ensures that:
 * <ul>
 *   <li>Final reports remain accessible via permanent links.</li>
 *   <li>The pipeline can be debugged by inspecting the exact input/output of every agent.</li>
 *   <li>Crashed or interrupted jobs can be rescued and resumed from the latest artifact.</li>
 * </ul>
 * </p>
 */
@Service
public class JobService {
    
    private static final String RESULTS_DIR = "results";

    // Saves the finished markdown report
    public void saveResult(String jobId, String markdown) {
        saveArtifact(jobId, "report.md", markdown);
    }

    public String getTitle(String jobId) {
        Path path = Paths.get(RESULTS_DIR, jobId, "title.txt");
        if (Files.exists(path)) {
            try {
                String title = Files.readString(path, StandardCharsets.UTF_8).trim();
                return title.isBlank() ? "Final Report" : title;
            } catch (IOException e) {
                return "Final Report";
            }
        }
        return "Final Report";
    }
    
    // Reads reports from secondary storage (for persistent URLs)
    public String getResult(String jobId) {
        Path path = Paths.get(RESULTS_DIR, jobId, "report.md");
        if (Files.exists(path)) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("Could not read report for job: " + jobId);
            }
        }
        return null;
    }

    // Saves metadata/artifacts (i.e. JSON)
    public void saveArtifact(String jobId, String filename, String content) {
        try {
            Path dir = Paths.get(RESULTS_DIR, jobId);
            Path file = dir.resolve(filename);
            
            // Generates sub folders
            if (!Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }
            
            Files.writeString(file, content != null ? content : "", StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Failed to write artifact " + filename + " for job " + jobId + ": " + e.getMessage());
        }
    }
    
    // Saves binary data (i.e audio/PDFs)
    public void saveBinaryArtifact(String jobId, String filename, byte[] content) {
        try {
            Path file = Paths.get(RESULTS_DIR, jobId, filename);
            
            // Generates sub folders
            if (!Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, content);
        } catch (IOException e) {
            System.err.println("Failed to write binary artifact " + filename + " for job " + jobId + ": " + e.getMessage());
        }
    }
    
    // Saves all originally uploaded data (from the user request) into a sub folder
    public void saveFile(String jobId, String filename, byte[] content) {
        try {
            Path dir = Paths.get(RESULTS_DIR, jobId, "inputs");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path file = dir.resolve(filename);
            Files.write(file, content);
        } catch (IOException e) {
            System.err.println("Failed to save binary file " + filename + " for job " + jobId);
        }
    }
}