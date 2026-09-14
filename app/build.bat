@echo off
rem DSH phone wrapper APK build script (ASCII only for cmd.exe)
setlocal enabledelayedexpansion
set SDK=D:\android-sdk
set BT=%SDK%\build-tools\36.1.0
set PLAT=%SDK%\platforms\android-34\android.jar
set SRC=D:\dsh-phone
set OUT=%SRC%\out

if not exist %BT%\aapt2.exe ( echo [ERROR] build-tools not found at %BT% & exit /b 1 )
if not exist %PLAT% ( echo [ERROR] android.jar not found & exit /b 1 )

rmdir /s /q %OUT% 2>nul
mkdir %OUT%\flat %OUT%\gen %OUT%\classes %OUT%\obj 2>nul

echo [1/6] compile resources
%BT%\aapt2.exe compile --dir %SRC%\res -o %OUT%\flat
if errorlevel 1 exit /b 1

echo [2/6] link resources + generate R.java
set FLATS=
for /r %OUT%\flat %%f in (*.flat) do set FLATS=!FLATS! "%%f"
%BT%\aapt2.exe link -o %OUT%\base.apk -I %PLAT% --manifest %SRC%\AndroidManifest.xml --java %OUT%\gen --auto-add-overlay %FLATS%
if errorlevel 1 exit /b 1

echo [3/6] compile Java
dir /s /b %SRC%\java\*.java > %OUT%\sources.txt
dir /s /b %OUT%\gen\*.java >> %OUT%\sources.txt
javac -encoding UTF-8 -source 8 -target 8 -classpath %PLAT% -d %OUT%\classes @%OUT%\sources.txt
if errorlevel 1 exit /b 1

echo [4/6] generate dex
java -cp "%BT%\lib\d8.jar" com.android.tools.r8.D8 --release --output %OUT%\obj --lib %PLAT% %OUT%\classes\com\dsh\phone\*.class
if errorlevel 1 exit /b 1

echo [5/6] add dex to apk
cd /d %OUT%\obj
jar -uf %OUT%\base.apk classes.dex
cd /d %SRC%

echo [6/6] align + sign
%BT%\zipalign.exe -f 4 %OUT%\base.apk %OUT%\aligned.apk
if not exist %SRC%\dsh.keystore (
    echo generating keystore...
    keytool -genkeypair -keystore %SRC%\dsh.keystore -storepass dshstore -keypass dshstore -alias dsh -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=DSH,OU=Personal,O=Personal,L=Home,ST=Home,C=CN" 2>nul
)
java -jar "%BT%\lib\apksigner.jar" sign --ks %SRC%\dsh.keystore --ks-pass pass:dshstore --key-pass pass:dshstore --out %OUT%\dsh-phone.apk %OUT%\aligned.apk
if errorlevel 1 exit /b 1

echo.
echo BUILD OK: %OUT%\dsh-phone.apk
