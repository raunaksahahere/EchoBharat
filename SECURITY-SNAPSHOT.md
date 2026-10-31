# 1.4.1 — security-hardening work-in-progress snapshot

Published at the maintainer's request to stop implementation and preserve the current
branch. This is not completion of the six-item security plan, nor evidence that this
build is suitable for emergency/security-sensitive deployment. Earlier local commit
subjects describe implementation intent, not verified end-to-end guarantees.

## Included work and known gaps

1. **SOS v2 prototype**: binary envelope/signature code, an in-memory event ledger,
   preference-backed record storage, capability bit, receive-path checks and banner.
   The complete signed origin announcement is **not** carried in the envelope.
   The signing path uses IdentityManager rather than the transport's EncryptionService
   signing identity, so compatibility with announcement-key checks is not established.
   The UI's active-event map still indexes event IDs alone; persistent tombstones,
   revision retention, resource bounds, canonical decoding and downgrade rejection need
   further work. Legacy JSON can supply authentication-display fields; those fields
   must not be treated as proof. The current "Verified SOS" label is not a guarantee.
   Multi-hop, fragmentation and post-restart adversarial behavior are not phone-tested.
2. **Contact verification prototype**: signed-payload sharing/pasting and badge UI.
   No rendered/scannable QR or completed safety-number comparison flow; no camera
   permission added. Nickname disambiguation currently uses a short peer-ID suffix.
   Cached fingerprint semantics need separation from transport pins; key-change warnings
   are not blocking. Challenge/response completion and durable identity-change handling
   remain incomplete. Unit tests do not validate Android URI handling or the real UI.
3. **Panic wipe prototype**: long-press title, in-app confirmation and cleanup calls.
   Restart with a fresh transport identity, stopping in-flight writers, clearing SOS
   storage/caches, and preventing pre-wipe state from being republished are **not proven**.
   No dedicated panic-wipe regression tests or destructive device test were completed.
4. **App lock/screen protection**: not implemented.
5. **Anti-spam limits/notices**: not implemented.
6. **Cleanup/threat model**: not completed; existing documentation is not a claim that
   the above defenses meet their acceptance criteria.

## Scope and verification

- Branch derives from v1.4.0, not the uncommitted 1.4.1 voice-pack work on main.
- The original main checkout is not part of this snapshot.
- No Hugging Face upload was resumed for this release.
- Passing JVM tests establish only the cases exercised; they do not establish the
  security guarantees listed above. Release notes record the final test/build result.
- No new phone tests were performed for this snapshot; v1.2.0 interoperability has not
  been retested. Do not use a panic wipe on valuable device data as a smoke test.
- Release signing credentials were not copied into the worktree. Unsigned build outputs
  are not installable signed release artifacts.

## Resume

Return to item 1 before extending the prototypes: use the transport's signing operation,
attach and validate the original announcement, gate forwarding and all state mutations,
namespace by full identity fingerprint/event ID, and persist bounded replay state atomically.
Then repair contact verification and panic-wipe lifecycle before implementing items 4–6.
