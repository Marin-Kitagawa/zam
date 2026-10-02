# Changelog

## 0.2.0 — 2026-10-02

### Fixed

- **`zam import` crashed on PowerShell-style history.** A line like
  `Set-Location C:\Windows` threw `InvalidPathException` and aborted the whole
  import. Worse, unrecognized lines (`git commit …`, `ls -la`) were recorded
  as fake directories. Import parsing is now strict: only bare absolute paths
  and `cd`-family / zoxide commands (`cd`, `chdir`, `pushd`, `Set-Location`,
  `sl`, `z`, `zoxide`, quoted paths, `&&`/`;` chains) yield candidates;
  everything else is skipped and counted. `~` paths expand to the home
  directory. New `--verify` flag records only paths that still exist.
- **A corrupt `db.json` was silently reset and overwritten** — total history
  loss with no warning. Now the corrupt bytes are quarantined as
  `db.json.corrupt-<time>.json` (once, not once per prompt), a loud warning
  is printed, and every successful save rotates the previous good generation
  into `db.json.bak`. zam never overwrites a file it could not read.
- **`query -i` could hang forever** when stdin was piped/redirected but a
  console existed (e.g. CI, or `echo | zam query -i`): the picker opened
  `CONIN$` and blocked. The picker now requires stdin to be an interactive
  terminal and otherwise prints the ranked list, as it already did without a
  console.
- **A lone `Esc` in the picker blocked until the next keypress** and swallowed
  that key. The ESC-followup read now times out after 40 ms.
- **`add <nonexistent>` exited 0** — scripts could not detect the failure.
  It now exits 1 when any path is rejected.
- **`query -s` printed the bare best match** instead of listing with scores
  (contradicting the docs). `-s` now implies listing, with scores.
- **`export out.txt` wrote into the data directory.** Relative targets now
  land in the current directory; the no-argument default still goes to the
  data dir.
- **Non-ASCII paths garbled on Windows consoles** (code page CP437/CP850):
  plain output now goes through `WriteConsoleW` (UTF-16) when attached to a
  console and UTF-8 bytes when piped; the PowerShell snippet sets
  `[Console]::OutputEncoding` to UTF-8; the picker's TUI writes via
  `WriteConsoleW` as well. Box drawing and CJK render regardless of `chcp`.
- **Java 21+ printed "restricted method" warnings** for JNA on every picker
  run. Launchers pass `--enable-native-access=ALL-UNNAMED` and the jar
  manifest carries `Enable-Native-Access`.
- **QuickEdit mouse selection could freeze the picker** mid-read; the raw
  console mode now clears `ENABLE_QUICK_EDIT_MODE`.
- `--max-results=abc` was silently ignored; it is now an error (exit 2).

### Added

- **Pastel "sakura" TUI theme** — the interactive picker is redesigned to be
  soft and cozy: a sakura-pink `❀ zam` header with the tagline "jump somewhere
  lovely", blossom/heart badges (`✿ fuzzy`, `✧ regex`, `♡ id #N`), a plum
  selection band with a heart marker and peach score, lavender match
  highlights, a quiet dusty-rose footer with keycaps and a signature `♡`,
  a little pink block cursor instead of reverse video, and gentler empty-state
  copy. Best on dark terminals; all colors are standard xterm-256.
- **Aliases** — named one-word shortcuts: `zam alias work C:\src\dayjob`,
  `zam alias docs <query>`, `zam alias` to list, `zam alias work` to resolve,
  `zam alias -d work` to drop. An exact alias beats fuzzy matching everywhere
  (bare invocation, `query`, `z`). Stored in `db.json` under `aliases`.
- **`zam move <old> <new>`** — re-point a remembered directory after moving
  or renaming a project; merges with the destination's history when one
  exists (`zam mv` also works).
- **`zam doctor`** — health check: dead entries, duplicate spellings, future
  timestamps, dangling aliases, broken config; exit 1 when problems exist.
- **`query --json`** — machine-readable output:
  `[{"path":…, "score":…, "visits":…, "last":…}, …]`.
- **Shell integrations for bash, zsh, and fish** (`zam shell bash|zsh|fish`)
  alongside PowerShell: `z` function, per-prompt recording hook, tab
  completion; launcher resolved via `zam` on PATH or `ZAM_JAR`.
- **cmd.exe support in `z.cmd`** — batch files run in-process in cmd.exe, so
  the launcher now `cd`s itself: `z`, `z <dir>`, `z <query>`, `z -i`,
  and records the visit (cmd.exe has no prompt hook). Display/management
  subcommands are forwarded verbatim.
- **Picker keys** — `Ctrl-P`/`Ctrl-K` up, `Ctrl-N`/`Ctrl-J` down, alongside
  the existing arrows and emacs-style `Ctrl-U`/`Ctrl-W`.
- Console reads now flow through a dedicated reader thread, so blocking and
  timeout reads can never race on the console handle.
- `FrameDump` (test tree only) renders picker frames to ANSI text and an HTML
  preview, so TUI design changes can be reviewed without a live terminal.

## 0.1.0

Initial release: full-history power-law frecency, zoxide-compatible fuzzy
matching, plain-JSON database with atomic writes and file locking, built-in
interactive picker (fuzzy / regex / id), PowerShell integration,
import/export, config, `explain`/`demo`.
