import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Minimal replacement for shadow's relocate(): rewrites every class under a directory so that
 * references to {@code from} point to {@code to}, and moves the relocated classes.
 * Usage: java Relocate.java <classesDir> <from/pkg/> <to/pkg/>
 */
public final class Relocate {
	public static void main(String[] args) throws IOException {
		Path root = Path.of(args[0]);
		String from = args[1], to = args[2];
		Remapper remapper = new Remapper() {
			@Override
			public String map(String name) {
				return name.startsWith(from) ? to + name.substring(from.length()) : name;
			}
		};
		List<Path> classes;
		try (Stream<Path> s = Files.walk(root)) {
			classes = s.filter(p -> p.toString().endsWith(".class")).toList();
		}
		for (Path p : classes) {
			ClassReader reader = new ClassReader(Files.readAllBytes(p));
			ClassWriter writer = new ClassWriter(0);
			reader.accept(new ClassRemapper(writer, remapper), 0);
			String rel = root.relativize(p).toString().replace('\\', '/');
			Path target = rel.startsWith(from) ? root.resolve(to + rel.substring(from.length())) : p;
			Files.createDirectories(target.getParent());
			Files.write(target, writer.toByteArray());
			if (!target.equals(p)) Files.delete(p);
		}
	}
}
