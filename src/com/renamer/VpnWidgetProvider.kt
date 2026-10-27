package com.renamer

/** Home-screen widget for the v2ray section. Live state from
 *  VpnClientService.isRunning; direct connect/disconnect from the
 *  widget works once the VPN permission was granted at least once. */
class VpnWidgetProvider : WidgetProviderBase() {
    override fun sectionKey(): String = "v2ray"
    override fun sectionInitial(): String = "V"
    override fun sectionDisplayName(): String = "v2ray"
    override fun iconRes(): Int = R.drawable.widget_icon_v2ray
    override fun targetActivity(): Class<*> = VpnActivity::class.java
}
