export function initMarkdownRenderer() {
    const rawMd = document.getElementById('raw-markdown');
    const outputDiv = document.getElementById('content-output');
    
    if (!rawMd || !outputDiv) return;

	let mdText = rawMd.value
	
	mdText = mdText.replace(/(?<!\\)\$\$(.*?)(?<!\\)\$\$/gs, function(match, math) {
		let fixed = math.replace(/\\/g, '\\\\');
		fixed = fixed.replace(/(\d),(\d)/g, '$1{,}$2');
		return '$$' + fixed + '$$';
	});

	mdText = mdText.replace(/(?<!\\)\$(.*?)(?<!\\)\$/g, function(match, math) {
		let fixed = math.replace(/\\/g, '\\\\');
		fixed = fixed.replace(/(\d),(\d)/g, '$1{,}$2');
		return '$' + fixed + '$';
	});

    marked.setOptions({ breaks: true, gfm: true });
    outputDiv.innerHTML = marked.parse(mdText);
    
    if (window.renderMathInElement) {
        renderMathInElement(outputDiv, {
            delimiters: [
                {left: '$$', right: '$$', display: true},
                {left: '$', right: '$', display: false},
                {left: '\\(', right: '\\)', display: false},
                {left: '\\[', right: '\\]', display: true}
            ],
            throwOnError: false
        });
    }
    
    tippy('.citation-link', {
        animation: 'shift-away',
        theme: 'dark', 
        maxWidth: 400,
        allowHTML: true,
        placement: 'top',
        interactive: true,
        trigger: 'mouseenter click',
    });

    document.querySelectorAll('.citation-link').forEach(link => {
        link.addEventListener('click', (e) => {
            if (window.matchMedia("(pointer: coarse)").matches) {
                if (!link.classList.contains('tooltip-is-open')) {
                    e.preventDefault(); 
                    link.classList.add('tooltip-is-open');
                    setTimeout(() => link.classList.remove('tooltip-is-open'), 3000);
                }
            }
        });
    });
}