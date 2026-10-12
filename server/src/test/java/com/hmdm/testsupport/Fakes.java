package com.hmdm.testsupport;

import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.domain.UserRole;
import com.hmdm.persistence.domain.UserRolePermission;
import com.hmdm.rest.json.LookupItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Hand-rolled test doubles (Mockito 1.x cannot mock on JDK 17). */
public final class Fakes {

    private Fakes() {
    }

    /** One recorded call on a fake mapper. */
    public static final class Call {
        public final String method;
        public final Object[] args;

        Call(String method, Object[] args) {
            this.method = method;
            this.args = args;
        }

        @Override
        public String toString() {
            return method + Arrays.toString(args);
        }
    }

    /** A fake MyBatis mapper: answers by method name (defaults otherwise) and records every call. */
    public static final class FakeMapper<T> {
        public final T mapper;
        public final List<Call> calls = new ArrayList<>();
        private final Map<String, Function<Object[], Object>> answers = new HashMap<>();

        public FakeMapper(Class<T> type) {
            this.mapper = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            switch (m.getName()) {
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == args[0];
                                default: return "Fake" + type.getSimpleName();
                            }
                        }
                        Object[] a = args == null ? new Object[0] : args;
                        calls.add(new Call(m.getName(), a));
                        Function<Object[], Object> f = answers.get(m.getName());
                        return f != null ? f.apply(a) : defaultValue(m.getReturnType());
                    }));
        }

        public FakeMapper<T> answer(String method, Function<Object[], Object> answer) {
            answers.put(method, answer);
            return this;
        }

        public List<Call> callsTo(String method) {
            return calls.stream().filter(c -> c.method.equals(method)).collect(Collectors.toList());
        }

        /** Calls whose method name starts with any of the prefixes (e.g. "update", "delete", "insert"). */
        public List<Call> callsStartingWith(String... prefixes) {
            return calls.stream()
                    .filter(c -> Arrays.stream(prefixes).anyMatch(c.method::startsWith))
                    .collect(Collectors.toList());
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == List.class) return new ArrayList<>();
        if (t == Set.class) return new HashSet<>();
        if (t == Long.class) return 0L;
        return null;
    }

    /** The SQL text of a mapper method's MyBatis annotation (all overloads joined); "" when there is none. */
    public static String sqlOf(Class<?> mapper, String method) {
        StringBuilder sb = new StringBuilder();
        for (Method m : mapper.getMethods()) {
            if (!m.getName().equals(method)) continue;
            String[] v = null;
            if (m.getAnnotation(Select.class) != null) v = m.getAnnotation(Select.class).value();
            if (m.getAnnotation(Update.class) != null) v = m.getAnnotation(Update.class).value();
            if (m.getAnnotation(Delete.class) != null) v = m.getAnnotation(Delete.class).value();
            if (m.getAnnotation(Insert.class) != null) v = m.getAnnotation(Insert.class).value();
            if (v != null) sb.append(String.join(" ", v)).append('\n');
        }
        return sb.toString();
    }

    /** The WHERE part of a statement, lower-cased with collapsed whitespace. */
    public static String whereOf(String sql) {
        String s = sql.replaceAll("\\s+", " ").toLowerCase();
        int i = s.indexOf(" where ");
        return i < 0 ? "" : s.substring(i);
    }

    public static User user(int id, int customerId, String... permissions) {
        User u = new User();
        u.setId(id);
        u.setLogin("user" + id);
        u.setCustomerId(customerId);
        UserRole role = new UserRole();
        role.setId(2);
        role.setName("role");
        List<UserRolePermission> perms = new ArrayList<>();
        for (String p : permissions) {
            UserRolePermission perm = new UserRolePermission();
            perm.setName(p);
            perms.add(perm);
        }
        role.setPermissions(perms);
        u.setUserRole(role);
        u.setAllDevicesAvailable(true);
        u.setAllConfigAvailable(true);
        u.setGroups(new ArrayList<>());
        u.setConfigurations(new ArrayList<>());
        return u;
    }

    public static User superAdmin(int id, int customerId) {
        User u = user(id, customerId);
        u.getUserRole().setSuperAdmin(true);
        return u;
    }

    public static User limitedToConfigurations(User u, int... configurationIds) {
        u.setAllConfigAvailable(false);
        List<LookupItem> items = new ArrayList<>();
        for (int id : configurationIds) items.add(new LookupItem(id, "c" + id));
        u.setConfigurations(items);
        return u;
    }

    public static <E> List<E> list(E... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    public static List<Object> empty() {
        return Collections.emptyList();
    }
}
