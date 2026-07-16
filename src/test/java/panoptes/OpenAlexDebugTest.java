package panoptes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

import panoptes.service.CitationFormatter;

@SpringBootTest
public class OpenAlexDebugTest {

    @Test
    @Disabled("Manual debug test - run only when inspecting OpenAlex API changes")
    public void debugOpenAlexPaper() throws Exception {
        String title = "Emergence of time persistence in an interpretable data-driven neural network model";
        
        System.out.println("\n=========================================");
        System.out.println("🔍 OPENALEX DEBUGGER (V3 - Waterfall Strategy)");
        System.out.println("Target: " + title);
        
        String safeTitle = title.replaceAll("[^\\p{L}\\p{N}\\- ]", "").trim();
        String[] words = safeTitle.split("\\s+");
        
        // 1. Full title (max 12 words)
        String queryFull = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 12)));
        // 2. First 5 words only (Fallback)
        String queryShort = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 5)));
        
        RestClient restClient = RestClient.builder().baseUrl("https://api.openalex.org").build();
        
        // STAGE 1: FULL QUERY
        System.out.println("\n[STAGE 1] Strict Title Search: " + queryFull);
        JsonNode response = fetchFromOpenAlex(restClient, queryFull);
        
        // STAGE 2: FALLBACK (If Stage 1 returned 0 results)
        if (response == null || !response.has("results") || response.path("results").isEmpty()) {
            System.out.println("❌ Stage 1 failed (0 results). Initiating Fallback...");
            System.out.println("[STAGE 2] Short Title Search: " + queryShort);
            response = fetchFromOpenAlex(restClient, queryShort);
        }
                
        if (response == null || !response.has("results") || response.path("results").isEmpty()) {
            System.out.println("❌ OpenAlex found NO results even in fallback.");
            return;
        }
        
        JsonNode results = response.path("results");
        System.out.println("✅ Found " + results.size() + " total results in OpenAlex.");
        System.out.println("Showing the Top candidates:\n");
        
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            JsonNode paper = results.get(i);
            String candidateTitle = paper.path("title").asText("");
            
            System.out.println("--- Result [" + i + "] ---");
            System.out.println("ID:        " + paper.path("id").asText());
            System.out.println("Title:     " + candidateTitle);
            System.out.println("Type:      " + paper.path("type").asText());
            System.out.println("Citations: " + paper.path("cited_by_count").asInt());
            
            boolean isMatch = CitationFormatter.isFuzzyMatch(title, candidateTitle);
            System.out.println(">> Fuzzy Match? " + (isMatch ? "🟢 YES" : "🔴 NO"));
            System.out.println();
        }
        System.out.println("=========================================\n");
    }

    private JsonNode fetchFromOpenAlex(RestClient restClient, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return restClient.get()
                .uri("/works?filter=title.search:" + encoded)
                .retrieve()
                .body(JsonNode.class);
    }
}