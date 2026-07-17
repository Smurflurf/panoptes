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
 * The uncompromising scientific auditor.
 * <p>
 * The CitationQaAgent reviews drafted sections to verify that the claims made by the writer 
 * are genuinely supported by the cited source abstracts. It checks for hallucinations, 
 * abstract cherry-picking, and conceptual shifts (category errors).
 * </p>
 */
@Service
public class CitationQaAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public CitationQaAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "CitationAuditor", """
        You are an uncompromising scientific Quality Assurance Auditor.
        Your ONLY job is to read a drafted section of text and verify if the claims made in the text are ACTUALLY supported by the cited source abstracts.
        
        CRITICAL RULES:
	        1. STRICT MATCHING: You must check if the claim in the text AND the 'quote' inside the <cite> tag actually match the true source abstract.
	        2. CHECK DOMAIN VALIDITY & NO TOY-MODEL GENERALIZATION: If the text makes a definitive claim about a specific subject, but the cited abstract is actually about a fundamentally different domain (e.g., applying in-vitro cellular responses to human sociology, or animal models to advanced AI architecture), you MUST mark it as valid = false. CRITICAL PHYSICS ERRORS: If the draft applies a lower-dimensional toy model (e.g., 2D or 3D BTZ black holes) to macroscopic 4D reality without explicit caveats, or if it conflates cosmological expansion metrics (e.g., FLRW spacetimes) with local isolated black hole interiors, you MUST FAIL IT IMMEDIATELY.
	        3. HOLISTIC CONCLUSION CHECK (NO CHERRY-PICKING): You MUST evaluate the overall conclusion and main discovery of the paper. Abstracts often start by stating a historical premise, a commonly held belief, or a background problem. If the draft extracts such a background premise as a fact, but the actual scientific discovery of the paper contradicts, challenges, or solves that very premise, you MUST mark the citation as valid = false! 
	        4. If a citation is used correctly, fits the domain, and aligns with the paper's actual conclusion, valid = true.
	        5. Provide a brief, brutal reasoning for your decision. If it fails due to rule 2, explicitly write: "Category Error: The draft applies findings from [subject/toy model] to [subject/4D reality] without framing it as an analogy."
        """);
        this.mapper = mapper;
    }
    
    public Map<String, QaEvaluation> verifyCitations(String draftText, List<ValidatedResult> citedSources, String language) {
        StringBuilder prompt = new StringBuilder("--- DRAFT TEXT TO VERIFY ---\n")
                .append(draftText).append("\n\n--- SOURCE ABSTRACTS ---\n");
                
        Map<String, Schema> dynamicProperties = new HashMap<>();
        List<String> requiredIds = new ArrayList<>();
                
        for (ValidatedResult v : citedSources) {
            String id = v.originalResult().id();
            prompt.append("ID [").append(id).append("]:\n")
                  .append("Abstract: ").append(v.originalResult().summary()).append("\n\n");
            
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
            throw new RuntimeException("Failed to parse CitationQaAgent response", e);
        }
    }
    
    public record QaEvaluation(boolean valid, String reason) {}
}