import time
import os
import sys

# WHY: Zero-dependency, unbuffered raw monitor.
# Focus: Observability of /tmp/big_brother.log.

LOG_PATH = "/tmp/big_brother.log"

def tail_f():
    print(f"Monitoring {LOG_PATH}...")
    print("-" * 40)
    
    if not os.path.exists(LOG_PATH):
        with open(LOG_PATH, "w") as f: f.write("")

    with open(LOG_PATH, "r") as f:
        # Go to the end of the file
        f.seek(0, 2)
        while True:
            line = f.read()
            if line:
                sys.stdout.write(line)
                sys.stdout.flush()
            else:
                time.sleep(0.1)

if __name__ == "__main__":
    try:
        tail_f()
    except KeyboardInterrupt:
        print("\nMonitor stopped.")
