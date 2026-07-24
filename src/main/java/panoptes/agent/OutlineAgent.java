package panoptes.agent;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;

import panoptes.llm.GeminiClient;

/**
 * The structural architect of the literature review.
 * <p>
 * The OutlineAgent takes the massive pool of coherent facts and organizes them into a logical, 
 * sequential blueprint for the final report. It defines section titles and provides strict, 
 * highly specific instructions for the writers on which facts belong in which section, 
 * ensuring proper domain segregation and narrative flow.
 * </p>
 */
@Service
public class OutlineAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public OutlineAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "Architect", """
                You are the Chief Architect of a scientific literature review. 
                Read the collected raw research facts and create a structured blueprint (outline) for the report.
                
                CRITICAL RULES:
                1. DEPTH OVER BREADTH & DOMAIN SEGREGATION: Do not try to cover every single discipline if they don't fit. Group facts strictly by their scientific disciplines. Do NOT force vastly different fields (e.g., Quantum Physics, Sociology, Algorithms, Biology) into a unified theory unless the sources explicitly bridge them! Keep boundaries clear.
                2. DEDICATED CRITIQUE SECTION: Instead of criticizing methodologies in every single paragraph, create ONE dedicated section near the end called 'Methodological Limitations & Disciplinary Boundaries' where you discuss weak sources, un-peer-reviewed preprints, and the dangers of conflating different disciplines.
                3. SERENDIPITY & CONCEPTUAL PARALLELS: If there are interesting cross-disciplinary connections (e.g., animal biology offering insights for AI), dedicate a section to 'Theoretical Synthesis'. CRITICAL: In your 'instructions' for this section, you MUST explicitly order the writer to frame these connections as "speculative analogies", "conceptual parallels", or "historical baselines", and NEVER as direct empirical evidence for the main topic!
                4. NARRATIVE OF SCIENTIFIC DEBATE, CHRONOLOGY & UNRESOLVED CONTROVERSIES: Look closely at the facts. Do they contradict each other? Do some facts have [HISTORICAL PARADIGM] or [WEAK/NICHE EVIDENCE] warnings, while others are recent and highly cited? If yes, DO NOT flatten them into a neutral list. You MUST structure the outline as a chronological scientific debate. Furthermore, if facts show an unresolved debate with contradictory modern papers (e.g., some claiming a method is beneficial, others claiming it causes collapse), you MUST instruct the writer to explicitly frame it as an "ongoing controversy" or "mixed evidence". Do not let the outline flip-flop between absolute statements!
                5. NO REPETITIVE STRUCTURES: Do not use the same narrative arc for every section. Let the content dictate the structure.
                6. 'section_title': The heading for the section IN THE REQUESTED TARGET LANGUAGE (do NOT use markdown '#' here).
                7. 'instructions': MUST BE IN ENGLISH! Write clear, strict English instructions on what facts this section must cover.
                """);
        this.mapper = mapper;
    }

    public List<SectionPlan> createOutline(String coreIdea, List<String> facts, String language) {
        StringBuilder prompt = new StringBuilder("Core Idea: " + coreIdea + "\n\nCollected Facts from Investigators:\n");
        for (int i = 0; i < facts.size(); i++) {
            prompt.append("--- Fact Report ").append(i + 1).append(" ---\n").append(facts.get(i)).append("\n\n");
        }

        Schema jsonSchema = Schema.builder()
                .type(Known.ARRAY)
                .items(Schema.builder()
                        .type(Known.OBJECT)
                        .properties(Map.of(
                                "section_title", Schema.builder().type(Known.STRING).build(),
                                "instructions", Schema.builder().type(Known.STRING).build()
                        ))
                        .required(List.of("section_title", "instructions"))
                        .build())
                .build();

        String json = executePonder(prompt.toString(), null, jsonSchema, language);
        try {
            return mapper.readValue(json, new TypeReference<List<SectionPlan>>(){});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OutlineAgent response", e);
        }
    }

    public record SectionPlan(String section_title, String instructions) {}
}