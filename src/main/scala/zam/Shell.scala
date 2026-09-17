package zam

/** Shell integration: the PowerShell profile hook and `z` function. */
object Shell:

  def powershellSnippet: String =
    """# --- zam shell integration (paste into $PROFILE) ---
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
      |      & $global:ZAM_CMD query --list
      |    } elseif ($args[0] -eq '-') {
      |      if ($global:ZAM_PREV -and (Test-Path -LiteralPath $global:ZAM_PREV)) {
      |        Set-Location -LiteralPath $global:ZAM_PREV
      |      } else {
      |        Write-Host "zam: no previous directory" -ForegroundColor Red
      |      }
      |    } elseif ($args.Count -eq 1 -and (Test-Path -LiteralPath $args[0] -PathType Container)) {
      |      Set-Location -LiteralPath $args[0]
      |    } else {
      |      $target = (& $global:ZAM_CMD query @args 2>$null)
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