package com.xingshulin.singularity.utils

import jdk.internal.org.objectweb.asm.*
import org.apache.commons.codec.digest.DigestUtils

class ClassUtil {
    static byte[] referHackWhenInit(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes)
        ClassWriter writer = new ClassWriter(reader, 0)
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM4, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {

                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                mv = new MethodVisitor(Opcodes.ASM4, mv) {
                    @Override
                    void visitInsn(int opcode) {
                        if ("<init>".equals(name) && opcode == Opcodes.RETURN) {
                            super.visitLdcInsn(Type.getType("Lcom/xingshulin/singularity/Hack;"));
                        }
                        super.visitInsn(opcode);
                    }
                }
                return mv;
            }
        };
        reader.accept(visitor, 0)
        return writer.toByteArray()
    }

    static byte[] patchClass(File inputFile) {
        def optClass = new File(inputFile.getParent(), inputFile.getName() + '.opt')
        FileOutputStream outputStream = new FileOutputStream(optClass)

        FileInputStream inputStream = new FileInputStream(inputFile)
        def bytes = referHackWhenInit(inputStream.bytes)
        outputStream.write(bytes)
        outputStream.close()
        inputStream.close()
        inputFile.delete()
        optClass.renameTo(inputFile)
        return bytes
    }

    static String guessFileName(File rootDir, File classFile) {
        String fullClassName = classFile.absolutePath.substring(rootDir.absolutePath.length())
        return fullClassName.substring(1)
    }
}
