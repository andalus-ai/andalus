package tech.kayys.andalus.api.grpc;

public class AndalusSdkSmokeTest {
    public static void main(String[] args) {
        System.out.println("Andalus SDK smoke test starting...");
        try {
            Class<?> factory = Class.forName("tech.kayys.aqli.factory.AqliSdkFactory");
            java.lang.reflect.Method create = factory.getMethod("createLocalSdk");
            Object sdk = create.invoke(null);
            System.out.println("createLocalSdk() returned: " + (sdk != null ? sdk.getClass().getName() : "<null>"));
            if (sdk != null) {
                try {
                    Class<?> impl = Class.forName("tech.kayys.aqli.sdk.session.ChatSessionImpl");
                    java.lang.reflect.Constructor<?> ctor = null;
                    for (java.lang.reflect.Constructor<?> c : impl.getConstructors()) {
                        Class<?>[] pts = c.getParameterTypes();
                        if (pts.length >= 3) { ctor = c; break; }
                    }
                    if (ctor != null) {
                        Object chat = ctor.newInstance(sdk, "test-model", "", Boolean.TRUE);
                        System.out.println("ChatSessionImpl created: " + (chat != null ? chat.getClass().getName() : "<null>"));
                        try {
                            Object hist = chat.getClass().getMethod("getHistory").invoke(chat);
                            System.out.println("getHistory() => " + (hist == null ? "null" : hist.toString()));
                        } catch (Throwable t) { System.out.println("getHistory failed: " + t.getMessage()); }
                    } else {
                        System.out.println("No suitable ChatSessionImpl constructor found.");
                    }
                } catch (ClassNotFoundException cnf) {
                    System.out.println("ChatSessionImpl class not found on classpath.");
                }
            }
            System.exit(0);
        } catch (ClassNotFoundException cnf) {
            System.out.println("Aqli factory not found on classpath: " + cnf.getMessage());
            System.exit(2);
        } catch (Throwable t) {
            System.out.println("Error invoking SDK: " + t);
            t.printStackTrace(System.out);
            System.exit(3);
        }
    }
}
