import json
import os
import asyncio
from typing import List, Optional, Any
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
import httpx
from dotenv import load_dotenv

load_dotenv()

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# Models
class ObjectiveFilters(BaseModel):
    skills: List[str] = []
    minYearsExperience: Optional[int] = None
    maxYearsExperience: Optional[int] = None
    location: Optional[str] = None
    companyTypes: List[str] = []

class SubjectiveRubric(BaseModel):
    guidelines: List[str] = []

class GenerateRequest(BaseModel):
    query: str

class GenerateResponse(BaseModel):
    objective_filters: ObjectiveFilters
    subjective_rubric: SubjectiveRubric

class PastCompany(BaseModel):
    company: str
    company_type: str
    title: str
    years: Optional[int] = None

class Profile(BaseModel):
    id: str
    name: str
    current_title: str
    years_experience: Optional[int] = None
    location: Optional[str] = None
    current_company: Optional[str] = None
    current_company_type: Optional[str] = None
    skills: List[str] = []
    past_companies: List[PastCompany] = []
    education: Optional[str] = None
    summary: Optional[str] = None

class SearchRequest(BaseModel):
    objective_filters: ObjectiveFilters
    subjective_rubric: SubjectiveRubric

class ScoredProfile(BaseModel):
    profile: Profile
    score: int
    explanation: str

class SearchResponse(BaseModel):
    matchedCount: int
    profiles: List[ScoredProfile]

class ChatMessage(BaseModel):
    role: str
    message: str

class RefineRequest(BaseModel):
    objective_filters: ObjectiveFilters
    subjective_rubric: SubjectiveRubric
    shown_profiles: List[Profile]
    chat_history: List[ChatMessage]

class RefineResponse(BaseModel):
    objective_filters: ObjectiveFilters
    subjective_rubric: SubjectiveRubric
    explanation: str

class FreezeRequest(BaseModel):
    objective_filters: ObjectiveFilters
    subjective_rubric: SubjectiveRubric
    profiles: List[ScoredProfile]

# Globals
profiles_db: List[Profile] = []
gemini_api_key = os.getenv("GEMINI_API_KEY", "")
gemini_model = os.getenv("GEMINI_MODEL", "gemini-3.1-flash-lite")

try:
    with open("profiles.json", "r", encoding="utf-8") as f:
        raw_profiles = json.load(f)
        profiles_db = [Profile(**p) for p in raw_profiles]
except Exception as e:
    print(f"Warning: Could not load profiles.json: {e}")

def read_prompt(filename: str) -> str:
    with open(f"prompts/{filename}", "r", encoding="utf-8") as f:
        return f.read()

async def call_gemini(prompt: str, schema_json: dict) -> dict:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{gemini_model}:generateContent"
    headers = {
        "Content-Type": "application/json",
        "X-goog-api-key": gemini_api_key
    }
    
    request_body = {
        "contents": [{
            "parts": [{"text": prompt}]
        }],
        "generationConfig": {
            "responseMimeType": "application/json",
            "responseSchema": schema_json
        }
    }

    async with httpx.AsyncClient(timeout=120.0) as client:
        max_retries = 2
        for attempt in range(max_retries + 1):
            try:
                response = await client.post(url, headers=headers, json=request_body)
                if response.status_code == 200:
                    data = response.json()
                    text_result = data["candidates"][0]["content"]["parts"][0]["text"]
                    return json.loads(text_result)
                elif response.status_code == 429 or response.status_code >= 500:
                    if attempt == max_retries:
                        raise Exception(f"Gemini API error after retries: {response.status_code} {response.text}")
                    await asyncio.sleep(2 * (attempt + 1))
                else:
                    raise Exception(f"Gemini API error: {response.status_code} {response.text}")
            except (httpx.RequestError, json.JSONDecodeError) as e:
                if attempt == max_retries:
                    raise e
                await asyncio.sleep(2 * (attempt + 1))
        raise Exception("Failed to call Gemini API")

@app.post("/api/generate")
async def generate(req: GenerateRequest):
    try:
        prompt_template = read_prompt("generate_prompt.md")
        prompt = prompt_template.replace("{query}", req.query)
        
        schema_json = {
            "type": "OBJECT",
            "properties": {
                "objective_filters": {
                    "type": "OBJECT",
                    "properties": {
                        "skills": { "type": "ARRAY", "items": { "type": "STRING" } },
                        "minYearsExperience": { "type": "INTEGER" },
                        "maxYearsExperience": { "type": "INTEGER", "nullable": True },
                        "location": { "type": "STRING", "nullable": True },
                        "companyTypes": { "type": "ARRAY", "items": { "type": "STRING" } }
                    },
                    "required": ["skills", "minYearsExperience"]
                },
                "subjective_rubric": {
                    "type": "OBJECT",
                    "properties": {
                        "guidelines": { "type": "ARRAY", "items": { "type": "STRING" } }
                    },
                    "required": ["guidelines"]
                }
            },
            "required": ["objective_filters", "subjective_rubric"]
        }
        
        result = await call_gemini(prompt, schema_json)
        return GenerateResponse(**result)
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": "gemini_error", "message": str(e)})

@app.post("/api/search")
async def search(req: SearchRequest):
    try:
        filters = req.objective_filters
        
        filtered_profiles = []
        for p in profiles_db:
            if filters.minYearsExperience is not None and p.years_experience is not None:
                if p.years_experience < filters.minYearsExperience:
                    continue
            if filters.maxYearsExperience is not None and p.years_experience is not None:
                if p.years_experience > filters.maxYearsExperience:
                    continue
            if filters.location and p.location:
                if filters.location.lower() not in p.location.lower():
                    continue
            if filters.companyTypes and p.current_company_type:
                if not any(t.lower() == p.current_company_type.lower() for t in filters.companyTypes):
                    continue
            if filters.skills and p.skills:
                has_skill = False
                for req_s in filters.skills:
                    for user_s in p.skills:
                        if req_s.lower() in user_s.lower() or user_s.lower() in req_s.lower():
                            has_skill = True
                            break
                    if has_skill:
                        break
                if not has_skill:
                    continue
            filtered_profiles.append(p)
            
        matched_count = len(filtered_profiles)
        profiles_to_score = filtered_profiles[:15]
        
        # In Pydantic v2 we use model_dump(), in v1 dict()
        def to_dict(obj):
            return obj.model_dump() if hasattr(obj, "model_dump") else obj.dict()

        rubric_json = json.dumps(to_dict(req.subjective_rubric))
        profiles_json = json.dumps([to_dict(p) for p in profiles_to_score])
        
        prompt_template = read_prompt("score_prompt.md")
        prompt = prompt_template.replace("{rubric}", rubric_json).replace("{profiles}", profiles_json)
        
        schema_json = {
            "type": "ARRAY",
            "items": {
                "type": "OBJECT",
                "properties": {
                    "id": { "type": "STRING" },
                    "score": { "type": "INTEGER" },
                    "explanation": { "type": "STRING" }
                },
                "required": ["id", "score", "explanation"]
            }
        }
        
        batch_scores = await call_gemini(prompt, schema_json)
        score_map = {item["id"]: item for item in batch_scores}
        
        scored_profiles = []
        for p in profiles_to_score:
            score_item = score_map.get(p.id)
            if score_item:
                scored_profiles.append(ScoredProfile(profile=p, score=score_item["score"], explanation=score_item["explanation"]))
            else:
                scored_profiles.append(ScoredProfile(profile=p, score=0, explanation="No score generated by AI"))
                
        scored_profiles.sort(key=lambda x: x.score, reverse=True)
        top_profiles = scored_profiles[:5]
        
        return SearchResponse(matchedCount=matched_count, profiles=top_profiles)
        
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": "search_error", "message": str(e)})

@app.post("/api/refine")
async def refine(req: RefineRequest):
    try:
        def to_dict(obj):
            return obj.model_dump() if hasattr(obj, "model_dump") else obj.dict()

        current_filters_json = json.dumps(to_dict(req.objective_filters))
        current_rubric_json = json.dumps(to_dict(req.subjective_rubric))
        shown_profiles_json = json.dumps([to_dict(p) for p in req.shown_profiles])
        chat_history_json = json.dumps([to_dict(c) for c in req.chat_history])
        
        prompt_template = read_prompt("refine_prompt.md")
        prompt = prompt_template.replace("{current_filters}", current_filters_json)\
                                .replace("{current_rubric}", current_rubric_json)\
                                .replace("{shown_profiles}", shown_profiles_json)\
                                .replace("{chat_history}", chat_history_json)
                                
        schema_json = {
            "type": "OBJECT",
            "properties": {
                "objective_filters": {
                    "type": "OBJECT",
                    "properties": {
                        "skills": { "type": "ARRAY", "items": { "type": "STRING" } },
                        "minYearsExperience": { "type": "INTEGER" },
                        "maxYearsExperience": { "type": "INTEGER", "nullable": True },
                        "location": { "type": "STRING", "nullable": True },
                        "companyTypes": { "type": "ARRAY", "items": { "type": "STRING" } }
                    },
                    "required": ["skills", "minYearsExperience"]
                },
                "subjective_rubric": {
                    "type": "OBJECT",
                    "properties": {
                        "guidelines": { "type": "ARRAY", "items": { "type": "STRING" } }
                    },
                    "required": ["guidelines"]
                },
                "explanation": { "type": "STRING" }
            },
            "required": ["objective_filters", "subjective_rubric", "explanation"]
        }
        
        result = await call_gemini(prompt, schema_json)
        return RefineResponse(**result)
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": "refine_error", "message": str(e)})

@app.post("/api/freeze")
async def freeze(req: FreezeRequest):
    def to_dict(obj):
        return obj.model_dump() if hasattr(obj, "model_dump") else obj.dict()
    return {"status": "frozen", "final_state": to_dict(req)}

# Mount static files at the root
app.mount("/", StaticFiles(directory="static", html=True), name="static")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8080, reload=True)
