package com.fr0nick.vista_advanced_player.client;

import net.minecraft.world.level.block.entity.BlockEntity;

public class BlockEntityRenderContext {
    public static final ThreadLocal<BlockEntity> CURRENT = new ThreadLocal<>();
}