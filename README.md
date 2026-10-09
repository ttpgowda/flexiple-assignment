# Flexiple Engineering Challenge: AI Recruiter Refinement Loop

This project implements the sourcing refinement loop for the AI Recruiter, fulfilling the requirements of the Flexiple Engineering Challenge.

## Quick Start

### Prerequisites
- Python 3.9+ installed.
- A valid Google Gemini API Key.

### Running Locally
1. Clone the repository and navigate to the root folder.
2. Set your Gemini API key as an environment variable (Windows/Linux/Mac):
   ```bash
   # Windows (Command Prompt)
   set GEMINI_API_KEY=your_actual_key
   
   # Windows (PowerShell)
   $env:GEMINI_API_KEY="your_actual_key"
   
   # Mac/Linux
   export GEMINI_API_KEY=your_actual_key
   ```
3. Run the application using the Maven wrapper:
   ```bash
   pip install -r requirements.txt`n   python main.py
   ```
4. Open your browser and navigate to `http://localhost:8080`.

## Architecture & Technical Decisions

### 1. Spring Boot + Vanilla JS (Single Application)
I chose to build a **Python FastAPI backend** with a **Vanilla JavaScript/HTML/CSS frontend** served from `static`.
- **Why?** This keeps the solution tightly integrated and completely runnable with a single `pip install -r requirements.txt`n   python main.py` command. Building a separate React/Vite app would have complicated the setup process. By leveraging CSS variables and smooth transitions, the vanilla frontend achieves the requested highly interactive, premium UI/UX without needing heavy frameworks.

### 2. Stateless Backend
The Spring Boot backend is completely stateless. It does not use HTTP Sessions. Instead, the frontend owns the entire search state (the `objective_filters`, `subjective_rubric`, `shownProfiles`, and `chatHistory`) and sends the full necessary context on every API request (`/api/refine`).
- **Why?** This elegantly fulfills the "no persistence across sessions" requirement and ensures the backend remains lightweight, fast, and horizontally scalable. It also allows the LLM to process multi-round refinements accurately since the full chat history is passed back.

### 3. LLM Model: Gemini 3.1 Flash Lite
I chose Google's `gemini-3.1-flash-lite`.
- **Why?** The refinement loop is a conversational experience. Recruiter feedback requires extremely fast round-trips to feel fluid. `gemini-3.1-flash-lite` provides near-instant structured output, making the loading/thinking states feel snappy while maintaining excellent reasoning capabilities.

### 4. Structured Output Validation
All LLM prompts enforce strict JSON output utilizing Gemini's `responseMimeType: application/json` and `responseSchema` configurations. On the backend, we deserialize this with Pydantic configured with `FAIL_ON_UNKNOWN_PROPERTIES = true`. 
- **Why?** This ensures malformed output explicitly throws a catchable exception (which triggers our retry logic) rather than silently corrupting the search state.

### 5. Explicit Citations
The scoring prompt explicitly demands the model to cite exact profile fields (e.g., specific past company names, exact skills) in every reasoning block, and penalizes generic praise. This ensures trust when the recruiter reads *why* a candidate matched.

### 6. Local Filtering Logic: ANY Match
For the objective filtering step (`/api/search`), I implemented an `ANY` match logic for skills and company types (meaning if a candidate has *at least one* requested skill, they pass the hard filter). 
- **Why?** Strict `ALL` matching is too aggressive and often eliminates "near misses" that a recruiter might actually want to see. By allowing a broad hard filter, we let the LLM's subjective scoring handle the nuance of ranking candidates who have *more* overlapping skills higher.

## Error Handling & Recovery
The system is built to gracefully handle LLM rate limits (429) or timeouts:
- The backend `GeminiService` implements a 120-second timeout (to handle complex refinement tasks) and a 2-attempt exponential backoff retry loop for 429/500 errors.
- If it ultimately fails, it returns a defined error shape.
- The frontend catches this and renders a friendly "Error Card" overlay, allowing the user to dismiss it and simply hit "Refine" again without losing their state or crashing the app.

## Repository Structure
- **Backend**: `main.py` (Controllers, Services, Models)
- **Frontend**: `static/` (`index.html`, `style.css`, `app.js`)
- **Prompts**: `prompts/` (Externalized `.md` prompt templates for easy review)
- **Data**: `profiles.json` (The provided talent pool)

