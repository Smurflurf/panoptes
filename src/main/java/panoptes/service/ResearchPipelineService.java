package panoptes.service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Part;

import panoptes.agent.CoherenceAgent;
import panoptes.agent.EpistemicEditorAgent;
import panoptes.agent.IdeaExtractionAgent;
import panoptes.agent.IdeaExtractionAgent.ExtractedIdea;
import panoptes.agent.ImplicationAgent;
import panoptes.agent.OutlineAgent;
import panoptes.agent.OutlineAgent.SectionPlan;
import panoptes.agent.PlanAgent;
import panoptes.agent.RedTeamAgent;
import panoptes.agent.SectionWriterAgent;
import panoptes.agent.SilverPlateAgent;
import panoptes.agent.TldrAgent;
import panoptes.dto.PlanStep;
import panoptes.dto.ResearchContext;
import panoptes.llm.GeminiClient;
import panoptes.service.orchestrator.InvestigationOrchestrator;
import panoptes.service.orchestrator.QaOrchestrator;
import panoptes.web.JobService;
import panoptes.web.SseService;

/**
 * The master orchestrator of the Panoptes multi-agent research system.
 * <p>
 * This service controls the high-level macro-lifecycle of a research job. By delegating 
 * heavy asynchronous fetching to the {@link panoptes.service.orchestrator.InvestigationOrchestrator} 
 * and hallucination checks to the {@link panoptes.service.orchestrator.QaOrchestrator}, this class 
 * remains a clean, highly readable linear blueprint of the research process.
 * </p>
 * <p>
 * It executes the fundamental phases sequentially: Discovery, Deep Search, Coherence Filtering, 
 * Structural Outlining, Drafting, Adversarial Red Teaming (Epistemic Editing), and Final Synthesis.
 * </p>
 */
@Service
public class ResearchPipelineService {
    
    private static final Logger log = LoggerFactory.getLogger(ResearchPipelineService.class);
    
    public record UploadedFile(String filename, String mimeType, byte[] data) {}
    
    @Value("${panoptes.llm.internal-language:Scientific English}")
    private String internalLanguage;
    
    // Core Agents
    private final IdeaExtractionAgent ideaExtractionAgent;
    private final PlanAgent planAgent;
    private final ImplicationAgent implicationAgent;
    private final CoherenceAgent coherenceAgent; 
    private final OutlineAgent outlineAgent; 
    private final SectionWriterAgent sectionWriterAgent; 
    private final RedTeamAgent redTeamAgent;
    private final EpistemicEditorAgent epistemicEditorAgent;
    private final SilverPlateAgent silverPlateAgent;
    private final TldrAgent tldrAgent;
    
    // Orchestrators & Infrastructure
    private final InvestigationOrchestrator searchOrchestrator;
    private final QaOrchestrator qaOrchestrator;
    private final GeminiClient geminiClient;
    private final SseService sseService;
    private final JobService jobService;
    private final ObjectMapper objectMapper; 

    public ResearchPipelineService(IdeaExtractionAgent ideaExtractionAgent, PlanAgent planAgent,
                                   ImplicationAgent implicationAgent, CoherenceAgent coherenceAgent,
                                   OutlineAgent outlineAgent, SectionWriterAgent sectionWriterAgent, 
                                   RedTeamAgent redTeamAgent, EpistemicEditorAgent epistemicEditorAgent,
                                   SilverPlateAgent silverPlateAgent, TldrAgent tldrAgent,
                                   InvestigationOrchestrator searchOrchestrator, QaOrchestrator qaOrchestrator,
                                   GeminiClient geminiClient, SseService sseService, 
                                   JobService jobService, ObjectMapper objectMapper) { 
        this.ideaExtractionAgent = ideaExtractionAgent;
        this.planAgent = planAgent;
        this.implicationAgent = implicationAgent;
        this.coherenceAgent = coherenceAgent;
        this.outlineAgent = outlineAgent;
        this.sectionWriterAgent = sectionWriterAgent;
        this.redTeamAgent = redTeamAgent;
        this.epistemicEditorAgent = epistemicEditorAgent;
        this.silverPlateAgent = silverPlateAgent;
        this.tldrAgent = tldrAgent;
        this.searchOrchestrator = searchOrchestrator;
        this.qaOrchestrator = qaOrchestrator;
        this.geminiClient = geminiClient;
        this.sseService = sseService;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    @Async
    public void executeDeepResearchAsync(String jobId, String rawInput, List<UploadedFile> files, String language) {
        // Set the log file context for this main thread
        MDC.put("jobId", jobId);
        long startTime = System.currentTimeMillis();
        
        ResearchContext context = new ResearchContext(jobId, language, internalLanguage, rawInput, files);

        try {
            saveInputs(context);

            // ==========================================
            // PHASE 1: DISCOVERY & EXTRACTION
            // ==========================================
            sseService.sendUpdate(jobId, "Extracting core idea and context...");
            List<Part> parts = prepareParts(files);
            ExtractedIdea idea = ideaExtractionAgent.process(rawInput, parts, language); 
            context.setCoreIdea(idea.synthesisedIdea());
            
            jobService.saveArtifact(jobId, "00_cleaned_transcription.txt", idea.cleanedTranscription());
            jobService.saveArtifact(jobId, "00_core_idea.txt", context.getCoreIdea());
            
            // ==========================================
            // PHASE 2: DEEP SEARCH & INVESTIGATION
            // ==========================================
            sseService.sendUpdate(jobId, "Generating specific research plan...");
            List<PlanStep> phase1Steps = planAgent.plan("User Request: " + rawInput + "\nExtracted Core Idea: " + context.getCoreIdea(), language);
            jobService.saveArtifact(jobId, "01_phase1_plan.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(phase1Steps));
            
            sseService.sendUpdate(jobId, "Executing Phase 1 deep search...");
            List<String> phase1Facts = searchOrchestrator.executeStandardSearch(context, phase1Steps);

            sseService.sendUpdate(jobId, "Generating implication steps for deeper insights...");
            List<PlanStep> phase2Steps = implicationAgent.deriveImplications("Raw facts gathered so far:\n" + String.join("\n", phase1Facts), language);
            jobService.saveArtifact(jobId, "02_phase2_implications.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(phase2Steps));
            
            sseService.sendUpdate(jobId, "Executing Phase 2 deep search...");
            List<String> phase2Facts = searchOrchestrator.executeStandardSearch(context, phase2Steps);

            List<String> allFacts = new ArrayList<>(phase1Facts);
            allFacts.addAll(phase2Facts);

            // ==========================================
            // PHASE 3: COHERENCE CHECK
            // ==========================================
            sseService.sendUpdate(jobId, "Coherence Check: Pruning forced tangents and conceptual overreach...");
            List<Integer> coherentIndices = coherenceAgent.filterCoherentFacts(context.getCoreIdea(), allFacts, context.getInternalLanguage());
            List<String> coherentFacts = filterCoherentFacts(allFacts, coherentIndices, jobId);

            // ==========================================
            // PHASE 4: OUTLINING & DRAFTING
            // ==========================================
            sseService.sendUpdate(jobId, "Architecting the final report structure...");
            List<SectionPlan> outline = outlineAgent.createOutline(context.getCoreIdea(), coherentFacts, language);
            jobService.saveArtifact(jobId, "03_outline.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(outline));

            sseService.sendUpdate(jobId, "Drafting " + outline.size() + " sections sequentially...");
            String assembledDraft = draftAndVerifySections(context, outline, coherentFacts);

            // ==========================================
            // PHASE 5: ADVERSARIAL RED TEAMING
            // ==========================================
            sseService.sendUpdate(jobId, "Red Teaming: Sighting draft for weaknesses...");
            List<PlanStep> adversarialSteps = redTeamAgent.analyzeAndPlan(context.getCoreIdea(), assembledDraft, language);
            String finalReportText = assembledDraft;

            if (adversarialSteps != null && !adversarialSteps.isEmpty()) {
                jobService.saveArtifact(jobId, "04_red_team_plan.json", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(adversarialSteps));
                sseService.sendUpdate(jobId, "Devil's Advocate: Executing counter-research on " + adversarialSteps.size() + " weak points...");
                
                // New dedicated search loop for counter-evidence
                List<String> contradictionReports = searchOrchestrator.executeAdversarialSearch(context, adversarialSteps, assembledDraft);

                if (!contradictionReports.isEmpty()) {
                    sseService.sendUpdate(jobId, "Epistemic Editing: Weaving counter-evidence and scientific debate into the draft...");
                    String editedDraft = epistemicEditorAgent.edit(assembledDraft, contradictionReports, language);
                    
                    // Final Global QA Pass (After Red Teaming)
                    finalReportText = qaOrchestrator.performGlobalRedTeamQa(context, editedDraft);
                } else {
                    sseService.sendUpdate(jobId, "Red Teaming complete: No robust counter-evidence found. Draft stands.");
                }
            }

            // ==========================================
            // PHASE 6: FINALIZATION & FORMATTING
            // ==========================================
            finalizeReport(context, finalReportText, startTime);

        } catch (Throwable t) { 
            handleCrash(jobId, t);
        } finally {
            if (geminiClient != null) {
                geminiClient.cleanupStats(jobId);
            }
            MDC.remove("jobId");
        }
    }

    private String draftAndVerifySections(ResearchContext context, List<SectionPlan> outline, List<String> coherentFacts) {
        List<String> writtenSections = new ArrayList<>();
        StringBuilder previousDraft = new StringBuilder();

        for (int i = 0; i < outline.size(); i++) {
            SectionPlan currentPlan = outline.get(i);
            
            // Pass all plans that come AFTER the current section
            List<SectionPlan> upcomingPlans = outline.subList(i + 1, outline.size());
            
            sseService.sendUpdate(context.getJobId(), "Drafting section: " + currentPlan.section_title());
            
            // Pass the full text history, the current plan, and the future outline
            String sectionContent = sectionWriterAgent.writeSection(currentPlan, upcomingPlans, previousDraft.toString(), coherentFacts, context.getLanguage());

            // Run strict QA on the written section
            String verifiedContent = qaOrchestrator.performSectionQa(context, currentPlan.section_title(), sectionContent);
            
            String formattedSection = "## " + currentPlan.section_title() + "\n\n" + verifiedContent;
            writtenSections.add(formattedSection);
            previousDraft.append(formattedSection).append("\n\n");
        }
        
        return String.join("\n\n", writtenSections);
    }

    private void finalizeReport(ResearchContext context, String finalReportText, long startTime) throws Exception {
        String jobId = context.getJobId();
        
        // 1. FIRST: Silver Plate Agent (Executive Answer)
        sseService.sendUpdate(jobId, "Formulating Executive Answer...");
        SilverPlateAgent.SilverResult silverResult = silverPlateAgent.deliverAnswer(context.getCoreIdea(), finalReportText, context.getLanguage());
        jobService.saveArtifact(jobId, "05_silver_plate_reasoning.txt", "Reasoning:\n" + silverResult.reasoning());

        // 2. THEN: TLDR Agent (Focusing on the Executive Answer)
        sseService.sendUpdate(jobId, "Drafting executive summary (TLDR)...");
        String tldrContext = "EXECUTIVE ANSWER TO THE RESEARCH QUESTION:\n" + silverResult.executiveAnswer() + "\n\nFULL REPORT CONTEXT:\n" + finalReportText;
        TldrAgent.TldrResult tldrResult = tldrAgent.generateTldr(tldrContext, context.getLanguage());
        jobService.saveArtifact(jobId, "title.txt", tldrResult.shortTitle());

        // 3. Assembly
        StringBuilder finalReportBuilder = new StringBuilder();
        
        // TLDR
        finalReportBuilder.append("<div class=\"tldr-box\"><strong>TL;DR:</strong> ").append(tldrResult.tldr()).append("</div>\n\n");
        
        // Silver Plate Answer
        if (!silverResult.executiveAnswer().isBlank()) {
            sseService.sendUpdate(jobId, "Adding Executive Answer to draft...");
            finalReportBuilder.append("> **Research Question:** *").append(context.getCoreIdea().trim().replace("\n", " "))
                              .append("*\n>\n> **Executive Answer:**\n> ")
                              .append(silverResult.executiveAnswer().replace("\n", "\n> ")).append("\n\n---\n\n");
        }
        
        // Main Text
        finalReportBuilder.append(finalReportText);
        String finalWithTldr = finalReportBuilder.toString();
        jobService.saveArtifact(jobId, "06_raw_draft_with_headers.md", finalWithTldr);

        log.debug("Exporting complete literature database...");
        
        // Transform all collected and verified papers into a clean JSON
        String bibliographyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context.getPaperDatabase().values());
        
        // Save the complete database in the main folder
        jobService.saveArtifact(jobId, "bibliography_database.json", bibliographyJson);
        
        sseService.sendUpdate(jobId, "Formatting citations, quotes and bibliography...");
        String finalFormattedReport = CitationFormatter.formatReport(finalWithTldr, context.getPaperDatabase());
        jobService.saveResult(jobId, finalFormattedReport);
        
        long durationSecs = (System.currentTimeMillis() - startTime) / 1000;
        int totalCalls = geminiClient.getCallCount(jobId);
        int totalRetries = geminiClient.getRetryCount(jobId);
        
        String statsJson = String.format("{\n  \"duration_seconds\": %d,\n  \"total_llm_calls\": %d,\n  \"total_retries\": %d,\n  \"papers_analyzed\": %d\n}", 
                durationSecs, totalCalls, totalRetries, context.getPaperDatabase().size());
        jobService.saveArtifact(jobId, "execution_stats.json", statsJson);
        
        sseService.sendComplete(jobId);
    }

    private void saveInputs(ResearchContext context) {
        // ZUERST: RAW TEXT INPUT in the /input/ folder
        jobService.saveArtifact(context.getJobId(), "input/00_raw_input.txt", "Raw Input:\n" + context.getRawInput());
        
        // ZWEITENS: ALL FILES (Audio, PDF) in the /input/ folder
        if (context.getFiles() != null) {
            for (UploadedFile file : context.getFiles()) {
                String safeName = file.filename() != null ? file.filename() : "upload.bin";
                jobService.saveBinaryArtifact(context.getJobId(), "input/" + safeName, file.data());
            }
        }
    }

    private List<Part> prepareParts(List<UploadedFile> files) {
        List<Part> parts = new ArrayList<>();
        if (files != null) {
            for (UploadedFile file : files) {
                String mimeType = file.mimeType() != null ? file.mimeType() : "application/octet-stream";
                parts.add(Part.fromBytes(file.data(), mimeType));
            }
        }
        return parts;
    }

    private List<String> filterCoherentFacts(List<String> allFacts, List<Integer> coherentIndices, String jobId) {
        List<String> coherentFacts = new ArrayList<>();
        List<String> droppedFacts = new ArrayList<>(); // List for deleted facts
        
        for (int i = 0; i < allFacts.size(); i++) {
            if (coherentIndices.contains(i)) {
                coherentFacts.add(allFacts.get(i));
            } else {
                droppedFacts.add(allFacts.get(i)); // Save what was thrown out
            }
        }
        
        // Fallback: If the LLM was too strict and deleted everything, keep all facts
        if (coherentFacts.isEmpty()) {
            coherentFacts.addAll(allFacts);
            droppedFacts.clear(); // Nothing was deleted
        }

        // Better report for the file
        StringBuilder coherenceReport = new StringBuilder();
        coherenceReport.append("Kept ").append(coherentFacts.size()).append(" out of ").append(allFacts.size()).append(" facts.\n\n");

        if (!droppedFacts.isEmpty()) {
            coherenceReport.append("--- PRUNED / DROPPED FACTS (Rejected by CoherenceAgent) ---\n\n");
            for (int i = 0; i < droppedFacts.size(); i++) {
                coherenceReport.append("Dropped Fact ").append(i + 1).append(":\n").append(droppedFacts.get(i)).append("\n\n");
            }
        } else {
            coherenceReport.append("No facts were dropped. All were deemed relevant.");
        }

        // Save the detailed report
        jobService.saveArtifact(jobId, "02.5_coherence_filtered_facts.txt", coherenceReport.toString());
        return coherentFacts;
    }

    private void handleCrash(String jobId, Throwable t) {
        log.error("CRITICAL PIPELINE ERROR: " + t.getMessage(), t);
        sseService.sendUpdate(jobId, "ERROR: Pipeline failed - " + t.getMessage());
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String errorReport = "Error Message: " + t.getMessage() + "\n\nStacktrace:\n" + sw.toString();
        jobService.saveArtifact(jobId, "error.log", errorReport);
        sseService.sendComplete(jobId);
    }
}