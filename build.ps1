param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# JAVA_HOME ve RTI_HOME ayarlanmışsa onları kullan; bu makinenin kurulumunu yedek al.
$jdkBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { '' }
$javac = if ($jdkBin) { Join-Path $jdkBin 'javac.exe' } else { (Get-Command javac).Source }
$java = if ($jdkBin) { Join-Path $jdkBin 'java.exe' } else { (Get-Command java).Source }
$porticoJar = if ($env:RTI_HOME) { Join-Path $env:RTI_HOME 'lib\portico.jar' } else {
    'C:\Program Files\Portico\portico-2.1.4\lib\portico.jar'
}
if (-not (Test-Path -LiteralPath $porticoJar)) { throw "Portico bulunamadı: $porticoJar" }
New-Item -ItemType Directory -Path build -Force | Out-Null
$sources = (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.java').FullName
& $javac -encoding UTF-8 --release 11 -cp $porticoJar -d build $sources
if ($LASTEXITCODE -ne 0) { throw 'Java derlemesi başarısız.' }
Write-Host 'Derleme tamamlandı: build/'
if ($Test) {
    foreach ($testClass in @('PhysicsTests', 'ProtocolTests', 'RuntimeDataTests')) {
        & $java -cp "build;$porticoJar" $testClass
        if ($LASTEXITCODE -ne 0) { throw "Test başarısız: $testClass" }
    }
}
