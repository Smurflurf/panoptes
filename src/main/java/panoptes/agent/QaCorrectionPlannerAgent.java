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
 * The tactical research planner deployed during Quality Assurance (QA) failures.
 * <p>
 * When the panel of auditors flags a drafted section for logical inconsistencies, 
 * category errors, or uncited claims, this agent steps in. Instead of executing a simple 
 * paraphrasing revision, the planner analyzes the failed draft alongside the exact auditor 
 * feedback to isolate the scientific information gap.
 * </p>
 * <p>
 * It then generates highly targeted {@link panoptes.dto.PlanStep} queries specifically optimized 
 * to retrieve missing empirical baselines or resolve contradictions. These queries are subsequently 
 * dispatched to the external Ideenatlas API via the orchestration layer to fetch corrective evidence, 
 * helping the system to actively learn and self-correct during the drafting phase.
 * </p>
 */
@Service
public class QaCorrectionPlannerAgent extends AbstractAgent {

    private final ObjectMapper mapper;

    public QaCorrectionPlannerAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "QaCorrectionPlanner", """
        You are a targeted research planner working for a Quality Assurance (QA) panel.
        A drafted section just failed QA because of hallucinations, category errors, or missing logical baselines.
        Your ONLY job is to read the failed draft and the QA feedback, and generate highly specific search queries to find the CORRECT information, empirical data, or missing context to fix the text.
        
        CRITICAL RULES:
        1. Only generate a few focused steps. Keep it highly focused on the exact error mentioned in the feedback.
        2. 'question': Formulate a clear question addressing the gap IN THE REQUESTED LANGUAGE.
        3. 'vector_query': IN ENGLISH, write a dense abstract-style statement predicting the correct facts or looking for the empirical baselines that were missing.
        """);
        this.mapper = mapper;
    }

    public List<PlanStep> planCorrection(String draft, String qaFeedback, String language) {
        String prompt = "--- FAILED DRAFT ---\n" + draft + 
                        "\n\n--- QA FEEDBACK (IDENTIFIED ERRORS) ---\n" + qaFeedback + 
                        "\n\nTask: Generate search steps to find the missing facts to correct these errors.";

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
            throw new RuntimeException("Failed to parse QaCorrectionPlannerAgent response", e);
        }
    }
}