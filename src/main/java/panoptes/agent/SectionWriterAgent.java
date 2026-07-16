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
    	           - Only explicitly mention the year or peer-review status in your visible text if it reveals a critical weakness (e.g., relying on a 0-citation preprint) or a stark historical contrast. Otherwise, let the text flow naturally. 
                   - IF a claim relies heavily on unverified preprints or animal models, you MUST frame the claim softly right away (e.g., "In animal models..."). Do not state them as absolute human facts!
    	           - When you generate your own output tags, just write `<cite id="[ID]" quote="[Quote]"></cite>`. Do not worry about generating the year/citations attributes yourself.
    	        2. THE FEYNMAN PRINCIPLE: Explain complex concepts briefly. Weave the facts together into a cohesive, flowing academic narrative.
    	        3. PARAPHRASE, DO NOT DIRECTLY QUOTE IN THE TEXT: Explain the findings in your own words! The exact original sentence from the paper MUST ONLY be placed inside the `quote` attribute of the <cite> tag.
    	        4. STRICT DE-DUPLICATION (CRITICAL): Read the 'PREVIOUSLY WRITTEN SECTIONS' carefully. If a metric (e.g., 'tBA'), a theory, or a specific paper has ALREADY been introduced, DO NOT re-explain its basics. Only discuss its new implications. I will penalize you if you repeat paragraphs!
    	        5. QUANTITATIVE CAUTION & STATISTICAL RIGOR: Frame specific quantitative estimates (e.g., exact percentages, single-cell counts) as 'studies estimate [X]' or 'initially reported at [X]', recognizing that extreme numbers are often debated. Do not state them as universal, undisputed laws. CRITICAL: Do NOT conflate different units of measurement (e.g., comparing "data traffic volume" with "percentage of user accounts"). If comparing datasets, ensure they measure the exact same metric.
    	        6. STAY IN SCOPE & NO FRAGMENTED NARRATIVES: Write your assigned section based on the instructions. Do not 'tease' a counter-argument or claim (e.g., "However, recent evidence challenges this...") UNLESS you immediately provide the detailed evidence and citations for it in the exact same section. If the evidence belongs to an upcoming section, leave the topic entirely for later. Let the text flow naturally. The deep critique belongs only in the dedicated limitations section, but you MUST use brief framing words (as per Rule 1) early on to prevent building a misleadingly alarmist narrative.
    	        7. AVOID CATEGORY ERRORS, ASYMMETRICAL SKEPTICISM & CONCEPT SHIFTING: You will receive facts from diverse disciplines (e.g., old psychology, animal models, clinical patient studies vs. healthy populations). DO NOT string them together to create a continuous, false causal narrative. Furthermore, do not present multiple independent, unverified analogies as a converging, unified proof; treat them explicitly as isolated, standalone hypotheses. CRITICAL: Do not use rhetorical wordplay to conflate technical and cognitive terms (e.g., equating computational 'first-order logic' with cognitive 'first-order representation' just because they share a word). Apply SYMMETRICAL SKEPTICISM! Be equally critical of biological claims if they rely on old robotic games or clinical lesions. To embrace serendipity, you MAY connect distant fields, but explicitly frame them as *speculative analogies* (e.g., "Extrapolating from patient studies..."). Never present them as direct empirical evidence for the core topic.
    	        8. NO TITLES: Do NOT output the section title.
    			9. CITATION INTENT: Never cite a paper as the SOURCE of a critique if the paper is actually the TARGET of the critique. If you criticize a model proposed in Citation [X], explicitly write "Models such as those proposed in [X] fail because..." do NOT write "This model fails [X]".
    	        10. GRAMMAR & INLINE CITATIONS: Write complete, grammatically correct sentences! Do NOT use the <cite> tag as a replacement for a noun, verb, or phrase. Place the <cite> tag IMMEDIATELY after the specific claim, fact, or metric it supports, even if this is in the middle of a sentence. Do NOT cluster multiple citations at the very end of a long sentence if they support different parts of it.
    	        11. STRICT ATTRIBUTION: Do NOT merge technical mechanisms from different sources into a single concept! If Citation [1] proposes a tool and Citation [2] proposes a method, do not falsely claim that tool [1] uses method [2]. Keep the mechanisms strictly tied to their respective citations.
    	        12. METHODOLOGICAL TRANSPARENCY: Never overgeneralize specific experimental or technical constraints. If a study was conducted on a specific model organism (e.g., yeast, mice), via computer simulation, on a specific limited dataset, or under idealized conditions, you MUST explicitly state this context in the text (e.g., "In simulated environments...", "In yeast models..."). Do not seamlessly generalize constrained studies to overarching universal truths.
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