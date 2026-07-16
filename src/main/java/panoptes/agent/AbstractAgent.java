package panoptes.agent;

import java.util.List;

import com.google.genai.types.Part;
import com.google.genai.types.Schema;

import panoptes.llm.GeminiClient;

public abstract class AbstractAgent {

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
				+ "1. CRITICAL RULE: You MUST write your ENTIRE output (except for exact quotes) in " + targetLang + "!\n"
				+ "2. Do not switch languages even if the source material is in another language.\n"
				+ "3. MARKDOWN & KATEX: The output will be rendered using standard Markdown and KaTeX. \n"
				+ "   - Use standard UTF-8 characters for all regular text and umlauts (e.g., ä, ö, ü).\n"
				+ "   - Use '$' (inline) and '$$' (block) STRICTLY and EXCLUSIVELY for mathematical formulas and equations (e.g., $O(n^2)$).\n"
				+ "   - Never use '$' as a replacement for letters in regular words.";

		System.out.println("[" + agentName + "] is pondering...");
		return geminiClient.ponder(persona, finalPrompt, attachments, schema);
	}
    
    public String getAgentName() {
        return agentName;
    }
}