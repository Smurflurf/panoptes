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
public class CrossrefDebugTest {

    @Test
    @Disabled("Manual debug test - run only when inspecting Crossref API changes")
    public void debugCrossrefPaper() throws Exception {
        String title = "Distinct corticostriatal compartments drive competition between adaptive and automatized behavior";
        
        System.out.println("\n=========================================");
        System.out.println("🔍 CROSSREF DEBUGGER (Bibliographic Search)");
        System.out.println("Target: " + title);
        
        String safeTitle = title.replaceAll("[^\\p{L}\\p{N}\\- ]", "").trim();
        String[] words = safeTitle.split("\\s+");
        
        // 1. Full title (max 12 words)
        String queryFull = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 12)));
        // 2. First 5 words only (Fallback)
        String queryShort = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 5)));
        
        // Polite Pool (E-Mail in Header)
        RestClient restClient = RestClient.builder()
                .baseUrl("https://api.crossref.org")
                .defaultHeader("User-Agent", "PanoptesDebug/1.0 (mailto:test-email@example.com)")	// Insert Email
                .build();
        
        // STAGE 1: FULL QUERY
        System.out.println("\n[STAGE 1] Bibliographic Search: " + queryFull);
        JsonNode response = fetchFromCrossref(restClient, queryFull);
        
        // STAGE 2: FALLBACK (If Stage 1 returned 0 results)
        if (isEmpty(response)) {
            System.out.println("❌ Stage 1 failed (0 results). Initiating Fallback...");
            System.out.println("[STAGE 2] Short Title Search: " + queryShort);
            response = fetchFromCrossref(restClient, queryShort);
        }
                
        if (isEmpty(response)) {
            System.out.println("❌ Crossref found NO results even in fallback.");
            return;
        }
        
        JsonNode items = response.path("message").path("items");
        System.out.println("✅ Found " + items.size() + " total results in Crossref.");
        System.out.println("Showing the Top candidates:\n");
        
        for (int i = 0; i < Math.min(items.size(), 5); i++) {
            JsonNode paper = items.get(i);
            
            String candidateTitle = (paper.path("title").isArray() && paper.path("title").size() > 0) 
                    ? paper.path("title").get(0).asText("") 
                    : "";
            
            System.out.println("--- Result [" + i + "] ---");
            System.out.println("DOI:       " + paper.path("DOI").asText("N/A"));
            System.out.println("Title:     " + candidateTitle);
            System.out.println("Type:      " + paper.path("type").asText("N/A"));
            System.out.println("Citations: " + paper.path("is-referenced-by-count").asInt(0));
            
            boolean isMatch = CitationFormatter.isFuzzyMatch(title, candidateTitle);
            System.out.println(">> Fuzzy Match? " + (isMatch ? "🟢 YES" : "🔴 NO"));
            System.out.println();
        }
        System.out.println("=========================================\n");
    }

    private JsonNode fetchFromCrossref(RestClient restClient, String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return restClient.get()
                .uri("/works?query.bibliographic=" + encoded + "&filter=type:journal-article,type:proceedings-article&rows=5")
                .retrieve()
                .body(JsonNode.class);
    }
    
    private boolean isEmpty(JsonNode response) {
        return response == null 
                || !response.has("message") 
                || !response.path("message").has("items") 
                || response.path("message").path("items").isEmpty();
    }
}