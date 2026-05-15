from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
import asyncio
import os
import json

# WHY: High-frequency log streamer for the Web-Based Glass Room.
# - Uses Server-Sent Events (SSE) for zero-latency thought delivery.
# - Absolute path resolution for the synchronized log.

app = FastAPI()
LOG_FILE = "/root/tools-n-stuff/big_brother.log"

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

async def log_generator():
    """WHY: Tails the big_brother.log and yields SSE events."""
    if not os.path.exists(LOG_FILE):
        # Create empty log if missing
        with open(LOG_FILE, "w") as f: f.write("")
        
    last_pos = 0
    while True:
        with open(LOG_FILE, "r") as f:
            f.seek(last_pos)
            lines = f.readlines()
            last_pos = f.tell()
            for line in lines:
                if line.strip():
                    yield f"data: {line}\n\n"
        await asyncio.sleep(0.1)

@app.get("/stream")
async def stream_logs():
    return StreamingResponse(log_generator(), media_type="text/event-stream")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
