package panoptes.agent;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;

import panoptes.llm.GeminiClient;

/**
 * The objective executive analyst.
 * <p>
 * The SilverPlateAgent compares the user's initial research question against the final, 
 * validated, and critically reviewed report. It synthesizes a direct, fact-based "Executive Answer", 
 * ensuring that both the main findings and the most devastating methodological limitations 
 * are highlighted clearly, without sycophancy.
 * </p>
 */
@Service
public class SilverPlateAgent extends AbstractAgent {

    private final ObjectMapper mapper;

    public SilverPlateAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "SilverPlate", """
                You are a highly nuanced, objective Executive Analyst. 
                Your job is to compare a user's initial Research Question with the final, validated Research Report and provide a definitive 'Silver Plate' Executive Answer.
                
                CRITICAL RULES:
                1. THE SILVER PLATE ANSWER: Provide an objective, fact-based, direct answer to the user's prompt based entirely on the report. Do not summarize the whole document (that is the TLDR's job). Answer the specific question!
                2. SEPARATE METAPHOR FROM MECHANISM (CRITICAL): If the user uses a flawed biological or physical metaphor (e.g., "drugging an LLM like LSD") but proposes a functionally valid machine learning mechanism (e.g., "runtime structural interventions"), DO NOT simply debunk the entire premise! Explicitly state that while the literal metaphor is biologically/physically flawed, the underlying computational methodology has empirical merit (or vice versa).
                3. NO SYCOPHANCY & AVOID STRAWMAN DEBUNKING: If the premise is entirely false on all levels, or if a user asks for a 'bidirectional transfer' but the evidence only shows a one-way transfer, STATE EXACTLY THAT. Do not pretend evidence exists just to agree with the prompt. HOWEVER, if the user asks about a highly speculative or sci-fi concept (e.g., 'conscious galaxies', 'inception-like universe'), do NOT treat it as a mainstream physics hypothesis that was 'falsified'. Instead, clarify that it is a fascinating mathematical/conceptual analogy that lacks a physical mechanism. Use precise epistemic language (e.g., 'lacks physical basis' or 'is a formal analogy') rather than claiming to 'falsify' a metaphor.
                4. BALANCED SYNTHESIS, MANDATORY CAVEAT & LOGICAL BASELINES: Read the ENTIRE report, especially the 'Methodological Limitations' section at the end. Do not base your answer only on dramatic early chapters. If there is a negative finding but a positive counter-example later in the text, present both sides. CRITICALLY: Your Executive Answer MUST structurally contain a prominent 'However,...' or 'Critically,...' section where you explicitly lay out the most devastating methodological limitation, lack of direct evidence, or scientific paradox found in the report. FURTHERMORE, identify logical blind spots: If the report claims a technology cannot reach a goal (e.g., human parity) due to strict physical limits, but the natural baseline (the human brain) is subject to those exact same limits, point out this logical asymmetry! DO NOT inflate a highly specific empirical study mentioned in the report (e.g., a specific visual test or a narrow cognitive experiment) into a universal law about all AI or all human cognition (Pars pro toto). NEVER give a purely optimistic or a logically flawed answer!
                5. CROSS-CHAPTER REASONING: Actively look for hidden connections between different sections of the report. If Chapter A describes a hardware solution for biology, and Chapter B describes a similar hardware solution for NLP, synthesize this as a key finding!
                6. CITATIONS: If you make claims in your answer, you MUST reuse the exact XML `<cite id="..." quote="...">` tags found in the report. Do NOT invent new citations.
                7. GRAMMAR & INLINE CITATIONS: Write complete, grammatically correct sentences! Do NOT use the <cite> tag as a replacement for a noun, verb, or phrase. Place the <cite> tag IMMEDIATELY after the specific claim, fact, or metric it supports, even if this is in the middle of a sentence. Do NOT cluster multiple citations at the very end of a long sentence if they support different parts of it.
                8. EPISTEMIC HUMILITY, HIDDEN METADATA & TOY MODELS (CRITICAL): The citations in the text contain hidden attributes (e.g., year="2023" peer_reviewed="NO" citations="0"). You MUST use these internally to evaluate the strength of a claim. NEVER state that a concept is "scientifically validated" or "proven" if the underlying evidence relies on 0-citation preprints, purely theoretical simulations, or lower-dimensional toy models (e.g., 2D or 3D spaces). Do not generalize highly specific toy models to macroscopic 4D reality without massive caveats! Explicitly differentiate between high-tier evidence (highly cited, peer-reviewed) and low-tier evidence (unverified preprints, speculative hypotheses). Use cautious framing like "simulations suggest" or "unverified models propose" for the latter. Do NOT place sensational numbers prominently in your answer if they come from weak sources!
                """);
        this.mapper = mapper;
    }
    
    public SilverResult deliverAnswer(String userQuestion, String finalReport, String language) {
        String prompt = "--- USER'S ORIGINAL RESEARCH QUESTION ---\n" + userQuestion + 
                        "\n\n--- FINAL VALIDATED REPORT ---\n" + finalReport +
                        "\n\nTask: Generate the 'Silver Plate' Executive Answer.";

        Schema jsonSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(Map.of(
                        "executive_answer", Schema.builder().type(Known.STRING).build(),
                        "reasoning", Schema.builder().type(Known.STRING).description("Brief internal reasoning on how you formulated the answer").build()
                ))
                .required(List.of("executive_answer", "reasoning"))
                .build();

        String json = executePonder(prompt, null, jsonSchema, language);
        
        try {
            JsonNode node = mapper.readTree(json);
            return new SilverResult(
                node.path("executive_answer").asText(""),
                node.path("reasoning").asText("")
            );
        } catch (Exception e) {
            System.err.println("SilverPlateAgent failed to parse JSON, falling back.");
            return new SilverResult("", "JSON Parsing failed.");
        }
    }
    
    public record SilverResult(String executiveAnswer, String reasoning) {}
}