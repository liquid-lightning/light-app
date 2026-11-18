# LiqhtApp — a Lightning wallet on Liquid that doesn't hide your node

An Android wallet (Kotlin + Jetpack Compose) built on
**Ember**, a service that runs a
dedicated **Core Lightning node over Liquid** for every wallet. Unlike wallets
that reduce Lightning to a balance and a send button, LiqhtApp gives you
the node's whole RPC surface in a clean UI: channels, liquidity, invoices,
xpay, on-chain L-BTC, and payments in Liquid-issued assets.

LiqhtApp is based on
[olegfomenko/light-app](https://github.com/olegfomenko/light-app), which
ran the same UI on Blockstream Greenlight.

> **Custodial.** ember derives your node's signing key on the server from
> the recovery phrase you register with. Whoever operates the ember
> deployment the app points at can spend the node's funds. Use it with
> amounts and an operator you trust.

## Features

<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/welcome.png" width="260" alt="Welcome screen"><br>
      <b>Onboarding</b><br>
      <sub>Create a 24-word BIP39 wallet or restore from seed — pasting the
      whole phrase fills every word box. The seed registers (or recovers)
      your ember node and gets this phone a device certificate. The welcome
      screen links straight to this repo.</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/main.png" width="260" alt="Main tab"><br>
      <b>Main</b><br>
      <sub>On-chain L-BTC and Lightning balances, a card per Liquid asset the
      node holds with its price and value, live node status (including
      “starting” / “waking up” while ember brings the node up), node ID, and
      recent activity. Auto-refreshes every 30&nbsp;s.</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/invoices-payments.png" width="260" alt="Invoices — payments"><br>
      <b>Invoices &amp; payments</b><br>
      <sub>Created invoices (paid / unpaid / expired) and sent payments,
      labeled by their invoice description. Payments run asynchronously, and
      the Payments segment carries a <b>Check route</b> shortcut for
      dry-running a payment before sending it.</sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/transactions.png" width="260" alt="Transactions tab"><br>
      <b>Transactions</b><br>
      <sub>On-chain history with inline detail: TXID linking out to the
      <a href="https://blockstream.info/liquid">Liquid explorer</a>, confirmation count,
      and the node-tracked inputs and outputs of each transaction.</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/channels.png" width="260" alt="Channels tab"><br>
      <b>Channels</b><br>
      <sub>Total outbound / inbound liquidity with per-channel balance bars
      and state. Channel detail exposes reserves, fees, and HTLC limits;
      close from the app.</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/open-channel-sheet.png" width="260" alt="Open channel sheet"><br>
      <b>Open channel</b><br>
      <sub>Peer connect string, amount, min conf, feerate presets and the
      announce toggle — <code>connect</code> then <code>fundchannel</code>
      in one tap. A switch connects to the deployment’s shared swap service
      instead, for asset payments.</sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/utxos.png" width="260" alt="UTXOs tab"><br>
      <b>UTXOs</b><br>
      <sub>Raw coin control view: every output your node tracks, filtered by
      confirmed / unconfirmed / spent / immature, with its address and
      outpoint linking to the explorer.</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/receive-sheet.png" width="260" alt="Receive sheet"><br>
      <b>Receive</b><br>
      <sub>Fresh Liquid address as QR + text (<code>newaddr</code>), one-tap
      copy, and regeneration — a new address after each use.</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/pay-sheet.png" width="260" alt="Pay sheet"><br>
      <b>Pay</b><br>
      <sub>Paste a bolt11 invoice, bolt12 offer or asset invoice and get a
      decode preview. Lightning invoices pay via <b>xpay</b> (multi-part)
      with the full argument set: maxfee, retry_for, maxdelay, partial_msat,
      askrene layers. Asset invoices show a live quote first. Runs in the
      background while you keep using the app.</sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/create-invoice.png" width="260" alt="Create invoice sheet"><br>
      <b>Create invoice</b><br>
      <sub>Every <code>invoice</code> argument: fixed or any amount,
      description, expiry, label, CLTV, on-chain fallback address, custom or
      auto preimage, and <code>exposeprivatechannels</code>
      (bool or explicit SCIDs). Or pick a Liquid asset to be paid in.</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/send-chain-sheet.png" width="260" alt="Send on-chain sheet"><br>
      <b>Send on-chain</b><br>
      <sub><code>withdraw</code> with available balance shown, a Send-max
      option, and slow / normal / urgent feerate presets.</sub>
    </td>
    <td align="center">
      <img src="docs/screenshots/check-route-sheet.png" width="260" alt="Check route sheet"><br>
      <b>Check route</b><br>
      <sub>Dry-run a payment with askrene's <code>getroutes</code>: source /
      destination, amount, max fee, CLTV and delay bounds, and route layers
      (<code>auto.localchans</code>, <code>xpay</code>, custom). Returns the
      parts askrene would use, with per-hop fees and success
      probability.</sub>
    </td>
  </tr>
  <tr>
    <td align="center" colspan="3">
      <img src="docs/screenshots/settings.png" width="260" alt="Settings tab"><br>
      <b>Settings</b><br>
      <sub>Display unit (sat / msat / L-BTC), reveal the recovery phrase,
      a link to this repo, and a danger zone that wipes the seed and device
      certificate from this phone.</sub>
    </td>
  </tr>
</table>

## Architecture

```
core/       Rust crate `lightcore`. Wraps ember-client and exposes a
            mobile-friendly API over UniFFI: seed generation, node
            registration/recovery, every CLN RPC mapped to a
            Kotlin-friendly model, and ember's asset service.
app/        Android app (package app.light.wallet), Jetpack Compose UI.
scripts/    bootstrap.sh (one-shot host setup + build) and build-rust.sh
            (cross-compiles the .so + regenerates the UniFFI Kotlin bindings).
design/     Snapshot of the reference design (see Credits below).
```

`core` depends on
[ember-client](https://github.com/liquid-lightning/ember-client) by path
(`../ember-client`, next to this repo). ember-client talks gRPC over mTLS to
`ember-server`, which schedules your node and proxies Core Lightning's own
`cln.Node` service to it.

Registration: the app derives an identity key from the seed, signs a server
challenge with it, and sends the phrase to ember. ember registers (or
recovers) the node and issues a device certificate, which the app keeps.
Every later call authenticates with that certificate alone.

Data flow: the UI reads shared `StateFlow`s on `WalletRepository`; each Core
Lightning RPC has exactly one flow, so one refresh updates every screen that
shows the data. The Main tab auto-refreshes every 30 s; other tabs refresh on
entry and via pull-to-refresh. RPCs start only once the node reports it is
up; until then the app shows whether ember is still starting or waking it.

## Building

### Requirements

- Android 9 phone or newer with a 64-bit ARM CPU. Only `arm64-v8a` is built.
- MacOS or Linux. Windows works through WSL2, but the setup script is written for macOS.
- An `ember-server` deployment to connect to, plus its app certificate, app
  key and CA certificate (`ember-cli gen-app-cert`). You do not need them to
  *build* the app: the default build uses ember-client's dev placeholders
  and points at a server on your machine.

### 1. Install Android Studio

Download and install it from
[developer.android.com/studio](https://developer.android.com/studio). It bundles
a JDK and the Android SDK, so you do not need to install Java separately.

### 2. Install Rust

The wallet's core is a Rust library. Install the toolchain from
[rustup.rs](https://rustup.rs):

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

Then restart your terminal so `cargo` is on your `PATH`.

### 3. Install protoc

ember's and Core Lightning's gRPC bindings are generated at build time and
need the protocol buffer compiler:

```bash
brew install protobuf          # macOS
sudo apt install -y protobuf-compiler   # Debian / Ubuntu
```

### 4. Clone the repositories

Clone this repo and ember-client side by side:

```bash
git clone https://github.com/liquid-lightning/ember-client.git
git clone https://github.com/liquid-lightning/light-app.git
cd light-app
```

### 5. Point the build at your ember deployment

These are read at build time from environment variables (`EMBER_ADDR` and
`EMBER_NETWORK` also accept `-P` Gradle properties):

| Variable | Default | Meaning |
|---|---|---|
| `EMBER_ADDR` | `https://10.0.2.2:50051` | ember-server address. `10.0.2.2` is the emulator's alias for your machine. |
| `EMBER_NETWORK` | `liquid-testnet` | `liquid`, `liquid-testnet` or `liquid-regtest`. Must match the deployment. |
| `EMBER_APP_CERT`, `EMBER_APP_KEY`, `EMBER_CA_CERT` | ember-client's dev placeholders | Paths to the deployment's app identity and CA, compiled into the library. |

```bash
export EMBER_ADDR=https://ember.example.com:50051
export EMBER_NETWORK=liquid
export EMBER_APP_CERT=/path/to/app-cert.pem
export EMBER_APP_KEY=/path/to/app-key.pem
export EMBER_CA_CERT=/path/to/ca.pem
```

### 6. Run the setup & build script

This installs everything else that is missing — the Android NDK, the
`aarch64-linux-android` Rust target, `cargo-ndk` — and then builds the app. It is
safe to re-run and only downloads what you do not already have.

```bash
./scripts/bootstrap.sh --build
```

The first run takes a while, mostly compiling the Rust core and its
dependencies. When it finishes you have an APK at
`app/build/outputs/apk/release/`.

> Linux: the script assumes macOS paths and Homebrew. Install the NDK
> yourself with `sdkmanager "ndk;27.2.12479018"`, then run
> `rustup target add aarch64-linux-android` and `cargo install cargo-ndk`.

### Release signing

Release builds are signed with your own key when `app/keystore.properties`
exists (it is git-ignored):

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Create the keystore with:

```bash
keytool -genkeypair -v -keystore ~/liqhtapp-upload.jks \
  -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Without that file, `assembleRelease` falls back to the public Android debug key
so a locally built APK still installs on your own device. `bundleRelease`, which
produces the Play Store bundle, refuses to build without a real key.

## Security

ember is custodial, so the operator of your ember deployment is part of your
trust model. Within that, the app protects what it keeps on the phone.

- **The node's keys live with ember.** The recovery phrase is sent to ember
  when you register or recover, and ember derives the node's signing key
  from it. Anyone with the phrase, or control of the ember server, can spend
  the node's funds.
- **Server authentication is mTLS.** Every call presents a certificate
  chaining to the deployment's CA: the build's app certificate before
  registration, then the device certificate ember issued to this phone.
- **At rest:** the mnemonic and device certificate are AES-GCM encrypted
  with an Android Keystore key that is StrongBox-backed where the device
  provides it and **unusable while the device is locked**;
  `allowBackup=false`; secrets are git-ignored.
- **On screen:** seed reveal screens set `FLAG_SECURE` (no screenshots, no
  Recents thumbnail, no screen recording) and are hidden from accessibility
  services.
- **Clipboard:** copying the seed or a private key marks the clip sensitive and
  auto-clears it after 60 s.
- **In memory:** the seed is zeroized in the Rust core after use.
- **Logging:** the Rust client logs (which contain node/payment metadata)
  are emitted **only on debug builds**.

## Credits

LiqhtApp is based on
**[olegfomenko/light-app](https://github.com/olegfomenko/light-app)**.

The visual language is adapted from **"Crypto Wallet App UI Template" by
[DSCODE](https://www.figma.com/community/file/1540673463269508643)** (Figma
Community). `design/lightning-wallet.dc.html` is a local snapshot of the
LiqhtApp-specific design. Fonts (Manrope, Space Grotesk, JetBrains Mono) are
bundled under `app/src/main/res/font/`.

## Contributing

Issues and PRs welcome. Keep the security properties above intact — anything
touching the seed, the Keystore, clipboard, `FLAG_SECURE`, or the payment state
machine deserves extra scrutiny in review.

## License

[MIT](LICENSE). The visual design is adapted from the DSCODE template credited
above, which carries its own Figma Community terms.
