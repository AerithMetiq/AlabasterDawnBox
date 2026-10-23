#!/usr/bin/env python3
"""
Parity check between AlabasterDawnBox and CrossCodeBox (the same file is kept in both repositories).

The two apps share most of their code, copied with the package name changed. This compares every file
under app/src that both have, after replacing the names that differ by design (package, app, game,
`SteamGame.ALABASTER_DAWN` / `TheGame.steam`, `goldStatusBar` / `statusBar`). A file that still
differs must be listed in PARITY.lock.json with a reason and a fingerprint of its difference; so must a
file that only one app has. The check fails when a difference is new or no longer the one reviewed,
which is how a fix made in one app and not carried to the other shows up.

    python3 tools/parity.py             check; prints each new or changed difference
    python3 tools/parity.py --accept    record the current differences in both repositories' lock
                                        (keeps reasons; a new entry gets a TODO reason to fill in)

The other repository is found next to this one (~/AlabasterDroid or ~/AlabasterDawnBox, and
~/CrossCodeBox), or set PARITY_OTHER to its folder.
"""
import difflib
import hashlib
import json
import os
import sys

ADB, CCB = "alabasterdawnbox", "crosscodebox"
LOCK = "PARITY.lock.json"
TODO = "TODO: say why these differ"

# Applied to both apps' text, longest first, so a mention of the other app reads the same in both.
NAMES = [
    ("SteamGame.ALABASTER_DAWN", "<steam game>"),
    ("TheGame.steam", "<steam game>"),
    ("goldStatusBar", "<status bar>"),
    ("statusBar", "<status bar>"),
    ("AlabasterDawnBox", "<app>"),
    ("CrossCodeBox", "<app>"),
    ("alabasterdawnbox", "<pkg>"),
    ("crosscodebox", "<pkg>"),
    ("Alabaster Dawn", "<game>"),
    ("CrossCode", "<game>"),
]


def package_of(root):
    base = os.path.join(root, "app/src/main/java/io/github/aerithmetiq")
    for pkg in (ADB, CCB):
        if os.path.isdir(os.path.join(base, pkg)):
            return pkg
    sys.exit(f"{root} holds neither app")


def find_other(here, pkg):
    if os.environ.get("PARITY_OTHER"):
        return os.environ["PARITY_OTHER"]
    parent = os.path.dirname(here)
    names = ["CrossCodeBox"] if pkg == ADB else ["AlabasterDawnBox", "AlabasterDroid"]
    for name in names:
        path = os.path.join(parent, name)
        if os.path.isdir(path) and package_of(path) != pkg:
            return path
    sys.exit("the other repository was not found; set PARITY_OTHER to its folder")


def files(root, pkg):
    """Every file under app/src, keyed by its path with the package folder written as <pkg>."""
    out = {}
    for folder, dirs, names in os.walk(os.path.join(root, "app/src")):
        dirs.sort()
        for name in names:
            path = os.path.join(folder, name)
            out[os.path.relpath(path, root).replace(pkg, "<pkg>")] = path
    return out


def normalized(path):
    data = open(path, "rb").read()
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return None, data
    for name, token in NAMES:
        text = text.replace(name, token)
    return text, data


def difference(adb_path, ccb_path):
    """The normalized difference AlabasterDawnBox → CrossCodeBox, or None when there is none."""
    a, a_raw = normalized(adb_path)
    c, c_raw = normalized(ccb_path)
    if a is None or c is None:
        return None if a_raw == c_raw else "binary files differ\n"
    if a == c:
        return None
    lines = difflib.unified_diff(a.splitlines(), c.splitlines(), ADB, CCB, lineterm="", n=1)
    return "\n".join(lines) + "\n"


def fingerprint(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def main():
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    pkg = package_of(here)
    other = find_other(here, pkg)
    roots = {pkg: here, (CCB if pkg == ADB else ADB): other}
    fa, fc = files(roots[ADB], ADB), files(roots[CCB], CCB)

    found = {}
    for rel in sorted(set(fa) & set(fc)):
        diff = difference(fa[rel], fc[rel])
        if diff is not None:
            found[rel] = diff
    only = {ADB: sorted(set(fa) - set(fc)), CCB: sorted(set(fc) - set(fa))}

    lock_path = os.path.join(here, LOCK)
    lock = json.load(open(lock_path)) if os.path.exists(lock_path) else {"differ": {}, "only": {ADB: {}, CCB: {}}}

    if "--accept" in sys.argv:
        new = {"differ": {}, "only": {ADB: {}, CCB: {}}}
        for rel, diff in found.items():
            old = lock["differ"].get(rel, {})
            new["differ"][rel] = {"reason": old.get("reason", TODO), "diff": fingerprint(diff)}
        for app in (ADB, CCB):
            for rel in only[app]:
                new["only"][app][rel] = lock["only"].get(app, {}).get(rel, TODO)
        text = json.dumps(new, indent=2, sort_keys=True) + "\n"
        for root in roots.values():
            with open(os.path.join(root, LOCK), "w") as out:
                out.write(text)
        todo = sum(1 for e in new["differ"].values() if e["reason"] == TODO) + \
            sum(1 for app in (ADB, CCB) for r in new["only"][app].values() if r == TODO)
        print(f"recorded {len(found)} differing files and {len(only[ADB]) + len(only[CCB])} one-app files in both repositories"
              + (f"; {todo} need a reason (search for TODO in {LOCK})" if todo else ""))
        return 0

    problems = 0
    for rel, diff in found.items():
        known = lock["differ"].get(rel)
        if known is None:
            print(f"NEW DIFFERENCE  {rel}")
        elif known["diff"] != fingerprint(diff):
            print(f"CHANGED         {rel}  (reviewed: {known['reason']})")
        else:
            continue
        problems += 1
        print(diff)
    for rel in sorted(set(lock["differ"]) - set(found)):
        print(f"NOW THE SAME    {rel}  (run --accept to drop it from the lock)")
        problems += 1
    for app in (ADB, CCB):
        for rel in only[app]:
            if rel not in lock["only"].get(app, {}):
                print(f"ONLY IN {app.upper()}  {rel}")
                problems += 1
        for rel in lock["only"].get(app, {}):
            if rel not in only[app]:
                print(f"NO LONGER ONLY IN {app.upper()}  {rel}")
                problems += 1

    if problems:
        print(f"\n{problems} difference(s) not reviewed. Carry the change to the other app, or run "
              f"--accept and give the reason in {LOCK}.")
        return 1
    print(f"parity: {len(found)} files differ as reviewed, {len(only[ADB])} + {len(only[CCB])} one-app files; nothing new")
    return 0


if __name__ == "__main__":
    sys.exit(main())
