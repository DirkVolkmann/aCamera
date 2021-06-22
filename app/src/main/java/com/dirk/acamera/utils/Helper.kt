package com.dirk.acamera.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.BulletSpan

fun buildBulletList(array: Array<out String>, gapWidth: Int = BulletSpan.STANDARD_GAP_WIDTH): CharSequence {
    val sStringBuilder = SpannableStringBuilder()
    array.forEach {
        val sString = SpannableString(it)
        sString.setSpan(BulletSpan(gapWidth), 0, sString.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        sStringBuilder.append(sString)
        sStringBuilder.append("\n")
    }
    sStringBuilder.delete(sStringBuilder.length - 1, sStringBuilder.length) // delete last "\n"
    return sStringBuilder
}

// TODO: Add support for IPv6 and/or hostname
fun getDeviceIp(context: Context): String {
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
}
