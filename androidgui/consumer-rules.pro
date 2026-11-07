-keep class cn.com.omnimind.androidgui.PrivilegedDisplayUserService {
    <init>(...);
    *;
}

-keep class cn.com.omnimind.androidgui.PrivilegedDisplayHelper {
    public static void main(java.lang.String[]);
    *;
}

-keep interface cn.com.omnimind.androidgui.IPrivilegedDisplayService { *; }
-keep class cn.com.omnimind.androidgui.PrivilegedDisplayServiceStub { *; }
-keep class cn.com.omnimind.androidgui.PrivilegedDisplayServiceProxy { *; }
