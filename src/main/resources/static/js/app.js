document.addEventListener('DOMContentLoaded', () => {
    const textarea = document.getElementById('research-query');
    const goBtn = document.getElementById('btn-go');
    const recordBtn = document.getElementById('btn-record');
    const attachmentsArea = document.getElementById('attachments-area');
    
    let attachedFiles = [];

	// --- 0. Helper function to read the CSRF cookie
	function getCsrfToken() {
		const match = document.cookie.match(new RegExp('(^| )XSRF-TOKEN=([^;]+)'));
		return match ? match[2] : '';
	}
	
    // --- 1. Form Validation ---
    function validateForm() {
        if (textarea && (textarea.value.trim().length > 0 || attachedFiles.length > 0)) {
            goBtn.removeAttribute('disabled');
        } else if (goBtn) {
            goBtn.setAttribute('disabled', 'true');
        }
    }

    if (textarea) {
        textarea.addEventListener('input', function() {
            this.style.height = 'auto';
            this.style.height = (this.scrollHeight) + 'px';
            validateForm();
        });
    }

    // --- 2. Smooth Audio Visualizer ---
    const canvas = document.getElementById('fullscreen-visualizer');
    if (!canvas) return; // Prevent errors on sub-pages
    
    const ctx = canvas.getContext('2d', { alpha: true });
    
    const VIS_CONFIG = {
        blobPoints: 8, blobBaseRadius: 40, blobWobbleSpeed: 0.002, blobWobbleAmp: 6,
        volumeSmoothing: 0.88, poolSize: 120, friction: 0.95, windSpeedBase: 0.04,
        windChangeSpeed: 0.0005, sparkSpeedBase: 1.5, sparkLife: 1500, waveSpeed: 3.5,
        waveForce: 4.0, peakThreshold: 55, peakDelta: 15
    };
    
    let particlePool = Array.from({ length: VIS_CONFIG.poolSize }, () => ({ active: false, x: 0, y: 0, vx: 0, vy: 0 }));
    let wavePool = [ { active: false, r: 0, opacity: 1 }, { active: false, r: 0, opacity: 1 } ];
    let blobPhases = Array.from({ length: VIS_CONFIG.blobPoints }, () => Math.random() * Math.PI * 2);

    let audioContext, analyser, microphone, mediaRecorder, animationFrameId;
    let audioChunks = [];
    let isRecording = false;

    async function toggleRecording() {
        if (isRecording) stopRecording();
        else await startRecording();
    }

    async function startRecording() {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            
            // Audio Encoder Setup
            let mimeType = 'audio/webm';
            let fileExt = 'webm';
            if (!MediaRecorder.isTypeSupported(mimeType)) {
                if (MediaRecorder.isTypeSupported('audio/mp4')) { mimeType = 'audio/mp4'; fileExt = 'm4a'; }
                else { mimeType = ''; fileExt = 'mp3'; }
            }

            mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
            audioChunks = [];
            
            mediaRecorder.ondataavailable = e => audioChunks.push(e.data);
            mediaRecorder.onstop = () => {
                const audioBlob = new Blob(audioChunks, { type: mediaRecorder.mimeType || 'audio/webm' });
                const fileName = `Voice_Note_${new Date().getTime()}.${fileExt}`;
                const file = new File([audioBlob], fileName, { type: audioBlob.type });
                
                attachAudioFile(file);
                stream.getTracks().forEach(track => track.stop());
            };

            audioContext = new (window.AudioContext || window.webkitAudioContext)();
            analyser = audioContext.createAnalyser();
            analyser.fftSize = 512;
            microphone = audioContext.createMediaStreamSource(stream);
            microphone.connect(analyser);

            mediaRecorder.start();
            isRecording = true;
            recordBtn.classList.add('is-recording');
            textarea.placeholder = "Listening...";
            
            particlePool.forEach(p => p.active = false);
            wavePool.forEach(w => w.active = false);
            visualize(performance.now());

        } catch (err) {
            console.error("Microphone access denied", err);
        }
    }

    function stopRecording() {
        isRecording = false;
        recordBtn.classList.remove('is-recording');
        textarea.placeholder = "Define your research question or topic...";
        
        if (mediaRecorder && mediaRecorder.state !== "inactive") {
            mediaRecorder.stop();
        }
        if (animationFrameId) cancelAnimationFrame(animationFrameId);
        ctx.clearRect(0, 0, canvas.width, canvas.height);
    }

    let smoothedVolume = 0;
    let lastRawVolume = 0;
    let sparkSpawnTimer = 0;
    let lastTime = performance.now();

    function visualize(currentTime) {
        if (!isRecording) return;
        animationFrameId = requestAnimationFrame(visualize);

        const dpr = window.devicePixelRatio || 1;

        // Use offsetWidth instead of innerWidth to avoid scrollbar layout shifts
        const cw = Math.floor(canvas.offsetWidth * dpr);
        const ch = Math.floor(canvas.offsetHeight * dpr);

        // Prevent flickering and performance loss by resizing only when necessary
        if (canvas.width !== cw || canvas.height !== ch) {
            canvas.width = cw;
            canvas.height = ch;
        }

        const btnRect = recordBtn.getBoundingClientRect();
        const centerX = (btnRect.left + btnRect.width / 2) * dpr;
        const centerY = (btnRect.top + btnRect.height / 2) * dpr;
        const dt = Math.min(currentTime - lastTime, 32) / 16.66; 
        lastTime = currentTime;

        const dataArray = new Uint8Array(analyser.frequencyBinCount);
        analyser.getByteFrequencyData(dataArray);

        let sum = 0;
        for (let i = 0; i < dataArray.length / 2; i++) sum += dataArray[i];
        const rawVolume = sum / (dataArray.length / 2);

        // Trigger wave emission on audio peaks
        if (rawVolume > VIS_CONFIG.peakThreshold && (rawVolume - lastRawVolume) > VIS_CONFIG.peakDelta) {
            const wave = wavePool.find(w => !w.active);
            if (wave) { wave.active = true; wave.r = VIS_CONFIG.blobBaseRadius * dpr; wave.opacity = 0.5; }
        }
        lastRawVolume = rawVolume;
        smoothedVolume = (smoothedVolume * VIS_CONFIG.volumeSmoothing) + (rawVolume * (1 - VIS_CONFIG.volumeSmoothing));
        
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        
        // Render Smooth Blob using Quadratic Bezier Curves
        const rBase = (VIS_CONFIG.blobBaseRadius + smoothedVolume * 0.6) * dpr;
        const points = [];
        
        // Calculate points dynamically based on volume and wobble
        for (let i = 0; i < VIS_CONFIG.blobPoints; i++) {
            const angle = (i / VIS_CONFIG.blobPoints) * Math.PI * 2;
            const wobble = Math.sin(currentTime * VIS_CONFIG.blobWobbleSpeed + blobPhases[i]) * VIS_CONFIG.blobWobbleAmp * dpr;
            const r = rBase + wobble;
            points.push({
                x: centerX + Math.cos(angle) * r,
                y: centerY + Math.sin(angle) * r
            });
        }
        
        // Connect points with smooth curves
        ctx.beginPath();
        let midX = (points[points.length - 1].x + points[0].x) / 2;
        let midY = (points[points.length - 1].y + points[0].y) / 2;
        ctx.moveTo(midX, midY);
        
        for (let i = 0; i < points.length; i++) {
            const nextNode = points[(i + 1) % points.length];
            const nextMidX = (points[i].x + nextNode.x) / 2;
            const nextMidY = (points[i].y + nextNode.y) / 2;
            ctx.quadraticCurveTo(points[i].x, points[i].y, nextMidX, nextMidY);
        }
        ctx.closePath();
        
        const volFactor = Math.min(1.0, smoothedVolume / 120);
        ctx.fillStyle = `rgba(202, 3, 0, ${0.15 + volFactor * 0.2})`;
        ctx.fill();
        ctx.strokeStyle = `rgba(242, 88, 88, ${0.3 + volFactor * 0.3})`;
        ctx.lineWidth = 2 * dpr;
        ctx.stroke();

        // Render emitting waves
        wavePool.forEach(w => {
            if (!w.active) return;
            w.r += VIS_CONFIG.waveSpeed * dt * dpr;
            w.opacity -= 0.015 * dt;
            if (w.opacity <= 0) { w.active = false; return; }
            ctx.beginPath();
            ctx.arc(centerX, centerY, w.r, 0, Math.PI * 2);
            ctx.strokeStyle = `rgba(242, 88, 88, ${w.opacity})`;
            ctx.stroke();
        });

        // Spawn and render sparks
        sparkSpawnTimer += dt;
        if (smoothedVolume > 25 && sparkSpawnTimer > 5) {
            sparkSpawnTimer = 0;
            const p = particlePool.find(p => !p.active);
            if (p) {
                p.active = true;
                const angle = Math.random() * Math.PI * 2;
                p.x = centerX + Math.cos(angle) * rBase;
                p.y = centerY + Math.sin(angle) * rBase;
                const speed = (VIS_CONFIG.sparkSpeedBase + (smoothedVolume / 80)) * dpr;
                p.vx = Math.cos(angle) * speed;
                p.vy = Math.sin(angle) * speed;
                p.maxLife = VIS_CONFIG.sparkLife;
                p.spawnTime = currentTime;
            }
        }

        ctx.globalCompositeOperation = 'screen';
        particlePool.forEach(p => {
            if (!p.active) return;
            const age = currentTime - p.spawnTime;
            if (age > p.maxLife) { p.active = false; return; }
            
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            
            ctx.globalAlpha = 1 - Math.pow(age / p.maxLife, 2);
            ctx.fillStyle = '#ff8c8c';
            ctx.beginPath();
            ctx.arc(p.x, p.y, 2 * dpr, 0, Math.PI * 2);
            ctx.fill();
        });
        ctx.globalCompositeOperation = 'source-over';
        ctx.globalAlpha = 1.0;
    }

    if (recordBtn) recordBtn.addEventListener('click', toggleRecording);

    // --- 3. UI Attachment Logic ---
    function attachAudioFile(file) {
        attachedFiles.push(file);
        
        const chip = document.createElement('div');
        chip.className = 'attachment-chip';
        chip.innerHTML = `
            <i class="fa-solid fa-play"></i> 
            <span>${file.name}</span>
            <i class="fa-solid fa-xmark delete-btn"></i>
        `;
        
        chip.querySelector('.delete-btn').addEventListener('click', () => {
            attachedFiles = attachedFiles.filter(f => f !== file);
            chip.remove();
            validateForm();
        });
        
        attachmentsArea.appendChild(chip);
        validateForm();
    }

    // --- 4. Form Submission & Redirect ---
    if (goBtn) {
        goBtn.addEventListener('click', async (e) => {
            // Prevent event bubbling
            e.preventDefault();
            e.stopPropagation();

            if (goBtn.hasAttribute('disabled')) return;

            const formData = new FormData();
            if (textarea.value.trim().length > 0) {
                formData.append('idea-text', textarea.value.trim());
            }
            attachedFiles.forEach(file => {
                formData.append('files', file, file.name);
            });

            const langInput = document.getElementById('language-input');
            const langVal = (langInput && langInput.value.trim() !== '') ? langInput.value.trim() : 'English';
            formData.append('language', langVal);

            // Lock UI immediately
            goBtn.setAttribute('disabled', 'true');
            textarea.setAttribute('disabled', 'true');
            recordBtn.setAttribute('disabled', 'true');
            if (langInput) langInput.setAttribute('disabled', 'true');
            goBtn.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin"></i> Starting..';

			try {
				const response = await fetch('/api/research/init', {
					method: 'POST',
					headers: {
						'X-XSRF-TOKEN': getCsrfToken()
					},
					body: formData
				});

                if (!response.ok) throw new Error("Server error");

                const data = await response.json();

                // Direct routing to status page via server-provided ID
                window.location.href = `/status/${data.jobId}`;

            } catch (err) {
                alert("Failed to initialize pipeline: " + err.message);
                
                // Unlock UI on failure
                goBtn.removeAttribute('disabled');
                textarea.removeAttribute('disabled');
                recordBtn.removeAttribute('disabled');
                if (langInput) langInput.removeAttribute('disabled');
                goBtn.innerHTML = 'Initialize <i class="fa-solid fa-arrow-right"></i>';
            }
        });
    }
});