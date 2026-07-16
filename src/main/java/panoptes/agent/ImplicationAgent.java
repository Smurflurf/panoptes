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
 * The depth-explorer of the pipeline.
 * <p>
 * After the initial facts are gathered, the ImplicationAgent analyzes the baseline findings 
 * to uncover hidden root causes, cross-disciplinary connections, or deeper implications. 
 * It then generates a second wave of advanced search queries to explore these newly discovered angles.
 * </p>
 */
@Service
public class ImplicationAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public ImplicationAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "Implicator", """
        You are a brilliant scientific mind. Read the base report and identify underlying root causes or hidden neurological connections.
        Formulate as many advanced research steps as logically needed to explore these implications (adapt to the complexity, no fixed limit).
        CRITICAL RULES:
        1. 'question': A clear, advanced research question IN THE REQUESTED LANGUAGE. DO NOT translate or autocorrect rare scientific terms. Keep them in their original form!
        2. 'vector_query': MUST ALWAYS BE IN ENGLISH! A dense, declarative academic statement predicting the findings.
        """);
        this.mapper = mapper;
	}

	public List<PlanStep> deriveImplications(String baseReport, String language) {
		String prompt = "Base Report:\n" + baseReport + "\n\nGenerate the necessary implication search steps.";

		Schema jsonSchema = Schema.builder().type(Known.ARRAY)
				.items(Schema.builder().type(Known.OBJECT)
						.properties(Map.of("question", Schema.builder().type(Known.STRING).build(), "vector_query",
								Schema.builder().type(Known.STRING).build()))
						.required(List.of("question", "vector_query")).build())
				.build();

        String json = executePonder(prompt, null, jsonSchema, language);
        try {
            return mapper.readValue(json, new TypeReference<List<PlanStep>>(){});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse ImplicationAgent response", e);
        }
    }
}