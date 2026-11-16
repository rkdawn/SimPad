package io.github.rkdawn.simpad.fixtures;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

public final class BaselineTinkerFixtures {
    public static class PostOnly {
        protected void onBaseContextAttached(Context context, long first, long second) { }
    }
    public static class EarlyOnly {
        public ClassLoader mCurrentClassLoader;
        public Handler mInlineFence;
        protected Handler createInlineFence(Application app, int flags, String name, boolean verify, long first, long second, Intent intent) { return null; }
    }
    public static class Both extends EarlyOnly {
        protected void onBaseContextAttached(Context context, long first, long second) { }
    }
    public static class PrivatePost {
        private void onBaseContextAttached(Context context, long first, long second) { }
    }
    public static class StaticPost {
        public static void onBaseContextAttached(Context context, long first, long second) { }
    }
    public static class WrongParameters {
        protected void onBaseContextAttached(Context context) { }
    }
    public static class MissingEarlyFields {
        protected Handler createInlineFence(Application app, int flags, String name, boolean verify, long first, long second, Intent intent) { return null; }
    }
    public abstract static class AbstractPost {
        protected abstract void onBaseContextAttached(Context context, long first, long second);
    }
}
