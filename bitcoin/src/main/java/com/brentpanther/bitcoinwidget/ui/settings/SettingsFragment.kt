package com.brentpanther.bitcoinwidget.ui.settings

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.*
import com.brentpanther.bitcoinwidget.*
import com.brentpanther.bitcoinwidget.R
import com.brentpanther.bitcoinwidget.db.Widget
import com.brentpanther.bitcoinwidget.db.WidgetDatabase
import com.brentpanther.bitcoinwidget.exchange.Exchange
import com.brentpanther.bitcoinwidget.exchange.ExchangeData
import com.brentpanther.bitcoinwidget.exchange.ExchangeHelper
import com.brentpanther.bitcoinwidget.receiver.WidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DecimalFormat
import java.util.*

class SettingsFragment : PreferenceFragmentCompat(), SettingsDialogFragment.NoticeDialogListener {

    private val viewModel: SettingsViewModel by activityViewModels()
    private lateinit var data: ExchangeData
    private var checkDataSaver: Boolean = true
    private var checkBatterySaver: Boolean = true

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        lifecycleScope.launch(Dispatchers.Main) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.exchangeDataFlow.collect { (widget, data) ->
                    updateWidget(data, widget, rootKey)
                }
            }
        }
    }

    private fun updateWidget(data: ExchangeData, widget: Widget?, rootKey: String?) {
        this@SettingsFragment.data = data
        val defaultCurrency = data.defaultCurrency
        if (defaultCurrency == null) {
            Toast.makeText(context, R.string.error_no_currencies, Toast.LENGTH_LONG).show()
            requireActivity().finish()
            return
        }
        val defaultExchange = data.getDefaultExchange(defaultCurrency)

        viewModel.widget = viewModel.widget ?: widget ?: Widget(
            0,
            widgetId = viewModel.widgetId,
            exchange = Exchange.valueOf(defaultExchange),
            coin = data.coinEntry.coin,
            currency = defaultCurrency,
            coinCustomName = data.getExchangeCoinName(defaultExchange),
            currencyCustomName = null,
            showLabel = false,
            showIcon = true,
            showDecimals = false,
            currencySymbol = null,
            theme = Theme.LIGHT,
            unit = data.coinEntry.coin.getUnits().firstOrNull()?.text,
            customIcon = data.coinEntry.iconUrl?.substringBefore("/"),
            lastUpdated = 0
        )
        viewModel.widget?.let {
            preferenceManager.preferenceDataStore = WidgetDataStore(it)
        }
        setPreferencesFromResource(R.xml.preferences, rootKey)
        loadPreferences()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.saveWidgetFlow.observe(viewLifecycleOwner) {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.widget?.let {
                    it.lastUpdated = 0
                    WidgetDatabase.getInstance(requireContext()).widgetDao().insert(it)
                }
            }
            WidgetProvider.refreshWidgets(requireActivity(), listOf(viewModel.widgetId))
            requireActivity().setResult(Activity.RESULT_OK)
            requireActivity().finish()
        }
    }

    private fun loadPreferences() {
        findPreference<ListPreference>(getString(R.string.key_currency))?.apply {
            entries = data.currencies
            entryValues = data.currencies
        }
        updateExchangeValues()
        if (viewModel.widget?.unit != null) {
            val unitNames = data.coinEntry.coin.getUnits().map { it.text }.toTypedArray()
            findPreference<ListPreference>(getString(R.string.key_units))?.apply {
                isVisible = true
                entries = unitNames
                entryValues = unitNames
            }
        }
        viewModel.updateWidget(true)
    }

    private fun updateExchangeValues() {
        findPreference<ListPreference>(getString(R.string.key_exchange))?.apply {
            viewModel.widget?.let { widget ->
                entryValues = data.getExchanges(widget.currency)
                entries = entryValues.map { Exchange.valueOf(it.toString()).exchangeName }.toTypedArray()
                value = data.getDefaultExchange(widget.currency)
            }
        }
    }

    inner class WidgetDataStore(val widget: Widget) : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when(key) {
                "icon" -> widget.showIcon
                "decimals" -> widget.showDecimals
                "label" -> widget.showLabel
                else -> throw IllegalArgumentException()
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            val updatePrice = when(key) {
                "icon" -> {
                    widget.showIcon = value
                    false
                }
                "decimals" -> {
                    widget.showDecimals = value
                    true
                }
                "label" -> {
                    widget.showLabel = value
                    false
                }
                else -> false
            }
            viewModel.updateWidget(updatePrice)
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when(key) {
                "currency" -> widget.currency
                "symbol" -> {
                    when(widget.currencySymbol) {
                        null -> "ISO"
                        "none" -> "NONE"
                        else -> "LOCAL"
                    }
                }
                "exchange" -> widget.exchange.name
                "units" -> widget.unit
                "theme" -> widget.theme.name
                else -> throw IllegalArgumentException()
            }
        }

        override fun putString(key: String?, value: String?) {
            val updatePrice = when(key) {
                "currency" -> {
                    widget.currency = value ?: widget.currency
                    updateExchangeValues()
                    true
                }
                "symbol" -> {
                    widget.currencySymbol = when(value) {
                        "ISO" -> null
                        "NONE" -> "none"
                        else -> getLocalSymbol(widget.currency)
                    }
                    true
                }
                "exchange" -> {
                    widget.exchange = Exchange.valueOf(value ?: Exchange.COINGECKO.name)
                    true
                }
                "units" -> {
                    widget.unit = value
                    true
                }
                "theme" -> {
                    widget.theme = Theme.valueOf(value ?: Theme.LIGHT.name)
                    if (widget.theme in listOf(Theme.DAY_NIGHT, Theme.TRANSPARENT_DAY_NIGHT)) {
                        val service = requireActivity().getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
                        service.nightMode = UiModeManager.MODE_NIGHT_AUTO
                    }
                    false
                }
                else -> false
            }
            viewModel.updateWidget(updatePrice)
        }
    }


    private fun getLocalSymbol(currencyCode: String): String? {
        val locale = Locale.getAvailableLocales().filter {
            // find all locales that match this currency code
            try {
                Currency.getInstance(it).currencyCode == currencyCode
            } catch (ignored: Exception) {
                false
            }
        }.minByOrNull {
            // pick the best currency symbol, which is probably the one that does not match the ISO symbol
            val symbols = (DecimalFormat.getCurrencyInstance(it) as DecimalFormat).decimalFormatSymbols
            if (symbols.currencySymbol == symbols.internationalCurrencySymbol) 1 else 0
        } ?: return null
        return (DecimalFormat.getCurrencyInstance(locale) as DecimalFormat).decimalFormatSymbols.currencySymbol
    }

    private fun downloadCustomIcon(customIcon: String?) = CoroutineScope(Dispatchers.IO).launch {
        if (customIcon == null) return@launch
        data.coinEntry.getFullIconUrl()?.let {
            val dir = File(requireContext().filesDir, "icons")
            if (!dir.exists()) {
                dir.mkdir()
            }
            val file = File(dir, customIcon)
            if (file.exists()) {
                return@launch
            }

            val os = ByteArrayOutputStream()
            val stream = ExchangeHelper.getStream(it)
            val image = BitmapFactory.decodeStream(stream)
            image.compress(Bitmap.CompressFormat.PNG, 100, os)
            file.writeBytes(os.toByteArray())
        }
    }

    //TODO: move these checks
    private fun checkBatterySaver(): Boolean {
        return if (NetworkStatusHelper.checkBattery(requireContext()) > 0) {
            // user has battery saver on, warn that widget will be affected
            val dialogFragment = SettingsDialogFragment.newInstance(
                R.string.title_warning,
                R.string.warning_battery_saver,
                CODE_BATTERY_SAVER,
                false
            )
            dialogFragment.show(parentFragmentManager, "dialog")
            false
        } else true
    }

    private fun checkDataSaver(): Boolean {
        return if (NetworkStatusHelper.checkBackgroundData(requireContext()) > 0) {
            // user has data saver on, show dialog asking for permission to whitelist
            val dialogFragment =
                SettingsDialogFragment.newInstance(R.string.title_warning, R.string.warning_data_saver, CODE_DATA_SAVER)
            dialogFragment.show(parentFragmentManager, "dialog")
            false
        } else true
    }

    override fun onDialogPositiveClick(code: Int) {
        (parentFragmentManager.findFragmentByTag("dialog") as DialogFragment).dismissAllowingStateLoss()
        when (code) {
            CODE_BATTERY_SAVER -> checkBatterySaver = false
            CODE_DATA_SAVER -> checkDataSaver = false
        }
        viewModel.save()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onDialogNegativeClick() {
        startActivity(
            Intent(
                Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                Uri.parse("package:" + requireActivity().packageName)
            )
        )
    }

    companion object {

        private const val CODE_DATA_SAVER = 1
        private const val CODE_BATTERY_SAVER = 2

    }

}

