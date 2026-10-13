#!/usr/bin/env python3
"""Expand the backend template after Docker Compose resolves .env values."""

import copy
import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def expand_backends(config):
    config = copy.deepcopy(config)
    raw_count = str(config.pop("x-server-count", "2"))
    if not re.fullmatch(r"[0-9]+", raw_count) or not 2 <= int(raw_count) <= 64:
        raise ValueError("SERVER_COUNT must be a whole number from 2 to 64")
    count = int(raw_count)
    ids = [chr(ord("a") + index) if index < 26 else f"s{index + 1}" for index in range(count)]
    services = config["services"]
    template = services.pop("server-a")
    services.pop("server-b", None)
    services["proxy"]["environment"]["BACKEND_SERVER_IDS"] = ",".join(ids)
    for server_id in ids:
        backend = copy.deepcopy(template)
        backend["environment"]["SERVER_ID"] = server_id
        services[f"server-{server_id}"] = backend
    return config


def main(args=None):
    args = sys.argv[1:] if args is None else args
    if not args or args[0] in ("-h", "--help"):
        print("Usage: ./docker/compose.py COMMAND [OPTIONS]")
        print("Set SERVER_COUNT in .env (default 2), then use Compose subcommands:")
        print("  ./docker/compose.py up --build -d")
        print("  ./docker/compose.py config --services")
        print("  ./docker/compose.py logs -f proxy")
        print("  ./docker/compose.py down")
        return 0
    if args[0].startswith("-"):
        print("Start with a Compose subcommand; use .env for settings.", file=sys.stderr)
        return 2
    if shutil.which("docker"):
        compose = ["docker", "compose"]
    elif shutil.which("docker-compose"):
        compose = ["docker-compose"]
    else:
        print("Docker Compose is required (docker compose or docker-compose).", file=sys.stderr)
        return 1

    root = Path(__file__).resolve().parent.parent
    common = ["--project-directory", str(root)]
    try:
        result = subprocess.run(
            compose + common + ["-f", str(root / "docker-compose.yml"), "config", "--format", "json"],
            cwd=root, capture_output=True, text=True, check=True,
        )
        if result.stderr:
            print(result.stderr, end="", file=sys.stderr)
        config = expand_backends(json.loads(result.stdout))
        # Rendered secrets remain in a restrictive temporary file outside the
        # build context; the next pass must preserve literal dollars in secrets.
        with tempfile.TemporaryDirectory(prefix="death-swap-compose-") as temp_dir:
            generated = Path(temp_dir) / "compose.json"
            generated.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
            generated.chmod(0o600)
            # Compose interpolates again when reading generated JSON. Double
            # dollar signs for that pass, including resolved environment values.
            generated.write_text(generated.read_text(encoding="utf-8").replace("$", "$$"), encoding="utf-8")
            return subprocess.run(compose + common + ["-f", str(generated)] + args, cwd=root).returncode
    except subprocess.CalledProcessError as error:
        print(error.stderr or "Could not resolve Compose configuration.", end="", file=sys.stderr)
        return error.returncode
    except (ValueError, KeyError, OSError) as error:
        print(f"[deathswap] {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
