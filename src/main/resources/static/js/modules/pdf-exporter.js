export function initPdfExport() {
    const btn = document.getElementById('btn-download-pdf');
    if (!btn) return;

    btn.addEventListener('click', () => {
        // 1. Extract report title from the main container's data attribute
        const mainContainer = document.getElementById('app-main');
        const reportTitle = mainContainer ? mainContainer.dataset.reportTitle : 'Panoptes_Report';
        
        // 2. Temporarily overwrite the document title to manipulate the default PDF filename
        const originalTitle = document.title;
        // Strip illegal characters for safe filenames
        const safeFilename = reportTitle.replace(/[^a-zA-Z0-9 -]/g, "_");
        document.title = `Panoptes_${safeFilename}`;

        // 3. Trigger native print dialog
        window.print();

        // 4. Restore original document title
        document.title = originalTitle;
    });
}