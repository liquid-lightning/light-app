//! lightcore — Rust core for LightApp.
//!
//! Wraps `ember-client` to drive a node provisioned by ember (an in-house
//! custodial Core Lightning provisioning service) and exposes a
//! mobile-friendly API through UniFFI (Kotlin now, Swift later).
//!
//! Previously wrapped Blockstream Greenlight's `gl-client`; ember-client
//! was deliberately shaped to mirror it (`Scheduler`, `node::ClnClient`,
//! `register`/`recover`/`node()`), so the CLN RPC business logic below is
//! almost unchanged from that version — only registration/recovery/
//! connection (ember's auth model is a seed-derived challenge-signed
//! identity exchanged for a CA-issued device certificate, not a local
//! HSM/signer) actually differs.

mod models;

pub use models::*;

use std::sync::Arc;

use bip39::{Language, Mnemonic};
use ember_client::auth::AuthKey;
use ember_client::pb::cln;
use ember_client::{ClientTlsIdentity, EmberClient};
use rand::RngCore;
use zeroize::Zeroizing;

uniffi::setup_scaffolding!();

/// Route Rust logs to Android logcat under the tag "lightcore".
///
/// `enable` must be `false` on release builds: the client/transport stack
/// can emit node ids, peer ids and payment metadata at info/error level,
/// which anything holding READ_LOGS could harvest. Pass `BuildConfig.DEBUG`
/// from Kotlin so logs only ever reach logcat on developer builds.
#[uniffi::export]
pub fn init_logging(enable: bool) {
    if !enable {
        return;
    }
    #[cfg(target_os = "android")]
    {
        use std::sync::Once;
        static ONCE: Once = Once::new();
        ONCE.call_once(|| {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("lightcore"),
            );
        });
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn ember_err<E: std::fmt::Display>(e: E) -> CoreError {
    CoreError::Msg(format!("{e}"))
}

/// Normalizes a user-supplied network name into the string CLN itself
/// expects on the wire (`--network`'s own values for ember's Liquid-backed
/// CLN fork - see ember's README "Open items" for why CLN runs over Liquid,
/// not Bitcoin, now). Guards against typos the same way the old
/// `gl_client::bitcoin::Network` parse used to.
fn parse_network(network: &str) -> CoreResult<String> {
    match network.to_lowercase().as_str() {
        "liquid" | "mainnet" => Ok("liquid".to_string()),
        "liquidtestnet" | "liquid-testnet" | "testnet" => Ok("liquid-testnet".to_string()),
        "liquidregtest" | "liquid-regtest" | "regtest" => Ok("liquid-regtest".to_string()),
        other => Err(CoreError::Msg(format!("unknown network: {other}"))),
    }
}

/// The full 64-byte BIP39 seed (not CLN's own `hsm_secret` — ember derives
/// that server-side straight from the mnemonic text). Self-wiping so this
/// process's only copy doesn't outlive the call.
fn seed_from_mnemonic(phrase: &str, passphrase: &str) -> CoreResult<Zeroizing<[u8; 64]>> {
    let mnemonic = Mnemonic::parse_in(Language::English, phrase.trim())
        .map_err(|e| CoreError::Msg(format!("invalid mnemonic: {e}")))?;
    Ok(Zeroizing::new(mnemonic.to_seed(passphrase)))
}

fn amount(msat: u64) -> cln::Amount {
    cln::Amount { msat }
}

/// sat → msat with overflow rejected. `opt-level=3` builds have overflow
/// checks off, so an unchecked `sat * 1000` would silently wrap to a tiny
/// value and send/fund the wrong amount.
fn checked_msat(sat: u64) -> CoreResult<u64> {
    sat.checked_mul(1000)
        .ok_or_else(|| CoreError::Msg(format!("amount too large: {sat} sat")))
}

fn amount_or_any(msat: Option<u64>) -> cln::AmountOrAny {
    cln::AmountOrAny {
        value: Some(match msat {
            Some(m) => cln::amount_or_any::Value::Amount(amount(m)),
            None => cln::amount_or_any::Value::Any(true),
        }),
    }
}

fn amount_or_all(msat: u64, all: bool) -> cln::AmountOrAll {
    cln::AmountOrAll {
        value: Some(if all {
            cln::amount_or_all::Value::All(true)
        } else {
            cln::amount_or_all::Value::Amount(amount(msat))
        }),
    }
}

/// Parse "slow" | "normal" | "urgent" | "<n>perkw" | "<n>perkb".
fn parse_feerate(s: &str) -> CoreResult<cln::Feerate> {
    use cln::feerate::Style;
    let s = s.trim().to_lowercase();
    let style = match s.as_str() {
        "slow" => Style::Slow(true),
        "normal" => Style::Normal(true),
        "urgent" => Style::Urgent(true),
        _ => {
            if let Some(n) = s.strip_suffix("perkw") {
                Style::Perkw(n.parse().map_err(ember_err)?)
            } else if let Some(n) = s.strip_suffix("perkb") {
                Style::Perkb(n.parse().map_err(ember_err)?)
            } else {
                return Err(CoreError::Msg(format!("invalid feerate: {s}")));
            }
        }
    };
    Ok(cln::Feerate { style: Some(style) })
}

fn opt_feerate(s: &Option<String>) -> CoreResult<Option<cln::Feerate>> {
    match s {
        Some(v) if !v.trim().is_empty() => Ok(Some(parse_feerate(v)?)),
        _ => Ok(None),
    }
}

fn msat_of(a: &Option<cln::Amount>) -> Option<u64> {
    a.as_ref().map(|x| x.msat)
}

fn hexs(b: &[u8]) -> String {
    hex::encode(b)
}

fn channel_state_name(v: i32) -> String {
    match v {
        0 => "OPENINGD",
        1 => "CHANNELD_AWAITING_LOCKIN",
        2 => "CHANNELD_NORMAL",
        3 => "CHANNELD_SHUTTING_DOWN",
        4 => "CLOSINGD_SIGEXCHANGE",
        5 => "CLOSINGD_COMPLETE",
        6 => "AWAITING_UNILATERAL",
        7 => "FUNDING_SPEND_SEEN",
        8 => "ONCHAIN",
        9 => "DUALOPEND_OPEN_INIT",
        10 => "DUALOPEND_AWAITING_LOCKIN",
        11 => "CHANNELD_AWAITING_SPLICE",
        12 => "DUALOPEND_OPEN_COMMITTED",
        13 => "DUALOPEND_OPEN_COMMIT_READY",
        14 => "CLOSED",
        _ => "UNKNOWN",
    }
    .to_string()
}

/// Packs a [`ClientTlsIdentity`] into the opaque blob `register_node`/
/// `recover_node` return and `connect_node` takes back — three
/// length-prefixed UTF-8 strings, in field order. Internal: Kotlin never
/// looks inside this, just stores and replays the bytes.
fn encode_identity(identity: &ClientTlsIdentity) -> Vec<u8> {
    let mut buf = Vec::new();
    for part in [&identity.cert_pem, &identity.key_pem, &identity.ca_cert_pem] {
        let bytes = part.as_bytes();
        buf.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
        buf.extend_from_slice(bytes);
    }
    buf
}

fn decode_identity(bytes: &[u8]) -> CoreResult<ClientTlsIdentity> {
    fn read_part(cursor: &mut &[u8]) -> CoreResult<String> {
        if cursor.len() < 4 {
            return Err(CoreError::Msg("corrupt device credentials".into()));
        }
        let len = u32::from_le_bytes(cursor[..4].try_into().unwrap()) as usize;
        *cursor = &cursor[4..];
        if cursor.len() < len {
            return Err(CoreError::Msg("corrupt device credentials".into()));
        }
        let s = String::from_utf8(cursor[..len].to_vec())
            .map_err(|e| CoreError::Msg(format!("corrupt device credentials: {e}")))?;
        *cursor = &cursor[len..];
        Ok(s)
    }

    let mut cursor = bytes;
    let cert_pem = read_part(&mut cursor)?;
    let key_pem = read_part(&mut cursor)?;
    let ca_cert_pem = read_part(&mut cursor)?;
    Ok(ClientTlsIdentity {
        cert_pem,
        key_pem,
        ca_cert_pem,
    })
}

// ---------------------------------------------------------------------------
// Mnemonic utilities (sync, no network)
// ---------------------------------------------------------------------------

/// Generate a fresh 24-word BIP39 mnemonic from 32 bytes of OS entropy.
#[uniffi::export]
pub fn generate_mnemonic() -> CoreResult<String> {
    let mut entropy = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut entropy);
    let mnemonic = Mnemonic::from_entropy_in(Language::English, &entropy)
        .map_err(|e| CoreError::Msg(e.to_string()))?;
    Ok(mnemonic.words().collect::<Vec<_>>().join(" "))
}

/// Validate a user-supplied mnemonic (word list + checksum).
#[uniffi::export]
pub fn validate_mnemonic(phrase: String) -> bool {
    Mnemonic::parse_in(Language::English, phrase.trim()).is_ok()
}

// ---------------------------------------------------------------------------
// Registration / recovery (ember's challenge-sign-register handshake)
// ---------------------------------------------------------------------------

/// Register a brand-new node for this seed with ember-server at
/// `ember_addr`. Connects with ember-client's baked-in pre-auth "app"
/// identity (see `ember_client::default_app_identity`) to prove seed
/// ownership and request a device certificate — the returned
/// `device_creds` is what `connect_node` needs from now on; the
/// mnemonic/passphrase aren't needed again after this call.
#[uniffi::export(async_runtime = "tokio")]
pub async fn register_node(
    ember_addr: String,
    mnemonic: String,
    passphrase: String,
    network: String,
    invite_code: String,
) -> CoreResult<RegisterResult> {
    let net = parse_network(&network)?;
    let seed = seed_from_mnemonic(&mnemonic, &passphrase)?;
    let auth_key = AuthKey::from_seed(seed.as_slice()).map_err(ember_err)?;

    let app_identity = ember_client::default_app_identity();
    let app_client = EmberClient::connect(ember_addr.clone(), app_identity.clone())
        .await
        .map_err(ember_err)?;

    let registered = app_client
        .scheduler()
        .register(
            &auth_key,
            net,
            mnemonic,
            invite_code,
            app_identity.ca_cert_pem,
        )
        .await
        .map_err(ember_err)?;

    Ok(RegisterResult {
        node_id: registered.node_id,
        mnemonic: registered.mnemonic,
        device_creds: encode_identity(&registered.device_identity),
    })
}

/// Recover access to an already-registered node (e.g. after reinstalling
/// the app with the same mnemonic). Returns a fresh `device_creds` blob —
/// e.g. after losing the original one; the underlying node is unchanged.
#[uniffi::export(async_runtime = "tokio")]
pub async fn recover_node(
    ember_addr: String,
    mnemonic: String,
    passphrase: String,
) -> CoreResult<RecoverResult> {
    let seed = seed_from_mnemonic(&mnemonic, &passphrase)?;
    let auth_key = AuthKey::from_seed(seed.as_slice()).map_err(ember_err)?;

    let app_identity = ember_client::default_app_identity();
    let app_client = EmberClient::connect(ember_addr.clone(), app_identity.clone())
        .await
        .map_err(ember_err)?;

    let recovered = app_client
        .scheduler()
        .recover(&auth_key, mnemonic, app_identity.ca_cert_pem)
        .await
        .map_err(ember_err)?;

    Ok(RecoverResult {
        node_id: recovered.node_id,
        device_creds: encode_identity(&recovered.device_identity),
    })
}

/// Description tag of a bolt11 invoice, if it carries a direct (non-hash) one.
fn bolt11_description(b11: &str) -> Option<String> {
    let inv: lightning_invoice::Bolt11Invoice = b11.parse().ok()?;
    match inv.description() {
        lightning_invoice::Bolt11InvoiceDescriptionRef::Direct(d) => {
            let s = d.to_string();
            if s.is_empty() { None } else { Some(s) }
        }
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// The connected node
// ---------------------------------------------------------------------------

/// A connected node, authenticated with the device identity `connect_node`
/// was given. Create it with `connect_node` every time the app starts;
/// drop it (Kotlin: `close()`) when done — there's no background task to
/// stop (ember has no local signer loop), just the connection itself.
#[derive(uniffi::Object)]
pub struct LightNode {
    client: tokio::sync::Mutex<ember_client::node::ClnClient>,
    // Kept alongside `client` (not just the one `ClnClient` extracted from
    // it) so `node_phase()` can re-ask ember's own scheduler for the pod's
    // lifecycle phase - cheap to hold, `EmberClient` is just a handle on
    // the shared connection (see its own doc comment).
    ember: EmberClient,
}

/// Connect to a node previously registered/recovered, using the
/// `device_creds` blob `register_node`/`recover_node` returned.
#[uniffi::export(async_runtime = "tokio")]
pub async fn connect_node(ember_addr: String, device_creds: Vec<u8>) -> CoreResult<Arc<LightNode>> {
    let identity = decode_identity(&device_creds)?;
    let ember = EmberClient::connect(ember_addr, identity)
        .await
        .map_err(ember_err)?;
    let client = ember.node_client();

    Ok(Arc::new(LightNode {
        client: tokio::sync::Mutex::new(client),
        ember,
    }))
}

#[uniffi::export(async_runtime = "tokio")]
impl LightNode {
    // -- Node info ---------------------------------------------------------

    /// ember's own view of the node's lifecycle (pod exists? ready?
    /// idled down?) - distinct from `get_info`, which only succeeds once
    /// the CLN pod itself is actually reachable. Meant to be polled right
    /// after connecting, to tell "still being created"/"waking up" apart
    /// from a real connection failure.
    pub async fn node_phase(&self) -> CoreResult<NodePhase> {
        use ember_client::pb::ember::scheduler::v1::NodePhase as WirePhase;
        let info = self.ember.scheduler().node_info().await.map_err(rpc_err)?;
        let phase = info
            .node
            .and_then(|n| WirePhase::try_from(n.phase).ok())
            .unwrap_or(WirePhase::Unspecified);
        Ok(match phase {
            WirePhase::Unspecified => NodePhase::Unknown,
            WirePhase::Provisioning => NodePhase::Provisioning,
            WirePhase::Running => NodePhase::Running,
            WirePhase::Stopped => NodePhase::Stopped,
            WirePhase::Failed => NodePhase::Failed,
            WirePhase::Deleting => NodePhase::Deleting,
        })
    }

    // -- Cross-asset payments -----------------------------------------------
    //
    // ember's own Assets service, not a CLN proxy - see
    // ember_client::Assets's own doc comment.

    /// The assets ember currently supports for cross-asset payments -
    /// server-side config, not per-node state. Drives the asset picker on
    /// the Receive/Pay sheets.
    pub async fn list_assets(&self) -> CoreResult<Vec<AssetInfo>> {
        let assets = self.ember.assets().list_assets().await.map_err(rpc_err)?;
        Ok(assets
            .into_iter()
            .map(|a| AssetInfo {
                id: a.id,
                symbol: a.symbol,
                name: a.name,
            })
            .collect())
    }

    /// Creates an invoice requiring payment in `asset` (an id from
    /// `list_assets`, or "lbtc" for this node's own native coin).
    pub async fn create_asset_invoice(
        &self,
        asset: String,
        amount_msat: u64,
        label: String,
        description: String,
    ) -> CoreResult<AssetInvoiceResult> {
        let r = self
            .ember
            .assets()
            .create_invoice(asset, amount_msat, label, description)
            .await
            .map_err(rpc_err)?;
        Ok(AssetInvoiceResult {
            wrapped: r.wrapped,
            bolt11: r.bolt11,
            payment_hash: r.payment_hash,
        })
    }

    /// Estimates what paying `wrapped` would cost, without paying it -
    /// an estimate from this node's own local pricing config, not the
    /// swap provider's live/binding decision (made only once a payment is
    /// actually attempted). Only returns real numbers if this node
    /// happens to carry matching pricing config of its own; otherwise
    /// fails the same way an unsupported pair would.
    pub async fn quote_asset_invoice(&self, wrapped: String) -> CoreResult<AssetQuote> {
        let r = self
            .ember
            .assets()
            .quote_invoice(wrapped)
            .await
            .map_err(rpc_err)?;
        Ok(AssetQuote {
            from_asset: r.from_asset,
            rate_ppm: r.rate_ppm,
            fee_base_msat: r.fee_base_msat,
            tiers: r
                .tiers
                .into_iter()
                .map(|t| AssetQuoteTier {
                    name: t.name,
                    rate_ppm: t.rate_ppm,
                    from_amount_msat: t.from_amount_msat,
                })
                .collect(),
        })
    }

    /// Pays `wrapped`, even in an asset this node doesn't directly hold
    /// (via a swap through the configured provider). `from_asset`: the
    /// caller's own choice of which asset to pay with - omitted, tries a
    /// direct payment in the invoice's own asset first, falling back to a
    /// swap only if that fails.
    pub async fn pay_asset_invoice(
        &self,
        wrapped: String,
        tier: Option<String>,
        from_asset: Option<String>,
        from_amount_msat: Option<u64>,
    ) -> CoreResult<PayAssetInvoiceResult> {
        let r = self
            .ember
            .assets()
            .pay_invoice(wrapped, tier, from_asset, from_amount_msat)
            .await
            .map_err(rpc_err)?;
        Ok(PayAssetInvoiceResult {
            payment_preimage: r.payment_preimage,
            status: r.status,
        })
    }

    /// This node's own channel balance per asset - drives the per-asset
    /// balance cards on the Main dashboard.
    pub async fn list_asset_balances(&self) -> CoreResult<Vec<AssetBalance>> {
        let balances = self.ember.assets().balances().await.map_err(rpc_err)?;
        Ok(balances
            .into_iter()
            .map(|b| AssetBalance {
                asset: b.asset,
                channel_balance_msat: b.channel_balance_msat,
                onchain_balance_msat: b.onchain_balance_msat,
            })
            .collect())
    }

    /// The shared swap-service node to connect to by default when opening
    /// a channel - `None` if the server has none configured.
    pub async fn get_swap_service(&self) -> CoreResult<Option<SwapService>> {
        let pair = self.ember.assets().swap_service().await.map_err(rpc_err)?;
        Ok(pair.map(|(node_id, addr)| SwapService { node_id, addr }))
    }

    pub async fn get_info(&self) -> CoreResult<NodeInfo> {
        let mut c = self.client.lock().await.clone();
        let r = c
            .getinfo(cln::GetinfoRequest::default())
            .await
            .map_err(rpc_err)?
            .into_inner();
        let id = hexs(&r.id);
        let connect_string = r
            .address
            .iter()
            .find_map(|a| {
                a.address
                    .as_ref()
                    .map(|host| format!("{id}@{host}:{}", a.port))
            })
            .unwrap_or_else(|| id.clone());
        Ok(NodeInfo {
            id,
            alias: r.alias,
            color: hexs(&r.color),
            network: r.network,
            blockheight: r.blockheight,
            version: r.version,
            num_peers: r.num_peers,
            num_active_channels: r.num_active_channels,
            num_inactive_channels: r.num_inactive_channels,
            num_pending_channels: r.num_pending_channels,
            fees_collected_msat: msat_of(&r.fees_collected_msat).unwrap_or(0),
            connect_string,
        })
    }

    // -- Funds / liquidity -------------------------------------------------

    pub async fn list_funds(&self) -> CoreResult<Funds> {
        let mut c = self.client.lock().await.clone();
        let r = c
            .list_funds(cln::ListfundsRequest { spent: Some(true) })
            .await
            .map_err(rpc_err)?
            .into_inner();

        let outputs: Vec<OnchainOutput> = r
            .outputs
            .iter()
            .map(|o| OnchainOutput {
                txid: hexs(&o.txid),
                output: o.output,
                amount_msat: msat_of(&o.amount_msat).unwrap_or(0),
                address: o.address.clone(),
                status: match o.status {
                    0 => "unconfirmed",
                    1 => "confirmed",
                    2 => "spent",
                    3 => "immature",
                    _ => "unknown",
                }
                .to_string(),
                blockheight: o.blockheight,
                reserved: o.reserved,
            })
            .collect();

        let channels: Vec<FundsChannel> = r
            .channels
            .iter()
            .map(|ch| FundsChannel {
                peer_id: hexs(&ch.peer_id),
                our_amount_msat: msat_of(&ch.our_amount_msat).unwrap_or(0),
                amount_msat: msat_of(&ch.amount_msat).unwrap_or(0),
                funding_txid: hexs(&ch.funding_txid),
                funding_output: ch.funding_output,
                connected: ch.connected,
                state: channel_state_name(ch.state),
                short_channel_id: ch.short_channel_id.clone(),
                channel_id: hexs(&ch.channel_id),
            })
            .collect();

        let onchain_confirmed_msat = outputs
            .iter()
            .filter(|o| o.status == "confirmed")
            .map(|o| o.amount_msat)
            .sum();
        let onchain_unconfirmed_msat = outputs
            .iter()
            .filter(|o| o.status == "unconfirmed" || o.status == "immature")
            .map(|o| o.amount_msat)
            .sum();
        // Liquidity: only usable (normal-state) channels count.
        let outbound_msat = channels
            .iter()
            .filter(|c| c.state == "CHANNELD_NORMAL")
            .map(|c| c.our_amount_msat)
            .sum();
        let inbound_msat = channels
            .iter()
            .filter(|c| c.state == "CHANNELD_NORMAL")
            .map(|c| c.amount_msat.saturating_sub(c.our_amount_msat))
            .sum();

        Ok(Funds {
            outputs,
            channels,
            onchain_confirmed_msat,
            onchain_unconfirmed_msat,
            outbound_msat,
            inbound_msat,
        })
    }

    // -- On-chain ----------------------------------------------------------

    /// addresstype: "bech32" | "p2tr" | "all" (None = node default)
    pub async fn new_address(&self, addresstype: Option<String>) -> CoreResult<NewAddressResult> {
        let t = match addresstype.as_deref().map(str::to_lowercase).as_deref() {
            None => None,
            Some("bech32") => Some(0),
            Some("all") => Some(2),
            Some("p2tr") => Some(3),
            Some(other) => {
                return Err(CoreError::Msg(format!("unknown address type: {other}")))
            }
        };
        let mut c = self.client.lock().await.clone();
        let r = c
            .new_addr(cln::NewaddrRequest { addresstype: t })
            .await
            .map_err(rpc_err)?
            .into_inner();
        Ok(NewAddressResult {
            bech32: r.bech32,
            p2tr: r.p2tr,
        })
    }

    pub async fn withdraw(&self, params: WithdrawParams) -> CoreResult<WithdrawResult> {
        let req = cln::WithdrawRequest {
            destination: params.destination.clone(),
            satoshi: Some(amount_or_all(
                if params.all { 0 } else { checked_msat(params.amount_sat)? },
                params.all,
            )),
            minconf: params.minconf,
            utxos: vec![],
            feerate: opt_feerate(&params.feerate)?,
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.withdraw(req).await.map_err(rpc_err)?.into_inner();
        Ok(WithdrawResult {
            txid: hexs(&r.txid),
            tx: hexs(&r.tx),
            psbt: r.psbt,
        })
    }

    // -- Peers & channels --------------------------------------------------

    pub async fn connect_peer(
        &self,
        id: String,
        host: Option<String>,
        port: Option<u32>,
    ) -> CoreResult<ConnectPeerResult> {
        let mut c = self.client.lock().await.clone();
        let r = c
            .connect_peer(cln::ConnectRequest { id, host, port })
            .await
            .map_err(rpc_err)?
            .into_inner();
        Ok(ConnectPeerResult {
            id: hexs(&r.id),
            direction: match r.direction {
                0 => "in",
                1 => "out",
                _ => "unknown",
            }
            .to_string(),
            address: r.address.and_then(|a| a.address),
        })
    }

    pub async fn disconnect_peer(&self, id: String, force: Option<bool>) -> CoreResult<()> {
        let id = hex::decode(&id).map_err(|e| CoreError::Msg(format!("bad peer id: {e}")))?;
        let mut c = self.client.lock().await.clone();
        c.disconnect(cln::DisconnectRequest { id, force })
            .await
            .map_err(rpc_err)?;
        Ok(())
    }

    /// askrene getroutes — dry-run a payment's routing without paying.
    pub async fn get_routes(&self, params: GetRoutesParams) -> CoreResult<RoutesResult> {
        let mut c = self.client.lock().await.clone();
        let source = match &params.source {
            Some(s) => hex::decode(s.trim())
                .map_err(|e| CoreError::Msg(format!("bad source node id: {e}")))?,
            None => {
                c.getinfo(cln::GetinfoRequest::default())
                    .await
                    .map_err(rpc_err)?
                    .into_inner()
                    .id
            }
        };
        let destination = hex::decode(params.destination.trim())
            .map_err(|e| CoreError::Msg(format!("bad destination node id: {e}")))?;
        let r = c
            .get_routes(cln::GetroutesRequest {
                source,
                destination,
                amount_msat: Some(cln::Amount { msat: params.amount_msat }),
                layers: params.layers.clone(),
                maxfee_msat: Some(cln::Amount { msat: params.maxfee_msat }),
                final_cltv: params.final_cltv.unwrap_or_default(),
                maxdelay: params.maxdelay,
                ..Default::default()
            })
            .await
            .map_err(rpc_err)?
            .into_inner();
        Ok(RoutesResult {
            probability_ppm: r.probability_ppm,
            routes: r
                .routes
                .iter()
                .map(|rt| Route {
                    probability_ppm: rt.probability_ppm,
                    amount_msat: rt.amount_msat.as_ref().map(|a| a.msat).unwrap_or(0),
                    final_cltv: Some(rt.final_cltv),
                    path: rt
                        .path
                        .iter()
                        .map(|h| RouteHop {
                            short_channel_id_dir: Some(h.short_channel_id_dir.clone()),
                            next_node_id: hexs(h.node_id_out.as_deref().unwrap_or_default()),
                            amount_msat: h.amount_in_msat.as_ref().map(|a| a.msat).unwrap_or(0),
                            delay: h.cltv_out.unwrap_or_default(),
                        })
                        .collect(),
                })
                .collect(),
        })
    }

    pub async fn list_channels(&self) -> CoreResult<Vec<PeerChannel>> {
        let mut c = self.client.lock().await.clone();
        let r = c
            .list_peer_channels(cln::ListpeerchannelsRequest::default())
            .await
            .map_err(rpc_err)?
            .into_inner();
        // Resolve peer aliases from gossip — one listnodes per unique peer,
        // best-effort (a peer missing from our gossip store just has no alias).
        let mut aliases: std::collections::HashMap<Vec<u8>, Option<String>> =
            std::collections::HashMap::new();
        for ch in &r.channels {
            if aliases.contains_key(&ch.peer_id) {
                continue;
            }
            let alias = c
                .list_nodes(cln::ListnodesRequest {
                    id: Some(ch.peer_id.clone()),
                    ..Default::default()
                })
                .await
                .ok()
                .and_then(|n| n.into_inner().nodes.into_iter().next())
                .and_then(|n| n.alias)
                .filter(|a| !a.trim().is_empty());
            aliases.insert(ch.peer_id.clone(), alias);
        }
        Ok(r.channels
            .iter()
            .map(|ch| {
                let to_us = msat_of(&ch.to_us_msat);
                let total = msat_of(&ch.total_msat);
                PeerChannel {
                    peer_id: hexs(&ch.peer_id),
                    peer_alias: aliases.get(&ch.peer_id).cloned().flatten(),
                    peer_connected: ch.peer_connected,
                    state: channel_state_name(ch.state),
                    channel_id: ch.channel_id.as_ref().map(|b| hexs(b)),
                    short_channel_id: ch.short_channel_id.clone(),
                    funding_txid: ch.funding_txid.as_ref().map(|b| hexs(b)),
                    funding_outnum: ch.funding_outnum,
                    opener: match ch.opener {
                        0 => "local",
                        1 => "remote",
                        _ => "unknown",
                    }
                    .to_string(),
                    is_private: ch.private,
                    to_us_msat: to_us,
                    total_msat: total,
                    to_them_msat: match (total, to_us) {
                        (Some(t), Some(u)) => Some(t.saturating_sub(u)),
                        _ => None,
                    },
                    spendable_msat: msat_of(&ch.spendable_msat),
                    receivable_msat: msat_of(&ch.receivable_msat),
                    our_reserve_msat: msat_of(&ch.our_reserve_msat),
                    their_reserve_msat: msat_of(&ch.their_reserve_msat),
                    fee_base_msat: msat_of(&ch.fee_base_msat),
                    fee_proportional_millionths: ch.fee_proportional_millionths,
                    dust_limit_msat: msat_of(&ch.dust_limit_msat),
                    our_to_self_delay: ch.our_to_self_delay,
                    their_to_self_delay: ch.their_to_self_delay,
                    max_accepted_htlcs: ch.max_accepted_htlcs,
                    htlc_count: ch.htlcs.len() as u32,
                    in_payments_fulfilled: ch.in_payments_fulfilled,
                    in_fulfilled_msat: msat_of(&ch.in_fulfilled_msat),
                    out_payments_fulfilled: ch.out_payments_fulfilled,
                    out_fulfilled_msat: msat_of(&ch.out_fulfilled_msat),
                    alias_local: ch.alias.as_ref().and_then(|a| a.local.clone()),
                    alias_remote: ch.alias.as_ref().and_then(|a| a.remote.clone()),
                    status: ch.status.clone(),
                    last_stable_connection: ch.last_stable_connection,
                }
            })
            .collect())
    }

    pub async fn open_channel(&self, params: OpenChannelParams) -> CoreResult<OpenChannelResult> {
        let id = hex::decode(&params.peer_id)
            .map_err(|e| CoreError::Msg(format!("bad peer id: {e}")))?;
        let req = cln::FundchannelRequest {
            id,
            amount: Some(amount_or_all(
                if params.all { 0 } else { checked_msat(params.amount_sat)? },
                params.all,
            )),
            feerate: opt_feerate(&params.feerate)?,
            announce: params.announce,
            push_msat: params.push_msat.map(amount),
            close_to: params.close_to.clone(),
            minconf: params.minconf,
            mindepth: params.mindepth,
            reserve: params.reserve_msat.map(amount),
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.fund_channel(req).await.map_err(rpc_err)?.into_inner();
        Ok(OpenChannelResult {
            txid: hexs(&r.txid),
            outnum: r.outnum,
            channel_id: hexs(&r.channel_id),
            close_to: r.close_to.as_ref().map(|b| hexs(b)),
            mindepth: r.mindepth,
        })
    }

    pub async fn close_channel(
        &self,
        params: CloseChannelParams,
    ) -> CoreResult<CloseChannelResult> {
        let req = cln::CloseRequest {
            id: params.id.clone(),
            unilateraltimeout: params.unilateral_timeout,
            destination: params.destination.clone(),
            fee_negotiation_step: params.fee_negotiation_step.clone(),
            wrong_funding: None,
            force_lease_closed: params.force_lease_closed,
            feerange: vec![],
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.close(req).await.map_err(rpc_err)?.into_inner();
        Ok(CloseChannelResult {
            close_type: match r.item_type {
                0 => "mutual",
                1 => "unilateral",
                2 => "unopened",
                _ => "unknown",
            }
            .to_string(),
            txids: r
                .txids
                .iter()
                .filter(|t| t.len() == 32)
                .map(|t| hexs(t))
                .collect(),
        })
    }

    // -- Invoices ----------------------------------------------------------

    pub async fn create_invoice(
        &self,
        params: CreateInvoiceParams,
    ) -> CoreResult<CreateInvoiceResult> {
        let preimage = match &params.preimage_hex {
            Some(p) if !p.is_empty() => Some(
                hex::decode(p).map_err(|e| CoreError::Msg(format!("bad preimage: {e}")))?,
            ),
            _ => None,
        };
        let req = cln::InvoiceRequest {
            amount_msat: Some(amount_or_any(params.amount_msat)),
            description: params.description.clone(),
            label: params.label.clone(),
            expiry: params.expiry,
            fallbacks: params.fallbacks.clone(),
            preimage,
            cltv: params.cltv,
            deschashonly: params.deschashonly,
            exposeprivatechannels: params.exposeprivatechannels.clone(),
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.invoice(req).await.map_err(rpc_err)?.into_inner();
        let warning = r
            .warning_capacity
            .or(r.warning_offline)
            .or(r.warning_deadends)
            .or(r.warning_private_unused)
            .or(r.warning_mpp);
        Ok(CreateInvoiceResult {
            bolt11: r.bolt11,
            payment_hash: hexs(&r.payment_hash),
            payment_secret: hexs(&r.payment_secret),
            expires_at: r.expires_at,
            created_index: r.created_index,
            warning,
        })
    }

    pub async fn list_invoices(
        &self,
        params: ListInvoicesParams,
    ) -> CoreResult<Vec<InvoiceEntry>> {
        let payment_hash = match &params.payment_hash_hex {
            Some(h) if !h.is_empty() => Some(
                hex::decode(h).map_err(|e| CoreError::Msg(format!("bad payment hash: {e}")))?,
            ),
            _ => None,
        };
        let index = match params.index.as_deref() {
            Some("updated") => Some(1),
            Some("created") => Some(0),
            _ => None,
        };
        let req = cln::ListinvoicesRequest {
            label: params.label.clone(),
            invstring: params.invstring.clone(),
            payment_hash,
            offer_id: None,
            index,
            start: params.start,
            limit: params.limit,
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.list_invoices(req).await.map_err(rpc_err)?.into_inner();
        Ok(r.invoices
            .iter()
            .map(|i| InvoiceEntry {
                label: i.label.clone(),
                description: i.description.clone(),
                payment_hash: hexs(&i.payment_hash),
                status: match i.status {
                    0 => "unpaid",
                    1 => "paid",
                    2 => "expired",
                    _ => "unknown",
                }
                .to_string(),
                expires_at: i.expires_at,
                amount_msat: msat_of(&i.amount_msat),
                amount_received_msat: msat_of(&i.amount_received_msat),
                bolt11: i.bolt11.clone(),
                bolt12: i.bolt12.clone(),
                pay_index: i.pay_index,
                paid_at: i.paid_at,
                payment_preimage: i.payment_preimage.as_ref().map(|b| hexs(b)),
                created_index: i.created_index,
                updated_index: i.updated_index,
            })
            .collect())
    }

    // -- Paying ------------------------------------------------------------

    // `PayRequest`'s fields are marked deprecated in favor of `xpay`/
    // `rene_pay` on ember's (newer CLN) proto, but `pay` is still a real,
    // supported RPC — not calling it isn't an option here, so the warning
    // is silenced rather than worked around.
    #[allow(deprecated)]
    pub async fn pay(&self, params: PayParams) -> CoreResult<PayResult> {
        let req = cln::PayRequest {
            bolt11: params.bolt11.clone(),
            amount_msat: params.amount_msat.map(amount),
            label: params.label.clone(),
            riskfactor: params.riskfactor,
            maxfeepercent: params.maxfeepercent,
            retry_for: params.retry_for,
            maxdelay: params.maxdelay,
            exemptfee: params.exemptfee_msat.map(amount),
            maxfee: params.maxfee_msat.map(amount),
            description: params.description.clone(),
            exclude: params.exclude.clone(),
            partial_msat: params.partial_msat.map(amount),
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.pay(req).await.map_err(rpc_err)?.into_inner();
        Ok(PayResult {
            payment_preimage: hexs(&r.payment_preimage),
            payment_hash: hexs(&r.payment_hash),
            destination: r.destination.as_ref().map(|b| hexs(b)),
            created_at: r.created_at,
            parts: r.parts,
            amount_msat: msat_of(&r.amount_msat).unwrap_or(0),
            amount_sent_msat: msat_of(&r.amount_sent_msat).unwrap_or(0),
            status: match r.status {
                0 => "complete",
                1 => "pending",
                2 => "failed",
                _ => "unknown",
            }
            .to_string(),
            warning_partial_completion: r.warning_partial_completion,
        })
    }

    pub async fn xpay(&self, params: XpayParams) -> CoreResult<XpayResult> {
        let req = cln::XpayRequest {
            invstring: params.invstring.clone(),
            amount_msat: params.amount_msat.map(amount),
            maxfee: params.maxfee_msat.map(amount),
            layers: params.layers.clone(),
            retry_for: params.retry_for,
            partial_msat: params.partial_msat.map(amount),
            maxdelay: params.maxdelay,
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.xpay(req).await.map_err(rpc_err)?.into_inner();
        Ok(XpayResult {
            payment_preimage: hexs(&r.payment_preimage),
            failed_parts: r.failed_parts,
            successful_parts: r.successful_parts,
            amount_msat: msat_of(&r.amount_msat).unwrap_or(0),
            amount_sent_msat: msat_of(&r.amount_sent_msat).unwrap_or(0),
        })
    }

    // See the note on `pay` above — `RenepayRequest`'s fields are
    // similarly deprecated-but-supported on ember's proto.
    #[allow(deprecated)]
    pub async fn rene_pay(&self, params: RenepayParams) -> CoreResult<RenepayResult> {
        let req = cln::RenepayRequest {
            invstring: params.invstring.clone(),
            amount_msat: params.amount_msat.map(amount),
            maxfee: params.maxfee_msat.map(amount),
            maxdelay: params.maxdelay,
            retry_for: params.retry_for,
            description: params.description.clone(),
            label: params.label.clone(),
            exclude: params.exclude.clone(),
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.rene_pay(req).await.map_err(rpc_err)?.into_inner();
        Ok(RenepayResult {
            payment_preimage: hexs(&r.payment_preimage),
            payment_hash: hexs(&r.payment_hash),
            created_at: r.created_at,
            parts: r.parts,
            amount_msat: msat_of(&r.amount_msat).unwrap_or(0),
            amount_sent_msat: msat_of(&r.amount_sent_msat).unwrap_or(0),
            status: match r.status {
                0 => "complete",
                1 => "pending",
                2 => "failed",
                _ => "unknown",
            }
            .to_string(),
            bolt11: r.bolt11.clone(),
            bolt12: r.bolt12.clone(),
            groupid: r.groupid,
        })
    }

    pub async fn list_pays(&self, params: ListPaysParams) -> CoreResult<Vec<PayListEntry>> {
        let payment_hash = match &params.payment_hash_hex {
            Some(h) if !h.is_empty() => Some(
                hex::decode(h).map_err(|e| CoreError::Msg(format!("bad payment hash: {e}")))?,
            ),
            _ => None,
        };
        let status = match params.status.as_deref() {
            Some("pending") => Some(0),
            Some("complete") => Some(1),
            Some("failed") => Some(2),
            _ => None,
        };
        let index = match params.index.as_deref() {
            Some("updated") => Some(1),
            Some("created") => Some(0),
            _ => None,
        };
        let req = cln::ListpaysRequest {
            bolt11: params.bolt11.clone(),
            payment_hash,
            status,
            index,
            start: params.start,
            limit: params.limit,
            ..Default::default()
        };
        let mut c = self.client.lock().await.clone();
        let r = c.list_pays(req).await.map_err(rpc_err)?.into_inner();
        Ok(r.pays
            .iter()
            .map(|p| PayListEntry {
                payment_hash: hexs(&p.payment_hash),
                // NB: ListpaysPaysStatus has a different order than PayStatus.
                status: match p.status {
                    0 => "pending",
                    1 => "failed",
                    2 => "complete",
                    _ => "unknown",
                }
                .to_string(),
                destination: p.destination.as_ref().map(|b| hexs(b)),
                created_at: p.created_at,
                completed_at: p.completed_at,
                label: p.label.clone(),
                bolt11: p.bolt11.clone(),
                bolt12: p.bolt12.clone(),
                // xpay does not persist the invoice description the way `pay`
                // does, so listpays often returns none — recover it from the
                // stored bolt11 instead (bolt12: the description is in the
                // local invoice copy, nothing to parse here).
                description: p
                    .description
                    .clone()
                    .filter(|d| !d.is_empty())
                    .or_else(|| p.bolt11.as_deref().and_then(bolt11_description)),
                amount_msat: msat_of(&p.amount_msat),
                amount_sent_msat: msat_of(&p.amount_sent_msat),
                preimage: p.preimage.as_ref().map(|b| hexs(b)),
                number_of_parts: p.number_of_parts,
                created_index: p.created_index,
                updated_index: p.updated_index,
            })
            .collect())
    }

    /// On-chain wallet transaction history, in the node's own order.
    pub async fn list_transactions(&self) -> CoreResult<Vec<WalletTx>> {
        let mut c = self.client.lock().await.clone();
        let r = c
            .list_transactions(cln::ListtransactionsRequest {})
            .await
            .map_err(rpc_err)?
            .into_inner();
        Ok(r.transactions
            .iter()
            .map(|t| WalletTx {
                txid: hexs(&t.hash),
                blockheight: t.blockheight,
                inputs: t
                    .inputs
                    .iter()
                    .map(|i| WalletTxInput {
                        txid: hexs(&i.txid),
                        index: i.index,
                    })
                    .collect(),
                outputs: t
                    .outputs
                    .iter()
                    .map(|o| WalletTxOutput {
                        index: o.index,
                        amount_msat: o.amount_msat.as_ref().map(|a| a.msat).unwrap_or(0),
                    })
                    .collect(),
            })
            .collect())
    }

    // -- Decode ------------------------------------------------------------

    pub async fn decode(&self, invstring: String) -> CoreResult<DecodedInvoice> {
        let mut c = self.client.lock().await.clone();
        let r = c
            .decode(cln::DecodeRequest { string: invstring })
            .await
            .map_err(rpc_err)?
            .into_inner();
        Ok(DecodedInvoice {
            item_type: match r.item_type {
                0 => "bolt12_offer",
                1 => "bolt12_invoice",
                2 => "bolt12_invoice_request",
                3 => "bolt11_invoice",
                4 => "rune",
                5 => "emergency_recover",
                _ => "unknown",
            }
            .to_string(),
            valid: r.valid,
            currency: r.currency.clone(),
            amount_msat: msat_of(&r.amount_msat),
            description: r.description.clone(),
            payee: r.payee.as_ref().map(|b| hexs(b)),
            payment_hash: r.payment_hash.as_ref().map(|b| hexs(b)),
            created_at: r.created_at,
            expiry: r.expiry,
            min_final_cltv_expiry: r.min_final_cltv_expiry,
            offer_description: r.offer_description.clone(),
            offer_issuer: r.offer_issuer.clone(),
            invoice_amount_msat: msat_of(&r.invoice_amount_msat),
        })
    }
}
