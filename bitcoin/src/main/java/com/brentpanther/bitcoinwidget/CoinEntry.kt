package com.brentpanther.bitcoinwidget

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CoinEntry(
    val id: String,
    val name: String,
    val symbol: String,
    val coin: Coin,
    val iconUrl: String? = null
) : Parcelable {

    fun getFullIconUrl() = if (iconUrl == null) null else "https://assets.coingecko.com/coins/images/$iconUrl"
}