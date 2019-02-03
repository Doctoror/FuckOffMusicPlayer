/*
 * Copyright (C) 2016 Yaroslav Mytkalyk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.doctoror.fuckoffmusicplayer.presentation.queue;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.res.Configuration;
import android.databinding.DataBindingUtil;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.support.design.widget.CoordinatorLayout;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.CardView;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.text.TextUtils;
import android.transition.Transition;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.doctoror.fuckoffmusicplayer.R;
import com.doctoror.fuckoffmusicplayer.data.util.CollectionUtils;
import com.doctoror.fuckoffmusicplayer.databinding.ActivityQueueBinding;
import com.doctoror.fuckoffmusicplayer.domain.media.CurrentMediaProvider;
import com.doctoror.fuckoffmusicplayer.domain.playback.PlaybackData;
import com.doctoror.fuckoffmusicplayer.domain.playback.PlaybackState;
import com.doctoror.fuckoffmusicplayer.domain.playback.initializer.PlaybackInitializer;
import com.doctoror.fuckoffmusicplayer.domain.queue.Media;
import com.doctoror.fuckoffmusicplayer.presentation.base.BaseActivity;
import com.doctoror.fuckoffmusicplayer.presentation.nowplaying.NowPlayingActivity;
import com.doctoror.fuckoffmusicplayer.presentation.transition.CardVerticalGateTransition;
import com.doctoror.fuckoffmusicplayer.presentation.transition.SlideFromBottomHelper;
import com.doctoror.fuckoffmusicplayer.presentation.transition.TransitionListenerAdapter;
import com.doctoror.fuckoffmusicplayer.presentation.transition.TransitionUtils;
import com.doctoror.fuckoffmusicplayer.presentation.transition.VerticalGateTransition;
import com.doctoror.fuckoffmusicplayer.presentation.util.CoordinatorLayoutUtil;
import com.doctoror.fuckoffmusicplayer.presentation.util.ViewUtils;
import com.doctoror.fuckoffmusicplayer.presentation.widget.ItemTouchHelperViewHolder;
import com.f2prateek.dart.Dart;
import com.f2prateek.dart.InjectExtra;

import org.parceler.Parcel;
import org.parceler.Parcels;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.inject.Inject;

import dagger.android.AndroidInjection;

/**
 * "Playlist" activity
 */
public final class QueueActivity extends BaseActivity
        implements DeleteMediaDialogFragment.Callback {

    public static final String TRANSITION_NAME_ALBUM_ART
            = "PlaylistActivity.TRANSITION_NAME_ALBUM_ART";

    public static final String TRANSITION_NAME_ROOT = "PlaylistActivity.TRANSITION_NAME_ROOT";

    private static final String EXTRA_STATE = "EXTRA_STATE";

    private final RequestOptions requestOptions = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.NONE);

    private final RequestOptions dontAnimateOptions = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .dontAnimate();

    private final QueueActivityModel mModel = new QueueActivityModel();

    private RequestManager mRequestManager;

    private QueueRecyclerAdapter mAdapter;
    private CoordinatorLayoutUtil.AnchorParams mFabAnchorParams;

    private int mShortAnimTime;
    private int mMediumAnimTime;

    @InjectExtra
    List<Media> queue;

    @InjectExtra
    boolean isNowPlayingQueue;

    @Nullable
    @InjectExtra
    String title;

    @InjectExtra
    boolean hasCoverTransition;

    @InjectExtra
    boolean hasItemViewTransition;

    private ActivityQueueBinding mBinding;

    private String mCoverUri;
    private int mAppbarOffset;

    private boolean mCreatedWithInstanceState;
    private Toast mToastRemovedFromQueue;

    @Inject
    CurrentMediaProvider currentMediaProvider;

    @Inject
    PlaybackData mPlaybackData;

    @Inject
    PlaybackInitializer mPlaybackInitializer;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Dart.inject(this);
        AndroidInjection.inject(this);
        mRequestManager = Glide.with(this);

        mShortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);
        mMediumAnimTime = getResources().getInteger(android.R.integer.config_mediumAnimTime);

        if (!TextUtils.isEmpty(title)) {
            setTitle(title);
        }

        mAdapter = new QueueRecyclerAdapter(this, queue);
        mAdapter.setTrackListener(new TrackListenerImpl());
        mAdapter.registerAdapterDataObserver(mAdapterDataObserver);
        mModel.setRecyclerAdpter(mAdapter);

        final ActivityQueueBinding binding = DataBindingUtil.setContentView(this,
                R.layout.activity_queue);
        binding.setModel(mModel);
        mBinding = binding;

        binding.fab.setOnClickListener(v -> onPlayClick(v, 0));

        binding.appBar.addOnOffsetChangedListener(
                (appBarLayout, verticalOffset) -> mAppbarOffset = verticalOffset);

        initAlbumArtAndToolbar(binding);
        initRecyclerView();

        if (TransitionUtils.supportsActivityTransitions()) {
            final View cardView = findViewById(R.id.cardView);
            QueueActivityLollipop.applyTransitions(this, cardView != null);
        }

        mCreatedWithInstanceState = savedInstanceState != null;
    }

    @Override
    protected void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) {
            final State state = Parcels.unwrap(savedInstanceState.getParcelable(EXTRA_STATE));
            if (state != null) {
                mFabAnchorParams = state.fabAnchorParams;
                queue = state.queue;
                mAdapter.setItems(queue);

                mBinding.fab.setScaleX(1f);
                mBinding.fab.setScaleY(1f);
            }
        }
    }

    @Override
    protected void onPostCreate(@Nullable final Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (TransitionUtils.supportsActivityTransitions()) {
            QueueActivityLollipop.addEnterTransitionListener(this);
        }
    }

    private void setAppBarCollapsibleIfNeeded() {
        final View cardHostScrollView = findViewById(R.id.cardHostScrollView);
        ViewUtils.setAppBarCollapsibleIfScrollableViewIsLargeEnoughToScroll(
                mBinding.root,
                mBinding.appBar,
                mBinding.recyclerView,
                ViewUtils.getOverlayTop(cardHostScrollView != null ?
                        cardHostScrollView :
                        mBinding.recyclerView));
    }

    private void initRecyclerView() {
        mBinding.recyclerView.setLayoutManager(new LinearLayoutManager(this) {
            @Override
            public void onLayoutChildren(final RecyclerView.Recycler recycler,
                                         final RecyclerView.State state) {
                super.onLayoutChildren(recycler, state);
                setAppBarCollapsibleIfNeeded();
            }
        });
        final QueueRecyclerAdapter adapter = (QueueRecyclerAdapter) mModel
                .getRecyclerAdapter().get();
        if (adapter == null) {
            throw new IllegalStateException("Adapter in Model must be set");
        }
        final ItemTouchHelper itemTouchHelper = new ItemTouchHelper(
                new ItemTouchHelperImpl(adapter));
        itemTouchHelper.attachToRecyclerView(mBinding.recyclerView);
    }

    private void initAlbumArtAndToolbar(@NonNull final ActivityQueueBinding binding) {
        setSupportActionBar(binding.toolbar);
        ViewCompat.setTransitionName(binding.getRoot(), QueueActivity.TRANSITION_NAME_ROOT);
        ViewCompat.setTransitionName(binding.albumArt, QueueActivity.TRANSITION_NAME_ALBUM_ART);

        String pic = null;
        for (final Media media : queue) {
            pic = media.getAlbumArt();
            if (pic != null) {
                break;
            }
        }
        mCoverUri = pic;

        if (TextUtils.isEmpty(pic)) {
            mRequestManager.clear(binding.albumArt);
            showPlaceholderArt();
            onImageSet();
        } else {
            loadAlbumArt(pic);
        }
    }

    @SuppressLint("CheckResult")
    private void loadAlbumArt(@NonNull final String uri) {
        final RequestBuilder<Drawable> b = mRequestManager
                .asDrawable()
                .load(uri);

        if (hasCoverTransition || hasItemViewTransition) {
            supportPostponeEnterTransition();
            b.apply(dontAnimateOptions);
        } else {
            b.apply(requestOptions);
        }
        b.listener(new AlbumArtRequestListener()).into(mBinding.albumArt);
    }

    private void showPlaceholderArt() {
        mBinding.albumArt.setImageResource(R.drawable.album_art_placeholder);
        mBinding.albumArt.setAlpha(1f);
    }

    private void onImageSet() {
        if (hasCoverTransition || hasItemViewTransition) {
            supportStartPostponedEnterTransition();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mCreatedWithInstanceState
                || !TransitionUtils.supportsActivityTransitions()
                || (!hasItemViewTransition && !hasCoverTransition)) {
            onEnterTransitionFinished();
        }
        if (mFabAnchorParams != null) {
            CoordinatorLayoutUtil.applyAnchorParams(mBinding.fab, mFabAnchorParams);
            mBinding.fab.post(() -> mBinding.fab.requestLayout());
            mFabAnchorParams = null;
        }
        if (isNowPlayingQueue) {
            disposeOnStop(mPlaybackData.playbackStateObservable()
                    .subscribe(this::onPlaybackStateChanged));
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mBinding.fab.setScaleX(1f);
        mBinding.fab.setScaleY(1f);
        mBinding.albumArtDim.setAlpha(1f);
        mBinding.albumArt.clearColorFilter();
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        final State state = new State();
        state.queue = queue;
        state.fabAnchorParams = mFabAnchorParams;
        outState.putParcelable(EXTRA_STATE, Parcels.wrap(state));
    }

    @Override
    public void setSupportActionBar(@Nullable final Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_TITLE
                    | ActionBar.DISPLAY_SHOW_HOME
                    | ActionBar.DISPLAY_HOME_AS_UP);
        }
    }

    @Override
    public void onPerformDelete(final long id) {
        if (!isFinishingAfterTransition()) {
            mAdapter.removeItemWithId(id);

            final Iterator<Media> i = queue.iterator();
            while (i.hasNext()) {
                if (i.next().getId() == id) {
                    i.remove();
                    break;
                }
            }
        }
    }

    private void onQueueEmpty() {
        ActivityCompat.finishAfterTransition(this);
    }

    /**
     * This is used to avoid
     * <pre>
     *   android.content.res.Resources$NotFoundException: Unable to find resource ID #0x0
     *     at android.content.res.ResourcesImpl.getResourceName(ResourcesImpl.java:253)
     *     at android.content.res.Resources.getResourceName(Resources.java:1933)
     *     at android.support.design.widget.CoordinatorLayout$LayoutParams.resolveAnchorView(CoordinatorLayout.java:3072)
     * </pre>
     */
    private void prepareFabForExitTransition() {
        final ViewGroup.LayoutParams params = mBinding.fab.getLayoutParams();
        if (params instanceof CoordinatorLayout.LayoutParams) {
            ((CoordinatorLayout.LayoutParams) params).setAnchorId(View.NO_ID);
            mBinding.fab.setLayoutParams(params);
        }
    }

    @WorkerThread
    private void onPlaybackStateChanged(@NonNull final PlaybackState state) {
        final Media media = state == PlaybackState.STATE_PLAYING
                ? currentMediaProvider.getCurrentMedia() : null;
        //noinspection WrongThread
        runOnUiThread(() -> onNowPlayingMediaChanged(media));
    }

    @UiThread
    private void onNowPlayingMediaChanged(@Nullable final Media media) {
        mAdapter.setNowPlayingId(media != null ? media.getId() : 0);
    }

    private void onPlayClick(@NonNull final View clickedView,
                             final int queuePosition) {

        mPlaybackInitializer.setQueueAndPlay(queue, queuePosition);
        final Media media = CollectionUtils.getItemSafe(queue, queuePosition);
        final boolean shouldPassCoverView = mAppbarOffset == 0
                && TextUtils.equals(mCoverUri, media != null ? media.getAlbumArt() : null);
        if (shouldPassCoverView) {
            prepareViewsAndExit(() -> startNowPlayingActivity(mBinding.albumArt, null));
        } else {
            mFabAnchorParams = CoordinatorLayoutUtil.getAnchorParams(mBinding.fab);
            CoordinatorLayoutUtil.clearAnchorGravityAndApplyMargins(mBinding.fab);
            startNowPlayingActivity(null, clickedView);
        }
    }

    private void startNowPlayingActivity(@Nullable final View albumArt,
                                         @Nullable final View listItemView) {
        if (isNowPlayingQueue) {
            // Note that starting a transition from here when returning to already running
            // NowPlayingActivity causes memory leak in ExitTransitionCoordinator. Thus null views
            // are passed here to avoid this.
            // https://code.google.com/p/android/issues/detail?id=170469
            prepareFabForExitTransition();
            NowPlayingActivity.start(this, null, null);
        } else {
            NowPlayingActivity.start(this, albumArt, listItemView);
        }
    }

    private void onEnterTransitionFinished() {
        if (mBinding.fab.getScaleX() != 1f) {
            mBinding.fab.animate().scaleX(1f).scaleY(1f).setDuration(mShortAnimTime).start();
        }
        if (mBinding.albumArtDim.getAlpha() != 1f) {
            mBinding.albumArtDim.animate().alpha(1f).setDuration(mShortAnimTime).start();
        }

        final CardView cardView = findViewById(R.id.cardView);
        if (cardView != null && cardView.getVisibility() != View.VISIBLE) {
            if (TransitionUtils.supportsActivityTransitions() && hasCoverTransition) {
                cardView.setTranslationY(SlideFromBottomHelper.getStartTranslation(cardView));
                cardView.setVisibility(View.VISIBLE);
                SlideFromBottomHelper.createAnimator(cardView).setDuration(mMediumAnimTime).start();
            } else {
                cardView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void prepareViewsAndExit(@NonNull final Runnable exitAction) {
        if (!TransitionUtils.supportsActivityTransitions() ||
                (mBinding.fab.getScaleX() == 0f && mBinding.albumArtDim.getAlpha() == 0f)) {
            exitAction.run();
        } else {
            final boolean isLandscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            // Landscape Now Playing has a dim, so dim the ImageView and send it
            if (isLandscape) {
                mBinding.albumArtDim.setAlpha(0f);
                mBinding.albumArt.setColorFilter(
                        ContextCompat.getColor(this, R.color.translucentBackground),
                        PorterDuff.Mode.SRC_ATOP);
            } else {
                // Portrait NowPlaying does not have a dim. Fade out the dim before animating.
                mBinding.albumArtDim.animate().alpha(0f).setDuration(mShortAnimTime).start();
            }
            mBinding.fab.animate().scaleX(0f).scaleY(0f).setDuration(mShortAnimTime)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(final Animator animation) {
                            exitAction.run();
                        }
                    }).start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mAdapter.unregisterAdapterDataObserver(mAdapterDataObserver);
    }

    private void showToastRemovedFromQueue(@NonNull final Media media) {
        if (mToastRemovedFromQueue != null
                && mToastRemovedFromQueue.getView().getWindowToken() != null) {
            mToastRemovedFromQueue.cancel();
        }
        mToastRemovedFromQueue = Toast.makeText(this,
                getString(R.string.s_removed_from_queue, media.getTitle()), Toast.LENGTH_SHORT);
        mToastRemovedFromQueue.show();
    }

    @Parcel
    static final class State {

        List<Media> queue;
        CoordinatorLayoutUtil.AnchorParams fabAnchorParams;
    }

    private final class AlbumArtRequestListener implements RequestListener<Drawable> {

        @Override
        public boolean onLoadFailed(
                @Nullable final GlideException e,
                @NonNull final Object model,
                @NonNull final Target<Drawable> target,
                final boolean isFirstResource) {
            mCoverUri = null;
            showPlaceholderArt();
            onImageSet();
            return true;
        }

        @Override
        public boolean onResourceReady(
                @NonNull final Drawable resource,
                @NonNull final Object model,
                @NonNull final Target<Drawable> target,
                @NonNull final DataSource dataSource,
                final boolean isFirstResource) {
            onImageSet();
            return false;
        }
    }

    private final class TrackListenerImpl implements QueueRecyclerAdapter.TrackListener {

        @Override
        public void onTrackClick(@NonNull final View itemView,
                                 final int position) {
            onPlayClick(itemView, position);
        }

        @Override
        public void onTrackDeleteClick(@NonNull final Media item) {
            DeleteMediaDialogFragment.show(QueueActivity.this, getSupportFragmentManager(),
                    item.getId(), item.getTitle());
        }

        @Override
        public void onTracksSwapped(final int i, final int j) {
            if (i < queue.size() && j < queue.size()) {
                Collections.swap(queue, i, j);
            }
            if (isNowPlayingQueue) {
                mPlaybackData.setPlayQueue(queue);
            }
        }
    }

    private final class ItemTouchHelperImpl extends ItemTouchHelper.SimpleCallback {

        @NonNull
        private final QueueRecyclerAdapter mAdapter;

        ItemTouchHelperImpl(@NonNull final QueueRecyclerAdapter adapter) {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT);
            mAdapter = adapter;
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public boolean isItemViewSwipeEnabled() {
            return true;
        }

        @Override
        public boolean onMove(final RecyclerView recyclerView,
                              final RecyclerView.ViewHolder source,
                              final RecyclerView.ViewHolder target) {
            //noinspection SimplifiableIfStatement
            if (source.getItemViewType() != target.getItemViewType()) {
                return false;
            }

            mAdapter.onItemMove(source.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override
        public int getMovementFlags(final RecyclerView recyclerView,
                                    final RecyclerView.ViewHolder viewHolder) {
            final int swipeFlags = ItemTouchHelper.LEFT;
            int dragFlags = 0;
            if (mAdapter.getItemCount() > 1) {
                dragFlags |= ItemTouchHelper.UP | ItemTouchHelper.DOWN;
            }
            return makeMovementFlags(dragFlags, swipeFlags);
        }

        @Override
        public void onSwiped(final RecyclerView.ViewHolder viewHolder, final int swipeDir) {
            final int pos = viewHolder.getAdapterPosition();
            final Media media = mAdapter.getItem(pos);
            queue.remove(pos);
            if (isNowPlayingQueue) {
                mPlaybackData.setPlayQueue(queue);
            }
            mAdapter.removeItem(pos);
            showToastRemovedFromQueue(media);
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                if (viewHolder instanceof ItemTouchHelperViewHolder) {
                    ((ItemTouchHelperViewHolder) viewHolder).onItemSelected();
                }
            }

            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (viewHolder instanceof ItemTouchHelperViewHolder) {
                ((ItemTouchHelperViewHolder) viewHolder).onItemClear();
            }
        }
    }

    private final RecyclerView.AdapterDataObserver mAdapterDataObserver
            = new RecyclerView.AdapterDataObserver() {

        @Override
        public void onChanged() {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeRemoved(final int positionStart, final int itemCount) {
            checkIfEmpty();
        }

        private void checkIfEmpty() {
            if (mAdapter.getItemCount() == 0 && !isFinishing()) {
                onQueueEmpty();
            }
        }
    };

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class QueueActivityLollipop {

        static void applyTransitions(@NonNull final BaseActivity activity,
                                     final boolean hasCardView) {
            TransitionUtils.clearSharedElementsOnReturn(activity);
            final Window window = activity.getWindow();
            window.setReturnTransition(hasCardView
                    ? new CardVerticalGateTransition()
                    : new VerticalGateTransition());
        }

        static void addEnterTransitionListener(@NonNull final QueueActivity activity) {
            final Transition enter = activity.getWindow().getSharedElementEnterTransition();
            if (enter != null) {
                enter.addListener(new TransitionListenerAdapter() {
                    @Override
                    public void onTransitionEnd(final Transition transition) {
                        activity.onEnterTransitionFinished();
                    }
                });
            }
        }
    }
}
