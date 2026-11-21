# CarCheck OTG — Workspace Configuration & Cross-Platform Standards

This workspace is initialized with the global **Cross-Platform Dev Suite** plugin (`cross-platform-dev-suite`) and the **Android CLI** plugin (`android-cli-plugin`).

## Local Toolchain Conventions (Windows)
- **JDK for Gradle**: Android Studio bundled JBR is available at `C:\Program Files\Android\Android Studio\jbr`. Set `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` when invoking `.\gradlew.bat`.
- **Android SDK**: Configured in `local.properties` (`C:\Users\mina\AppData\Local\Android\Sdk`) with `adb` under `platform-tools\adb.exe`.
- **Node / npm**: PowerShell execution policy blocks `npm.ps1`; use `npm.cmd` / `npx.cmd` or `cmd /c npm`.
- **Suite Python Runtime**: `C:\Users\mina\.gemini\config\plugins\cross-platform-dev-suite\.venv\Scripts\python.exe` (includes `Pillow`, `NumPy`, `opencv-python-headless`, `Bandit`).

## Active Agents & Quality Gates
- **Android Development (`car_checker`)**: Route Android feature work to `android-code-agent`, verify builds via `.\gradlew.bat testDebugUnitTest assembleDebug`, and respect Arabic/English RTL/LTR layouts.
- **Cross-Platform Expansion**: Use `desktop-app-agent`, `image-processing-agent`, `browser-extension-agent`, `game-dev-agent`, `security-auditor-agent`, and `graphic-asset-agent` for corresponding modules.
- **Real-Time Security Guard**: Active on all `write_file` / `edit_file` steps; resolve any `CRITICAL`/`HIGH` findings before completion.
- **Graphic Assets & Textures**: Generate/verify textures, icons, and 3D X-Ray/HUD assets via `cross-platform-dev-suite-tools` MCP tools or `asset_texture_engine.py`.
