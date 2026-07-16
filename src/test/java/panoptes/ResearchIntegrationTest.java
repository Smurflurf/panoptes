package panoptes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import panoptes.agent.RankingAgent;
import panoptes.agent.RankingAgent.RankEvaluation;
import panoptes.api.IdeenatlasApiClient;
import panoptes.dto.DetailedResult;
import panoptes.dto.ValidatedResult;
import panoptes.service.PaperValidationService;

@SpringBootTest 
public class ResearchIntegrationTest {
    
    @Autowired
    private IdeenatlasApiClient apiClient;
    
    @Autowired
    private RankingAgent rankingAgent;

    @Autowired
    private PaperValidationService validationService;

    /**
     * TEST 1: Queries the Ideenatlas API and checks the default result count.
     */
    @Test
    @Disabled("Manual debug test")
    void testApiResultsCount() {
        String query = "neurological basis of aphantasia";
        
        System.out.println("\n=== TEST 1: API RESULTS ===");
        System.out.println("Querying ideenatlas.eu for: '" + query + "'");
        
        List<DetailedResult> results = apiClient.search(query);
        
        assertNotNull(results, "API should not return null.");
        assertFalse(results.isEmpty(), "API should return at least one result.");
        
        System.out.println("Results found: " + results.size());
        
        for (int i = 0; i < results.size(); i++) {
            System.out.println((i + 1) + ". [ID: " + results.get(i).id() + "] " + results.get(i).title());
        }
        System.out.println("===========================\n");
    }

    /**
     * TEST 2: Queries the API, VALIDATES papers via OpenAlex, 
     * and lets the LLM agent score the list.
     */
    @Test
    @Disabled("Manual debug test - invokes LLM billing!")
    void testRankingAgentMatchesInputSize() {
        String query = "neurological basis of aphantasia";
        
        System.out.println("\n=== TEST 2: RANKING AGENT BEHAVIOR ===");
        
        List<DetailedResult> results = apiClient.search(query);
        assumeTrue(results != null && !results.isEmpty(), "API must return results for this test.");
        
        System.out.println("Validating " + results.size() + " papers via OpenAlex before sending to LLM...");
        
        List<ValidatedResult> validatedResults = results.stream()
                .map(validationService::validatePaper)
                .collect(Collectors.toList());

        System.out.println("Sending " + validatedResults.size() + " validated papers to the RankingAgent...");
        
        List<RankEvaluation> rankedResults = rankingAgent.rankWithValidation(query, validatedResults, "English");
        
        System.out.println("RankingAgent returned " + rankedResults.size() + " scores.");
        
        for (RankEvaluation eval : rankedResults) {
            System.out.println("ID: " + eval.id() + " -> Score: " + eval.score());
        }
        
        assertEquals(results.size(), rankedResults.size(), 
                "ERROR: LLM did not assign a score to every paper! " +
                "Expected: " + results.size() + ", Received: " + rankedResults.size());
                
        System.out.println("======================================\n");
    }

    /**
     * TEST 3: Stress test for the OpenAlex Validation Pipeline.
     * Verifies if DOIs, peer-review status, and retractions are correctly fetched.
     */
    @Test
    @Disabled("Manual debug test")
    void testOpenAlexValidation() {
        String query = "artificial general intelligence architectures";
        
        System.out.println("\n=== TEST 3: OPENALEX API VALIDATION ===");
        
        List<DetailedResult> results = apiClient.search(query);
        assumeTrue(results != null && !results.isEmpty(), "API must return results for this test.");

        // Limit to top 5 results to keep the test fast and avoid rate-limiting
        int limit = Math.min(5, results.size());
        System.out.println("Checking metadata for the top " + limit + " papers via OpenAlex...\n");

        for (int i = 0; i < limit; i++) {
            DetailedResult rawResult = results.get(i);
            ValidatedResult validResult = validationService.validatePaper(rawResult);
            
            assertNotNull(validResult, "ValidationResult must not be null.");
            
            System.out.println("--- Paper " + (i + 1) + " ---");
            System.out.println("Title:      " + validResult.originalResult().title());
            System.out.println("DOI:        " + (validResult.doi() != null ? validResult.doi() : "Not found (Preprint/Niche paper?)"));
            System.out.println("Peer-Rev:   " + (validResult.isPeerReviewed() ? "✅ YES (Journal/Review)" : "❌ NO (Preprint/Other)"));
            System.out.println("Citations:  " + validResult.citationCount());
            System.out.println("Retracted:  " + (validResult.isRetracted() ? "🚨 YES (RETRACTED!)" : "✅ NO"));
            System.out.println();
        }
        
        System.out.println("=======================================\n");
    }
}