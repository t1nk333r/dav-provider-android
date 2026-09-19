# Removal keeps the synced rows, and asks before it does not

Removing an account deleted every contact and event it had synced, and the app deleting no rows itself made no difference: the rows belong to the account, and `ContactsProvider2` deletes the raw contacts of any `ACCOUNT_TYPE`/`ACCOUNT_NAME` not registered in `AccountManager`. The account is therefore the only thing that can hold the data, and "remove the account but keep what it synced" cannot be done by deleting less — it needs the account to survive.

We make keeping the default. Removing an account clears what made it a server — the address, the Credentials, the certificate and the Collection selection — and leaves the account registered with its rows untouched, reading as **disconnected**, with a **Configure** that fills it in again. Taking the data away is the second option, named on a checkbox inside the confirmation rather than being what the obvious button does; it is the one outcome that cannot be undone, so it is the one that has to be reached for.

## Consequences

- A disconnected account is still in Android's Settings → Accounts and still occupies its label. Registering that label again fills *that* account in — which is what `AccountStore.create` permits for a disconnected label and refuses for a configured one, the refusal being the silent-overwrite hazard this replaced.
- Removal clears Credentials because a disconnected account has nothing to authenticate with, not because removing is destructive. Reconfiguring one therefore means re-entering its password, and the copy says so before the choice is made.
- A kept account's rows stay until the account is removed with the checkbox ticked, or removed from Android's own settings — the platform reaps them either way. That is the hazard §7 already documents, and the reason a keep has to keep the account rather than orphan the rows.
- Two states exist where one did: **configured** and **disconnected**. Anything that reads an account has to say which it means. The settings card, the setup screen's collision warning and the store's `create` each do, and each decides it from the record's address — an account with no base URL is one that is not configured.
