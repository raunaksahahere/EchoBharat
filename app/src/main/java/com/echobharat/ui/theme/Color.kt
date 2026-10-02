package com.echobharat.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Light, calm palette with a single saffron accent (matching the "iT" launcher icon).
 *
 * Deliberately light rather than the previous dark theme: someone reaching for this app
 * is often already frightened, and a cramped dark screen covered in alert icons reads as
 * alarming. Colour is spent sparingly, so that when something *is* urgent — a distress
 * announcement — it stands out instead of competing with the chrome.
 */

// Surfaces
val SurfaceBg = Color(0xFFF4F1EC)          // warm off-white app background
val SurfaceCard = Color(0xFFFFFFFF)        // cards, app bar, incoming bubbles
val SurfaceVariantBg = Color(0xFFEDEFF2)   // inputs, chips, inactive fills
val BorderSubtle = Color(0xFFE0E3E7)

// Accent — saffron carries brand identity; the deep variant is for text and icons,
// where the bright fill tone would fail contrast against white.
val AccentSaffron = Color(0xFFF57C1F)
val AccentSaffronDeep = Color(0xFFB4530A)

val AccentEmerald = Color(0xFF0E8A5F)
val AccentChakra = Color(0xFF2563EB)
val AccentCyan = Color(0xFF0E7490)
val AccentAlert = Color(0xFFD32F2F)

// Text
val TextPrimary = Color(0xFF14181C)
val TextSecondary = Color(0xFF54656F)
val TextMuted = Color(0xFF8A97A0)

// Message bubbles
val BubbleMine = Color(0xFFFFF0DF)         // faint saffron tint for own messages
val BubbleTheirs = Color(0xFFFFFFFF)

// Peer chips
val PeerBadgeBg = Color(0xFFEDEFF2)
val PeerBadgeBorder = Color(0xFFD4D9DE)
