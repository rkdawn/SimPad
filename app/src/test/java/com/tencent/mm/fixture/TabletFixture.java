package com.tencent.mm.fixture;

public class TabletFixture {
    public static Boolean cache;
    public static Object monitor = new Object();
    public static final Boolean finalCache = Boolean.FALSE;
    public static int wrongCache;
    public static int invocations;
    public static boolean decision(Object environment) { invocations++; cache = Boolean.FALSE; return false; }
    public static boolean wrapper() { return cache != null ? cache : decision(null); }
    public static void login(android.view.View view, Object environment) {}
    public boolean nonStatic(Object environment) { return false; }
    public static boolean primitiveArgument(int environment) { return false; }
}
