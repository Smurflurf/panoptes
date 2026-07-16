package panoptes.agent;

import java.util.List;
import org.springframework.stereotype.Service;

import panoptes.dto.DetailedResult;
import panoptes.dto.ValidatedResult;
import panoptes.llm.GeminiClient;

/**
 * The adversarial researcher.
 * <p>
 * Triggered by the {@link RedTeamAgent}, the AdversarialInvestigatorAgent searches for and extracts 
 * specific counter-evidence from the retrieved adversarial literature. It generates a highly focused 
 * "Contradiction Report" detailing methodological flaws or opposing findings with inline citations.
 * </p>
 */
@Service
public class AdversarialInvestigatorAgent extends AbstractAgent {

	public AdversarialInvestigatorAgent(GeminiClient geminiClient) {
    	super(geminiClient, "AdversarialInvestigator", """
    	        You are an adversarial scientific researcher. Your job is to extract evidence from the provided sources that CONTRADICTS, CHALLENGES, or ADDS NUANCE to prevailing theories.
    	        
    	        CRITICAL RULES:
    	        1. SOURCE WEIGHT: Pay close attention to citations and 'Peer Reviewed' status. If your counter-evidence comes from a 0-citation preprint, you MUST extract it as a 'weak, speculative counter-claim', not as a hard debunking!
    	        2. PARAPHRASE IN THE TEXT: Synthesize the contradictory findings in your own words. Do NOT use direct quotes in the visible text!
    	        3. PRESERVE METHODOLOGICAL CONTEXT & EXACT SUBJECT (CRITICAL): When extracting a counter-claim, you MUST include its exact conditions and subjects. If a finding occurred 'in parent-child interactions', 'in a 2012 human-robot game', or 'in a specific model organism', you MUST explicitly write this! Do not extract naked conclusions from specific human-to-human studies and present them generically as universal laws.
    	        4. INLINE XML CITATIONS: You MUST cite every claim using an XML <cite> tag. You MUST include a highly relevant, exact quote from the paper's abstract that supports your claim inside the 'quote' attribute. FORMAT EXACTLY LIKE THIS: This is the claim <cite id=\"[ID]\" quote=\"[Exact sentence from abstract]\"></cite>. Place the tag immediately after the specific finding it supports, not just at the end of the paragraph.
    	        5. Do NOT create a bibliography. Just write a focused 'Contradiction Report'.
    	        """);
    }

    public String investigateCounterClaims(String draftContext, String question, List<ValidatedResult> topSources, String language) {
        StringBuilder prompt = new StringBuilder("Original Draft Context to Challenge:\n" + draftContext + "\n\nAdversarial Question: " + question + "\n\nCounter-Sources:\n");
        for (ValidatedResult v : topSources) {
            DetailedResult r = v.originalResult();
            prompt.append("ID: ").append(r.id()).append("\n") 
                  .append("Title: ").append(r.title()).append("\n")
                  .append("Year: ").append(v.publicationYear() != null ? v.publicationYear() : "Unknown").append("\n")
                  .append("Peer Reviewed: ").append(v.isPeerReviewed() ? "YES" : "NO").append("\n")
                  .append("Citations: ").append(v.citationCount()).append("\n")
                  .append("Abstract: ").append(r.summary()).append("\n\n");
        }

        return executePonder(prompt.toString(), null, null, language);
    }
}