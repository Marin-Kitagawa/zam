# Changelog

## 0.3.0 — 2026-11-26

### Fixed

- **PowerShell Tab completion searched for the cursor column, not what you
  typed.** `Register-ArgumentCompleter -CommandName` without `-ParameterName`
  is treated as a *native* completer, so the script block receives
  `($wordToComplete, $commandAst, $cursorPosition)`. The old snippet declared
  the 5-parameter cmdlet form, which made `$wordToComplete` the cursor
  position — pressing Tab after `z d` ran `zam query --list 4` and returned
  arbitrary entries whose names happened to contain "4". The snippet now
  detects the argument shape, so it works in both PowerShell 7.6+ and
  Windows PowerShell 5.1.
- **Tab completion offered old, even dead, directories instead of what is
  actually in the current directory.** `zam query --list <word>` forces
  `--all`, so the menu was the entire history — dead paths included — ranked
  by frecency and filtered by zoxide's *last-component* fuzzy rule. Nothing in
  the current working directory was ever considered. Completion is now its own
  engine (`zam complete`, `Completion.scala`) that returns, in order: children
  of the current directory, aliases, then remembered paths. Missing
  directories are excluded, the current directory is never suggested as
  history, and history rows carry visits / age / score as a tooltip.
- **PowerShell completion quoted nothing and used the wrong working
  directory.** Paths with spaces broke the inserted token, and the completer
  read the JVM's process cwd (PowerShell's `Set-Location` does not change it).
  The snippet now passes `--cwd (Get-Location).ProviderPath`, wraps values
  containing whitespace in single quotes, and returns real
  `CompletionResult` objects. It also completes `zam` subcommands and `z`
  flags, and falls back to `java -jar $env:ZAM_JAR` when no launcher is on
  `PATH`.
- **`zam add ~` reported a false failure** (exit 1, "not a directory"): the
  verification path did not expand `~` even though the recorded path did.
- **`--exclude` only matched whole paths**, contradicting the documented
  "skip paths containing these components". A plain name now matches any path
  component and an absolute path excludes its whole subtree.
- **An exact alias hijacked listing modes**: `zam query -l work`,
  `query --json work` and `query -i work` returned the alias instead of
  listing. The alias shortcut now applies only to plain "print the best
  match" queries.
- Non-local `return`s in `Store.parseHistoryLine` (a Scala 3.9 error class)
  were replaced with ordinary control flow.

### Added

- **Automated releases** — `.github/workflows/release.yml` runs the test
  suite and builds the assembly jar on a `v*` tag, verifies that
  `java -jar … version` reports the tag, attaches the jar plus
  `SHA256SUMS.txt` to a GitHub Release, and uses the matching `CHANGELOG.md`
  section as release notes. The build version comes from `ZAM_VERSION` (set
  from the tag) with `0.3.0` as the local default, and `zam version` reads the
  packaged manifest, so the tag and the binary cannot drift.
- **`zam complete [<word>]`** — the completion engine behind every shell
  snippet. Filesystem-first, filesystem-aware for path-like words
  (`z C:\Us<TAB>`, `z ../pro<TAB>`), flag completion (`z -<TAB>`), `--cwd`,
  `--limit`, `--all`, and `--json` for rich menus.
- **`zam recent [N]`** — directories ordered by when you were last there,
  not by frecency; `--all` includes gone paths, `--json` adds age/visits/score.
- **`query --regex=PATTERN`** — case-insensitive regex search over the full
  path (the picker's `Ctrl-R` mode, now available from the CLI). Invalid
  patterns exit 2.
- **`query --print0`** — NUL-separated output for `xargs -0` and friends.
- **`zam import -`** — read history from stdin
  (`zoxide query -l | zam import -`).
- **Value flags accept the space-separated form** — `--regex foo`,
  `--exclude a,b`, `--max-results 5`, `--cwd DIR`, `--limit N`, `--max-age N`.
- Bash, zsh and fish completion now go through `zam complete` (local
  directories first, dead entries omitted, spaces quoted by the shell) and
  complete `zam` subcommands.
- Shell `z` helpers gained `z -s` (list with scores) and `z -i` (picker) in
  bash, zsh and fish.

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
