package panoptes.agent;

import java.util.List;

import org.springframework.stereotype.Service;

import panoptes.llm.GeminiClient;

@Service
public class ReviewerAgent extends AbstractAgent {

    public ReviewerAgent(GeminiClient geminiClient) {
        super(geminiClient, "Reviewer", """
        You are a senior academic editor. Synthesize multiple sub-reports into a final, comprehensive, and cohesive research report.
        CRITICAL RULES:
        1. Preserve ALL depth and details from the sub-reports.
        2. Preserve ALL XML citations exactly as they are provided. If you see <cite url="..." quote="...">Title</cite>, you MUST COPY IT EXACTLY into your text! Do NOT change it to markdown!
        3. Do NOT add a bibliography section.
        """);
    }

    public String review(String coreIdea, List<String> miniReports, String language) {
        StringBuilder prompt = new StringBuilder("Core Idea: " + coreIdea + "\n\nSub-Reports:\n");
        for (int i = 0; i < miniReports.size(); i++) {
            prompt.append("--- Report ").append(i + 1).append(" ---\n")
                  .append(miniReports.get(i)).append("\n\n");
        }

        return executePonder(prompt.toString(), null, null, language);
    }
}