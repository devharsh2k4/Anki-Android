/*
 *  Copyright (c) 2023 Brayan Oliveira <brayandso.dev@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.previewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import com.ichi2.anki.CollectionHelper
import com.ichi2.anki.NoteEditor
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.previewer.PreviewerViewModel.Companion.stdHtml
import com.ichi2.themes.Themes
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

class PreviewerFragment : Fragment(), Toolbar.OnMenuItemClickListener {
    private lateinit var viewModel: PreviewerViewModel

    private val menu: Menu
        get() = requireView().findViewById<Toolbar>(R.id.toolbar).menu

    private val backsideOnlyOption: MenuItem
        get() = menu.findItem(R.id.action_back_side_only)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.previewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val selectedCardIds = requireArguments().getLongArray(CARD_IDS_EXTRA)!!
        val currentIndex = requireArguments().getInt(CURRENT_INDEX_EXTRA, 0)
        val mediaDir = File(CollectionHelper.getCurrentAnkiDroidDirectory(requireContext()), "collection.media").path

        viewModel = ViewModelProvider(
            requireActivity(),
            PreviewerViewModel.factory(mediaDir, selectedCardIds, currentIndex)
        )[PreviewerViewModel::class.java]

        val webView = view.findViewById<WebView>(R.id.webview)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        with(webView) {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    viewModel.loadCurrentCard()
                }
            }
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
            with(settings) {
                javaScriptEnabled = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = true
                domStorageEnabled = true
            }
            loadDataWithBaseURL(
                viewModel.serverBaseUrl(),
                stdHtml(requireContext(), Themes.currentTheme.isNightMode),
                "text/html",
                null,
                null
            )
        }

        val slider = view.findViewById<Slider>(R.id.slider)
        val nextButton = view.findViewById<MaterialButton>(R.id.show_next)
        val previousButton = view.findViewById<MaterialButton>(R.id.show_previous)
        val progressIndicator = view.findViewById<MaterialTextView>(R.id.progress_indicator)

        viewModel.onError
            .flowWithLifecycle(lifecycle)
            .onEach { errorMessage ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.vague_error)
                    .setMessage(errorMessage)
                    .show()
            }
            .launchIn(lifecycleScope)
        viewModel.eval
            .flowWithLifecycle(lifecycle)
            .onEach { eval ->
                webView.evaluateJavascript(eval, null)
            }
            .launchIn(lifecycleScope)
        lifecycleScope.launch {
            viewModel.backsideOnly
                .flowWithLifecycle(lifecycle)
                .collectLatest { isBacksideOnly ->
                    setBacksideOnlyButtonIcon(isBacksideOnly)
                }
        }

        val cardsCount = selectedCardIds.count()
        lifecycleScope.launch {
            viewModel.currentIndex
                .flowWithLifecycle(lifecycle)
                .collectLatest { currentIndex ->
                    val displayIndex = currentIndex + 1
                    slider.value = displayIndex.toFloat()
                    progressIndicator.text =
                        getString(R.string.preview_progress_bar_text, displayIndex, cardsCount)
                }
        }
        if (cardsCount == 1) {
            slider.visibility = View.GONE
            progressIndicator.visibility = View.GONE
            nextButton.visibility = View.GONE
            previousButton.visibility = View.GONE
        }

        slider.apply {
            valueTo = cardsCount.toFloat()
            addOnSliderTouchListener(
                object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}

                    override fun onStopTrackingTouch(slider: Slider) {
                        viewModel.launchCatching {
                            displayCard(value.toInt() - 1)
                        }
                    }
                }
            )
        }

        nextButton.setOnClickListener {
            viewModel.launchCatching { showAnswerOrNextCard() }
        }

        previousButton.setOnClickListener {
            viewModel.launchCatching { showAnswerOrPreviousCard() }
        }

        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setOnMenuItemClickListener(this@PreviewerFragment)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        super.onViewCreated(view, savedInstanceState)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_edit -> editCard()
            R.id.action_back_side_only -> viewModel.toggleBacksideOnly()
        }
        return true
    }

    private fun setBacksideOnlyButtonIcon(isBacksideOnly: Boolean) {
        backsideOnlyOption.apply {
            if (isBacksideOnly) {
                setIcon(R.drawable.ic_card_answer)
                setTitle(R.string.card_side_answer)
            } else {
                setIcon(R.drawable.ic_card_question)
                setTitle(R.string.card_side_both)
            }
        }
    }

    private val editCardLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(NoteEditor.RELOAD_REQUIRED_EXTRA_KEY, false) == true ||
            result.data?.getBooleanExtra(NoteEditor.NOTE_CHANGED_EXTRA_KEY, false) == true
        ) {
            viewModel.loadCurrentCard(reload = true)
        }
    }

    private fun editCard() {
        val intent = Intent(requireContext(), NoteEditor::class.java).apply {
            putExtra(NoteEditor.EXTRA_CALLER, NoteEditor.CALLER_PREVIEWER_EDIT)
            putExtra(NoteEditor.EXTRA_EDIT_FROM_CARD_ID, viewModel.cardId())
        }
        editCardLauncher.launch(intent)
    }

    companion object {
        const val CURRENT_INDEX_EXTRA = "currentIndex"
        const val CARD_IDS_EXTRA = "cardIds"

        fun getIntent(context: Context, selectedCardIds: LongArray, currentIndex: Int): Intent {
            val args = bundleOf(CURRENT_INDEX_EXTRA to currentIndex, CARD_IDS_EXTRA to selectedCardIds)
            return SingleFragmentActivity.getIntent(context, PreviewerFragment::class, args)
        }
    }
}