package panoptes.agent;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;

import com.google.genai.types.Part;
import com.google.genai.types.Schema;

import panoptes.llm.GeminiClient;

public abstract class AbstractAgent {

    @Value("${panoptes.llm.internal-language:Scientific English}")
    private String internalLanguage;
	
    protected final GeminiClient geminiClient;
    protected final String agentName;
    protected final String persona;

    public AbstractAgent(GeminiClient geminiClient, String agentName, String persona) {
        this.geminiClient = geminiClient;
        this.agentName = agentName;
        this.persona = persona;
    }

    /**
     * Abstract base class for all Large Language Model (LLM) agents in the Panoptes pipeline.
     * <p>
     * This class provides the foundational mechanism for executing LLM requests via the {@link GeminiClient}.
     * It automatically injects critical global instructions into every prompt, ensuring strict adherence to 
     * the requested output language, UTF-8 encoding for special characters (e.g., umlauts), and proper 
     * formatting rules for Markdown and KaTeX (mathematical formulas).
     * </p>
     */
    protected String executePonder(String taskDescription, List<Part> attachments, Schema schema) {
        return executePonder(taskDescription, attachments, schema, null);
    }

    protected String executePonder(String taskDescription, List<Part> attachments, Schema schema, String language) {
		String finalPrompt = taskDescription;
		String targetLang = (language == null || language.isBlank()) ? "English" : language;

		finalPrompt += "\n\n--- GLOBAL ENCODING & LANGUAGE RULES ---\n"
				+ "1. PRIMARY LANGUAGE: You MUST write your ENTIRE output (except for exact quotes) in " + targetLang.toUpperCase() + "!\n"
				+ "2. FIELD-SPECIFIC OVERRIDES: If your specific instructions require certain JSON fields (e.g., 'vector_query' or 'instructions') to be in "+ internalLanguage +", you MUST strictly obey those field-level language rules. Everything else defaults to " + targetLang.toUpperCase() + ".\n"
				+ "3. SOURCE MATERIAL: Maintain the target language even if the source material is in another language.\n"
				+ "4. MARKDOWN, KATEX & ENCODING: The output will be rendered using standard Markdown and KaTeX.\n"
				+ "   - Write all regular text and special characters (like ä, ö, ü, ß) in standard raw UTF-8.\n"
				+ "   - Output raw text directly. Avoid URL encoding (like %C3) or double-percents (like %%).\n"
				+ "   - Use '$' (inline) and '$$' (block) strictly to wrap mathematical formulas and equations.";
		
		System.out.println("[" + agentName + "] is pondering...");
		return geminiClient.ponder(agentName, persona, finalPrompt, attachments, schema);
	}
    
    public String getAgentName() {
        return agentName;
    }
}