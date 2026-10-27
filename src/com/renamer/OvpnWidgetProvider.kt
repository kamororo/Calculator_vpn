package com.renamer

/** Home-screen widget for the OpenVPN section. State comes from
 *  prefs "renamer_widget" key "widget_state_openvpn" (the parent
 *  must update it from OpenVpnActivity); tapping the widget opens
 *  OpenVpnActivity with widget_auto_connect=true, because the
 *  OpenVPN engine needs the activity permission flow. */
class OvpnWidgetProvider : WidgetProviderBase() {
    override fun sectionKey(): String = "openvpn"
    override fun sectionInitial(): String = "O"
    override fun sectionDisplayName(): String = "OpenVPN"
    override fun iconRes(): Int = R.drawable.widget_icon_openvpn
    override fun targetActivity(): Class<*> = OpenVpnActivity::class.java
}
