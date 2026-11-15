package android.app.admin;

/**
 * Stands in for the platform class in JVM tests, ahead of android.jar on the test classpath, where every method throws
 * "not mocked". Records what the factories were given, so SystemUpdatePolicyMappingTest can read it back.
 */
public final class SystemUpdatePolicy {
    public static final int TYPE_INSTALL_AUTOMATIC = 1;
    public static final int TYPE_INSTALL_WINDOWED = 2;
    public static final int TYPE_POSTPONE = 3;

    private final int type;
    private final int start;
    private final int end;

    private SystemUpdatePolicy(int type, int start, int end) {
        this.type = type;
        this.start = start;
        this.end = end;
    }

    public static SystemUpdatePolicy createAutomaticInstallPolicy() {
        return new SystemUpdatePolicy(TYPE_INSTALL_AUTOMATIC, -1, -1);
    }

    public static SystemUpdatePolicy createWindowedInstallPolicy(int startTime, int endTime) {
        return new SystemUpdatePolicy(TYPE_INSTALL_WINDOWED, startTime, endTime);
    }

    public static SystemUpdatePolicy createPostponeInstallPolicy() {
        return new SystemUpdatePolicy(TYPE_POSTPONE, -1, -1);
    }

    public int getPolicyType() {
        return type;
    }

    public int getInstallWindowStart() {
        return start;
    }

    public int getInstallWindowEnd() {
        return end;
    }
}
