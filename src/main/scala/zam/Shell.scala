package zam

import java.nio.file.{Files, Paths}

/** Shell integration pieces: interactive picker and PowerShell profile hook. */
object Shell:

  def fzfOnPath: Boolean =
    val path = sys.env.getOrElse("PATH", "")
    val exts = if Store.isWindows then Seq("", ".exe", ".bat", ".cmd") else Seq("")
    path.split(java.io.File.pathSeparator).exists { d =>
      if d.isEmpty then false
      else
        val dir = Paths.get(d)
        exts.exists(ext => Files.isRegularFile(dir.resolve("fzf" + ext)))
    }

  /** Feed candidates to fzf; returns the selected path, or None (fall back to
    * printing the ranked list). */
  def pick(candidates: Vector[String]): Option[String] =
    if candidates.isEmpty || !fzfOnPath then None
    else
      try
        val pb = new ProcessBuilder("fzf", "--height=40%", "--reverse", "--border")
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        val proc = pb.start()
        val writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(proc.getOutputStream, java.nio.charset.StandardCharsets.UTF_8))
        candidates.foreach { c =>
          writer.write(c); writer.newLine()
        }
        writer.close()
        val out = new String(proc.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
        if proc.waitFor() == 0 && out.nonEmpty then Some(out) else None
      catch case _: Exception => None

  /** PowerShell integration: a per-prompt hook that records directory changes
    * plus a `z` function. Save this output to your `$PROFILE`.
    *
    * The launcher is discovered by name (`z.cmd`, `zam.cmd`, or `zam`) so this
    * works regardless of what you renamed the wrapper to. Note that the actual
    * directory change MUST happen here in PowerShell — a .cmd/.bat child
    * process can never change the parent shell's working directory. */
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