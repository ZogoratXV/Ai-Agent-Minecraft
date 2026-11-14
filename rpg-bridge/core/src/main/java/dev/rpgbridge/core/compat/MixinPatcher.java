/*
 * RPG Bridge - Fabric mods on Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 */
package dev.rpgbridge.core.compat;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disables single injectors of Fabric API / mod mixins whose injection point Paper changed,
 * keeping the rest of the mixin (interfaces, fields, other injectors) intact.
 *
 * <p>Entries in {@code rpgbridge-mixin-patches.properties}: {@code <mixin class>#<handler>=<reason>}.
 * The injector annotation is removed from the prepared mixin class node, so Mixin treats the
 * handler as a plain method. Replacements live in {@code dev.rpgbridge.core.mixin.replace}.
 */
public final class MixinPatcher {
	private static final Set<String> INJECTORS = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;", "Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
			"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
			// accessors: the patched method then has to be provided by an RPG Bridge mixin
			"Lorg/spongepowered/asm/mixin/gen/Invoker;", "Lorg/spongepowered/asm/mixin/gen/Accessor;");

	private MixinPatcher() {
	}

	public static Map<String, String> patches() {
		Map<String, String> map = new LinkedHashMap<>();
		try (InputStream in = MixinPatcher.class.getResourceAsStream("/rpgbridge-mixin-patches.properties")) {
			if (in == null) return map;
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#")) continue;
				int eq = line.indexOf('=');
				if (eq > 0) map.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return map;
	}

	/** @param mixins every prepared mixin (see {@link MixinBlocker}) */
	public static void apply(List<IMixinInfo> mixins) {
		Map<String, String> patches = patches();
		Set<String> done = new HashSet<>();
		for (IMixinInfo info : mixins) {
			for (ClassNode node : stateNodes(info)) {
			for (MethodNode method : node.methods) {
				String key = info.getClassName() + "#" + method.name;
				if (!patches.containsKey(key)) continue;
				String op = patches.get(key);
				if (op.startsWith("args+:") || op.startsWith("args^:")) {
					String oldDesc = method.desc;
					String types = op.substring(6).split("\\|")[0].strip();
					if (op.startsWith("args^:")) insertArgsAt(method, types, 0);
					else insertArgs(method, types);
					updateClassInfo(node.name, "methods", method.name, oldDesc, method.desc);
					done.add(key);
					continue;
				}
				if (method.visibleAnnotations != null) method.visibleAnnotations.removeIf(a -> INJECTORS.contains(a.desc));
				if (method.invisibleAnnotations != null) method.invisibleAnnotations.removeIf(a -> INJECTORS.contains(a.desc));
				done.add(key);
			}
			}
		}
		int extended = 0;
		for (IMixinInfo info : mixins) {
			for (ClassNode node : stateNodes(info)) extended += extendInjectsToPaperOverloads(info, node, patches.keySet());
		}
		if (extended > 0) System.out.println("[RPGBridge] Retargeted " + extended + " @Inject handlers to Paper's extended overloads");
		int retyped = 0;
		for (IMixinInfo info : mixins) {
			for (ClassNode node : stateNodes(info)) retyped += retypeShadowFields(info, node);
		}
		if (retyped > 0) System.out.println("[RPGBridge] Retyped " + retyped + " @Shadow fields to Paper's narrower types");
		for (String key : patches.keySet()) {
			if (!done.contains(key)) System.out.println("[RPGBridge] WARNING: mixin patch target not found: " + key);
		}
		System.out.println("[RPGBridge] Disabled " + done.size() + " incompatible mixin injectors");
	}

	/**
	 * Paper often keeps a vanilla method as a delegate and moves the body into an overload with extra
	 * trailing parameters (e.g. applyItemBlocking(level, source, amount) -> (level, source, amount, simulate)).
	 * A name-only @Inject then matches both and fails on the extended one, while Paper's own callers use the
	 * extended one. Retarget such handlers to the extended overload and append the extra parameters.
	 */
	private static int extendInjectsToPaperOverloads(IMixinInfo info, ClassNode mixin, Set<String> manual) {
		int n = 0;
		for (MethodNode m : mixin.methods) {
			if (manual.contains(info.getClassName() + "#" + m.name)) continue;
			AnnotationNode inject = find(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
			if (inject == null) continue;
			org.objectweb.asm.Type[] h = org.objectweb.asm.Type.getArgumentTypes(m.desc);
			int ci = -1;
			for (int i = 0; i < h.length; i++) {
				if (h[i].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
					ci = i;
					break;
				}
			}
			if (ci <= 0) continue; // captures no arguments: fits any overload
			StringBuilder captured = new StringBuilder("(");
			for (int i = 0; i < ci; i++) captured.append(h[i].getDescriptor());
			List<Object> values = annotationValues(inject, "method");
			if (values.isEmpty()) continue;
			List<String> selectors = new java.util.ArrayList<>();
			String extra = null;
			String newReturn = null;
			for (Object v : values) {
				String sel = (String) v;
				String name = sel.contains("(") ? sel.substring(0, sel.indexOf('(')) : sel;
				if (sel.startsWith("<") || name.contains("*") || name.startsWith("/")) { selectors.add(sel); continue; }
				String replacement = sel;
				for (String target : info.getTargetClasses()) {
					for (String desc : methodDescs(target.replace('.', '/'), name)) {
						String args = desc.substring(0, desc.indexOf(')'));
						if (args.length() > captured.length() && args.startsWith(captured.toString())) {
							String more = args.substring(captured.length());
							if (extra == null || extra.equals(more)) {
								extra = more;
								replacement = name + desc;
								newReturn = desc.substring(desc.indexOf(')') + 1);
							}
						}
					}
				}
				selectors.add(replacement);
			}
			if (extra == null) continue;
			setAnnotationValue(inject, "method", new java.util.ArrayList<Object>(selectors));
			String oldDesc = m.desc;
			insertArgs(m, extra);
			// Paper sometimes also gave a void method a return value: CallbackInfo -> CallbackInfoReturnable (a subclass).
			String ci0 = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
			if (newReturn != null && !newReturn.equals("V") && m.desc.contains(ci0)) {
				m.desc = m.desc.replace(ci0, "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;");
				if (m.localVariables != null) for (var lv : m.localVariables) if (lv.desc.equals(ci0)) {
					lv.desc = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
					lv.signature = null;
				}
			}
			updateClassInfo(mixin.name, "methods", m.name, oldDesc, m.desc);
			System.out.println("[RPGBridge] Retargeted " + info.getClassName() + "#" + m.name + " to " + selectors);
			n++;
		}
		return n;
	}

	private static final Map<String, List<String>> METHOD_CACHE = new java.util.HashMap<>();

	private static List<String> methodDescs(String internalName, String name) {
		return METHOD_CACHE.computeIfAbsent(internalName + "#" + name, k -> {
			List<String> descs = new java.util.ArrayList<>();
			try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(internalName + ".class")) {
				if (in == null) return descs;
				ClassNode c = new ClassNode();
				new org.objectweb.asm.ClassReader(in).accept(c, org.objectweb.asm.ClassReader.SKIP_CODE);
				for (MethodNode mm : c.methods) if (mm.name.equals(name)) descs.add(mm.desc);
			} catch (IOException ignored) {
			}
			return descs;
		});
	}

	private static AnnotationNode find(List<AnnotationNode> anns, String desc) {
		if (anns != null) for (AnnotationNode a : anns) if (a.desc.equals(desc)) return a;
		return null;
	}

	private static List<Object> annotationValues(AnnotationNode a, String key) {
		if (a.values != null) {
			for (int i = 0; i < a.values.size(); i += 2) {
				if (a.values.get(i).equals(key)) {
					Object v = a.values.get(i + 1);
					return v instanceof List<?> l ? new java.util.ArrayList<>(l) : List.of(v);
				}
			}
		}
		return List.of();
	}

	private static void setAnnotationValue(AnnotationNode a, String key, Object value) {
		for (int i = 0; i < a.values.size(); i += 2) {
			if (a.values.get(i).equals(key)) {
				a.values.set(i + 1, value);
				return;
			}
		}
		a.values.add(key);
		a.values.add(value);
	}

	/**
	 * Paper often appends parameters to vanilla methods (for Bukkit events). Handlers that capture the
	 * target's arguments then no longer match. Insert the extra parameter types right after the captured
	 * arguments (before the CallbackInfo for @Inject, at the end otherwise) and shift the handler's locals.
	 */
	private static void insertArgs(MethodNode m, String types) {
		org.objectweb.asm.Type[] params = org.objectweb.asm.Type.getArgumentTypes(m.desc);
		int at = params.length;
		for (int i = 0; i < params.length; i++) {
			String d = params[i].getDescriptor();
			if (d.equals("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;")
					|| d.equals("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;")) {
				at = i;
				break;
			}
		}
		insertArgsAt(m, types, at);
	}

	/** Inserts parameter types at position {@code at} (0 = before the first captured argument). */
	private static void insertArgsAt(MethodNode m, String types, int at) {
		org.objectweb.asm.Type[] params = org.objectweb.asm.Type.getArgumentTypes(m.desc);
		org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(m.desc);
		org.objectweb.asm.Type[] extra = org.objectweb.asm.Type.getArgumentTypes("(" + types + ")V");
		boolean isStatic = (m.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0;
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < at; i++) slot += params[i].getSize();
		int added = 0;
		for (org.objectweb.asm.Type t : extra) added += t.getSize();
		org.objectweb.asm.Type[] np = new org.objectweb.asm.Type[params.length + extra.length];
		System.arraycopy(params, 0, np, 0, at);
		System.arraycopy(extra, 0, np, at, extra.length);
		System.arraycopy(params, at, np, at + extra.length, params.length - at);
		m.desc = org.objectweb.asm.Type.getMethodDescriptor(ret, np);
		m.signature = null; // generic signature would still describe the old parameters
		if (m.instructions != null) {
			for (var insn : m.instructions) {
				if (insn instanceof org.objectweb.asm.tree.VarInsnNode v && v.var >= slot) v.var += added;
				else if (insn instanceof org.objectweb.asm.tree.IincInsnNode inc && inc.var >= slot) inc.var += added;
			}
		}
		if (m.localVariables != null) for (var lv : m.localVariables) if (lv.index >= slot) lv.index += added;
		m.maxLocals += added;
		m.visibleParameterAnnotations = shiftAnnotations(m.visibleParameterAnnotations, params.length, at, extra.length);
		m.invisibleParameterAnnotations = shiftAnnotations(m.invisibleParameterAnnotations, params.length, at, extra.length);
		if (m.visibleAnnotableParameterCount != 0) m.visibleAnnotableParameterCount = np.length;
		if (m.invisibleAnnotableParameterCount != 0) m.invisibleAnnotableParameterCount = np.length;
		if (m.parameters != null && m.parameters.size() == params.length) {
			for (int i = 0; i < extra.length; i++) m.parameters.add(at + i, new org.objectweb.asm.tree.ParameterNode("rpgbridge$extra" + i, 0));
		}
		System.out.println("[RPGBridge] Added Paper parameters (" + types + ") to mixin handler " + m.name);
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] shiftAnnotations(List<AnnotationNode>[] a, int oldLen, int at, int count) {
		if (a == null) return null;
		List<AnnotationNode>[] n = new List[Math.max(a.length, oldLen) + count];
		for (int i = 0; i < a.length; i++) n[i < at ? i : i + count] = a[i];
		return n;
	}

	/**
	 * Paper narrows some field types (e.g. {@code LevelChunk.level}: Level -> ServerLevel). A vanilla-typed
	 * {@code @Shadow} would not be found; rewrite it and the mixin's own accesses to Paper's type. Reads stay
	 * valid because the new type is a subtype; writes to such (final) fields do not happen in mixins.
	 */
	private static int retypeShadowFields(IMixinInfo info, ClassNode mixin) {
		int n = 0;
		for (org.objectweb.asm.tree.FieldNode f : mixin.fields) {
			if (!hasAnnotation(f.visibleAnnotations, f.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")) continue;
			for (String target : info.getTargetClasses()) {
				String paperDesc = fieldDesc(target.replace('.', '/'), f.name);
				if (paperDesc == null || paperDesc.equals(f.desc)) continue;
				if (!paperDesc.startsWith("L") || !f.desc.startsWith("L")) continue;
				String old = f.desc;
				f.desc = paperDesc;
				for (MethodNode m : mixin.methods) {
					if (m.instructions == null) continue;
					for (var insn : m.instructions) {
						if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fi && fi.name.equals(f.name) && fi.desc.equals(old)
								&& fi.owner.equals(mixin.name)) {
							fi.desc = paperDesc;
						}
					}
				}
				updateClassInfo(mixin.name, "fields", f.name, old, paperDesc);
				System.out.println("[RPGBridge] Retyped @Shadow " + info.getClassName() + "." + f.name + ": " + old + " -> " + paperDesc);
				n++;
				break;
			}
		}
		return n;
	}

	/** Mixin already built a ClassInfo for the mixin class: update its field member too. */
	private static void updateClassInfo(String mixinClass, String set, String name, String oldDesc, String newDesc) {
		org.spongepowered.asm.mixin.transformer.ClassInfo ci = org.spongepowered.asm.mixin.transformer.ClassInfo.fromCache(mixinClass);
		if (ci == null) return;
		try {
			java.lang.reflect.Field fieldsF = ci.getClass().getDeclaredField(set);
			fieldsF.setAccessible(true);
			Class<?> member = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo$Member");
			java.lang.reflect.Field memberName = member.getDeclaredField("memberName");
			java.lang.reflect.Field memberDesc = member.getDeclaredField("memberDesc");
			java.lang.reflect.Field currentDesc = member.getDeclaredField("currentDesc");
			memberName.setAccessible(true);
			memberDesc.setAccessible(true);
			currentDesc.setAccessible(true);
			for (Object fld : (java.util.Collection<?>) fieldsF.get(ci)) {
				if (name.equals(memberName.get(fld)) && oldDesc.equals(memberDesc.get(fld))) {
					memberDesc.set(fld, newDesc);
					if (oldDesc.equals(currentDesc.get(fld))) currentDesc.set(fld, newDesc);
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unsupported Mixin version", e);
		}
	}

	private static boolean hasAnnotation(List<AnnotationNode> a, List<AnnotationNode> b, String desc) {
		for (List<AnnotationNode> l : java.util.Arrays.asList(a, b)) {
			if (l != null) for (AnnotationNode an : l) if (an.desc.equals(desc)) return true;
		}
		return false;
	}

	/** Reads the field descriptor from the game class bytes without loading the class. */
	private static String fieldDesc(String internalName, String field) {
		try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(internalName + ".class")) {
			if (in == null) return null;
			ClassNode c = new ClassNode();
			new org.objectweb.asm.ClassReader(in).accept(c, org.objectweb.asm.ClassReader.SKIP_CODE);
			for (org.objectweb.asm.tree.FieldNode f : c.fields) if (f.name.equals(field)) return f.desc;
		} catch (IOException ignored) {
		}
		return null;
	}

	/** The class nodes Mixin applies from: MixinInfo.state/pendingState -> State.classNode (getClassNode() returns copies). */
	private static List<ClassNode> stateNodes(IMixinInfo info) {
		List<ClassNode> nodes = new java.util.ArrayList<>();
		for (String stateField : new String[] {"state", "pendingState"}) {
			try {
				java.lang.reflect.Field f = info.getClass().getDeclaredField(stateField);
				f.setAccessible(true);
				Object state = f.get(info);
				if (state == null) continue;
				java.lang.reflect.Field cn = state.getClass().getDeclaredField("classNode");
				cn.setAccessible(true);
				Object node = cn.get(state);
				if (node instanceof ClassNode c && !nodes.contains(c)) nodes.add(c);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Unsupported Mixin version", e);
			}
		}
		return nodes;
	}

	static boolean isInjector(AnnotationNode a) {
		return INJECTORS.contains(a.desc);
	}
}
