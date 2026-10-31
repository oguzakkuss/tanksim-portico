param([switch]$Integration, [switch]$Restart)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1') -Test
if ($Integration -or $Restart) {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java).Source }
}
if ($Integration) {
    $jar = if ($env:RTI_HOME) { Join-Path $env:RTI_HOME 'lib\portico.jar' } else {
        'C:\Program Files\Portico\portico-2.1.4\lib\portico.jar'
    }
    & $java -cp "build;$jar" IntegrationTests
    if ($LASTEXITCODE -ne 0) { throw 'RTI entegrasyon testi başarısız.' }
    & $java -cp "build;$jar" LateJoinTest
    if ($LASTEXITCODE -ne 0) { throw 'Geç katılım testi başarısız.' }
}
if ($Restart) {
    # Test agent'ı gerçek düğmeyi EDT'de çalıştırır; iki oyun ve süreç temizliği sınanır.
    & $java -cp build RestartTests
    if ($LASTEXITCODE -ne 0) { throw 'Yeniden başlatma testi başarısız.' }
}
