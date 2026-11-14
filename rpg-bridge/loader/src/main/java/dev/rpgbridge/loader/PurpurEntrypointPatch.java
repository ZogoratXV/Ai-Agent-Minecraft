/*
 * RPG Bridge - Fabric Loader game provider for Purpur.
 * Copyright (C) 2026 RPG Bridge contributors. Licensed under the GNU GPL v3 (see LICENSE).
 * Same hook points as Fabric Loader's EntrypointPatch (Apache-2.0) for 20w22a+ servers.
 */
package dev.rpgbridge.loader;

import net.fabricmc.loader.impl.game.minecraft.Hooks;
import net.fabricmc.loader.impl.game.patch.GamePatch;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Initialises Fabric mods in Paper's {@code net.minecraft.server.Main#main(OptionSet)}:
 * {@code Hooks.startServer} right before {@code server.properties} is read (after the vanilla
 * bootstrap, exactly like on a Fabric server), and {@code Hooks.setGameInstance} with the
 * {@code DedicatedServer} created inside {@code MinecraftServer.spin(...)}.
 */
final class PurpurEntrypointPatch extends GamePatch {
	static final String MAIN = "net.minecraft.server.Main";
	private static final String HOOKS = Hooks.INTERNAL_NAME;
	private static final String DEDICATED_SERVER = "net/minecraft/server/dedicated/DedicatedServer";

	@Override
	public void process(FabricLauncher launcher, Function<String, ClassNode> classSource, Consumer<ClassNode> classEmitter) {
		ClassNode main = classSource.apply(MAIN);
		if (main == null) throw new RuntimeException("Could not load " + MAIN);

		MethodNode mainMethod = findMethod(main, m -> m.name.equals("main") && m.desc.equals("(Ljoptsimple/OptionSet;)V"));
		if (mainMethod == null) throw new RuntimeException("Could not find main(OptionSet) in " + MAIN);

		AbstractInsnNode props = findInsn(mainMethod, i -> i instanceof LdcInsnNode ldc && "server.properties".equals(ldc.cst), false);
		if (props == null) throw new RuntimeException("Could not find server.properties in " + MAIN + ".main");
		InsnList start = new InsnList();
		start.add(new TypeInsnNode(Opcodes.NEW, "java/io/File"));
		start.add(new InsnNode(Opcodes.DUP));
		start.add(new LdcInsnNode("."));
		start.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false));
		start.add(new InsnNode(Opcodes.ACONST_NULL));
		start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "startServer", "(Ljava/io/File;Ljava/lang/Object;)V", false));
		mainMethod.instructions.insertBefore(props, start);

		boolean captured = false;
		for (MethodNode m : main.methods) {
			if ((m.access & Opcodes.ACC_SYNTHETIC) == 0) continue;
			if (!Type.getReturnType(m.desc).getInternalName().equals(DEDICATED_SERVER)) continue;
			AbstractInsnNode ctor = findInsn(m, i -> i instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL
					&& mi.owner.equals(DEDICATED_SERVER) && mi.name.equals("<init>"), false);
			if (ctor == null) continue;
			InsnList set = new InsnList();
			set.add(new InsnNode(Opcodes.DUP));
			set.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "setGameInstance", "(Ljava/lang/Object;)V", false));
			m.instructions.insert(ctor, set);
			captured = true;
		}
		if (!captured) throw new RuntimeException("Could not find the DedicatedServer constructor in " + MAIN);

		classEmitter.accept(main);
	}
}
