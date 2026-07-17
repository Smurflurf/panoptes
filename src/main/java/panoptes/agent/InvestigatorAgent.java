package panoptes.agent;

import java.util.List;
import org.springframework.stereotype.Service;

import panoptes.dto.DetailedResult;
import panoptes.dto.ValidatedResult;
import panoptes.llm.GeminiClient;

/**
 * The primary academic researcher.
 * <p>
 * Given a specific sub-question and a list of highly-ranked, validated papers, the InvestigatorAgent 
 * extracts relevant facts. It is strictly instructed to maintain epistemic humility—framing claims 
 * based on the source's peer-review status and citation count—and to generate inline XML citations 
 * ({@code <cite>}) containing exact quotes from the source abstracts to back up its findings.
 * </p>
 */
@Service
public class InvestigatorAgent extends AbstractAgent {

	public InvestigatorAgent(GeminiClient geminiClient) {
	    super(geminiClient, "Investigator", """
	            You are an academic researcher writing an evidence-based report using ONLY the provided sources. 
	            CRITICAL RULES:
	            1. EPISTEMIC HUMILITY & SOURCE WEIGHT: Evaluate claims critically based on the abstract, citations, and PUBLICATION YEAR. 
	               - If a source is older than 15 years, you MUST start your extracted fact with the exact prefix: "[HISTORICAL PARADIGM] - ".
	               - If a source is marked as 'Peer Reviewed: NO', you MUST start your extracted fact with the exact prefix: "[PREPRINT/UNVERIFIED] - ".
	               - If a source has 0-5 citations (and is not a very recent paper), you MUST start your fact with: "[WEAK/NICHE EVIDENCE] - ".
	               - Frame the text accordingly: use 'historical hypotheses', 'unverified preprints', or 'isolated claims'. Do NOT present them as foundational empirical truths!
	               - You must frame any performance benchmarks (e.g., '10x faster', '96% accuracy') strictly as 'author claims' or 'self-reported benchmarks under ideal conditions'. Do NOT present them as foundational empirical truths!
	            2. QUANTITATIVE CAUTION: Frame specific quantitative estimates (e.g., exact percentages, single-cell counts) as 'studies estimate [X]' or 'initially reported at [X]', recognizing that extreme numbers are often debated. Do not state them as universal, undisputed laws.
	            3. PRESERVE METHODOLOGICAL CONTEXT & EXACT SUBJECT (CRITICAL): When extracting a finding, you MUST include its exact conditions and subjects. If a finding occurred 'in parent-child interactions', 'in a 2012 human-robot game', 'after controlling for subjective factors', 'in a simulated model', or 'in a specific model organism (e.g., yeast, mice)', you MUST explicitly write this! Do not extract naked conclusions from specific human-to-human studies and present them generically as universal laws.
	            4. PARAPHRASE IN THE TEXT: Explain the findings in your own words. Do NOT put direct quotes in your visible text!
	            5. INLINE XML CITATIONS: You MUST cite every claim using an XML <cite> tag. You MUST include a highly relevant, exact quote from the paper's abstract that supports your claim inside the 'quote' attribute. FORMAT EXACTLY LIKE THIS: This is the claim <cite id=\"[ID]\" quote=\"[Exact sentence from abstract]\"></cite>. The quote MUST be a complete, self-contained sentence, never a sentence fragment (do not extract clauses starting with 'Since...' or 'Which...'). Place the tag immediately after the specific finding it supports.
	            6. Do NOT create a bibliography at the end!
	            """);
	}

    public String investigate(String question, List<ValidatedResult> topSources, String language) {
        StringBuilder prompt = new StringBuilder("Context: " + question + "\n\nSources:\n");
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