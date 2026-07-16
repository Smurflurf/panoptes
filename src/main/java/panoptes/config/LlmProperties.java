package panoptes.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * Configuration properties binding for the Large Language Model (LLM) integration.
 * <p>
 * This class automatically maps configuration values (defined in {@code application.yml} or 
 * {@code application.properties} under the {@code panoptes.llm} prefix) to Java fields. 
 * It manages the availability of API keys and the specific model identifiers (e.g., Gemini versions) 
 * required by the {@link panoptes.llm.GeminiClient} to execute agent prompts.
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "panoptes.llm")
public class LlmProperties {
    private List<String> apiKeys;
    private List<String> models;

    public List<String> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<String> apiKeys) { this.apiKeys = apiKeys; }
    public List<String> getModels() { return models; }
    public void setModels(List<String> models) { this.models = models; }
}