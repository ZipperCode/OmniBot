package cn.com.omnimind.baselib.privileged;

interface IOmnibotPrivilegedUserService {

    String probeIdentity() = 2;

    boolean closeSessions() = 3;

    void destroy() = 16777114;

    String execute(String requestJson) = 1;
}
