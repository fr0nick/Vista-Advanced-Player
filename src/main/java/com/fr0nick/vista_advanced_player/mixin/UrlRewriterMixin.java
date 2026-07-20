package com.fr0nick.vista_advanced_player.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.animenkin.vistaplus.urlcassette.UrlRewriter", remap = false)
public class UrlRewriterMixin {
    
    @Inject(method = "rewrite", at = @At("HEAD"), cancellable = true, require = 0)
    private static void advancedPlayer$allowAllUrls(String raw, CallbackInfoReturnable<Object> cir) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        
        try {
            Class<?> resultClass = Class.forName("com.animenkin.vistaplus.urlcassette.UrlRewriter$Result");
            java.lang.reflect.Method okMethod = resultClass.getMethod("ok", String.class);
            Object newResult = okMethod.invoke(null, raw.trim());
            
            cir.setReturnValue(newResult);
        } catch (Throwable ignored) {}
    }
}