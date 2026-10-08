param(
    [string]$SdkRoot = 'D:\AndroidSDK',
    [string]$JavaRoot = 'C:\Program Files\Java\jdk-22',
    [string]$DeviceSerial = '',
    [string]$Baseline = '181ce6f4221d29610ca791e5b37e7c3d37a1454e'
)
$ErrorActionPreference = 'Stop'
function Invoke-Checked([string]$program, [string[]]$toolArguments) {
    & $program @toolArguments
    if ($LASTEXITCODE -ne 0) { throw "$program failed with exit code $LASTEXITCODE" }
}
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $workspace
try {
    $destination = 'build/perf-diagnostics/video-copy'
    New-Item -ItemType Directory -Force "$destination/smoke" | Out-Null
    $jpegLibrary = Get-ChildItem 'app/.cxx/Debug' -Recurse -Filter libjpeg.a |
        Where-Object { $_.FullName -match 'arm64-v8a' } | Select-Object -First 1
    if (!$jpegLibrary) { throw 'Build the ARM64 debug APK first.' }
    $nativeBuild = Split-Path (Split-Path $jpegLibrary.FullName -Parent) -Parent
    $clangRoot = Join-Path $SdkRoot 'ndk/30.0.15729638/toolchains/llvm/prebuilt/windows-x86_64/bin'
    $adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
    $adbTarget = @()
    if ($DeviceSerial) { $adbTarget = @('-s', $DeviceSerial) }
    $includes = @('-Iapp/src/main/cpp/third_party/libuvc/include', "-I$nativeBuild/include",
        '-Iapp/src/main/cpp/third_party/libusb/libusb', '-Iapp/src/main/cpp')
    $flags = @('--target=aarch64-linux-android24', '-O2', '-ffunction-sections', '-fdata-sections',
        '-Wl,--gc-sections', '-DLIBUVC_NUM_TRANSFER_BUFS=512', '-DLIBUVC_PACKETS_PER_TRANSFER_MAX=16')
    Invoke-Checked "$clangRoot/clang.exe" ($flags + $includes + @('app/src/test/native/uvc_payload_test.c',
        'app/src/main/cpp/third_party/libuvc/src/stream.c', '-llog', '-o', "$destination/uvc_payload_test"))
    Invoke-Checked "$clangRoot/clang.exe" ($flags + $includes + @('app/src/test/native/uvc_receive_queue_test.c',
        'app/src/main/cpp/third_party/libuvc/src/stream.c', '-llog', '-o', "$destination/uvc_receive_queue_test"))
    $reference = (& git show "${Baseline}:app/src/main/cpp/third_party/libuvc/src/frame-mjpeg.c") -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the reference JPEG decoder.' }
    $reference = [regex]::Replace($reference, '\buvc_mjpeg(\w+)', 'reference_mjpeg$1')
    [IO.File]::WriteAllText((Join-Path $workspace "$destination/reference-frame-mjpeg.c"), $reference)
    Invoke-Checked "$clangRoot/clang.exe" ($flags + $includes + @('-DMJPEG_COMPARE_REFERENCE',
        '-Iapp/src/main/cpp/third_party/libjpeg-turbo/src', "-I$nativeBuild/jpeg-turbo",
        'app/src/test/native/mjpeg_diagnostics_test.c', "$destination/reference-frame-mjpeg.c",
        'app/src/main/cpp/mjpeg_repair.c', "$nativeBuild/libuvc.a", $jpegLibrary.FullName, '-llog', '-lm',
        '-o', "$destination/mjpeg_diagnostics_test"))
    Invoke-Checked "$clangRoot/clang++.exe" @('--target=aarch64-linux-android24', '-std=c++17', '-O2',
        '-Wall', '-Wextra', '-Werror', '-static-libstdc++', '-Iapp/src/main/cpp',
        'app/src/test/native/native_video_buffer_test.cpp', '-o', "$destination/native_video_buffer_test")
    Invoke-Checked "$JavaRoot/bin/javac.exe" @('--release', '8', '-Xlint:-options', '-encoding', 'UTF-8',
        '-d', "$destination/smoke", 'diagnostics/video-copy/VideoCopySmoke.java')
    Invoke-Checked "$JavaRoot/bin/java.exe" @('-cp', "$SdkRoot/build-tools/36.1.0/lib/d8.jar", 'com.android.tools.r8.D8',
        '--min-api', '24', '--output', "$destination/smoke.jar", "$destination/smoke/VideoCopySmoke.class")
    $apkPath = 'app/build/intermediates/apk/debug/app-debug.apk'
    if (!(Test-Path $apkPath)) { $apkPath = 'app/build/outputs/apk/debug/app-debug.apk' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apk = [IO.Compression.ZipFile]::OpenRead((Join-Path $workspace $apkPath))
    try {
        foreach ($library in @('libuvclivestreaming_usb.so', 'libc++_shared.so')) {
            $entry = $apk.GetEntry("lib/arm64-v8a/$library")
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $workspace "$destination/$library"), $true)
        }
    } finally { $apk.Dispose() }
    $remote = '/data/local/tmp/uvc-video-copy'
    Invoke-Checked $adb ($adbTarget + @('shell', 'mkdir', '-p', $remote))
    $executables = @('uvc_payload_test', 'uvc_receive_queue_test', 'mjpeg_diagnostics_test', 'native_video_buffer_test')
    $uploads = @($apkPath, "$destination/smoke.jar", "$destination/libuvclivestreaming_usb.so",
        "$destination/libc++_shared.so", 'app/src/androidTest/resources/mjpeg/yuv420.jpg',
        'app/src/androidTest/resources/mjpeg/yuv422.jpg', 'app/src/androidTest/resources/mjpeg/yuv444.jpg')
    $uploads += $executables | ForEach-Object { "$destination/$_" }
    Invoke-Checked $adb ($adbTarget + @('push') + $uploads + @("$remote/"))
    foreach ($executable in $executables) {
        Invoke-Checked $adb ($adbTarget + @('shell', 'chmod', '755', "$remote/$executable"))
        Invoke-Checked $adb ($adbTarget + @('shell', "$remote/$executable"))
    }
    Invoke-Checked $adb ($adbTarget + @('shell',
        "CLASSPATH=$remote/app-debug.apk:$remote/smoke.jar dalvikvm -Djava.library.path=$remote VideoCopySmoke $remote"))
} finally { Pop-Location }
