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
package com.doctoror.fuckoffmusicplayer.presentation.library.albums.conditional;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.databinding.DataBindingUtil;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.AppBarLayout;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.doctoror.commons.util.Log;
import com.doctoror.fuckoffmusicplayer.R;
import com.doctoror.fuckoffmusicplayer.databinding.FragmentConditionalAlbumListBinding;
import com.doctoror.fuckoffmusicplayer.domain.albums.AlbumsProviderKt;
import com.doctoror.fuckoffmusicplayer.domain.playback.initializer.PlaybackInitializer;
import com.doctoror.fuckoffmusicplayer.domain.queue.Media;
import com.doctoror.fuckoffmusicplayer.domain.queue.QueueProviderAlbums;
import com.doctoror.fuckoffmusicplayer.presentation.Henson;
import com.doctoror.fuckoffmusicplayer.presentation.base.BaseActivity;
import com.doctoror.fuckoffmusicplayer.presentation.base.BaseFragment;
import com.doctoror.fuckoffmusicplayer.presentation.nowplaying.NowPlayingActivity;
import com.doctoror.fuckoffmusicplayer.presentation.queue.QueueActivity;
import com.doctoror.fuckoffmusicplayer.presentation.transition.CardVerticalGateTransition;
import com.doctoror.fuckoffmusicplayer.presentation.transition.TransitionUtils;
import com.doctoror.fuckoffmusicplayer.presentation.transition.VerticalGateTransition;
import com.doctoror.fuckoffmusicplayer.presentation.util.ViewUtils;

import java.util.List;

import javax.inject.Inject;

import dagger.android.support.AndroidSupportInjection;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Album lists fragment
 */
public abstract class ConditionalAlbumListFragment extends BaseFragment {

    private static final String TAG = "ConditionalAlbumListFragment";

    private final ConditionalAlbumListModel mModel = new ConditionalAlbumListModel();

    private final RequestOptions requestOptions = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .dontAnimate();

    private ConditionalAlbumsRecyclerAdapter mAdapter;

    private Disposable mDisposableDataOld;
    private Disposable mDisposableData;

    private RequestManager mRequestManager;
    private Cursor mData;

    private int mAnimTime;

    private AppBarLayout appBar;
    private ImageView albumArt;
    private View albumArtDim;

    private View errorContainer;
    private View emptyContainer;
    private View fab;
    private View progress;

    private RecyclerView recyclerView;

    @Inject
    QueueProviderAlbums mQueueFactory;

    @Inject
    PlaybackInitializer mPlaybackInitializer;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AndroidSupportInjection.inject(this);

        mAnimTime = getResources().getInteger(R.integer.shortest_anim_time);
        mRequestManager = Glide.with(this);

        final Activity activity = getActivity();
        if (activity == null) {
            throw new IllegalStateException("Activity is null");
        }

        mAdapter = new ConditionalAlbumsRecyclerAdapter(activity, mRequestManager);
        mAdapter.setOnAlbumClickListener(this::onListItemClick);
        mModel.setRecyclerAdpter(mAdapter);
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull final LayoutInflater inflater,
            @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState) {
        final FragmentConditionalAlbumListBinding binding = DataBindingUtil.inflate(inflater,
                R.layout.fragment_conditional_album_list, container, false);
        binding.setModel(mModel);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        albumArt = view.findViewById(R.id.albumArt);
        albumArtDim = view.findViewById(R.id.albumArtDim);

        appBar = view.findViewById(R.id.appBar);
        errorContainer = view.findViewById(R.id.errorContainer);
        emptyContainer = view.findViewById(R.id.emptyContainer);
        progress = view.findViewById(R.id.progress);

        fab = view.findViewById(R.id.fab);
        fab.setOnClickListener(v -> onFabClick());

        final AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity == null) {
            throw new IllegalStateException("Activity is null");
        }
        activity.setSupportActionBar(view.findViewById(R.id.toolbar));

        recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity) {
            @Override
            public void onLayoutChildren(final RecyclerView.Recycler recycler,
                                         final RecyclerView.State state) {
                super.onLayoutChildren(recycler, state);
                setAppBarCollapsibleIfNeeded();
            }
        });

        if (TransitionUtils.supportsActivityTransitions()) {
            final View cardView = view.findViewById(R.id.cardView);
            LollipopUtils.applyTransitions((BaseActivity) activity, cardView != null);
        }
    }

    private void setAppBarCollapsibleIfNeeded() {
        final View root = getView();
        if (root != null) {
            final View cardHostScrollView = root.findViewById(R.id.cardHostScrollView);
            ViewUtils.setAppBarCollapsibleIfScrollableViewIsLargeEnoughToScroll(
                    root,
                    root.findViewById(R.id.appBar),
                    recyclerView,
                    ViewUtils.getOverlayTop(
                            cardHostScrollView != null
                                    ? cardHostScrollView : recyclerView));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        fab.setScaleX(1f);
        fab.setScaleY(1f);
        albumArtDim.setAlpha(1f);
        restartLoader();
    }

    @Override
    public void onStop() {
        super.onStop();
        mAdapter.changeCursor(null);
    }

    @NonNull
    protected Observable<List<Media>> queueFromAlbum(final long albumId) {
        return mQueueFactory.fromAlbum(albumId);
    }

    @NonNull
    protected Observable<List<Media>> queueFromAlbums(@NonNull final long[] albumIds) {
        return mQueueFactory.fromAlbums(albumIds, null);
    }

    private void onListItemClick(final int position,
                                 final long albumId,
                                 @Nullable final String queueName) {
        disposeOnStop(queueFromAlbum(albumId)
                .take(1)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(q -> onQueueLoaded(position, queueName, q), (t) -> onQueueLoadFailed()));
    }

    private void onQueueLoadFailed() {
        if (isAdded()) {
            onQueueEmpty();
        }
    }

    private void onQueueLoaded(final int position,
                               @Nullable final String queueName,
                               @NonNull final List<Media> queue) {
        if (isAdded()) {
            onQueueLoaded(
                    queue,
                    ViewUtils.getItemView(recyclerView, position),
                    queueName);
        }
    }

    private void onQueueLoaded(@NonNull final List<Media> queue,
                               @Nullable final View itemView,
                               @Nullable final String queueName) {
        if (queue.isEmpty()) {
            onQueueEmpty();
        } else {
            final Activity activity = getActivity();
            if (activity != null) {
                final Intent intent = Henson.with(activity).gotoQueueActivity()
                        .hasCoverTransition(false)
                        .hasItemViewTransition(false)
                        .isNowPlayingQueue(false)
                        .queue(queue)
                        .title(queueName)
                        .build();

                Bundle options = null;
                if (itemView != null) {
                    options = ActivityOptionsCompat.makeSceneTransitionAnimation(activity, itemView,
                            QueueActivity.TRANSITION_NAME_ROOT).toBundle();
                }

                startActivity(intent, options);
            }
        }
    }

    private void onQueueEmpty() {
        Toast.makeText(getActivity(), R.string.The_queue_is_empty, Toast.LENGTH_SHORT).show();
    }

    private void onPlayClick(@NonNull final long[] albumIds) {
        queueFromAlbums(albumIds)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onQueueLoaded, t -> onQueueEmpty());
    }

    private void onQueueLoaded(@NonNull final List<Media> queue) {
        final Activity activity = getActivity();
        if (activity != null && isAdded()) {
            if (queue.isEmpty()) {
                onQueueEmpty();
            } else {
                mPlaybackInitializer.setQueueAndPlay(queue, 0);
                prepareViewsAndExit(() -> NowPlayingActivity.start(activity,
                        albumArt, null));
            }
        }
    }

    private void prepareViewsAndExit(@NonNull final Runnable exitAction) {
        if (!TransitionUtils.supportsActivityTransitions() || fab.getScaleX() == 0f) {
            exitAction.run();
        } else {
            albumArtDim
                    .animate()
                    .alpha(0f)
                    .setDuration(mAnimTime)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(final Animator animation) {
                            exitAction.run();
                        }
                    });
        }
    }

    private void restartLoader() {
        final Context context = getContext();
        if (context != null) {
            if (ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                mDisposableDataOld = mDisposableData;
                mDisposableData = disposeOnStop(load()
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::onDataLoaded, (t) -> onDataLoadFailed()));
            } else {
                Log.w(TAG, "restartLoader is called, READ_EXTERNAL_STORAGE is not granted");
            }
        }
    }

    private void onDataLoadFailed() {
        if (mDisposableDataOld != null) {
            mDisposableDataOld.dispose();
            mDisposableDataOld = null;
        }
        if (mData != null) {
            mData.close();
            mData = null;
        }
        if (isAdded()) {
            showStateError();
        }
    }

    private void onDataLoaded(@NonNull final Cursor cursor) {
        loadAlbumArt(cursor);
        mAdapter.changeCursor(cursor);
        mData = cursor;

        if (cursor.getCount() == 0) {
            showStateEmpty();
        } else {
            showStateContent();
        }

        if (mDisposableDataOld != null) {
            mDisposableDataOld.dispose();
            mDisposableDataOld = null;
        }
    }

    protected abstract Observable<Cursor> load();

    private void showStateError() {
        final View root = getView();
        if (root != null) {
            fab.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            recyclerView.setVisibility(View.GONE);
            emptyContainer.setVisibility(View.GONE);
            errorContainer.setVisibility(View.VISIBLE);

            final View cardView = root.findViewById(R.id.cardView);
            if (cardView == null) {
                // Collapse for non-card-view
                appBar.setExpanded(false, false);
            } else {
                appBar.setExpanded(true, false);
            }
            setAppBarCollapsibleIfNeeded();
        }
    }

    private void showStateEmpty() {
        final View root = getView();
        if (root != null) {
            fab.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            recyclerView.setVisibility(View.GONE);
            emptyContainer.setVisibility(View.VISIBLE);
            errorContainer.setVisibility(View.GONE);

            final View cardView = root.findViewById(R.id.cardView);
            if (cardView == null) {
                // Collapse for non-card-view
                appBar.setExpanded(false, false);
            } else {
                appBar.setExpanded(true, false);
            }
            setAppBarCollapsibleIfNeeded();
        }
    }

    private void showStateContent() {
        fab.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        errorContainer.setVisibility(View.GONE);
        setAppBarCollapsibleIfNeeded();
    }

    private void onFabClick() {
        if (mData != null) {
            final long[] ids = new long[mData.getCount()];
            int i = 0;
            for (mData.moveToFirst(); !mData.isAfterLast(); mData.moveToNext(), i++) {
                ids[i] = mData.getLong(AlbumsProviderKt.COLUMN_ID);
            }
            onPlayClick(ids);
        }
    }

    private void loadAlbumArt(@NonNull final Cursor cursor) {
        if (albumArt != null) {
            final String pic = findAlbumArt(cursor);
            if (TextUtils.isEmpty(pic)) {
                mRequestManager.clear(albumArt);
                showPlaceholderAlbumArt();
            } else {
                mRequestManager
                        .asDrawable()
                        .apply(requestOptions)
                        .load(pic)
                        .listener(new AlbumArtRequestListener())
                        .into(albumArt);
            }
        }
    }

    private void showPlaceholderAlbumArt() {
        albumArt.setImageResource(R.drawable.album_art_placeholder);
        albumArt.setAlpha(1f);

        final FragmentActivity activity = getActivity();
        if (activity != null) {
            activity.supportStartPostponedEnterTransition();
        }
    }

    @Nullable
    private String findAlbumArt(@NonNull final Cursor cursor) {
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            final String art = cursor.getString(AlbumsProviderKt.COLUMN_ALBUM_ART);
            if (!TextUtils.isEmpty(art)) {
                return art;
            }
        }
        return null;
    }

    private final class AlbumArtRequestListener implements RequestListener<Drawable> {

        @Override
        public boolean onLoadFailed(
                @Nullable final GlideException e,
                @NonNull final Object model,
                @NonNull final Target<Drawable> target,
                final boolean isFirstResource) {
            showPlaceholderAlbumArt();
            return true;
        }

        @Override
        public boolean onResourceReady(
                @NonNull final Drawable resource,
                @NonNull final Object model,
                @NonNull final Target<Drawable> target,
                @NonNull final DataSource dataSource,
                final boolean isFirstResource) {
            final FragmentActivity activity = getActivity();
            if (activity != null) {
                activity.supportStartPostponedEnterTransition();
            }
            return false;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class LollipopUtils {

        static void applyTransitions(@NonNull final BaseActivity activity,
                                     final boolean hasCardView) {
            TransitionUtils.clearSharedElementsOnReturn(activity);
            activity.getWindow().setReturnTransition(hasCardView
                    ? new CardVerticalGateTransition()
                    : new VerticalGateTransition());
        }
    }
}
