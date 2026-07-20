package com.fr0nick.vista_advanced_player.client;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.Method;

public class SableHelper {
    private static Object instance;
    private static Method projectMethod;
    private static boolean init = false;

    public static Vec3 project(Level level, Vec3 pos) {
        if (!init) {
            init = true;
            try {
                Class<?> clazz = Class.forName("dev.ryanhcode.sable.companion.SableCompanion");
                instance = clazz.getField("INSTANCE").get(null);
                projectMethod = clazz.getMethod("projectOutOfSubLevel", Level.class, Vec3.class);
            } catch (Throwable ignored) {}
        }
        if (projectMethod != null && instance != null && level != null) {
            try { return (Vec3) projectMethod.invoke(instance, level, pos); } catch (Throwable ignored) {}
        }
        return pos;
    }
}