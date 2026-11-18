#!/usr/bin/env python3
"""Generate sandbox-manifest.json — SHA-256 pins for agent-executed assets.

Covers assets/default_mount (sandbox overlay) and assets/remote (session
server). Run after changing any covered asset:

    python3 scripts/generate_asset_manifest.py

The runtime verifier (AssetManifest.kt) checks the overlay against this
manifest before applying it, so drift is caught instead of silently
executing altered code. Stdlib only.
"""

import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "app", "src", "main", "assets")
COVERED_DIRS = ["default_mount", "remote"]
MANIFEST_VERSION = 1
OUT = os.path.join(ASSETS, "sandbox-manifest.json")


def main():
    entries = []
    for d in COVERED_DIRS:
        root = os.path.join(ASSETS, d)
        if not os.path.isdir(root):
            print("missing covered dir: %s" % root, file=sys.stderr)
            return 1
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in sorted(filenames):
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, ASSETS).replace(os.sep, "/")
                with open(full, "rb") as f:
                    data = f.read()
                entries.append({
                    "path": rel,
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "size": len(data),
                })
    entries.sort(key=lambda e: e["path"])
    manifest = {"version": MANIFEST_VERSION, "files": entries}
    with open(OUT, "w") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print("wrote %s (%d files)" % (OUT, len(entries)))
    return 0


if __name__ == "__main__":
    sys.exit(main())