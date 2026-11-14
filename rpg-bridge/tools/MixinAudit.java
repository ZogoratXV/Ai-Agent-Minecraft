import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.zip.ZipFile;

/**
 * Static check of Fabric API / mod mixins against Purpur's classes.
 *
 * <p>For every common/server mixin of every Fabric mod jar (nested jars included) it resolves the
 * target classes and, for each injector, the target methods and the {@code @At} INVOKE/FIELD/NEW
 * targets, then checks they exist in Purpur. Methods whose bytecode differs from vanilla are
 * flagged too (the mixin may still apply, but Paper changed the logic around it).
 *
 * <p>Usage: {@code java MixinAudit.java <vanilla-server.jar> <purpur.jar> <mods dir or jar>...}
 * Prints one line per finding: {@code STATUS mod mixin -> target#method : detail}.
 */
public final class MixinAudit {
	private static final List<String> INJECTORS = List.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
			"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

	private final Map<String, ClassNode> vanilla;
	private final Map<String, ClassNode> purpur;
	private final List<String> out = new ArrayList<>();

	private MixinAudit(Map<String, ClassNode> vanilla, Map<String, ClassNode> purpur) {
		this.vanilla = vanilla;
		this.purpur = purpur;
	}

	public static void main(String[] args) throws IOException {
		Map<String, ClassNode> vanilla = readGameJar(Path.of(args[0]));
		Map<String, ClassNode> purpur = readGameJar(Path.of(args[1]));
		MixinAudit audit = new MixinAudit(vanilla, purpur);
		for (int i = 2; i < args.length; i++) {
			Path p = Path.of(args[i]);
			if (Files.isDirectory(p)) {
				try (var s = Files.list(p)) {
					for (Path j : s.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) audit.modJar(Files.readAllBytes(j));
				}
			} else {
				audit.modJar(Files.readAllBytes(p));
			}
		}
		audit.out.stream().sorted().forEach(System.out::println);
	}

	/** Reads a server jar; for the vanilla bundler, reads the nested server jar. */
	private static Map<String, ClassNode> readGameJar(Path jar) throws IOException {
		Map<String, ClassNode> classes = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/versions/") && e.getName().endsWith(".jar")).findFirst();
			if (nested.isPresent()) {
				readJar(zip.getInputStream(nested.get()).readAllBytes(), classes);
				return classes;
			}
		}
		readJar(Files.readAllBytes(jar), classes);
		return classes;
	}

	private static void readJar(byte[] bytes, Map<String, ClassNode> classes) throws IOException {
		try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(bytes))) {
			JarEntry e;
			while ((e = in.getNextJarEntry()) != null) {
				if (!e.getName().endsWith(".class")) continue;
				if (!(e.getName().startsWith("net/minecraft/") || e.getName().startsWith("com/mojang/"))) continue;
				ClassNode node = new ClassNode();
				new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
				classes.put(node.name, node);
			}
		}
	}

	private void modJar(byte[] bytes) throws IOException {
		Map<String, byte[]> entries = new TreeMap<>();
		try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(bytes))) {
			JarEntry e;
			while ((e = in.getNextJarEntry()) != null) entries.put(e.getName(), in.readAllBytes());
		}
		for (var e : entries.entrySet()) {
			if (e.getKey().startsWith("META-INF/jars/") && e.getKey().endsWith(".jar")) modJar(e.getValue());
		}
		byte[] modJson = entries.get("fabric.mod.json");
		if (modJson == null) return;
		JsonObject mod = JsonParser.parseString(new String(modJson, StandardCharsets.UTF_8)).getAsJsonObject();
		String modId = mod.get("id").getAsString();
		if (!mod.has("mixins")) return;
		for (JsonElement m : mod.getAsJsonArray("mixins")) {
			String cfgName = m.isJsonPrimitive() ? m.getAsString() : m.getAsJsonObject().get("config").getAsString();
			if (!m.isJsonPrimitive() && m.getAsJsonObject().has("environment")
					&& m.getAsJsonObject().get("environment").getAsString().equals("client")) continue;
			byte[] cfgBytes = entries.get(cfgName);
			if (cfgBytes == null) continue;
			JsonObject cfg = JsonParser.parseString(new String(cfgBytes, StandardCharsets.UTF_8)).getAsJsonObject();
			String pkg = cfg.get("package").getAsString().replace('.', '/');
			for (String list : List.of("mixins", "server")) {
				if (!cfg.has(list)) continue;
				for (JsonElement name : cfg.getAsJsonArray(list)) {
					byte[] cls = entries.get(pkg + "/" + name.getAsString().replace('.', '/') + ".class");
					if (cls == null) {
						out.add("NOCLASS " + modId + " " + name.getAsString());
						continue;
					}
					ClassNode mixin = new ClassNode();
					new ClassReader(cls).accept(mixin, ClassReader.SKIP_CODE);
					mixin(modId, mixin);
				}
			}
		}
	}

	private void mixin(String modId, ClassNode mixin) {
		List<String> targets = new ArrayList<>();
		AnnotationNode ann = find(mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
		if (ann == null) return;
		for (Object v : values(ann, "value")) targets.add(((Type) v).getInternalName());
		for (Object v : values(ann, "targets")) targets.add(((String) v).replace('.', '/'));
		String label = modId + " " + mixin.name.substring(mixin.name.lastIndexOf('/') + 1);
		for (String target : targets) {
			ClassNode pc = purpur.get(target);
			if (pc == null) {
				if (target.startsWith("net/minecraft/") || target.startsWith("com/mojang/")) out.add("NOTARGET " + label + " -> " + target);
				continue;
			}
			ClassNode vc = vanilla.get(target);
			for (var f : mixin.fields) {
				if (find(f.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;") == null && find(f.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;") == null) continue;
				var pf = pc.fields.stream().filter(x -> x.name.equals(f.name)).findFirst();
				if (pf.isEmpty()) out.add("SHADOW " + label + " -> " + target + "." + f.name + " " + f.desc + " : missing in Purpur");
				else if (!pf.get().desc.equals(f.desc)) out.add("SHADOW " + label + " -> " + target + "." + f.name + " " + f.desc + " : Purpur has " + pf.get().desc);
			}
			for (MethodNode m : mixin.methods) {
				if (find(m.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;") != null || find(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;") != null) {
					if (find(pc, m.name, m.desc) == null && (vc == null || find(vc, m.name, m.desc) != null)) {
						var same = pc.methods.stream().filter(x -> x.name.equals(m.name)).map(x -> x.desc).toList();
						out.add("SHADOW " + label + " -> " + target + "#" + m.name + m.desc + " : Purpur has " + same);
					}
				}
			}
			for (MethodNode m : mixin.methods) {
				List<AnnotationNode> anns = new ArrayList<>();
				if (m.visibleAnnotations != null) anns.addAll(m.visibleAnnotations);
				if (m.invisibleAnnotations != null) anns.addAll(m.invisibleAnnotations);
				for (AnnotationNode a : anns) {
					if (INJECTORS.contains(a.desc)) {
						injector(label, target, pc, vc, a);
						if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) injectDescriptor(label, target, pc, a, m);
					}
					else if (a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Invoker;")) {
						Object v = value(a, "value");
						String name = v instanceof String str && !str.isEmpty() ? str : m.name.replaceFirst("^(call|invoke|new|create)", "");
						if (v == null) name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
						String desc = m.desc;
						String fname = name;
						boolean ok = pc.methods.stream().anyMatch(x -> x.name.equals(fname) && sameArgs(x.desc, desc));
						if (!ok) out.add("INVOKER " + label + " -> " + target + "#" + name + desc + " : Purpur has "
								+ pc.methods.stream().filter(x -> x.name.equals(fname)).map(x -> x.desc).toList());
					} else if (a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
						Object v = value(a, "value");
						String name = v instanceof String str && !str.isEmpty() ? str : m.name.replaceFirst("^(get|set|is)", "");
						if (v == null) name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
						String fname = name;
						if (pc.fields.stream().noneMatch(x -> x.name.equals(fname))) out.add("ACCESSOR " + label + " -> " + target + "." + name + " : missing in Purpur");
					}
					else if (a.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) method(label, target, pc, vc, m.name + m.desc, "overwrite");
				}
			}
		}
	}

	private void injector(String label, String target, ClassNode pc, ClassNode vc, AnnotationNode a) {
		List<String> selectors = new ArrayList<>();
		for (Object v : values(a, "method")) selectors.add((String) v);
		List<AnnotationNode> ats = new ArrayList<>();
		for (Object v : values(a, "at")) ats.add((AnnotationNode) v);
		Object single = value(a, "at");
		if (single instanceof AnnotationNode an && !ats.contains(an)) ats.add(an);
		for (String sel : selectors) {
			List<MethodNode> pms = resolve(pc, sel);
			if (pms.isEmpty()) {
				out.add("MISSING " + label + " -> " + target + "#" + sel);
				continue;
			}
			for (MethodNode pm : pms) {
				MethodNode vm = vc == null ? null : find(vc, pm.name, pm.desc);
				for (AnnotationNode at : ats) {
					String kind = (String) value(at, "value");
					String atTarget = (String) value(at, "target");
					if (atTarget == null || kind == null) continue;
					Object ord = value(at, "ordinal");
					int need = ord instanceof Integer o ? o + 1 : 1;
					if (contains(pm, kind, atTarget) && count(pm, kind, atTarget) < need) {
						out.add("ORDINAL " + label + " -> " + target + "#" + pm.name + pm.desc + " : @At(" + kind + ", " + atTarget + ", ordinal=" + (need - 1) + ") only "
								+ count(pm, kind, atTarget) + " in Purpur" + (vm != null ? ", " + count(vm, kind, atTarget) + " in vanilla" : ""));
					}
					if (!contains(pm, kind, atTarget)) {
						out.add("NOPOINT " + label + " -> " + target + "#" + pm.name + pm.desc + " : @At(" + kind + ", " + atTarget + ")"
								+ (vm != null && contains(vm, kind, atTarget) ? " [present in vanilla]" : " [also absent in vanilla]"));
					}
				}
				if (vm != null && !text(vm).equals(text(pm))) out.add("CHANGED " + label + " -> " + target + "#" + pm.name + pm.desc);
			}
		}
	}

	private void method(String label, String target, ClassNode pc, ClassNode vc, String nameDesc, String what) {
		int p = nameDesc.indexOf('(');
		MethodNode pm = find(pc, nameDesc.substring(0, p), nameDesc.substring(p));
		if (pm == null) out.add("MISSING " + label + " -> " + target + "#" + nameDesc + " (" + what + ")");
		else if (vc != null) {
			MethodNode vm = find(vc, pm.name, pm.desc);
			if (vm != null && !text(vm).equals(text(pm))) out.add("CHANGED " + label + " -> " + target + "#" + nameDesc + " (" + what + ")");
		}
	}

	private static List<MethodNode> resolve(ClassNode c, String selector) {
		String sel = selector;
		if (sel.startsWith("L") && sel.contains(";")) sel = sel.substring(sel.indexOf(';') + 1);
		String name = sel, desc = null;
		int p = sel.indexOf('(');
		if (p >= 0) {
			name = sel.substring(0, p);
			desc = sel.substring(p);
		}
		boolean wildcard = name.endsWith("*");
		List<MethodNode> res = new ArrayList<>();
		for (MethodNode m : c.methods) {
			boolean nameOk = wildcard ? m.name.startsWith(name.substring(0, name.length() - 1)) : m.name.equals(name);
			if (nameOk && (desc == null || m.desc.equals(desc))) res.add(m);
		}
		return res;
	}

	/** @Inject handler: arguments before CallbackInfo must be all target arguments, or none. */
	private void injectDescriptor(String label, String target, ClassNode pc, AnnotationNode a, MethodNode handler) {
		Type[] h = Type.getArgumentTypes(handler.desc);
		int ci = -1;
		for (int i = 0; i < h.length; i++) {
			if (h[i].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) { ci = i; break; }
		}
		if (ci <= 0) return;
		StringBuilder prefix = new StringBuilder("(");
		for (int i = 0; i < ci; i++) prefix.append(h[i].getDescriptor());
		for (Object v : values(a, "method")) {
			for (MethodNode pm : resolve(pc, (String) v)) {
				String args = pm.desc.substring(0, pm.desc.indexOf(')'));
				if (!args.equals(prefix.toString())) {
					out.add("DESCRIPTOR " + label + "#" + handler.name + " -> " + target + "#" + pm.name + pm.desc + " : handler captures " + prefix + ")");
				}
			}
		}
	}

	private static boolean sameArgs(String a, String b) {
		return a.substring(0, a.indexOf(')')).equals(b.substring(0, b.indexOf(')')));
	}

	private static int count(MethodNode m, String kind, String target) {
		int n = 0;
		if (m.instructions == null) return 0;
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode mi && (kind.startsWith("INVOKE")) && matches(target, mi.owner, mi.name, mi.desc)) n++;
			else if (insn instanceof FieldInsnNode fi && kind.equals("FIELD") && matches(target, fi.owner, fi.name, fi.desc)) n++;
		}
		return n;
	}

	private static boolean contains(MethodNode m, String kind, String target) {
		if (m.instructions == null) return false;
		for (AbstractInsnNode insn : m.instructions) {
			switch (kind) {
				case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
					if (insn instanceof MethodInsnNode mi && matches(target, mi.owner, mi.name, mi.desc)) return true;
				}
				case "FIELD" -> {
					if (insn instanceof FieldInsnNode fi && matches(target, fi.owner, fi.name, fi.desc)) return true;
				}
				case "NEW" -> {
					if (insn instanceof TypeInsnNode ti && (target.equals(ti.desc) || target.equals("L" + ti.desc + ";") || target.replace('.', '/').equals(ti.desc))) return true;
					if (insn instanceof MethodInsnNode mi && mi.name.equals("<init>") && matches(target, mi.owner, mi.name, mi.desc)) return true;
				}
				default -> {
					return true;
				}
			}
		}
		return false;
	}

	/** Target selectors: {@code Lowner;name(desc)}, {@code Lowner;name:desc}, or without owner/desc. */
	private static boolean matches(String target, String owner, String name, String desc) {
		String t = target;
		String tOwner = null;
		if (t.startsWith("L") && t.contains(";")) {
			tOwner = t.substring(1, t.indexOf(';'));
			t = t.substring(t.indexOf(';') + 1);
		}
		if (tOwner != null && !tOwner.equals(owner)) return false;
		String tName = t, tDesc = null;
		int p = t.indexOf('('), c = t.indexOf(':');
		if (p >= 0) {
			tName = t.substring(0, p);
			tDesc = t.substring(p);
		} else if (c >= 0) {
			tName = t.substring(0, c);
			tDesc = t.substring(c + 1);
		}
		return tName.equals(name) && (tDesc == null || tDesc.equals(desc));
	}

	private final Map<MethodNode, String> textCache = new HashMap<>();

	private String text(MethodNode m) {
		return textCache.computeIfAbsent(m, mm -> {
			Textifier t = new Textifier();
			TraceMethodVisitor v = new TraceMethodVisitor(t);
			for (AbstractInsnNode insn : mm.instructions) {
				if (insn.getType() == AbstractInsnNode.LINE || insn.getType() == AbstractInsnNode.FRAME || insn.getType() == AbstractInsnNode.LABEL) continue;
				insn.accept(v);
			}
			StringWriter sw = new StringWriter();
			t.print(new PrintWriter(sw));
			return sw.toString();
		});
	}

	private static MethodNode find(ClassNode c, String name, String desc) {
		for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static AnnotationNode find(List<AnnotationNode> anns, String desc) {
		if (anns == null) return null;
		for (AnnotationNode a : anns) if (a.desc.equals(desc)) return a;
		return null;
	}

	private static Object value(AnnotationNode a, String key) {
		if (a.values == null) return null;
		for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
		return null;
	}

	private static List<Object> values(AnnotationNode a, String key) {
		Object v = value(a, key);
		if (v == null) return List.of();
		if (v instanceof List<?> l) return new ArrayList<>(l);
		return List.of(v);
	}
}
