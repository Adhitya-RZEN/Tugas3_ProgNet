[CmdletBinding()]
param(
    [ValidateSet('BYTEBOX', 'BYTEBOX-candidate', 'BYTEBOX-progress-candidate', 'BYTEBOX-progress-final')]
    [string]$ImageDirectoryName = 'BYTEBOX'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pomPath = Join-Path $projectRoot 'pom.xml'
$pom = [xml](Get-Content -LiteralPath $pomPath -Raw)
$version = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='version']").InnerText
$artifactId = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='artifactId']").InnerText
$javafxVersion = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='properties']/*[local-name()='javafx.version']").InnerText
$mainClass = 'com.bytebox.ByteboxApplication'
$javafxModules = @('javafx-base', 'javafx-graphics', 'javafx-controls', 'javafx-fxml')
$outputDirectory = Join-Path $projectRoot 'dist'
$imageDirectory = Join-Path $outputDirectory $ImageDirectoryName

if (-not $env:JAVA_HOME) {
    throw 'Set JAVA_HOME to a 64-bit JDK 21 before packaging BYTEBOX.'
}
$javaHome = (Resolve-Path $env:JAVA_HOME).Path
$java = Join-Path $javaHome 'bin\java.exe'
$jlink = Join-Path $javaHome 'bin\jlink.exe'
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
foreach ($tool in @($java, $jlink, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required JDK tool not found: $tool" }
}
if ((& $java -version 2>&1 | Out-String) -notmatch 'version "21(?:\.|\")') {
    throw "JDK 21 is required. JAVA_HOME currently points to: $javaHome"
}
if (Test-Path -LiteralPath $imageDirectory) {
    throw "Refusing to replace an existing distribution. Move or rename it first: $imageDirectory"
}

$workDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "bytebox-package-$([guid]::NewGuid().ToString('N'))"
$dependencyDirectory = Join-Path $workDirectory 'dependencies'
$appInputDirectory = Join-Path $workDirectory 'input'
$runtimeDirectory = Join-Path $workDirectory 'runtime'
$packageOutputDirectory = Join-Path $workDirectory 'package-output'
New-Item -ItemType Directory -Path $dependencyDirectory, $appInputDirectory, $packageOutputDirectory | Out-Null

try {
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'mvnw.cmd') -B package
        if ($LASTEXITCODE -ne 0) { throw "Maven package failed with exit code $LASTEXITCODE" }

        & (Join-Path $projectRoot 'mvnw.cmd') -B 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies' `
            '-DincludeScope=runtime' "-DoutputDirectory=$dependencyDirectory"
        if ($LASTEXITCODE -ne 0) { throw "Runtime dependency collection failed with exit code $LASTEXITCODE" }
    }
    finally {
        Pop-Location
    }

    $javafxJars = foreach ($module in $javafxModules) {
        $jarName = "$module-$javafxVersion-win.jar"
        $jar = Join-Path $dependencyDirectory $jarName
        if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
            throw "Missing Windows JavaFX module $jarName. Build this image on Windows x64 with JavaFX $javafxVersion."
        }
        $jar
    }

    $modulePath = (@((Join-Path $javaHome 'jmods')) + $javafxJars) -join [System.IO.Path]::PathSeparator
    & $jlink '--module-path' $modulePath '--add-modules' 'javafx.controls,javafx.fxml,jdk.crypto.ec' `
        '--strip-debug' '--no-header-files' '--no-man-pages' '--compress=zip-6' '--output' $runtimeDirectory
    if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE" }

    $jarName = "$artifactId-$version.jar"
    $applicationJar = Join-Path $projectRoot "target\$jarName"
    if (-not (Test-Path -LiteralPath $applicationJar -PathType Leaf)) { throw "Maven output not found: $applicationJar" }
    Copy-Item -LiteralPath $applicationJar -Destination (Join-Path $appInputDirectory $jarName)
    & $jpackage '--type' 'app-image' '--name' 'BYTEBOX' '--app-version' $version '--dest' $packageOutputDirectory `
        '--input' $appInputDirectory '--main-jar' $jarName '--main-class' $mainClass `
        '--runtime-image' $runtimeDirectory '--java-options' '--add-modules=javafx.controls,javafx.fxml,jdk.crypto.ec'
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

    $stagedImage = Join-Path $packageOutputDirectory 'BYTEBOX'
    $stagedLauncher = Join-Path $stagedImage 'BYTEBOX.exe'
    $stagedLauncherConfig = Join-Path $stagedImage 'app\BYTEBOX.cfg'
    $stagedRuntimeJava = Join-Path $stagedImage 'runtime\bin\java.exe'
    if (-not (Test-Path -LiteralPath $stagedLauncher -PathType Leaf)) { throw "jpackage did not create $stagedLauncher" }
    if (-not (Test-Path -LiteralPath $stagedRuntimeJava -PathType Leaf)) {
        throw 'The application image does not contain its Java runtime.'
    }
    $configText = Get-Content -LiteralPath $stagedLauncherConfig -Raw
    $launcherHasRequiredModules = $configText -match 'javafx\.controls,javafx\.fxml,jdk\.crypto\.ec'
    if ($configText -notmatch [regex]::Escape($mainClass) -or -not $launcherHasRequiredModules) {
        throw 'The generated launcher configuration is missing its JavaFX entry point or modules.'
    }
    $moduleInventory = (& $stagedRuntimeJava --list-modules 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $moduleInventory -notmatch '(?m)^jdk\.crypto\.ec@') {
        throw 'The application runtime is missing the required jdk.crypto.ec TLS provider module.'
    }

    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    Move-Item -LiteralPath $stagedImage -Destination $imageDirectory

    $launcher = Join-Path $imageDirectory 'BYTEBOX.exe'
    $launcherConfig = Join-Path $imageDirectory 'app\BYTEBOX.cfg'

    $imageBytes = (Get-ChildItem -LiteralPath $imageDirectory -Recurse -File | Measure-Object -Property Length -Sum).Sum
    Write-Host "Created portable application image: $imageDirectory"
    Write-Host "Launcher: $launcher ($((Get-Item -LiteralPath $launcher).Length) bytes)"
    Write-Host "Distribution size: $imageBytes bytes"
}
finally {
    if (Test-Path -LiteralPath $workDirectory) {
        Remove-Item -LiteralPath $workDirectory -Recurse -Force
    }
}
