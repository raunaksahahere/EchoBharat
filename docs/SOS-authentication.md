# Relayed SOS origin authentication — proposed protocol, not implemented

## Current limitation (E04)

Private conversations are Noise encrypted and transport-attributed. SOS is deliberately
public. `EchoBharatMeshManager.attribute()` preserves an SOS payload's `senderId` because
application-level re-announcement wraps the original JSON in a new relay-signed packet.
The original identity is therefore self-asserted. A valid relay signature authenticates
the relay, **not the person named inside the SOS**. Sender-scoped cancellation tombstones
prevent bookkeeping collisions but do not repair this trust gap. Do not claim that only
the true origin can raise/update/cancel a relayed distress record.

## Safe design

Keep existing private messages and v1.2.0 BLE packet framing unchanged. Introduce a new,
versioned application SOS envelope; do not reinterpret legacy JSON as authenticated.

1. The origin signs a domain-separated binary body, for example
   `EchoBharat/SOS/v2\0 || bodyLength(u32be) || body`, with its existing Ed25519 key.
   Define an exact binary schema before implementation, not general JSON canonicalization:
   version, action (raise/update/cancel), random 128-bit event ID, monotonic revision,
   creation time, fixed event expiry, UTF-8 language/text/name/model fields with explicit
   byte-length prefixes, optional fixed-format coordinates/accuracy, and the complete
   origin public-key binding. Reject trailing data and noncanonical encodings.
2. Carry the original signed identity announcement binding the origin's Ed25519 and Noise
   keys. Reuse `AnnouncementIdentityValidator`'s verified identity/peer-ID derivation;
   never accept a payload peer ID or an arbitrary attached signing key as an existing
   peer's identity. Pin and expose key changes under the existing identity policy.
   Authentication proves a cryptographic identity, not a real person's name or truth of
   their location/emergency. New unknown identities must remain visibly unverified people.
3. Relays preserve the origin envelope bytes and signature exactly. The outer relay packet
   may change TTL/routing, but cannot change signed content. Receivers validate both packet
   policy and the inner origin envelope before indexing, speaking, storing or forwarding.
   A relay cannot sign an origin update, extend expiry, or issue its cancellation.
4. Key events by `(full origin key fingerprint, event ID)`, not display name, short peer ID,
   or event ID alone. Cancellation names that exact event and is signed by the same origin.
   Keep revision high-water marks and cancellation tombstones until signed expiry plus
   bounded clock skew; an older raise cannot resurrect a cancelled event. Enforce the
   one-hour maximum measured from signed creation and do not renew it on receipt.
5. A fresh location is a new origin-signed revision of the same event with the same expiry.
   Relays may retransmit it but cannot amend coordinates. Persist original envelopes and
   tombstones atomically so restart does not undo replay protection. Bound bytes, active
   origins/events, clock skew, revision arithmetic, signature-verification rate and storage;
   make overload visible rather than silently claiming emergency delivery.
6. Require signature validation before deduplication or tombstone mutation. Reject malformed,
   unsupported, mismatched-key, expired, oversized and downgrade-wrapped envelopes. Keep
   private-message keys/receipts and public SOS verification separate.

## v1.2.0 coexistence and rollout

- Leave v1.2.0 private conversations interoperable. Advertise SOS-v2 capability in a
  backwards-compatible capability extension and only send the new format where supported.
- Legacy SOS can remain visible in a separate **unauthenticated legacy SOS** namespace with
  a conspicuous warning. It must never mutate/cancel a verified-v2 event. Never display
  legacy reception as proof of origin authenticity.
- If a legacy compatibility copy is emitted, explicitly label it unauthenticated and link
  it only for display; do not let a legacy cancellation retire the verified original.
  Prefer explicit opt-in to legacy emergency interoperability over an automatic downgrade.
- Existing v1.2.0 peers cannot verify the new guarantee. A mixed-version mesh is not fully
  authenticated; a deployment requiring that guarantee must upgrade every participating
  endpoint. Avoid promising both unchanged legacy semantics and full origin security.

## Required acceptance tests before implementation is called complete

- Two/three phones: valid origin → relay → receiver with origin out of radio range;
  original signature survives fragmenting, forwarding, reconnect and store-and-forward.
- Wrong relay key, altered text/location/expiry/action/reference, substituted public key,
  shortened-ID collision and forged key binding all fail without changing state.
- Duplicate/reordered revisions, cancel-before-raise, post-cancel replay, restart, clock
  skew boundaries, stale event IDs and expiry overflow do not resurrect/extend distress.
- Legacy peers still exchange private messages; legacy SOS is never promoted to verified
  status and cannot cancel v2 records. Unknown versions and invalid signatures fail closed.
- Flood/resource tests prove bounded work, visible overload and isolation of valid events.

This document is the safe-design alternative requested for E04. The wire implementation
and adversarial multi-hop verification remain open; two-phone SOS smoke tests do not close it.
