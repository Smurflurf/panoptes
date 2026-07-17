document.addEventListener('DOMContentLoaded', () => {
    
    // 1. Extract Job ID from the HTML data attribute
    const mainContainer = document.getElementById('status-main');
    if (!mainContainer) return;
    
    const jobId = mainContainer.dataset.jobId;
    if (!jobId) return;

    const consoleEl = document.getElementById('sse-console');
    const titleEl = document.getElementById('live-status-title');
    const apiBanner = document.getElementById('api-delay-banner');
    const apiRetryCount = document.getElementById('api-retry-count');
    const closeApiBannerBtn = document.getElementById('close-api-banner');
    
    // Banner logic: Re-open after 10 new consecutive errors
    let dismissedAtCount = -1; 
    
    if (closeApiBannerBtn) {
        closeApiBannerBtn.addEventListener('click', () => {
            apiBanner.classList.add('hidden-data');
            // Remember the error count when the user dismissed the banner
            dismissedAtCount = parseInt(apiRetryCount.textContent, 10) || 0;
        });
    }

    // 2. Establish Server-Sent Events (SSE) connection
    const eventSource = new EventSource(`/api/research/stream/${jobId}`);

    eventSource.addEventListener('update', (e) => {
        const rawData = e.data;

        // Handle API Delay Warnings specifically
        if (rawData.startsWith("[API_DELAY_WARNING]:")) {
            const count = parseInt(rawData.split(":")[1], 10);

            if (apiBanner && apiRetryCount) {
                apiRetryCount.textContent = count;

                let hue = 45;       // Starting hue: Orange
                let lightness = 50; // Starting lightness: Normal

                // Color transition starts after the 5th error
                if (count > 5) {
                    const excessErrors = count - 5;
                    // Subtract 1 degree per 2 errors (0.5 per error)
                    hue = Math.max(0, Math.floor(45 - (excessErrors * 0.5)));

                    // Couple lightness to the hue value
                    // If Hue = 45 -> 50% Lightness. If Hue = 0 -> 35% Lightness (Dark Red).
                    lightness = Math.floor(35 + (15 * (hue / 45)));
                }

                // Update CSS variables for the banner dynamically
                apiBanner.style.setProperty('--warn-color', `hsl(${hue}, 100%, ${lightness}%)`);
                apiBanner.style.setProperty('--warn-bg', `hsla(${hue}, 100%, ${lightness}%, 0.1)`);

                if (dismissedAtCount === -1 || count >= (dismissedAtCount + 10)) {
                    apiBanner.classList.remove('hidden-data');
                    apiBanner.style.display = 'flex'; // Ensure flex layout is restored
                    dismissedAtCount = -1;
				}
			}
			return;
		}

		// Standard logging for pipeline updates
		let logText = rawData;

		logText = logText.replace(/(?<!\\)\$\$(.*?)(?<!\\)\$\$/gs, function(match, math) {
			return '$$' + math.replace(/(\d),(\d)/g, '$1{,}$2') + '$$';
		});
		logText = logText.replace(/(?<!\\)\$(.*?)(?<!\\)\$/g, function(match, math) {
			return '$' + math.replace(/(\d),(\d)/g, '$1{,}$2') + '$';
		});

		const line = document.createElement('div');
		line.className = 'log-line';
		line.textContent = "> " + logText;

		consoleEl.appendChild(line);

		if (window.renderMathInElement) {
			renderMathInElement(line, {
				delimiters: [
					{ left: '$$', right: '$$', display: true },
					{ left: '$', right: '$', display: false },
					{ left: '\\(', right: '\\)', display: false },
					{ left: '\\[', right: '\\]', display: true }
				],
				throwOnError: false
			});
		}

		consoleEl.scrollTop = consoleEl.scrollHeight;
    });

    eventSource.addEventListener('complete', (e) => {
        eventSource.close();
        
        // Check if the last log indicates a failure
        const lastLine = consoleEl.lastElementChild?.textContent || "";
        const isError = lastLine.includes("ERROR");

        const spinner = document.querySelector('.status-spinner');
        if(spinner) {
            spinner.classList.remove('fa-spin', 'fa-circle-notch');
            
            if (isError) {
                spinner.classList.add('fa-triangle-exclamation');
                spinner.style.color = '#f25858'; // Danger color
                titleEl.textContent = "Analysis Failed.";
            } else {
                spinner.classList.add('fa-check-circle');
                spinner.style.color = '#34a853'; // Success color
                titleEl.textContent = "Analysis Complete. Redirecting...";
                
                setTimeout(() => {
                    window.location.href = `/results/${jobId}`;
                }, 1000);
            }
        }
    });

    eventSource.onerror = (e) => {
        eventSource.close();
    };
});