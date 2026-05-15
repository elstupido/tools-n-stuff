import json
import time
import subprocess
import os
from textual.app import App, ComposeResult
from textual.widgets import Header, Footer, Static, Collapsible
from textual.containers import ScrollableContainer, Vertical
from rich.panel import Panel
from rich.text import Text
from rich.syntax import Syntax

# WHY: Path Certainty for WSL/MCP environments.
LOG_FILE = "/root/tools-n-stuff/big_brother.log"

class VRAMHeatmap(Static):
    def on_mount(self) -> None:
        self.set_interval(1.0, self.update_telemetry)
    def update_telemetry(self) -> None:
        try:
            res = subprocess.check_output(
                ["nvidia-smi", "--query-gpu=memory.used,memory.total,utilization.gpu", "--format=csv,noheader,nounits"],
                text=True
            ).strip()
            used, total, util = map(int, res.split(", "))
            pct = used / total
            color = "green" if pct < 0.8 else "red"
            bar = "█" * int(pct * 20) + "░" * (20 - int(pct * 20))
            self.update(Panel(Text(f"BLACKWELL GPU: {used}/{total}MB [{bar}] UTIL: {util}%", style=color), title="HARDWARE-TELEMETRY", border_style=color))
        except: self.update("Hardware Sync Error")

class ThoughtNode(Static):
    """WHY: Mimicking the 'Thought for a moment' aesthetic from the screenshot."""
    def __init__(self, content: str, title: str = "Thought for a moment"):
        super().__init__()
        self.node_title = title
        self.node_content = content

    def compose(self) -> ComposeResult:
        with Collapsible(title=self.node_title, collapsed=False):
            # WHY: Using a grey-boxed code block aesthetic.
            yield Static(Syntax(self.node_content, "markdown", theme="monokai", word_wrap=True, background_color="#1e1e1e"))

class ThoughtStream(ScrollableContainer):
    def on_mount(self) -> None:
        self.last_pos = 0
        self.set_interval(0.1, self.tail_log)

    def process_line(self, line: str) -> None:
        MAX_NODES = 50
        nodes = self.query("Static, ThoughtNode")
        if len(nodes) > MAX_NODES:
            nodes[0].remove()

        try:
            event = json.loads(line)
            ev_type = event.get("type", "").upper()
            data = event.get("data", {})
            
            if ev_type == "PROMPT":
                self.mount(Static(Text(f"\n> {data.get('query', '')}", style="dim")))
            elif ev_type == "STREAM":
                content = data.get("content", "")
                # WHY: Handle thinking mode markers.
                if "<thinking>" in content:
                    self.mount(Static(Text("Neural deliberation active...", style="blue")))
                else:
                    self.mount(Static(Text(content, style="bright_white")))
            elif ev_type == "TOOL_CALL":
                tool_name = data.get("tool", "unknown")
                args = data.get("args", {})
                self.mount(Static(Text(f"🛠️  Calling: {tool_name}({json.dumps(args)})", style="cyan")))
            elif ev_type == "TOOL_OUTPUT":
                # WHY: Group tool calls into 'Thought' nodes for drill-down.
                tool_name = data.get("tool", "unknown")
                output = data.get("output", "")
                self.mount(ThoughtNode(
                    title=f"Result: {tool_name}",
                    content=f"### MCP Tool Result: {tool_name}\n\n```json\n{json.dumps(output, indent=2) if isinstance(output, (dict, list)) else str(output)}\n```"
                ))
            elif ev_type == "RESPONSE":
                self.mount(Static(Text(f"\n{data.get('content', '')}", style="bold green")))
            elif ev_type == "ERROR":
                self.mount(Static(Text(f"❌ [ARCHITECTURAL FAULT]: {data.get('content', '')}", style="bold red")))
            
            self.scroll_end(animate=False)
        except Exception as e:
            self.mount(Static(Text(f"[ERROR]: {e}", style="bold red")))

    def tail_log(self) -> None:
        if not os.path.exists(LOG_FILE): return
        with open(LOG_FILE, "r") as f:
            f.seek(self.last_pos)
            for line in f:
                if line.strip():
                    self.process_line(line)
            self.last_pos = f.tell()

class ScreenshotParityMonitor(App):
    TITLE = "BIG BROTHER | THE GLASS ROOM"
    def compose(self) -> ComposeResult:
        yield Header()
        yield VRAMHeatmap()
        yield ThoughtStream(id="main_zone")
        yield Footer()
    CSS = """
    Screen { background: #0d1117; }
    #main_zone {
        height: 1fr;
        padding: 1 2;
        overflow-y: scroll;
    }
    Collapsible {
        background: #161b22;
        border: none;
        margin: 1 0;
    }
    Collapsible Title {
        color: #8b949e;
        background: #0d1117;
    }
    """

if __name__ == "__main__":
    ScreenshotParityMonitor().run()
