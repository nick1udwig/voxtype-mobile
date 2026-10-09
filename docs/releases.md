# Sign an APK and publish a GitHub release

This guide publishes a signed APK for direct installation, together with a versioned source checkout for the server.
It does not configure Google Play.
Run commands from the repository root in Bash.

## 1. Choose the version and tools

Set `versionName` and `versionCode` in [`android/app/build.gradle`](../android/app/build.gradle).
The current version is `0.2.0`, code `11`; increase the code for each subsequent release.
Use the same version in the release tag and filenames below:

```bash
release_version=0.2.0
```

Install JDK 17, Android SDK platform 35 and Build Tools 35.0.0.
`keytool` comes with the JDK; `zipalign` and `apksigner` come with Android Build Tools.
Set `ANDROID_HOME` to your SDK and put the JDK's `bin` directory on `PATH`.
For this workspace's staged toolchains, or an already-configured external SDK:

```bash
if [[ -d .toolchains/jdk ]]; then
  export JAVA_HOME="$PWD/.toolchains/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -d .toolchains/android ]]; then
  export ANDROID_HOME="$PWD/.toolchains/android"
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
android_tools="$ANDROID_HOME/build-tools/35.0.0"
```

## 2. Create a release key once

For direct APK distribution, keep the same signing key for future updates.
Back up the keystore and its passwords somewhere private and recoverable; losing them prevents ordinary updates to installations signed with that key.
Android's [app signing guide](https://developer.android.com/studio/publish/app-signing) explains the key and certificate requirements.

Keep the key outside the repository.
Generate it only for the first release:

```bash
signing_keystore="${XDG_DATA_HOME:-$HOME/.local/share}/voxtype-mobile/signing/release.jks"
install -d -m 700 "$(dirname "$signing_keystore")"
(
  umask 077
  keytool -genkeypair \
    -keystore "$signing_keystore" -storetype JKS \
    -alias voxtype-mobile -keyalg RSA -keysize 3072 -validity 10000 \
    -dname "CN=Voxtype Mobile"
)
```

Enter a strong keystore password when prompted.
Press Enter at the key-password prompt to use the same password.
For later releases, set `signing_keystore` to this existing file and skip key generation.
Keep passwords out of shell command arguments and source files.
The certificate's subject is public in the APK.

## 3. Build, align, sign, and verify

The helper builds the release variant and runs its JVM tests and lint.
Its output is unsigned; signing happens separately with your private key:

```bash
scripts/build-android --release

release_apk="artifacts/voxtype-mobile-${release_version}.apk"
"$android_tools/zipalign" -P 16 -f 4 \
  artifacts/voxtype-mobile-release-unsigned.apk \
  artifacts/voxtype-mobile-release-aligned.apk

"$android_tools/apksigner" sign \
  --ks "$signing_keystore" --ks-key-alias voxtype-mobile \
  --v4-signing-enabled false \
  --out "$release_apk" artifacts/voxtype-mobile-release-aligned.apk

"$android_tools/apksigner" verify --verbose --print-certs "$release_apk"
"$android_tools/zipalign" -c -P 16 4 "$release_apk"
```

`apksigner` prompts for the password.
Record the printed SHA-256 signing certificate fingerprint for users to compare across releases.
Align before signing and leave the signed APK unchanged afterward; see the official [apksigner](https://developer.android.com/tools/apksigner) and [zipalign](https://developer.android.com/tools/zipalign) documentation.

Install the release on a phone and complete the relevant [device checks](device-validation.md):

```bash
adb install -r "$release_apk"
```

Android rejects an update signed with a different key.
A development-signed installation cannot be replaced in place by your new release key.
If migrating one, save its server address/token before uninstalling it; uninstalling deletes app settings.
Reinstall and grant the permissions/accessibility service again.
Subsequent release APKs signed with the same key can update in place.

## 4. Publish reproducible server source

Run the daemon checks in [development.md](development.md) and `scripts/build-daemon`.
Do not launch the artifact alongside the running daemon.
Commit any daemon changes inside `daemon/`, push the fork first, then commit the new parent submodule pin and other release changes:

```bash
git -C daemon push origin mobile-api
# Commit the release-ready changes and daemon pin in the parent repository.
git diff --check
git push origin HEAD
```

Before tagging, check that a fresh clone can obtain the exact parent commit and its submodule without relying on local objects:

```bash
release_commit=$(git rev-parse HEAD)
release_checkout=$(mktemp -d)
git clone --recurse-submodules \
  https://github.com/nick1udwig/voxtype-mobile.git "$release_checkout/source"
git -C "$release_checkout/source" checkout --detach "$release_commit"
git -C "$release_checkout/source" submodule update --init --recursive
test "$(git -C "$release_checkout/source" rev-parse HEAD:daemon)" = \
  "$(git -C "$release_checkout/source/daemon" rev-parse HEAD)"
```

The working tree must be committed before you create the tag.
Confirm the APK was built from that source, then tag the tested commit:

```bash
git tag -a "v${release_version}" -m "Voxtype Mobile ${release_version}"
git push origin "v${release_version}"
```

Consumers should pin this tag or its commit and initialize submodules.
GitHub's automatic source archives do not bundle the daemon submodule checkout.
The standalone installer clones the default branch, so a tag in the installer URL alone does not pin the installed server; see [integration guidance](development.md).

## 5. Publish the APK and checksums

Create checksums with relative filenames:

```bash
(cd artifacts && sha256sum "voxtype-mobile-${release_version}.apk" > SHA256SUMS)
```

Write `artifacts/release-notes.md` with the changes, Android version requirements, mobile protocol **2 / opus_v1**, the exact daemon commit, server dependencies (including system libopus), and the device checks actually completed.
Label untested device behavior explicitly.
Include the signing certificate fingerprint.
Attach the signed APK and `SHA256SUMS`.
Keep the signing keystore private.

In GitHub, open **Releases → Draft a new release**, select the existing tag, enter the notes, attach the two files, and publish.
Alternatively, with an authenticated GitHub CLI:

```bash
gh release create "v${release_version}" \
  "artifacts/voxtype-mobile-${release_version}.apk" artifacts/SHA256SUMS \
  --repo nick1udwig/voxtype-mobile --verify-tag \
  --title "Voxtype Mobile ${release_version}" \
  --notes-file artifacts/release-notes.md
```

Add `--prerelease` for an experimental release.
A daemon binary is optional; if attaching one, name its OS/architecture, state its runtime dependencies, and add it to the checksums.
The source installer already builds the pinned daemon.
See [GitHub's release instructions](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository).
