#!/usr/bin/env python3
"""Turn learning-mode profiles into a proposed sandbox policy, for a person to review.

A learning run (docs/engine-sandbox.md, "Learning mode") locks a core down with
nothing refused and records everything a real lockdown would have refused, into
one profile per core build: Android/data/dev.enginehost/files/sandbox-learned/
<core>-<archive>.json. Pull them (the core harness's --learn does) and run:

    python3 tools/sandbox-learn/propose.py results/sandbox-learned/*.json > proposal.md

Nothing is applied. The proposal lists, per core:

  - paths the core needed, by where they are: the launch's own folders ($GAME,
    $SAVE, $BUNDLE, $CACHE), Enginehost's private data ($APP), shared storage
    ($EXTERNAL), and the system. Each path carries the strongest access asked
    (write > read > lookup) and how often. Lookups alone (a stat or access of a
    folder on the way somewhere) usually need no rule.
  - calls a lockdown refuses that the core made. Those the owner's rules forbid
    (network, running programs, debugging other processes) are listed under
    "Refused by decision" and never proposed.

Standard library only; Python 3.8+.
"""

import argparse
import json
import sys

# The owner's rules (CLAUDE.md, "Engine sandbox"): no internet, no arbitrary file
# access, no running programs. These calls stay refused whatever a core needs.
REFUSED_BY_DECISION = {
    "socket": "network", "connect": "network", "bind": "network",
    "execve": "running programs", "execveat": "running programs",
    "ptrace": "debugging other processes", "process_vm_readv": "reading other processes",
    "process_vm_writev": "writing other processes", "bpf": "kernel programs",
    "perf_event_open": "kernel performance counters",
}

NEED_RANK = {"lookup": 0, "read": 1, "write": 2}

# Where a generalised path lives, in the order a reviewer reads them.
PLACES = [
    ("$GAME", "The game folder"),
    ("$SAVE", "The save folder"),
    ("$BUNDLE", "The core's own files"),
    ("$CACHE", "Enginehost's cache"),
    ("$APP", "Enginehost's private data: outside the launch, review carefully"),
    ("$EXTERNAL", "Shared storage outside the game: review carefully"),
    ("/", "The system"),
]


def place_of(path):
    for prefix, _ in PLACES[:-1]:
        if path == prefix or path.startswith(prefix + "/"):
            return prefix
    return "/"


def merge(profiles):
    """Profiles of the same core (any builds) combined: core -> summary."""
    cores = {}
    for profile in profiles:
        core = cores.setdefault(profile["core"], {"builds": set(), "runs": 0, "games": [], "paths": {}, "calls": {}})
        core["builds"].add("%s (%s, %s)" % (profile.get("build", "?"), profile.get("archive", "")[:12].lower(),
                                            profile.get("abi", "?")))
        core["runs"] += profile.get("runs", 0)
        for game in profile.get("games", []):
            if game not in core["games"]:
                core["games"].append(game)
        for entry in profile.get("paths", []):
            seen = core["paths"].setdefault(entry["path"], {"need": "lookup", "ops": set(), "count": 0})
            if NEED_RANK.get(entry["need"], 0) > NEED_RANK[seen["need"]]:
                seen["need"] = entry["need"]
            seen["ops"].add(entry["op"])
            seen["count"] += entry["count"]
        for entry in profile.get("calls", []):
            seen = core["calls"].setdefault(entry["call"], {"would": entry["would"], "count": 0, "examples": []})
            seen["count"] += entry["count"]
            for example in entry.get("examples", []):
                if example not in seen["examples"] and len(seen["examples"]) < 3:
                    seen["examples"].append(example)
    return cores


def render(cores):
    lines = ["# Proposed sandbox policy, from learning runs", "",
             "Nothing here is applied. A person reviews it; what is accepted goes into the core's",
             "manifest or Enginehost's preset by hand. See docs/engine-sandbox.md, \"Learning mode\"."]
    for name in sorted(cores):
        core = cores[name]
        lines += ["", "## %s" % name, "",
                  "Builds: %s. Runs: %d. Games: %s." % (", ".join(sorted(core["builds"])), core["runs"],
                                                       ", ".join(core["games"]) or "none")]
        if not core["paths"] and not core["calls"]:
            lines += ["", "Nothing would have been refused: the current policy is enough for these runs."]
            continue
        if core["paths"]:
            lines += ["", "### Paths the lockdown refused"]
            for prefix, title in PLACES:
                here = sorted((p, e) for p, e in core["paths"].items() if place_of(p) == prefix)
                if not here:
                    continue
                needed = [(p, e) for p, e in here if e["need"] != "lookup"]
                looked = [(p, e) for p, e in here if e["need"] == "lookup"]
                lines += ["", "**%s**" % title]
                for path, entry in needed:
                    lines.append("- `%s` %s (%s, %d times)" % (path, entry["need"], ", ".join(sorted(entry["ops"])),
                                                              entry["count"]))
                if looked:
                    lines.append("- looked up only, usually no rule needed: " +
                                 ", ".join("`%s`" % p for p, _ in looked))
        proposed = {c: e for c, e in core["calls"].items() if c not in REFUSED_BY_DECISION}
        refused = {c: e for c, e in core["calls"].items() if c in REFUSED_BY_DECISION}
        if proposed:
            lines += ["", "### Calls the lockdown refuses, for review"]
            for call in sorted(proposed):
                entry = proposed[call]
                lines.append("- `%s` (a lockdown answers %s, %d times), e.g. %s" % (
                    call, entry["would"], entry["count"], "; ".join("`%s`" % e for e in entry["examples"])))
        if refused:
            lines += ["", "### Refused by decision, never proposed"]
            for call in sorted(refused):
                entry = refused[call]
                lines.append("- `%s`: %s (%d times), e.g. %s" % (
                    call, REFUSED_BY_DECISION[call], entry["count"], "; ".join("`%s`" % e for e in entry["examples"])))
    return "\n".join(lines) + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("profiles", nargs="+", help="learning profiles (sandbox-learned/*.json)")
    args = ap.parse_args(argv)
    profiles = []
    for path in args.profiles:
        with open(path) as f:
            profiles.append(json.load(f))
    sys.stdout.write(render(merge(profiles)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
