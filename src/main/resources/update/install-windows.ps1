param(
    [Parameter(Mandatory=$true)][int]$ApplicationProcessId,
    [Parameter(Mandatory=$true)][string]$InstallerPath,
    [Parameter(Mandatory=$true)][string]$ApplicationPath,
    [Parameter(Mandatory=$true)][string]$LogPath
)
$ErrorActionPreference = 'Stop'
try {
    # Release all application files before Windows Installer replaces them.
    $application = Get-Process -Id $ApplicationProcessId -ErrorAction SilentlyContinue
    if ($null -ne $application) { $application.WaitForExit() }
    if (-not (Test-Path -LiteralPath $InstallerPath -PathType Leaf)) { throw 'Installer is missing.' }
    $arguments = '/i "' + $InstallerPath + '" /passive /norestart /l*v "' + $LogPath + '"'
    $installer = Start-Process -FilePath 'msiexec.exe' -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
    if ($installer.ExitCode -ne 0 -and $installer.ExitCode -ne 3010) {
        throw ('Windows Installer failed with code ' + $installer.ExitCode + '. See ' + $LogPath)
    }
    if (-not (Test-Path -LiteralPath $ApplicationPath -PathType Leaf)) { throw 'Updated application executable is missing.' }
    Start-Process -FilePath $ApplicationPath -WorkingDirectory (Split-Path -LiteralPath $ApplicationPath) -WindowStyle Hidden
} catch {
    $_.Exception.Message | Set-Content -LiteralPath ($LogPath + '.error.txt') -Encoding UTF8
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'KittyTune update') | Out-Null
    exit 1
}
