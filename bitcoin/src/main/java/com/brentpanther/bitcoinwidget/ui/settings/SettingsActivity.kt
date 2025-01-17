package com.brentpanther.bitcoinwidget.ui.settings

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.brentpanther.bitcoinwidget.CoinEntry
import com.brentpanther.bitcoinwidget.R
import com.brentpanther.bitcoinwidget.WidgetApplication
import com.brentpanther.bitcoinwidget.WidgetState
import com.brentpanther.bitcoinwidget.databinding.LayoutSettingsBinding
import com.brentpanther.bitcoinwidget.db.Widget
import com.brentpanther.bitcoinwidget.strategy.data.WidgetDataStrategy
import com.brentpanther.bitcoinwidget.strategy.display.WidgetDisplayStrategy
import com.brentpanther.bitcoinwidget.strategy.presenter.PreviewWidgetPresenter
import com.brentpanther.bitcoinwidget.ui.settings.SettingsViewModel.DataState.Downloading
import com.brentpanther.bitcoinwidget.ui.settings.SettingsViewModel.DataState.Success
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var coin: CoinEntry
    private val viewModel by viewModels<SettingsViewModel>()
    private lateinit var binding: LayoutSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setResult(Activity.RESULT_CANCELED, intent)
        val extras = intent.extras!!
        viewModel.widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val widgetType = WidgetApplication.instance.getWidgetType(viewModel.widgetId)
        coin = extras.getParcelable(EXTRA_COIN) ?: throw IllegalArgumentException()
        if (extras.getBoolean(EXTRA_EDIT_WIDGET)) {
            title = getString(R.string.edit_widget, coin.name)
            binding.save.text = getString(R.string.settings_update)
        } else {
            title = getString(R.string.new_widget, coin.name)
            binding.save.text = getString(R.string.settings_create)
        }
        binding.textWidgetSummary.text = getString(widgetType.widgetSummary, coin.name)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.settingsData(coin, applicationContext).collect {
                    binding.apply {
                        when(it) {
                            is Downloading -> {
                                progress.isVisible = true
                                empty.isVisible = true
                            }
                            is Success -> {
                                supportFragmentManager.commit {
                                    replace(R.id.fragment_container_view,
                                    widgetType.getSettingsFragment())
                                }
                                progress.isVisible = false
                                empty.isVisible = false
                                labelPreview.isVisible = true
                                widgetPreview.previewLayout.isVisible = true
                                save.setOnClickListener { viewModel.save() }
                            }
                        }
                    }
                }
                viewModel.widgetPreviewFlow.collectLatest {
                    updateWidget(it.widget, it.refreshPrice)
                }
            }
        }
    }

    private suspend fun updateWidget(widget: Widget, refreshPrice: Boolean) {
        if (refreshPrice) {
            val strategy = WidgetDataStrategy.getStrategy(this, widget.widgetId)
            strategy.widget = widget
            strategy.loadData(manual = false, force = true)
        }
        if (widget.state != WidgetState.CURRENT) {
            widget.lastValue = null
        }
        val widgetPresenter = PreviewWidgetPresenter(widget, binding.widgetPreview)
        val displayStrategy = WidgetDisplayStrategy.getStrategy(this, widget, widgetPresenter)
        displayStrategy.refresh()
        val price = binding.root.findViewById<TextView>(R.id.price)
        TextViewCompat.setAutoSizeTextTypeWithDefaults(price, TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM)
    }

    companion object {

        const val EXTRA_EDIT_WIDGET = "edit_widget"
        const val EXTRA_COIN = "coin"
    }
}
