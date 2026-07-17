package panoptes.agent;

import java.util.List;

import org.springframework.stereotype.Service;

import panoptes.llm.GeminiClient;

/**
 * The dedicated editor for correcting hallucinations.
 * <p>
 * When the {@link CitationQaAgent} flags claims as invalid or unsupported, the RevisionAgent 
 * steps in. It receives the faulty draft alongside the auditor's brutal feedback and rewrites 
 * the text to align with reality; either by softening the claim, correcting the context, 
 * or removing the hallucinated citation entirely.
 * </p>
 */

@Service
public class RevisionAgent extends AbstractAgent {

    public RevisionAgent(GeminiClient geminiClient) {
        super(geminiClient, "RevisionEditor", """
        You are a senior academic editor correcting a drafted section based on strict Quality Assurance (QA) feedback.
        
        CRITICAL RULES:
        1. You will receive a drafted section of text and a list of QA failures (e.g., "ID 123 is hallucinated" or "ID 456 doesn't support the claim").
        2. If a claim is flagged as unsupported or hallucinated, you MUST rewrite the sentence to reflect reality, or REMOVE the claim and its citation entirely.
        3. DO NOT alter or remove citations that were NOT flagged in the QA feedback.
        4. KEEP the XML citation format EXACTLY as: <cite id=\"[ID]\" quote=\"[Exact sentence from abstract]\"></cite>.
        5. Return ONLY the rewritten section text. No conversational filler, no title.
        """);
    }

    public String rewrite(String sectionContent, String qaFeedback, String language) {
        String prompt = "--- ORIGINAL DRAFTED SECTION ---\n" + sectionContent + 
                        "\n\n--- QA AUDITOR FEEDBACK (FIX THESE ERRORS) ---\n" + qaFeedback +
                        "\n\nTask: Rewrite the section to fix the errors mentioned in the QA feedback. Remove or adjust invalid claims. Output only the new markdown text.";
                        
        return executePonder(prompt, null, null, language);
    }
    
    public String rewriteWithFacts(String sectionContent, String qaFeedback, List<String> newFacts, String language) {
        StringBuilder prompt = new StringBuilder("--- ORIGINAL DRAFTED SECTION ---\n")
                .append(sectionContent)
                .append("\n\n--- QA AUDITOR FEEDBACK (FIX THESE ERRORS) ---\n")
                .append(qaFeedback);

        if (newFacts != null && !newFacts.isEmpty()) {
            prompt.append("\n\n--- NEW CORRECTIVE FACTS GATHERED BY QA RESEARCH ---\n");
            for (int i = 0; i < newFacts.size(); i++) {
                prompt.append("New Fact ").append(i + 1).append(":\n").append(newFacts.get(i)).append("\n\n");
            }
            prompt.append("\nTask: Rewrite the section to fix the errors mentioned in the feedback. Use the NEW CORRECTIVE FACTS to replace the hallucinated/false claims with real empirical evidence. You MUST cite the new facts using their provided <cite> tags. Output only the new markdown text.");
        } else {
            prompt.append("\nTask: Rewrite the section to fix the errors. Remove invalid claims. Output only the new markdown text.");
        }

        return executePonder(prompt.toString(), null, null, language);
    }
}