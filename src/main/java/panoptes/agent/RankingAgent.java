package panoptes.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;

import panoptes.dto.DetailedResult;
import panoptes.dto.ValidatedResult;
import panoptes.llm.GeminiClient;

/**
 * The critical evaluator of academic sources.
 * <p>
 * The RankingAgent reviews the metadata and abstracts of retrieved papers against a specific 
 * research question. It assigns a relevance score (0-100) based on methodological robustness, 
 * citation count, and conceptual overlap, filtering out irrelevant noise while explicitly 
 * allowing serendipitous, cross-disciplinary matches.
 * Using schemas, the LLM is forced to review all the papers.
 * This uses the results from the ideenatlas.eu api to its fullest extend.
 * </p>
 */
@Service
public class RankingAgent extends AbstractAgent {
    private final ObjectMapper mapper;

    public RankingAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(
            geminiClient, 
            "Ranker", 
            """
            You objectively evaluate the usefulness of academic papers for answering a specific research question.
            CRITICAL RULES - THIS IS A CROSS-DISCIPLINARY SEARCH ENGINE:
            - A paper from a completely different field might be highly relevant if the methodologies or concepts are transferable.
            - NEVER score a paper 0 just because it belongs to a different discipline.
            
            SCORING GUIDE:
            75-100: Direct match with robust methodology and high impact.
            30-74: Tangential/Serendipitous match. Offers conceptual analogies or out-of-the-box inspiration.
            0-29: Irrelevant garbage, highly questionable methodology, or no conceptual overlap.
            """
        );
        this.mapper = mapper;
    }

    public List<RankEvaluation> rankWithValidation(String question, List<ValidatedResult> results, String language) {
        if (results == null || results.isEmpty())
            return List.of();

        StringBuilder prompt = new StringBuilder("Research Question: " + question + "\n\nCandidates:\n");
        
        // 1. Setup for a 'hard schema'
        Map<String, Schema> dynamicProperties = new HashMap<>();
        List<String> requiredIds = new ArrayList<>();

        for (ValidatedResult v : results) {
            DetailedResult r = v.originalResult();
            String id = r.id();
            
            // Fill the prompt with all information of a paper
            prompt.append("ID: ").append(id).append("\n")
                  .append("Title: ").append(r.title()).append("\n")
                  .append("Year: ").append(v.publicationYear() != null ? v.publicationYear() : "Unknown").append("\n")
                  .append("Peer Reviewed: ").append(v.isPeerReviewed() ? "YES" : "NO (Preprint)").append("\n")
                  .append("Citation Count: ").append(v.citationCount()).append("\n")
                  .append("Abstract: ").append(r.summary()).append("\n\n");

            // 2. Put the papers ID as a hard key into the schema
            dynamicProperties.put(id, Schema.builder().type(Known.INTEGER).description("Score from 0 to 100").build());
            requiredIds.add(id);
        }

        // 3. Build the json
        Schema jsonSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(dynamicProperties)
                .required(requiredIds) // Require all IDs! -> No hallucinated IDs
                .build();

        String sysPrompt = """
            You objectively evaluate academic papers based on RELEVANCE and TRUSTWORTHINESS.
            CRITICAL RULES:
            1. BE CRITICAL OF PEER REVIEW: A 'Peer Reviewed: YES' flag is a good baseline, but it does NOT guarantee high quality! If the abstract reveals a weak methodology, or if it is an old paper with very few citations, do NOT give it a high score just because it was peer-reviewed. Always consider the publication year!
            2. PREPRINTS NEED CITATIONS: If a paper is 'NO (Preprint)', it MUST have a high 'Citation Count' to be trusted.
            3. SERENDIPITY: Papers from different fields are allowed if concepts are transferable.
            4. EXHAUSTIVE SCORING: You must provide a score for EVERY single ID.
            """;

        String json = executePonder(sysPrompt + "\n\n" + prompt.toString(), null, jsonSchema, language);

        try {
            // Read the json: { "ID-1": 85, "ID-2": 30, ... }
            Map<String, Integer> scoreMap = mapper.readValue(json, new TypeReference<Map<String, Integer>>() {});
            
            return scoreMap.entrySet().stream()
                    .map(entry -> new RankEvaluation(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse RankingAgent response", e);
        }
    }
    
    public record RankEvaluation(String id, int score) {}
}