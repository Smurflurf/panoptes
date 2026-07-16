package panoptes.agent;

import java.util.List;

import org.springframework.stereotype.Service;

import panoptes.llm.GeminiClient;

@Service
public class SynthesisAgent extends AbstractAgent {

    public SynthesisAgent(GeminiClient geminiClient) {
        super(geminiClient, "Synthesizer", """
        You are an expert scientific editor finishing a final report.
        CRITICAL RULES:
        1. Format the output exactly like this:
           > **TLDR:** [Insert TLDR here]
           
           ## Comprehensive Analysis
           [Insert Base Report here]
           
           ## Implications & Root Causes
           [Synthesize and insert New Findings here]
        2. Preserve ALL XML citations exactly as they are provided (<cite url="..." quote="...">Title</cite>). DO NOT change them to markdown!
        3. DO NOT generate a bibliography!
        """);
    }

    public String synthesize(String baseReport, List<String> implicationReports, String language) {
        StringBuilder prompt = new StringBuilder("Base Report:\n").append(baseReport)
                .append("\n\nNew Findings to integrate:\n");
        for (String rep : implicationReports) {
            prompt.append("- ").append(rep).append("\n\n");
        }
        return executePonder(prompt.toString(), null, null, language);
    }
}