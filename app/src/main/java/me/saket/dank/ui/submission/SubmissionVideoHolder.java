package me.saket.dank.ui.submission;

import android.graphics.Bitmap;
import android.net.Uri;
import android.support.annotation.CheckResult;
import android.view.View;
import android.widget.ProgressBar;

import com.danikula.videocache.HttpProxyCacheServer;
import com.devbrackets.android.exomedia.ui.widget.VideoView;
import com.f2prateek.rx.preferences2.Preference;
import com.jakewharton.rxbinding2.internal.Notification;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;

import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;

import dagger.Lazy;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import me.saket.dank.data.NetworkStrategy;
import me.saket.dank.data.links.MediaLink;
import me.saket.dank.ui.media.MediaHostRepository;
import me.saket.dank.utils.ExoPlayerManager;
import me.saket.dank.utils.NetworkStateListener;
import me.saket.dank.utils.Views;
import me.saket.dank.widgets.DankVideoControlsView;
import me.saket.dank.widgets.InboxUI.ExpandablePageLayout;
import me.saket.dank.widgets.ScrollingRecyclerViewSheet;

/**
 * Manages loading of video in {@link SubmissionPageLayout}.
 */
public class SubmissionVideoHolder {

  private final Relay<Integer> videoWidthChangeStream = PublishRelay.create();
  private final Relay<Object> videoPreparedStream = BehaviorRelay.create();
  private final Lazy<MediaHostRepository> mediaHostRepository;
  private final Lazy<HttpProxyCacheServer> httpProxyCacheServer;
  private final Lazy<NetworkStateListener> networkStateListener;
  private final Lazy<Preference<NetworkStrategy>> highResolutionMediaNetworkStrategy;
  private final Lazy<Preference<NetworkStrategy>> autoPlayVideosNetworkStrategy;

  private ExoPlayerManager exoPlayerManager;
  private VideoView contentVideoView;
  private ScrollingRecyclerViewSheet commentListParentSheet;
  private ExpandablePageLayout submissionPageLayout;
  private int deviceDisplayHeight;
  private int statusBarHeight;
  private int minimumGapWithBottom;
  private ProgressBar contentLoadProgressView;

  @Inject
  public SubmissionVideoHolder(
      Lazy<MediaHostRepository> mediaHostRepository,
      Lazy<HttpProxyCacheServer> httpProxyCacheServer,
      Lazy<NetworkStateListener> networkStateListener,
      @Named("high_resolution_media_network_strategy") Lazy<Preference<NetworkStrategy>> highResolutionMediaNetworkStrategy,
      @Named("auto_play_videos_network_strategy") Lazy<Preference<NetworkStrategy>> autoPlayVideosNetworkStrategy)
  {
    this.mediaHostRepository = mediaHostRepository;
    this.httpProxyCacheServer = httpProxyCacheServer;
    this.networkStateListener = networkStateListener;
    this.highResolutionMediaNetworkStrategy = highResolutionMediaNetworkStrategy;
    this.autoPlayVideosNetworkStrategy = autoPlayVideosNetworkStrategy;
  }

  /**
   * <var>displayWidth</var> and <var>statusBarHeight</var> are used for capturing the video's bitmap,
   * which is in turn used for generating status bar tint. To minimize bitmap creation time, a Bitmap
   * of height equal to the status bar is created instead of the entire video height.
   *
   * @param minimumGapWithBottom The difference between video's bottom and the window's bottom.
   */
  public void setup(
      ExoPlayerManager exoPlayerManager,
      VideoView contentVideoView,
      ScrollingRecyclerViewSheet commentListParentSheet,
      ProgressBar contentLoadProgressView,
      ExpandablePageLayout submissionPageLayout,
      int deviceDisplayHeight,
      int statusBarHeight,
      int minimumGapWithBottom)
  {
    this.exoPlayerManager = exoPlayerManager;
    this.contentVideoView = contentVideoView;
    this.commentListParentSheet = commentListParentSheet;
    this.contentLoadProgressView = contentLoadProgressView;
    this.submissionPageLayout = submissionPageLayout;
    this.deviceDisplayHeight = deviceDisplayHeight;
    this.statusBarHeight = statusBarHeight;
    this.minimumGapWithBottom = minimumGapWithBottom;

    DankVideoControlsView controlsView = new DankVideoControlsView(contentVideoView.getContext());
    contentVideoView.setControls(controlsView);
    contentVideoView.setOnPreparedListener(() -> videoPreparedStream.accept(Notification.INSTANCE));
  }

  @CheckResult
  public Completable load(MediaLink mediaLink) {
    // Later hidden inside loadVideo(), when the video's height becomes available.
    contentLoadProgressView.setVisibility(View.VISIBLE);

    Single<Boolean> loadHighQualityVideoPredicate = highResolutionMediaNetworkStrategy.get()
        .asObservable()
        .flatMap(networkStateListener.get()::streamNetworkInternetCapability)
        .firstOrError();

    Completable autoPlayVideoIfAllowed = autoPlayVideosNetworkStrategy.get()
        .asObservable()
        .flatMap(networkStateListener.get()::streamNetworkInternetCapability)
        .firstOrError()
        .flatMapCompletable(canAutoPlay -> Completable.fromAction(() -> exoPlayerManager.startVideoPlayback()));

    return mediaHostRepository.get().resolveActualLinkIfNeeded(mediaLink)
        .subscribeOn(Schedulers.io())
        .flatMap(resolvedLink -> loadHighQualityVideoPredicate
            .map(loadHQ -> loadHQ ? resolvedLink.highQualityUrl() : resolvedLink.lowQualityUrl()))
        .observeOn(AndroidSchedulers.mainThread())
        .flatMapCompletable(videoUrl -> loadVideo(videoUrl))
        .andThen(autoPlayVideoIfAllowed);
  }

  @CheckResult
  public Observable<Bitmap> streamVideoFirstFrameBitmaps() {
    return Observable.zip(videoPreparedStream, videoWidthChangeStream, (o, videoWidth) -> videoWidth)
        .delay(new Function<Integer, ObservableSource<Integer>>() {
          private boolean firstDelayDone;

          @Override
          public ObservableSource<Integer> apply(Integer videoWidth) throws Exception {
            // onPrepared() gets called way too early when loading a video for the first time.
            // We'll manually add a delay.
            if (firstDelayDone) {
              return Observable.just(videoWidth);
            } else {
              firstDelayDone = true;
              return Observable.just(videoWidth).delay(200, TimeUnit.MILLISECONDS);
            }
          }
        })
        .map(videoWidth -> exoPlayerManager.getBitmapOfCurrentVideoFrame(videoWidth, statusBarHeight, Bitmap.Config.RGB_565));
  }

  private Completable loadVideo(String videoUrl) {
    return Completable.fromAction(() -> {
      exoPlayerManager.setOnVideoSizeChangeListener((resizedVideoWidth, resizedVideoHeight, actualVideoWidth, actualVideoHeight) -> {
        int contentHeightWithoutKeyboard = deviceDisplayHeight - minimumGapWithBottom - statusBarHeight;
        int adjustedVideoViewHeight = Math.min(contentHeightWithoutKeyboard, resizedVideoHeight);
        Views.setHeight(contentVideoView, adjustedVideoViewHeight);

        // Wait for the height change to happen and then reveal the video.
        Views.executeOnNextLayout(contentVideoView, () -> {
          contentLoadProgressView.setVisibility(View.GONE);

          // Warning: Avoid getting the height from view instead of reusing adjustedVideoViewHeight.
          // I was seeing older values instead of the one passed to setHeight().
          int videoHeightMinusToolbar = adjustedVideoViewHeight - commentListParentSheet.getTop();
          commentListParentSheet.setScrollingEnabled(true);
          commentListParentSheet.setMaxScrollY(videoHeightMinusToolbar);
          commentListParentSheet.scrollTo(videoHeightMinusToolbar, submissionPageLayout.isExpanded() /* smoothScroll */);

          exoPlayerManager.setOnVideoSizeChangeListener(null);
          videoWidthChangeStream.accept(actualVideoWidth);
        });
      });

      String cachedVideoUrl = httpProxyCacheServer.get().getProxyUrl(videoUrl);
      exoPlayerManager.setVideoUriToPlayInLoop(Uri.parse(cachedVideoUrl));

    });
  }

  public void pausePlayback() {
    exoPlayerManager.pauseVideoPlayback();
  }
}

