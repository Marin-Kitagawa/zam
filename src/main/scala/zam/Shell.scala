package zam

/** Shell integration snippets: the launcher hook and `z` function for each
  * supported shell (`zam shell <name>` prints one). */
object Shell:

  private val Resolution =
    """__zam() {
      |  if command -v zam >/dev/null 2>&1; then zam "$@"
      |  elif [ -n "$ZAM_JAR" ] && [ -f "$ZAM_JAR" ]; then
      |    java --enable-native-access=ALL-UNNAMED -jar "$ZAM_JAR" "$@"
      |  else
      |    echo "zam: no launcher found (put zam on PATH or set ZAM_JAR)" >&2
      |    return 127
      |  fi
      |}""".stripMargin

  def powershellSnippet: String =
    """# --- zam shell integration (paste into $PROFILE) ---
      |# zam writes UTF-8; decode it correctly (paths may contain non-ASCII)
      |try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch {}
      |
      |# Resolve the launcher once per session. Prefer the plain forwarder
      |# (zam.cmd / zam); fall back to running the jar directly via ZAM_JAR.
      |$global:ZAM_EXE = $null
      |$global:ZAM_ARGV = @()
      |foreach ($c in @('zam.cmd', 'zam', 'z.cmd')) {
      |  $found = Get-Command $c -CommandType Application -ErrorAction SilentlyContinue |
      |    Select-Object -First 1
      |  if ($found) { $global:ZAM_EXE = $found.Source; break }
      |}
      |if (-not $global:ZAM_EXE -and $env:ZAM_JAR -and (Test-Path -LiteralPath $env:ZAM_JAR)) {
      |  $global:ZAM_EXE = 'java'
      |  $global:ZAM_ARGV = @('--enable-native-access=ALL-UNNAMED', '-jar', $env:ZAM_JAR)
      |}
      |if ($global:ZAM_EXE) {
      |
      |  # Every call goes through the same launcher (works for zam.cmd, zam
      |  # or a java -jar fallback).
      |  function global:__zam {
      |    $launch = $global:ZAM_ARGV
      |    & $global:ZAM_EXE @launch @args
      |  }
      |
      |  # Record every directory change (runs once per prompt).
      |  function global:__zamHook {
      |    $now = (Get-Location).ProviderPath
      |    if ($global:ZAM_LAST_DIR -ne $now) {
      |      $global:ZAM_PREV = $global:ZAM_LAST_DIR
      |      $global:ZAM_LAST_DIR = $now
      |      $null = __zam add $now 2>$null
      |    }
      |  }
      |
      |  # Overriding Prompt also happens to work with posh-git, but if you use
      |  # another prompt framework, call `__zamHook` from your own Prompt too.
      |  function global:Prompt {
      |    __zamHook
      |    "$($executionContext.SessionState.Path.CurrentLocation)$('>' * ($nestedPromptLevel + 1)) "
      |  }
      |
      |  # zoxide-compatible jump:
      |  #   z              cd to your home directory (~)
      |  #   z -l           list remembered dirs         z -s   list with scores
      |  #   z -i           interactive picker (fuzzy / regex / id)
      |  #   z -            back to previous dir
      |  #   z <existing>   cd straight into it (and it will be recorded by
      |  #                  __zamHook on the next prompt)
      |  #   z <query>      cd to the best match
      |  function global:z {
      |    if ($args.Count -eq 0) {
      |      Set-Location -LiteralPath $HOME
      |    } elseif ($args[0] -eq '-l' -or $args[0] -eq '--list') {
      |      __zam query --list @($args | Select-Object -Skip 1)
      |    } elseif ($args[0] -eq '-s' -or $args[0] -eq '--score') {
      |      __zam query -s @($args | Select-Object -Skip 1)
      |    } elseif ($args[0] -eq '-') {
      |      if ($global:ZAM_PREV -and (Test-Path -LiteralPath $global:ZAM_PREV)) {
      |        Set-Location -LiteralPath $global:ZAM_PREV
      |      } else {
      |        Write-Host "zam: no previous directory" -ForegroundColor Red
      |      }
      |    } elseif ($args[0] -eq '-i' -or $args[0] -eq '--interactive') {
      |      $rest = @()
      |      if ($args.Count -gt 1) { $rest = $args[1..($args.Count - 1)] }
      |      $target = __zam query -i @rest 2>$null
      |      if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrEmpty($target)) {
      |        Set-Location -LiteralPath $target
      |      }
      |      # cancelled / no match: the picker already said so on the console
      |    } elseif ($args.Count -eq 1 -and (Test-Path -LiteralPath $args[0] -PathType Container)) {
      |      Set-Location -LiteralPath $args[0]
      |    } else {
      |      $target = __zam query @args 2>$null
      |      if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrEmpty($target)) {
      |        Set-Location -LiteralPath $target
      |      } else {
      |        Write-Host "zam: no match for '$($args -join ' ')'" -ForegroundColor Red
      |      }
      |    }
      |  }
      |
      |  # Candidates for the word being completed. `zam complete` looks at the
      |  # real filesystem first (children of the current directory) and only
      |  # then at history, so stale entries never crowd out what is right here.
      |  function global:__zamCandidates {
      |    param([string] $Word)
      |    # PowerShell's process cwd is NOT the session location, so pass it in.
      |    $here = (Get-Location).ProviderPath
      |    $raw = __zam complete --cwd $here --json -- $Word 2>$null
      |    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($raw)) { return @() }
      |    try { return @($raw | ConvertFrom-Json) } catch { return @() }
      |  }
      |
      |  function global:__zamResults {
      |    param([string] $Word)
      |    foreach ($c in (__zamCandidates $Word)) {
      |      $text = [string]$c.value
      |      if ($text -match '[\s''"]') { $text = "'" + ($text -replace "'", "''") + "'" }
      |      $kind =
      |        if ($c.source -eq 'flag') {
      |          [System.Management.Automation.CompletionResultType]::ParameterName
      |        } else {
      |          [System.Management.Automation.CompletionResultType]::ParameterValue
      |        }
      |      [System.Management.Automation.CompletionResult]::new(
      |        $text, [string]$c.display, $kind, [string]$c.detail)
      |    }
      |  }
      |
      |  # PowerShell passes an argument completer one of two signatures:
      |  #   Windows PowerShell 5.1 / -ParameterName
      |  #     (commandName, parameterName, wordToComplete, commandAst, fakeBoundParameters)
      |  #   PowerShell 6+ / -CommandName (treated as a native completer)
      |  #     (wordToComplete, commandAst, cursorPosition)
      |  # A plain -CommandName registration uses the second one even for a
      |  # function, so reading position 2 blindly makes the "word" the cursor
      |  # column. Detect the shape instead and one snippet works everywhere.
      |  function global:__zamCompletionContext {
      |    param($AllArgs)
      |    if ($AllArgs.Count -ge 2 -and
      |        $AllArgs[1] -is [System.Management.Automation.Language.CommandAst]) {
      |      [pscustomobject]@{ Word = [string]$AllArgs[0]; Ast = $AllArgs[1] }
      |    } elseif ($AllArgs.Count -ge 4) {
      |      [pscustomobject]@{ Word = [string]$AllArgs[2]; Ast = $AllArgs[3] }
      |    } else {
      |      [pscustomobject]@{ Word = ''; Ast = $null }
      |    }
      |  }
      |
      |  # Tab-complete z arguments from the filesystem + the database.
      |  Register-ArgumentCompleter -CommandName z -ScriptBlock {
      |    $ctx = __zamCompletionContext $args
      |    __zamResults $ctx.Word
      |  }
      |
      |  # Tab-complete zam subcommands, then arguments.
      |  $global:ZAM_COMMANDS = @(
      |    'add', 'query', 'list', 'recent', 'complete', 'remove', 'move', 'alias',
      |    'prune', 'explain', 'stats', 'doctor', 'config', 'shell', 'import',
      |    'export', 'demo', 'version', 'help'
      |  )
      |  Register-ArgumentCompleter -CommandName zam -ScriptBlock {
      |    $ctx = __zamCompletionContext $args
      |    $word = $ctx.Word
      |    $els = @()
      |    if ($ctx.Ast) { $els = @($ctx.Ast.CommandElements | ForEach-Object { $_.ToString() }) }
      |    $typingSubcommand =
      |      $els.Count -le 1 -or ($els.Count -eq 2 -and $els[1] -eq $word)
      |    if ($typingSubcommand) {
      |      foreach ($cmd in $global:ZAM_COMMANDS) {
      |        if ($cmd.StartsWith($word, [StringComparison]::OrdinalIgnoreCase)) {
      |          [System.Management.Automation.CompletionResult]::new(
      |            $cmd, $cmd,
      |            [System.Management.Automation.CompletionResultType]::ParameterValue,
      |            "zam $cmd")
      |        }
      |      }
      |    } else {
      |      __zamResults $word
      |    }
      |  }
      |}
      |# --- end zam shell integration ---""".stripMargin

  def bashSnippet: String =
    s"""# --- zam shell integration (bash, paste into ~/.bashrc) ---
      |$Resolution
      |
      |# Record every directory change (runs before each prompt).
      |__zam_hook() {
      |  local now="$$PWD"
      |  if [ "$$__ZAM_LAST" != "$$now" ]; then
      |    __ZAM_PREV="$$__ZAM_LAST"
      |    __ZAM_LAST="$$now"
      |    __zam add "$$now" >/dev/null 2>&1 || true
      |  fi
      |}
      |PROMPT_COMMAND="__zam_hook$${PROMPT_COMMAND:+;$$PROMPT_COMMAND}"
      |
      |# zoxide-compatible jump:
      |#   z              cd to ~        z -l    list remembered dirs
      |#   z -            previous dir   z -s    list with scores
      |#   z -i           picker         z <q>   cd to the best match
      |z() {
      |  case "$$1" in
      |    -l|--list)   shift; __zam query --list "$$@" ;;
      |    -s|--score)  shift; __zam query -s "$$@" ;;
      |    -i|--interactive) shift; local target
      |       target=$$(__zam query -i "$$@") || return 1
      |       [ -n "$$target" ] && cd "$$target" ;;
      |    -) if [ -n "$$__ZAM_PREV" ] && [ -d "$$__ZAM_PREV" ]; then cd "$$__ZAM_PREV"
      |       else echo "zam: no previous directory" >&2; return 1; fi ;;
      |    *) local target
      |       if [ $$# -eq 1 ] && [ -d "$$1" ]; then cd "$$1"; return 0; fi
      |       target=$$(__zam query "$$@") || { echo "zam: no match for '$$*'" >&2; return 1; }
      |       cd "$$target" ;;
      |  esac
      |}
      |
      |# Tab-complete z arguments: directories here first, then history.
      |__zam_complete() {
      |  local cur="$${COMP_WORDS[COMP_CWORD]}"
      |  COMPREPLY=()
      |  mapfile -t COMPREPLY < <(__zam complete -- "$$cur" 2>/dev/null)
      |  compopt -o filenames 2>/dev/null || true
      |}
      |complete -F __zam_complete z
      |
      |# Completing `zam` itself: subcommands first, then arguments.
      |__zam_cmd_complete() {
      |  local cur="$${COMP_WORDS[COMP_CWORD]}"
      |  if [ "$$COMP_CWORD" -eq 1 ]; then
      |    COMPREPLY=()
      |    mapfile -t COMPREPLY < <(compgen -W "add query list recent complete remove move \\
      |      alias prune explain stats doctor config shell import export demo version help" -- "$$cur")
      |  else
      |    __zam_complete
      |  fi
      |}
      |complete -F __zam_cmd_complete zam
      |# --- end zam shell integration ---""".stripMargin

  def zshSnippet: String =
    s"""# --- zam shell integration (zsh, paste into ~/.zshrc after compinit) ---
      |$Resolution
      |
      |# Record every directory change, the zsh-native way.
      |autoload -Uz add-zsh-hook
      |__zam_hook() {
      |  local now=$$PWD
      |  if [[ $$__ZAM_LAST != $$now ]]; then
      |    __ZAM_PREV=$$__ZAM_LAST
      |    __ZAM_LAST=$$now
      |    __zam add "$$now" >/dev/null 2>&1 || true
      |  fi
      |}
      |add-zsh-hook precmd __zam_hook
      |
      |# zoxide-compatible jump:  z  z -l  z -s  z -i  z -  z <existing>  z <query>
      |z() {
      |  case "$$1" in
      |    -l|--list)  shift; __zam query --list "$$@" ;;
      |    -s|--score) shift; __zam query -s "$$@" ;;
      |    -i|--interactive) shift; local target
      |       target=$$(__zam query -i "$$@") || return 1
      |       [[ -n $$target ]] && cd $$target ;;
      |    -) if [[ -n $$__ZAM_PREV && -d $$__ZAM_PREV ]]; then cd $$__ZAM_PREV
      |       else echo "zam: no previous directory" >&2; return 1; fi ;;
      |    *) local target
      |       if [[ $$# -eq 1 && -d $$1 ]]; then cd $$1; return 0; fi
      |       target=$$(__zam query "$$@") || { echo "zam: no match for '$$*'" >&2; return 1 }
      |       cd $$target ;;
      |  esac
      |}
      |
      |# Tab-complete z arguments: directories here first, then history.
      |__zam_z_complete() {
      |  local out
      |  out="$$(__zam complete -- "$${(Q)words[CURRENT]}" 2>/dev/null)"
      |  [[ -z $$out ]] && return 1
      |  local -a dirs
      |  dirs=("$${(@f)out}")
      |  _describe -t zam-dirs 'directory' dirs
      |}
      |compdef __zam_z_complete z
      |
      |# Completing `zam` itself: subcommands first, then arguments.
      |__zam_cmd_complete() {
      |  if (( CURRENT == 2 )); then
      |    local -a cmds
      |    cmds=(add query list recent complete remove move alias prune explain
      |          stats doctor config shell import export demo version help)
      |    _describe -t zam-commands 'zam command' cmds
      |  else
      |    __zam_z_complete
      |  fi
      |}
      |compdef __zam_cmd_complete zam
      |# --- end zam shell integration ---""".stripMargin

  def fishSnippet: String =
    s"""# --- zam shell integration (fish) ---
      |# Save as ~/.config/fish/functions/z.fish, or paste into config.fish.
      |function __zam
      |    if type -q zam
      |        zam $$argv
      |    else if set -q ZAM_JAR; and test -f "$$ZAM_JAR"
      |        java --enable-native-access=ALL-UNNAMED -jar "$$ZAM_JAR" $$argv
      |    else
      |        echo "zam: no launcher found (put zam on PATH or set ZAM_JAR)" >&2
      |        return 127
      |    end
      |end
      |
      |# Record every directory change (fires the moment PWD changes).
      |function __zam_hook --on-variable PWD
      |    if test "$$__zam_last" != "$$PWD"
      |        set -g __zam_prev $$__zam_last
      |        set -g __zam_last $$PWD
      |        __zam add "$$PWD" >/dev/null 2>&1
      |    end
      |end
      |
      |# zoxide-compatible jump:  z  z -l  z -s  z -i  z -  z <existing>  z <query>
      |function z --description "jump to a remembered directory"
      |    switch "$$argv[1]"
      |        case -l --list
      |            __zam query --list $$argv[2..-1]
      |        case -s --score
      |            __zam query -s $$argv[2..-1]
      |        case -i --interactive
      |            set -l target (__zam query -i $$argv[2..-1])
      |            if test $$status -eq 0; and test -n "$$target"
      |                cd "$$target"
      |            end
      |        case -
      |            if test -n "$$__zam_prev"; and test -d "$$__zam_prev"
      |                cd "$$__zam_prev"
      |            else
      |                echo "zam: no previous directory" >&2
      |                return 1
      |            end
      |        case '*'
      |            set -l target (__zam query $$argv)
      |            if test $$status -eq 0; and test -n "$$target"
      |                cd "$$target"
      |            else
      |                echo "zam: no match for '$$argv'" >&2
      |                return 1
      |            end
      |    end
      |end
      |
      |# Tab-complete z arguments: directories here first, then history.
      |complete -c z -f -a '(__zam complete -- (commandline -ct) 2>/dev/null)'
      |complete -c zam -f -n '__fish_use_subcommand' -a 'add query list recent complete remove move alias prune explain stats doctor config shell import export demo version help'
      |# --- end zam shell integration ---""".stripMargin
