# Use the project directory for Java's short-lived local socket files.
# Keep this workaround local to the build; restore the caller's environment.
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = ($previousJavaOptions + ' "-Djdk.net.unixdomain.tmpdir=' + $PSScriptRoot + '"').Trim()
    Push-Location -LiteralPath $PSScriptRoot
    try {
        if ($args.Count -eq 0) {
            & .\gradlew.bat :app:assembleDebug :app:lintDebug
        } else {
            & .\gradlew.bat @args
        }
        $buildExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}
exit $buildExitCode
