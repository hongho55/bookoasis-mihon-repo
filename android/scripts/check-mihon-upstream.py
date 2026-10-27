#!/usr/bin/env python3
"""Read-only Mihon upstream release check; makes no local or remote changes."""

import json
import subprocess
import sys
import urllib.error
import urllib.request

REPOSITORY = "mihonapp/mihon"
API = f"https://api.github.com/repos/{REPOSITORY}"


def get_json(url):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "mihon-local-upstream-check/1.0",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def git(*args):
    return subprocess.check_output(["git", *args], text=True, stderr=subprocess.DEVNULL).strip()


def main():
    try:
        root = git("rev-parse", "--show-toplevel")
        head = git("rev-parse", "HEAD")
        branch = git("branch", "--show-current") or "(detached HEAD)"
        release = get_json(f"{API}/releases/latest")
        main_branch = get_json(f"{API}/branches/main")
    except (subprocess.CalledProcessError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        print(f"Upstream check failed: {error}", file=sys.stderr)
        return 1

    print(f"Repository: {REPOSITORY}")
    print(f"Local checkout: {root}")
    print(f"Local HEAD: {head} ({branch})")
    print(f"Latest upstream release: {release.get('tag_name', '(untagged)')}")
    print(f"Published: {release.get('published_at', '(unknown)')}")
    print(f"Release notes: {release.get('html_url', '(none)')}")
    print(f"Upstream main SHA: {main_branch['commit']['sha']}")
    print(f"Upstream main: https://github.com/{REPOSITORY}/tree/main")
    print("Read-only check only: no fetch, checkout, merge, commit, push, build, or deployment was performed.")
    print("Review the release notes and compare upstream changes manually before preparing an isolated update branch.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
