# 👁️ Panoptes

**An Autonomous, Cross-Disciplinary AI Research Pipeline**

Panoptes is a multi-agent artificial intelligence system built with Java and Spring Boot. Designed to function as an autonomous academic researcher, the system moves beyond simple text summarization. It actively queries scientific databases, discovers cross-disciplinary conceptual connections, rigorously audits its own citations to prevent hallucinations, and conducts adversarial counter-research to challenge its own findings.

By integrating the semantic vector search of [Ideenatlas.eu](https://ideenatlas.eu) with the empirical metadata verification of [OpenAlex](https://openalex.org) and the reasoning capabilities of Large Language Models (e.g., Google Gemini), Panoptes produces robust, fully cited, and epistemically grounded research reports.

---

## Core Capabilities

*   **Multi-Agent Orchestration:** A sequential pipeline of specialized AI models (Extractors, Planners, Investigators, Architects, Writers, and Auditors) collaborating asynchronously to synthesize literature.
*   **Cross-Disciplinary Serendipity:** Utilizes the Ideenatlas API to bypass traditional keyword matching, discovering structurally analogous research from disparate academic fields via vector proximity.
*   **Strict Hallucination Prevention:** A dedicated Quality Assurance Orchestrator performs a ruthless two-step verification. It validates the existence of cited paper IDs against a local database and subsequently deploys an auditing agent to ensure the drafted claims correctly reflect the original abstracts without conceptual shifting.
*   **Adversarial Red Teaming:** The system acts as its own devil's advocate. It identifies logical blind spots in its drafted report and launches targeted counter-searches to weave scientific debate, limitations, and counter-evidence into the final text.
*   **High Concurrency Architecture:** Built on Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) to enable massive parallelization of API requests and agent evaluations without blocking OS threads.
*   **Automated Academic Formatting:** Dynamically generates Markdown reports with inline XML citations, converting them into UI elements containing exact quotes, peer-review status, and citation metrics.

---

## Architecture & Agent Methodology

The logic governing the individual LLM agents and the pipeline's epistemological framework is highly complex. 

For a detailed breakdown of the internal workflows, the adversarial red teaming process, and the specific role of each AI agent, please refer to the dedicated documentation:
👉 **[Read the Agent Architecture Documentation](src/main/java/panoptes/agent/README.md)**

---

## Getting Started

### Prerequisites
*   **Java 21** (or higher) - Required for Virtual Threads.
*   **Maven** - For building the project.
*   **API Keys** - Required for LLM inference and metadata validation.

### 1. Configuration
Open `src/main/resources/application.properties` and provide your API keys:

*   **Google Gemini:** Panoptes requires access to Google's Gemini models. Obtain your API keys at [Google AI Studio](https://aistudio.google.com/app/apikey). You can provide multiple keys separated by commas; the system automatically rotates them to mitigate rate limits.
    ```properties
    panoptes.llm.api-keys=YOUR_GEMINI_KEY_1,YOUR_GEMINI_KEY_2
    ```
*   **OpenAlex:** Used to verify paper metadata (citations, peer-review status). Providing an email grants access to the "polite pool" for faster responses. Register at [OpenAlex](https://openalex.org/).
    ```properties
    openalex.email=your.email@example.com
    openalex.api-keys=YOUR_OPENALEX_KEY_1
    ```

### 2. Build and Run
Clone the repository, compile the project, and start the Spring Boot application:

```bash
git clone https://github.com/YOUR_USERNAME/panoptes.git
cd panoptes
mvn clean install
mvn spring-boot:run
```

### 3. Access the Interface
Open your web browser and navigate to:
**`http://localhost:9090`**

*Note: The user interface supports multimodal input, allowing users to define research questions via text or direct audio recordings.*

---

## License

This project is open-source and available under the **Apache License 2.0**. You are free to use, modify, and distribute this software, provided that proper credit is given to the original author(s) as outlined in the license terms.

*Note: The data provided by the external search APIs (Ideenatlas, OpenAlex, Semantic Scholar) are subject to their respective terms of service.*
