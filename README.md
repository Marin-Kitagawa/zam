# zam

A smarter `cd`, written in Scala 3 — a drop-in alternative to
[zoxide](https://github.com/ajeetdsouza/zoxide) with one important upgrade: it
keeps your **full visit history** and scores it, instead of collapsing each
directory to `(visit count, last access)`.

```console
zam docu        -> cd C:\Users\Ahri\Documents
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

1. Clone or copy the repository, then build the single runnable jar:

   ```console
   sbt assembly
   ```

   The jar lands at:

   ```text
   target/out/jvm/scala-3.9.0/zam/zam-assembly-0.1.0.jar
   ```

2. Add the `bin/` directory to your `PATH`. The launcher `bin/z.cmd`
   (originally shipped as `bin/zam.cmd`) finds the jar using a path *relative
   to the script*, so you can move or copy the whole repo without editing
   anything.

> [!TIP]
> In PowerShell, a `.cmd` batch file runs in a child process and can never
> change the *parent* shell's working directory. For `z <dir>` to actually
> `cd`, you must use the [PowerShell integration](#powershell-integration)
> below — the batch launcher is for the CLI and for scripts.

Startup on the JVM (~0.3–0.6 s) is the one thing a native binary does
better. For production, package the same code with Scala Native or GraalVM
native-image — the toolchain-agnostic scoring (`Model`/`Score`) is pure Scala
and identical.

## Quick start

```console
zam add C:\Users\Ahri\Documents          # record a visit (existing dirs only)
zam docu                                 # jump to the best match for "docu"
zam                                      # list remembered dirs, best first
zam explain docu                         # why does each match rank where it does
```

Bare invocations follow zoxide semantics:

- `zam <path>` where `<path>` is an *existing directory* → record the visit.
- `zam <query>` otherwise → print the best-matching directory (the shell
  function below turns "print" into `cd`).

## Commands

```text
zam add <path>...      record visits        [--no-verify]
zam query <query>      print best match     [-l | -s | -a | -i], [--max-results=N], [--exclude=P1,P2]
zam list               remembered dirs, best first
zam remove <path>...   forget paths
zam prune              forget gone dirs last seen > --max-age=DAYS ago (default 90); --dry-run
zam explain <query>    why each candidate ranks where it does
zam stats              database statistics
zam config [k=v ...]   view/set alpha, tau, max_visits   (also: config reset)
zam shell              print PowerShell integration
zam import <file>      add dirs from a shell history / log file
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

### query

Prints the best match for a fuzzy query.

```console
zam query foo bar          # both terms must appear, in order
zam query -l               # list top matches, one path per line
zam query -s               # like -l, but with scores
zam query -a               # include directories that no longer exist
zam query -i               # interactive picker via fzf (falls back to a ranked list)
zam query --max-results=5  # cap the result set
zam query --exclude=a,b    # skip paths containing these components
```

Exit status is 0 on a match, 1 otherwise. The PowerShell `z` function relies
on this to decide whether to `cd`.

### list

Shorthand for `zam query --list --all` — all remembered directories, best
first, whether or not they still exist.

### remove

Forgets paths entirely, including their history.

```console
zam remove C:\src\old-project
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

Prints the PowerShell integration snippet (see
[PowerShell integration](#powershell-integration)).

### import / export

- `zam import <file>` — parse a shell history / log and record every
  mentioned directory (`cd` prefixed lines, `&&`/`;` chains, and quoted
  paths are handled). Useful for a one-time seed from your old zoxide or
  PSReadLine history.
- `zam export [<file>]` — write remembered paths (best first) to a text file,
  default `zam-export.txt` in the data directory.

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
- **Format:** plain JSON, epoch-second timestamps (UTC), pretty-printed.
- **Mutation:** writes go to `db.json.tmp` and are atomically renamed over
  `db.json`, guarded by an OS file lock on `db.lock`. Concurrent shells
  cannot corrupt the file.

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
      "p": "C:\\Users\\Ahri\\Documents",
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
$snip = & 'C:\Users\Ahri\Documents\Default Project\zam\bin\z.cmd' shell
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

  - `z` — list remembered directories
  - `z -` — return to the previous directory
  - `z <existing dir>` — `cd` straight into it (and it is recorded by the
    per-prompt hook)
  - `z <query>` — `cd` to the best match

- A per-prompt hook (`__zamHook`, chained through the `Prompt` function) that
  records every directory change. It cooperates cleanly with posh-git; if you
  use another prompt framework, call `__zamHook` from your own `Prompt`.
- `Tab` completion for `z` arguments, backed by the database.

### Why the hook needs a function and not just the batch file

A `.cmd` stub in `PATH` cannot affect the calling shell: `z.cmd` runs in a
child `cmd.exe`, and any `cd` it performs is discarded when it exits. zoxide
has exactly the same constraint on Windows and solves it the same way — a
profile function. If all you want is the *output* of a query from a script,
`z.cmd query <query>` prints the path to stdout and is perfectly usable
without the hook.

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
sbt "Test / runMain zam.TestMain"  # dependency-free test runner (52 checks)
```

Source layout (`.scala` files under `src/main/scala/zam/`):

| Component       | File             | Purpose                                                                |
|-----------------|------------------|------------------------------------------------------------------------|
| `Model` / `Score` | `Model.scala`   | Entry/DB types, power-law frecency, zoxide-model comparison, human ages. |
| `Matcher`       | `Matcher.scala` | zoxide-faithful fuzzy matching (case-fold, ordered terms, last-component). |
| `Store`         | `Store.scala`   | JSON persistence, file locking, atomic writes, cap/prune/remove logic.  |
| `Cli`           | `Cli.scala`     | Argument parsing and all subcommands, including the bare-invocation logic. |
| `Shell`         | `Shell.scala`   | fzf picker and the PowerShell integration snippet.                     |
| `Json`          | `Json.scala`    | Dependency-free JSON reader/writer.                                    |

The scoring code has no external dependencies — it is pure Scala — so it
transports unchanged to Scala Native or GraalVM for a native binary.

## Command quick reference

```console
zam add        <path>...       record visits            (--no-verify)
zam query      [query]         print best match         (-l -s -a -i --max-results=N --exclude=P1,P2)
zam list                       list remembered dirs
zam remove     <path>...       forget paths
zam prune                      prune gone dirs          (--max-age=DAYS, default 90; --dry-run)
zam explain    <query>         show scoring breakdown
zam stats                      database statistics
zam config     [k=v ...]       alpha / tau / max_visits  (config reset)
zam shell                      print PowerShell integration
zam import     <file>          add dirs from a history/log file
zam export     [<file>]        write remembered paths
zam demo                       comparison vs zoxide
zam version                    print version
```
