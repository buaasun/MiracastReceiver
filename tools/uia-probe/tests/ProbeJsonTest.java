import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProbeJsonTest {
    private static int checks;

    private static Object invoke(String method, Class<?>[] types, Object... args) throws Exception {
        Class<?> probe;
        try {
            probe = Class.forName("UiWindowProbe");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("尚未实现只读窗口探针", e);
        }
        Method target = probe.getDeclaredMethod(method, types);
        target.setAccessible(true);
        return target.invoke(null, args);
    }

    private static void equal(String wanted, Object actual) {
        checks++;
        if (!wanted.equals(actual)) {
            throw new AssertionError("expected=" + wanted + " actual=" + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?>[] object = {Object.class};
        equal("null", invoke("json", object, (Object) null));
        equal("\"连接邀请\\n\\\"\\\\\\t\\u0000\"",
                invoke("json", object, "连接邀请\n\"\\\t\u0000"));
        equal("[true,7,null,\"PC\"]", invoke("json", object, Arrays.asList(true, 7, null, "PC")));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("root", null);
        value.put("nodes", Arrays.asList("允许", "取消"));
        equal("{\"root\":null,\"nodes\":[\"允许\",\"取消\"]}", invoke("json", object, value));
        Object rejected = invoke("validateArgs", new Class<?>[]{String[].class}, (Object) new String[]{"--click"});
        equal("false", String.valueOf(rejected));
        equal("true", String.valueOf(invoke("validateArgs", new Class<?>[]{String[].class}, (Object) new String[0])));
        System.out.println("ProbeJsonTest: " + checks + " checks passed");
    }
}
