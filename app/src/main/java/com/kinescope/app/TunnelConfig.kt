package com.kinescope.app

/**
 * Patch 57: the configuration text of the vendored `hev-socks5-tunnel` for the YouTube tunnel. Pure, so a test can pin it.
 *
 * The tunnel is IPv4-only, the way ByeByeDPI's default is: `VpnService.Builder` documents that a VPN with no address, route or DNS
 * server of one family blocks that family for the apps it carries (verified in the framework source, `VpnService.Builder.allowFamily`),
 * so the app falls back to IPv4 at once instead of trying an IPv6 path that the engine may not be able to use. `ipv6` is optional in
 * hev (an absent key means it configures no IPv6 address: `hev_config_get_tunnel_ipv6_address` returns NULL, read from tag 2.17.1),
 * so none is written; the TUN built in `BypassVpnService.buildAppTunnel` has no IPv6 address, route or DNS server either.
 */
internal fun hevTunnelConfig(port: Int, mtu: Int): String =
    """
    tunnel:
      name: tun0
      mtu: $mtu
      ipv4: 198.18.0.1
      icmp: 'off'
    socks5:
      port: $port
      address: 127.0.0.1
      udp: 'udp'
    misc:
      task-stack-size: 86016
      tcp-buffer-size: 65536
      max-session-count: 512
      log-file: null
      log-level: warn
    """.trimIndent() + "\n"
