package panoptes.service.orchestrator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import panoptes.agent.CitationQaAgent;
import panoptes.agent.LogicalFallacyQaAgent;
import panoptes.agent.QaCorrectionPlannerAgent;
import panoptes.agent.RevisionAgent;
import panoptes.dto.PlanStep;
import panoptes.dto.ResearchContext;
import panoptes.dto.ValidatedResult;
import panoptes.util.CitationUtil;
import panoptes.web.JobService;
import panoptes.web.SseService;

/**
 * The orchestration layer dedicated to Quality Assurance (QA) and hallucination prevention.
 * <p>
 * This service enforces Panoptes' strict epistemic standards by deploying a "Panel of Auditors"
 * and managing an autonomous, iterative feedback and corrective research loop:
 * <ol>
 *   <li><b>Hard Java Check:</b> Verifies if the referenced ID actually exists in the local paper database. If it is fabricated, the claim instantly fails.</li>
 *   <li><b>Citation Fidelity:</b> Dispatches sources to the {@link panoptes.agent.CitationQaAgent} to ensure the drafted claim matches the original abstract without cherry-picking or concept shifting.</li>
 *   <li><b>Logical & Scale Audit:</b> Dispatches sources to the {@link panoptes.agent.LogicalFallacyQaAgent} to detect false equivalences, mismatched physical units, and scale conflations (e.g., cosmological FLRW metrics vs. local black hole interiors).</li>
 * </ol>
 * </p>
 * <p>
 * If a section fails the QA process, the orchestrator does not simply soften or delete the claims.
 * Instead, it triggers an autonomous corrective loop to actively resolve the issue:
 * <ul>
 *   <li>It invokes the {@link panoptes.agent.QaCorrectionPlannerAgent} to analyze the failed draft alongside the exact auditor feedback and generate targeted search queries.</li>
 *   <li>It dispatches these queries to the {@link panoptes.service.orchestrator.InvestigationOrchestrator} to execute standard searches and retrieve new empirical facts.</li>
 *   <li>It utilizes the {@link panoptes.agent.RevisionAgent} to rewrite the section, replacing hallucinated or weak claims with the newly retrieved, verified citations.</li>
 * </ul>
 * This corrective loop is repeated up to a configured threshold to ensure rigorous fact-checking and maximum report integrity.
 * </p>
 */
@Service
public class QaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(QaOrchestrator.class);
    
    private final CitationQaAgent citationQaAgent;
    private final LogicalFallacyQaAgent logicQaAgent;
    private final RevisionAgent revisionAgent;
    private final QaCorrectionPlannerAgent qaCorrectionPlanner;
    private final InvestigationOrchestrator investigationOrchestrator;
    private final JobService jobService;
    private final SseService sseService;

    public QaOrchestrator(CitationQaAgent citationQaAgent, LogicalFallacyQaAgent logicQaAgent, 
                          RevisionAgent revisionAgent, QaCorrectionPlannerAgent qaCorrectionPlanner,
                          @Lazy InvestigationOrchestrator investigationOrchestrator,
                          JobService jobService, SseService sseService) {
        this.citationQaAgent = citationQaAgent;
        this.logicQaAgent = logicQaAgent;
        this.revisionAgent = revisionAgent;
        this.qaCorrectionPlanner = qaCorrectionPlanner;
        this.investigationOrchestrator = investigationOrchestrator;
        this.jobService = jobService;
        this.sseService = sseService;
    }

    public String performSectionQa(ResearchContext context, String sectionTitle, String sectionContent) {
        String safeTitle = sectionTitle.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        String currentContent = sectionContent;
        int maxRetries = 3;
        boolean sectionPerfect = false;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            sseService.sendUpdate(context.getJobId(), "QA Panel (Attempt " + attempt + "/" + maxRetries + "): Verifying citations & logic for " + sectionTitle + "...");
            QaEvaluationResult result = evaluateContent(context, currentContent);
            
            if (!result.failed()) {
                jobService.saveArtifact(context.getJobId(), "QA/PASSED_" + safeTitle + "_Att" + attempt + ".txt", "Section: " + sectionTitle + "\n\n" + result.feedback());
                currentContent = result.cleanedContent();
                sectionPerfect = true;
                break; 
            } else {
                jobService.saveArtifact(context.getJobId(), "QA/FAILED_" + safeTitle + "_Att" + attempt + ".txt", "Section: " + sectionTitle + "\n\n" + result.feedback());
                log.warn("Section QA failed for {} on attempt {}:\n{}", sectionTitle, attempt, result.feedback());
                
                if (attempt == maxRetries) {
                    sseService.sendUpdate(context.getJobId(), "Max QA attempts reached. Forcing Editor to ruthlessly delete hallucinated claims...");
                    currentContent = revisionAgent.rewriteWithFacts(result.cleanedContent(), result.feedback(), List.of(), context.getLanguage());
                } else {
                    sseService.sendUpdate(context.getJobId(), "QA Failed! Auditor detected hallucination or gap. Triggering corrective research...");
                    List<PlanStep> correctiveSteps = qaCorrectionPlanner.planCorrection(currentContent, result.feedback(), context.getLanguage());
                    List<String> newFacts = investigationOrchestrator.executeStandardSearch(context, correctiveSteps);
                    
                    sseService.sendUpdate(context.getJobId(), "QA Research complete. Rewriting section with new empirical facts...");
                    currentContent = revisionAgent.rewriteWithFacts(result.cleanedContent(), result.feedback(), newFacts, context.getLanguage());
                }
            }
        }

        if (!sectionPerfect) {
            sseService.sendUpdate(context.getJobId(), "Finalizing section '" + sectionTitle + "' after forced correction.");
            QaEvaluationResult finalResult = evaluateContent(context, currentContent);
            jobService.saveArtifact(context.getJobId(), "QA/FINAL_FORCED_FIX_" + safeTitle + ".txt", "Section: " + sectionTitle + "\n\n" + finalResult.feedback());
        }

        return CitationUtil.enrichCiteTags(currentContent, context.getPaperDatabase());
    }

    public String performGlobalRedTeamQa(ResearchContext context, String assembledDraft) {
        String currentDraft = assembledDraft;
        int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            sseService.sendUpdate(context.getJobId(), "Final QA Pass (Attempt " + attempt + "/" + maxRetries + "): Verifying edited Red Team citations...");
            
            String[] sections = currentDraft.split("(?m)(?=^## )");
            StringBuilder repairedReport = new StringBuilder();
            StringBuilder globalLog = new StringBuilder();
            boolean anySectionFailed = false;

            for (String sectionText : sections) {
                if (sectionText.trim().isBlank()) continue;
                
                String header = "";
                String body = sectionText;
                if (sectionText.startsWith("## ")) {
                    int firstNewline = sectionText.indexOf('\n');
                    if (firstNewline != -1) {
                        header = sectionText.substring(0, firstNewline);
                        body = sectionText.substring(firstNewline).trim();
                    }
                }
                
                QaEvaluationResult result = evaluateContent(context, body);
                globalLog.append("--- SECTION FEEDBACK ---\n")
                         .append(result.feedback().isBlank() ? "No citations in this section.\n\n" : result.feedback() + "\n\n");

                String finalContent = result.cleanedContent();
                
                if (result.failed()) {
                    anySectionFailed = true;
                    sseService.sendUpdate(context.getJobId(), "Global QA found errors. Triggering corrective research...");
                    
                    List<PlanStep> correctiveSteps = qaCorrectionPlanner.planCorrection(finalContent, result.feedback(), context.getLanguage());
                    List<String> newFacts = investigationOrchestrator.executeStandardSearch(context, correctiveSteps);
                    
                    finalContent = revisionAgent.rewriteWithFacts(finalContent, result.feedback(), newFacts, context.getLanguage());
                }
                
                if (!header.isBlank()) {
                    repairedReport.append(header).append("\n\n");
                }
                repairedReport.append(finalContent).append("\n\n");
            }

            currentDraft = repairedReport.toString().trim();

            if (!anySectionFailed) {
                jobService.saveArtifact(context.getJobId(), "QA/PASSED_GLOBAL_RedTeam_Att" + attempt + ".txt", globalLog.toString());
                break; // Everything passed!
            } else {
                jobService.saveArtifact(context.getJobId(), "QA/FAILED_GLOBAL_RedTeam_Att" + attempt + ".txt", globalLog.toString());
            }
        }
        
        return currentDraft;
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
            Map<String, CitationQaAgent.QaEvaluation> citeEvals = citationQaAgent.verifyCitations(cleanedContent, batch, context.getInternalLanguage());
            Map<String, LogicalFallacyQaAgent.QaEvaluation> logicEvals = logicQaAgent.verifyLogic(cleanedContent, batch, context.getInternalLanguage());
            
            for (ValidatedResult vr : batch) {
                String paperId = vr.originalResult().id();
                var citeEval = citeEvals.get(paperId);
                var logicEval = logicEvals.get(paperId);
                
                boolean isCiteValid = citeEval != null && citeEval.valid();
                boolean isLogicValid = logicEval != null && logicEval.valid();
                
                // 1. Status (FAILED oder PASSED)
                if (!isCiteValid || !isLogicValid) {
                    failed = true;
                    feedback.append("- ID [").append(paperId).append("] -> Valid: FALSE\n");
                } else {
                    feedback.append("- ID [").append(paperId).append("] -> Valid: TRUE\n");
                }
                
                // 2. Save the QA citation agents feedback log
                if (citeEval != null) {
                    feedback.append("  Citation Feedback: ").append(citeEval.reason()).append("\n");
                } else {
                    feedback.append("  Citation Feedback: [No feedback received]\n");
                }
                
                // 3. Save the QA logic agents feedback log
                if (logicEval != null) {
                    feedback.append("  Logical Feedback: ").append(logicEval.reason()).append("\n");
                } else {
                    feedback.append("  Logical Feedback: [No feedback received]\n");
                }
                
                feedback.append("\n");
            }
        }
        
        return new QaEvaluationResult(failed, feedback.toString().trim(), cleanedContent);
    }
    
    private record QaEvaluationResult(boolean failed, String feedback, String cleanedContent) {}
}