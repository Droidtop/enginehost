"""PreToolUse sanitization checker for Bash and PowerShell commands.

Runs before every shell command Claude Code (and its local subagents) execute.
Denies commands that can destroy data, per the owner's rule after the
2026-10-15 droidtop-dev wipe: "do not run recursive deletes. period."

Reads the hook payload on stdin. Prints a PreToolUse deny decision when a
command (or a script file it runs) matches a dangerous pattern; prints
nothing and exits 0 otherwise. Never modifies anything.
"""
import json
import os
import re
import sys

# (pattern, reason). Patterns are checked case-insensitively against the
# command text and against the text of any script file the command runs.
RULES = [
    (r"\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\b", "recursive rm"),
    (r"\brm\s+.*\s(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\b", "recursive rm"),
    (r"\brsync\b[^\n]*--(delete|remove-source-files)", "rsync with delete"),
    (r"\bfind\b[^\n]*\s-delete\b", "find -delete"),
    (r"\bfind\b[^\n]*-exec\s+rm\b", "find -exec rm"),
    (r"\bremove-item\b[^\n]*-recurse", "Remove-Item -Recurse"),
    (r"\b(rmdir|rd)\s+[^\n]*/s\b", "rmdir /s"),
    (r"\bdel\s+[^\n]*/s\b", "del /s"),
    (r"\bgit\s+clean\b[^\n]*-[a-z]*[fdx]", "git clean"),
    (r"\bshutil\.rmtree\b", "shutil.rmtree"),
    (r"\bremovedirs\b|\brmtree\b", "tree removal"),
    (r"\bwsl(\.exe)?\b[^\n]*--(unregister|shutdown)\b", "wsl unregister/shutdown"),
    (r"\boptimize-vhd\b|\bdiskpart\b|\bformat-volume\b|\bclear-disk\b", "disk operation"),
    (r"\bmkfs(\.\w+)?\b|\bwipefs\b|\bshred\b", "filesystem wipe"),
    (r"\bdd\b[^\n]*\bof=/dev/", "dd to a device"),
    (r"\bgit\s+push\b[^\n]*(--force|-f\b|--mirror)", "force push"),
    (r"\bgit\s+(reset\s+--hard|filter-branch|filter-repo)\b", "history rewrite"),
    (r"\.vhdx\b[^\n]*\b(remove-item|del|rm|move-item|mv)\b|\b(remove-item|del|rm|move-item|mv)\b[^\n]*\.vhdx\b", "touching a vhdx"),
]

# Variables inside a nested shell -c string passed through wsl.exe: the
# exact hazard that expanded $T to empty and turned rsync into "rsync ./ /".
NESTED_VAR = re.compile(r"\bwsl(\.exe)?\b[^\n]*\b(bash|sh)\s+-c\s+(['\"]).*\$\{?[A-Za-z_]", re.S)

SCRIPT_REF = re.compile(r"(?:bash|sh|python3?|pwsh|powershell(?:\.exe)?)\s+(?:-\w+\s+)*([^\s;|&]+\.(?:sh|py|ps1))")


def windows_path(p):
    p = p.strip("'\"")
    m = re.match(r"^/mnt/([a-z])/(.*)", p) or re.match(r"^/([a-z])/(.*)", p)
    if m:
        return f"{m.group(1).upper()}:/{m.group(2)}"
    return p


def check(text):
    hits = []
    for pat, why in RULES:
        if re.search(pat, text, re.I):
            hits.append(why)
    if NESTED_VAR.search(text):
        hits.append("variable inside a nested wsl shell -c string (write a script file with literal paths)")
    return hits


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0
    cmd = (payload.get("tool_input") or {}).get("command") or ""
    if not cmd:
        return 0
    hits = check(cmd)
    for ref in SCRIPT_REF.findall(cmd):
        path = windows_path(ref)
        try:
            if os.path.isfile(path) and os.path.getsize(path) < 2_000_000:
                with open(path, encoding="utf-8", errors="replace") as f:
                    for h in check(f.read()):
                        hits.append(f"{h} (in {ref})")
        except OSError:
            pass
    if not hits:
        return 0
    reason = (
        "Sanitization checker blocked this command: " + "; ".join(sorted(set(hits)))
        + ". Owner rule: no recursive deletes, period; no destructive disk, WSL or history operations."
        + " Do not work around this. If something must be removed, list it for the owner to delete."
    )
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
