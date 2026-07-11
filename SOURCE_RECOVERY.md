# Source recovery notes

## Why this archive is reconstructed

The signed Codex Meter 1.7.0 APK survived, but its original transient build directory did not. Earlier clean source was retained through version 1.5.0. To provide a usable 1.7.0 source download, this tree was reconstructed from:

1. The clean Codex Meter 1.5.0 source archive.
2. The distributed signed Codex Meter 1.7.0 APK.
3. Android API 36 framework stubs for compile verification.

## Recovery process

- Decompiled the 1.7.0 DEX and decoded all packaged resources.
- Replaced the 1.5 Java and resource trees with the 1.7 contents.
- Removed generated `R.java` and `Manifest.java` from the project source.
- Restored the clean OAuth callback implementation where the decompiler had damaged exception control flow; the recovered 1.7 bytecode showed the same externally relevant behavior.
- Repaired decompiler artifacts in reset-credit selection, HTTP error reading, reset-credit consumption, gauge sweep calculation, and reset-action visibility.
- Updated the direct Android build script to version name 1.7.0 and version code 8.
- Cleaned compiled-only manifest fields so the build script supplies SDK and version metadata normally.

## Verification performed

The complete Java source set compiled successfully against Android API 36 when paired with the resource identifiers recovered from the APK. The compiler produced warnings from decompiler-simplified generic types, but no errors.

The resource tree and manifest are decoded from the distributed APK. A full APK rebuild requires Android SDK Platform 36 and Build Tools 36.x.

## Limitations

- Local variable names and formatting in recovered classes are not the original authoring names.
- Some generic type information was erased by DEX compilation and therefore appears as raw collections.
- Comments added after version 1.5 could not be recovered from bytecode.
- A rebuilt APK is not expected to be byte-for-byte identical to the distributed APK.
- The private release signing key is intentionally not included.

This archive is suitable for inspection, modification, and continued development, but it is not a forensic copy of the lost original source workspace.
