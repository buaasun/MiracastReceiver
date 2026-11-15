import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 临时只读窗口探针；Android 隐藏接口仅通过反射调用。 */
public final class UiWindowProbe {
    private static final int MAX_NODES = 512;
    private static final int MAX_DEPTH = 16;
    private static int remaining = MAX_NODES;

    static boolean validateArgs(String[] args) {
        return args.length == 0;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }

    private static Object get(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0]);
    }

    private static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = value.toString();
        return result.length() <= 512 ? result : result.substring(0, 512);
    }

    private static List<Integer> bounds(Object target) throws Exception {
        Class<?> rectClass = Class.forName("android.graphics.Rect");
        Object rect = rectClass.getConstructor().newInstance();
        call(target, "getBoundsInScreen", new Class<?>[]{rectClass}, rect);
        List<Integer> values = new ArrayList<>();
        for (String key : new String[]{"left", "top", "right", "bottom"}) {
            values.add(rectClass.getField(key).getInt(rect));
        }
        return values;
    }

    private static void recycle(Object target) throws Exception {
        if (target != null) get(target, "recycle");
    }

    private static void nodes(Object node, int parent, int depth, List<Object> result) throws Exception {
        if (remaining == 0) return;
        remaining--;
        int index = result.size();
        Map<String, Object> value = map();
        value.put("index", index);
        value.put("parent", parent);
        value.put("package", text(get(node, "getPackageName")));
        value.put("text", text(get(node, "getText")));
        value.put("description", text(get(node, "getContentDescription")));
        value.put("resource_id", text(get(node, "getViewIdResourceName")));
        value.put("class", text(get(node, "getClassName")));
        value.put("clickable", get(node, "isClickable"));
        value.put("enabled", get(node, "isEnabled"));
        value.put("visible", get(node, "isVisibleToUser"));
        value.put("bounds", bounds(node));
        int childCount = (Integer) get(node, "getChildCount");
        value.put("child_count", childCount);
        value.put("truncated", childCount > 0 && (depth >= MAX_DEPTH || remaining == 0 || childCount > 128));
        result.add(value);
        if (depth >= MAX_DEPTH) return;
        for (int i = 0; i < Math.min(childCount, 128) && remaining > 0; i++) {
            Object child = call(node, "getChild", new Class<?>[]{int.class}, i);
            try {
                if (child != null) nodes(child, index, depth + 1, result);
            } finally {
                recycle(child);
            }
        }
    }

    private static Map<String, Object> rootSummary(Object root) throws Exception {
        Map<String, Object> result = map();
        result.put("present", root != null);
        if (root != null) result.put("package", text(get(root, "getPackageName")));
        return result;
    }

    private static List<Object> windows(Object automation) throws Exception {
        List<?> source = (List<?>) get(automation, "getWindows");
        List<Object> result = new ArrayList<>();
        try {
            for (int i = 0; i < Math.min(source.size(), 64); i++) {
                Object window = source.get(i);
                Map<String, Object> value = map();
                Object root = null;
                try {
                    value.put("id", get(window, "getId"));
                    value.put("type", get(window, "getType"));
                    value.put("layer", get(window, "getLayer"));
                    value.put("active", get(window, "isActive"));
                    value.put("focused", get(window, "isFocused"));
                    value.put("accessibility_focused", get(window, "isAccessibilityFocused"));
                    value.put("title", text(get(window, "getTitle")));
                    value.put("bounds", bounds(window));
                    root = get(window, "getRoot");
                    value.put("root", rootSummary(root));
                    if (root != null && "android".equals(String.valueOf(get(root, "getPackageName")))) {
                        List<Object> tree = new ArrayList<>();
                        nodes(root, -1, 0, tree);
                        value.put("nodes", tree);
                    }
                } catch (Exception e) {
                    value.put("error", e.toString());
                } finally {
                    recycle(root);
                }
                result.add(value);
            }
        } finally {
            for (Object window : source) recycle(window);
        }
        return result;
    }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        StringBuilder result = new StringBuilder();
        if (value instanceof Map) {
            result.append('{');
            for (Object raw : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
                if (result.length() > 1) result.append(',');
                result.append(json(entry.getKey().toString())).append(':').append(json(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable || value.getClass().isArray()) {
            result.append('[');
            if (value instanceof Iterable) {
                for (Object item : (Iterable<?>) value) {
                    if (result.length() > 1) result.append(',');
                    result.append(json(item));
                }
            } else {
                for (int i = 0; i < Array.getLength(value); i++) {
                    if (i > 0) result.append(',');
                    result.append(json(Array.get(value, i)));
                }
            }
            return result.append(']').toString();
        }
        result.append('"');
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"': result.append("\\\""); break;
                case '\\': result.append("\\\\"); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (c < 32 || Character.isSurrogate(c)) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
            }
        }
        return result.append('"').toString();
    }

    public static void main(String[] args) {
        if (!validateArgs(args)) {
            System.err.println("只支持无参数只读运行");
            System.exit(2);
        }
        Map<String, Object> result = map();
        result.put("schema_version", 1);
        result.put("read_only", true);
        Object thread = null;
        Object automation = null;
        boolean connected = false;
        int exitCode = 0;
        try {
            Class<?> threadClass = Class.forName("android.os.HandlerThread");
            thread = threadClass.getConstructor(String.class).newInstance("uia-window-probe");
            get(thread, "start");
            Object looper = get(thread, "getLooper");
            Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
            Object connection = connectionClass.getConstructor().newInstance();
            Class<?> automationClass = Class.forName("android.app.UiAutomation");
            automation = automationClass.getConstructor(Class.forName("android.os.Looper"),
                    Class.forName("android.app.IUiAutomationConnection")).newInstance(looper, connection);
            // 1 = FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，保留既有服务。
            call(automation, "connect", new Class<?>[]{int.class}, 1);
            connected = true;
            Object info = get(automation, "getServiceInfo");
            Field flags = info.getClass().getField("flags");
            flags.setInt(info, flags.getInt(info) | 64 | 2);
            call(automation, "setServiceInfo", new Class<?>[]{Class.forName("android.accessibilityservice.AccessibilityServiceInfo")}, info);
            // 只等待自身服务配置传播，不等待全系统空闲，也不注入输入。
            Thread.sleep(250);
            Object root = get(automation, "getRootInActiveWindow");
            try {
                result.put("active_root", rootSummary(root));
            } finally {
                recycle(root);
            }
            result.put("windows", windows(automation));
            result.put("node_budget_exhausted", remaining == 0);
            result.put("status", "observed");
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", e.toString());
            e.printStackTrace(System.err);
            exitCode = 3;
        } finally {
            if (connected) {
                try { get(automation, "disconnect"); }
                catch (Exception e) { result.put("disconnect_error", e.toString()); exitCode = 3; }
            }
            if (thread != null) {
                try { get(thread, "quitSafely"); }
                catch (Exception e) { result.put("thread_error", e.toString()); exitCode = 3; }
            }
        }
        System.out.println(json(result));
        System.exit(exitCode);
    }
}
