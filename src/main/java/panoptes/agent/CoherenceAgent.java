package panoptes.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;

import panoptes.llm.GeminiClient;

/**
 * The curator of the gathered knowledge base.
 * <p>
 * The CoherenceAgent evaluates all the disparate facts collected by the investigators against 
 * the original core idea. Its goal is to prune absolute garbage or search-engine errors while 
 * intentionally preserving wild, serendipitous, and cross-disciplinary connections that might 
 * offer valuable conceptual analogies for the final report.
 * </p>
 */
@Service
public class CoherenceAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public CoherenceAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "CoherenceOfficer", """
        You are the Chief Coherence Officer. Your curation sets the foundation for the entire research report.
        
        CRITICAL RULES:
        1. EMBRACE SERENDIPITY (CROSS-DISCIPLINARY THINKING): Do NOT remove a fact simply because it originates from a wildly different scientific discipline! The core philosophy of this system is to find hidden, cross-disciplinary connections. If a fact connects thermodynamics to neuroscience, or quantum physics to biology, KEEP IT.
        2. FILTER ONLY PURE NOISE: You must ONLY remove facts that are complete garbage, result from obvious search engine errors (e.g., a paper about 'Internet Routing' in a query about 'Brain Routing'), or have absolutely zero conceptual, mathematical, or metaphorical overlap with the core idea.
        3. ALLOW CREATIVE SYNTHESIS: If a distant fact offers a fascinating analogy, a mathematical isomorphism, or a provocative hypothesis, it is extremely valuable. Keep it! The downstream writers and editors are strictly instructed to frame these connections honestly as 'analogies' and 'hypotheses' rather than proven facts, so you do not need to fear bold ideas causing hallucinations. Let the serendipity flow.
        4. EXHAUSTIVE EVALUATION: You MUST evaluate every single fact index provided to you. For each fact, output whether to keep it (true) or drop it (false), along with a brief reasoning.
        """);
        this.mapper = mapper;
    }

    public List<Integer> filterCoherentFacts(String coreIdea, List<String> allFacts, String language) {
        StringBuilder prompt = new StringBuilder("Core Idea: " + coreIdea + "\n\nCollected Facts:\n");
        
        Map<String, Schema> dynamicProperties = new HashMap<>();
        List<String> requiredKeys = new ArrayList<>();

        for (int i = 0; i < allFacts.size(); i++) {
            String key = String.valueOf(i);
            prompt.append("--- Fact Index [").append(key).append("] ---\n").append(allFacts.get(i)).append("\n\n");
            
            // hard schema again, force the LLM to do good work :)
            Schema evaluationSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(Map.of(
                    "keep", Schema.builder().type(Known.BOOLEAN).build(),
                    "reasoning", Schema.builder().type(Known.STRING).build()
                ))
                .required(List.of("keep", "reasoning"))
                .build();
                
            dynamicProperties.put(key, evaluationSchema);
            requiredKeys.add(key); 
        }

        // Das finale "Hard Schema"
        Schema jsonSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(dynamicProperties)
                .required(requiredKeys) 
                .build();

        String json = executePonder(prompt.toString(), null, jsonSchema, language);
        
        try {
            // json structure: { "0": { "keep": true, "reasoning": "..." }, "1": { ... } }
            Map<String, CoherenceEvaluation> evaluations = mapper.readValue(json, new TypeReference<Map<String, CoherenceEvaluation>>(){});
            List<Integer> keptIndices = new ArrayList<>();
            
            for (Map.Entry<String, CoherenceEvaluation> entry : evaluations.entrySet()) {
                if (entry.getValue().keep()) {
                    keptIndices.add(Integer.parseInt(entry.getKey()));
                }
            }
            
            Collections.sort(keptIndices);
            return keptIndices;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse CoherenceAgent response", e);
        }
    }
    
    public record CoherenceEvaluation(boolean keep, String reasoning) {}
}