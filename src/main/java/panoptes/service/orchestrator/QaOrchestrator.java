package panoptes.service.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import panoptes.agent.CitationQaAgent;
import panoptes.agent.LogicalFallacyQaAgent;
import panoptes.agent.RevisionAgent;
import panoptes.dto.ResearchContext;
import panoptes.dto.ValidatedResult;
import panoptes.util.CitationUtil;
import panoptes.web.JobService;
import panoptes.web.SseService;

/**
 * The orchestration layer dedicated to Quality Assurance (QA) and hallucination prevention.
 * <p>
 * This service enforces Panoptes' strict epistemic standards by deploying a "Panel of Auditors".
 * It parses drafted sections, extracts all referenced paper IDs, and performs a ruthless verification process:
 * <ol>
 *   <li><b>Hard Java Check:</b> Verifies if the referenced ID actually exists in the local paper database. If it is fabricated, the claim instantly fails.</li>
 *   <li><b>Citation Fidelity:</b> Dispatches sources to the {@link panoptes.agent.CitationQaAgent} to ensure the drafted claim matches the original abstract without cherry-picking or concept shifting.</li>
 *   <li><b>Logical & Scale Audit:</b> Dispatches sources to the {@link panoptes.agent.LogicalFallacyQaAgent} to detect false equivalences, mismatched physical units, and scale conflations (e.g., evolution vs. real-time behavior).</li>
 * </ol>
 * If a section fails QA, this orchestrator automatically triggers the {@link panoptes.agent.RevisionAgent} 
 * to rewrite the text and logs the exact failure reasons to the filesystem for transparency.
 * </p>
 */
@Service
public class QaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(QaOrchestrator.class);
    
    private final CitationQaAgent citationQaAgent;
    private final LogicalFallacyQaAgent logicQaAgent;
    private final RevisionAgent revisionAgent;
    private final JobService jobService;
    private final SseService sseService;

    public QaOrchestrator(CitationQaAgent citationQaAgent, LogicalFallacyQaAgent logicQaAgent, 
                          RevisionAgent revisionAgent, JobService jobService, SseService sseService) {
        this.citationQaAgent = citationQaAgent;
        this.logicQaAgent = logicQaAgent;
        this.revisionAgent = revisionAgent;
        this.jobService = jobService;
        this.sseService = sseService;
    }

    public String performSectionQa(ResearchContext context, String sectionTitle, String sectionContent) {
        sseService.sendUpdate(context.getJobId(), "QA Panel: Verifying citations & logic for " + sectionTitle + "...");
        
        QaEvaluationResult result = evaluateContent(context, sectionContent);
        
        String safeTitle = sectionTitle.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        String finalContent = result.cleanedContent();
        
        if (result.failed()) {
            sseService.sendUpdate(context.getJobId(), "QA Failed! Rewriting section based on auditor panel feedback...");
            
            jobService.saveArtifact(context.getJobId(), "QA/FAILED_" + safeTitle + ".txt", "Section: " + sectionTitle + "\n\n" + result.feedback());
            log.warn("Section QA failed for {}:\n{}", sectionTitle, result.feedback());
            
            finalContent = revisionAgent.rewrite(finalContent, result.feedback(), context.getLanguage());
            jobService.saveArtifact(context.getJobId(), "QA/FIXED_" + safeTitle + ".md", finalContent);
        } else {
            jobService.saveArtifact(context.getJobId(), "QA/PASSED_" + safeTitle + ".txt", "Section: " + sectionTitle + "\n\n" + result.feedback());
        }

        return CitationUtil.enrichCiteTags(finalContent, context.getPaperDatabase());
    }

    public String performGlobalRedTeamQa(ResearchContext context, String assembledDraft) {
        sseService.sendUpdate(context.getJobId(), "Final QA Pass: Verifying edited Red Team citations & logic...");
        
        String[] sections = assembledDraft.split("(?m)(?=^## )");
        StringBuilder repairedReport = new StringBuilder();
        StringBuilder globalLog = new StringBuilder();
        boolean anySectionFailed = false;

        for (String sectionText : sections) {
            if (sectionText.trim().isBlank()) continue;
            
            QaEvaluationResult result = evaluateContent(context, sectionText);
            
            globalLog.append("--- SECTION FEEDBACK ---\n")
                     .append(result.feedback().isBlank() ? "No citations in this section.\n\n" : result.feedback() + "\n\n");

            String finalContent = result.cleanedContent();
            
            if (result.failed()) {
                anySectionFailed = true;
                sseService.sendUpdate(context.getJobId(), "Final QA: Fixing logical errors and hallucinated claims...");
                finalContent = revisionAgent.rewrite(finalContent, result.feedback(), context.getLanguage());
            }
            
            finalContent = CitationUtil.enrichCiteTags(finalContent, context.getPaperDatabase());
            repairedReport.append(finalContent).append("\n\n");
        }

        String finalReportText = repairedReport.toString().trim();

        if (anySectionFailed) {
            jobService.saveArtifact(context.getJobId(), "QA/FAILED_GLOBAL_RedTeam.txt", globalLog.toString());
            jobService.saveArtifact(context.getJobId(), "QA/FIXED_GLOBAL_RedTeam.md", finalReportText);
        } else {
            jobService.saveArtifact(context.getJobId(), "QA/PASSED_GLOBAL_RedTeam.txt", globalLog.toString());
        }

        return finalReportText;
    }
    
    private QaEvaluationResult evaluateContent(ResearchContext context, String content) {
        Pattern p = Pattern.compile("<cite id=\"([^\"]+)\"");
        Matcher m = p.matcher(content);
        List<String> usedIds = new ArrayList<>();
        String cleanedContent = content;
        
        while (m.find()) {
            String rawId = m.group(1);
            String cleanId = rawId.replace("–", "-").replace("—", "-");
            usedIds.add(cleanId);
            
            if (!rawId.equals(cleanId)) {
                cleanedContent = cleanedContent.replace(rawId, cleanId);
            }
        }

        List<ValidatedResult> sectionSources = new ArrayList<>();
        boolean failed = false;
        StringBuilder feedback = new StringBuilder();

        for (String id : usedIds.stream().distinct().toList()) {
            ValidatedResult vr = context.getPaperDatabase().get(id);
            if (vr != null) {
                sectionSources.add(vr); 
            } else {
                failed = true;
                feedback.append("- ID [").append(id).append("] -> Valid: false\n")
                        .append("  Reason: CRITICAL ERROR! This ID is hallucinated and does not exist in the database. You MUST completely remove the claim and the citation associated with it.\n\n");
            }
        }

        int batchSize = 30;
        for (int j = 0; j < sectionSources.size(); j += batchSize) {
            List<ValidatedResult> batch = sectionSources.subList(j, Math.min(j + batchSize, sectionSources.size()));
            
            // DEPLOY THE PANEL OF EXPERTS
            Map<String, CitationQaAgent.QaEvaluation> citeEvals = citationQaAgent.verifyCitations(cleanedContent, batch, context.getLanguage());
            Map<String, LogicalFallacyQaAgent.QaEvaluation> logicEvals = logicQaAgent.verifyLogic(cleanedContent, batch, context.getLanguage());
            
            for (ValidatedResult vr : batch) {
                String paperId = vr.originalResult().id();
                var citeEval = citeEvals.get(paperId);
                var logicEval = logicEvals.get(paperId);
                
                boolean isCiteValid = citeEval != null && citeEval.valid();
                boolean isLogicValid = logicEval != null && logicEval.valid();
                
                if (!isCiteValid || !isLogicValid) {
                    failed = true;
                    feedback.append("- ID [").append(paperId).append("] -> Valid: false\n");
                    if (!isCiteValid && citeEval != null) {
                        feedback.append("  Citation Error: ").append(citeEval.reason()).append("\n");
                    }
                    if (!isLogicValid && logicEval != null) {
                        feedback.append("  Logic Error: ").append(logicEval.reason()).append("\n");
                    }
                    feedback.append("\n");
                } else {
                    feedback.append("- ID [").append(paperId).append("] -> Valid: true\n")
                            .append("  Reason: Passed both Citation Fidelity and Logical/Physics Audit.\n\n");
                }
            }
        }
        
        return new QaEvaluationResult(failed, feedback.toString().trim(), cleanedContent);
    }
    
    private record QaEvaluationResult(boolean failed, String feedback, String cleanedContent) {}
}