package panoptes.agent;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type.Known;

import panoptes.llm.GeminiClient;

/**
 * The entry point of the research pipeline.
 * <p>
 * The IdeaExtractionAgent is responsible for parsing unstructured, messy, or conversational 
 * user input (such as voice transcripts) and distilling it into a precise, academic core research 
 * question. It generates a cleaned transcription while preserving the user's original intent, 
 * metaphors, and constraints.
 * </p>
 */
@Service
public class IdeaExtractionAgent extends AbstractAgent {

    private final ObjectMapper objectMapper;

    private static final String PERSONA = """
            You are an elite research extraction engine. Your job is to decode the user's true research intent from unstructured, sometimes messy, or metaphorical input (like voice notes) and elevate it into a rigorous academic research question which will be answered by agents down the line.
            
            CRITICAL RULES:
            1. Step 1: Write a 'cleaned_transcription'. Carefully transcribe and grammatically smooth out EVERYTHING the user said. Capture every single technical angle, metaphor, and constraint mentioned. Do not summarize or drop details here! Treat it as a polished, high-fidelity transcript.
            2. Step 2: Formulate the 'core_idea'. This MUST be a dense, academic formulation of the user's research question. 
               - CRITICAL: NEVER ANSWER THE QUESTION! You are formulating the inquiry for downstream researchers, NOT explaining the solution. (e.g., If the user asks "Why do black holes lose mass?", do NOT explain Hawking radiation. Instead, formulate: "An investigation into the quantum mechanical and thermodynamic mechanisms driving mass evaporation in black holes...").
               - It should not be a meta-text. Do NOT write 'This research inquiry seeks to...' or similar phrases. Just state the core research topic or question itself.
            3. NO PREMATURE NARROWING: Do NOT translate the user's creative metaphors into standard machine learning concepts unless explicitly mentioned. If they talk about "disabling layers" or "brain regions", keep that exact structural nuance.
            4. DO NOT AUTOCORRECT or alter rare terms (e.g., 'anauralia', 'aphantasia').
            5. ELEVATE, DO NOT SOLVE: If the user expresses uncertainties or asks a very simple question, synthesize a rigorous scientific foundation that outlines the *scope of the required research*, but again, DO NOT answer the actual question.
            """;
    
    public IdeaExtractionAgent(GeminiClient geminiClient, ObjectMapper objectMapper) {
        super(geminiClient, "IdeaExtractor", PERSONA);
        this.objectMapper = objectMapper;
    }

    public ExtractedIdea process(String rawInput, List<Part> files, String language) {
        String task = "Extract the core idea from the following input.\nUser Input: " + 
                      (rawInput != null && !rawInput.isBlank() ? rawInput : "None provided.");

        Schema jsonSchema = Schema.builder()
                .type(Known.OBJECT)
                .properties(Map.of(
                        "cleaned_transcription", Schema.builder().type(Known.STRING).description("A grammatically smoothed, highly detailed transcript of all points made by the user.").build(),
                        "core_idea", Schema.builder().type(Known.STRING).build(),
                        "short_summary", Schema.builder().type(Known.STRING).build()
                ))
                .required(List.of("cleaned_transcription", "core_idea", "short_summary"))
                .build();

        String jsonResponse = executePonder(task, files, jsonSchema, language);

        try {
        	JsonNode node = objectMapper.readTree(jsonResponse);
            return new ExtractedIdea(
                    node.path("cleaned_transcription").asText("No transcription generated."),
                    node.path("core_idea").asText("No idea could be extracted."),
                    node.path("short_summary").asText("No summary.")
            );
        } catch (Exception e) {
            throw new RuntimeException("Agent failed to produce valid JSON", e);
        }
    }

    public record ExtractedIdea(String cleanedTranscription, String synthesisedIdea, String shortSummary) {}
}