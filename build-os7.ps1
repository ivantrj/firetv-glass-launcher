param([string]$JdkHome=$env:JAVA_HOME, [string]$SdkRoot=$env:ANDROID_HOME)
$ErrorActionPreference='Stop'
if (-not $SdkRoot) { $SdkRoot=$env:ANDROID_SDK_ROOT }
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin/javac.exe'))) {
    throw 'Set JAVA_HOME or pass -JdkHome with a JDK 17 folder.'
}
if (-not $SdkRoot -or -not (Test-Path (Join-Path $SdkRoot 'platforms/android-35/android.jar'))) {
    throw 'Set ANDROID_HOME or pass -SdkRoot with Android SDK platform 35 and build-tools 35.0.0.'
}
if (-not (Test-Path (Join-Path $SdkRoot 'build-tools/35.0.0/aapt2.exe'))) {
    throw 'Install Android SDK build-tools 35.0.0 first.'
}
$previousJava=$env:JAVA_HOME
$previousAndroid=$env:ANDROID_USER_HOME
$previousGradle=$env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME=(Resolve-Path $JdkHome).Path
    $env:ANDROID_USER_HOME=Join-Path $PSScriptRoot 'local-state/android'
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME=Join-Path $PSScriptRoot 'local-state/gradle' }
    $sdkPath=(Resolve-Path $SdkRoot).Path -replace '\\','/'
    "sdk.dir=$sdkPath" | Set-Content -Encoding ascii (Join-Path $PSScriptRoot 'launcher/local.properties')
    $testDir=Join-Path $PSScriptRoot 'local-state/policy-tests'
    New-Item -ItemType Directory -Force $testDir | Out-Null
    & "$env:JAVA_HOME/bin/javac.exe" -d $testDir (Join-Path $PSScriptRoot 'launcher/app/src/main/java/com/ivantrj/glasslauncher/HomeRedirectPolicy.java') (Join-Path $PSScriptRoot 'tests/HomeRedirectPolicyTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Policy test compilation failed.' }
    & "$env:JAVA_HOME/bin/java.exe" -cp $testDir com.ivantrj.glasslauncher.HomeRedirectPolicyTest
    if ($LASTEXITCODE -ne 0) { throw 'Policy tests failed.' }
    & (Join-Path $PSScriptRoot 'launcher/gradlew.bat') -p (Join-Path $PSScriptRoot 'launcher') --no-daemon -PfireOs7=true lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build or lint failed; do not install.' }
} finally {
    $env:JAVA_HOME=$previousJava
    $env:ANDROID_USER_HOME=$previousAndroid
    $env:GRADLE_USER_HOME=$previousGradle
}
