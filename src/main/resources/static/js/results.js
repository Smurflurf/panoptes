import { initMarkdownRenderer } from './modules/markdown-renderer.js';
import { initPdfExport } from './modules/pdf-exporter.js';

document.addEventListener('DOMContentLoaded', () => {
    // 1. Render the Markdown report
    initMarkdownRenderer();
    
    // 2. Activate the PDF Download button
    initPdfExport();
});