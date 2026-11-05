package com.renamer

import com.wireguard.android.backend.Tunnel
import org.amnezia.awg.backend.Tunnel as AwgTunnel

/** Central record of the tunnel objects actually handed
 *  to the two GoBackends. Backend setState(DOWN) only
 *  stops its OWN current tunnel object (identity
 *  comparison, verified in both backends), so stopping
 *  "by name" with a freshly built object is a silent
 *  no-op: the old tunnel keeps running, and bringing
 *  the other engine's tunnel up next to it throws the
 *  app out without connecting (the 1.72 bug: tapping
 *  a WireGuard config while an Amnezia tunnel was up).
 *  Every place that brings a tunnel UP registers its
 *  object here; every place that brings one DOWN
 *  clears it. A stale entry is harmless: setState on
 *  an object that is no longer current is a no-op. */
object WgTunnelRegistry {
    @Volatile var wgTunnel: Tunnel? = null
    @Volatile var awgTunnel: AwgTunnel? = null
}
