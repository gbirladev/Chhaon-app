"""Run the scheduler from the command line (cron, or a local loop).

    uv run python -m app.tick              # one tick
    uv run python -m app.tick --every 300  # keep ticking every 5 minutes (local development)
"""

import argparse
import json
import logging
import time

from app.config import get_settings
from app.db import service_client
from app.deps import get_messenger
from app.services.scheduler import run_tick


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the Chhaon scheduler")
    parser.add_argument("--every", type=int, default=0, help="repeat every N seconds (0 = run once)")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    settings = get_settings()
    while True:
        print(json.dumps(run_tick(service_client(), get_messenger(settings), settings)), flush=True)
        if not args.every:
            return
        time.sleep(args.every)


if __name__ == "__main__":
    main()
