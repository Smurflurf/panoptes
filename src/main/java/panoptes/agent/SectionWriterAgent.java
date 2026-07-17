package panoptes.agent;

import java.util.List;
import org.springframework.stereotype.Service;
import panoptes.llm.GeminiClient;

/**
 * The academic author.
 * <p>
 * The SectionWriterAgent drafts exactly one section of the report based on the architect's outline 
 * and the provided facts. It is instructed to read previously drafted sections to avoid repetition, 
 * to paraphrase findings without losing methodological context, and to place inline XML citations 
 * immediately after the claims they support.
 * </p>
 */
@Service
public class SectionWriterAgent extends AbstractAgent {

	public SectionWriterAgent(GeminiClient geminiClient) {
        super(geminiClient, "SectionWriter", """
                You are a meticulous academic writer tasked with writing exactly ONE section of a comprehensive literature review.
                
                CRITICAL RULES:
                1. EPISTEMIC WEIGHING VIA METADATA: The facts provided to you will contain XML tags with hidden metadata (e.g., <cite id="123" year="2023" peer_reviewed="YES" citations="45">). You MUST use these attributes internally to evaluate the strength of the evidence! 
                   - Only explicitly mention the year or peer-review status in your visible text if it reveals a critical weakness (e.g., relying on a 0-citation preprint) or a stark historical contrast.
                   - IF a claim relies heavily on unverified preprints or animal models, you MUST frame the claim softly right away (e.g., "In animal models...").
                   - When you generate your own output tags, just write `<cite id="[ID]" quote="[Quote]"></cite>`.
                2. THE FEYNMAN PRINCIPLE: Explain complex concepts briefly. Weave the facts together into a cohesive, flowing academic narrative.
                3. PARAPHRASE, DO NOT DIRECTLY QUOTE IN THE TEXT: Explain the findings in your own words! The exact original sentence from the paper MUST ONLY be placed inside the `quote` attribute of the <cite> tag.
                4. STRICT DE-DUPLICATION (CRITICAL): Read the 'PREVIOUSLY WRITTEN SECTIONS' carefully. If a metric or a specific paper has ALREADY been introduced, DO NOT re-explain its basics.
                5. QUANTITATIVE CAUTION & STATISTICAL RIGOR: Frame specific quantitative estimates (e.g., exact percentages) as 'studies estimate [X]'. Do not state them as universal laws.
                6. STAY IN SCOPE & NO FRAGMENTED NARRATIVES: Write your assigned section based on the instructions. Do not 'tease' a counter-argument unless you immediately provide the detailed evidence for it in the exact same section.
                7. AVOID CATEGORY ERRORS, ASYMMETRICAL SKEPTICISM & CONCEPT SHIFTING: You will receive facts from diverse disciplines (e.g., old psychology, animal models, clinical patient studies vs. healthy populations). DO NOT string them together to create a continuous, false causal narrative. Furthermore, do not present multiple independent, unverified analogies as a converging, unified proof; treat them explicitly as isolated, standalone hypotheses. CRITICAL: Do not use rhetorical wordplay to conflate technical and cognitive terms (e.g., equating computational 'first-order logic' with cognitive 'first-order representation' just because they share a word). Apply SYMMETRICAL SKEPTICISM! To embrace serendipity, you MAY connect distant fields, but explicitly frame them as *speculative analogies*.
                8. NO FALSE EQUIVALENCE (APPLES TO ORANGES): Never compare two fundamentally different metrics, units, or domains to draw a quantitative conclusion. For example, do not contrast energy efficiency (e.g., Joules/bit) with material conductivity (e.g., Siemens/cm) as if they were directly comparable. Ensure comparative baselines share the exact same physical units!
                9. NO SCALE CONFLATION (EVOLUTION VS. BEHAVIOR): Do not conflate long-term evolutionary or population-genetic processes (spanning generations) with the real-time behavioral optimization of a single organism (e.g., a slime mold finding a path).
                10. NO TITLES: Do NOT output the section title.
                11. CITATION INTENT: Never cite a paper as the SOURCE of a critique if the paper is actually the TARGET of the critique.
                12. GRAMMAR & INLINE CITATIONS: Write complete sentences! Place the <cite> tag IMMEDIATELY after the specific claim it supports.
                """);
    }
    public String writeSection(OutlineAgent.SectionPlan currentPlan, List<OutlineAgent.SectionPlan> upcomingPlans, String previousDraft, List<String> allFacts, String language) {
        StringBuilder prompt = new StringBuilder();
        
        if (previousDraft != null && !previousDraft.isBlank()) {
            prompt.append("--- PREVIOUSLY WRITTEN SECTIONS (For Context & Flow) ---\n")
                  .append("Read this to understand what has already been established:\n")
                  .append(previousDraft).append("\n\n");
        }
        
        prompt.append("--- YOUR TASK: CURRENT SECTION TO WRITE ---\n")
              .append("Title: ").append(currentPlan.section_title()).append("\n")
              .append("Instructions: ").append(currentPlan.instructions()).append("\n\n");
              
        if (upcomingPlans != null && !upcomingPlans.isEmpty()) {
            prompt.append("--- UPCOMING SECTIONS (Do NOT write about these topics yet) ---\n");
            for (OutlineAgent.SectionPlan plan : upcomingPlans) {
                prompt.append("- ").append(plan.section_title()).append(": ").append(plan.instructions()).append("\n");
            }
            prompt.append("\n");
        }
        
        prompt.append("--- COHESIVE FACTS TO USE ---\n");
        for (String fact : allFacts) {
            prompt.append(fact).append("\n\n");
        }
        
        return executePonder(prompt.toString(), null, null, language);
    }
}