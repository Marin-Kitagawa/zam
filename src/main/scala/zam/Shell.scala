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
      |$global:ZAM_CMD = $null
      |foreach ($c in @('z.cmd', 'zam.cmd', 'zam')) {
      |  $global:ZAM_CMD = Get-Command $c -CommandType Application -ErrorAction SilentlyContinue
      |  if ($global:ZAM_CMD) { break }
      |}
      |if ($global:ZAM_CMD) {
      |  $global:ZAM_CMD = $global:ZAM_CMD.Source
      |
      |  # Record every directory change (runs once per prompt).
      |  function global:__zamHook {
      |    $now = (Get-Location).Path
      |    if ($global:ZAM_LAST_DIR -ne $now) {
      |      $global:ZAM_PREV = $global:ZAM_LAST_DIR
      |      $global:ZAM_LAST_DIR = $now
      |      $null = & $global:ZAM_CMD add $now 2>$null
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
      |  #   z -l           list remembered dirs
      |  #   z -i           open the interactive picker (fuzzy / regex / id)
      |  #   z -            back to previous dir
      |  #   z <existing>   cd straight into it (and it will be recorded by
      |  #                  __zamHook on the next prompt)
      |  #   z <query>      cd to the best match
      |  function global:z {
      |    if ($args.Count -eq 0) {
      |      Set-Location -LiteralPath $HOME
      |    } elseif ($args[0] -eq '-l' -or $args[0] -eq '--list') {
      |      & $global:ZAM_CMD query --list @($args | Select-Object -Skip 1)
      |    } elseif ($args[0] -eq '-') {
      |      if ($global:ZAM_PREV -and (Test-Path -LiteralPath $global:ZAM_PREV)) {
      |        Set-Location -LiteralPath $global:ZAM_PREV
      |      } else {
      |        Write-Host "zam: no previous directory" -ForegroundColor Red
      |      }
      |    } elseif ($args[0] -eq '-i' -or $args[0] -eq '--interactive') {
      |      $rest = @()
      |      if ($args.Count -gt 1) { $rest = $args[1..($args.Count - 1)] }
      |      $target = & $global:ZAM_CMD query -i @rest 2>$null
      |      if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrEmpty($target)) {
      |        Set-Location -LiteralPath $target
      |      }
      |      # cancelled / no match: the picker already said so on the console
      |    } elseif ($args.Count -eq 1 -and (Test-Path -LiteralPath $args[0] -PathType Container)) {
      |      Set-Location -LiteralPath $args[0]
      |    } else {
      |      $target = & $global:ZAM_CMD query @args 2>$null
      |      if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrEmpty($target)) {
      |        Set-Location -LiteralPath $target
      |      } else {
      |        Write-Host "zam: no match for '$($args -join ' ')'" -ForegroundColor Red
      |      }
      |    }
      |  }
      |
      |  # Tab-complete z arguments from the database.
      |  Register-ArgumentCompleter -CommandName z -ScriptBlock {
      |    param($commandName, $parameterName, $wordToComplete, $commandAst, $fakeBoundParameters)
      |    if ($wordToComplete) { & $global:ZAM_CMD query --list $wordToComplete } else { & $global:ZAM_CMD query --list }
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
      |#   z <existing>   cd there       z <q>   cd to the best match
      |z() {
      |  case "$$1" in
      |    -l|--list)   shift; __zam query --list "$$@" ;;
      |    -s|--score)  shift; __zam query -s "$$@" ;;
      |    -) if [ -n "$$__ZAM_PREV" ] && [ -d "$$__ZAM_PREV" ]; then cd "$$__ZAM_PREV"
      |       else echo "zam: no previous directory" >&2; return 1; fi ;;
      |    *) local target
      |       if [ $$# -eq 1 ] && [ -d "$$1" ]; then cd "$$1"; return 0; fi
      |       target=$$(__zam query "$$@") || { echo "zam: no match for '$$*'" >&2; return 1; }
      |       cd "$$target" ;;
      |  esac
      |}
      |
      |# Tab-complete z arguments from the database (paths with spaces do not
      |# complete verbatim; type a broader prefix instead).
      |__zam_complete() {
      |  local cur="$${COMP_WORDS[COMP_CWORD]}" line
      |  COMPREPLY=()
      |  while IFS= read -r line; do
      |    [ -n "$$line" ] && COMPREPLY+=("$$line")
      |  done < <(__zam query --list "$$cur" 2>/dev/null)
      |}
      |complete -F __zam_complete z
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
      |# zoxide-compatible jump:  z  z -l  z -s  z -  z <existing>  z <query>
      |z() {
      |  case "$$1" in
      |    -l|--list)  shift; __zam query --list "$$@" ;;
      |    -s|--score) shift; __zam query -s "$$@" ;;
      |    -) if [[ -n $$__ZAM_PREV && -d $$__ZAM_PREV ]]; then cd $$__ZAM_PREV
      |       else echo "zam: no previous directory" >&2; return 1; fi ;;
      |    *) local target
      |       if [[ $$# -eq 1 && -d $$1 ]]; then cd $$1; return 0; fi
      |       target=$$(__zam query "$$@") || { echo "zam: no match for '$$*'" >&2; return 1 }
      |       cd $$target ;;
      |  esac
      |}
      |
      |# Tab-complete z arguments from the database.
      |__zam_z_complete() {
      |  local -a dirs
      |  dirs=($${(f)"$$(__zam query --list $${(Q)words[CURRENT]})"})
      |  _describe -t zam-dirs 'remembered directory' dirs
      |}
      |compdef __zam_z_complete z
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
      |# zoxide-compatible jump:  z  z -l  z -s  z -  z <existing>  z <query>
      |function z --description "jump to a remembered directory"
      |    switch "$$argv[1]"
      |        case -l --list
      |            __zam query --list $$argv[2..-1]
      |        case -s --score
      |            __zam query -s $$argv[2..-1]
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
      |# --- end zam shell integration ---""".stripMargin
