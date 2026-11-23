package dev.tessera.stubgen;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera, per ogni interfaccia elencata in {@code stubs.txt}, una classe concreta
 * {@code dev.tessera.stub.Stub<Nome>} che implementa tutti i metodi astratti.
 *
 * <p>Ogni metodo generato chiama {@code dev.tessera.api.Unsupported.hit(...)}: a seconda della
 * configurazione lancia {@link UnsupportedOperationException} oppure registra un avviso e
 * restituisce un valore neutro (null / 0 / false). Le implementazioni reali di Tessera estendono
 * gli stub e sovrascrivono solo i metodi che supportano davvero.
 *
 * <p>I tipi vengono scritti nella loro forma "erasure": un metodo con firma uguale all'erasure di
 * un metodo generico lo sovrascrive comunque (JLS 8.4.2), con un semplice avviso unchecked.
 */
public final class StubGenerator {

    private static final String PACKAGE = "dev.tessera.stub";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        List<String> names = Files.readAllLines(Path.of(args[1])).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        Path pkgDir = out.resolve(PACKAGE.replace('.', '/'));
        Files.createDirectories(pkgDir);
        for (String name : names) {
            Class<?> type = Class.forName(name, false, StubGenerator.class.getClassLoader());
            if (!type.isInterface()) {
                throw new IllegalArgumentException(name + " non è un'interfaccia");
            }
            String simple = stubName(type);
            Files.writeString(pkgDir.resolve(simple + ".java"), generate(type, simple));
        }
    }

    static String stubName(Class<?> type) {
        // org.bukkit.entity.Player -> StubPlayer ; org.bukkit.entity.Player.Spigot -> StubPlayer_Spigot
        String n = type.getName();
        n = n.substring(n.lastIndexOf('.') + 1).replace('$', '_');
        return "Stub" + n;
    }

    static String generate(Class<?> type, String simple) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("// GENERATO da tessera-stubgen: non modificare a mano.\n");
        sb.append("package ").append(PACKAGE).append(";\n\n");
        sb.append("@SuppressWarnings({\"unchecked\", \"rawtypes\", \"deprecation\", \"removal\"})\n");
        sb.append("public class ").append(simple).append(" implements ").append(typeName(type)).append(" {\n\n");
        sb.append("    protected String tesseraStubName() { return \"").append(type.getSimpleName()).append("\"; }\n\n");

        for (Method m : abstractMethods(type)) {
            sb.append("    @Override\n    public ");
            sb.append(typeName(m.getReturnType())).append(' ').append(m.getName()).append('(');
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeName(params[i])).append(" a").append(i);
            }
            sb.append(") {\n");
            sb.append("        dev.tessera.api.Unsupported.hit(tesseraStubName(), \"")
                    .append(signature(m)).append("\");\n");
            if (m.getReturnType() != void.class) {
                sb.append("        return ").append(defaultValue(m.getReturnType())).append(";\n");
            }
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** Metodi astratti effettivi dell'interfaccia (ereditati compresi), uno per firma erasa. */
    static List<Method> abstractMethods(Class<?> type) {
        Map<String, List<Method>> bySig = new LinkedHashMap<>();
        for (Method m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isBridge() || m.isSynthetic()) continue;
            bySig.computeIfAbsent(m.getName() + paramKey(m), k -> new ArrayList<>()).add(m);
        }
        List<Method> result = new ArrayList<>();
        for (List<Method> group : bySig.values()) {
            // Se esiste un default più specifico di tutte le dichiarazioni astratte, è già implementato.
            boolean covered = group.stream().anyMatch(d -> d.isDefault() && group.stream()
                    .allMatch(o -> o == d || o.getDeclaringClass().isAssignableFrom(d.getDeclaringClass())));
            if (covered) continue;
            // Tipo di ritorno più specifico (gestisce i ritorni covarianti).
            Method best = group.stream()
                    .filter(c -> group.stream().allMatch(o -> o.getReturnType().isAssignableFrom(c.getReturnType())))
                    .findFirst()
                    .orElse(group.getFirst());
            result.add(best);
        }
        result.sort(Comparator.comparing(Method::getName).thenComparing(StubGenerator::paramKey));
        return result;
    }

    static String paramKey(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) sb.append(p.getName()).append(';');
        return sb.append(')').toString();
    }

    static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    static String typeName(Class<?> c) {
        if (c.isArray()) return typeName(c.getComponentType()) + "[]";
        return c.getName().replace('$', '.');
    }

    static String defaultValue(Class<?> c) {
        if (!c.isPrimitive()) return "null";
        if (c == boolean.class) return "false";
        if (c == char.class) return "'\\0'";
        if (c == long.class) return "0L";
        if (c == float.class) return "0F";
        if (c == double.class) return "0D";
        if (c == int.class) return "0";
        return "(" + c.getName() + ") 0";
    }

    private StubGenerator() {
    }
}
