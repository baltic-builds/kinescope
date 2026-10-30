package com.kinescope.app

/**
 * Patch 57: the real hosts a strategy is judged against besides the three of the light probe
 * ([DpiStrategySearch.DEFAULT_HOSTS]).
 *
 * The light probe asks about the YouTube site, its image host and a bare redirector. None of them is where the bytes of a
 * video come from: those are served by `rrN---sn-*.googlevideo.com` nodes, and a network's filter treats that name differently
 * from `www.youtube.com`. ByeByeDPI, whose strategy test the user's phone runs perfectly with, therefore tests its strategies against
 * about thirty real names of both kinds and scores each strategy by the share that answered in full. This is the same idea:
 * [SITE_HOSTS] are YouTube's own front-end, API and image names, [VIDEO_EDGE_HOSTS] are real video-server nodes.
 *
 * Hostnames only (public facts, no addresses, no paths). A node name belongs to one cache of one provider, so some of them do
 * not answer from every network at all; that costs every strategy the same, which is why the result is a ranking signal
 * ([StrategyResult.score], [DpiStrategySearch.ranked]) and never a gate. The bare apex names `googleapis.com` and
 * `googleusercontent.com` of the source list are left out: their certificates are not known to carry the apex name, and a
 * certificate mismatch would fail every strategy alike.
 */
internal object ProbeSites {
    /** YouTube's own site, API, image and signalling names. */
    val SITE_HOSTS: List<String> = listOf(
        "youtu.be",
        "youtube.com",
        "i.ytimg.com",
        "i9.ytimg.com",
        "yt3.ggpht.com",
        "yt4.ggpht.com",
        "jnn-pa.googleapis.com",
        "signaler-pa.youtube.com",
        "youtubei.googleapis.com",
        "manifest.googlevideo.com",
        "yt3.googleusercontent.com"
    )

    /** Real video-server nodes (`*.googlevideo.com`), the names a download's bytes come from. */
    val VIDEO_EDGE_HOSTS: List<String> = listOf(
        "rr1---sn-4axm-n8vs.googlevideo.com",
        "rr1---sn-gvnuxaxjvh-o8ge.googlevideo.com",
        "rr1---sn-ug5onuxaxjvh-p3ul.googlevideo.com",
        "rr1---sn-ug5onuxaxjvh-n8v6.googlevideo.com",
        "rr4---sn-q4flrnsl.googlevideo.com",
        "rr10---sn-gvnuxaxjvh-304z.googlevideo.com",
        "rr14---sn-n8v7kn7r.googlevideo.com",
        "rr16---sn-axq7sn76.googlevideo.com",
        "rr1---sn-8ph2xajvh-5xge.googlevideo.com",
        "rr1---sn-gvnuxaxjvh-5gie.googlevideo.com",
        "rr12---sn-gvnuxaxjvh-bvwz.googlevideo.com",
        "rr5---sn-n8v7knez.googlevideo.com",
        "rr1---sn-u5uuxaxjvhg0-ocje.googlevideo.com",
        "rr2---sn-q4fl6ndl.googlevideo.com",
        "rr5---sn-gvnuxaxjvh-n8vk.googlevideo.com",
        "rr4---sn-jvhnu5g-c35d.googlevideo.com",
        "rr1---sn-q4fl6n6y.googlevideo.com",
        "rr2---sn-hgn7ynek.googlevideo.com",
        "rr1---sn-xguxaxjvh-gufl.googlevideo.com"
    )

    /** Every host of the coverage probe: the site names first, then the nodes. */
    val ALL: List<String> = SITE_HOSTS + VIDEO_EDGE_HOSTS

    private val edgeSet: Set<String> = VIDEO_EDGE_HOSTS.toSet()

    fun isEdge(host: String): Boolean = host in edgeSet
}
