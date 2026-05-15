import os
import sys
import json
import httpx
import time

# WHY: Protocol Shield (The Great Wall of MCP).
# MCP requires absolute silence on stdout. We redirect everything to stderr 
# until the MCP runner is ready to communicate.
_REAL_STDOUT = sys.stdout
sys.stdout = sys.stderr

# WHY: Self-Healing Architectural Core.
BASE_DIR = "/root/tools-n-stuff"
VENV_PACKAGES = os.path.join(BASE_DIR, ".venv/lib/python3.12/site-packages")
if os.path.exists(VENV_PACKAGES) and VENV_PACKAGES not in sys.path:
    sys.path.insert(0, VENV_PACKAGES)

from mcp.server.fastmcp import FastMCP

# WHY: High-Fidelity Skeptical Core.
# - Persona: Big Brother (The Skeptical Overseer).
# - Role: The 'Devils Advocate' of the PersonaPlex Project.
mcp = FastMCP("antigravity-toolbox")
LOG_PATH = os.path.join(BASE_DIR, "big_brother.log")

def emit_event(ev_type: str, data: dict):
    """WHY: Telemetry for the Glass Room monitor."""
    event = {"type": ev_type, "data": data, "timestamp": time.time()}
    try:
        with open(LOG_PATH, "a") as f:
            f.write(json.dumps(event) + "\n")
            f.flush()
    except: pass

@mcp.tool()
async def big_brother(query: str) -> str:
    """Big Brother: Senior Architectural Reasoning Loop (Skeptical Advisor)."""
    emit_event("PROMPT", {"query": query})
    
    # WHY: The Skeptical Mandate.
    # Big Brother is not a helper; he is a critic. He must always take the 
    # opposite view, identify hidden flaws, and enforce the WHY-First standard.
    system_prompt = (
        "IDENTITY: You are Big Brother, the Skeptical Overseer of the PersonaPlex Project.\n"
        "MANDATE: Take the opposite view of any proposal. Be critical. Identify the 'Stupidity' in current plans.\n"
        "PRINCIPLE: Enforce the WHY-First standard. If the WHY is weak, the plan is a failure.\n"
        "RESPONSE STRUCTURE: [OVERSEER ASSESSMENT], [THE OPPOSITE VIEW], [THE MANDATE].\n"
        "CONSTRAINT: No tools. No summaries. Just cold, architectural skepticism."
    )
    
    messages = [{"role": "system", "content": system_prompt}, {"role": "user", "content": query}]
    ollama_url = "http://127.0.0.1:11434/api/chat"

    async with httpx.AsyncClient(timeout=300.0) as client:
        payload = {
            "model": "gemma4:latest", 
            "messages": messages, 
            "stream": False, 
            "options": {"temperature": 0.3} # Slightly higher for more 'creative' skepticism
        }
        try:
            response = await client.post(ollama_url, json=payload)
            response.raise_for_status()
            full_content = response.json()["message"]["content"]
            emit_event("RESPONSE", {"content": full_content})
            return full_content
        except Exception as e:
            emit_event("ERROR", {"content": f"Ollama Error: {e}"})
            return f"Error: {e}"

@mcp.tool()
def echo(message: str) -> str:
    """Basic echo test."""
    return f"ECHO: {message}"

if __name__ == "__main__":
    # Restore stdout for the MCP run
    sys.stdout = _REAL_STDOUT
    mcp.run()
