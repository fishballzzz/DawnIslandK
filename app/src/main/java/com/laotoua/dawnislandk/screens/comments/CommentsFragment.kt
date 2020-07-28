/*
 *  Copyright 2020 Fishballzzz
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.laotoua.dawnislandk.screens.comments


import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.text.style.UnderlineSpan
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.text.toSpannable
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.LayoutMode
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.bottomsheets.BasicGridItem
import com.afollestad.materialdialogs.bottomsheets.BottomSheet
import com.afollestad.materialdialogs.bottomsheets.gridItems
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import com.google.android.material.animation.AnimationUtils
import com.laotoua.dawnislandk.DawnApp
import com.laotoua.dawnislandk.MainNavDirections
import com.laotoua.dawnislandk.R
import com.laotoua.dawnislandk.data.local.entity.Comment
import com.laotoua.dawnislandk.databinding.FragmentCommentBinding
import com.laotoua.dawnislandk.di.DaggerViewModelFactory
import com.laotoua.dawnislandk.screens.MainActivity
import com.laotoua.dawnislandk.screens.SharedViewModel
import com.laotoua.dawnislandk.screens.adapters.QuickAdapter
import com.laotoua.dawnislandk.screens.util.Layout.updateHeaderAndFooter
import com.laotoua.dawnislandk.screens.widgets.LinkifyTextView
import com.laotoua.dawnislandk.screens.widgets.popups.ImageViewerPopup
import com.laotoua.dawnislandk.screens.widgets.popups.PostPopup
import com.laotoua.dawnislandk.screens.widgets.spans.ReferenceSpan
import com.laotoua.dawnislandk.util.DawnConstants
import com.laotoua.dawnislandk.util.EventPayload
import com.laotoua.dawnislandk.util.SingleLiveEvent
import com.laotoua.dawnislandk.util.lazyOnMainOnly
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.BasePopupView
import com.lxj.xpopup.interfaces.SimpleCallback
import dagger.android.support.DaggerFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.dkzwm.widget.srl.RefreshingListenerAdapter
import me.dkzwm.widget.srl.config.Constants
import timber.log.Timber
import javax.inject.Inject


class CommentsFragment : DaggerFragment() {
    private val args: CommentsFragmentArgs by navArgs()

    private var binding: FragmentCommentBinding? = null

    @Inject
    lateinit var viewModelFactory: DaggerViewModelFactory
    private val viewModel: CommentsViewModel by viewModels { viewModelFactory }
    private val sharedVM: SharedViewModel by activityViewModels { viewModelFactory }

    private var mAdapter: QuickAdapter<Comment>? = null

    // last visible item indicates the current page, uses for remembering last read page
    private var currentPage = 0
    private var pageCounter: TextView? = null
    private var filterActivated: Boolean = false
    private var requireTitleUpdate: Boolean = false

    private var imagesList: List<Any> = listOf()

    // list to remember all currently displaying popups
    // need to dismiss all before jumping to new post, by lifo
    private val quotePopups: MutableList<QuotePopup> = mutableListOf()

    private val postPopup: PostPopup by lazyOnMainOnly { PostPopup(requireActivity(), sharedVM) }
    private var imageViewerPopup: ImageViewerPopup? = null

    enum class RVScrollState {
        UP,
        DOWN
    }

    private var currentState: RVScrollState? = null
    private var currentAnimatorSet: ViewPropertyAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_fragment_comment, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        pageCounter = menu.findItem(R.id.pageCounter).actionView.findViewById(R.id.text)
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.filter -> {
                filterActivated = filterActivated.not()
                if (!filterActivated) {
                    viewModel.clearFilter()
                    Toast.makeText(context, R.string.comment_filter_off, Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.onlyPo()
                    Toast.makeText(context, R.string.comment_filter_on, Toast.LENGTH_SHORT).show()
                }
                (binding?.srlAndRv?.recyclerView?.layoutManager as LinearLayoutManager?)?.run {
                    val startPos = findFirstVisibleItemPosition()
                    val itemCount = findLastVisibleItemPosition() - startPos
                    mAdapter?.notifyItemRangeChanged(startPos, itemCount + initialPrefetchItemCount)
                }
                return true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        if (mAdapter == null) {
            mAdapter = QuickAdapter<Comment>(R.layout.list_item_comment, sharedVM).apply {
                setReferenceClickListener(object : ReferenceSpan.ReferenceClickHandler {
                    override fun handleReference(id: String) {
                        displayQuote(id)
                    }
                })

                setOnItemClickListener { _, _, pos ->
                    toggleCommentMenuOnPos(pos)
                }

                addChildClickViewIds(
                    R.id.attachedImage,
                    R.id.expandSummary,
                    R.id.comment,
                    R.id.content,
                    R.id.copy,
                    R.id.report
                )

                setOnItemChildClickListener { _, view, position ->
                    when (view.id) {
                        R.id.attachedImage -> {
                            val pos = imagesList.indexOf(getItem(position))
                            if (pos < 0) {
                                Timber.e("Did not find image in for comment #$position")
                                return@setOnItemChildClickListener
                            }
                            getImageViewerPopup().setSrcView(null, pos)
                            XPopup.Builder(context)
                                .asCustom(getImageViewerPopup())
                                .show()
                        }
                        R.id.comment -> {
                            val content = ">>No.${getItem(position).id}\n"
                            postPopup.setupAndShow(
                                viewModel.currentPostId,
                                viewModel.currentPostFid,
                                targetPage = viewModel.maxPage,
                                quote = content
                            )
                        }
                        R.id.copy -> {
                            mAdapter?.getViewByPosition(position, R.id.content)?.let {
                                copyText("评论", (it as TextView).text.toString())
                            }
                        }
                        R.id.report -> {
                            MaterialDialog(requireContext()).show {
                                title(R.string.report_reasons)
                                listItemsSingleChoice(res = R.array.report_reasons) { _, _, text ->
                                    postPopup.setupAndShow(
                                        "18",//值班室
                                        "18",
                                        newPost = true,
                                        quote = "\n>>No.${getItem(position).id}\n${context.getString(
                                            R.string.report_reasons
                                        )}: $text"
                                    )
                                }
                                cancelOnTouchOutside(false)
                            }
                        }
                        R.id.content -> {
                            val ltv = view as LinkifyTextView
                            // no span was clicked, simulate click events to parent
                            if (ltv.currentSpan == null) {
                                val metaState = 0
                                (view.parent as View).dispatchTouchEvent(
                                    MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_DOWN,
                                        0f,
                                        0f,
                                        metaState
                                    )
                                )
                                (view.parent as View).dispatchTouchEvent(
                                    MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_UP,
                                        0f,
                                        0f,
                                        metaState
                                    )
                                )
                            }
                        }
                        R.id.expandSummary -> {
                            data[position].visible = true
                            notifyItemChanged(position)
                        }
                    }
                }

                // load more
                loadMoreModule.setOnLoadMoreListener {
                    viewModel.getNextPage()
                }
            }
        }

        if (binding != null) {
            Timber.d("Fragment View Reusing!")
        } else {
            Timber.d("Fragment View Created")
            binding = FragmentCommentBinding.inflate(inflater, container, false)
            binding!!.srlAndRv.refreshLayout.apply {
                setOnRefreshListener(object : RefreshingListenerAdapter() {
                    override fun onRefreshing() {
                        if (binding == null || mAdapter == null) return
                        if (mAdapter?.data.isNullOrEmpty().not() && mAdapter?.getItem(
                                (binding!!.srlAndRv.recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                            )?.page == 1
                        ) {
                            Toast.makeText(context, "没有上一页了。。。", Toast.LENGTH_SHORT).show()
                            refreshComplete(true, 100L)
                        } else {
                            viewModel.getPreviousPage()
                        }
                    }
                })
            }

            binding!!.srlAndRv.recyclerView.apply {
                val llm = LinearLayoutManager(context)
                layoutManager = llm
                adapter = mAdapter
                setHasFixedSize(true)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (binding == null) return
                        if (dy > 0) {
                            hideMenu()
                        } else if (dy < 0) {
                            showMenu()
                            if (llm.findFirstVisibleItemPosition() <= 2 && !binding!!.srlAndRv.refreshLayout.isRefreshing) {
                                viewModel.getPreviousPage()
                            }
                        }
                        updateCurrentPage()
                    }
                })
            }

            binding!!.copyAndShare.setOnClickListener {
                val items = listOf(
                    BasicGridItem(R.drawable.ic_share_black_48dp, "分享串"),
                    BasicGridItem(R.drawable.ic_public_black_48dp, "复制串地址"),
                    BasicGridItem(R.drawable.ic_content_copy_black_48dp, "复制串号")
                )
                MaterialDialog(requireContext(), BottomSheet(LayoutMode.WRAP_CONTENT)).show {
                    gridItems(items) { _, index, item ->
                        when (index) {
                            1 -> copyText(
                                "串地址",
                                "${DawnConstants.nmbHost}/t/${viewModel.currentPostId}"
                            )
                            2 -> copyText("串号", ">>No.${viewModel.currentPostId}")
                            else -> {
                                Toast.makeText(
                                    context,
                                    "Selected item ${item.title} at index $index",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                    }
                }
            }

            binding!!.post.setOnClickListener {
                postPopup.setupAndShow(
                    viewModel.currentPostId,
                    viewModel.currentPostFid,
                    targetPage = viewModel.maxPage
                )
            }

            binding!!.jump.setOnClickListener {
                if (binding == null || mAdapter == null) return@setOnClickListener
                if (binding!!.srlAndRv.refreshLayout.isRefreshing || mAdapter?.loadMoreModule?.isLoading == true) {
                    Timber.d("Loading data...Holding on jump...")
                    return@setOnClickListener
                }
                val page = getCurrentPage()
                val jumpPopup = JumpPopup(requireContext())
                XPopup.Builder(context)
                    .setPopupCallback(object : SimpleCallback() {
                        override fun beforeShow(popupView: BasePopupView?) {
                            super.beforeShow(popupView)
                            jumpPopup.updatePages(page, viewModel.maxPage)
                        }

                        override fun onDismiss(popupView: BasePopupView?) {
                            super.onDismiss(popupView)
                            if (binding == null || mAdapter == null) return
                            if (jumpPopup.submit) {
                                binding!!.srlAndRv.refreshLayout.autoRefresh(
                                    Constants.ACTION_NOTHING,
                                    false
                                )
                                mAdapter?.setList(emptyList())
                                Timber.i("Jumping to ${jumpPopup.targetPage}...")
                                viewModel.jumpTo(jumpPopup.targetPage)
                            }
                        }
                    })
                    .isDestroyOnDismiss(true)
                    .asCustom(jumpPopup)
                    .show()
            }

            binding!!.addFeed.setOnClickListener {
                viewModel.addFeed(viewModel.currentPostId)
            }
        }
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.setPost(args.id, args.fid, args.targetPage)
        requireTitleUpdate = args.fid.isBlank()
        updateTitle()
    }

    private val addFeedObs = Observer<SingleLiveEvent<String>> {
        it.getContentIfNotHandled()?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private val loadingStatusObs = Observer<SingleLiveEvent<EventPayload<Nothing>>> {
        if (binding == null || mAdapter == null) return@Observer
        it.getContentIfNotHandled()?.run {
            updateHeaderAndFooter(binding!!.srlAndRv.refreshLayout, mAdapter!!, this)
        }
    }

    private val commentsObs = Observer<MutableList<Comment>> {
        if (mAdapter == null || it.isEmpty()) return@Observer
        updateCurrentPage()
        if (requireTitleUpdate) {
            updateTitle()
            requireTitleUpdate = false
        }
        mAdapter?.setDiffNewData(it.toMutableList())
        updateCurrentlyAvailableImages(it)
        mAdapter?.setPo(viewModel.po)
        Timber.i("${this.javaClass.simpleName} Adapter will have ${mAdapter?.data?.size} comments")
    }

    private val successPostObs = Observer<SingleLiveEvent<Boolean>> { event ->
        event.getContentIfNotHandled()?.let {
            if (it && currentPage >= viewModel.maxPage - 1) {
                mAdapter?.loadMoreModule?.loadMoreToLoading()
            }
        }
    }

    private fun subscribeUI() {
        viewModel.addFeedResponse.observe(viewLifecycleOwner, addFeedObs)
        viewModel.loadingStatus.observe(viewLifecycleOwner, loadingStatusObs)
        viewModel.comments.observe(viewLifecycleOwner, commentsObs)
        sharedVM.savePostStatus.observe(viewLifecycleOwner, successPostObs)
    }

    private fun unsubscribeUI() {
        viewModel.addFeedResponse.removeObserver(addFeedObs)
        viewModel.loadingStatus.removeObserver(loadingStatusObs)
        viewModel.comments.removeObserver(commentsObs)
        sharedVM.savePostStatus.removeObserver(successPostObs)
    }

    override fun onPause() {
        super.onPause()
        unsubscribeUI()
    }

    override fun onResume() {
        super.onResume()
        subscribeUI()

        (requireActivity() as MainActivity).run {
            setToolbarClickListener {
                binding?.srlAndRv?.recyclerView?.layoutManager?.scrollToPosition(0)
                showMenu()
            }
            hideNav()
        }
    }

    private fun copyText(label: String, text: String) {
        getSystemService(requireContext(), ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(
            context,
            resources.getString(R.string.content_copied, label),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun getCurrentPage(): Int {
        if (mAdapter == null || binding == null || mAdapter?.data.isNullOrEmpty()) return 1
        val pos = (binding!!.srlAndRv.recyclerView.layoutManager as LinearLayoutManager)
            .findLastVisibleItemPosition()
            .coerceAtLeast(0)
            .coerceAtMost(mAdapter!!.data.lastIndex)
        return mAdapter!!.getItem(pos).page
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissAllQuotes()
        if (!DawnApp.applicationDataStore.viewCaching) {
            mAdapter = null
            binding = null
        }
        imageViewerPopup?.clearLoaders()
        imageViewerPopup = null
        Timber.d("Fragment View Destroyed ${binding == null}")
    }

    fun hideMenu() {
        if (currentState == RVScrollState.DOWN) return
        if (currentAnimatorSet != null) {
            currentAnimatorSet!!.cancel()
        }
        currentState = RVScrollState.DOWN
        currentAnimatorSet = binding?.bottomToolbar?.animate()?.apply {
            alpha(0f)
            translationY(binding!!.bottomToolbar.height.toFloat())
            duration = 250
            interpolator = AnimationUtils.FAST_OUT_LINEAR_IN_INTERPOLATOR
            setListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationEnd(animation: Animator?) {
                    currentAnimatorSet = null
                    binding?.bottomToolbar?.visibility = View.GONE
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {}
            })
        }
        currentAnimatorSet!!.start()
    }

    fun showMenu() {
        if (currentState == RVScrollState.UP) return
        if (currentAnimatorSet != null) {
            currentAnimatorSet!!.cancel()
        }
        currentState = RVScrollState.UP
        binding?.bottomToolbar?.visibility = View.VISIBLE
        currentAnimatorSet = binding?.bottomToolbar?.animate()?.apply {
            alpha(1f)
            translationY(0f)
            duration = 250
            interpolator = AnimationUtils.LINEAR_OUT_SLOW_IN_INTERPOLATOR
            setListener(object : Animator.AnimatorListener {
                override fun onAnimationRepeat(animation: Animator?) {}
                override fun onAnimationEnd(animation: Animator?) {
                    currentAnimatorSet = null
                }

                override fun onAnimationCancel(animation: Animator?) {}
                override fun onAnimationStart(animation: Animator?) {}
            })
        }
        currentAnimatorSet!!.start()
    }

    private fun updateTitle() {
        if (viewModel.currentPostFid.isNotBlank()) {
            (requireActivity() as MainActivity).setToolbarTitle(
                "${sharedVM.getSelectedPostForumName(viewModel.currentPostFid)} • ${viewModel.currentPostId}"
            )
        }
    }

    private fun updateCurrentPage() {
        if (mAdapter == null || binding == null) return
        val page = getCurrentPage()
        if (page != currentPage || pageCounter?.text?.isBlank() == true) {
            viewModel.saveReadingProgress(page)
            pageCounter?.text =
                (page.toString() + " / " + viewModel.maxPage.toString()).toSpannable()
                    .apply { setSpan(UnderlineSpan(), 0, length, 0) }
        }
        currentPage = page
    }

    private var menuPos = -1

    private fun showCommentMenuOnPos(pos: Int) {
        menuPos = pos
        mAdapter?.getViewByPosition(pos, R.id.commentMenu)?.apply {
            visibility = View.VISIBLE
            animate()
                .alpha(1f)
                .setDuration(150)
                .setListener(null)
        }
    }

    private fun hideCommentMenuOnPos(pos: Int) {
        if (menuPos < 0) return
        mAdapter?.getViewByPosition(pos, R.id.commentMenu)?.apply {
            animate()
                .alpha(0f)
                .setDuration(150)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.GONE
                    }
                })
        }
    }

    private fun toggleCommentMenuOnPos(pos: Int) {
        mAdapter?.getViewByPosition(pos, R.id.commentMenu)?.apply {
            if (isVisible) {
                hideCommentMenuOnPos(pos)
            } else {
                hideCommentMenuOnPos(menuPos)
                showCommentMenuOnPos(pos)
            }
        }
    }

    fun displayQuote(id: String) {
        val top = QuotePopup(this, viewModel.getQuote(id), viewModel.currentPostId, viewModel.po)
        quotePopups.add(top)
        XPopup.Builder(context)
            .setPopupCallback(object : SimpleCallback() {
                override fun beforeShow(popupView: BasePopupView?) {
                    super.beforeShow(popupView)
                    top.listenToLiveQuote(viewLifecycleOwner)
                }

                override fun beforeDismiss(popupView: BasePopupView?) {
                    super.beforeDismiss(popupView)
                    quotePopups.remove(popupView)
                }
            })
            .asCustom(top)
            .show()
    }

    private fun dismissAllQuotes() {
        for (i in quotePopups.indices.reversed()) {
            quotePopups[i].destroy()
        }
    }

    fun jumpToNewPost(id: String) {
        dismissAllQuotes()
        lifecycleScope.launch {
            delay(100L)
            val navAction = MainNavDirections.actionGlobalCommentsFragment(id, "")
            findNavController().navigate(navAction)
        }
    }

    private fun getImageViewerPopup(): ImageViewerPopup {
        if (imageViewerPopup == null) {
            imageViewerPopup = ImageViewerPopup(requireContext()).apply {
                setNextPageLoader { viewModel.getNextPage() }
                setPreviousPageLoader { viewModel.getPreviousPage() }
            }
        }
        return imageViewerPopup!!
    }

    private fun updateCurrentlyAvailableImages(newList: MutableList<Comment>) {
        imagesList = newList.filter { it.getImgUrl().isNotBlank() }
        getImageViewerPopup().setImageUrls(imagesList.toMutableList())
    }
}
