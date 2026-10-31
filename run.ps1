param(
    [switch]$Headless,
    [switch]$Demo,
    [ValidatePattern('^[A-Za-z0-9_.-]+$')][string]$Federation = 'TankSimulation'
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
& (Join-Path $PSScriptRoot 'build.ps1')
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java).Source }
$jar = if ($env:RTI_HOME) { Join-Path $env:RTI_HOME 'lib\portico.jar' } else {
    'C:\Program Files\Portico\portico-2.1.4\lib\portico.jar'
}
$cp = (Join-Path $PSScriptRoot 'build') + ';' + $jar
$sessionLogDir = Join-Path $PSScriptRoot ('logs\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
New-Item -ItemType Directory -Path $sessionLogDir -Force | Out-Null

function Start-Federate([string]$className, [string[]]$extraArgs) {
    $arguments = @('-Djava.net.preferIPv4Stack=true', '-Dfile.encoding=UTF-8', "-Dtanksim.federation=$Federation")
    if ($className -eq 'DisplayFederate') {
        # Display yeniden başlatma isteğinde 10 ile çıkar. Süreçleri yeniden
        # kurabilecek bu betik olmadan pencere restart düğmesini etkinleştirmez.
        $arguments += '-Dtanksim.managedRestart=true'
    }
    $arguments += @('-cp', ('"' + $cp + '"'), $className) + $extraArgs
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $PSScriptRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDir "$className.out.log") `
        -RedirectStandardError (Join-Path $logDir "$className.err.log")
    $processes.Add($process)
    $processNames[$process.Id] = $className
    # Windows PowerShell 5.1, redirect + PassThru kullanıldığında süreç bittikten
    # sonra ExitCode'u null döndürebiliyor. Handle'ı süreç yaşarken önbelleğe al;
    # yalnız kapanışta WaitForExit() çağırmak bu sorunu çözmüyor.
    $null = $process.Handle
    return $process
}

function Wait-FederateJoined([System.Diagnostics.Process]$process, [string]$className) {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 100
        if ($process.HasExited) { throw "$className başlatılamadı. Günlükler: $logDir" }
        $ready = (Get-Content -LiteralPath (Join-Path $logDir "$className.out.log") -Raw) -match 'RTI_JOINED'
        if ((Get-Date) -gt $deadline) { throw "$className RTI başlangıç süresi aşıldı. Günlükler: $logDir" }
    } until ($ready)
}

$round = 0
$restartRequested = $false
do {
    $round++
    $logDir = Join-Path $sessionLogDir ('round-{0:D3}' -f $round)
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    $processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
    $processNames = @{}
    $restartRequested = $false

    try {
        $tankProcess = Start-Federate 'TankFederate' @()
        # İlk süreç federasyonu kursun; dört eşzamanlı Portico create girişimini önler.
        Wait-FederateJoined $tankProcess 'TankFederate'
        $predictionProcess = Start-Federate 'PredictionFederate' @()
        Wait-FederateJoined $predictionProcess 'PredictionFederate'
        $collisionProcess = Start-Federate 'CollisionDetectionFederate' @()
        Wait-FederateJoined $collisionProcess 'CollisionDetectionFederate'
        $displayArgs = @()
        if ($Headless) { $displayArgs += '--headless' }
        if ($Demo) { $displayArgs += '--demo' }
        $displayProcess = Start-Federate 'DisplayFederate' $displayArgs
        Write-Host "Koşu $round başladı. Dört federe için günlükler: $logDir"
        if (-not $Headless) {
            Write-Host 'Oyun bitince Yeniden başlat yeni bir koşu açar; pencereyi kapatınca betik de tamamlanır.'
        }

        # Son sahne açıkken Display kullanıcıyı bekler. Pencere kapandıktan veya
        # restart istendikten sonra takılmış bir eski JVM yeni koşuyu engellemesin.
        $shutdownDeadline = $null
        while ($true) {
            $alive = @($processes | Where-Object { -not $_.HasExited })
            if ($alive.Count -eq 0) { break }
            if ($displayProcess.HasExited) {
                if ($null -eq $shutdownDeadline) {
                    $shutdownDeadline = [DateTime]::UtcNow.AddSeconds(10)
                }
                if ([DateTime]::UtcNow -ge $shutdownDeadline) {
                    foreach ($process in $alive) {
                        if (-not $process.HasExited) {
                            Write-Warning "$($processNames[$process.Id]) kapanış süresini aştı; bu koşunun süreci sonlandırılıyor. Günlükler: $logDir"
                            try { $process.Kill() }
                            catch { if (-not $process.HasExited) { throw } }
                        }
                    }
                    break
                }
            }
            Start-Sleep -Milliseconds 200
        }

        $exitResults = foreach ($process in $processes) {
            if (-not $process.WaitForExit(5000)) {
                throw "$($processNames[$process.Id]) sonlandırılamadı; yeni koşu başlatılmayacak. Günlükler: $logDir"
            }
            # Yönlendirilmiş stdout/stderr de tamamen boşalsın.
            $process.WaitForExit()
            [pscustomobject]@{
                Federate = $processNames[$process.Id]
                ProcessId = $process.Id
                ExitCode = $process.ExitCode
            }
        }
        $exitResults | Export-Csv -LiteralPath (Join-Path $logDir 'process-exits.csv') -NoTypeInformation -Encoding UTF8
        $displayExit = $exitResults | Where-Object { $_.Federate -eq 'DisplayFederate' }
        $restartRequested = -not $Headless -and $null -ne $displayExit.ExitCode -and $displayExit.ExitCode -eq 10

        # Yalnız Display'in 10 kodu restart sinyalidir. Diğer hatalar logda kalır;
        # kullanıcı açıkça restart istediyse temiz JVM'lerle yeniden denenebilir.
        # null'ı int'e çevirmek 0 üretir; bilinmeyen sonucu başarı saymıyoruz.
        $unknown = @($exitResults | Where-Object { $null -eq $_.ExitCode })
        if ($unknown.Count -gt 0) {
            $message = "Çıkış kodu okunamadı: $($unknown.Federate -join ', '). Günlükler: $logDir"
            if ($restartRequested) { Write-Warning $message } else { throw $message }
        }
        $bad = @($exitResults | Where-Object {
            $null -ne $_.ExitCode -and $_.ExitCode -ne 0 -and
                -not ($restartRequested -and $_.Federate -eq 'DisplayFederate' -and $_.ExitCode -eq 10)
        })
        if ($bad.Count -gt 0) {
            $details = ($bad | ForEach-Object { "$($_.Federate) (kod=$($_.ExitCode))" }) -join ', '
            $message = "Federe hata verdi: $details. Günlükler: $logDir"
            if ($restartRequested) { Write-Warning $message } else { throw $message }
        }
        if (-not $restartRequested) {
            Write-Host 'Dört federe de başarıyla kapandı (çıkış kodu 0).'
        }
    } finally {
        # Başlangıç hatasında da çalışır. Sadece bu koşunun süreçlerine dokunulur;
        # sonraki koşuya geçmeden hepsinin gerçekten sonlandığı doğrulanır.
        $cleanupErrors = [System.Collections.Generic.List[string]]::new()
        $cleanupExitResults = [System.Collections.Generic.List[object]]::new()
        foreach ($process in $processes) {
            try {
                if (-not $process.HasExited) {
                    try { $process.Kill() }
                    catch { if (-not $process.HasExited) { throw } }
                }
                if (-not $process.WaitForExit(5000)) {
                    throw "$($processNames[$process.Id]) süreci kapanmadı."
                }
                $process.WaitForExit()
                $cleanupExitResults.Add([pscustomobject]@{
                    Federate = $processNames[$process.Id]
                    ProcessId = $process.Id
                    ExitCode = $process.ExitCode
                })
            } catch {
                $cleanupErrors.Add($_.Exception.Message)
            } finally {
                $process.Dispose()
            }
        }
        # Başlangıç yarıda kesilse bile başlatılabilmiş süreçlerin sonucu saklanır.
        if ($cleanupExitResults.Count -gt 0) {
            $cleanupExitResults | Export-Csv -LiteralPath (Join-Path $logDir 'process-exits.csv') -NoTypeInformation -Encoding UTF8
        }
        if ($cleanupErrors.Count -gt 0) {
            throw "Eski koşu temizlenemedi; yeni koşu başlatılmayacak: $($cleanupErrors -join ' ') Günlükler: $logDir"
        }
    }

    if ($restartRequested) {
        Write-Host 'Yeniden başlatılıyor: dört yeni federe, başlangıç konumları ve sıfır simülasyon zamanı.'
    }
} while ($restartRequested)
