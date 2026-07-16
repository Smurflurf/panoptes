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
 * exaggerated claims, and conceptual shifts (category errors), flagging any citation that 
 * misrepresents the original paper.
 * </p>
 */
@Service
public class CitationQaAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public CitationQaAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "QualityAssurance", """
        You are an uncompromising scientific Quality Assurance Auditor.
        Your job is to read a drafted section of text and verify if the claims made in the text are ACTUALLY supported by the cited source abstracts.
        
        CRITICAL RULES:
		1. Be strict! You must check if the claim in the text AND the 'quote' inside the <cite> tag actually match the true source abstract.
		2. CHECK DOMAIN VALIDITY & FALSE EXTRAPOLATION: If the text makes a definitive claim about modern AI/LLMs or healthy human cognition, but the cited abstract is actually about a completely different subject (e.g., parent-child interactions, animal models, clinical patient studies with brain lesions vs. healthy individuals, 2012 robotic games, or general sociology), you MUST mark it as valid = false. The ONLY exception is if the text explicitly frames the citation as a 'speculative analogy' or 'historical baseline'. If it presents an animal/patient study as direct proof for a healthy AI/human effect, FAIL IT.
		3. CHECK CONTEXT & NO CONCEPT SHIFTING: Do not just check if a word or sentence exists in the abstract. Verify if the overall context of the abstract genuinely supports the specific argument being made in the draft. Furthermore, FAIL the citation if the draft uses rhetorical wordplay to conflate two different scientific concepts just because they share a word (e.g., equating computational 'first-order logic' with cognitive 'first-order representation'). If the draft twists the paper's original intent to fit a narrative, valid = false.
		4. HOLISTIC CONCLUSION CHECK (NO CHERRY-PICKING): You MUST evaluate the overall conclusion and main discovery of the paper. Abstracts often start by stating a historical premise, a commonly held belief, or a background problem. If the draft extracts such a background premise as a fact, but the actual scientific discovery of the paper contradicts, challenges, or solves that very premise, you MUST mark the citation as valid = false! 
		5. If a citation is used correctly, fits the domain, and aligns with the paper's actual conclusion, valid = true.
		6. Provide a brief, brutal reasoning for your decision. If it fails due to a category error, explicitly write: "Category Error: The draft applies findings from [subject] to AI without framing it as an analogy." If it fails due to rule 4, write: "Cherry-Picking Error: The draft uses a background premise, ignoring the paper's actual conclusion."
        """);
        this.mapper = mapper;
    }
    public Map<String, QaEvaluation> verifyCitations(String draftText, List<ValidatedResult> citedSources, String language) {
        StringBuilder prompt = new StringBuilder("--- DRAFT TEXT TO VERIFY ---\n")
                .append(draftText).append("\n\n--- SOURCE ABSTRACTS ---\n");
                
        // 1. hard schema again.
        Map<String, Schema> dynamicProperties = new HashMap<>();
        List<String> requiredIds = new ArrayList<>();
                
        for (ValidatedResult v : citedSources) {
            String id = v.originalResult().id();
            
            prompt.append("ID [").append(id).append("]:\n")
                  .append("Abstract: ").append(v.originalResult().summary()).append("\n\n");
            
            // 2. sub-schema for all IDs
            Schema evaluationSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(Map.of(
                    "valid", Schema.builder().type(Known.BOOLEAN).build(),
                    "reason", Schema.builder().type(Known.STRING).build()
                ))
                .required(List.of("valid", "reason"))
                .build();
            
            // 3. ID is key
            dynamicProperties.put(id, evaluationSchema);
            requiredIds.add(id); // ... and required
        }

        // 4. build the schema
        Schema jsonSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(dynamicProperties)
                .required(requiredIds) 
                .build();

        String json = executePonder(prompt.toString(), null, jsonSchema, language);

        try {
            // json structure: { "ID-123": { "valid": true, "reason": "..." } }
            return mapper.readValue(json, new TypeReference<Map<String, QaEvaluation>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse CitationQaAgent response", e);
        }
    }
    
    public record QaEvaluation(boolean valid, String reason) {}
}