package me.saket.dank.notifs;

import static me.saket.dank.ui.media.MediaDownloadJob.ProgressState.DOWNLOADED;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.danikula.videocache.HttpProxyCacheServer;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.Function;
import me.saket.dank.R;
import me.saket.dank.data.MediaLink;
import me.saket.dank.di.Dank;
import me.saket.dank.ui.media.MediaDownloadJob;
import me.saket.dank.utils.Files2;
import me.saket.dank.utils.Intents;
import me.saket.dank.utils.RxUtils;
import me.saket.dank.utils.Strings;
import me.saket.dank.utils.Urls;
import me.saket.dank.utils.VideoHostRepository;
import me.saket.dank.utils.glide.GlideProgressTarget;
import me.saket.dank.utils.okhttp.OkHttpResponseBodyWithProgress;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import timber.log.Timber;

/**
 * Downloads images and videos to disk.
 */
public class MediaDownloadService extends Service {

  private static final String KEY_MEDIA_LINK_TO_DOWNLOAD = "mediaLinkToDownload";
  private static final String KEY_MEDIA_LINK_TO_CANCEL_DOWNLOAD = "mediaLinkToCancelDownload";
  private static final String KEY_MEDIA_LINK_TO_CANCEL_NOTIF = "mediaLinkToDismissNotif";
  private static final String KEY_ACTION = "action";
  private static final int MAX_LENGTH_FOR_NOTIFICATION_TITLE = 40;
  private static final String REQUESTCODE_RETRY_DOWNLOAD_PREFIX_ = "300";
  private static final String REQUESTCODE_SHARE_IMAGE_PREFIX_ = "301";
  private static final String REQUESTCODE_DELETE_IMAGE_PREFIX_ = "302";
  private static final String REQUESTCODE_OPEN_IMAGE_PREFIX_ = "303";
  private static final String REQUESTCODE_CANCEL_DOWNLOAD_PREFIX_ = "304";

  /**
   * Starting from Nougat, Android has a rate limiter in place which puts a certain
   * threshold between every update. The exact value is somewhere between 100ms to 200ms.
   */
  private static final int MINIMUM_GAP_BETWEEN_NOTIFICATION_UPDATEs = 201;

  @Inject HttpProxyCacheServer videoCacheServer;
  @Inject OkHttpClient okHttpClient;
  @Inject VideoHostRepository videoHostRepository;

  private CompositeDisposable disposables = new CompositeDisposable();
  private final Set<String> ongoingDownloadUrls = new HashSet<>();
  private final Map<String, MediaDownloadJob> downloadJobsWithVisibleNotif = new HashMap<>();
  private final Relay<MediaLink> downloadRequestStream = PublishRelay.create();
  private final Relay<MediaLink> downloadCancellationStream = PublishRelay.create();

  enum Action {
    ENQUEUE_DOWNLOAD,
    CANCEL_DOWNLOAD,
    CANCEL_NOTIFICATION,            // Canceled programmatically.
  }

  public static void enqueueDownload(Context context, MediaLink mediaLink) {
    Intent intent = new Intent(context, MediaDownloadService.class);
    intent.putExtra(KEY_MEDIA_LINK_TO_DOWNLOAD, mediaLink);
    intent.putExtra(KEY_ACTION, Action.ENQUEUE_DOWNLOAD);
    context.startService(intent);
  }

  public static Intent createCancelDownloadIntent(Context context, MediaLink mediaLink) {
    Intent intent = new Intent(context, MediaDownloadService.class);
    intent.putExtra(KEY_MEDIA_LINK_TO_CANCEL_DOWNLOAD, mediaLink);
    intent.putExtra(KEY_ACTION, Action.CANCEL_DOWNLOAD);
    return intent;
  }

  /**
   * Called when a notification is canceled internally by the app.
   */
  public static void cancelNotification(Context context, MediaLink mediaLink) {
    Intent intent = new Intent(context, MediaDownloadService.class);
    intent.putExtra(KEY_MEDIA_LINK_TO_CANCEL_NOTIF, mediaLink);
    intent.putExtra(KEY_ACTION, Action.CANCEL_NOTIFICATION);
    context.startService(intent);
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    Dank.dependencyInjector().inject(this);
    super.onCreate();

    // Stop service when all downloads finish.
    Relay<Collection<MediaDownloadJob>> activeDownloadsProgressChangeStream = PublishRelay.create();
    disposables.add(
        activeDownloadsProgressChangeStream
            .map(activeDownloadJobs -> {
              boolean allDownloadsComplete = true;
              for (MediaDownloadJob downloadJob : activeDownloadJobs) {
                if (downloadJob.progressState() != DOWNLOADED) {
                  allDownloadsComplete = false;
                }
              }
              return allDownloadsComplete;
            })
            .distinctUntilChanged()
            .subscribe(allDownloadsComplete -> {
              if (allDownloadsComplete) {
                stopSelf();
              }
            })
    );

    disposables.add(
        downloadCancellationStream.subscribe(mediaLinkToCancel -> {
          ongoingDownloadUrls.remove(mediaLinkToCancel.originalUrl());
          NotificationManagerCompat.from(this).cancel(createNotificationIdFor(mediaLinkToCancel));
        })
    );

    disposables.add(
        downloadRequestStream
            .sample(MINIMUM_GAP_BETWEEN_NOTIFICATION_UPDATEs, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
            .doOnNext(linkToQueue -> {
              MediaDownloadJob downloadJobToQueue = MediaDownloadJob.createQueued(linkToQueue, System.currentTimeMillis());
              downloadJobsWithVisibleNotif.put(downloadJobToQueue.mediaLink().originalUrl(), downloadJobToQueue);
              updateIndividualProgressNotification(downloadJobToQueue, createNotificationIdFor(linkToQueue));
            })
            .concatMap(linkToDownload -> {
              Observable<MediaDownloadJob> downloadStream;

              if (linkToDownload.isVideo()) {
                // TODO: Once we resolve all links on start of MediaAlbumViewerActivity, this shouldn't be needed:
                downloadStream = videoHostRepository.fetchActualVideoUrlIfNeeded(linkToDownload)
                    .flatMapObservable(resolvedLink -> downloadVideoAndStreamProgress(resolvedLink))
                    .compose(RxUtils.applySchedulers());
              } else {
                downloadStream = downloadImageAndStreamProgress(linkToDownload);
              }

              return downloadStream
                  .map(moveFileToUserSpaceOnDownload())
                  .doOnTerminate(() -> ongoingDownloadUrls.remove(linkToDownload.originalUrl()))
                  .doOnError(e -> Timber.e(e, "Couldn't download media"))
                  .onErrorReturnItem(MediaDownloadJob.createFailed(linkToDownload, System.currentTimeMillis()))
                  .takeUntil(downloadCancellationStream.filter(linkToCancel -> linkToCancel.equals(linkToDownload)))
                  .sample(MINIMUM_GAP_BETWEEN_NOTIFICATION_UPDATEs, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread(), true);
            })
            .subscribe(downloadJob -> {
              int notificationId = createNotificationIdFor(downloadJob.mediaLink());

              switch (downloadJob.progressState()) {
                case CONNECTING:
                  updateIndividualProgressNotification(downloadJob, notificationId);
                  break;

                case IN_FLIGHT:
                  updateIndividualProgressNotification(downloadJob, notificationId);
                  break;

                case FAILED:
                  displayErrorNotification(downloadJob, notificationId);
                  ongoingDownloadUrls.remove(downloadJob.mediaLink().originalUrl());
                  break;

                case DOWNLOADED:
                  displaySuccessNotification(downloadJob, notificationId);
                  ongoingDownloadUrls.remove(downloadJob.mediaLink().originalUrl());
                  break;

                default:
                  throw new UnsupportedOperationException("Unknown state: " + downloadJob.progressState());
              }

              downloadJobsWithVisibleNotif.put(downloadJob.mediaLink().originalUrl(), downloadJob);
              activeDownloadsProgressChangeStream.accept(downloadJobsWithVisibleNotif.values());
            })
    );
  }

  @Override
  public void onDestroy() {
    disposables.clear();
    super.onDestroy();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Action serviceAction = (Action) intent.getSerializableExtra(KEY_ACTION);
    switch (serviceAction) {
      case ENQUEUE_DOWNLOAD:
        MediaLink mediaLinkToDownload = (MediaLink) intent.getSerializableExtra(KEY_MEDIA_LINK_TO_DOWNLOAD);
        boolean isDownloadAlreadyOngoing = ongoingDownloadUrls.contains(mediaLinkToDownload.originalUrl());
        if (!isDownloadAlreadyOngoing) {
          ongoingDownloadUrls.add(mediaLinkToDownload.originalUrl());
          downloadRequestStream.accept(mediaLinkToDownload);
        } else {
          Timber.w("Ignoring ongoing download");
        }
        break;

      case CANCEL_DOWNLOAD:
        MediaLink mediaLinkToCancel = (MediaLink) intent.getSerializableExtra(KEY_MEDIA_LINK_TO_CANCEL_DOWNLOAD);
        downloadCancellationStream.accept(mediaLinkToCancel);
        break;

      case CANCEL_NOTIFICATION:
        MediaLink mediaLinkToCancelNotif = (MediaLink) intent.getSerializableExtra(KEY_MEDIA_LINK_TO_CANCEL_NOTIF);
        downloadCancellationStream.accept(mediaLinkToCancelNotif);
        break;

      default:
        throw new UnsupportedOperationException("Unknown action: " + serviceAction);
    }
    return START_NOT_STICKY;
  }

  private void updateIndividualProgressNotification(MediaDownloadJob mediaDownloadJob, int notificationId) {
    boolean isQueued = mediaDownloadJob.progressState() == MediaDownloadJob.ProgressState.QUEUED;
    String notificationTitle = ellipsizeNotifTitleIfExceedsMaxLength(getString(
        isQueued ? R.string.mediadownloadnotification_queued_title : R.string.mediadownloadnotification_progress_title,
        mediaDownloadJob.mediaLink().originalUrl()
    ));
    boolean indeterminateProgress = mediaDownloadJob.progressState() == MediaDownloadJob.ProgressState.CONNECTING;

    NotificationCompat.Action cancelAction = new NotificationCompat.Action(0,
        getString(R.string.mediadownloadnotification_cancel),
        PendingIntent.getService(this,
            createPendingIntentRequestId(REQUESTCODE_CANCEL_DOWNLOAD_PREFIX_, notificationId),
            createCancelDownloadIntent(this, mediaDownloadJob.mediaLink()),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    );

    NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
        .setContentTitle(notificationTitle)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setLocalOnly(true)   // Hide from wearables.
        .setWhen(mediaDownloadJob.timestamp())
        .setColor(ContextCompat.getColor(this, R.color.notification_icon_color))
        // Keep notification of ongoing-download above queued-downloads.
        .setPriority(isQueued ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT)
        .setProgress(100 /* max */, mediaDownloadJob.downloadProgress(), indeterminateProgress)
        .addAction(cancelAction);

    if (mediaDownloadJob.progressState() != MediaDownloadJob.ProgressState.CONNECTING) {
      notificationBuilder = notificationBuilder.setContentText(mediaDownloadJob.downloadProgress() + "%");
    }

    Notification notification = notificationBuilder.build();
    NotificationManagerCompat.from(this).notify(notificationId, notification);
  }

  /**
   * We're ellipsizing the title so that the notification's content text (which is the progress
   * percentage at the time of writing this) is always visible.
   */
  private static String ellipsizeNotifTitleIfExceedsMaxLength(String fullTitle) {
    return fullTitle.length() > MAX_LENGTH_FOR_NOTIFICATION_TITLE
        ? Strings.safeSubstring(fullTitle, MAX_LENGTH_FOR_NOTIFICATION_TITLE) + "…"
        : fullTitle;
  }

  private void displayErrorNotification(MediaDownloadJob failedDownloadJob, int notificationId) {
    Intent retryIntent = MediaNotifActionReceiver.createRetryDownloadIntent(this, failedDownloadJob);
    PendingIntent retryPendingIntent = PendingIntent.getBroadcast(this,
        createPendingIntentRequestId(REQUESTCODE_RETRY_DOWNLOAD_PREFIX_, notificationId),
        retryIntent,
        PendingIntent.FLAG_UPDATE_CURRENT
    );

    Notification errorNotification = new NotificationCompat.Builder(this)
        .setContentTitle(getString(
            failedDownloadJob.mediaLink().isVideo()
                ? R.string.mediadownloadnotification_failed_to_save_video
                : R.string.mediadownloadnotification_failed_to_save_image
        ))
        .setContentText(getString(R.string.mediadownloadnotification_tap_to_retry_url, failedDownloadJob.mediaLink().originalUrl()))
        .setSmallIcon(R.drawable.ic_error_24dp)
        .setOngoing(false)
        .setLocalOnly(true)   // Hide from wearables.
        .setWhen(failedDownloadJob.timestamp()) // TODO: set this.
        .setColor(ContextCompat.getColor(this, R.color.notification_icon_color))
        .setContentIntent(retryPendingIntent)
        .setAutoCancel(false)
        .build();
    NotificationManagerCompat.from(this).notify(notificationId, errorNotification);
  }

  /**
   * Generate a notification with a preview of the media. Images and videos both work, thanks to Glide.
   */
  private void displaySuccessNotification(MediaDownloadJob completedDownloadJob, int notificationId) {
    // Content intent.
    Uri mediaContentUri = FileProvider.getUriForFile(this, getString(R.string.file_provider_authority), completedDownloadJob.downloadedFile());

    PendingIntent viewImagePendingIntent = PendingIntent.getActivity(this,
        createPendingIntentRequestId(REQUESTCODE_OPEN_IMAGE_PREFIX_, notificationId),
        Intents.createForViewingMedia(this, mediaContentUri),
        PendingIntent.FLAG_CANCEL_CURRENT
    );

    // Share action.
    PendingIntent shareImagePendingIntent = PendingIntent.getBroadcast(this,
        createPendingIntentRequestId(REQUESTCODE_SHARE_IMAGE_PREFIX_, notificationId),
        MediaNotifActionReceiver.createShareImageIntent(this, completedDownloadJob),
        PendingIntent.FLAG_CANCEL_CURRENT
    );
    NotificationCompat.Action shareImageAction = new NotificationCompat.Action(0,
        getString(R.string.mediadownloadnotification_share),
        shareImagePendingIntent
    );

    // Delete action.
    PendingIntent deleteImagePendingIntent = PendingIntent.getBroadcast(this,
        createPendingIntentRequestId(REQUESTCODE_DELETE_IMAGE_PREFIX_, notificationId),
        MediaNotifActionReceiver.createDeleteImageIntent(this, completedDownloadJob),
        PendingIntent.FLAG_CANCEL_CURRENT
    );
    NotificationCompat.Action deleteImageAction = new NotificationCompat.Action(0,
        getString(R.string.mediadownloadnotification_delete),
        deleteImagePendingIntent
    );

    Glide.with(this)
        .asBitmap()
        .load(Uri.fromFile(completedDownloadJob.downloadedFile()))
        .into(new SimpleTarget<Bitmap>() {
          @Override
          public void onResourceReady(Bitmap imageBitmap, Transition<? super Bitmap> transition) {
            Notification successNotification = new NotificationCompat.Builder(MediaDownloadService.this)
                .setContentTitle(getString(
                    completedDownloadJob.mediaLink().isVideo()
                        ? R.string.mediadownloadnotification_sucesss_title_for_video
                        : R.string.mediadownloadnotification_success_title_for_image
                ))
                .setContentText(getString(R.string.mediadownloadnotification_success_body))
                .setSmallIcon(R.drawable.ic_done_24dp)
                .setOngoing(false)
                .setLocalOnly(true)
                .setWhen(completedDownloadJob.timestamp())
                .setColor(ContextCompat.getColor(MediaDownloadService.this, R.color.notification_icon_color))
                .setContentIntent(viewImagePendingIntent)
                .addAction(shareImageAction)
                .addAction(deleteImageAction)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(imageBitmap)
                    .setSummaryText(completedDownloadJob.mediaLink().originalUrl())
                )
                .build();
            NotificationManagerCompat.from(MediaDownloadService.this).notify(notificationId, successNotification);
          }
        });
  }

  /**
   * Download an image and streams progress updates.
   */
  private Observable<MediaDownloadJob> downloadImageAndStreamProgress(MediaLink mediaLink) {
    String imageUrl = mediaLink.originalUrl();
    long downloadStartTimeMillis = System.currentTimeMillis();

    return Observable.create(emitter -> {
      Target<File> fileTarget = new SimpleTarget<File>() {
        @Override
        public void onResourceReady(File downloadedFile, Transition<? super File> transition) {
          long downloadCompleteTimeMillis = System.currentTimeMillis();
          emitter.onNext(MediaDownloadJob.createDownloaded(mediaLink, downloadedFile, downloadCompleteTimeMillis));
          emitter.onComplete();
        }

        @Override
        public void onLoadFailed(@Nullable Drawable errorDrawable) {
          long downloadFailTimeMillis = System.currentTimeMillis();
          emitter.onNext(MediaDownloadJob.createFailed(mediaLink, downloadFailTimeMillis));
          emitter.onComplete();
        }
      };

      GlideProgressTarget<String, File> progressTarget = new GlideProgressTarget<String, File>(fileTarget) {
        @Override
        public float getGranularityPercentage() {
          return 1f;
        }

        @Override
        protected void onConnecting() {
          emitter.onNext(MediaDownloadJob.createConnecting(mediaLink, downloadStartTimeMillis));
        }

        @Override
        protected void onDownloading(long bytesRead, long expectedLength) {
          int progress = (int) (100 * (float) bytesRead / expectedLength);
          emitter.onNext(MediaDownloadJob.createProgress(mediaLink, progress, downloadStartTimeMillis));
        }

        @Override
        protected void onDownloaded() {}

        @Override
        protected void onDelivered() {}
      };
      progressTarget.setModel(this, imageUrl);

      Glide.with(this).download(imageUrl).into(progressTarget);
      emitter.setCancellable(() -> Glide.with(this).clear(progressTarget));
    });
  }

  /**
   * Download a video and streams progress updates.
   */
  private Observable<MediaDownloadJob> downloadVideoAndStreamProgress(MediaLink linkToDownload) {
    return Observable.create(emitter -> {
      String videoUrl = linkToDownload.highQualityVideoUrl();
      long downloadStartTimeMillis = System.currentTimeMillis();

      if (videoCacheServer.isCached(videoUrl)) {
        emitter.onNext(MediaDownloadJob.createConnecting(linkToDownload, downloadStartTimeMillis));

        String cachedVideoFileUrl = videoCacheServer.getProxyUrl(videoUrl);
        File cachedVideoFile = new File(Uri.parse(cachedVideoFileUrl).getPath());
        emitter.onNext(MediaDownloadJob.createDownloaded(linkToDownload, cachedVideoFile, System.currentTimeMillis()));

      } else {
        // Proxy through VideoCacheServer so that the downloaded video also gets saved to cache.
        String videoProxyUrl = videoCacheServer.getProxyUrl(videoUrl, false);

        emitter.onNext(MediaDownloadJob.createConnecting(linkToDownload, downloadStartTimeMillis));

        // Emit progress updates while reading the video's input stream.
        Request downloadRequest = new Request.Builder()
            .url(videoProxyUrl)
            .get()
            .build();
        Response videoResponse = okHttpClient.newCall(downloadRequest).execute();
        Response responseWithProgressListener = videoResponse.newBuilder()
            .body(new OkHttpResponseBodyWithProgress(
                downloadRequest.url(),
                videoResponse.body(),
                (url, bytesRead, expectedContentLength) -> {
                  if (bytesRead < expectedContentLength) {
                    int progress = (int) (100 * (float) bytesRead / expectedContentLength);
                    emitter.onNext(MediaDownloadJob.createProgress(linkToDownload, progress, downloadStartTimeMillis));
                  }
                }
            ))
            .build();

        if (!responseWithProgressListener.isSuccessful()) {
          throw new IOException("Unexpected code: " + responseWithProgressListener);
        }

        // Write to a temporary file, that will later get replaced by moveFileToUserSpaceOnDownload().
        File videoTempFile = new File(getCacheDir(), Urls.parseFileNameWithExtension(videoUrl));
        try (BufferedSource bufferedSource = responseWithProgressListener.body().source()) {
          BufferedSink bufferedSink = Okio.buffer(Okio.sink(videoTempFile));
          bufferedSink.writeAll(bufferedSource);
          bufferedSink.close();
        }

        long downloadCompleteTimeMillis = System.currentTimeMillis();
        emitter.onNext(MediaDownloadJob.createDownloaded(linkToDownload, videoTempFile, downloadCompleteTimeMillis));
        emitter.onComplete();
      }
    });
  }

  @NonNull
  private Function<MediaDownloadJob, MediaDownloadJob> moveFileToUserSpaceOnDownload() {
    return downloadJobUpdate -> {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
          throw new AssertionError("Storage permission not granted");
        }
      }

      if (downloadJobUpdate.progressState() == DOWNLOADED) {
        MediaLink downloadedMediaLink = downloadJobUpdate.mediaLink();
        String mediaUrl = downloadedMediaLink.isVideo() ? downloadedMediaLink.highQualityVideoUrl() : downloadedMediaLink.originalUrl();
        String mediaFileName = Urls.parseFileNameWithExtension(mediaUrl);
        File userAccessibleFile = Files2.copyFileToPicturesDirectory(getResources(), downloadJobUpdate.downloadedFile(), mediaFileName);
        return MediaDownloadJob.createDownloaded(downloadedMediaLink, userAccessibleFile, downloadJobUpdate.timestamp());

      } else {
        return downloadJobUpdate;
      }
    };
  }

  public static int createNotificationIdFor(MediaLink mediaLink) {
    return (NotificationConstants.ID_MEDIA_DOWNLOAD_PROGRESS_PREFIX_ + mediaLink.originalUrl()).hashCode();
  }

  public static int createPendingIntentRequestId(String idPrefix, int idSuffix) {
    boolean isNegative = idSuffix < 0;
    long requestId = Long.parseLong(idPrefix + Math.abs(idSuffix));
    return (int) requestId * (isNegative ? -1 : 1);
  }
}