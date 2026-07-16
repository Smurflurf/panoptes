package panoptes.agent;

import java.util.Map;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;
import panoptes.llm.GeminiClient;

/**
 * The executive summarizer.
 * <p>
 * The TldrAgent reads the executive answer and the full report to generate a punchy, 
 * 1-2 sentence TL;DR (Too Long; Didn't Read) and a short title. It is strictly instructed 
 * to capture both the main finding and its primary caveat or limitation.
 * </p>
 */
@Service
public class TldrAgent extends AbstractAgent {

    private final ObjectMapper mapper;

    public TldrAgent(GeminiClient geminiClient, ObjectMapper mapper) {
        super(geminiClient, "TldrAgent", """
        You are an executive scientific editor. Read the provided research context (which includes a direct 'EXECUTIVE ANSWER') and write a punchy, 1-2 sentence 'TLDR'.
        
        CRITICAL RULES:
        1. FORCED NUANCE: Your TLDR MUST structurally contain two parts: The main finding AND the primary limitation/counter-evidence. Use a structure similar to: "While [main finding], this is severely limited by [major caveat / lack of direct evidence]."
        2. REFLECT THE SKEPTICISM: Your TLDR MUST accurately reflect the tone and conclusions of the EXECUTIVE ANSWER. If the Executive Answer debunks a premise or highlights massive limitations, the TLDR MUST also include these caveats! Do not write an overly optimistic summary if the actual report is highly critical.
        3. NO FLUFF: Do not say "This report suggests...". State the definitive conclusion directly.
        4. The 'short_title' MUST be very short (max 4 words) and punchy.
        5. NO SENSATIONAL UNVERIFIED NUMBERS: Do not include extreme statistical claims (e.g., percentages) in the TLDR unless backed by robust, peer-reviewed consensus.
        """);
        this.mapper = mapper;
    }
    
    public TldrResult generateTldr(String fullReport, String language) {
        String prompt = "Full Report:\n" + fullReport + "\n\nGenerate the TLDR and the short_title.";

        Schema jsonSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(Map.of(
                        "tldr", Schema.builder().type(Known.STRING).build(),
                        "short_title", Schema.builder().type(Known.STRING).build()
                ))
                .required(java.util.List.of("tldr", "short_title"))
                .build();

        String json = executePonder(prompt, null, jsonSchema, language);
        try {
            JsonNode node = mapper.readTree(json);
            return new TldrResult(
                node.path("tldr").asText("TLDR generation failed."),
                node.path("short_title").asText("Final Report")
            );
        } catch (Exception e) {
            throw new RuntimeException("TldrAgent failed to produce valid JSON", e);
        }
    }
    
    public record TldrResult(String tldr, String shortTitle) {}
}