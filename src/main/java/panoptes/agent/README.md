# Panoptes Agent Architecture & Epistemic Workflow

The Panoptes pipeline is not a single prompt passed to an LLM. It is a highly orchestrated, sequential multi-agent system designed to enforce academic rigor, prevent conceptual shifting, and embrace scientific serendipity.

This document outlines the distinct phases of the research pipeline and the responsibilities of each AI agent located in this directory.

---

## Phase 1: Discovery & Planning

**Goal:** Translate messy user input into a concrete research direction and formulate queries optimized for semantic vector databases.

1.  **`IdeaExtractionAgent`**: Parses unstructured multimodal input (e.g., voice transcripts). It cleans the grammar while strictly preserving the user's original metaphors and technical constraints, outputting a precise core research question.
2.  **`PlanAgent`**: Breaks the core idea down into a logical sequence of search steps. It generates human-readable sub-questions alongside dense, abstract-style English queries optimized for the Ideenatlas vector space.
3.  **`ImplicationAgent`**: After an initial round of fact-gathering, this agent analyzes the baseline findings to uncover hidden root causes or deeper cross-disciplinary connections, generating a second wave of advanced search queries.

---

## Phase 2: Investigation & Curation

**Goal:** Retrieve papers, validate their metadata, extract facts, and filter out noise.

1.  **`RankingAgent`**: Evaluates the retrieved papers against the sub-questions. It assigns relevance scores based on abstract content and OpenAlex metadata (peer-review status, citation count). It is explicitly instructed to score serendipitous, cross-disciplinary papers highly if conceptual analogies exist.
2.  **`InvestigatorAgent`**: Acts as the primary researcher. It extracts findings from the top-ranked papers and enforces epistemic humility (e.g., framing a 0-citation preprint as a "speculative claim"). It generates inline XML citations containing the exact quotes from the source abstracts.
3.  **`CoherenceAgent`**: Reviews the massive pool of extracted facts. It prunes objective noise (e.g., search engine errors) while intentionally preserving valid cross-disciplinary connections to set the foundation for the final report.

---

## Phase 3: Outlining & Drafting

**Goal:** Structure the curated facts into a coherent academic narrative.

1.  **`OutlineAgent`**: The Chief Architect. It groups the facts strictly by scientific discipline to prevent inappropriate generalization. It defines section titles and writes explicit instructions for the drafting agents.
2.  **`SectionWriterAgent`**: Drafts the report section by section. It is instructed to use the hidden XML metadata to weigh the strength of the evidence, paraphrase the findings, and strictly prevent "category errors" (e.g., applying behavioral economics directly to LLM architecture without framing it as an analogy).

---

## Phase 4: Quality Assurance (QA) & Autonomous Correction

**Goal:** Ruthlessly eliminate hallucinations, misapplied citations, and logical fallacies via a "Panel of Auditors" and actively research missing facts.

*Note: This phase is orchestrated by the `QaOrchestrator`, which performs a hard Java-level verification of cited IDs before deploying the following panel of experts. If errors are found, it triggers an autonomous ReAct (Reasoning and Acting) loop.*

1.  **`CitationQaAgent`**: The fidelity auditor. It compares the drafted text against the original source abstracts. If a claim misrepresents a paper, shifts the concept, or generalizes a toy-model to reality, this agent fails the citation.
2.  **`LogicalFallacyQaAgent`**: The logic and methodology auditor. It scans the draft specifically for "Apples to Oranges" comparisons, scale conflation, and critically, *uncited hallucinations* brought in from the LLM's pre-training data.
3.  **`QaCorrectionPlannerAgent`**: The tactical researcher. If a section fails the audit, this agent analyzes the auditor's brutal feedback and generates highly targeted search queries to actively find the missing empirical facts via the vector database.
4.  **`RevisionAgent`**: The editor. Receives the failed drafted section, the auditors' feedback, and the *newly gathered facts* from the Correction Planner. It rewrites the text to reflect reality, integrating the new valid citations and removing hallucinated claims.

---

## Phase 5: Adversarial Red Teaming

**Goal:** Challenge the generated draft, seek counter-evidence, and weave scientific debate into the report.

1.  **`RedTeamAgent`**: The Devil's Advocate. It reads the fully assembled draft to identify logical blind spots, missing baselines, or claims that rely too heavily on single studies. It generates adversarial search queries designed to falsify the report's premises.
2.  **`AdversarialInvestigatorAgent`**: Executes the counter-searches and extracts explicit contradictions, methodological critiques, and opposing findings from newly retrieved literature.
3.  **`EpistemicEditorAgent`**: Weaves the discovered counter-evidence seamlessly into the original draft, primarily by expanding the "Methodological Limitations" section. It ensures the final report reflects a balanced scientific consensus.

---

## Phase 6: Synthesis

**Goal:** Finalize the report and provide clear answers to the user.

1.  **`SilverPlateAgent`**: Compares the user's original query against the final, red-teamed report. It formulates a direct, objective "Executive Answer", ensuring that both the main findings and the most devastating methodological limitations are highlighted.
2.  **`TldrAgent`**: Condenses the executive answer into a punchy 1-2 sentence TL;DR and generates a short title for the UI.
