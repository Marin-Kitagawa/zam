# zam

A smarter `cd`, written in Scala 3 — a drop-in alternative to
[zoxide](https://github.com/ajeetdsouza/zoxide) with one important upgrade: it
keeps your **full visit history** and scores it, instead of collapsing each
directory to `(visit count, last access)`.

```console
zam docu        -> cd C:\Users\you\Documents
zam sys         -> cd C:\Windows\System32
```

## What is it?

zam is a *frecency* (frequency + recency) directory jumper. You visit
directories, it remembers *when* you visited them, and it can jump you to the
best-matching directory for any fuzzy query.

Type a few characters that appear in a directory path and zam finds the
directory you most likely want, based on:

- **how often** you have been there, and
- **how recently** you have been there.

It is designed as a drop-in replacement for zoxide: the same one-word command
shape, the same fuzzy-matching rules, and the same bare-invocation semantics
(an existing directory is recorded; anything else is a jump query).

## Why it beats zoxide

zoxide reduces every directory's history to two numbers: how many times you
visited it and the last time you did. That *loses information*:

> "100 visits concentrated last week plus one now" and "100 visits spread over
> three months plus one now" have the identical count and identical last
> access, so they score *nearly identically*.

zam stores the actual visit timestamps and scores them with a continuous
power-law aging kernel:

$$
\mathrm{frecency}(\text{path}) = \sum_{i \in \text{visits}} \left( 1 + \frac{\text{age}_i}{\tau} \right)^{-\alpha}
$$

with $\tau = 1$ day and $\alpha = 1$ by default. A visit contributes ~1 to
the score while it is younger than $\tau$, then decays *polynomially*.
Frequency and recency both matter, and the shape of the history matters — not
just its last element.

Run `zam demo` to see it:

```console
name                                        n      last  zoxide      zam
A  (100 visits within the last week + now)  101  just now     404   25.633
B  (100 visits spread over 90 days + now)   101  just now     404    6.697
C  (6 visits today, nothing older)            6   12h ago      12    4.703
```

zoxide forces A and B to tie at `count × 4`; zam ages every visit
individually, so recent momentum (A) outranks long-forgotten habit (B) — and
recency still matters: C has the highest score per visit.

Other differences from zoxide:

- **Transparent scores** — `zam explain <query>` prints a full score
  breakdown: last-visit weight, history weight, and each recent visit's
  contribution, plus what zoxide's aggregate model would have produced for
  comparison.
- **Auditable data** — the database is plain JSON
  (`%LOCALAPPDATA%\zam\db.json`), not bincode. Inspect it, diff it, even
  `git init` your data directory.
- **Event-counted aging** — the database is bounded by *total visits* (oldest
  history is dropped first when the cap is hit), so a week's holiday doesn't
  silently erase entries the way wall-clock pruning does.
- **Correct config** — `alpha`, `tau`, and `max_visits` are real JSON doubles
  that persist across runs — no reparse bugs of the zoxide kind.

## Installation

Requirements: JDK 17+ and an sbt launcher, or any JDK plus sbt installed via
coursier.

1. Clone or copy the repository, then build the single runnable jar. The
   interactive picker needs the JNA bridge libraries, which sbt's assembly
   task fetches and bundles automatically (no extra install step):

   ```console
   sbt assembly
   ```

   The jar lands at:

   ```text
   target/out/jvm/scala-3.9.0/zam/zam-assembly-0.3.0.jar
   ```

2. Put a launcher next to the jar and add that directory to your `PATH`. The
   launchers find the jar via a path *relative to the script*, so you can
   move or copy the whole directory without editing anything. On Windows use
   `z.cmd` (see [cmd.exe](#cmdexe) below — it actually `cd`s for you) and
   `zam.cmd` as a plain forwarder for scripts. On Unix, a two-line
   `zam` script does the same job:

   ```sh
   #!/bin/sh
   exec java --enable-native-access=ALL-UNNAMED -jar "$(dirname "$0")/zam-assembly-0.3.0.jar" "$@"
   ```

   `--enable-native-access` (Java 21+) keeps the interactive picker's console
   access warning-free; without it the picker still works but Java prints a
   warning per run.

> [!TIP]
> In PowerShell, a `.cmd` batch file runs in a child process and can never
> change the *parent* shell's working directory. For `z <dir>` to actually
> `cd` in PowerShell, use the [PowerShell integration](#powershell-integration)
> below. In **cmd.exe** the situation is different — a batch file runs
> *in-process* there, so `z.cmd` performs the `cd` itself (and records the
> visit), giving you `z <query>` with no profile setup at all.

Startup on the JVM (~0.3–0.6 s) is the one thing a native binary does
better. For production, package the same code with Scala Native or GraalVM
native-image — the toolchain-agnostic scoring (`Model`/`Score`) is pure Scala
and identical.

## Quick start

```console
zam add C:\Users\you\Documents          # record a visit (existing dirs only)
zam docu                                 # print the best match for "docu"
zam list                                 # list remembered dirs, best first
zam explain docu                         # why does each match rank where it does
```

Bare invocations follow zoxide semantics:

- `zam <path>` where `<path>` is an *existing directory* → record the visit.
- `zam <query>` otherwise → print the best-matching directory (the shell
  function below turns "print" into `cd`).

## Commands

```text
zam add <path>...      record visits        [--no-verify]
zam query <query>      print best match     [-l | -s | -a | -i | --json], [--regex=PATTERN],
                                             [--print0], [--max-results=N], [--exclude=P1,P2]
zam list               remembered dirs, best first
zam recent [N]         most recently visited dirs (true recency)
zam complete [word]    tab-completion candidates (filesystem + history)
zam remove <path>...   forget paths
zam move <old> <new>   re-point a remembered dir (histories merge)
zam alias [<n> <tgt>]  named shortcuts; `zam alias` lists, `zam alias n` resolves, `alias -d n` drops
zam prune              forget gone dirs last seen > --max-age=DAYS ago (default 90); --dry-run
zam explain <query>    why each candidate ranks where it does
zam stats              database statistics
zam doctor             check the database for problems
zam config [k=v ...]   view/set alpha, tau, max_visits   (also: config reset)
zam shell [<name>]     print shell integration: powershell (default), bash, zsh, fish
zam import <file>      add dirs from a shell history / log file   [--verify]
zam export [<file>]    write remembered paths, one per line
zam demo               scoring comparison vs zoxide
zam version            print version
```

Global flag: `--data-dir <dir>` overrides the database location (environment
variable: `ZAM_DATA_DIR`).

### add

Records a visit for one or more paths.

```console
zam add C:\src\project-a C:\src\project-b
```

- Verifies the path is a directory by default; `--no-verify` records it
  blindly (useful for importing).
- First visit creates the entry; later visits append a timestamp.
- Rejected paths are reported and make the command exit `1`, so scripts can
  detect a failed add.

### query

Prints the best match for a fuzzy query.

```console
zam query foo bar          # both terms must appear, in order
zam query -l               # list top matches, one path per line
zam query -s               # like -l, but with scores (implies listing)
zam query -a               # include directories that no longer exist
zam query -i               # interactive picker (built-in TUI) — or --interactive
zam query --json           # machine-readable: [{"path","score","visits","last"}, ...]
zam query --regex='^C:\\src'  # case-insensitive regex on the full path
zam query -l --print0      # NUL-separated output, safe for xargs -0
zam query --max-results=5  # cap the result set
zam query --exclude=a,b    # skip a path component, or an absolute path subtree
```

Exit status is 0 on a match, 1 otherwise. The PowerShell `z` function relies
on this to decide whether to `cd`.

`--regex` and `--exclude` accept either `--flag=value` or `--flag value`.

#### Interactive picker (`-i`)

`zam query -i` opens a self-contained full-screen picker (no external
dependencies — same spirit as fzf, but built in). It shows the ranked list
with score and a scrollbar; on Windows it drives the console directly via
JNA, on Unix via `/dev/tty`. The theme is soft pastel — a sakura-pink header
(`❀ zam`), a plum selection band with a heart marker, lavender match
highlights, peach scores, and a little pink block cursor:

```text
 ❀ zam  ·  jump somewhere lovely  ✿ fuzzy  ·  3 places
 ❯ wall█
────────────────────────────────────────────────────────
 ♡   1  C:\Users\you\Pictures\wallpapers\sakura   4.850
     2  C:\Users\you\Pictures\icons               1.203
     3  C:\Users\you\Pictures\avatars             0.902
────────────────────────────────────────────────────────
 ↑↓ move   ↵ choose   esc cancel   ctrl-r regex   123 by id  ♡
```

- **Type to filter** — the fuzzy `zoxide` matcher (same semantics as
  `zam query foo bar`). Type **digits only** to select a row directly by its
  `#N` id; the cursor jumps to that row and `Enter` chooses it.
- **`Ctrl-R`** — toggle regex mode; the query becomes a real (case-insensitive)
  regular expression, and matched runs are highlighted.
- **`Ctrl-W`** — delete the last word, **`Ctrl-U`** — clear the query,
  **`↑/↓/Ctrl-P/Ctrl-N/Ctrl-K/Ctrl-J/Home/End/PgUp/PgDn`** — move the cursor.
- **`Enter`** — choose the current row; **`Esc`** or **`Ctrl-C`** — cancel.
- If the terminal is too small (< 40 cols or < 10 rows) the picker skips
  itself and prints the ranked list instead, so scripts never hang.

### list

Shorthand for `zam query --list --all` — all remembered directories, best
first, whether or not they still exist.

### recent

Directories ordered by **when you were last there**, not by frecency — the
"what was I just working on" view. Gone directories are hidden unless
`--all`; `--json` adds the age, visit count and current score.

```console
zam recent            # 20 most recently visited dirs
zam recent 5          # just the five most recent
zam recent --json     # [{"path","last","age","visits","score"}, ...]
```

### complete

The completion engine behind the shell snippets. `zam complete <word>`
prints one insertion candidate per line, best first. It is **filesystem
first**: children of the current directory come before remembered paths, so
a stale entry can never crowd out the directory that is right in front of
you. Only existing directories are offered unless `--all` is passed.

```console
zam complete do                    # documents/ here first, then history
zam complete 'C:\Us'               # path-component completion
zam complete --cwd 'C:\src' -- -i  # flag completion for `z`
zam complete --json -- ''          # rich list (tooltips) for shells
```

Candidates carry a `source`: `here` (a real child directory), `history`
(a remembered path), `alias`, or `flag`. The PowerShell integration uses the
JSON form to show visit counts and scores as tooltips.

### remove

Forgets paths entirely, including their history.

```console
zam remove C:\src\old-project
```

### move

Renamed or relocated a project? Re-point its whole history at the new path
in one step (if the destination is already remembered, the two histories
merge):

```console
zam move C:\src\old-project C:\work\renamed-project
```

The target is verified to exist; `--no-verify` skips that check.

### alias

Named shortcuts — one word that always jumps to one directory, no matter how
it ranks. An exact alias match beats fuzzy matching:

```console
zam alias work C:\src\dayjob     # point an alias at a path
zam alias docs C:\\doc           # or at whatever a fuzzy query matches today
zam alias                        # list all aliases
zam alias work                   # print the target (handy in scripts)
zam alias -d work                # drop an alias
```

`z work`, `zam work`, and `zam query work` all honor the alias; a broken
alias (target deleted) is reported instead of silently ignored, and
`zam doctor` flags it.

### doctor

Health check for the database — dead entries, duplicate spellings of the
same directory, future timestamps, dangling aliases, broken config:

```console
zam doctor        # exit 0 = all good, 1 = problems listed
```

### prune

Forgets directories that no longer exist and whose last visit is older than
`--max-age` days (default 90).

```console
zam prune --dry-run        # preview what would be removed
zam prune --max-age=30     # more aggressive
```

### explain

Prints, for each match, the full scoring breakdown:

```console
zam explain docu
```

Produces, per candidate:

- total zam score and the zoxide-model score for comparison
- visit count and human-readable last-visit age
- last-visit weight vs. remaining history weight
- the five most recent visits with their individual contributions

### stats

Shows the data file location, entry/visit counts, history span, effective
config, and the current top-5 directories.

### config

View or set scoring parameters.

```console
zam config                     # show current values
zam config alpha=1.5           # harder-tailed decay
zam config tau=43200           # 12-hour half-life-ish knee
zam config max_visits=50000    # bound history by event count
zam config reset               # restore defaults
```

### shell

Prints an integration snippet for your shell — `zam shell powershell` (the
default), `zam shell bash`, `zam shell zsh`, or `zam shell fish`. Each
snippet installs a `z` function (jump, list, previous-dir) plus a per-prompt
hook that records every directory change. See
[PowerShell integration](#powershell-integration) below for the Windows
details; the bash/zsh/fish snippets resolve the launcher via `zam` on PATH
or a `ZAM_JAR` environment variable.

### import / export

- `zam import <file>` — parse a shell history / log and record every
  mentioned directory. Parsing is strict: a line is used when it is a bare
  *absolute* path, or a `cd`-family command (`cd`, `chdir`, `pushd`,
  `Set-Location`, `sl`, `z`, `zoxide`, quoted paths, `&&`/`;` chains)
  with an absolute argument. Anything else — `git commit`, `ls -la`,
  relative paths — is skipped and counted, never invented into a fake
  directory. `--verify` additionally records only paths that still exist.
  `zam import -` reads the history from stdin, so
  `zoxide query -l | zam import -` works without a temp file.
- `zam export [<file>]` — write remembered paths (best first). A relative
  file name lands in the **current directory**; with no argument the
  default `zam-export.txt` goes to the data directory.

### demo

Reproducible head-to-head of zam vs. zoxide scoring on three synthetic
histories.

### version / help

`zam version` prints the version; `zam help`, `-h`, or `--help` prints the
usage summary.

## Matching rules

Identical to zoxide's documented algorithm:

1. **Case-insensitive.**
2. **All terms must be present, in order** (slashes included): `zam fo ba`
   matches `/foo/bar`, but not `/bar/foo`; `zam fo / ba` matches `/foo/bar`,
   but not `/foobar`.
3. **The last term must land in the last path component:** `zam bar` matches
   `/foo/bar`, but not `/bar/foo`; `zam foo/bar` matches `/foo/bar`, but not
   `/foo/bar/baz`.

## Scoring model

The score of an entry is a sum over its visit timestamps:

$$
w_i = \left( 1 + \frac{now - t_i}{\tau} \right)^{-\alpha}, \qquad
\mathrm{score} = \sum_{i} w_i
$$

where $now - t_i$ is the visit's age in seconds.

$\tau$ is the aging time scale in seconds (default `86400`, one day).
$\alpha$ controls how fast older visits lose influence (default `1`, i.e.
polynomial decay). Both act per-visit: a burst of activity in the past week
outweighs the same count spread over three months, while a single fresh visit
still beats many ancient ones.

### Parameters

| Parameter    | Default          | Meaning                                                         |
|--------------|------------------|-----------------------------------------------------------------|
| `alpha`      | `1.0`            | Decay exponent. Larger = older visits die faster.               |
| `tau`        | `86400` (1 day)  | Characteristic age in seconds. A visit is worth ~1 until this old. |
| `max_visits` | `100000`         | Cap on total stored visits. Excess is dropped oldest-first before entries with no visits left are forgotten. |

## Database & data format

- **Location:** `%LOCALAPPDATA%\zam\db.json`, or anywhere set by
  `ZAM_DATA_DIR` or `--data-dir`.
- **Format:** plain JSON, epoch-second timestamps (UTC), pretty-printed,
  with the scoring `config`, optional `aliases`, and the `entries`.
- **Mutation:** writes go to `db.json.tmp` and are atomically renamed over
  `db.json`, guarded by an OS file lock on `db.lock`. Concurrent shells
  cannot corrupt the file.
- **Backups:** every successful save rotates the previous good generation
  into `db.json.bak`, so any bug or crash is one file-copy away from
  recovery.
- **Corruption is loud, never silent:** if `db.json` cannot be decoded, zam
  warns on stderr, quarantines the raw bytes as `db.json.corrupt-<time>.json`,
  and starts from an empty database — it will never overwrite a file it
  could not read (and `db.json.bak` still holds the last good generation).
  `zam doctor` re-checks structure, duplicates, and dead entries.

Example:

```json
{
  "version": 1,
  "config": {
    "alpha": 1.0,
    "tau": 86400.0,
    "max_visits": 10000000.0
  },
  "entries": [
    {
      "p": "C:\\Users\\you\\Documents",
      "t": [1710000000, 1710100000, 1710200000]
    }
  ]
}
```

Because the file is human-readable, you can back it up, diff it, or keep it
under `git`. On Windows, path keys are compared case-insensitively; display
preserves the original casing you typed.

## PowerShell integration

The core trick: **only a PowerShell *function* can change the session's
working directory.** The whole point of `zam shell` is to install one.

### Installing

```powershell
zam shell | Set-Content -Path $PROFILE -Append
```

Then start a new terminal (or run `. $PROFILE`).

The snippet is *launcher-aware*: it discovers your wrapper as `z.cmd`,
`zam.cmd`, or `zam`, whichever is on `PATH`. So renaming the launcher (for
example renaming `zam.cmd` to `z.cmd`) does not break the hook — the profile
block re-resolves the command on every fresh session.

### Installing without duplicating an old block

The snippet is delimited by `# --- zam shell integration ---` /
`# --- end zam shell integration ---`. To replace an older installed version
in place:

```powershell
Copy-Item $PROFILE "$PROFILE.bak"                        # safety backup
$snip = & 'C:\Users\you\zam\bin\z.cmd' shell
$raw  = Get-Content $PROFILE -Raw
$raw  = [regex]::Replace($raw, '(?s)# --- zam shell integration ---.*?# --- end zam shell integration ---\r?\n?', '')
$new  = $raw.TrimEnd() + "`r`n`r`n" + ($snip -join "`r`n") + "`r`n"
Set-Content $PROFILE -Value $new -Encoding UTF8
. $PROFILE
```

This removes only the old block and appends the new one — everything else in
your profile is untouched.

### What the hook gives you

- A `z` function, so `z` resolves to PowerShell (functions beat external
  commands), not to the batch launcher:

  - `z` — `cd` to your home directory (`~`)
  - `z -l` (or `z --list`) — list remembered directories; `z -s` lists with scores
  - `z -i` — interactive picker (fuzzy / regex / id)
  - `z -` — return to the previous directory
  - `z <existing dir>` — `cd` straight into it (and it is recorded by the
    per-prompt hook)
  - `z <query>` — `cd` to the best match

- A per-prompt hook (`__zamHook`, chained through the `Prompt` function) that
  records every directory change. It cooperates cleanly with posh-git; if you
  use another prompt framework, call `__zamHook` from your own `Prompt`.
- `Tab` completion for `z` arguments and `zam` subcommands, backed by
  `zam complete`. The menu is **filesystem first**: children of the current
  directory appear above remembered paths, directories that no longer exist
  are left out, and history rows show visits / age / score as a tooltip.
  `Tab` passes the session location explicitly (`--cwd`), because
  PowerShell's process working directory is not its current location. The
  snippet detects both argument-completer signatures — the 5-parameter form
  used by Windows PowerShell 5.1 and the 3-parameter native form that
  PowerShell 6+ uses when `-ParameterName` is absent — so Tab works on both.

### Why the hook needs a function and not just the batch file

In PowerShell, a `.cmd` stub in `PATH` cannot affect the calling shell:
`z.cmd` runs in a child `cmd.exe`, and any `cd` it performs is discarded when
it exits. zoxide has exactly the same constraint on Windows and solves it the
same way — a profile function. If all you want is the *output* of a query
from a script, `z.cmd query <query>` prints the path to stdout and is
perfectly usable without the hook.

## cmd.exe

PowerShell needs a function to `cd`, but cmd.exe runs batch files
*in-process* — so there the launcher itself can do the jump. `z.cmd` is
written for that:

- `z` — `cd` to your home directory
- `z <existing-dir>` — `cd` straight there
- `z <query>` — `cd` to the best match (recorded automatically, since cmd.exe
  has no prompt hook)
- `z -i` — interactive picker, then `cd` to the chosen row
- `z -l`, `z -s`, `zam add`, `zam stats`, … — everything else is forwarded
  to the jar untouched

In PowerShell the `z` *function* from the integration snippet shadows this
batch file (functions win), so the two coexist without interference.

## Importing your old zoxide history

A text list of paths (for example `zoxide query -l` output, or any shell
history) can be seeded in one shot:

```console
zoxide query -l > dirs.txt     # if you still have zoxide installed
zam import dirs.txt
```

## Development

Build and test:

```console
sbt compile                      # compile
sbt "Test/runMain zam.TestMain"  # dependency-free test runner (172 checks)
```

Source layout (`.scala` files under `src/main/scala/zam/`):

| Component       | File             | Purpose                                                                |
|-----------------|------------------|------------------------------------------------------------------------|
| `Model` / `Score` | `Model.scala` | Entry/DB types, power-law frecency, zoxide-model comparison, human ages. |
| `Matcher`       | `Matcher.scala` | zoxide-faithful fuzzy matching (case-fold, ordered terms, last-component). |
| `Completion`    | `Completion.scala` | Tab-completion ranking: local directories, aliases, history, flags. |
| `Store`         | `Store.scala`   | JSON persistence, file locking, atomic writes, cap/prune/remove logic.  |
| `Cli`           | `Cli.scala`     | Argument parsing and all subcommands, including the bare-invocation logic. |
| `Picker`        | `Picker.scala`  | Built-in interactive picker: fuzzy / regex / id modes, scrolling TUI.   |
| `Terminal`      | `Terminal.scala`| Raw console I/O via JNA (Windows) and /dev/tty+stty (Unix); ANSI/VT keys. |
| `ConsoleIO`     | `ConsoleIO.scala`| UTF-16 console output (`WriteConsoleW`) so non-ASCII paths never garble. |
| `Shell`         | `Shell.scala`   | Integration snippets for PowerShell, bash, zsh, and fish.               |
| `Json`          | `Json.scala`    | Dependency-free JSON reader/writer.                                    |

The scoring code has no external dependencies — it is pure Scala — so it
transports unchanged to Scala Native or GraalVM for a native binary.

## Releasing

Releases are built and published by
[`.github/workflows/release.yml`](.github/workflows/release.yml):

1. Add a `## <version>` section to `CHANGELOG.md` and bump the fallback
   version in `build.sbt` (only needed for local builds).
2. Commit, then tag and push:

   ```console
   git tag v0.3.1
   git push origin v0.3.1
   ```

The workflow derives the version from the tag (`v0.3.1` → `0.3.1`), exports it
as `ZAM_VERSION`, runs the test suite, builds the assembly jar, and verifies
that `java -jar … version` reports the tag — so the binary and the tag can
never drift. It uploads the jar plus `SHA256SUMS.txt` to a GitHub Release and
uses the matching `CHANGELOG.md` section as the release notes.

Nothing else is needed: `sbt assembly` locally still produces
`zam-assembly-0.3.0.jar` (or whatever `ZAM_VERSION` is set to), and the same
tag-driven flow can be re-run from **Actions → Release → Run workflow**.

## Command quick reference

```console
zam add        <path>...       record visits            (--no-verify)
zam query      [query]         print best match         (-l -s -a -i --json --regex=PATTERN --print0)
zam list                       list remembered dirs
zam recent     [N]             most recently visited dirs        (--all, --json)
zam complete   [word]          tab-completion candidates         (--cwd=DIR, --limit=N, --all, --json)
zam remove     <path>...       forget paths
zam move       <old> <new>     re-point a moved directory
zam alias      [n] [target]    named shortcuts          (alias -d <name> drops one)
zam prune                      prune gone dirs          (--max-age=DAYS, default 90; --dry-run)
zam explain    <query>         show scoring breakdown
zam stats                      database statistics
zam doctor                     check the database for problems
zam config     [k=v ...]       alpha / tau / max_visits  (config reset)
zam shell      [powershell|bash|zsh|fish]
                               print shell integration
zam import     <file|->        add dirs from a history/log file   (--verify)
zam export     [<file>]        write remembered paths
zam demo                       comparison vs zoxide
zam version                    print version
```
