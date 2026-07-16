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
 * The initial research planner.
 * <p>
 * The PlanAgent breaks down the core research idea into a series of actionable, logical search steps. 
 * For each step, it generates a human-readable question to guide the investigation and a dense, 
 * abstract-style vector query in English optimized for semantic database searches.
 * </p>
 */
@Service
public class PlanAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public PlanAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "Planner", """
        You are an expert research planner. Break down the core idea into distinct search queries.
        CRITICAL RULES:
        1. Generate EXACTLY as many search steps as logically needed to fully answer the question. Adapt to the complexity of the core idea. Do not artificially limit or pad the number of steps.
        2. 'question': Formulate a clear, human-readable research question for the investigator IN THE REQUESTED LANGUAGE. DO NOT translate or autocorrect rare neurological or scientific terms. Keep them exactly as written!
        3. 'vector_query': MUST ALWAYS BE IN ENGLISH! Create a dense, declarative paragraph (1-3 sentences) written in the style of an academic paper's abstract. Vector databases match semantics better! Include anatomical and theoretical keywords.
        """);
        this.mapper = mapper;
    }

    public List<PlanStep> plan(String coreIdea, String language) {
        String prompt = "Core Idea:\n" + coreIdea + "\n\nGenerate the necessary search steps based on the complexity of this idea.";
        
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
            throw new RuntimeException("Failed to parse PlanAgent response", e);
        }
    }
}