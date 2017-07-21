package me.saket.dank.widgets;

import android.app.Activity;
import android.support.annotation.CheckResult;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.google.auto.value.AutoValue;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;

public class KeyboardVisibilityDetector {

  private final Observable<KeyboardVisibilityChangeEvent> keyboardVisibilityChanges;
  private int activityContentHeightPrevious = -1;

  @AutoValue
  public abstract static class KeyboardVisibilityChangeEvent {
    public abstract boolean visible();

    public abstract int contentHeightPrevious();

    public abstract int contentHeightCurrent();

    public static KeyboardVisibilityChangeEvent create(boolean visible, int contentHeightPrevious, int contentHeightCurrent) {
      return new AutoValue_KeyboardVisibilityDetector_KeyboardVisibilityChangeEvent(visible, contentHeightPrevious, contentHeightCurrent);
    }
  }

  public KeyboardVisibilityDetector(Activity activity, int statusBarHeight) {
    View rootResizableLayout = getWindowRootResizableLayout(activity);
    View rootNonResizableLayout = ((View) rootResizableLayout.getParent());

    keyboardVisibilityChanges = Observable.create((ObservableOnSubscribe<KeyboardVisibilityChangeEvent>) emitter -> {
      ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
        int activityContentHeight = rootResizableLayout.getHeight();

        if (activityContentHeightPrevious == -1) {
          activityContentHeightPrevious = rootNonResizableLayout.getHeight() - statusBarHeight;
        }
        boolean isKeyboardVisible = activityContentHeight < rootNonResizableLayout.getHeight() - statusBarHeight;
        emitter.onNext(KeyboardVisibilityChangeEvent.create(isKeyboardVisible, activityContentHeightPrevious, activityContentHeight));

        activityContentHeightPrevious = activityContentHeight;
      };
      rootResizableLayout.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
      emitter.setCancellable(() -> rootResizableLayout.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener));
      rootResizableLayout.post(() -> layoutListener.onGlobalLayout());      // Initial value.
    })
        .distinctUntilChanged((prevEvent, currentEvent) -> prevEvent.visible() == currentEvent.visible())
        .share();
  }

  @CheckResult
  public Observable<KeyboardVisibilityChangeEvent> streamKeyboardVisibilityChanges() {
    return keyboardVisibilityChanges;
  }

  /**
   * DecorView <- does not get resized and contains space for system Ui bars.
   * - LinearLayout <- does not get resized and contains space for only status bar.
   * -- Activity content <- gets resized.
   */
  public View getWindowRootResizableLayout(Activity activity) {
    return ((ViewGroup) ((ViewGroup) activity.getWindow().getDecorView()).getChildAt(0)).getChildAt(1);
  }
}
