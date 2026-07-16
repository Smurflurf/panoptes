package panoptes.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import panoptes.service.ResearchPipelineService.UploadedFile;

/**
 * A central state-holder object representing the context of an ongoing research job.
 * <p>
 * Instead of passing dozens of parameters (like jobId, language, thread-safe databases) 
 * through every method signature, this context object encapsulates the entire state of 
 * a pipeline run. It holds the original user inputs, the derived core idea, and the 
 * globally shared, thread-safe paper databases used by the asynchronous agents.
 * </p>
 */
public class ResearchContext {
    private final String jobId;
    private final String language;
    private final String rawInput;
    private final List<UploadedFile> files;

    // Global, thread-safe databases for the current job
    private final Map<String, ValidatedResult> paperDatabase = new ConcurrentHashMap<>();
    private final Set<String> usedPaperIds = ConcurrentHashMap.newKeySet();
    private final Set<String> globallySeenDois = ConcurrentHashMap.newKeySet();

    private String coreIdea;

    public ResearchContext(String jobId, String language, String rawInput, List<UploadedFile> files) {
        this.jobId = jobId;
        this.language = language;
        this.rawInput = rawInput;
        this.files = files;
    }

    public String getJobId() { return jobId; }
    public String getLanguage() { return language; }
    public String getRawInput() { return rawInput; }
    public List<UploadedFile> getFiles() { return files; }
    
    public Map<String, ValidatedResult> getPaperDatabase() { return paperDatabase; }
    public Set<String> getUsedPaperIds() { return usedPaperIds; }
    public Set<String> getGloballySeenDois() { return globallySeenDois; }

    public String getCoreIdea() { return coreIdea; }
    public void setCoreIdea(String coreIdea) { this.coreIdea = coreIdea; }
}