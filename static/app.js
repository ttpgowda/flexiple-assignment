// State Management
const state = {
    filters: null,
    rubric: null,
    shownProfiles: [],
    chatHistory: []
};

// DOM Elements
const els = {
    initialView: document.getElementById('initial-view'),
    mainView: document.getElementById('main-view'),
    loadingState: document.getElementById('loading-state'),
    resultsState: document.getElementById('results-state'),
    frozenState: document.getElementById('frozen-state'),
    errorOverlay: document.getElementById('error-overlay'),
    
    searchInput: document.getElementById('initial-search-input'),
    searchBtn: document.getElementById('initial-search-btn'),
    freezeBtn: document.getElementById('freeze-btn'),
    chatInput: document.getElementById('chat-input'),
    chatSendBtn: document.getElementById('chat-send-btn'),
    errorDismissBtn: document.getElementById('error-dismiss-btn'),
    newSearchBtn: document.getElementById('new-search-btn'),
    
    filtersContainer: document.getElementById('filters-container'),
    rubricContainer: document.getElementById('rubric-container'),
    candidatesContainer: document.getElementById('candidates-container'),
    chatHistory: document.getElementById('chat-history'),
    resultsCountText: document.getElementById('results-count-text'),
    loadingText: document.getElementById('loading-text'),
    errorMessage: document.getElementById('error-message')
};

// Event Listeners
els.searchBtn.addEventListener('click', handleInitialSearch);
els.chatSendBtn.addEventListener('click', handleRefinement);
els.chatInput.addEventListener('keypress', (e) => { if (e.key === 'Enter') handleRefinement(); });
els.freezeBtn.addEventListener('click', handleFreeze);
els.errorDismissBtn.addEventListener('click', () => els.errorOverlay.classList.add('hidden'));
els.newSearchBtn.addEventListener('click', resetApp);

// Functions
async function handleInitialSearch() {
    const query = els.searchInput.value.trim();
    if (!query) return;

    showLoading("Generating AI filters & rubric...");
    
    try {
        // 1. Generate Criteria
        const genRes = await fetchApi('/api/generate', { query });
        state.filters = genRes.objective_filters;
        state.rubric = genRes.subjective_rubric;
        
        renderContext();
        
        // 2. Initial Search
        els.loadingText.textContent = "Scoring candidates...";
        await performSearch();
        
        // Add initial AI message to chat
        addChatMessage('ai', "I've found these candidates based on your criteria. How do they look? You can tell me if someone is too junior, missing a specific skill, or if the company types aren't right.");
        
        showResults();
    } catch (err) {
        showError(err.message);
        hideLoading(true); // Return to initial view
    }
}

async function performSearch() {
    const searchRes = await fetchApi('/api/search', {
        objective_filters: state.filters,
        subjective_rubric: state.rubric
    });
    
    state.shownProfiles = searchRes.profiles.map(p => p.profile);
    
    els.resultsCountText.textContent = `${searchRes.matchedCount} Match${searchRes.matchedCount !== 1 ? 'es' : ''} (Top ${searchRes.profiles.length} Shown)`;
    renderCandidates(searchRes.profiles);
}

async function handleRefinement() {
    const feedback = els.chatInput.value.trim();
    if (!feedback) return;
    
    els.chatInput.value = '';
    addChatMessage('user', feedback);
    
    showLoading("Analyzing feedback and adjusting criteria...");
    
    try {
        const refineRes = await fetchApi('/api/refine', {
            objective_filters: state.filters,
            subjective_rubric: state.rubric,
            shown_profiles: state.shownProfiles,
            chat_history: state.chatHistory
        });
        
        state.filters = refineRes.objective_filters;
        state.rubric = refineRes.subjective_rubric;
        
        addChatMessage('ai', refineRes.explanation);
        renderContext();
        
        els.loadingText.textContent = "Scoring updated candidates...";
        await performSearch();
        
        showResults();
    } catch (err) {
        showError("Failed to refine: " + err.message);
        showResults(); // Go back to results to let them try again
    }
}

async function handleFreeze() {
    showLoading("Freezing search...");
    try {
        await fetchApi('/api/freeze', {
            objective_filters: state.filters,
            subjective_rubric: state.rubric,
            profiles: [] // We don't need to send the full objects back just to echo
        });
        showFrozenState();
    } catch (err) {
        showError(err.message);
        showResults();
    }
}

// API Helper
async function fetchApi(endpoint, body) {
    const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    });
    
    const data = await response.json();
    if (!response.ok) {
        throw new Error(data.message || 'API request failed');
    }
    return data;
}

// UI Rendering
function renderContext() {
    // Filters
    const f = state.filters;
    let html = '';
    
    if (f.skills && f.skills.length > 0) {
        html += `<div class="detail-row"><span class="detail-label">Skills:</span> <div class="tag-list">` + 
                f.skills.map(s => `<span class="tag">${s}</span>`).join('') + `</div></div>`;
    }
    html += `<div class="detail-row"><span class="detail-label">Min Exp:</span> <span>${f.minYearsExperience || 0} years</span></div>`;
    if (f.maxYearsExperience) html += `<div class="detail-row"><span class="detail-label">Max Exp:</span> <span>${f.maxYearsExperience} years</span></div>`;
    if (f.location) html += `<div class="detail-row"><span class="detail-label">Location:</span> <span>${f.location}</span></div>`;
    if (f.companyTypes && f.companyTypes.length > 0) {
        html += `<div class="detail-row"><span class="detail-label">Companies:</span> <div class="tag-list">` + 
                f.companyTypes.map(c => `<span class="tag">${c}</span>`).join('') + `</div></div>`;
    }
    
    els.filtersContainer.innerHTML = html;
    
    // Rubric
    els.rubricContainer.innerHTML = state.rubric.guidelines.map(g => `<div class="rubric-item">${g}</div>`).join('');
}

function renderCandidates(scoredProfiles) {
    if (scoredProfiles.length === 0) {
        els.candidatesContainer.innerHTML = `<div class="candidate-card" style="text-align: center; color: var(--text-secondary);">No profiles matched the current objective filters. Try asking the AI to broaden the search.</div>`;
        return;
    }

    els.candidatesContainer.innerHTML = scoredProfiles.map((sp, index) => {
        const p = sp.profile;
        const scoreClass = sp.score >= 80 ? 'score-high' : sp.score >= 50 ? 'score-medium' : 'score-low';
        
        return `
            <div class="candidate-card">
                <div class="card-header">
                    <div>
                        <div class="candidate-name">#${index + 1} - ${p.name}</div>
                        <div class="candidate-title">${p.current_title} @ ${p.current_company} (${p.current_company_type})</div>
                    </div>
                    <div class="score-badge ${scoreClass}">${sp.score}/100</div>
                </div>
                <div class="candidate-meta">
                    <span class="meta-item">💼 ${p.years_experience} YOE</span>
                    <span class="meta-item">📍 ${p.location}</span>
                </div>
                <div class="ai-explanation">
                    <strong>AI Note:</strong> ${sp.explanation}
                </div>
                <div class="tag-list mt-2">
                    ${p.skills.slice(0, 5).map(s => `<span class="tag" style="background: rgba(255,255,255,0.05); border: 1px solid rgba(255,255,255,0.1); color: #cbd5e1;">${s}</span>`).join('')}
                    ${p.skills.length > 5 ? `<span class="tag" style="background: transparent; border: none;">+${p.skills.length - 5}</span>` : ''}
                </div>
            </div>
        `;
    }).join('');
}

function addChatMessage(role, message) {
    state.chatHistory.push({ role, message });
    
    const div = document.createElement('div');
    div.className = `chat-message msg-${role}`;
    div.textContent = message;
    els.chatHistory.appendChild(div);
    els.chatHistory.scrollTop = els.chatHistory.scrollHeight;
}

// View Management
function showLoading(text) {
    els.loadingText.textContent = text;
    els.initialView.classList.add('hidden');
    els.mainView.classList.remove('hidden');
    els.loadingState.classList.remove('hidden');
    els.resultsState.classList.add('hidden');
    els.frozenState.classList.add('hidden');
}

function showResults() {
    els.loadingState.classList.add('hidden');
    els.resultsState.classList.remove('hidden');
}

function showFrozenState() {
    els.loadingState.classList.add('hidden');
    els.resultsState.classList.add('hidden');
    els.frozenState.classList.remove('hidden');
    els.freezeBtn.classList.add('hidden');
}

function hideLoading(isInitial = false) {
    els.loadingState.classList.add('hidden');
    if (isInitial) {
        els.initialView.classList.remove('hidden');
        els.mainView.classList.add('hidden');
    }
}

function showError(msg) {
    els.errorMessage.textContent = msg;
    els.errorOverlay.classList.remove('hidden');
}

function resetApp() {
    state.filters = null;
    state.rubric = null;
    state.shownProfiles = [];
    state.chatHistory = [];
    els.chatHistory.innerHTML = '';
    els.searchInput.value = '';
    
    els.mainView.classList.add('hidden');
    els.initialView.classList.remove('hidden');
    els.freezeBtn.classList.remove('hidden');
}
