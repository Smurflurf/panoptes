package panoptes.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;

import panoptes.dto.ValidatedResult;
import panoptes.llm.GeminiClient;

/**
 * The logical and methodological auditor.
 * <p>
 * This agent is part of the QA panel. Instead of checking if the abstract was quoted correctly, 
 * it actively searches the drafted text for logical fallacies, false equivalences (e.g., comparing 
 * Joules to Siemens), scale conflation, and missing baselines.
 * </p>
 */
@Service
public class LogicalFallacyQaAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public LogicalFallacyQaAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "LogicAuditor", """
        You are a merciless Logical and Methodological Auditor in a rigorous scientific peer-review process.
        Your ONLY job is to read a drafted section of text and verify if the claims contain logical fallacies, false equivalences, or mismatched scientific scales.
        
        CRITICAL RULES:
        1. NO FALSE EQUIVALENCE (APPLES TO ORANGES): Critically examine quantitative comparisons in the draft. If the draft compares incommensurable units or disparate metrics to make a quantitative argument (e.g., contrasting energy efficiency with material conductivity, absolute volume with a percentage rate, or computational FLOPs with biological ATP consumption), you MUST mark it as valid = false.
        2. NO SCALE CONFLATION: Do not allow the conflation of fundamentally different temporal, spatial, or systemic scales. If a claim applies a paper about long-term macro-phenomena (like evolutionary biology, cosmological time, or macroeconomics) to directly criticize real-time micro-behavior (like organism pathfinding, quantum decoherence, or high-frequency trading), FAIL IT.
        3. ASYMMETRICAL SKEPTICISM & MISSING BASELINES: If the draft criticizes a concept or system for failing to reach a goal due to strict physical, logical, or biological limits, check if the comparative baseline is subject to those exact same limits. If the text holds one system to an impossible standard without applying the same standard to its natural counterpart, FAIL IT.
        4. If the logic is sound, units match, and no fallacies are detected, valid = true.
        5. Provide a brief, brutal reasoning. Example: "False Equivalence Error: The draft compares incommensurable metrics (e.g., energy vs. conductivity)." or "Scale Conflation Error: The draft conflates macro-scale phenomena with micro-scale behavior."
        """);
        this.mapper = mapper;
    }

    public Map<String, QaEvaluation> verifyLogic(String draftText, List<ValidatedResult> citedSources, String language) {
        StringBuilder prompt = new StringBuilder("--- DRAFT TEXT TO VERIFY ---\n")
                .append(draftText).append("\n\n--- SOURCES CITED IN DRAFT ---\n");
                
        Map<String, Schema> dynamicProperties = new HashMap<>();
        List<String> requiredIds = new ArrayList<>();
                
        for (ValidatedResult v : citedSources) {
            String id = v.originalResult().id();
            prompt.append("ID [").append(id).append("]: ").append(v.originalResult().title()).append("\n");
            
            Schema evaluationSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(Map.of(
                    "valid", Schema.builder().type(Known.BOOLEAN).build(),
                    "reason", Schema.builder().type(Known.STRING).build()
                ))
                .required(List.of("valid", "reason"))
                .build();
            
            dynamicProperties.put(id, evaluationSchema);
            requiredIds.add(id); 
        }

        Schema jsonSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(dynamicProperties)
                .required(requiredIds) 
                .build();

        String json = executePonder(prompt.toString(), null, jsonSchema, language);

        try {
            return mapper.readValue(json, new TypeReference<Map<String, QaEvaluation>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse LogicalFallacyQaAgent response", e);
        }
    }
    
    public record QaEvaluation(boolean valid, String reason) {}
}