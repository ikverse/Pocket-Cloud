"""The `pocket` command: pocket.app() from a shell.

    pocket --app egx ls backups/
    pocket --app egx put report.pdf reports/2026/report.pdf
    pocket --app egx get reports/2026/report.pdf
    pocket --app egx backup data/ledger.db --keep 14
    pocket --app egx sync ./notes --prefix notes/
    pocket --app egx notify "Evening calls are ready" --title EGX --tag chart_with_upwards_trend
    pocket --app egx publish dist/egx-5.9.1.apk 5.9.1 --notes "Faster reports." --platform android-arm64

The app can also come from POCKET_APP. Machine setup as for pocket.connect(): <home>/.pocket/config or
POCKET_ENDPOINT + POCKET_ENROLL_SECRET.
"""

import argparse
import datetime
import os
import sys

from ._errors import PocketError


def _parser():
    p = argparse.ArgumentParser(prog="pocket", description="Pocket Cloud from the command line.")
    p.add_argument("--app", default=os.environ.get("POCKET_APP"), help="app id (default: $POCKET_APP)")
    p.add_argument("--home", help="Pocket home folder (default: ~/.pocket or $POCKET_HOME)")
    p.add_argument("--endpoint", help="server address, overriding the machine setup")
    sub = p.add_subparsers(dest="command", metavar="command")
    sub.required = True

    def cmd(name, help_):
        c = sub.add_parser(name, help=help_, description=help_)
        c.add_argument("--bucket", default="shared", help="shared (default), device or family")
        return c

    c = cmd("ls", "List files under a prefix.")
    c.add_argument("prefix", nargs="?", default="")
    c.add_argument("-l", "--long", action="store_true", help="show size, time and ETag")
    c.add_argument("-r", "--recursive", action="store_true", help="list everything below, not just one level")

    c = cmd("get", "Download a file (DEST '-' writes to standard output).")
    c.add_argument("key")
    c.add_argument("dest", nargs="?")

    c = cmd("put", "Upload a file (SRC '-' reads standard input; then KEY is required).")
    c.add_argument("src")
    c.add_argument("key", nargs="?")

    c = cmd("backup", "Back up a file (SQLite databases are copied safely first) and keep the newest N.")
    c.add_argument("path")
    c.add_argument("--keep", type=int, default=7)
    c.add_argument("--prefix", default="backups/")

    c = cmd("sync", "Two-way sync of a folder with a prefix.")
    c.add_argument("folder")
    c.add_argument("--prefix", default="")
    c.add_argument("--allow-mass-delete", action="store_true", help="allow a run to delete more than 50 files")

    c = sub.add_parser("notify", help="Send a notification to the app's ntfy topic.")
    c.add_argument("message")
    c.add_argument("--title")
    c.add_argument("--tag", action="append", dest="tags")
    c.add_argument("--priority", type=int, default=3)
    c.add_argument("--click")

    c = sub.add_parser("publish", help="Publish a release file and update releases/latest.json.")
    c.add_argument("file")
    c.add_argument("version")
    c.add_argument("--notes", default="")
    c.add_argument("--platform")

    return p


def _size(n):
    if n is None:
        return "-"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def _when(t):
    if not t:
        return "-"
    return datetime.datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M")


def run(argv=None, out=None):
    out = out or sys.stdout
    args = _parser().parse_args(argv)
    if not args.app:
        print("pocket: say which app with --app NAME or POCKET_APP.", file=sys.stderr)
        return 2
    from ._app import app
    p = app(args.app, home=args.home, endpoint=args.endpoint)
    c = args.command

    if c == "ls":
        for e in p.list(args.prefix, args.bucket, folders=not args.recursive):
            if args.long:
                print(f"{_size(e.size):>10}  {_when(e.modified)}  {e.key}", file=out)
            else:
                print(e.key, file=out)
    elif c == "get":
        if args.dest == "-":
            data = p.get(args.key, args.bucket)
            (getattr(out, "buffer", None) or out).write(data)
        else:
            dest = args.dest or os.path.basename(args.key)
            if os.path.isdir(dest):
                dest = os.path.join(dest, os.path.basename(args.key))
            p.get_file(args.key, dest, args.bucket)
            print(f"{args.key} -> {dest}", file=out)
    elif c == "put":
        if args.src == "-":
            if not args.key:
                print("pocket: put - needs a KEY.", file=sys.stderr)
                return 2
            e = p.put(args.key, sys.stdin.buffer.read(), args.bucket)
        else:
            e = p.put_file(args.key or os.path.basename(args.src), args.src, args.bucket)
        print(f"{args.src} -> {e.bucket}/{e.key} ({_size(e.size)})", file=out)
    elif c == "backup":
        e = p.backup(args.path, keep=args.keep, prefix=args.prefix, bucket=args.bucket)
        print(f"{args.path} -> {e.bucket}/{e.key} ({_size(e.size)})", file=out)
    elif c == "sync":
        r = p.sync(args.folder, args.bucket, args.prefix, args.allow_mass_delete)
        for label, keys in (("up", r.uploaded), ("down", r.downloaded), ("deleted here", r.deleted_local),
                            ("deleted there", r.deleted_remote), ("conflict", r.conflicts)):
            for k in keys:
                print(f"{label:>13}  {k}", file=out)
        print(f"{len(r.uploaded)} up, {len(r.downloaded)} down, "
              f"{len(r.deleted_local) + len(r.deleted_remote)} deleted, {len(r.conflicts) // 2} conflicts", file=out)
    elif c == "notify":
        p.notify(args.message, title=args.title, tags=args.tags, priority=args.priority, click=args.click)
        print("sent", file=out)
    elif c == "publish":
        rel = p.publish(args.file, args.version, notes=args.notes, platform=args.platform)
        print(f"published {rel.app} {rel.version}", file=out)
        for f in rel.files:
            print(f"  {f.path}  {_size(f.size)}  {f.url or ''}".rstrip(), file=out)
    return 0


def main(argv=None):
    try:
        return run(argv)
    except PocketError as e:
        print(f"pocket: {e.message or e} [{e.code}]", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
