# Mishka CLI

Mishka exposes a structured control surface for an authorized `adb shell`. It
uses an Android `ContentProvider`, not a TCP listener, so the app does not open
a new LAN port. The provider rejects callers other than the ADB shell, root,
system, or Mishka's own UID.

The host helper is [`scripts/mishka-cli`](../scripts/mishka-cli). It returns
JSON and never prints the Mihomo secret by default.

## Quick start

```sh
SERIAL='your-adb-serial'
scripts/mishka-cli --serial "$SERIAL" capabilities
scripts/mishka-cli --serial "$SERIAL" status
scripts/mishka-cli --serial "$SERIAL" proxy start
scripts/mishka-cli --serial "$SERIAL" --pretty profiles list
scripts/mishka-cli --serial "$SERIAL" backup export --output /tmp/mishka-backup.zip
```

Use `--package top.yukonga.mishka.side` or `--package
top.yukonga.mishka.tailscale` for the corresponding installed variant.

## Command groups

- `proxy`: `status`, `start`, `stop`, `restart`, `toggle`
- `profiles`: list/active/use/create/patch/apply/update/update-all/release/delete
- `overrides`: list/get/create/edit/save/update/delete/select
- `override`: read/replace/reset the global Mihomo override JSON
- `settings`: dump/get/set persisted settings (field names, snake_case keys, and kebab-case aliases are accepted)
- `boot`: inspect or toggle boot-start receiver
- `wifi`: inspect/start/stop/evaluate Wi-Fi policy monitoring
- `backup`: local export/import through the provider's file channel, versioned WebDAV snapshot test/list/upload/download/restore, explicit legacy export, plus cleanup
- `runtime`: Mihomo version/config/proxies/groups/delay/rules/connections/providers/DNS/cache plus one-shot traffic, memory, and log samples
- `diagnostics`: `preview` and `validate`

The CLI uses the same `ProxyServiceController`, repositories, profile
processor, diagnostics builder, and Mihomo repository as the GUI. It therefore
keeps subscription validation, profile locking, restart semantics, and VPN/root
checks in one place instead of duplicating service-start logic.

A VPN start still needs Android's VPN consent. Approve it once in the Mishka GUI
first; the CLI cannot bypass or display Android's system consent dialog. The host
helper routes `proxy.start`, `proxy.restart`, and the start side of `proxy.toggle`
through a transparent, `DUMP`-protected Activity because Android rejects a
background ContentProvider when it tries to start a foreground service.

The host helper also provides polling commands such as `watch status`, `watch
traffic`, `watch connections`, and `watch memory`; each output line is one JSON
response. `watch log` requests the next available log sample and may wait for
the five-second runtime timeout. It is intentionally not a fake WebSocket.

`backup import` requires the proxy to be stopped and returns
`restartRequired=true`. Relaunch Mishka after a successful import so its in-memory
Room/override state is rebuilt from the restored files.

WebDAV backups created by the current version are immutable snapshots under `Mishka/snapshots/`; normal uploads never overwrite the original app's `Mishka/mishka-backup.zip`. Use `backup.webdav-export-legacy` only when intentionally exporting a snapshot for the original app.
