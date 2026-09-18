# Credentials are Keystore-wrapped ciphertext stored in AccountManager

An Account's header values and password are live credentials — a service token for an identity-aware proxy grants access to everything behind it — and the obvious Android options each fall short: `AccountManager.setPassword` and userdata store plaintext readable by anything holding the app's signature, while `EncryptedSharedPreferences` is a maintenance-mode dependency and a second store to keep in sync with the account record. We therefore encrypt each credential with an Android Keystore key and store only the ciphertext in `AccountManager` userdata, so the account record stays the single source of truth and the key never leaves the Keystore.

The threat model is a lost or stolen device and other software on it, not a targeted forensic attacker with the device unlocked.

## Consequences

- Credentials cannot be read back for display; the UI can offer replace, never reveal.
- Keystore keys do not survive a device-to-device restore, so a restored Account has unusable credentials and must detect this and prompt, rather than failing as a confusing authentication error. This is why the JSON export is passphrase-encrypted and portable while the on-device store is not.
