# Pre-Release Setup Checklist

Before you can use the GitHub Actions workflow to create releases, follow these steps:

## 1. Generate Android Keystore

```bash
cd android/scripts
./generate-keystore.sh
```

This will create:
- `android/app/upload-keystore.jks` - The keystore file
- Prompts for keystore password, key password, and key alias

**Save these values securely - you'll need them for GitHub Secrets!**

## 2. Encode Keystore to Base64

```bash
cd android/app
base64 -w 0 upload-keystore.jks > keystore.base64
```

This creates a text file with the base64-encoded keystore.

## 3. Configure GitHub Secrets

1. Go to your repository on GitHub: https://github.com/peliorg/speech2code

2. Navigate to: **Settings** → **Secrets and variables** → **Actions**

3. Click **"New repository secret"** for each of these:

   | Secret Name | Value | Where to Get |
   |-------------|-------|--------------|
   | `ANDROID_KEYSTORE_BASE64` | Contents of `keystore.base64` file | Open the file and copy all contents |
   | `ANDROID_KEYSTORE_PASSWORD` | Keystore password | From step 1 (keystore generation) |
   | `ANDROID_KEY_ALIAS` | Key alias (probably "upload") | From step 1 (keystore generation) |
   | `ANDROID_KEY_PASSWORD` | Key password | From step 1 (keystore generation) |

4. Verify all 4 secrets are listed in the repository secrets page

## 4. Update Maintainer Information (Optional)

Update your email address in these files:

```bash
# Debian package
nano desktop/packaging/deb/DEBIAN/control
# Change: Maintainer: Daniel Pelikan <daniel@example.com>
# To:     Maintainer: Daniel Pelikan <your-real-email@example.com>

# RPM package
nano desktop/packaging/rpm/speech2code.spec
# Change: * Sat Feb 01 2026 Daniel Pelikan <daniel@example.com>
# To:     * Sat Feb 01 2026 Daniel Pelikan <your-real-email@example.com>
```

## 5. Test the Workflow (Recommended)

Before creating your first real release, test with a pre-release version:

1. Go to GitHub Actions: https://github.com/peliorg/speech2code/actions

2. Select "Release Build" workflow

3. Click "Run workflow"

4. Enter version: `0.1.0` (current version as a test)

5. Monitor the workflow execution

6. If successful, you'll see:
   - Git tag created: `v0.1.0`
   - All 5 artifacts built
   - GitHub Release created

7. Test installations:
   - Download the .deb and install on Ubuntu
   - Download the .apk and install on Android
   - Download the AppImage and run

## 6. Clean Up Test Release (If You Want)

If you tested with version 0.1.0 and want to remove it:

```bash
# Delete the release on GitHub (via web UI)

# Delete local and remote tag
git tag -d v0.1.0
git push origin :refs/tags/v0.1.0
```

## 7. Ready for First Real Release!

Once everything works:

1. Update version numbers if needed
2. Run workflow with your target version (e.g., `1.0.0`)
3. Verify the release
4. Announce to users!

---

## Quick Reference

### Triggering a Release

1. **Go to:** https://github.com/peliorg/speech2code/actions
2. **Select:** Release Build
3. **Click:** Run workflow
4. **Enter:** Version (e.g., `1.0.0`)
5. **Wait:** ~20 minutes for builds
6. **Check:** Releases page for published artifacts

### Expected Artifacts

✅ `speech2code-{version}-linux-x86_64.deb` (~15-20 MB)
✅ `speech2code-{version}-linux-x86_64.rpm` (~15-20 MB)
✅ `Speech2Code-{version}-x86_64.AppImage` (~20-25 MB)
✅ `speech2code-{version}.apk` (~30-40 MB)
✅ `speech2code-{version}-source.tar.gz` (~5-10 MB)

---

## Troubleshooting

### "Error: Missing secrets"

**Problem:** GitHub Secrets not configured

**Solution:** Complete step 3 above

### "Error: Tag already exists"

**Problem:** Version number already used

**Solution:** Use a different version number, or delete the existing tag

### "APK build failed"

**Problem:** Keystore secrets incorrect

**Solution:** 
1. Verify secrets match keystore generation values
2. Re-encode keystore to base64 (ensure no line breaks: `-w 0`)
3. Update GitHub Secrets

### "Debian build failed"

**Problem:** Missing dependencies or packaging error

**Solution:**
1. Check workflow logs for specific error
2. Test build locally (see `.github/workflows/README.md`)
3. Verify control file syntax

---

## Security Notes

- ⚠️ **Never commit** the keystore file (`.jks`) to git
- ⚠️ **Never commit** keystore passwords to git
- ⚠️ **Never share** the keystore.base64 file publicly
- ✅ **Do backup** your keystore file securely offline
- ✅ **Do keep** keystore passwords in a password manager

If you lose the keystore, you cannot update the Android app on user devices!

---

**Next File to Read:** `.github/workflows/README.md` for detailed documentation
