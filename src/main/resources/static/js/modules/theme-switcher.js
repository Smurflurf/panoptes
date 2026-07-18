// --- THEME SWITCHER & EYE ANIMATION ---
document.addEventListener('DOMContentLoaded', () => {
    const themes = ['dark', 'light', 'midnight'];
    
    // Die verschiedenen Augen-Stadien für die Themes
    const eyeClasses = {
        'dark': 'fa-solid fa-eye',          // Thicc
        'light': 'fa-regular fa-eye',       // Thin
        'midnight': 'fa-solid fa-eye' 		// Sleeping
    };

    const eyeIcon = document.querySelector('.brand-title i');
    
    if (eyeIcon) {
        eyeIcon.style.cursor = 'pointer';
        eyeIcon.title = 'Switch Theme';
        
        let currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
        eyeIcon.className = eyeClasses[currentTheme];
        
        eyeIcon.addEventListener('click', (e) => {
            e.preventDefault(); 
            e.stopPropagation();
            
            currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
            let nextIndex = (themes.indexOf(currentTheme) + 1) % themes.length;
            let nextTheme = themes[nextIndex];
            
            document.documentElement.setAttribute('data-theme', nextTheme);
            localStorage.setItem('ideenatlas-theme', nextTheme);
            
            eyeIcon.className = eyeClasses[nextTheme];
        });
    }
});