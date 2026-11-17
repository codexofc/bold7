# R8 rules for every APK that embeds core/platform.
#
# Hidden framework classes this module uses: compiled against stubs/framework.jar
# (compileOnly), present on the Q25 at run time, unknown to R8, which only sees android.jar.
# One line per class, so a new hidden dependency stops the build until it is listed here.
-dontwarn android.app.ActivityTaskManager
-dontwarn android.app.IActivityTaskManager
-dontwarn android.app.IApplicationThread
-dontwarn android.app.ITaskStackListener
-dontwarn android.app.TaskStackListener
-dontwarn android.app.WindowConfiguration
-dontwarn android.app.contextualsearch.ContextualSearchConfig
-dontwarn android.os.IRemoteCallback
-dontwarn android.os.IRemoteCallback$Stub
-dontwarn android.view.InsetsState
-dontwarn android.view.RemoteAnimationTarget
-dontwarn android.window.IRemoteTransition
-dontwarn android.window.IRemoteTransition$Stub
-dontwarn android.window.IRemoteTransitionFinishedCallback
-dontwarn android.window.RemoteTransition
-dontwarn android.window.TaskSnapshot
-dontwarn android.window.TransitionFilter
-dontwarn android.window.TransitionFilter$Requirement
-dontwarn android.window.TransitionInfo
-dontwarn android.window.TransitionInfo$Change
-dontwarn android.window.TransitionInfo$Root
-dontwarn android.window.WindowAnimationState
-dontwarn android.window.WindowContainerTransaction
-dontwarn com.android.internal.app.LocalePicker

# Our overrides of those hidden classes' methods (IRemoteTransition.Stub, TaskStackListener): the framework calls them, but R8, blind to the supertypes, sees
# them unused and removes them (checked in the dex). "class * extends <hidden>" matches nothing
# for the same reason, hence the method names within this module's package.
-keepclassmembers class dev.bold7.platform.** {
    public void onTaskSnapshotChanged(...);
    public void onTaskRemoved(...);
    public void startAnimation(...);
    public void mergeAnimation(...);
    public void takeOverAnimation(...);
    public void onTransitionConsumed(...);
}
