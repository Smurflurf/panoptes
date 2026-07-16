package panoptes.agent;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;

import panoptes.dto.PlanStep;
import panoptes.llm.GeminiClient;

/**
 * The master critic and devil's advocate.
 * <p>
 * The RedTeamAgent analyzes the fully assembled draft to identify logical blind spots, missing 
 * baselines, and claims that rely on weak evidence. It then formulates adversarial search queries 
 * designed specifically to find literature that contradicts, challenges, or debunks the draft's 
 * prevailing theories.
 * </p>
 */
@Service
public class RedTeamAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public RedTeamAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "DevilsAdvocate", """
        You are the Master Critic (Devil's Advocate) in a rigorous scientific peer-review process.
        Your ONLY goal is to find weaknesses in the provided research draft.
        
        CRITICAL RULES:
        1. Find claims that rely heavily on single studies, preprints, or assert strong causality where only correlation might exist.
        2. Identify where a theory is presented as the absolute truth without acknowledging scientific controversies.
        3. LOGICAL BLIND SPOTS & MISSING BASELINES (CRITICAL): Check if the draft holds a proposed concept to an unfair standard or lacks a comparative baseline. For example, if the draft argues that AI cannot achieve 'human parity' because it is bound by fundamental physical or thermodynamic limits, check if the human brain is ALSO bound by those exact same limits! If a comparative baseline is missing, this is a massive logical leap.
        4. Formulate specific 'question' items for human-readable contradiction searches IN THE REQUESTED LANGUAGE. If you found a missing baseline (Rule 3), formulate a question that investigates this exact comparison.
        5. Formulate 'vector_query' items ALWAYS IN ENGLISH for semantic vector search. These must be declarative abstract-style sentences predicting the OPPOSITE, METHODOLOGICAL FLAWS, or the COMPARATIVE BASELINE of the claims in the draft.
        6. Generate as many highly critical counter-search steps as necessary to address the flaws (no fixed limit, adapt to the severity and number of claims).
        """);
        this.mapper = mapper;
    }

    public List<PlanStep> analyzeAndPlan(String coreIdea, String draft, String language) {
        String prompt = "--- USER'S CORE PREMISE ---\n" + coreIdea + 
                        "\n\n--- CURRENT DRAFT ---\n" + draft + 
                        "\n\nTask: Generate search steps to falsify the boldest claims OR to find literature that exposes fundamental conceptual errors in the premise.";
        
        Schema jsonSchema = Schema.builder()
                .type(Known.ARRAY)
                .items(Schema.builder()
                        .type(Known.OBJECT)
                        .properties(Map.of(
                                "question", Schema.builder().type(Known.STRING).build(),
                                "vector_query", Schema.builder().type(Known.STRING).build()
                        ))
                        .required(List.of("question", "vector_query")) 
                        .build())
                .build();

        String json = executePonder(prompt, null, jsonSchema, language);
        try {
            return mapper.readValue(json, new TypeReference<List<PlanStep>>(){});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse RedTeamAgent response", e);
        }
    }
}