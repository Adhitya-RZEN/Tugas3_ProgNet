[CmdletBinding()]
param()

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
$imageDirectory = Join-Path $outputDirectory 'BYTEBOX'

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
New-Item -ItemType Directory -Path $dependencyDirectory, $appInputDirectory | Out-Null

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
    & $jlink '--module-path' $modulePath '--add-modules' 'javafx.controls,javafx.fxml' `
        '--strip-debug' '--no-header-files' '--no-man-pages' '--compress=zip-6' '--output' $runtimeDirectory
    if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE" }

    $jarName = "$artifactId-$version.jar"
    $applicationJar = Join-Path $projectRoot "target\$jarName"
    if (-not (Test-Path -LiteralPath $applicationJar -PathType Leaf)) { throw "Maven output not found: $applicationJar" }
    Copy-Item -LiteralPath $applicationJar -Destination (Join-Path $appInputDirectory $jarName)
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

    & $jpackage '--type' 'app-image' '--name' 'BYTEBOX' '--app-version' $version '--dest' $outputDirectory `
        '--input' $appInputDirectory '--main-jar' $jarName '--main-class' $mainClass `
        '--runtime-image' $runtimeDirectory '--java-options' '--add-modules=javafx.controls,javafx.fxml'
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }

    $launcher = Join-Path $imageDirectory 'BYTEBOX.exe'
    $launcherConfig = Join-Path $imageDirectory 'app\BYTEBOX.cfg'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) { throw "jpackage did not create $launcher" }
    if (-not (Test-Path -LiteralPath (Join-Path $imageDirectory 'runtime\bin\java.exe') -PathType Leaf)) {
        throw 'The application image does not contain its Java runtime.'
    }
    $configText = Get-Content -LiteralPath $launcherConfig -Raw
    if ($configText -notmatch [regex]::Escape($mainClass) -or $configText -notmatch 'javafx\.controls,javafx\.fxml') {
        throw 'The generated launcher configuration is missing its JavaFX entry point or modules.'
    }

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
