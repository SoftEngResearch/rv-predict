package rvpredict.instrumentation;

import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import rvpredict.config.Config;

public class ClassTransformer extends ClassVisitor {

    private final Config config;

    private String className;
    private String source;

    private int version;

    private final Set<String> finalFields = new HashSet<>();

    public ClassTransformer(ClassVisitor cv, Config config) {
        super(Opcodes.ASM5, cv);
        assert cv != null;

        this.config = config;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
        className = name;
        this.version = version;
        MetaData.setSuperclass(name, superName);
        cv.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public void visitSource(String source, String debug) {
        this.source = source;
        cv.visitSource(source, debug);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature,
            Object value) {
        /* TODO(YilongL): add comments about what is special about `final`,
         * `volatile`, and `static` w.r.t. instrumentation */

        MetaData.addField(className, name);
        if ((access & Opcodes.ACC_FINAL) != 0) {
            finalFields.add(name);
        }
        if ((access & Opcodes.ACC_VOLATILE) != 0) {
            MetaData.addVolatileVariable(className, name);
        }

        return cv.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
            String[] exceptions) {
        MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);

        if (mv != null) {
            Type[] args = Type.getArgumentTypes(desc);
            int numOfWords = args.length;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == Type.DOUBLE_TYPE || args[i] == Type.LONG_TYPE)
                    numOfWords++;
            }

            mv = new MethodTransformer(mv, source, className, version, name, name
                    + desc, access, numOfWords, finalFields, config);
        }
        return mv;
    }
}